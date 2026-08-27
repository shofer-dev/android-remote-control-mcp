package com.danielealbano.androidremotecontrolmcp.services.screenstream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The hardware H.264 encoder behind one drive session: the phone's own `MediaCodec` fed by the
 * armed virtual display, drained to browser-leg messages.
 *
 * Configured for INTERACTIVE latency rather than for file size, because the thing on the other end
 * is a person tapping. Four settings carry that, and each is a real behaviour rather than a hint:
 *
 * - `KEY_LATENCY = 1` asks the encoder to emit a frame per frame queued, instead of holding a
 *   pipeline of them. On an encoder that ignores it the stream is merely later, never wrong.
 * - `KEY_MAX_B_FRAMES = 0` forbids bidirectional prediction, which by definition cannot be
 *   emitted before the picture after it exists.
 * - `BITRATE_MODE_CBR` keeps the bitrate honest on a mobile link, where a variable-rate burst on a
 *   busy screen is what turns a smooth stream into a stall.
 * - `KEY_REPEAT_PREVIOUS_FRAME_AFTER` is the one that is not about latency at all: a virtual
 *   display produces NOTHING while the screen is static, so without it an idle phone starves the
 *   encoder and a viewer that connects mid-idle sits on a blank canvas until the user moves
 *   something. Repeating the last frame keeps a keyframe coming.
 *
 * Output is split into individual Annex-B NAL units before it leaves ([ViewerStreamContract]).
 */
internal class H264ScreenEncoder(
    private val sink: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var drain: Thread? = null
    private val running = AtomicBoolean(false)

    /** The size this encoder is configured at, so a parameter change can tell whether to restart. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    /**
     * Configures and starts the encoder, returning its input surface for the virtual display to be
     * pointed at, or null when the device cannot encode at these settings.
     */
    fun start(
        params: StreamParameters,
        encodeWidth: Int,
        encodeHeight: Int,
    ): Surface? {
        val format =
            MediaFormat.createVideoFormat(MIME_TYPE, encodeWidth, encodeHeight).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, params.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, params.maxFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, params.iFrameIntervalSeconds)
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, PRIORITY_REALTIME)
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_IDLE_FRAME_MICROS)
            }
        return try {
            val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder
            inputSurface = surface
            width = encodeWidth
            height = encodeHeight
            running.set(true)
            drain = Thread({ drainLoop(encoder) }, DRAIN_THREAD_NAME).also { it.start() }
            surface
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "This device's H.264 encoder refused the requested format", e)
            release()
            null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "This device's H.264 encoder could not be started", e)
            release()
            null
        } catch (e: java.io.IOException) {
            Log.w(TAG, "No usable H.264 encoder on this device", e)
            release()
            null
        }
    }

    /**
     * Applies a bitrate change WITHOUT restarting, and asks for an immediate keyframe.
     *
     * The keyframe is what makes a quality change visible at once rather than at the next scheduled
     * IDR, which at a five-second interval is a noticeable stall for an operator who just moved a
     * slider. A resolution change cannot be done this way and is handled by the caller restarting.
     */
    fun retune(params: StreamParameters) {
        val encoder = codec ?: return
        val bundle =
            Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, params.bitrate)
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
        runCatching { encoder.setParameters(bundle) }
            .onFailure { Log.w(TAG, "The encoder refused a live retune", it) }
    }

    /** Stops the encoder and releases everything. Safe to call more than once. */
    fun release() {
        running.set(false)
        drain?.join(DRAIN_JOIN_MILLIS)
        drain = null
        codec?.let { encoder ->
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    /**
     * Pulls encoded buffers and hands each NAL unit to the sink.
     *
     * It runs on its own thread rather than a coroutine because `dequeueOutputBuffer` is a blocking
     * native call with its own timeout, and parking a dispatcher thread on it for the life of a
     * drive session would take that thread out of the pool the connector's command exchange uses.
     */
    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index =
                try {
                    encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_MICROS)
                } catch (e: IllegalStateException) {
                    if (running.get()) {
                        Log.w(TAG, "The encoder failed mid-stream", e)
                        onError("the phone's encoder stopped")
                    }
                    return
                }
            if (index >= 0) {
                emit(encoder, index, info)
            }
        }
    }

    /** Copies one output buffer out, splits it, and releases it back to the codec promptly. */
    private fun emit(
        encoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
    ) {
        val buffer = encoder.getOutputBuffer(index)
        if (buffer != null && info.size > 0) {
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            // Split rather than forward whole: the codec-config buffer holds the SPS and PPS back
            // to back, and the viewer types a message by its FIFTH byte — so an unsplit buffer
            // would deliver a PPS the decoder never sees and never configure.
            ViewerStreamContract.splitAnnexB(bytes).forEach(sink)
        }
        runCatching { encoder.releaseOutputBuffer(index, false) }
    }

    private companion object {
        const val TAG = "H264ScreenEncoder"
        const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        const val DRAIN_THREAD_NAME = "screen-stream-encoder"
        const val DEQUEUE_TIMEOUT_MICROS = 100_000L
        const val DRAIN_JOIN_MILLIS = 2_000L

        /** `MediaFormat.KEY_PRIORITY`'s realtime level. */
        const val PRIORITY_REALTIME = 0

        /**
         * Re-feed the last frame after a fifth of a second of a motionless screen. Short enough
         * that a viewer connecting to an idle phone paints almost immediately; long enough that a
         * static screen costs a trickle rather than a full frame rate of identical pictures.
         */
        const val REPEAT_IDLE_FRAME_MICROS = 200_000L
    }
}
