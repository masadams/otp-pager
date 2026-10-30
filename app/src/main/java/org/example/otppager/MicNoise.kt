package org.example.otppager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Microphone noise as an extra entropy input for pad generation. About 1.5 s of raw 16-bit
 * audio is recorded in memory only, health-tested, conditioned with SHA3-512 and then zeroed.
 * Nothing is stored or sent. Runs on a worker thread.
 *
 * Phones differ a lot here, so it tries the unprocessed source first (sensor noise intact), then
 * the ordinary microphone sources, at 48, 44.1 or 16 kHz, and drops the first 300 ms: many
 * phones return digital silence while the microphone starts, which would fail the health test.
 */
internal object MicNoise {
    class Result(val seed: ByteArray?, val note: String)
    private const val WARM_UP_MS = 300
    private val RATES = intArrayOf(48000, 44100, 16000)

    fun capture(context: Context, millis: Int = 1500): Result {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return Result(null, "Microphone noise not used (no permission). Keys come from both phones' generators.")
        val unprocessed = context.getSystemService(AudioManager::class.java)
            ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val sources = listOfNotNull(MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed },
            MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        var silent = false; var opened = false
        for (source in sources) for (rate in RATES) {
            val raw = record(source, rate, millis) ?: continue
            opened = true
            try {
                when (val seed = fromSamples(raw, rate * 2 * WARM_UP_MS / 1000)) {
                    null -> { silent = true }
                    else -> return Result(seed, "Microphone noise added · health tests passed")
                }
            } finally { raw.fill(0) }
            break // This source records but sounds stuck: try the next source, not another rate.
        }
        return Result(null, when {
            silent -> "The microphone only returned silence, so its noise wasn't used. Check that microphone access is on in Android's quick settings and no other app is recording, then try again. Keys still come from both phones' generators."
            opened -> "Microphone noise not captured; keys come from both phones' generators."
            else -> "The microphone couldn't be opened (another app may be using it). Keys still come from both phones' generators."
        })
    }

    /** Raw PCM16 little-endian with [warmUp] bytes of start-up to drop; the seed, or null if it fails the health test. */
    internal fun fromSamples(raw: ByteArray, warmUp: Int): ByteArray? {
        val start = (warmUp and 1.inv()).coerceIn(0, raw.size)
        val count = (raw.size - start) / 2
        val low = ByteArray(count) { raw[start + it * 2] } // The noisiest byte of each sample.
        try { if (!PadEntropy.noiseHealthy(low)) return null } finally { low.fill(0) }
        val kept = raw.copyOfRange(start, raw.size)
        return try { PadEntropy.condition(kept) } finally { kept.fill(0) }
    }

    /** Warm-up plus [millis] of audio, or null if this source/rate can't record. The caller checked the permission. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun record(source: Int, rate: Int, millis: Int): ByteArray? {
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return null
        val recorder = try {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192))
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
        } catch (_: Exception) { null } ?: return null
        val raw = ByteArray(rate * 2 * (WARM_UP_MS + millis) / 1000)
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) { raw.fill(0); return null }
            var at = 0
            while (at < raw.size) { val n = recorder.read(raw, at, raw.size - at); if (n <= 0) break; at += n }
            if (at < raw.size) { raw.fill(0); return null }
            return raw
        } catch (_: Exception) { raw.fill(0); return null }
        finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }
    }
}
