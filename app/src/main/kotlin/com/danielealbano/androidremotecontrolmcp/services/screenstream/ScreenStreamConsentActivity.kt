package com.danielealbano.androidremotecontrolmcp.services.screenstream

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * The one place a human grants this phone's screen-capture consent.
 *
 * It is an Activity because it has to be: `createScreenCaptureIntent()` produces an Intent whose
 * result is the grant, and only an Activity can receive an activity result. It is TRANSPARENT and
 * finishes immediately, so what the holder sees is the system's own capture dialog and nothing of
 * ours in front of it — the consent must plainly be the OS asking, not this app.
 *
 * ── Why this is arming rather than a per-stream prompt ─────────────────────────────────────────
 *
 * Android 14 requires consent per capture SESSION, and a session is one `createVirtualDisplay`
 * call. Prompting per drive session would put a dialog on a handset every time an operator opened
 * the viewer. So this consent is taken once and the projection is HELD ([MediaProjectionHolder]);
 * every stream afterwards attaches an encoder to a display that already exists.
 *
 * ── Why it is not started from the background ──────────────────────────────────────────────────
 *
 * It is launched from the app's own UI, while the app is visible. Android's
 * background-activity-start rules would block a service starting it from behind a locked or idle
 * screen, and the block is SILENT — the activity simply never appears — which would look exactly
 * like a holder ignoring a dialog. Arming is therefore a deliberate act on a phone somebody is
 * holding, and the platform learns the outcome from the capability the connector advertises rather
 * than from a promise made here.
 */
class ScreenStreamConsentActivity : ComponentActivity() {
    private val request =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                // The foreground service takes it from here: the OS requires a running
                // mediaProjection foreground service BEFORE the projection is obtained, so the
                // grant is handed over rather than used here.
                ContextCompat.startForegroundService(this, ScreenStreamService.armIntent(this, result.resultCode, data))
            } else {
                Log.i(TAG, "The holder declined screen capture; this phone stays on the frame poll")
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            Log.w(TAG, "This device exposes no MediaProjectionManager; screen streaming is impossible here")
            finish()
            return
        }
        // The default (user-choice) config is deliberate: opting out of app screen sharing would
        // forbid the holder from sharing a single app, and this app has no standing to narrow what
        // its owner is willing to show.
        request.launch(manager.createScreenCaptureIntent())
    }

    companion object {
        private const val TAG = "ScreenStreamConsent"

        /** Opens the OS capture dialog. Call only from a visible app. */
        fun armIntent(context: Context): Intent = Intent(context, ScreenStreamConsentActivity::class.java)
    }
}
