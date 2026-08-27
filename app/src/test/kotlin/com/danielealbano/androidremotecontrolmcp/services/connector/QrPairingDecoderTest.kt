package com.danielealbano.androidremotecontrolmcp.services.connector

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The scanner's decoding path, end to end, WITHOUT a camera.
 *
 * ZXing's `core` artifact writes QR codes as well as reading them, so a test can produce exactly
 * what the operator console renders and feed it back in as a greyscale frame. What that covers is
 * everything between the camera and the ViewModel: the luminance layout the decoder is handed, the
 * row-stride padding a real frame arrives with, and the rule that a QR code which is not a pairing
 * link is a miss rather than a bad pairing.
 */
@DisplayName("QrPairingDecoder")
class QrPairingDecoderTest {
    @Test
    fun `a pairing link rendered as a QR code is read back`() {
        val frame = qrFrame("justceo-enrol:v1?host=devices.justceo.ai&code=PAIR-4KJ2")

        val pairing = QrPairingDecoder.decode(frame.luminance, frame.width, frame.width, frame.height)

        assertEquals("devices.justceo.ai", pairing?.edgeHost)
        assertEquals("PAIR-4KJ2", pairing?.code)
    }

    @Test
    fun `a padded frame decodes, because a camera row is wider than the image`() {
        // A camera's Y plane is free to pad each row for alignment; the decoder is told the stride
        // and crops, so the padding must not shift the image.
        val frame = qrFrame("justceo-enrol:v1?host=devices.justceo.ai&code=PAIR-4KJ2")
        val stride = frame.width + PAD_BYTES
        val padded = ByteArray(stride * frame.height) { WHITE }
        for (row in 0 until frame.height) {
            frame.luminance.copyInto(
                destination = padded,
                destinationOffset = row * stride,
                startIndex = row * frame.width,
                endIndex = (row + 1) * frame.width,
            )
        }

        val pairing = QrPairingDecoder.decode(padded, stride, frame.width, frame.height)

        assertEquals("devices.justceo.ai", pairing?.edgeHost)
    }

    @Test
    fun `a QR code that is not a pairing link is a miss, not a pairing`() {
        val frame = qrFrame("https://example.com/some/other/qr")

        assertNull(QrPairingDecoder.decode(frame.luminance, frame.width, frame.width, frame.height))
    }

    @Test
    fun `a frame with no code in it decodes to nothing`() {
        val blank = ByteArray(QR_SIZE * QR_SIZE) { WHITE }

        assertNull(QrPairingDecoder.decode(blank, QR_SIZE, QR_SIZE, QR_SIZE))
    }

    @Test
    fun `a frame smaller than its own stride claims is refused rather than read out of bounds`() {
        val tooSmall = ByteArray(QR_SIZE)

        assertNull(QrPairingDecoder.decode(tooSmall, QR_SIZE, QR_SIZE, QR_SIZE))
    }

    @Test
    fun `a zero-sized frame is refused`() {
        assertNull(QrPairingDecoder.decode(ByteArray(0), 0, 0, 0))
    }

    private data class Frame(
        val luminance: ByteArray,
        val width: Int,
        val height: Int,
    )

    /** Renders [text] as a QR code and returns it as the greyscale plane a camera would produce. */
    private fun qrFrame(text: String): Frame {
        val matrix: BitMatrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(EncodeHintType.MARGIN to QR_MARGIN),
            )
        val luminance =
            ByteArray(matrix.width * matrix.height) { index ->
                if (matrix.get(index % matrix.width, index / matrix.width)) BLACK else WHITE
            }
        return Frame(luminance, matrix.width, matrix.height)
    }

    private companion object {
        const val QR_SIZE = 240
        const val QR_MARGIN = 4
        const val PAD_BYTES = 16
        const val BLACK: Byte = 0
        val WHITE: Byte = 0xFF.toByte()
    }
}
