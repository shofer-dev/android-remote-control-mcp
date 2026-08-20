package com.danielealbano.androidremotecontrolmcp.services.connector.indicator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks whether a remote session is ACTIVELY DRIVING and drives the two transparency
 * signals: the screen border ([ActivityBorderOverlay]) and the ongoing notification, whose
 * text the connector's foreground service reads off [driving]
 * (`docs/phones/android_remote_control.md` §6.4).
 *
 * There is no way to suppress either from the platform side. That is deliberate and is the
 * difference between a signal and a control: the policy snapshot carries no field for it,
 * there is no action frame that turns it off, and the org's own admin plane has nothing to
 * publish that reaches it. What the holder does about a session they can see goes through
 * the platform (`android-use` pause), where it is auditable — but SEEING it is not something
 * the platform gets a vote on.
 *
 * ## The linger, and why it exists
 *
 * A run taps, reads the screen, and taps again — commands arrive seconds apart. Tying the
 * border strictly to a command in flight would make it strobe, which reads as a rendering
 * glitch rather than as a warning and is exactly how a holder learns to ignore it. So the
 * indicator stays lit for [LINGER_MILLIS] after the last command completes. Erring long is
 * the safe direction here: the failure of showing the border for ten seconds too many is
 * nothing, and the failure of hiding it while a session is between taps is a holder who
 * believes they are alone.
 *
 * Concurrency: commands are serialised by the platform (one in flight per device), but the
 * counter is not a boolean anyway — a redelivery after a reconnect can overlap a completion,
 * so it counts, and the count is what keeps a stale completion from turning the border off
 * under a live command.
 */
@Singleton
class RemoteActivityIndicator
    @Inject
    constructor(
        private val overlay: ActivityBorderOverlay,
    ) {
        private val _driving = MutableStateFlow(false)

        /** True while a remote session is driving (or within the linger of its last command). */
        val driving: StateFlow<Boolean> = _driving.asStateFlow()

        private val lock = Any()
        private var inFlight = 0
        private var lastActivityMillis = 0L

        /** Called as a relayed command begins execution. */
        fun onCommandStarted(nowMillis: Long = System.currentTimeMillis()) {
            synchronized(lock) {
                inFlight++
                lastActivityMillis = nowMillis
            }
            setDriving(true)
        }

        /** Called when a relayed command finishes, successfully or not. */
        fun onCommandFinished(nowMillis: Long = System.currentTimeMillis()) {
            synchronized(lock) {
                if (inFlight > 0) inFlight--
                lastActivityMillis = nowMillis
            }
        }

        /**
         * Re-evaluates the indicator against the clock. Called on a ticker by the connector
         * service, which is what expires the linger; keeping the decision here rather than in
         * a coroutine of its own is what makes the whole rule testable without a scheduler.
         */
        fun tick(nowMillis: Long = System.currentTimeMillis()) {
            val active =
                synchronized(lock) {
                    inFlight > 0 || (lastActivityMillis != 0L && nowMillis - lastActivityMillis < LINGER_MILLIS)
                }
            setDriving(active)
        }

        /** Forces the indicator off — the socket ended, so no session can be driving. */
        fun reset() {
            synchronized(lock) {
                inFlight = 0
                lastActivityMillis = 0L
            }
            setDriving(false)
        }

        private fun setDriving(active: Boolean) {
            if (_driving.value == active) return
            _driving.value = active
            if (active) overlay.show() else overlay.hide()
        }

        companion object {
            /** How long the indicator stays lit after the last command. See the class docs. */
            const val LINGER_MILLIS = 15_000L

            /** How often [tick] should be called for the linger to expire promptly. */
            const val TICK_INTERVAL_MILLIS = 2_000L
        }
    }
