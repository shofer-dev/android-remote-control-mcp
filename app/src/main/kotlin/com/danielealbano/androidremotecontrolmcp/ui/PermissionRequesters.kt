package com.danielealbano.androidremotecontrolmcp.ui

import androidx.compose.runtime.Immutable

/**
 * The four runtime-permission requests, bundled.
 *
 * They travel together everywhere — [MainActivity] owns the `ActivityResultLauncher`s and every
 * screen below only forwards them — so passing them as one value says what four adjacent lambda
 * parameters did not: that this is one capability, not four unrelated callbacks that happen to
 * have the same shape. It is also what keeps the screens that carry them under the parameter
 * count the linter enforces, without a suppression.
 *
 * [Immutable] because the lambdas are stable for the Activity's lifetime; Compose can then skip
 * recomposing a screen whose only other inputs are unchanged.
 */
@Immutable
data class PermissionRequesters(
    val onRequestNotification: () -> Unit,
    val onRequestCamera: () -> Unit,
    val onRequestMicrophone: () -> Unit,
    val onRequestLocation: () -> Unit,
)
