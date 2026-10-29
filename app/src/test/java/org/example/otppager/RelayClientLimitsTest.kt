package org.example.otppager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RelayClientLimitsTest {
    /** A signed REVOKED with both signatures, as relay/server.mjs sends it, must arrive whole. */
    @Test fun aHybridRevokedAnswerFitsTheSmallBodyCap() {
        val e = Base64.getEncoder()
        val seed = PqSignature.newSeed(); val frame = ByteArray(StrictChatProtocol.BYTES) { it.toByte() }
        val body = JSONObject().put("error", "share retired").put("frame", e.encodeToString(frame))
            .put("signature", e.encodeToString(ByteArray(72))).put("pqSignature", e.encodeToString(PqSignature.sign(seed, frame))).toString()
        assertTrue(body.length > 4096) // What the old 4 KB cap cut off.
        val read = JSONObject(RelayClient.readCapped(body.byteInputStream(), RelayClient.SMALL_BODY))
        assertTrue(PqSignature.verify(PqSignature.publicKey(seed), frame, Base64.getDecoder().decode(read.getString("pqSignature"))))
    }
    @Test fun longerAnswersAreCutAtTheCap() {
        assertEquals(RelayClient.SMALL_BODY, RelayClient.readCapped(ByteArray(100_000) { 'a'.code.toByte() }.inputStream(), RelayClient.SMALL_BODY).length)
    }
}
