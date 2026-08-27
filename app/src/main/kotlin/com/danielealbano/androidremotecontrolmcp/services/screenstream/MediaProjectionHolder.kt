package com.danielealbano.androidremotecontrolmcp.services.screenstream

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's screen-capture ARMING, and the single most consequential design decision in the
 * physical-phone video backend.
 *
 * ── What Android 14 actually permits, and why this class exists ────────────────────────────────
 *
 * On API 34 a capture session requires the user's consent, per session, and the OS enforces it:
 * `createVirtualDisplay` throws `SecurityException` if the consent `Intent` is passed to
 * `getMediaProjection` twice, or if `createVirtualDisplay` is called twice on one `MediaProjection`.
 * There is no bypass an ordinary app can reach — the auto-grant inside SystemUI's permission
 * activity needs either the privileged `CAPTURE_VIDEO_OUTPUT` permission (OEM-preinstalled apps
 * only) or the `android:project_media` app-op set to ALLOWED, which only the shell can set. Being a
 * device ADMINISTRATOR, which this app is, grants none of it.
 *
 * Read literally, that would mean a person must tap a system dialog on the handset every time an
 * operator opens the viewer — unusable for a phone nobody is standing next to.
 *
 * The way out is not a bypass, it is the lifetime. A `MediaProjection` may be used ONCE, but the
 * `VirtualDisplay` it creates lives until the projection is stopped, and Android explicitly
 * supports re-pointing that display at a new `Surface` (`VirtualDisplay#resize` +
 * `VirtualDisplay#setSurface`, the documented way to handle a rotation). So the consent is taken
 * ONCE, while a person is present, and the projection is then held for as long as the OS allows —
 * with the encoder's input surface attached and detached per drive session. One tap ARMS the phone;
 * every stream after it is silent.
 *
 * ── What still ends it, and why that is reported rather than papered over ──────────────────────
 *
 * The OS stops a projection when the user stops it from the status-bar chip, when another app
 * starts projecting, when the app's `mediaProjection` foreground service goes away, when the
 * process dies — and, from Android 15, when the device locks with a secure keyguard. Every one of
 * those needs a fresh consent, so [armed] flips to false and the platform is told, which is what
 * makes the console fall back to the frame poll instead of offering a video transport nothing can
 * serve. Nothing here retries silently: a phone that needs a tap says so.
 *
 * ── Transparency is a property, not a cost ─────────────────────────────────────────────────────
 *
 * Holding the projection means the OS keeps its screen-capture indicator up for as long as the
 * phone is armed. That is aligned with this platform's stance that a holder can always tell their
 * device is drivable, so it is deliberately not something to work around.
 */
@Singleton
class MediaProjectionHolder
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) {
        private val _armed = MutableStateFlow(false)

        /**
         * Whether a live, usable projection is held RIGHT NOW.
         *
         * This is the honest predicate the platform's screen-stream capability is derived from, and
         * it is deliberately about the projection rather than about the app's build or the phone's
         * model: it means "a stream request will succeed without anybody touching this handset",
         * which is exactly the question the console is asking when it chooses a transport.
         */
        val armed: StateFlow<Boolean> = _armed.asStateFlow()

        private val projectionManager: MediaProjectionManager? =
            appContext.getSystemService(MediaProjectionManager::class.java)

        private val handler = Handler(Looper.getMainLooper())

        private var projection: MediaProjection? = null
        private var display: VirtualDisplay? = null

        /** The display's real size at arming time — the coordinate space every tap is mapped through. */
        @Volatile
        var displayWidth: Int = 0
            private set

        @Volatile
        var displayHeight: Int = 0
            private set

        @Volatile
        var displayDensity: Int = DisplayMetrics.DENSITY_DEFAULT
            private set

        private val callback =
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "The OS stopped the screen projection; the phone is no longer armed")
                    releaseLocked()
                }
            }

        /**
         * Takes the user's consent result and holds the projection.
         *
         * MUST be called from a `mediaProjection` foreground service that is ALREADY in the
         * foreground: `MediaProjection.start` throws `SecurityException` without one, and the OS
         * additionally stops the grant the moment that service goes away.
         *
         * Returns false rather than throwing, because every failure here is a phone that simply
         * cannot stream — a refused consent, a stale token (the OS voids one five minutes after it
         * is issued), an OEM build that refuses — and all of them mean the same thing to the caller.
         */
        @Synchronized
        fun arm(
            resultCode: Int,
            data: Intent,
        ): Boolean {
            val manager =
                projectionManager
                    ?: return notArmed("this device exposes no MediaProjectionManager")
            releaseLocked()
            measureDisplay()
            return takeProjection(manager, resultCode, data)
        }

        /**
         * Turns the consent into a held projection, or reports why it could not be.
         *
         * Every failure collapses to the same answer on purpose: a refused consent, a token past
         * its five-minute validity and an OEM build that simply declines all mean "this phone
         * cannot stream", and a caller that had to tell them apart could act on none of them.
         */
        private fun takeProjection(
            manager: MediaProjectionManager,
            resultCode: Int,
            data: Intent,
        ): Boolean =
            try {
                val live = manager.getMediaProjection(resultCode, data)
                if (live == null) notArmed("the OS handed back no projection") else hold(live)
            } catch (e: SecurityException) {
                Log.w(TAG, "The OS refused the screen-capture consent", e)
                notArmed("the OS refused the consent")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "The screen-capture consent could not be used", e)
                notArmed("the consent could not be used")
            }

        /**
         * Registers the lifecycle callback and creates the ONE virtual display this consent buys.
         *
         * The callback is mandatory from API 34 — `createVirtualDisplay` throws
         * `IllegalStateException` without one — and it is also how we learn the OS ended the
         * capture, which is the whole basis of [armed] being trustworthy. The display starts with
         * NO surface: the phone is armed and producing nothing until a drive session attaches an
         * encoder to it.
         */
        private fun hold(live: MediaProjection): Boolean {
            live.registerCallback(callback, handler)
            val virtual =
                live.createVirtualDisplay(
                    VIRTUAL_DISPLAY_NAME,
                    displayWidth,
                    displayHeight,
                    displayDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    null,
                    null,
                    handler,
                )
            if (virtual == null) {
                live.unregisterCallback(callback)
                live.stop()
                return notArmed("the OS refused the virtual display")
            }
            projection = live
            display = virtual
            _armed.value = true
            Log.i(TAG, "Screen capture armed at ${displayWidth}x$displayHeight")
            return true
        }

        /**
         * Points the held display at an encoder's input surface, resizing it to the encoded size.
         *
         * Returns false when the phone is not armed, which is the whole guard a stream needs: the
         * capability said yes, and between then and now the OS may have taken it away.
         */
        @Synchronized
        fun attach(
            surface: Surface,
            width: Int,
            height: Int,
        ): Boolean {
            val virtual = display ?: return false
            virtual.resize(width, height, displayDensity)
            virtual.surface = surface
            return true
        }

        /** Detaches whatever surface is attached, leaving the phone armed and idle. */
        @Synchronized
        fun detach() {
            display?.surface = null
        }

        /** Releases the projection. The next stream needs a fresh consent. */
        @Synchronized
        fun disarm() {
            releaseLocked()
        }

        /** The single "this phone did not arm" answer, so every refusal reads the same downstream. */
        private fun notArmed(why: String): Boolean {
            Log.w(TAG, "The phone is not armed for screen capture: $why")
            return false
        }

        private fun releaseLocked() {
            display?.release()
            display = null
            projection?.let {
                it.unregisterCallback(callback)
                it.stop()
            }
            projection = null
            _armed.value = false
        }

        /**
         * Reads the display's REAL size — including the parts under the system bars and a display
         * cutout — because that is the coordinate space the accessibility layer injects taps in.
         * A capture sized to the app-usable area would be systematically offset from every touch.
         */
        private fun measureDisplay() {
            val windows = appContext.getSystemService(WindowManager::class.java)
            val bounds = windows?.maximumWindowMetrics?.bounds
            displayWidth = bounds?.width() ?: 0
            displayHeight = bounds?.height() ?: 0
            displayDensity = appContext.resources.displayMetrics.densityDpi
        }

        private companion object {
            const val TAG = "MediaProjectionHolder"
            const val VIRTUAL_DISPLAY_NAME = "platform-screen-stream"
        }
    }
