package org.example.otppager

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections

/** Canonical bounded payload. Authentication and lifecycle authority belong to the store. */
internal object RetirementEvidenceCodec {
    const val MAX_BYTES = 512 * 1024
    const val MAX_ENTRIES = 4096
    private val magic = "OTPTERM1".toByteArray(Charsets.US_ASCII)
    private val rootPattern = Regex("station-v2|chat-[a-f0-9]{20}")
    private val guardPattern = Regex("chat-index-claims|(station-v2|chat-[a-f0-9]{20})-pads")
    private val hex = Regex("[a-f0-9]+")
    enum class Phase { INTENT, SEALED, CLEANED } // No ACTIVE state or enrollment authority.
    data class Guard(val namespace: String, val sha256: String)
    data class Record(val generation: String, val phase: Phase, val roots: Set<String>, val guards: List<Guard>)
    fun record(generation: String, phase: Phase, roots: Collection<String>, guards: Collection<Guard>): Record {
        check(generation.length == 32 && hex.matches(generation))
        check(roots.size <= MAX_ENTRIES && roots.all(rootPattern::matches) && roots.toSet().size == roots.size)
        check(guards.size <= MAX_ENTRIES && guards.map { it.namespace }.toSet().size == guards.size)
        check(guards.all { guardPattern.matches(it.namespace) && it.sha256.length == 64 && hex.matches(it.sha256) })
        if (phase == Phase.INTENT) check(roots.isEmpty() && guards.isEmpty()) else {
            check("station-v2" in roots && guards.count { it.namespace == "chat-index-claims" } == 1)
            check(guards.filter { it.namespace != "chat-index-claims" }.all { it.namespace.removeSuffix("-pads") in roots })
        }
        return Record(generation, phase, Collections.unmodifiableSet(roots.toSortedSet()),
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
        val record = record(value.generation, value.phase, value.roots, value.guards)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.write(magic); stream.writeByte(record.phase.ordinal); stream.write(bytes(record.generation))
            stream.writeShort(record.roots.size); record.roots.forEach { stream.text(it) }
            stream.writeShort(record.guards.size)
            record.guards.forEach { stream.text(it.namespace); stream.write(bytes(it.sha256)) }
        }
        return out.toByteArray().also { check(it.size <= MAX_BYTES - 32) }
    }
    fun decode(payload: ByteArray): Record {
        check(payload.size in 29..MAX_BYTES - 32)
        val record = DataInputStream(ByteArrayInputStream(payload)).use { stream ->
            val header = ByteArray(8); stream.readFully(header); check(header.contentEquals(magic))
            val phase = Phase.entries.getOrNull(stream.readUnsignedByte()) ?: error("Unknown evidence phase")
            val nonce = ByteArray(16); stream.readFully(nonce)
            val roots = mutableListOf<String>(); val rootCount = stream.readUnsignedShort(); check(rootCount <= MAX_ENTRIES)
            repeat(rootCount) { roots.add(stream.text()) }
            val guards = mutableListOf<Guard>(); val guardCount = stream.readUnsignedShort(); check(guardCount <= MAX_ENTRIES)
            repeat(guardCount) { val name = stream.text(); val sha = ByteArray(32); stream.readFully(sha); guards.add(Guard(name, hex(sha))) }
            check(stream.available() == 0)
            record(hex(nonce), phase, roots, guards)
        }
        // Reject alternate ordering, duplicates, extensions and noncanonical encodings.
        check(encode(record).contentEquals(payload))
        return record
    }
}
