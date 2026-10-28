package org.example.otppager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.KeyGenerator

/** Manifest storage adapter on an isolated directory; no app vault, index or global state is touched. */
@RunWith(AndroidJUnit4::class)
class EpochManifestsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid manifest state accepted") } catch (_: Exception) {} }
    private fun hex(bytes: Int) = PadProtocol.hex(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    private fun reservationKey(directory: File, epoch: String) {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(EnrollmentStaging.keyAlias(directory, epoch), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests(KeyProperties.DIGEST_SHA256).build()); generator.generateKey()
    }
    private fun deleteKey(directory: File, epoch: String) =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(EnrollmentStaging.keyAlias(directory, epoch)) }

    @Test fun authenticatedCreateReadAppendAndFailClosedOnTamperingVariantsAndStrays() {
        val directory = File(context.cacheDir, "epoch-" + UUID.randomUUID()).apply { check(mkdir()) }
        val epoch = hex(16)
        try {
            reservationKey(directory, epoch)
            val initial = ActiveManifestCodec.record(epoch, hex(16), hex(32), hex(32), hex(32), 1, listOf("chat-" + epoch.take(20)))
            rejected { EpochManifests.create(directory, ActiveManifestCodec.appended(initial, "chat-" + hex(10))) }
            EpochManifests.create(directory, initial)
            assertEquals(initial, EpochManifests.read(directory, epoch))
            assertEquals(listOf(initial), EpochManifests.readAll(directory))
            rejected { EpochManifests.create(directory, initial) } // Never overwritten.

            val root = "chat-" + hex(10)
            val next = EpochManifests.appendRoot(directory, epoch, root)
            assertEquals(2L, next.sequence); assertTrue(root in next.roots)
            assertEquals(next, EpochManifests.read(directory, epoch))
            rejected { EpochManifests.appendRoot(directory, epoch, root) }

            val file = EpochManifests.file(directory, epoch); val good = file.readBytes()
            file.writeBytes(good.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() })
            rejected { EpochManifests.read(directory, epoch) }
            file.writeBytes(good + byteArrayOf(0)); rejected { EpochManifests.read(directory, epoch) }
            file.writeBytes(good); assertEquals(next, EpochManifests.read(directory, epoch))

            val variant = File(file.path + ".bak").apply { writeBytes(good) }
            rejected { EpochManifests.read(directory, epoch) }; rejected { EpochManifests.readAll(directory) }
            assertTrue(variant.delete())
            val stray = File(directory, "epoch-active-unknown.bin").apply { writeText("x") }
            rejected { EpochManifests.readAll(directory) }
            assertTrue(stray.delete())

            // A manifest alone never activates anything: no terminal evidence or PREPARED stage backs it.
            assertEquals(EnrollmentStaging.Presence.PRESENT, EnrollmentStaging.probe(directory))
            assertTrue(EpochState.resolve(directory) is EpochLineage.State.Frozen)
            assertNull(EpochState.active(directory))
            assertTrue(GlobalRetirement.isFrozen(directory))
            assertEquals(VaultGeneration.Capability.Blocked, VaultGeneration.capture(directory))

            deleteKey(directory, epoch)
            rejected { EpochManifests.read(directory, epoch) } // Missing key never reads as valid or empty.
        } finally {
            deleteKey(directory, epoch)
            directory.deleteRecursively()
            EnrollmentStaging.probe(directory)
        }
    }
}
