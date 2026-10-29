package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class PadEntropyTest {
    private fun fails(block: () -> Unit) { try { block(); fail("Health test passed bad output") } catch (_: PadEntropy.HealthFailure) {} }
    @Test fun goodGeneratorOutputPassesAFullSizePad() {
        val monitor = PadEntropy.OutputMonitor(); val chunk = ByteArray(32768); val random = PadEntropy.generator(null)
        repeat(2048) { random.nextBytes(chunk); monitor.check(chunk) } // 64 MiB, the largest pad.
    }
    @Test fun stuckBiasedOrRepeatingOutputIsRejected() {
        fails { PadEntropy.OutputMonitor().check(ByteArray(64)) }
        fails { PadEntropy.OutputMonitor().check(ByteArray(512) { if (it % 20 == 0) 7 else (it * 31 + 1).toByte() }.also { b -> for (i in 0 until 512 step 25) b[i] = 7 }) }
        val block = ByteArray(32).also(SecureRandom()::nextBytes)
        fails { PadEntropy.OutputMonitor().check(block + block) }
    }
    @Test fun noiseHealthTestRejectsSilenceAndShortCaptures() {
        assertFalse(PadEntropy.noiseHealthy(ByteArray(72000)))                 // muted microphone
        assertFalse(PadEntropy.noiseHealthy(ByteArray(100).also(SecureRandom()::nextBytes))) // too short
        assertTrue(PadEntropy.noiseHealthy(ByteArray(72000).also(SecureRandom()::nextBytes)))
        val random = SecureRandom()
        assertFalse(PadEntropy.noiseHealthy(ByteArray(72000) { if (random.nextInt(10) == 0) random.nextInt(256).toByte() else 5 })) // 90% one value
    }
    @Test fun conditioningIsSha3_512() {
        // NIST SHA3-512("abc") test vector.
        assertEquals("b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
            PadProtocol.hex(PadEntropy.condition("abc".toByteArray())))
    }
}
