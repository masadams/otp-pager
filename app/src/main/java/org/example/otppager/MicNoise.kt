package org.example.otppager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone noise as an extra entropy input for pad generation. About 1.5 s of raw 16-bit
 * audio is recorded in memory only, health-tested, conditioned with SHA3-512 and then zeroed.
 * Nothing is stored or sent. Runs on a worker thread.
 */
internal object MicNoise {
    class Result(val seed: ByteArray?, val note: String)

    fun capture(context: Context, millis: Int = 1500): Result {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return Result(null, "Microphone noise not used (no permission). Keys come from both phones' generators.")
        val rate = 48000
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return Result(null, "Microphone noise not available on this phone.")
        val raw = ByteArray(rate * 2 * millis / 1000)
        // Unprocessed audio keeps the sensor noise that noise suppression would remove; not every phone offers it.
        @Suppress("MissingPermission") // Checked above.
        fun open(source: Int) = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        } catch (_: Exception) { null }
        val recorder = open(MediaRecorder.AudioSource.UNPROCESSED) ?: open(MediaRecorder.AudioSource.MIC)
            ?: return Result(null, "Microphone noise not available on this phone.")
        try {
            recorder.startRecording()
            var at = 0
            while (at < raw.size) { val n = recorder.read(raw, at, raw.size - at); if (n <= 0) break; at += n }
            if (at < raw.size) return Result(null, "Microphone noise not captured; keys come from both phones' generators.")
            val low = ByteArray(raw.size / 2) { raw[it * 2] } // PCM16 little-endian: the noisiest byte.
            return try {
                if (!PadEntropy.noiseHealthy(low)) Result(null, "Microphone noise failed its health test (silent or stuck), so it wasn't used.")
                else Result(PadEntropy.condition(raw), "Microphone noise added · health tests passed")
            } finally { low.fill(0) }
        } catch (_: Exception) { return Result(null, "Microphone noise not captured; keys come from both phones' generators.") }
        finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release(); raw.fill(0) }
    }
}
