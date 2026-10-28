package org.example.otppager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections

/**
 * Canonical `OTPACT01` epoch manifest payload (design: docs/ACTIVE_EPOCH_DESIGN.md).
 *
 * Pure encoding only. Authentication, storage and the activation transaction belong to a
 * later slice; nothing in this build writes or trusts a manifest for access decisions.
 */
internal object ActiveManifestCodec {
    const val MAX_ROOTS = 512
    const val HEADER_BYTES = 8 + 16 + 16 + 32 + 32 + 32 + 4 + 2
    const val MAX_PAYLOAD_BYTES = HEADER_BYTES + MAX_ROOTS * 10
    private const val MAX_SEQUENCE = 0xFFFF_FFFFL
    private val magic = "OTPACT01".toByteArray(Charsets.US_ASCII)
    private val hex = Regex("[a-f0-9]+")
    private val rootPattern = Regex("chat-[a-f0-9]{20}")
    private val zeroHash = "0".repeat(64)

    data class Record(
        val epoch: String,
        val predecessorGeneration: String,
        val predecessorCommitment: String,
        val indexHash: String,
        val claimsHash: String,
        val sequence: Long,
        val roots: List<String>,
    ) {
        val index get() = "enr-" + epoch.take(20)
        val claims get() = "$index-claims"
        val initialRoot get() = "chat-" + epoch.take(20)
    }

    /** Validates and canonicalizes (roots sorted ascending, unmodifiable). */
    fun record(epoch: String, predecessorGeneration: String, predecessorCommitment: String,
               indexHash: String, claimsHash: String, sequence: Long, roots: Collection<String>): Record {
        check(epoch.length == 32 && hex.matches(epoch)) { "Invalid epoch" }
        check(predecessorGeneration.length == 32 && hex.matches(predecessorGeneration)) { "Invalid predecessor" }
        for (hash in listOf(predecessorCommitment, indexHash, claimsHash))
            check(hash.length == 64 && hex.matches(hash) && hash != zeroHash) { "Invalid commitment" }
        check(sequence in 1..MAX_SEQUENCE) { "Invalid sequence" }
        check(roots.size in 1..MAX_ROOTS && roots.toSet().size == roots.size && roots.all(rootPattern::matches)) { "Invalid roots" }
        val sorted = roots.sorted()
        check("chat-" + epoch.take(20) in sorted) { "Initial root missing" }
        return Record(epoch, predecessorGeneration, predecessorCommitment, indexHash, claimsHash, sequence,
            Collections.unmodifiableList(sorted))
    }

    /** The only growth path: one new root, next sequence. Roots are never removed. */
    fun appended(current: Record, root: String): Record {
        check(root !in current.roots) { "Root already listed" }
        return record(current.epoch, current.predecessorGeneration, current.predecessorCommitment,
            current.indexHash, current.claimsHash, current.sequence + 1, current.roots + root)
    }

    private fun binary(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(data: ByteArray) = data.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(value: Record): ByteArray {
        val r = record(value.epoch, value.predecessorGeneration, value.predecessorCommitment,
            value.indexHash, value.claimsHash, value.sequence, value.roots)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.write(magic); s.write(binary(r.epoch)); s.write(binary(r.predecessorGeneration))
            s.write(binary(r.predecessorCommitment)); s.write(binary(r.indexHash)); s.write(binary(r.claimsHash))
            s.writeInt(r.sequence.toInt()); s.writeShort(r.roots.size)
            r.roots.forEach { s.write(binary(it.removePrefix("chat-"))) }
        }
        return out.toByteArray().also { check(it.size == HEADER_BYTES + 10 * r.roots.size) }
    }

    fun decode(payload: ByteArray): Record {
        check(payload.size in HEADER_BYTES + 10..MAX_PAYLOAD_BYTES) { "Invalid manifest size" }
        val r = DataInputStream(ByteArrayInputStream(payload)).use { s ->
            val header = ByteArray(8); s.readFully(header); check(header.contentEquals(magic)) { "Unknown manifest format" }
            fun field(size: Int) = ByteArray(size).also(s::readFully).let(::hex)
            val epoch = field(16); val generation = field(16); val commitment = field(32)
            val indexHash = field(32); val claimsHash = field(32)
            val sequence = s.readInt().toLong() and MAX_SEQUENCE
            val count = s.readUnsignedShort()
            check(count in 1..MAX_ROOTS && payload.size == HEADER_BYTES + 10 * count) { "Root count mismatch" }
            val roots = List(count) { "chat-" + field(10) }
            check(roots.zipWithNext().all { (a, b) -> a < b }) { "Roots not strictly ascending" }
            record(epoch, generation, commitment, indexHash, claimsHash, sequence, roots)
        }
        check(encode(r).contentEquals(payload)) { "Noncanonical manifest" }
        return r
    }
}
