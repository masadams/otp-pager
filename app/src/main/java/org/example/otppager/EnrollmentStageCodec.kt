package org.example.otppager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Fixed, canonical local staging payload. No ACTIVE encoding. */
internal object EnrollmentStageCodec {
    const val PAYLOAD_BYTES = 137
    const val ENVELOPE_BYTES = PAYLOAD_BYTES + 32
    const val MAX_ATTEMPTS = 32
    private val magic = "OTPSTG01".toByteArray(Charsets.US_ASCII)
    private val hex = Regex("[a-f0-9]+")
    val zeroHash = "0".repeat(64)
    enum class Phase { PREPARING, PREPARED, ABANDONED }
    data class Record(val epoch: String, val terminalGeneration: String, val terminalCommitment: String,
                      val phase: Phase, val indexHash: String = zeroHash, val claimsHash: String = zeroHash) {
        val index get() = "enr-" + epoch.take(20)
        val claims get() = "$index-claims"
        val root get() = "chat-" + epoch.take(20)
    }
    private fun validate(r: Record) {
        check(r.epoch.length == 32 && hex.matches(r.epoch))
        check(r.terminalGeneration.length == 32 && hex.matches(r.terminalGeneration))
        for (hash in listOf(r.terminalCommitment, r.indexHash, r.claimsHash)) check(hash.length == 64 && hex.matches(hash))
        check(r.terminalCommitment != zeroHash)
        if (r.phase == Phase.PREPARED) check(r.indexHash != zeroHash && r.claimsHash != zeroHash)
        else check(r.indexHash == zeroHash && r.claimsHash == zeroHash)
    }
    private fun binary(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun encode(r: Record): ByteArray {
        validate(r); val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.write(magic); s.writeByte(r.phase.ordinal); s.write(binary(r.epoch)); s.write(binary(r.terminalGeneration))
            s.write(binary(r.terminalCommitment)); s.write(binary(r.indexHash)); s.write(binary(r.claimsHash))
        }
        return out.toByteArray().also { check(it.size == PAYLOAD_BYTES) }
    }
    fun decode(payload: ByteArray): Record {
        check(payload.size == PAYLOAD_BYTES)
        val r = DataInputStream(ByteArrayInputStream(payload)).use { s ->
            val header = ByteArray(8); s.readFully(header); check(header.contentEquals(magic))
            val phase = Phase.entries.getOrNull(s.readUnsignedByte()) ?: error("Unknown staging phase")
            fun field(size: Int) = ByteArray(size).also(s::readFully).let(::hex)
            Record(field(16), field(16), field(32), phase, field(32), field(32))
        }
        validate(r); check(encode(r).contentEquals(payload)); return r
    }
}
