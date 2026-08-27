package com.danielealbano.androidremotecontrolmcp.services.screenstream

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the connector does with one screen-stream's bytes.
 *
 * [message] is called once per browser-leg message, in order, from the encoder's own drain thread —
 * so an implementation must hand off rather than block, or a slow socket write becomes encoder
 * back-pressure and the picture stalls instead of degrading.
 */
interface ScreenStreamSink {
    fun message(data: ByteArray)

    /**
     * The stream ended on the DEVICE's side: the OS stopped the projection, the encoder failed, or
     * the holder disarmed the phone. [error] is a typed code for the platform, [details] prose for
     * a human. It is not called for a stop the platform itself asked for.
     */
    fun ended(
        error: String,
        details: String,
    )
}

/** How a start request came out. */
sealed interface ScreenStreamStart {
    data object Started : ScreenStreamStart

    /** [code] is a wire code the platform branches on; [details] is prose. */
    data class Refused(
        val code: String,
        val details: String,
    ) : ScreenStreamStart
}

/**
 * The connector's seam onto screen capture. An interface so the connector — which is constructed by
 * hand and unit-tested without an Android framework — can be driven with a fake.
 */
interface ScreenStreamController {
    /**
     * Whether this phone can serve a stream RIGHT NOW with no human touching it. It is what the
     * platform's `screen_stream` capability is derived from, and it changes while the connector is
     * attached, so the connector watches it rather than reading it once.
     */
    val capable: StateFlow<Boolean>

    fun start(sink: ScreenStreamSink): ScreenStreamStart

    /** Applies one viewer control message, verbatim off the socket. Unknown messages are ignored. */
    fun applyControl(data: ByteArray)

    fun stop()
}

/**
 * The real controller: the armed projection plus one encoder at a time.
 *
 * It holds no consent logic of its own — [MediaProjectionHolder] owns that, and owns the reason the
 * consent is taken once rather than per session. This class is the part that turns "the phone is
 * armed" into "these bytes, framed the way the console's viewer reads them".
 *
 * Exactly one stream at a time, enforced here as well as at the gateway, because the constraint is
 * physical rather than administrative: there is one screen and one encoder attached to it.
 */
@Singleton
class PlatformScreenStreamController
    @Inject
    constructor(
        private val holder: MediaProjectionHolder,
    ) : ScreenStreamController {
        override val capable: StateFlow<Boolean> get() = holder.armed

        private val lock = Any()
        private var encoder: H264ScreenEncoder? = null
        private var params: StreamParameters = StreamParameters.DEFAULT
        private var sink: ScreenStreamSink? = null

        override fun start(sink: ScreenStreamSink): ScreenStreamStart =
            synchronized(lock) {
                when {
                    encoder != null -> refusal(STREAM_BUSY, "this phone is already streaming its screen")
                    !holder.armed.value -> refusal(STREAM_UNSUPPORTED, NOT_ARMED_DETAILS)
                    else -> begin(sink)
                }
            }

        override fun applyControl(data: ByteArray) {
            val requested = StreamParameters.parse(data) ?: return
            synchronized(lock) {
                params = requested
                val live = encoder ?: return
                val (width, height) = requested.encoderSize(holder.displayWidth, holder.displayHeight)
                if (width == live.width && height == live.height) {
                    // Same picture size: the bitrate can move without interrupting the stream, and
                    // the forced keyframe makes the change visible now rather than at the next
                    // scheduled IDR — which at a five-second interval reads as a frozen slider.
                    live.retune(requested)
                } else {
                    // A resolution change cannot be applied to a running MediaCodec, so the encoder
                    // is replaced. The PROJECTION is untouched, which is the whole point of holding
                    // it: re-sizing costs a keyframe, not a consent dialog.
                    restart(requested, width, height)
                }
            }
        }

        override fun stop() {
            synchronized(lock) {
                holder.detach()
                encoder?.release()
                encoder = null
                sink = null
            }
        }

        /** Starts the encoder and writes the initial metadata block. Caller holds [lock]. */
        private fun begin(target: ScreenStreamSink): ScreenStreamStart {
            val (width, height) = params.encoderSize(holder.displayWidth, holder.displayHeight)
            sink = target
            val outcome =
                when {
                    width <= 0 || height <= 0 -> {
                        refusal(STREAM_UNSUPPORTED, "this phone reports no display size to capture")
                    }

                    !launch(params, width, height) -> {
                        refusal(STREAM_UNSUPPORTED, "this phone's hardware H.264 encoder would not start")
                    }

                    else -> {
                        ScreenStreamStart.Started
                    }
                }
            if (outcome is ScreenStreamStart.Started) {
                // The initial block goes out BEFORE any video, and carries the display's REAL size
                // rather than the encoded size: it is the coordinate space the console maps taps
                // through, so a stream that reported the scaled size would put every touch in the
                // wrong place while looking perfectly healthy.
                target.message(
                    ViewerStreamContract.initialBlock(
                        Build.MODEL.orEmpty(),
                        holder.displayWidth,
                        holder.displayHeight,
                    ),
                )
            } else {
                sink = null
            }
            return outcome
        }

        /** Replaces the running encoder at a new size, leaving the projection armed. */
        private fun restart(
            requested: StreamParameters,
            width: Int,
            height: Int,
        ) {
            holder.detach()
            encoder?.release()
            encoder = null
            if (width > 0 && height > 0 && !launch(requested, width, height)) {
                endStream("the phone could not restart its encoder at the requested quality")
            }
        }

        /** Builds an encoder and points the armed display at it. Caller holds [lock]. */
        private fun launch(
            requested: StreamParameters,
            width: Int,
            height: Int,
        ): Boolean {
            val target = sink
            if (target == null) return false
            val built =
                H264ScreenEncoder(
                    sink = { target.message(it) },
                    onError = { endStream(it) },
                )
            val surface = built.start(requested, width, height)
            val attached = surface != null && holder.attach(surface, width, height)
            if (attached) {
                encoder = built
            } else {
                built.release()
            }
            return attached
        }

        /** Tears the stream down and tells the platform why. */
        private fun endStream(details: String) {
            val target = synchronized(lock) { sink }
            stop()
            target?.ended(STREAM_UNSUPPORTED, details)
        }

        private fun refusal(
            code: String,
            details: String,
        ): ScreenStreamStart {
            Log.i(TAG, "Refusing a screen stream: $code ($details)")
            return ScreenStreamStart.Refused(code, details)
        }

        private companion object {
            const val TAG = "ScreenStreamController"

            /** Wire codes, matching `device-gateway/internal/protocol`. */
            const val STREAM_UNSUPPORTED = "stream-unsupported"
            const val STREAM_BUSY = "stream_busy"

            /**
             * Said in full because it is the sentence an operator will read when a phone declines
             * to stream, and "unsupported" alone sends them to look at the wrong thing: the app is
             * fine, the phone simply has no live screen-capture consent.
             */
            const val NOT_ARMED_DETAILS =
                "this phone has no live screen-capture consent; " +
                    "enable remote screen on the handset, then reopen the viewer"
        }
    }
