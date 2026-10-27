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
    private val aad = "OTP Pager local vault v2".toByteArray(Charsets.UTF_8)

    sealed class Guard {
        data class Claims(val pads: Map<String, String>, val rooms: Map<String, String>) : Guard()
        data class Ledger(val owner: String, val reservations: Map<String, Reservation>) : Guard()
    }
    data class Reservation(val high: Long, val capacity: Int)
    data class Reference(val namespace: String, val sha256: String) {
        init {
            require(namespace == "chat-index-claims" || ledgerName.matches(namespace)) { "Not a retained guard namespace" }
            require(roomId.matches(sha256)) { "Invalid guard commitment" }
        }
    }
    data class Verified(val reference: Reference, val guard: Guard, val ciphertextBytes: Int)

    /** Requires an exact caller-supplied commitment. It is NOT proof of when that commitment was made. */
    fun readFrozen(context: Context, reference: Reference): Verified = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir
        check(GlobalRetirement.isFrozen(directory)) { "Retained guard inspection requires global freeze" }
        try {
            val base = File(directory, "${reference.namespace}.bin")
            check(VaultLifecycle.variants(base).drop(1).none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
            check(Files.isRegularFile(base.toPath(), LinkOption.NOFOLLOW_LINKS))
            val bytes = readBounded(base)
            try {
                check(PadProtocol.hex(MessageDigest.getInstance("SHA-256").digest(bytes)) == reference.sha256)
                check(bytes.size >= 29 && bytes[0] == 2.toByte())
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                // No key creation, AtomicFile recovery, SecureStore, identity, or contact-vault access.
                val key = keys.getKey("otp-pager-${reference.namespace}", null) as? SecretKey
                    ?: error("Guard key unavailable")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
                cipher.updateAAD(aad)
                val clear = cipher.doFinal(bytes, 13, bytes.size - 13)
                try {
                    val guard = parse(reference.namespace, clear)
                    check(VaultLifecycle.variants(base).drop(1).none { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) })
                    Verified(reference, guard, bytes.size)
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
            val claims = namespace == "chat-index-claims"
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
        check(references.size in 1..MAX_ENTRIES)
        check(knownRoots.size <= MAX_ENTRIES && knownRoots.all(VaultLifecycle::isContact))
        check(references.map { it.namespace }.toSet().size == references.size)
        check(references.count { it.namespace == "chat-index-claims" } == 1)
        val records = mutableListOf<Verified>()
        var bytes = 0L; var claimsEntries = 0; var ledgerEntries = 0
        for (reference in references) {
            val record = readFrozen(context, reference)
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
        Collections.unmodifiableList(records.toList())
    }
    /** Consistency only; callers cannot bypass authenticated reads with fabricated parsed records. */
    private fun validateCohort(records: List<Verified>, knownRoots: Set<String>) {
        check(knownRoots.size <= MAX_ENTRIES && knownRoots.all(VaultLifecycle::isContact))
        check(records.size in 1..MAX_ENTRIES)
        check(records.map { it.reference.namespace }.toSet().size == records.size)
        for (record in records) when (val guard = record.guard) {
            is Guard.Claims -> check(record.reference.namespace == "chat-index-claims")
            is Guard.Ledger -> check(record.reference.namespace == "${guard.owner}-pads" && guard.owner in knownRoots)
        }
        val claims = records.single { it.reference.namespace == "chat-index-claims" }.guard as Guard.Claims
        check((claims.pads.values + claims.rooms.values).all { it in knownRoots })
        val ledgers = records.mapNotNull { it.guard as? Guard.Ledger }.associateBy { it.owner }
        check(ledgers.values.sumOf { it.reservations.size.toLong() } <= MAX_ENTRIES)
        for ((id, owner) in claims.pads) check(ledgers[owner]?.reservations?.containsKey(id) == true)
        for ((owner, ledger) in ledgers) for (id in ledger.reservations.keys) check(claims.pads[id] == owner)
    }
}
