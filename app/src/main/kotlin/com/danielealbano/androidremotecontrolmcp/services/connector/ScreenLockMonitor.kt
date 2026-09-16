// Every OS read here is DEFENSIVE by design: a vendor ROM that refuses the keyguard service, or
// throws from a broadcast registration, must cost the connector nothing — the platform simply
// falls back to inferring lockedness from refusals, exactly as it did before this file existed.
// Catching narrowly would mean predicting which OEM throws what, which is the guess this whole
// class exists to avoid. Same posture, same suppression as `policy/DeviceEnvironment.kt`.
@file:Suppress("TooGenericExceptionCaught")

package com.danielealbano.androidremotecontrolmcp.services.connector

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The phone's keyguard, as a value the connector can state and a stream it can watch.
 *
 * # Why the PHONE is the only honest source
 *
 * The platform used to learn "this handset is locked" from the connector's own
 * `policy-screen-locked` refusals, and nothing else — no Android signal reaches the gateway, and
 * the gateway holds no adb. That inference has two defects an operator meets immediately: it can
 * only ever mint "locked" (there is no refusal that means "unlocked"), and it clears only when a
 * command SUCCEEDS — so a phone somebody unlocked a minute ago goes on being displayed as locked
 * until the console happens to send it something. That was reported as a bug, twice, and it was
 * really the absence of this class.
 *
 * The app holds a [KeyguardManager]. It knows, at once, for free. So it says so: the state rides
 * the attach frame (`screen_locked`) and every change is a `screen_state` frame on the live
 * socket — the same shape as the capability re-advertisement next door in [PlatformConnector],
 * and for the same reason. The gateway prefers this over its inference whenever a phone sends it
 * (`hub/screenlock.go`), and an older app that sends nothing keeps the old behaviour.
 *
 * # What is watched, and why those three broadcasts
 *
 * Nothing in Android broadcasts "the keyguard changed". What exists is the three moments the
 * keyguard can change AROUND: the screen going off (which engages it), the screen coming on (which
 * shows it, still locked), and the user dismissing it (`ACTION_USER_PRESENT`). So the receiver
 * treats all three as "ask again" and re-reads [KeyguardManager.isKeyguardLocked] rather than
 * inferring the state from which broadcast arrived — a phone woken and unlocked in one gesture
 * fires two of them, and only the re-read is right both times.
 *
 * All three are protected broadcasts the system sends to REGISTERED receivers only; a manifest
 * entry would never fire. That is why this is tied to the connector's lifetime rather than
 * declared, and it costs nothing when no socket is up.
 *
 * # Unknown is a state, and it is not "unlocked"
 *
 * [current] answers null when the OS could not be asked, and the connector then sends no
 * `screen_locked` at all. Reporting `false` there would be the one unrecoverable lie in this
 * design: the gateway treats a reported `false` as authoritative and stops inferring, so a
 * guessed "unlocked" would suppress the very refusals that used to be the fallback.
 */
interface ScreenLockMonitor {
    /**
     * The keyguard right now, or null when the OS could not be asked.
     *
     * Read on the attach path, where there is no stream to wait on: the platform must know what it
     * is attaching to before the first command, not one broadcast later.
     */
    fun current(): Boolean?

    /**
     * Keyguard states as they change, starting with the state at collection.
     *
     * De-duplicated, so a wake-and-unlock that fires two broadcasts sends one frame. The initial
     * emission is deliberate and harmless: the connector has already stated the same value on its
     * attach frame, and the gateway's record does not move its "locked since" clock for a restated
     * state (`hub/screenlock.go`).
     */
    fun states(): Flow<Boolean>
}

/**
 * The production [ScreenLockMonitor].
 *
 * Every read is defensive: a vendor OS that refuses the service, or throws from
 * `isKeyguardLocked`, must cost the connector nothing — the platform falls back to inferring from
 * refusals exactly as it did before this existed.
 */
class AndroidScreenLockMonitor(
    private val appContext: Context,
) : ScreenLockMonitor {
    override fun current(): Boolean? =
        try {
            appContext.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the keyguard state", e)
            null
        }

    override fun states(): Flow<Boolean> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        // The broadcast is only a PROMPT — which one arrived says nothing reliable
                        // about the resulting state (a wake-and-unlock fires two), so the keyguard
                        // itself is re-read every time.
                        current()?.let { trySend(it) }
                    }
                }
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            try {
                appContext.registerReceiver(receiver, filter)
            } catch (e: Exception) {
                // A registration that fails leaves the flow with its initial value and nothing
                // more. The connector stays correct — it simply stops being live, which is the
                // pre-existing behaviour rather than a new failure.
                Log.w(TAG, "Could not watch the keyguard; lock state will not update live", e)
            }
            current()?.let { trySend(it) }
            awaitClose {
                try {
                    appContext.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.w(TAG, "Keyguard receiver was already gone", e)
                }
            }
        }.distinctUntilChanged()

    private companion object {
        const val TAG = "MCP:ScreenLockMonitor"
    }
}
