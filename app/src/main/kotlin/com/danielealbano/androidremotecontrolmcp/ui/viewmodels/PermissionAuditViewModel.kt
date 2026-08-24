package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionAuditState
import com.danielealbano.androidremotecontrolmcp.services.permissions.PermissionAuditor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * What the permissions card renders.
 *
 * Deliberately thin: the audit is a SINGLETON ([PermissionAuditor]) rather than ViewModel state,
 * because the same evaluation drives the notification from a WorkManager tick where no ViewModel
 * exists. A second copy of the decision in the UI layer is how the card and the shade end up
 * disagreeing about whether anything is wrong.
 */
@HiltViewModel
class PermissionAuditViewModel
    @Inject
    constructor(
        private val auditor: PermissionAuditor,
    ) : ViewModel() {
        val state: StateFlow<PermissionAuditState> = auditor.state

        /** Re-reads every grant. Called when the screen resumes, so a fix made in Settings shows. */
        fun refresh() {
            auditor.refresh()
        }

        /** Hides the card until the next evaluation; never longer (see [PermissionAuditor]). */
        fun dismiss() {
            auditor.dismiss()
        }
    }
