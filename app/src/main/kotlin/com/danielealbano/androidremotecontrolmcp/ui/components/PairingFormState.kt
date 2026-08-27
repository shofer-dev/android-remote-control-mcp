package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.danielealbano.androidremotecontrolmcp.services.connector.PairingInput

/** Which way the holder is entering the pairing. */
internal enum class PairingStep {
    /** The primary path: scan the pairing code shown by the operator console. */
    SCAN,

    /** The fallback: type the edge host and the code. */
    MANUAL,
}

/**
 * The pairing form's own state: which way the holder is entering it, and what they have entered.
 *
 * It is one object rather than three hoisted values so the composables that render it take two
 * parameters instead of eight, and it is held ABOVE the entry/outcome split in
 * [ConnectorPairingDialog] so a refusal does not erase what was typed — a holder retrying after
 * "that code expired" should find the host still filled in.
 *
 * Every derived value delegates to [PairingInput], so the form marks a field wrong on exactly the
 * rule that would have refused it at the write.
 */
@Stable
internal class PairingFormState {
    var step by mutableStateOf(PairingStep.SCAN)
    var host by mutableStateOf("")
    var code by mutableStateOf("")

    val normalisedHost: String get() = PairingInput.normaliseHost(host)
    val normalisedCode: String get() = PairingInput.normaliseCode(code)

    /**
     * Whether to mark the host field. Only a host that is present and wrong is an error: an empty
     * field is where everyone starts, and shouting at it is noise.
     */
    val hostInvalid: Boolean get() = PairingInput.hostError(normalisedHost) == PairingInput.Error.HOST_INVALID

    val canSubmit: Boolean get() = PairingInput.validate(normalisedHost, normalisedCode) == null

    /**
     * A whole pairing link pasted into the host field fills BOTH fields. A phone's camera app
     * decodes a QR code into text and offers to copy it, so this is the path a holder takes when
     * the in-app scanner cannot be used but the code can still be photographed.
     */
    fun onHostTyped(typed: String) {
        val link = PairingInput.parsePairingUri(typed)
        if (link == null) {
            host = typed
        } else {
            host = link.edgeHost
            code = link.code
        }
    }

    companion object {
        /** Survives a rotation mid-entry, which would otherwise empty both fields. */
        val Saver: Saver<PairingFormState, Any> =
            listSaver(
                save = { listOf(it.step.name, it.host, it.code) },
                restore = { saved ->
                    PairingFormState().apply {
                        step = PairingStep.valueOf(saved[0])
                        host = saved[1]
                        code = saved[2]
                    }
                },
            )
    }
}
