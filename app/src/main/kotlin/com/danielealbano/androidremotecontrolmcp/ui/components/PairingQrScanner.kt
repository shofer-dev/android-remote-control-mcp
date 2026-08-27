// Composables are PascalCase by convention, which detekt's FunctionNaming cannot see past.
@file:Suppress("FunctionNaming")

package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.danielealbano.androidremotecontrolmcp.services.connector.PairingInput
import com.danielealbano.androidremotecontrolmcp.services.connector.PairingQrAnalyzer
import java.util.concurrent.Executors

private const val TAG = "MCP:PairingScanner"

/**
 * The viewfinder the holder points at the console's pairing QR code.
 *
 * It is a plain CameraX preview plus one analysis use case, bound to the composition's own
 * lifecycle so the camera is released the moment the pairing dialog goes away — a scanner that
 * outlives its dialog is a camera light that stays on for no reason anybody can see.
 *
 * The caller is responsible for the CAMERA permission: this composable assumes it is held, because
 * the decision of what to show a holder who has refused it (a rationale, and the way to type the
 * code instead) belongs to the surface that has somewhere else to send them.
 *
 * [onScanned] fires at most once — the latch is in [PairingQrAnalyzer] — and always on the main
 * thread, so it can move Compose state directly.
 */
@Composable
fun PairingQrScanner(
    onScanned: (PairingInput.Pairing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val previewView = remember(context) { PreviewView(context) }

    DisposableEffect(previewView, lifecycleOwner) {
        val binding =
            ScannerBinding(
                context = context,
                lifecycleOwner = lifecycleOwner,
                previewView = previewView,
                onScanned = { pairing -> currentOnScanned(pairing) },
            )
        binding.start()
        onDispose { binding.stop() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * One scanner's camera resources, from bind to unbind.
 *
 * A class rather than inline effect code because acquiring the camera is ASYNCHRONOUS — the
 * provider arrives on a listener — so the dispose path can run before the bind completes. Holding
 * the state here makes that orderly: a [stop] before the provider arrives is remembered and the
 * late bind is skipped, instead of leaving a camera bound to a composition that no longer exists.
 *
 * Only this scanner's own use cases are unbound. `unbindAll` would also tear down whatever else
 * holds the camera, which on this device is the camera MCP tool.
 */
private class ScannerBinding(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onScanned: (PairingInput.Pairing) -> Unit,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var stopped = false

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped) return@addListener
            runCatching { bind(future.get()) }
                .onFailure { Log.w(TAG, "Could not start the pairing scanner camera", it) }
        }, mainExecutor)
    }

    fun stop() {
        stopped = true
        provider?.unbind(preview, analysis)
        provider = null
        preview = null
        analysis = null
        analysisExecutor.shutdown()
    }

    private fun bind(cameraProvider: ProcessCameraProvider) {
        val newPreview =
            Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val analyzer =
            PairingQrAnalyzer { pairing ->
                // The decode happens on the analysis thread; the caller moves Compose state.
                mainExecutor.execute { onScanned(pairing) }
            }
        val newAnalysis =
            ImageAnalysis
                .Builder()
                // Only the newest frame matters: a queue of stale frames would decode a code the
                // holder has already moved the phone away from.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, analyzer) }
        cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, newPreview, newAnalysis)
        provider = cameraProvider
        preview = newPreview
        analysis = newAnalysis
    }
}
