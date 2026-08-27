package com.danielealbano.androidremotecontrolmcp.services.screenstream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the viewer's quality control message and the sizing rule it feeds.
 *
 * Both halves reach a hardware encoder, and both fail in ways that are hard to attribute: a
 * mis-parsed field configures the codec at a value the OEM refuses (so the stream never starts, on
 * that phone model only), and a sizing bug produces a picture whose aspect ratio does not match the
 * size the console maps taps through (so the picture looks fine and every touch lands somewhere
 * else).
 */
class StreamParametersTest {
    /**
     * The viewer's 36-byte `CHANGE_STREAM_PARAMETERS` message, built from the fields this backend
     * reads. The remaining bytes (crop, frame-meta, display id, the two length-prefixed strings)
     * stay zero, which is what the viewer sends for them.
     */
    private data class Control(
        val bitrate: Int = 4_000_000,
        val maxFps: Int = 24,
        val iFrameInterval: Int = 5,
        val boundWidth: Int = 1280,
        val boundHeight: Int = 1280,
        val type: Int = StreamParameters.TYPE_CHANGE_STREAM_PARAMETERS,
    ) {
        fun encode(): ByteArray {
            val bytes = ByteArray(StreamParameters.MESSAGE_BYTES)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buffer.put(0, type.toByte())
            buffer.putInt(1, bitrate)
            buffer.putInt(5, maxFps)
            buffer.put(9, iFrameInterval.toByte())
            buffer.putShort(10, boundWidth.toShort())
            buffer.putShort(12, boundHeight.toShort())
            buffer.put(23, (-1).toByte())
            return bytes
        }
    }

    @Test
    fun `the viewer's message is read field for field`() {
        val parsed =
            StreamParameters.parse(
                Control(bitrate = 2_000_000, maxFps = 30, boundWidth = 800, boundHeight = 800).encode(),
            )

        requireNotNull(parsed)
        assertEquals(2_000_000, parsed.bitrate)
        assertEquals(30, parsed.maxFps)
        assertEquals(5, parsed.iFrameIntervalSeconds)
        assertEquals(800, parsed.boundWidth)
        assertEquals(800, parsed.boundHeight)
    }

    @Test
    fun `anything that is not the viewer's message leaves the stream alone`() {
        // A running stream must survive an unrecognised control rather than end on it: this socket
        // may one day carry a second verb, and a truncated message is a transport hiccup, not a
        // reason to black out an operator's screen.
        assertNull(StreamParameters.parse(ByteArray(0)))
        assertNull(StreamParameters.parse(ByteArray(StreamParameters.MESSAGE_BYTES - 1)))
        assertNull(StreamParameters.parse(Control(type = 1).encode()))
    }

    @Test
    fun `absurd values are clamped rather than handed to the encoder`() {
        val parsed = StreamParameters.parse(Control(bitrate = Int.MAX_VALUE, maxFps = 10_000).encode())

        requireNotNull(parsed)
        assertEquals(20_000_000, parsed.bitrate)
        assertEquals(60, parsed.maxFps)
    }

    @Test
    fun `a zero bound means native and yields the display's own size`() {
        val parsed = StreamParameters.parse(Control(boundWidth = 0, boundHeight = 0).encode())

        requireNotNull(parsed)
        assertEquals(0, parsed.boundWidth)
        assertEquals(1080 to 2400, parsed.encoderSize(1080, 2400))
    }

    @Test
    fun `a bound box preserves the display's aspect ratio`() {
        // The console maps every tap through the REAL size it was told, so a stretched picture puts
        // every touch in the wrong place while looking perfectly healthy.
        val params = StreamParameters.DEFAULT.copy(boundWidth = 800, boundHeight = 800)
        val (width, height) = params.encoderSize(1080, 2400)

        assertEquals(360, width)
        assertEquals(800, height)
    }

    @Test
    fun `a capture is never scaled up past the display`() {
        val params = StreamParameters.DEFAULT.copy(boundWidth = 4000, boundHeight = 4000)
        assertEquals(1080 to 2400, params.encoderSize(1080, 2400))
    }

    @Test
    fun `both dimensions come out even`() {
        // H.264's 4:2:0 chroma sampling requires it, and several OEM encoders enforce it by
        // refusing to configure at all — which reads as "streaming does not work on that phone".
        val params = StreamParameters.DEFAULT.copy(boundWidth = 501, boundHeight = 4000)
        val (width, height) = params.encoderSize(1001, 2001)

        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
    }

    @Test
    fun `a display with no reported size yields no encoder size`() {
        assertEquals(0 to 0, StreamParameters.DEFAULT.encoderSize(0, 0))
    }
}
