package org.example.otppager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections

/**
 * Canonical `OTPTERM2` payload: terminal evidence for wiping an ACTIVE epoch (slice B3d).
 * Same phases and authority limits as `OTPTERM1`, plus the epoch it terminates. Roots are the
 * epoch's contact roots; guards are the epoch's own claims delta and contact ledgers only.
 */
internal object EpochTerminalCodec {
    const val MAX_BYTES = RetirementEvidenceCodec.MAX_BYTES
    const val MAX_ENTRIES = RetirementEvidenceCodec.MAX_ENTRIES
    private val magic = "OTPTERM2".toByteArray(Charsets.US_ASCII)
    private val rootPattern = Regex("chat-[a-f0-9]{20}")
    private val ledgerPattern = Regex("(chat-[a-f0-9]{20})-pads")
    private val hex = Regex("[a-f0-9]+")
    data class Record(val generation: String, val epoch: String, val phase: RetirementEvidenceCodec.Phase,
                      val roots: Set<String>, val guards: List<RetirementEvidenceCodec.Guard>) {
        val claims get() = "enr-" + epoch.take(20) + "-claims"
        val initialRoot get() = "chat-" + epoch.take(20)
    }

    fun record(generation: String, epoch: String, phase: RetirementEvidenceCodec.Phase, roots: Collection<String>,
               guards: Collection<RetirementEvidenceCodec.Guard>): Record {
        check(generation.length == 32 && hex.matches(generation)) { "Invalid generation" }
        check(epoch.length == 32 && hex.matches(epoch) && epoch != generation) { "Invalid epoch" }
        check(roots.size <= MAX_ENTRIES && roots.all(rootPattern::matches) && roots.toSet().size == roots.size) { "Invalid roots" }
        check(guards.size <= MAX_ENTRIES && guards.map { it.namespace }.toSet().size == guards.size) { "Duplicate guards" }
        check(guards.all { it.sha256.length == 64 && hex.matches(it.sha256) }) { "Invalid guard commitment" }
        val claims = "enr-" + epoch.take(20) + "-claims"
        if (phase == RetirementEvidenceCodec.Phase.INTENT) check(roots.isEmpty() && guards.isEmpty()) { "INTENT carries no scope" }
        else {
            check("chat-" + epoch.take(20) in roots) { "Initial root missing" }
            check(guards.count { it.namespace == claims } == 1) { "Epoch claims guard missing" }
            for (guard in guards) if (guard.namespace != claims) {
                val owner = ledgerPattern.matchEntire(guard.namespace)?.groupValues?.get(1)
                check(owner != null && owner in roots) { "Guard outside epoch scope" }
            }
        }
        return Record(generation, epoch, phase, Collections.unmodifiableSet(roots.toSortedSet()),
            Collections.unmodifiableList(guards.sortedBy { it.namespace }))
    }

    private fun bytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun DataOutputStream.text(value: String) {
        check(value.length in 1..40 && value.all { it.code in 1..127 })
        writeByte(value.length); write(value.toByteArray(Charsets.US_ASCII))
    }
    private fun DataInputStream.text(): String {
        val length = readUnsignedByte(); check(length in 1..40)
        val data = ByteArray(length); readFully(data); check(data.all { it.toInt() in 1..127 })
        return data.toString(Charsets.US_ASCII)
    }

    fun encode(value: Record): ByteArray {
        val r = record(value.generation, value.epoch, value.phase, value.roots, value.guards)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { s ->
            s.write(magic); s.writeByte(r.phase.ordinal); s.write(bytes(r.generation)); s.write(bytes(r.epoch))
            s.writeShort(r.roots.size); r.roots.forEach { s.text(it) }
            s.writeShort(r.guards.size); r.guards.forEach { s.text(it.namespace); s.write(bytes(it.sha256)) }
        }
        return out.toByteArray().also { check(it.size <= MAX_BYTES - 32) }
    }

    fun decode(payload: ByteArray): Record {
        check(payload.size in 45..MAX_BYTES - 32)
        val r = DataInputStream(ByteArrayInputStream(payload)).use { s ->
            val header = ByteArray(8); s.readFully(header); check(header.contentEquals(magic)) { "Unknown terminal format" }
            val phase = RetirementEvidenceCodec.Phase.entries.getOrNull(s.readUnsignedByte()) ?: error("Unknown phase")
            val generation = ByteArray(16).also(s::readFully).let(::hex)
            val epoch = ByteArray(16).also(s::readFully).let(::hex)
            val rootCount = s.readUnsignedShort(); check(rootCount <= MAX_ENTRIES)
            val roots = List(rootCount) { s.text() }
            val guardCount = s.readUnsignedShort(); check(guardCount <= MAX_ENTRIES)
            val guards = List(guardCount) { RetirementEvidenceCodec.Guard(s.text(), ByteArray(32).also(s::readFully).let(::hex)) }
            check(s.available() == 0)
            record(generation, epoch, phase, roots, guards)
        }
        check(encode(r).contentEquals(payload)) { "Noncanonical terminal record" }
        return r
    }
}
