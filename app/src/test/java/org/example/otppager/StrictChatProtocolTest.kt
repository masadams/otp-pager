package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Properties

class StrictChatProtocolTest {
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun card(key: KeyPair) = Base64.getEncoder().encodeToString(key.public.encoded)
    private fun sign(key: KeyPair, bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initSign(key.private); update(bytes); sign()
    }
    private fun rejects(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Invalid strict authorization accepted")
    }
    private val now = 1_700_000_000_000L
    private fun request(device: KeyPair, authority: KeyPair) = StrictChatProtocol.Frame(
        StrictChatProtocol.Kind.UNLOCK, 'A', ByteArray(16) { it.toByte() },
        StrictChatProtocol.fingerprint(card(device)), StrictChatProtocol.fingerprint(card(authority)), 7,
        ByteArray(32) { (it + 64).toByte() }, now, now + 60_000, ByteArray(32))
    private fun grant(request: StrictChatProtocol.Frame) = request.copy(kind = StrictChatProtocol.Kind.GRANT, share = ByteArray(32) { 99 })

    @Test fun kotlinCodecMatchesPythonFixturesForEveryKind() {
        val fixtures = Properties().apply { StrictChatProtocolTest::class.java.getResourceAsStream("/strict-chat-v1.properties")!!.use { load(it) } }
        assertEquals(6, fixtures.size)
        for (name in fixtures.stringPropertyNames()) {
            val bytes = fixtures.getProperty(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertEquals(StrictChatProtocol.BYTES, bytes.size)
            val decoded = StrictChatProtocol.decode(bytes)
            assertEquals(name, decoded.kind.name.lowercase())
            assertArrayEquals(bytes, StrictChatProtocol.encode(decoded))
        }
    }
    @Test fun malformedFramesAndInvalidLeaseBoundsFailClosed() {
        val r = request(key(), key()); val bytes = StrictChatProtocol.encode(r)
        for (size in listOf(0, 177, 179)) rejects { StrictChatProtocol.decode(bytes.copyOf(size)) }
        for (offset in listOf(0, 8, 9)) rejects { StrictChatProtocol.decode(bytes.copyOf().also { it[offset] = 0 }) }
        for (bad in listOf(r.copy(epoch = 0), r.copy(epoch = -1), r.copy(expires = now),
            r.copy(expires = now + 60_001), r.copy(issued = -1), r.copy(share = ByteArray(32) { 1 }),
            r.copy(generation = ByteArray(15)), r.copy(role = 'C'))) rejects { StrictChatProtocol.encode(bad) }
        rejects { StrictChatProtocol.encode(r.copy(kind = StrictChatProtocol.Kind.REVOKE)) }
        val revoke = r.copy(kind = StrictChatProtocol.Kind.REVOKE, issued = 0, expires = 0)
        assertEquals(StrictChatProtocol.Kind.REVOKE, StrictChatProtocol.decode(StrictChatProtocol.encode(revoke)).kind)
    }
    @Test fun signaturesBindEveryByteAndTheExpectedSignerRole() {
        val device = key(); val authority = key(); val r = request(device, authority)
        val bytes = StrictChatProtocol.encode(r); val signature = sign(device, bytes)
        StrictChatProtocol.verified(bytes, signature, card(device), false)
        for (i in bytes.indices) rejects {
            StrictChatProtocol.verified(bytes.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, signature, card(device), false)
        }
        rejects { StrictChatProtocol.verified(bytes, signature, card(authority), false) }
        rejects { StrictChatProtocol.verified(bytes, signature, card(device), true) }
        val wrongPin = StrictChatProtocol.encode(r.copy(device = ByteArray(32)))
        rejects { StrictChatProtocol.verified(wrongPin, sign(device, wrongPin), card(device), false) }
    }
    @Test fun validGrantIsOneUseAndCancellationRejectsLateResponses() {
        val device = key(); val authority = key(); val r = request(device, authority)
        val g = StrictChatProtocol.encode(grant(r)); val signature = sign(authority, g)
        val pending = StrictChatProtocol.PendingUnlock(r, 100)
        assertArrayEquals(ByteArray(32) { 99 }, pending.accept(g, signature, card(authority), now + 1, 101))
        rejects { pending.accept(g, signature, card(authority), now + 2, 102) }
        val cancelled = StrictChatProtocol.PendingUnlock(r, 100); cancelled.cancel()
        rejects { cancelled.accept(g, signature, card(authority), now + 1, 101) }
    }
    @Test fun signedCrossContextGrantsAreRejected() {
        val device = key(); val authority = key(); val r = request(device, authority); val valid = grant(r)
        for (bad in listOf(valid.copy(role = 'B'), valid.copy(epoch = 8), valid.copy(generation = ByteArray(16)),
            valid.copy(device = ByteArray(32)), valid.copy(nonce = ByteArray(32)),
            valid.copy(authority = ByteArray(32)), valid.copy(issued = now + 1), valid.copy(expires = now + 59_999),
            valid.copy(kind = StrictChatProtocol.Kind.ENROLLED, share = ByteArray(32)))) {
            val bytes = StrictChatProtocol.encode(bad)
            rejects { StrictChatProtocol.PendingUnlock(r, 100).accept(bytes, sign(authority, bytes), card(authority), now + 2, 102) }
        }
    }
    @Test fun elapsedDeadlineSurvivesWallClockRollbackAndRequestIsDefensivelyCaptured() {
        val authority = key(); val r = request(key(), authority)
        val bytes = StrictChatProtocol.encode(grant(r)); val signature = sign(authority, bytes)
        for ((wall, elapsed) in listOf(now - 1 to 101L, now + 60_000 to 101L, now + 1 to 60_100L, now + 1 to 99L))
            rejects { StrictChatProtocol.PendingUnlock(r, 100).accept(bytes, signature, card(authority), wall, elapsed) }
        val pending = StrictChatProtocol.PendingUnlock(r, 100)
        r.nonce.fill(0)
        assertArrayEquals(ByteArray(32) { 99 }, pending.accept(bytes, signature, card(authority), now + 1, 101))
    }
}
