package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RetiredContactTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private fun cleanup(index: String, roots: Collection<String>) {
        for (id in roots) {
            listOf(id, "$id-draft", "$id-pads").forEach { SecureStore(context, it).forget() }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
        }
        SecureStore(context, index).forget(); SecureStore(context, "$index-claims").forget()
    }
    @Test fun metadataMigrationPreservesVaultBytesAndRejectsUnknownVersions() {
        val index = "migration-" + UUID.randomUUID().toString().take(8); val id = root()
        try {
            val station = StationEngine(SecureStore(context, id))
            station.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            station.generatePad().fill(0)
            val vault = File(context.noBackupFilesDir, "$id.bin").readBytes()
            val ledger = File(context.noBackupFilesDir, "$id-pads.bin").readBytes()
            val old = JSONObject().put("version", 2).put("active", id).put("chats", JSONArray()
                .put(JSONObject().put("id", id).put("name", "Friend")))
            SecureStore(context, index).save(old)
            val book = ChatBook(context, index, id)
            assertEquals(id, book.active().id); assertEquals(id, book.active().entryId)
            assertEquals(3, SecureStore(context, index).load()!!.getInt("version"))
            assertArrayEquals(vault, File(context.noBackupFilesDir, "$id.bin").readBytes())
            assertArrayEquals(ledger, File(context.noBackupFilesDir, "$id-pads.bin").readBytes())
            SecureStore(context, index).save(old.put("version", 99))
            try { ChatBook(context, index, id); fail("Unknown index reset") } catch (_: IllegalStateException) {}
            assertEquals(99, SecureStore(context, index).load()!!.getInt("version"))
        } finally { cleanup(index, listOf(id)) }
    }
    @Test fun replacementIsEmptyKeepsStableEntryAndClaimsAndCannotBeUndoneByStaleBook() {
        val index = "replace-" + UUID.randomUUID().toString().take(8); val id = root()
        val roots = mutableListOf(id)
        try {
            val book = ChatBook(context, index, id); book.rename("Alice")
            val engine = book.engine(); val secret = Protocol.randomSecret()
            engine.configure("https://pager.example.com", "t".repeat(43), secret, "A")
            engine.generatePad().fill(0); engine.view() // Populate cached plaintext/view owner.
            val stale = ChatBook(context, index, id)
            val neighbor = book.add("Bob", null); roots.add(neighbor.id)
            book.engine(neighbor.id).configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val neighborBytes = File(context.noBackupFilesDir, "${neighbor.id}.bin").readBytes()
            val before = SecureStore(context, index).load()!!
            val claims = SecureStore(context, "$index-claims").load()!!.toString()
            val ledger = File(context.noBackupFilesDir, "$id-pads.bin").readBytes()
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            assertNull("Engine retains operational snapshot", engine.state)
            val cached = StationEngine::class.java.getDeclaredField("cached").apply { isAccessible = true }
            assertNull(cached.get(engine))
            book.refresh(id); assertTrue(book.summary(id).retired); assertFalse(book.summary(id).eligible)
            val fresh = book.replaceRetired(id); roots.add(fresh.id)
            assertNotEquals(id, fresh.id); assertEquals(id, fresh.entryId); assertEquals("Alice", fresh.name)
            assertNull(book.engine().state); assertNull(SecureStore(context, fresh.id).sibling("draft").load())
            assertFalse(book.engine().view().canSend)
            assertArrayEquals(ledger, File(context.noBackupFilesDir, "$id-pads.bin").readBytes())
            assertEquals(claims, SecureStore(context, "$index-claims").load()!!.toString())
            assertArrayEquals(neighborBytes, File(context.noBackupFilesDir, "${neighbor.id}.bin").readBytes())
            // A pre-retirement object reads current metadata before mutating it.
            stale.rename("Alice again")
            assertEquals(fresh.id, ChatBook(context, index, id).active().id)
            assertEquals(2, stale.chats().size)
            try { stale.select(id); fail("Stale generation selected") } catch (_: IllegalArgumentException) {}
            try { book.engine().configure("https://pager.example.com", "t".repeat(43), secret, "A"); fail("Old secret reused") } catch (_: IllegalArgumentException) {}
            // Restoring an older index cannot override the independent terminal journal.
            SecureStore(context, index).save(before)
            val restored = ChatBook(context, index, id)
            try { restored.engine(id); fail("Restored index reopened old generation") } catch (_: IllegalStateException) {}
            val retried = restored.replaceRetired(id); roots.add(retried.id)
            assertNotEquals(fresh.id, retried.id); assertEquals(id, retried.entryId)
            assertNull(restored.engine().state)
        } finally { cleanup(index, roots) }
    }
    @Test fun incompleteCleanupNeverPublishesReplacementAndActivePairingCannotBeReplaced() {
        val index = "incomplete-" + UUID.randomUUID().toString().take(8); val id = root()
        val roots = mutableListOf(id)
        val obstruction = File(context.noBackupFilesDir, "$id.bin")
        try {
            val book = ChatBook(context, index, id)
            try { book.replaceRetired(id); fail("Active generation replaced") } catch (_: IllegalStateException) {}
            assertTrue(obstruction.mkdir()); File(obstruction, "blocked").writeText("test obstruction")
            assertFalse(VaultRetirement.retireContact(context, id).completed)
            val before = SecureStore(context, index).load()!!.toString()
            try { book.replaceRetired(id); fail("Incomplete cleanup hidden") } catch (_: IllegalStateException) {}
            assertEquals(before, SecureStore(context, index).load()!!.toString())
            obstruction.deleteRecursively()
            val fresh = book.replaceRetired(id); roots.add(fresh.id)
            assertNotEquals(id, fresh.id); assertNull(book.engine().state)
        } finally { obstruction.deleteRecursively(); cleanup(index, roots) }
    }
    @Test fun missingIndexCannotReinitializeRetiredLegacyRoot() {
        val index = "lost-" + UUID.randomUUID().toString().take(8); val id = root()
        try {
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            try { ChatBook(context, index, id); fail("Missing index reopened terminal root") } catch (_: IllegalStateException) {}
            assertNull(SecureStore(context, index).load())
        } finally { cleanup(index, listOf(id)) }
    }
}
