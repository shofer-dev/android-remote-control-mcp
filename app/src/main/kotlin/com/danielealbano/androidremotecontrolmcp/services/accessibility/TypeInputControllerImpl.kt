package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import javax.inject.Inject

/**
 * Implementation of [TypeInputController] that delegates to the
 * [AccessibilityInputConnection] obtained from the [McpAccessibilityService]'s
 * [InputMethod] instance.
 *
 * **API floor**: the accessibility IME plane (`android.accessibilityservice.InputMethod` and its
 * `AccessibilityInputConnection`) arrived in API 33, while this app installs from API 31. The whole
 * plane therefore lives in [TiramisuTypeInputController], which is CONSTRUCTED ONLY on a build that
 * has it: below 33 the delegate is null, no class referencing `InputMethod` is ever loaded, and
 * every operation answers "no input connection" — the same answer the tools already handle for a
 * field that cannot be typed into. The natural-typing tool family is consequently unavailable on
 * Android 12; nothing else in the app is affected.
 *
 * **Threading**: The AccessibilityInputConnection is an IPC proxy managed by
 * the accessibility framework — NOT a View-bound InputConnection. Methods can
 * be called safely from any thread. If runtime testing reveals thread-safety
 * issues, the [TypeInputController] interface methods would need to be changed
 * to `suspend` to enable `withContext(Dispatchers.Main)`.
 *
 * **Concurrency**: This class is stateless and safe to call from any thread.
 * Callers must use the file-level `typeOperationMutex` in the typing tools
 * to serialize operations and prevent interleaved character commits.
 *
 * **Return values**: The underlying AccessibilityInputConnection methods return
 * `void`. The Boolean return here indicates IC availability only — NOT whether
 * the target field accepted the operation.
 */
class TypeInputControllerImpl
    @Inject
    constructor() : TypeInputController {
        private val delegate: TypeInputController? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TiramisuTypeInputController()
            } else {
                null
            }

        override fun isReady(): Boolean = delegate?.isReady() == true

        override fun commitText(
            text: CharSequence,
            newCursorPosition: Int,
        ): Boolean = delegate?.commitText(text, newCursorPosition) == true

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean = delegate?.setSelection(start, end) == true

        override fun getSurroundingText(
            beforeLength: Int,
            afterLength: Int,
            flags: Int,
        ): SurroundingText? = delegate?.getSurroundingText(beforeLength, afterLength, flags)

        override fun performContextMenuAction(id: Int): Boolean = delegate?.performContextMenuAction(id) == true

        override fun sendKeyEvent(event: KeyEvent): Boolean = delegate?.sendKeyEvent(event) == true

        override fun deleteSurroundingText(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean = delegate?.deleteSurroundingText(beforeLength, afterLength) == true
    }

/**
 * The real accessibility-IME calls, all of which require API 33.
 *
 * All methods access the singleton [McpAccessibilityService.inputMethodInstance]
 * to get the current [AccessibilityInputConnection].
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class TiramisuTypeInputController : TypeInputController {
    private fun getInputConnection() = McpAccessibilityService.inputMethodInstance?.getCurrentInputConnection()

    override fun isReady(): Boolean =
        McpAccessibilityService.inputMethodInstance?.getCurrentInputStarted() == true &&
            getInputConnection() != null

    override fun commitText(
        text: CharSequence,
        newCursorPosition: Int,
    ): Boolean {
        val ic = getInputConnection() ?: return false
        ic.commitText(text, newCursorPosition, null)
        return true
    }

    override fun setSelection(
        start: Int,
        end: Int,
    ): Boolean {
        val ic = getInputConnection() ?: return false
        ic.setSelection(start, end)
        return true
    }

    override fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText? = getInputConnection()?.getSurroundingText(beforeLength, afterLength, flags)

    override fun performContextMenuAction(id: Int): Boolean {
        val ic = getInputConnection() ?: return false
        ic.performContextMenuAction(id)
        return true
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val ic = getInputConnection() ?: return false
        ic.sendKeyEvent(event)
        return true
    }

    override fun deleteSurroundingText(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean {
        val ic = getInputConnection() ?: return false
        ic.deleteSurroundingText(beforeLength, afterLength)
        return true
    }
}
