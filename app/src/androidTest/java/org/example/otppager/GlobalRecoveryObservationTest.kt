package org.example.otppager

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GlobalRecoveryObservationTest {
    @Test fun activeObservationAndRecoveryRefusalCannotCreateGlobalIntentOrKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "global-observe-" + UUID.randomUUID())
        check(directory.mkdir())
        val fixture = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = keys.aliases().toList().toSet()
        try {
            repeat(2) { assertEquals(GlobalRetirement.LocalState.NOT_FROZEN, GlobalRetirement.inspect(fixture).state) }
            try { GlobalRetirement.retryExisting(fixture); fail("Recovery control initiated global destruction") }
            catch (_: IllegalStateException) {}
            assertTrue(checkNotNull(directory.list()).isEmpty())
            assertEquals(before, KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.aliases().toList().toSet())
        } finally { directory.deleteRecursively() }
    }
    @Test fun unavailableDirectoryNeverLooksLikeACompletedCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "global-unavailable-" + UUID.randomUUID())
        directory.writeText("not a directory")
        val fixture = object : ContextWrapper(context) { override fun getNoBackupFilesDir() = directory }
        try {
            assertEquals(GlobalRetirement.LocalState.UNAVAILABLE, GlobalRetirement.inspect(fixture).state)
            assertEquals("not a directory", directory.readText())
        } finally { directory.delete() }
    }
}
