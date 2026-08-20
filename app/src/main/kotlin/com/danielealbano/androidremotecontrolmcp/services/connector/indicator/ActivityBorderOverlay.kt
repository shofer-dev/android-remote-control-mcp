@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector.indicator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persistent border drawn around the whole screen while a remote session is driving the
 * device (`docs/phones/android_remote_control.md` §6.4 — "transparency is a signal, not a
 * control"). It sits on top of every app, is invisible to touch, and there is no platform
 * frame, no policy field and no setting that turns it off: the holder must always be able to
 * tell, from the device in their hand, that it is being driven.
 *
 * ## Why the ACCESSIBILITY overlay window type
 *
 * `TYPE_ACCESSIBILITY_OVERLAY` is added through the [android.accessibilityservice.AccessibilityService]'s
 * own WindowManager and needs no `SYSTEM_ALERT_WINDOW` grant. That is not a convenience, it
 * is the correct coupling: the window rides the SAME grant that makes remote driving possible
 * at all, so the indicator cannot be revoked while leaving the driving working — revoking
 * accessibility revokes both, and the device goes loudly offline in the fleet view (§6.1). An
 * overlay behind a separate, separately-revocable permission would fail open exactly when it
 * mattered, and would be one more prompt at enrolment.
 *
 * The consequence, stated because it constrains the caller: the border can only be shown
 * while the accessibility service is connected. That is also precisely when a command can be
 * executed, so there is no window in which the device is driven without the border — but
 * [show] does return silently when the service is not up, and the caller must not read that
 * as success.
 *
 * All WindowManager work is marshalled onto the main looper: adding or removing a view from
 * any other thread throws, and commands arrive on the connector's IO scope.
 */
@Singleton
class ActivityBorderOverlay
    @Inject
    constructor(
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
    ) {
        private val main = Handler(Looper.getMainLooper())

        /** Non-null exactly while the border is attached to a window. */
        private var attached: View? = null

        /** Adds the border if it is not already up. Safe to call from any thread. */
        fun show() {
            main.post {
                if (attached != null) return@post
                val serviceContext =
                    accessibilityServiceProvider.getContext() ?: run {
                        Log.w(TAG, "Accessibility service is not connected; the activity border cannot be shown")
                        return@post
                    }
                val windowManager =
                    serviceContext.getSystemService(WindowManager::class.java) ?: run {
                        Log.w(TAG, "No WindowManager on the accessibility service context")
                        return@post
                    }
                val view = BorderView(serviceContext)
                try {
                    windowManager.addView(view, layoutParams())
                    attached = view
                    Log.i(TAG, "Activity border shown")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not add the activity border", e)
                }
            }
        }

        /** Removes the border if it is up. Safe to call from any thread. */
        fun hide() {
            main.post {
                val view = attached ?: return@post
                attached = null
                val windowManager =
                    accessibilityServiceProvider.getContext()?.getSystemService(WindowManager::class.java)
                try {
                    windowManager?.removeView(view)
                    Log.i(TAG, "Activity border hidden")
                } catch (e: Exception) {
                    // A service teardown can take the window with it; the view is already
                    // gone in that case and there is nothing left to remove.
                    Log.d(TAG, "Activity border was already detached: ${e.message}")
                }
            }
        }

        /**
         * NOT_TOUCHABLE is what keeps the border a SIGNAL rather than an obstacle: every touch
         * passes through to the app underneath, including the holder's. NOT_FOCUSABLE keeps it
         * out of the input path entirely, and LAYOUT_NO_LIMITS lets it reach into the status and
         * navigation bar areas so the border is a complete rectangle rather than three sides and
         * a gap.
         */
        private fun layoutParams(): WindowManager.LayoutParams {
            val flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val params =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT,
                )
            params.gravity = Gravity.TOP or Gravity.START
            return params
        }

        /** A stroked rectangle around the screen edge. Nothing else — no text, no touch target. */
        private class BorderView(
            context: Context,
        ) : View(context) {
            private val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = BORDER_COLOR
                    strokeWidth = BORDER_WIDTH_DP * context.resources.displayMetrics.density
                }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val inset = paint.strokeWidth / 2f
                canvas.drawRect(inset, inset, width - inset, height - inset, paint)
            }
        }

        private companion object {
            const val TAG = "MCP:ActivityBorder"

            /** Amber-red at full opacity: unmissable against both light and dark UIs. */
            val BORDER_COLOR: Int = Color.parseColor("#FFD32F2F")
            const val BORDER_WIDTH_DP = 6f
        }
    }
