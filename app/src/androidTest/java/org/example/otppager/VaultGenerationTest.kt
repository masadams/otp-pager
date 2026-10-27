package org.example.otppager

import android.content.Context
import android.content.ContextWrapper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class VaultGenerationTest {
    private fun rejected(action: () -> Unit) { try { action(); fail("Stale owner or blocked generation accepted") } catch (_: Exception) {} }
    private fun capability(store: SecureStore) = SecureStore::class.java.getDeclaredField("generation").apply { isAccessible = true }.get(store)
    @Test fun capturedOwnersIdentityLeasesAndSiblingsNeverRebindToCurrentGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "generation-" + UUID.randomUUID()).apply { check(mkdir()) }
        val fixture = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = directory
            override fun getApplicationContext(): Context = this
        }
        val namespace = "generation-" + UUID.randomUUID().toString().take(12)
        val old = SecureStore(fixture, namespace); val draft = old.sibling("draft")
        val markerAlias = EnrollmentStaging.keyAlias(directory, "1".repeat(32))
        var lease: ContactOperations.Lease? = null
        try {
            old.save(JSONObject().put("version", 2)); draft.save(JSONObject().put("version", 2))
            val identity = old.identity(); identity.card(true); lease = old.operation()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder(markerAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build()); generator.generateKey()
            assertEquals(EnrollmentStaging.Presence.PRESENT, EnrollmentStaging.probe(directory))
            val blocked = SecureStore(fixture, namespace)
            assertEquals(VaultGeneration.Capability.LEGACY, capability(old))
            assertEquals(VaultGeneration.Capability.LEGACY, capability(old.sibling("other")))
            assertEquals(VaultGeneration.Capability.BLOCKED, capability(blocked))
            rejected { old.load() }; rejected { draft.save(JSONObject().put("version", 2)) }; rejected { old.forget() }
            rejected { identity.card(false) }; rejected { identity.sign(byteArrayOf(1)) }; rejected { identity.forget() }
            rejected { checkNotNull(lease).check() }
            rejected { old.sibling("other").load() }
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(markerAlias) } // Test-only external state loss.
            assertEquals(EnrollmentStaging.Presence.NONE, EnrollmentStaging.probe(directory))
            // A captured BLOCKED owner remains blocked even if external deletion makes current state look legacy.
            rejected { blocked.load() }; rejected { blocked.forget() }; rejected { blocked.sibling("other").load() }
            rejected { blocked.identity().forget() }
        } finally {
            lease?.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(markerAlias) }
            EnrollmentStaging.probe(directory)
            old.forget(); draft.forget(); directory.deleteRecursively()
        }
    }
}
