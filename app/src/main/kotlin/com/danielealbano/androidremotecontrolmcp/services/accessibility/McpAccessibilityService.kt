package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.InputMethod
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

@Suppress("TooManyFunctions")
class McpAccessibilityService : AccessibilityService() {
    // AccessibilityNodeCache is in the same package — no import needed
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NodeCacheEntryPoint {
        fun nodeCache(): AccessibilityNodeCache
    }

    private var serviceScope: CoroutineScope? = null

    private var nodeCache: AccessibilityNodeCache? = null

    private var cacheInvalidationDebouncer: CacheInvalidationDebouncer? = null

    @Volatile
    private var currentPackageName: String? = null

    @Volatile
    private var currentActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        serviceScope = scope
        nodeCache = resolveNodeCache()
        cacheInvalidationDebouncer =
            CacheInvalidationDebouncer(
                scope = scope,
                debounceMillis = CACHE_INVALIDATION_DEBOUNCE_MS,
                onSettled = { invalidateCache(nodeCache) },
            )

        configureServiceInfo()

        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                event.packageName?.toString()?.let { packageName ->
                    currentPackageName = packageName
                }
                event.className?.toString()?.let { className ->
                    currentActivityName = className
                }
                Log.d(
                    TAG,
                    "Window state changed: package=$currentPackageName, " +
                        "activity=$currentActivityName",
                )
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Soft-keyboard show/hide and other window add/remove/bounds changes arrive here.
                Log.d(TAG, "Windows changed (e.g. soft-keyboard show/hide)")
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                Log.d(TAG, "Window content changed: package=${event.packageName}")
            }

            else -> {
                // Ignored event types
            }
        }

        // A structural window change (keyboard show/hide, rotation, activity/dialog transition)
        // shifts element bounds. Because cached node ids are derived from bounds, and which
        // elements are present/actionable also changes, the cached id->node entries become stale.
        // Schedule a debounced invalidation so the cache is dropped once — and only once — AFTER
        // the transition settles, so the next node lookup resolves against the live tree.
        scheduleCacheInvalidationIfNeeded(event.eventType, cacheInvalidationDebouncer)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroying")

        // Stop any pending debounced invalidation before tearing down the scope it runs on.
        cacheInvalidationDebouncer?.cancel()
        cacheInvalidationDebouncer = null

        // Flush the node cache — all AccessibilityNodeInfo references become invalid.
        nodeCache?.clear()
        nodeCache = null

        serviceScope?.cancel()
        serviceScope = null
        currentPackageName = null
        currentActivityName = null
        inputMethodInstance = null
        instance = null

        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition reported")
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName =
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
                else -> "UNKNOWN($level)"
            }
        Log.w(TAG, "Trim memory: level=$levelName")
    }

    /**
     * Returns the root [AccessibilityNodeInfo] of the currently active window,
     * or null if no window is available.
     */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Returns all on-screen windows via [AccessibilityService.getWindows].
     * Requires [AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS].
     *
     * @return List of [AccessibilityWindowInfo], or empty list if unavailable.
     */
    fun getAccessibilityWindows(): List<AccessibilityWindowInfo> =
        try {
            windows ?: emptyList()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "getWindows() failed: ${e.message}")
            emptyList()
        }

    /**
     * Returns the package of the app currently in the foreground, RE-RESOLVED at call time from
     * the live window list rather than read from the last-event cache.
     *
     * The policy enforcer judges every command against this package, so it must be the app the
     * agent is driving — not whatever most recently changed window state. The last-event value
     * ([currentPackageName]) is routinely overwritten by this app's own overlay window and by
     * transient SystemUI dialogs, both structurally undrivable, which made the enforcer refuse
     * every command. See [resolveForegroundPackage] for the resolution order and fallbacks.
     */
    fun getCurrentPackageName(): String? =
        resolveForegroundPackage(
            windows = getAccessibilityWindows(),
            ownPackage = packageName,
            activeWindowRootProvider = { rootInActiveWindow },
            cachedPackage = currentPackageName,
        )

    /**
     * Returns the class name (activity name) of the currently focused window,
     * or null if unknown.
     */
    fun getCurrentActivityName(): String? = currentActivityName

    /**
     * Returns true if the service is connected and ready to process requests.
     * Does NOT check for an active window — multi-window support handles
     * window availability at tree-parsing time.
     */
    fun isReady(): Boolean = instance != null

    /**
     * Returns the [CoroutineScope] for this service, or null if not connected.
     */
    fun getServiceScope(): CoroutineScope? = serviceScope

    /**
     * Returns the current screen dimensions, density, and orientation.
     *
     * @return [ScreenInfo] with width, height, densityDpi, and orientation.
     */
    fun getScreenInfo(): ScreenInfo {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = windowManager.currentWindowMetrics
        val bounds = metrics.bounds
        val width = bounds.width()
        val height = bounds.height()

        val displayMetrics = resources.displayMetrics
        val densityDpi = displayMetrics.densityDpi

        val orientation =
            when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> ScreenInfo.ORIENTATION_LANDSCAPE
                else -> ScreenInfo.ORIENTATION_PORTRAIT
            }

        return ScreenInfo(
            width = width,
            height = height,
            densityDpi = densityDpi,
            orientation = orientation,
        )
    }

    /**
     * The accessibility IME plane exists only from API 33, and the framework calls this only on
     * builds that have it — so the annotation states the fact rather than adding a guard that
     * could never fire.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreateInputMethod(): InputMethod {
        val method = McpInputMethod(this)
        inputMethodInstance = method
        return method
    }

    private fun configureServiceInfo() {
        serviceInfo =
            serviceInfo?.apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                // FLAG_INPUT_METHOD_EDITOR (API 33) asks for the accessibility IME plane the typing
                // tools drive. Below 33 there is no such plane, so the bit is simply not asked for.
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR
                    } else {
                        0
                    }
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
            }
        if (serviceInfo == null) {
            Log.w(TAG, "serviceInfo is null, cannot configure accessibility service settings")
        }
    }

    /**
     * Resolves the singleton [AccessibilityNodeCache] via Hilt's application entry point.
     * Returns null (and logs) if Hilt is not initialized, in which case cache invalidation
     * becomes a no-op rather than crashing the service.
     */
    private fun resolveNodeCache(): AccessibilityNodeCache? =
        try {
            EntryPointAccessors
                .fromApplication(applicationContext, NodeCacheEntryPoint::class.java)
                .nodeCache()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not resolve node cache", e)
            null
        }

    /**
     * Takes a screenshot using AccessibilityService.takeScreenshot() API.
     * Does NOT require user consent.
     *
     * @param timeoutMs Maximum time to wait for screenshot capture.
     * @return Bitmap of the screenshot, or null if capture failed or timed out.
     */
    suspend fun takeScreenshotBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val executor = Executor { it.run() }
                val callback =
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap =
                                Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace,
                                )
                            screenshot.hardwareBuffer.close()
                            if (continuation.isActive) {
                                continuation.resume(bitmap)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }

                takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            }
        }

    /**
     * Returns true if screenshot capability is available. `takeScreenshot` is API 30+, so this is
     * always true on this app's minSdk (31).
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun canTakeScreenshot(): Boolean = true

    /**
     * Drops the framework's accessibility node cache for this service via [clearCache], public
     * since API 33. See [AccessibilityServiceProvider.clearFrameworkNodeCache] for why this is
     * needed to defeat stale WebView reads after JavaScript DOM changes.
     *
     * On API 31/32 there is no such API — the framework cache cannot be dropped from an
     * accessibility service at all — so the call is skipped and a stale WebView read stays
     * possible there. Our own id→node cache is still flushed by [invalidateCache], which is a
     * different cache and unaffected by the API level.
     */
    fun clearFrameworkNodeCache() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearCache()
        }
    }

    /** The accessibility IME instance the typing tools drive. API 33+ only; see [InputMethod]. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    class McpInputMethod(
        service: AccessibilityService,
    ) : InputMethod(service)

    companion object {
        private const val TAG = "MCP:AccessibilityService"
        private const val NOTIFICATION_TIMEOUT_MS = 100L
        private const val SCREENSHOT_TIMEOUT_MS = 5000L

        /**
         * Length of the QUIET GAP (no further window-structure events) that must elapse before the
         * node cache is invalidated. The debounce timer resets on every event during a transition,
         * so this value is the silence required *after the last event*, not the total transition
         * duration. 250ms is an empirically chosen heuristic — long enough that a settling
         * keyboard/rotation has stopped emitting events, short enough to keep the post-transition
         * cache fresh quickly. Single tunable constant; validate/adjust against real-device traces.
         */
        private const val CACHE_INVALIDATION_DEBOUNCE_MS = 250L

        /**
         * Singleton instance of the accessibility service.
         * Set when the service connects, cleared when it is destroyed.
         * Access from other components to interact with the accessibility tree.
         */
        @Volatile
        var instance: McpAccessibilityService? = null
            private set

        @Volatile
        var inputMethodInstance: McpInputMethod? = null
            private set
    }
}

/**
 * Returns true if [eventType] is a structural window change after which cached node bounds may be
 * stale and the node cache should be invalidated: [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED]
 * (rotation, activity/dialog transition) and [AccessibilityEvent.TYPE_WINDOWS_CHANGED]
 * (soft-keyboard show/hide, window add/remove).
 *
 * Deliberately EXCLUDES [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED], which fires far too
 * frequently (e.g. live text, progress bars) to drive cache invalidation without thrashing.
 *
 * Top-level and `internal` so the decision can be unit-tested without instantiating the service.
 */
internal fun triggersCacheInvalidation(eventType: Int): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

/**
 * Schedules a debounced cache invalidation iff [eventType] is a structural window change (see
 * [triggersCacheInvalidation]). No-op when [debouncer] is null (service not fully connected).
 *
 * Top-level and `internal` so the event-to-schedule wiring can be unit-tested without
 * instantiating the service.
 */
internal fun scheduleCacheInvalidationIfNeeded(
    eventType: Int,
    debouncer: CacheInvalidationDebouncer?,
) {
    if (triggersCacheInvalidation(eventType)) {
        debouncer?.schedule()
    }
}

/**
 * Clears [cache] if present. The whole-cache drop is the invalidation applied after a settled
 * window transition; a null [cache] (Hilt entry point unavailable) is a safe no-op.
 *
 * Top-level and `internal` so the invalidation behavior can be unit-tested without instantiating
 * the service.
 */
internal fun invalidateCache(cache: AccessibilityNodeCache?) {
    cache?.clear()
}

/** The SystemUI package — its transient dialogs must never be mistaken for the driven foreground. */
private const val SYSTEMUI_PACKAGE = "com.android.systemui"

/**
 * Resolves the REAL foreground application package AT CALL TIME, so a transient overlay — this
 * app's own edge-border window, a SystemUI dialog — never masquerades as the driven foreground.
 *
 * The resolution order, each candidate filtered against [ownPackage] and [SYSTEMUI_PACKAGE]:
 * 1. the topmost drivable [AccessibilityWindowInfo.TYPE_APPLICATION] window (see
 *    [foregroundFromWindows]) — the authoritative answer when the window list is available;
 * 2. the active window's root package ([activeWindowRootProvider]) — used when the window list is
 *    empty or unavailable;
 * 3. the last-event [cachedPackage], returned as-is so behaviour degrades to the old value rather
 *    than crashing when neither of the above resolves.
 *
 * Returns null only when nothing resolves; [PolicyEnforcer]-style callers fail closed on a null
 * foreground.
 *
 * Top-level and `internal` so the resolution can be unit-tested without instantiating the service.
 */
internal fun resolveForegroundPackage(
    windows: List<AccessibilityWindowInfo>,
    ownPackage: String,
    activeWindowRootProvider: () -> AccessibilityNodeInfo?,
    cachedPackage: String?,
): String? =
    foregroundFromWindows(windows, ownPackage)
        ?: activeWindowForeground(activeWindowRootProvider, ownPackage)
        ?: cachedPackage

/**
 * The topmost drivable application package among [windows], or null when none qualifies. A window
 * qualifies when it is a [AccessibilityWindowInfo.TYPE_APPLICATION] whose root package is neither
 * [ownPackage] nor [SYSTEMUI_PACKAGE]. Among the qualifying windows the one with the highest
 * [AccessibilityWindowInfo.layer] wins; an active/focused window breaks a layer tie.
 *
 * Top-level and `internal` so the selection can be unit-tested without instantiating the service.
 */
internal fun foregroundFromWindows(
    windows: List<AccessibilityWindowInfo>,
    ownPackage: String,
): String? {
    var bestPackage: String? = null
    var bestLayer = Int.MIN_VALUE
    var bestActive = false
    for (window in windows) {
        val pkg = drivableApplicationPackage(window, ownPackage) ?: continue
        val layer = window.layer
        val active = window.isActive || window.isFocused
        val wins =
            bestPackage == null ||
                layer > bestLayer ||
                (layer == bestLayer && active && !bestActive)
        if (wins) {
            bestPackage = pkg
            bestLayer = layer
            bestActive = active
        }
    }
    return bestPackage
}

/**
 * The package the active window's root reports, when it is drivable, else null. The root is
 * obtained from [activeWindowRootProvider] and recycled before returning. Used only as a fallback
 * when the window list is empty or unavailable.
 */
private fun activeWindowForeground(
    activeWindowRootProvider: () -> AccessibilityNodeInfo?,
    ownPackage: String,
): String? {
    val activeRoot = activeWindowRootProvider() ?: return null
    return try {
        activeRoot.packageName?.toString()?.takeIf { isDrivableForeground(it, ownPackage) }
    } finally {
        @Suppress("DEPRECATION")
        activeRoot.recycle()
    }
}

/**
 * The package of [window] when it is a drivable [AccessibilityWindowInfo.TYPE_APPLICATION] window
 * (root present, and neither [ownPackage] nor [SYSTEMUI_PACKAGE]), else null. Its root node is
 * obtained only to read the package and is recycled immediately (a no-op on API 33+, kept for
 * consistency with the codebase's node-recycling convention).
 */
private fun drivableApplicationPackage(
    window: AccessibilityWindowInfo,
    ownPackage: String,
): String? {
    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
    return window.root?.let { root ->
        try {
            root.packageName?.toString()?.takeIf { isDrivableForeground(it, ownPackage) }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }
}

/**
 * True when [packageName] names a drivable foreground app — present, and neither this app's own UI
 * ([ownPackage]) nor SystemUI ([SYSTEMUI_PACKAGE]). Shared by the window path and the active-window
 * fallback so the "not us, not the system chrome" rule is written once.
 *
 * Top-level and `internal` so the filter can be unit-tested without instantiating the service.
 */
internal fun isDrivableForeground(
    packageName: String?,
    ownPackage: String,
): Boolean {
    if (packageName.isNullOrBlank()) return false
    return packageName != ownPackage && packageName != SYSTEMUI_PACKAGE
}
