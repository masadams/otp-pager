package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Uses Android's JSON implementation and the serializer used by StationEngine.sync. */
@RunWith(AndroidJUnit4::class)
class PacketSerializationTest {
    @Test fun productionPostPreservesFixedPacketsAcrossPayloadKindsAndSizes() {
        val binding = ByteArray(32) { 3 }
        val pad = PadProtocol.material(PadProtocol.generate(binding), binding)
        try {
            val payloads = listOf("", "x", "é".repeat(1000), "y".repeat(PadProtocol.BLOCK - 2),
                "{\"kind\":\"receipt\",\"for\":\"${"a".repeat(32)}\"}", "{\"kind\":\"ready\",\"protocol\":4}")
            for ((i, payload) in payloads.withIndex()) {
                val packet = PadProtocol.seal(pad, 'A', i + 1L, payload).second
                val body = RelayClient.packetBody(packet)
                assertEquals(packet, JSONObject(body).getString("packet"))
                assertEquals(1, JSONObject(body).length())
                assertEquals(5540, packet.length)
                assertEquals(4154, Base64.getDecoder().decode(packet).size)
                // Every character is ASCII. Only slash escaping can increase this JSON body's length.
                val overhead = "{\"packet\":\"\"}".length
                val escapedSlashes = body.windowed(2).count { it == "\\/" }
                assertEquals(overhead + packet.length + escapedSlashes, body.toByteArray(Charsets.UTF_8).size)
            }
        } finally { pad.fill(0) }
    }
    @Test fun escapingVariationIsMeasuredRatherThanAssumingConstantHttpBodyLength() {
        // Deterministic synthetic ciphertext distinguishes Android serialization from string interpolation.
        val withoutSlash = "A".repeat(5540)
        val withSlash = "/".repeat(5540)
        val first = RelayClient.packetBody(withoutSlash)
        val second = RelayClient.packetBody(withSlash)
        assertEquals(withSlash, JSONObject(second).getString("packet"))
        assertEquals(5540, second.length - first.length)
        assertTrue(second.contains("\\/"))
    }
}
