package com.danielealbano.androidremotecontrolmcp.services.connector

import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads the console's pairing link out of one camera frame.
 *
 * It takes the raw LUMINANCE plane rather than a bitmap, because that is what the camera already
 * produces: a YUV frame's first plane IS the greyscale image a QR decoder wants, so nothing has to
 * be converted, copied through an ARGB buffer, or rotated (a QR code's finder patterns are found at
 * any orientation).
 *
 * The decode is deliberately narrowed to [BarcodeFormat.QR_CODE]. Everything else a camera might
 * see — a barcode on a parcel, a URL QR on a poster — should read as "keep looking", not as a
 * pairing attempt with nonsense in it, and the same is true one level up: a QR that decodes
 * perfectly but is not a `justceo-enrol:` link returns null here, so the scanner goes on scanning
 * instead of failing at something the holder never pointed it at.
 *
 * ZXing's `core` artifact is pure Java with no Android, AWT or Play Services dependency, which is
 * what makes this identical in the foss and gms flavors — and what makes the whole path above the
 * camera unit-testable, since the same artifact can WRITE a QR code to feed back in.
 */
object QrPairingDecoder {
    private const val TAG = "MCP:QrPairing"

    private val HINTS =
        mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            // A phone held by hand over a screen: worth the extra passes, and this runs on a
            // dedicated analysis thread with the newest frame only.
            DecodeHintType.TRY_HARDER to true,
        )

    /**
     * Decodes one greyscale frame, returning the pairing it carries or null when it carries none.
     *
     * [rowStride] is the distance between the starts of two rows in [luminance], which a camera is
     * free to make larger than [width] for alignment; passing it through as the source's data width
     * and cropping to [width] × [height] is what lets a padded frame be read without copying it.
     * [luminance] must therefore hold at least `rowStride * height` bytes.
     */
    fun decode(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): PairingInput.Pairing? {
        if (!isReadableFrame(luminance, rowStride, width, height)) return null
        val source =
            PlanarYUVLuminanceSource(
                luminance,
                rowStride,
                height,
                0,
                0,
                width,
                height,
                false,
            )
        val text =
            try {
                QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
            } catch (e: ReaderException) {
                // Not a failure: a frame with no readable code in it is the ordinary case, and
                // there are tens of them a second. Verbose so it is available when a real handset
                // will not scan, and silent otherwise.
                Log.v(TAG, "No readable QR code in this frame", e)
                null
            }
        return text?.let { PairingInput.parsePairingUri(it) }
    }

    /**
     * Whether the frame's dimensions describe a rectangle that is actually inside [luminance].
     * Checked here rather than trusted, because the decoder reads the full `rowStride * height`
     * rectangle and a short buffer would fail as an index-out-of-bounds inside ZXing.
     */
    private fun isReadableFrame(
        luminance: ByteArray,
        rowStride: Int,
        width: Int,
        height: Int,
    ): Boolean {
        val hasArea = width > 0 && height > 0
        val strideCovers = rowStride >= width
        return hasArea && strideCovers && luminance.size >= rowStride * height
    }
}
