package com.danielealbano.androidremotecontrolmcp.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher

private const val TAG = "MCP:ActivityStart"

/**
 * Finds the [Activity] a [Context] belongs to, or `null` when there is none.
 *
 * Compose hands out `LocalContext.current`, which is the hosting activity WRAPPED — usually by a
 * `ContextThemeWrapper`, and by however many more wrappers a theme or a preview adds — so an
 * `is Activity` test on it answers `false` on a screen that plainly has one. Unwrapping the
 * [ContextWrapper] chain is the only reliable answer.
 */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Starts a system screen, reporting whether it went anywhere.
 *
 * [Intent.FLAG_ACTIVITY_NEW_TASK] is added **only when this context has no Activity**. The flag is
 * mandatory from an application or service context and actively harmful from an activity: a new
 * TASK is precisely the launch a vendor background-start guard drops — MIUI's "display pop-up
 * windows while running in the background" is off by default for a sideloaded app, and refusing it
 * costs no exception, so the button that fired it looks like dead UI — and an activity cannot
 * receive a RESULT from a target it started in another task.
 *
 * Both failure modes are expected rather than exceptional: the screen may not exist on this build
 * (`ActivityNotFoundException`) or may exist but not be launchable by us (`SecurityException`), so
 * the caller is told and takes its fallback instead of crashing.
 */
fun Context.startSettingsActivity(intent: Intent): Boolean =
    try {
        if (findActivity() == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "Settings screen not present on this device", e)
        false
    } catch (e: SecurityException) {
        Log.i(TAG, "Settings screen refused the launch", e)
        false
    }

/**
 * Starts a system screen through an activity-result [ActivityResultLauncher], so the caller hears
 * about it when the holder comes back — however they come back, including having granted nothing.
 *
 * No [Intent.FLAG_ACTIVITY_NEW_TASK] is added here and none may be: a launcher belongs to an
 * activity, and a target started in another task delivers no result to it.
 *
 * Returns false when nothing could be started, which is the caller's cue to try its fallback
 * destination.
 */
fun ActivityResultLauncher<Intent>.startSettingsActivity(intent: Intent): Boolean =
    try {
        launch(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "Settings screen not present on this device", e)
        false
    } catch (e: SecurityException) {
        Log.i(TAG, "Settings screen refused the launch", e)
        false
    }

/**
 * Whether this build has an activity for [intent].
 *
 * Asked BEFORE a launch, so a screen a vendor left out becomes a different destination and honest
 * row text, rather than a button that fails at the moment it is pressed.
 */
fun Context.canStartSettingsActivity(intent: Intent): Boolean = resolveActivity(packageManager, intent) != null

/**
 * Resolves the way [Context.startActivity] would.
 *
 * `MATCH_DEFAULT_ONLY` is the load-bearing flag: an implicit intent is only launchable through an
 * activity that declares `CATEGORY_DEFAULT`, so resolving without it can answer "yes" about a
 * screen that would then refuse to start.
 *
 * API 33 replaced the `Int` flags overload with a typed `ResolveInfoFlags` one and deprecated the
 * original; API 31/32 has only the original. The query is identical on both, so this branch
 * chooses a spelling, never a behaviour.
 */
private fun resolveActivity(
    pm: PackageManager,
    intent: Intent,
) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
} else {
    @Suppress("DEPRECATION")
    pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
}
