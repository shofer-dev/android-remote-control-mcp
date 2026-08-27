package com.danielealbano.androidremotecontrolmcp.services.screenstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The FROZEN browser-leg framing every screen-stream backend on this platform emits.
 *
 * The console's drive viewer already decodes a live H.264 stream from a WebSocket for the two
 * adb-reachable device classes, where a `scrcpy` server produces the bytes. This app is the third
 * backend — a physical phone capturing its own screen — and it produces the SAME bytes on purpose.
 * Matching the contract exactly is what lets one viewer, one decode-queue backpressure policy and
 * one server-side session-recording tee serve all three classes; a private framing here would mean
 * reimplementing all three for no gain, because the payload is identical H.264 either way.
 *
 * The contract, as the viewer parses it (`user-console/frontend/src/components/AndroidDriveViewer.tsx`):
 *
 * 1. Every message is BINARY. A message LONGER than 14 bytes whose first 14 bytes are the ASCII
 *    [INITIAL_MAGIC] is the initial metadata block; everything else is video.
 * 2. The initial block is a fixed binary struct, big-endian, and the viewer reads exactly two
 *    fields out of it: an int32 width at byte offset 86 and an int32 height at offset 90. It
 *    requires at least [INITIAL_BLOCK_BYTES] bytes before it will read either.
 * 3. Every video message is exactly ONE Annex-B NAL unit beginning with the four-byte start code
 *    `00 00 00 01`. The viewer reads the NAL type from `data[4] and 0x1F`, so a three-byte start
 *    code or two NALs in one message mis-types the unit and the picture never appears.
 *
 * The device's REAL pixel size in the initial block is not decoration: it is the coordinate space
 * every tap is mapped through. A stream that paints but reports the wrong size sends every touch to
 * the wrong place, and a stream that never sends the block swallows touches entirely — which is why
 * it is written before the first frame rather than alongside it.
 *
 * The magic string still says "scrcpy" because it is a wire constant the viewer matches on, not a
 * claim about what produced the bytes. Renaming it would break every deployed viewer to gain a
 * tidier spelling in one file.
 */
object ViewerStreamContract {
    /** The 14-byte ASCII prefix that marks a metadata message rather than video. */
    val INITIAL_MAGIC: ByteArray = "scrcpy_initial".toByteArray(Charsets.US_ASCII)

    /**
     * The minimum length of the initial block. The viewer refuses to read the size fields from
     * anything shorter, so a block that is merely long enough to hold them at 86/90 (94 bytes) but
     * built to a shorter layout would be silently ignored.
     */
    const val INITIAL_BLOCK_BYTES: Int = 94

    /** Offsets of the two int32 fields the viewer actually reads. */
    private const val WIDTH_OFFSET = 86
    private const val HEIGHT_OFFSET = 90

    /** The fixed-width device-name field that sits between the magic and the display info. */
    private const val DEVICE_NAME_BYTES = 64

    /**
     * The two legal Annex-B start codes. Both may appear in an elementary stream; only the
     * four-byte form may appear on this wire, because the viewer types a unit by `data[4]`.
     */
    private val SHORT_START_CODE = byteArrayOf(0, 0, 1)
    private val LONG_START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Builds the initial metadata block for a display of [width] x [height] physical pixels.
     *
     * Layout, big-endian, matching what the viewer indexes into:
     * ```
     *  0..13   magic "scrcpy_initial"
     * 14..77   device name, ASCII, NUL-padded to 64 bytes
     * 78..81   display count (always 1 — this app mirrors the default display)
     * 82..85   display id (0 — the default display)
     * 86..89   width
     * 90..93   height
     * ```
     * [deviceName] is truncated rather than rejected: it is a label in a struct the viewer does not
     * read, and failing a stream over a long model name would be absurd.
     */
    fun initialBlock(
        deviceName: String,
        width: Int,
        height: Int,
    ): ByteArray {
        val block = ByteArray(INITIAL_BLOCK_BYTES)
        INITIAL_MAGIC.copyInto(block)
        val name = deviceName.toByteArray(Charsets.US_ASCII)
        name.copyInto(block, INITIAL_MAGIC.size, 0, minOf(name.size, DEVICE_NAME_BYTES))
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(INITIAL_MAGIC.size + DEVICE_NAME_BYTES, SINGLE_DISPLAY)
        buffer.putInt(INITIAL_MAGIC.size + DEVICE_NAME_BYTES + Int.SIZE_BYTES, DEFAULT_DISPLAY_ID)
        buffer.putInt(WIDTH_OFFSET, width)
        buffer.putInt(HEIGHT_OFFSET, height)
        return block
    }

    /**
     * Splits one encoder output buffer into the individual Annex-B NAL units the viewer expects,
     * each re-emitted with a four-byte start code.
     *
     * The split is required rather than cosmetic. `MediaCodec`'s H.264 encoder delivers the codec
     * config as a single buffer holding the SPS and the PPS back to back, and some encoders emit an
     * access-unit delimiter or SEI ahead of a picture — so forwarding a buffer whole would hand the
     * viewer a message whose fifth byte types the FIRST unit and whose remaining units are never
     * seen. It also normalises three-byte start codes, which are legal in the elementary stream and
     * illegal on this wire.
     *
     * Zero-length units (two start codes in a row) are dropped; a buffer with no start code at all
     * yields nothing, because there is no honest way to type it.
     */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var index = firstStartCode(data, 0)
        while (index != null) {
            val payloadStart = index + startCodeLength(data, index)
            val next = firstStartCode(data, payloadStart)
            val payloadEnd = next ?: data.size
            if (payloadEnd > payloadStart) {
                units.add(withLongStartCode(data, payloadStart, payloadEnd))
            }
            index = next
        }
        return units
    }

    /** Copies `data[from until to]` behind a four-byte start code. */
    private fun withLongStartCode(
        data: ByteArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val unit = ByteArray(LONG_START_CODE.size + (to - from))
        LONG_START_CODE.copyInto(unit)
        data.copyInto(unit, LONG_START_CODE.size, from, to)
        return unit
    }

    /** Index of the next Annex-B start code at or after [from], or null when there is none. */
    private fun firstStartCode(
        data: ByteArray,
        from: Int,
    ): Int? {
        var index = maxOf(from, 0)
        var found: Int? = null
        while (found == null && index < data.size) {
            if (startsCodeAt(data, index)) found = index else index++
        }
        return found
    }

    private fun startsCodeAt(
        data: ByteArray,
        at: Int,
    ): Boolean = matchesAt(data, at, SHORT_START_CODE) || matchesAt(data, at, LONG_START_CODE)

    /** The length of the start code at [at] — four bytes when it is the long form, else three. */
    private fun startCodeLength(
        data: ByteArray,
        at: Int,
    ): Int = if (matchesAt(data, at, LONG_START_CODE)) LONG_START_CODE.size else SHORT_START_CODE.size

    private fun matchesAt(
        data: ByteArray,
        at: Int,
        pattern: ByteArray,
    ): Boolean {
        if (at < 0 || at + pattern.size > data.size) return false
        return pattern.indices.all { data[at + it] == pattern[it] }
    }

    /** This app mirrors exactly one display, the default one. */
    private const val SINGLE_DISPLAY = 1
    private const val DEFAULT_DISPLAY_ID = 0
}
