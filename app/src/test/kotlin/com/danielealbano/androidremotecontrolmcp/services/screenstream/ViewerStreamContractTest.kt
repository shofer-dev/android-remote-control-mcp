package com.danielealbano.androidremotecontrolmcp.services.screenstream

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pins the FROZEN browser-leg framing, byte for byte.
 *
 * These assertions matter more than their size suggests. The console's viewer is shared with two
 * other device classes and is not going to be changed to accommodate this one, so every fact it
 * relies on — the 14-byte magic, the int32s at offsets 86 and 90, one NAL per message behind a
 * four-byte start code — is a contract this app must keep. Each of them also fails SILENTLY when
 * broken: a mis-sized initial block leaves the viewer with no coordinate space and it swallows every
 * tap, and an unsplit buffer leaves the decoder configured by an SPS whose PPS it never saw. There
 * is no error anywhere in either case, just a black rectangle.
 */
class ViewerStreamContractTest {
    private fun int32At(
        data: ByteArray,
        offset: Int,
    ): Int = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).getInt(offset)

    @Test
    fun `the initial block carries the real display size where the viewer reads it`() {
        val block = ViewerStreamContract.initialBlock("Pixel 7", 1080, 2400)

        assertEquals(ViewerStreamContract.INITIAL_BLOCK_BYTES, block.size)
        assertArrayEquals(
            ViewerStreamContract.INITIAL_MAGIC,
            block.copyOfRange(0, ViewerStreamContract.INITIAL_MAGIC.size),
        )
        assertEquals(1080, int32At(block, 86))
        assertEquals(2400, int32At(block, 90))
    }

    @Test
    fun `the initial block is long enough to be classified as metadata`() {
        // The viewer only treats a message as metadata when it is STRICTLY longer than the magic,
        // and only reads the size fields at 94 bytes or more. A shorter block would be decoded as
        // video, fail to parse as a NAL, and vanish.
        val block = ViewerStreamContract.initialBlock("Pixel 7", 1080, 2400)
        assertTrue(block.size > ViewerStreamContract.INITIAL_MAGIC.size)
        assertTrue(block.size >= 94)
    }

    @Test
    fun `an over-long device name is truncated rather than overflowing the struct`() {
        val block = ViewerStreamContract.initialBlock("x".repeat(500), 720, 1280)
        assertEquals(ViewerStreamContract.INITIAL_BLOCK_BYTES, block.size)
        assertEquals(720, int32At(block, 86))
        assertEquals(1280, int32At(block, 90))
    }

    @Test
    fun `a codec-config buffer holding SPS and PPS is split into two messages`() {
        // This is the exact shape MediaCodec delivers as BUFFER_FLAG_CODEC_CONFIG, and the reason
        // the split exists: forwarded whole, the viewer would type the message by its fifth byte,
        // see an SPS, and never learn the PPS that follows it.
        val buffer =
            byteArrayOf(
                0,
                0,
                0,
                1,
                0x67,
                0x42,
                0x00,
                0,
                0,
                0,
                1,
                0x68.toByte(),
                0xCE.toByte(),
            )

        val units = ViewerStreamContract.splitAnnexB(buffer)

        assertEquals(2, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00), units[0])
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x68.toByte(), 0xCE.toByte()), units[1])
    }

    @Test
    fun `a three-byte start code is normalised to the four-byte form the viewer requires`() {
        // Three-byte codes are legal in an elementary stream and illegal on this wire: the viewer
        // reads the NAL type from data[4], so a three-byte message types itself by the first
        // payload byte instead.
        val units = ViewerStreamContract.splitAnnexB(byteArrayOf(0, 0, 1, 0x65, 0x11))
        assertEquals(1, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11), units[0])
    }

    @Test
    fun `every emitted unit types itself at byte four`() {
        val units = ViewerStreamContract.splitAnnexB(byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x65))
        units.forEach { unit ->
            assertTrue(unit.size >= 5)
            assertArrayEquals(byteArrayOf(0, 0, 0, 1), unit.copyOfRange(0, 4))
        }
        assertEquals(listOf(7, 5), units.map { it[4].toInt() and 0x1F })
    }

    @Test
    fun `leading bytes before the first start code are discarded`() {
        val units = ViewerStreamContract.splitAnnexB(byteArrayOf(0x11, 0x22, 0, 0, 0, 1, 0x65))
        assertEquals(1, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), units[0])
    }

    @Test
    fun `a buffer with no start code yields nothing rather than an untypable message`() {
        assertTrue(ViewerStreamContract.splitAnnexB(byteArrayOf(1, 2, 3, 4, 5)).isEmpty())
        assertTrue(ViewerStreamContract.splitAnnexB(ByteArray(0)).isEmpty())
    }

    @Test
    fun `back-to-back start codes produce no empty message`() {
        val units = ViewerStreamContract.splitAnnexB(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 0x65))
        assertEquals(1, units.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65), units[0])
    }
}
