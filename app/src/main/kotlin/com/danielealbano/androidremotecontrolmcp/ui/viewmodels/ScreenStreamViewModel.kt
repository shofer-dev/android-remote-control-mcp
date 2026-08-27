package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.screenstream.MediaProjectionHolder
import com.danielealbano.androidremotecontrolmcp.services.screenstream.ScreenStreamConsentActivity
import com.danielealbano.androidremotecontrolmcp.services.screenstream.ScreenStreamService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The holder's control over remote screen viewing.
 *
 * There is exactly one piece of state and it is not stored here: whether a live screen-capture
 * consent is held ([MediaProjectionHolder.armed]). Nothing about this is a preference to persist —
 * the OS can revoke the grant at any moment, and a remembered "the holder wanted this on" would go
 * on claiming a capability the phone no longer has, which is the one failure the platform's
 * transport negotiation exists to prevent.
 */
@HiltViewModel
class ScreenStreamViewModel
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        holder: MediaProjectionHolder,
    ) : ViewModel() {
        /** Whether this phone can currently serve a live screen stream. */
        val armed: StateFlow<Boolean> = holder.armed

        /**
         * Opens the OS capture dialog.
         *
         * Started as an ACTIVITY from the app's own UI, deliberately: Android blocks a background
         * activity start silently, so arming from a service would look exactly like a holder
         * ignoring a dialog that in fact never appeared.
         */
        fun arm() {
            val intent = ScreenStreamConsentActivity.armIntent(appContext)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }

        /** Releases the grant. The next stream needs a fresh consent. */
        fun disarm() {
            ContextCompat.startForegroundService(appContext, ScreenStreamService.disarmIntent(appContext))
        }
    }
