package org.example.otppager

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Narrow read-only adapter for future enrollment. Never establishes provenance or opens a contact. */
internal object RetainedGuardReader {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_ENTRIES = 4096
    const val MAX_COHORT_BYTES = 8 * MAX_BYTES
    private val padId = Regex("[a-f0-9]{32}")
    private val roomId = Regex("[a-f0-9]{64}")
    private val integer = Regex("0|[1-9][0-9]*")
    private val ledgerName = Regex("(station-v2|chat-[a-f0-9]{20})-pads")
    private val claimsName = Regex("chat-index-claims|enr-[a-f0-9]{20}-claims")
    private val aad = "OTP Pager local vault v2".toByteArray(Charsets.UTF_8)

    sealed class Guard {
        data class Claims(val pads: Map<String, String>, val rooms: Map<String, String>) : Guard()
        data class Ledger(val owner: String, val reservations: Map<String, Reservation>) : Guard()
    }
    data class Reservation(val high: Long, val capacity: Int)
    data class Reference(val namespace: String, val sha256: String) {
        init {
            require(claimsName.matches(namespace) || ledgerName.matches(namespace)) { "Not a retained guard namespace" }
            require(roomId.matches(sha256)) { "Invalid guard commitment" }
        }
    }
    data class Verified(val reference: Reference, val guard: Guard, val ciphertextBytes: Int)

    /** Requires an exact caller-supplied commitment. It is NOT proof of when that commitment was made. */
    fun readFrozen(context: Context, reference: Reference): Verified = read(context, reference.namespace, reference.sha256)
    /** Observation only; only a fresh terminal transaction may seal this as pre-cleanup evidence. */
    fun observeFrozen(context: Context, namespace: String): Verified {
        Reference(namespace, "0".repeat(64)) // Confine the namespace before I/O, just like expected-hash reads.
        return read(context, namespace, null)
    }
    private fun read(context: Context, namespace: String, expected: String?): Verified = synchronized(VaultLifecycle.lock) {
        check(GlobalRetirement.isFrozen(context.noBackupFilesDir)) { "Retained guard inspection requires global freeze" }
        readUngated(context.noBackupFilesDir, namespace, expected)
    }
    /** Callers must establish authority first: a freeze, or sealed references from an ACTIVE lineage. */
    private fun readUngated(directory: File, namespace: String, expected: String?): Verified = synchronized(VaultLifecycle.lock) {
        try {
            val base = File(directory, "${namespace}.bin")
            check(VaultLifecycle.variants(base).drop(1).none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
            check(Files.isRegularFile(base.toPath(), LinkOption.NOFOLLOW_LINKS))
            val bytes = readBounded(base)
            try {
                val commitment = PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                check(expected == null || commitment == expected)
                check(bytes.size >= 29 && bytes[0] == 2.toByte())
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                // No key creation, AtomicFile recovery, SecureStore, identity, or contact-vault access.
                val key = keys.getKey("otp-pager-${namespace}", null) as? SecretKey
                    ?: error("Guard key unavailable")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                cipher.updateAAD(aad)
                val clear = cipher.doFinal(bytes, 13, bytes.size - 13)
                try {
                    val guard = parse(namespace, clear)
                    check(VaultLifecycle.variants(base).drop(1).none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
                    Verified(Reference(namespace, commitment), guard, bytes.size)
                } finally { clear.fill(0) }
            } finally { bytes.fill(0) }
        } catch (_: Exception) {
            // Fixed error text; never expose parsed contents or crypto/provider exceptions in UI/logs.
            throw IllegalStateException("Retained reuse guard is unavailable or invalid")
        }
    }
    private fun readBounded(base: File): ByteArray {
        Files.newByteChannel(base.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 29..MAX_BYTES.toLong())
            val out = ByteArrayOutputStream()
            val buffer = java.nio.ByteBuffer.allocate(8192)
            while (true) {
                val count = channel.read(buffer)
                if (count == -1) break
                check(count > 0 && out.size() <= MAX_BYTES - count)
                out.write(buffer.array(), 0, count); buffer.clear()
            }
            return out.toByteArray()
        }
    }
    private fun <K, V> immutable(map: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(map))
    private fun JsonReader.number(): Long {
        check(peek() == JsonToken.NUMBER)
        val token = nextString()
        check(token.length <= 19 && integer.matches(token))
        return token.toLong()
    }
    private fun JsonReader.text(): String { check(peek() == JsonToken.STRING); return nextString() }
    private class Budget { var entries = 0; fun entry() { check(++entries <= MAX_ENTRIES) } }
    private fun owners(reader: JsonReader, ids: Regex, budget: Budget): Map<String, String> {
        val result = linkedMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            budget.entry(); val id = reader.nextName()
            check(ids.matches(id) && !result.containsKey(id))
            val owner = reader.text(); check(VaultLifecycle.isContact(owner))
            result[id] = owner
        }
        reader.endObject(); return result
    }
    private fun numbers(reader: JsonReader, budget: Budget): Map<String, Long> {
        val result = linkedMapOf<String, Long>()
        reader.beginObject()
        while (reader.hasNext()) {
            budget.entry(); val id = reader.nextName()
            check(padId.matches(id) && !result.containsKey(id))
            result[id] = reader.number().also { check(it <= PadProtocol.MAX_SLOTS) }
        }
        reader.endObject(); return result
    }
    /** Fixed-depth streaming schema: duplicates, coercions, unknown fields and trailing data fail. */
    private fun parse(namespace: String, clear: ByteArray): Guard {
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        JsonReader(InputStreamReader(ByteArrayInputStream(clear), decoder)).use { reader ->
            reader.isLenient = false
            val claims = claimsName.matches(namespace)
            val fields = mutableSetOf<String>(); val budget = Budget()
            var pads: Map<String, String>? = null; var rooms: Map<String, String>? = null
            var highs: Map<String, Long>? = null; var capacities: Map<String, Long>? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); check(fields.add(field))
                when (field) {
                    "version" -> check(reader.number() == 2L)
                    "pads" -> if (claims) pads = owners(reader, padId, budget) else highs = numbers(reader, budget)
                    "rooms" -> { check(claims); rooms = owners(reader, roomId, budget) }
                    "capacities" -> { check(!claims); capacities = numbers(reader, budget) }
                    else -> error("Unknown guard field")
                }
            }
            reader.endObject(); check(reader.peek() == JsonToken.END_DOCUMENT)
            check("version" in fields && "pads" in fields)
            if (claims) return Guard.Claims(immutable(checkNotNull(pads)), immutable(checkNotNull(rooms)))
            val values = checkNotNull(highs); val bounds = capacities ?: emptyMap()
            check(values.keys.containsAll(bounds.keys))
            val reservations = values.mapValues { (id, high) ->
                val capacity = (bounds[id] ?: PadProtocol.SLOTS.toLong()).toInt()
                check(capacity in 1..PadProtocol.MAX_SLOTS && high <= capacity)
                Reservation(high, capacity)
            }
            return Guard.Ledger(checkNotNull(ledgerName.matchEntire(namespace)).groupValues[1], immutable(reservations))
        }
    }

    /** Bounded complete supplied cohort, NOT complete discovery or pre-cleanup provenance. */
    fun readCohortFrozen(context: Context, references: List<Reference>, knownRoots: Set<String>): List<Verified> = synchronized(VaultLifecycle.lock) {
        check(GlobalRetirement.isFrozen(context.noBackupFilesDir)) { "Retained guard inspection requires global freeze" }
        readCohort(context.noBackupFilesDir, references, knownRoots)
    }

    /** Pad and room identifiers that no ACTIVE epoch may claim again. */
    data class Exclusions(val pads: Set<String>, val rooms: Set<String>)
    private val activeCache = mutableMapOf<String, Exclusions>()

    /**
     * Every retained cohort sealed by the terminals in [epoch]'s ACTIVE lineage. References come
     * only from authenticated terminal evidence, never from the caller. Missing, altered or
     * unreadable guards throw (fail closed) and are never read as empty. Results are cached per
     * exact sealed commitment set; a cached set only ever keeps enforcing exclusions.
     */
    fun activeExclusions(directory: File, epoch: String): Exclusions = synchronized(VaultLifecycle.lock) {
        val state = EpochState.resolve(directory) as? EpochLineage.State.Active
            ?: throw IllegalStateException("Retained reuse guard is unavailable or invalid")
        check(state.manifest.epoch == epoch && state.chain.isNotEmpty()) { "Retained reuse guard is unavailable or invalid" }
        val key = directory.absolutePath + "|" + epoch + "|" + state.chain.joinToString(",") { it.commitment }
        activeCache[key]?.let { return@synchronized it }
        verifyChain(directory, state.chain).also { activeCache[key] = it }
    }

    /** Authenticates every sealed cohort of [chain] (from [EpochState] only) and returns their union. */
    internal fun verifyChain(directory: File, chain: List<EpochLineage.Terminal>): Exclusions = synchronized(VaultLifecycle.lock) {
        val pads = mutableSetOf<String>(); val rooms = mutableSetOf<String>()
        for (terminal in chain) {
            check(terminal.guards.isNotEmpty()) { "Retained reuse guard is unavailable or invalid" }
            for (record in readCohort(directory, terminal.guards.map { Reference(it.namespace, it.sha256) }, terminal.roots)) {
                when (val guard = record.guard) {
                    is Guard.Claims -> { pads.addAll(guard.pads.keys); rooms.addAll(guard.rooms.keys) }
                    is Guard.Ledger -> pads.addAll(guard.reservations.keys)
                }
            }
        }
        Exclusions(Collections.unmodifiableSet(pads), Collections.unmodifiableSet(rooms))
    }

    private fun readCohort(directory: File, references: List<Reference>, knownRoots: Set<String>): List<Verified> {
        check(references.size in 1..MAX_ENTRIES)
        check(knownRoots.size <= MAX_ENTRIES && knownRoots.all(VaultLifecycle::isContact))
        check(references.map { it.namespace }.toSet().size == references.size)
        check(references.count { claimsName.matches(it.namespace) } == 1)
        val records = mutableListOf<Verified>()
        var bytes = 0L; var claimsEntries = 0; var ledgerEntries = 0
        for (reference in references) {
            val record = readUngated(directory, reference.namespace, reference.sha256)
            bytes += record.ciphertextBytes
            check(bytes <= MAX_COHORT_BYTES)
            when (val guard = record.guard) {
                is Guard.Claims -> claimsEntries += guard.pads.size + guard.rooms.size
                is Guard.Ledger -> ledgerEntries += guard.reservations.size
            }
            // Enforce while assembling: never retain thousands of full per-record maps first.
            check(claimsEntries <= MAX_ENTRIES && ledgerEntries <= MAX_ENTRIES)
            records.add(record)
        }
        validateCohort(records, knownRoots)
        return Collections.unmodifiableList(records.toList())
    }
    /** Consistency only; callers cannot bypass authenticated reads with fabricated parsed records. */
    private fun validateCohort(records: List<Verified>, knownRoots: Set<String>) {
        check(knownRoots.size <= MAX_ENTRIES && knownRoots.all(VaultLifecycle::isContact))
        check(records.size in 1..MAX_ENTRIES)
        check(records.map { it.reference.namespace }.toSet().size == records.size)
        for (record in records) when (val guard = record.guard) {
            is Guard.Claims -> check(claimsName.matches(record.reference.namespace))
            is Guard.Ledger -> check(record.reference.namespace == "${guard.owner}-pads" && guard.owner in knownRoots)
        }
        val claims = records.single { claimsName.matches(it.reference.namespace) }.guard as Guard.Claims
        check((claims.pads.values + claims.rooms.values).all { it in knownRoots })
        val ledgers = records.mapNotNull { it.guard as? Guard.Ledger }.associateBy { it.owner }
        check(ledgers.values.sumOf { it.reservations.size.toLong() } <= MAX_ENTRIES)
        for ((id, owner) in claims.pads) check(ledgers[owner]?.reservations?.containsKey(id) == true)
        for ((owner, ledger) in ledgers) for (id in ledger.reservations.keys) check(claims.pads[id] == owner)
    }
}
