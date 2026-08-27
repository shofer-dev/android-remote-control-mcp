package com.danielealbano.androidremotecontrolmcp.services.connector

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds camera frames to [QrPairingDecoder] and reports the FIRST pairing link it sees, once.
 *
 * The latch is the point. An analyser fires many times a second and a QR code stays in frame for as
 * long as the holder keeps the phone still, so without it a single code would submit the same
 * one-time enrolment code repeatedly — the platform would refuse every attempt after the first as
 * already-spent, and the holder would be told their brand-new code was used up.
 *
 * [onScanned] is invoked on the analysis thread. A caller that touches UI state must hop to the
 * main thread itself; that is left to the caller rather than done here so this class needs no
 * Looper and no context.
 */
class PairingQrAnalyzer(
    private val onScanned: (PairingInput.Pairing) -> Unit,
) : ImageAnalysis.Analyzer {
    private val delivered = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (delivered.get()) return
            val pairing = decode(image) ?: return
            if (delivered.compareAndSet(false, true)) {
                onScanned(pairing)
            }
        } finally {
            // The analyser stalls permanently on an unclosed frame, so this happens on every path
            // including the ones that found nothing.
            image.close()
        }
    }

    /**
     * Extracts the frame's greyscale plane and decodes it.
     *
     * The Y plane of a YUV_420_888 image is the luminance the decoder wants directly. A plane whose
     * pixel stride is not 1 is not that — the samples would be interleaved with something else — so
     * it is skipped rather than misread. The buffer is copied into an array sized `rowStride *
     * height` because a camera may under-fill the final row, and the decoder reads the full
     * rectangle.
     */
    private fun decode(image: ImageProxy): PairingInput.Pairing? {
        val plane = image.planes.firstOrNull()
        if (plane == null || plane.pixelStride != 1) return null
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val luminance = ByteArray(rowStride * image.height)
        buffer.get(luminance, 0, minOf(buffer.remaining(), luminance.size))
        return QrPairingDecoder.decode(luminance, rowStride, image.width, image.height)
    }
}
