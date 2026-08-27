package com.danielealbano.androidremotecontrolmcp.services.screenstream

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The encoder settings the console's viewer asks for, and the sizing rule that turns them into a
 * capture resolution.
 *
 * The viewer sends its request as a 36-byte binary control message on the stream socket — the same
 * message it sends to the two adb-reachable backends, forwarded to this app verbatim by
 * device-gateway. Honouring it rather than inventing our own settings is what makes the operator's
 * quality selector work identically on all three device classes.
 *
 * Only the fields this backend can act on are modelled. The message also carries a crop rectangle,
 * a frame-meta flag, a locked orientation, a display id and two length-prefixed strings (codec
 * options, encoder name); none applies to a single-display in-app capture, and reading fields we
 * would then ignore would suggest they do something.
 */
data class StreamParameters(
    /** Target bitrate in bits per second. */
    val bitrate: Int,
    /** Target frame rate. The encoder treats it as a ceiling, not a promise. */
    val maxFps: Int,
    /** Seconds between forced keyframes. */
    val iFrameIntervalSeconds: Int,
    /**
     * The bounding box the capture must fit inside, in pixels. It is a BOX, not a resolution: the
     * viewer sends a square (1280x1280 for "high") and expects the device's own aspect ratio to be
     * preserved inside it.
     */
    val boundWidth: Int,
    val boundHeight: Int,
) {
    companion object {
        /** The control message's first byte — the viewer's `TYPE_CHANGE_STREAM_PARAMETERS`. */
        const val TYPE_CHANGE_STREAM_PARAMETERS: Int = 101

        /** The exact length of that message. Anything else is not it. */
        const val MESSAGE_BYTES: Int = 36

        /**
         * What the encoder runs at until the viewer says otherwise.
         *
         * The viewer sends its request on socket open, so this is only ever the setting for the
         * instant between the first frame and that message arriving — but it must still be sane,
         * because a stream that starts at an absurd bitrate on a metered mobile link has already
         * spent the bytes by the time it is corrected. These mirror the viewer's own "high".
         */
        val DEFAULT =
            StreamParameters(
                bitrate = 4_000_000,
                maxFps = 24,
                iFrameIntervalSeconds = 5,
                boundWidth = 1280,
                boundHeight = 1280,
            )

        private const val OFFSET_BITRATE = 1
        private const val OFFSET_MAX_FPS = 5
        private const val OFFSET_I_FRAME_INTERVAL = 9
        private const val OFFSET_BOUND_WIDTH = 10
        private const val OFFSET_BOUND_HEIGHT = 12

        /** Bounds a hostile or garbled request into something an encoder can be handed. */
        private const val MIN_BITRATE = 100_000
        private const val MAX_BITRATE = 20_000_000
        private const val MIN_FPS = 1
        private const val MAX_FPS = 60
        private const val MIN_I_FRAME_INTERVAL = 1
        private const val MAX_I_FRAME_INTERVAL = 60
        private const val MIN_BOUND = 160
        private const val MAX_BOUND = 4096

        /** The message's type byte is unsigned; Kotlin's Byte is not. */
        private const val BYTE_MASK = 0xFF

        /**
         * Parses the viewer's control message, or returns null when [data] is not one.
         *
         * Returning null rather than throwing is the point: this arrives on a socket that also
         * carries nothing else, but a future control verb, a truncated message or a stray frame
         * must leave the running stream alone rather than end it. Every field is clamped, because
         * the values reach a hardware encoder and an out-of-range one is a crash on some OEM
         * builds rather than a rejection.
         *
         * A bound of 0 means "native" in the viewer's vocabulary — it asks for the device's own
         * size — so it is passed through as 0 and resolved by [encoderSize].
         */
        fun parse(data: ByteArray): StreamParameters? {
            if (data.size != MESSAGE_BYTES || (data[0].toInt() and BYTE_MASK) != TYPE_CHANGE_STREAM_PARAMETERS) {
                return null
            }
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            return StreamParameters(
                bitrate = buffer.getInt(OFFSET_BITRATE).coerceIn(MIN_BITRATE, MAX_BITRATE),
                maxFps = buffer.getInt(OFFSET_MAX_FPS).coerceIn(MIN_FPS, MAX_FPS),
                iFrameIntervalSeconds =
                    buffer
                        .get(OFFSET_I_FRAME_INTERVAL)
                        .toInt()
                        .coerceIn(MIN_I_FRAME_INTERVAL, MAX_I_FRAME_INTERVAL),
                boundWidth = boundOf(buffer.getShort(OFFSET_BOUND_WIDTH).toInt()),
                boundHeight = boundOf(buffer.getShort(OFFSET_BOUND_HEIGHT).toInt()),
            )
        }

        /** 0 stays 0 ("native"); anything else is clamped into a range an encoder accepts. */
        private fun boundOf(raw: Int): Int = if (raw <= 0) 0 else raw.coerceIn(MIN_BOUND, MAX_BOUND)
    }

    /**
     * The capture resolution for a display of [displayWidth] x [displayHeight] physical pixels.
     *
     * Three rules, and each exists because of a way encoders fail rather than a preference:
     *
     * - the device's aspect ratio is preserved, since the viewer maps every tap through the REAL
     *   size it was told and a stretched picture would put every touch in the wrong place;
     * - the capture is never scaled UP, because encoding invented pixels costs bitrate and buys
     *   nothing;
     * - both dimensions are rounded DOWN to a multiple of two, which H.264's 4:2:0 chroma sampling
     *   requires and several OEM encoders enforce by refusing to configure at all.
     */
    fun encoderSize(
        displayWidth: Int,
        displayHeight: Int,
    ): Pair<Int, Int> {
        if (displayWidth <= 0 || displayHeight <= 0) return 0 to 0
        val fitsWidth = if (boundWidth <= 0) displayWidth else minOf(boundWidth, displayWidth)
        val fitsHeight = if (boundHeight <= 0) displayHeight else minOf(boundHeight, displayHeight)
        val scale =
            minOf(
                fitsWidth.toDouble() / displayWidth,
                fitsHeight.toDouble() / displayHeight,
            ).coerceAtMost(1.0)
        val width = even((displayWidth * scale).toInt())
        val height = even((displayHeight * scale).toInt())
        return width to height
    }

    private fun even(value: Int): Int = maxOf(2, value - (value % 2))
}
