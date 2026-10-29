package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class MicNoiseTest {
    /** PCM16 little-endian: [silent] bytes of digital silence, then quiet random noise. */
    private fun audio(silent: Int, total: Int): ByteArray {
        val random = Random(7)
        return ByteArray(total) { i -> if (i < silent) 0 else if (i % 2 == 0) random.nextInt(256).toByte() else (random.nextInt(3) - 1).toByte() }
    }

    @Test fun startUpSilenceIsDroppedBeforeTheHealthTest() {
        val rate = 48000; val warmUp = rate * 2 * 300 / 1000
        val raw = audio(silent = warmUp - 200, total = warmUp + rate * 3) // Silence just inside the warm-up.
        assertNull("Silence at the start used to fail the whole recording", MicNoise.fromSamples(raw, 0))
        assertEquals(64, MicNoise.fromSamples(raw, warmUp)?.size)
    }

    @Test fun aSilentMicrophoneIsRefused() {
        assertNull(MicNoise.fromSamples(ByteArray(48000 * 2 * 2), 48000 * 2 * 300 / 1000)) // Privacy toggle off: all zeros.
        assertNull(MicNoise.fromSamples(audio(0, 1000), 0)) // Too short to assess.
    }

    @Test fun differentNoiseGivesDifferentSeeds() {
        val a = MicNoise.fromSamples(audio(0, 40000), 0)!!
        val b = MicNoise.fromSamples(audio(0, 40000).also { it[1000] = (it[1000] + 1).toByte() }, 0)!!
        assertFalse(a.contentEquals(b))
    }
}
