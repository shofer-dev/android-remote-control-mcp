package com.danielealbano.androidremotecontrolmcp.services.connector

import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.ConnectorJson
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.Frame
import com.danielealbano.androidremotecontrolmcp.services.connector.protocol.FrameType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the Gap B re-attach wire contract: the `attach_sig` frame carries the accepted
 * `terms_hash` on a re-consent re-attach, and omits it entirely on a normal attach. The signing
 * input (the nonce) is unchanged — only an additional optional field is asserted.
 */
class PlatformConnectorAttachSigTest {
    @Test
    fun `re-attach attach_sig carries the accepted terms_hash`() {
        val frame = PlatformConnector.buildAttachSig("sig-base64", "abc123hash")
        assertEquals(FrameType.ATTACH_SIG, frame.type)
        assertEquals("sig-base64", frame.signature)
        assertEquals("abc123hash", frame.termsHash)

        val json = ConnectorJson.encodeToString(Frame.serializer(), frame)
        assertTrue(json.contains("\"terms_hash\":\"abc123hash\""))
        assertTrue(json.contains("\"signature\":\"sig-base64\""))
    }

    @Test
    fun `normal attach_sig omits terms_hash from the wire`() {
        val frame = PlatformConnector.buildAttachSig("sig-base64", null)
        assertEquals(null, frame.termsHash)

        val json = ConnectorJson.encodeToString(Frame.serializer(), frame)
        assertFalse(json.contains("terms_hash"))
        assertTrue(json.contains("\"signature\":\"sig-base64\""))
    }
}
