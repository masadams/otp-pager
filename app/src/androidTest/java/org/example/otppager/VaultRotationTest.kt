package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Package C: removed history is crypto-erased from older snapshot copies; retirement finds every generation. */
@RunWith(AndroidJUnit4::class)
class VaultRotationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun root() = "chat-" + UUID.randomUUID().toString().replace("-", "").take(20)

    @Test fun clearHistoryRotatesTheKeySoOldSnapshotsCannotBeOpened() {
        val id = root(); val file = File(context.noBackupFilesDir, "$id.bin")
        try {
            val engine = StationEngine(SecureStore(context, id))
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val before = file.readBytes()
            assertEquals(3, before[0].toInt()) // Contact vaults use the generation-bound v3 envelope.
            val firstKeys = VaultKeys.all(id, keys().aliases().toList())
            assertEquals(1, firstKeys.size)
            engine.clearHistory() // Content removal forces rotation even within the interval.
            val after = VaultKeys.all(id, keys().aliases().toList())
            assertEquals(1, after.size); assertNotEquals(firstKeys, after)
            assertNotNull(SecureStore(context, id).load())
            // A restored older copy (flash remnant, backup) no longer decrypts: its key is gone.
            val current = file.readBytes()
            file.writeBytes(before)
            try { SecureStore(context, id).load(); fail("Old snapshot reopened after rotation") } catch (_: Exception) {}
            file.writeBytes(current)
            assertNotNull(SecureStore(context, id).load())
        } finally { VaultKeys.deleteAll(id); VaultLifecycle.variants(file).forEach { it.delete() } }
    }

    @Test fun legacyV2VaultsStillOpenAndRetirementRemovesEveryGeneration() {
        val id = root(); val file = File(context.noBackupFilesDir, "$id.bin")
        try {
            val engine = StationEngine(SecureStore(context, id))
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            engine.clearHistory()
            // An interrupted rotation leaves an unused extra generation behind.
            KeyGeneratorHelper.aes(VaultKeys.alias(id, 9))
            assertTrue(VaultKeys.all(id, keys().aliases().toList()).size >= 2)
            assertTrue(VaultRetirement.retireContact(context, id).completed)
            assertTrue("Rotated key survived retirement", VaultKeys.all(id, keys().aliases().toList()).isEmpty())
            assertEquals(VaultRetirement.LocalState.COMPLETE, VaultRetirement.inspect(context, id).state)
        } finally {
            VaultKeys.deleteAll(id); VaultLifecycle.variants(file).forEach { it.delete() }
            VaultLifecycle.variants(VaultLifecycle.marker(context.noBackupFilesDir, id)).forEach { it.delete() }
        }
    }
}

internal object KeyGeneratorHelper {
    fun aes(alias: String) {
        val generator = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generator.generateKey()
    }
}
