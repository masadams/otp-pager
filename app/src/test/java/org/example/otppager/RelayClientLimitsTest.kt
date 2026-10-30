package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RelayClientLimitsTest {
    /** A signed REVOKED with both signatures, as relay/server.mjs sends it, must arrive whole. */
    @Test fun aHybridRevokedAnswerFitsTheSmallBodyCap() {
        val e = Base64.getEncoder()
        val seed = PqSignature.newSeed(); val frame = ByteArray(StrictChatProtocol.BYTES) { it.toByte() }
        val pq = e.encodeToString(PqSignature.sign(seed, frame))
        // Same shape as the relay's JSON (org.json is only a stub in plain unit tests).
        val body = "{\"error\":\"share retired\",\"frame\":\"${e.encodeToString(frame)}\",\"signature\":\"${e.encodeToString(ByteArray(72))}\",\"pqSignature\":\"$pq\"}"
        assertTrue(body.length > 4096) // What the old 4 KB cap cut off.
        val read = RelayClient.readCapped(body.byteInputStream(), RelayClient.SMALL_BODY)
        assertEquals(body, read)
        val signature = read.substringAfter("\"pqSignature\":\"").substringBefore('"')
        assertTrue(PqSignature.verify(PqSignature.publicKey(seed), frame, Base64.getDecoder().decode(signature)))
    }
    @Test fun longerAnswersAreCutAtTheCap() {
        assertEquals(RelayClient.SMALL_BODY, RelayClient.readCapped(ByteArray(100_000) { 'a'.code.toByte() }.inputStream(), RelayClient.SMALL_BODY).length)
    }
}
