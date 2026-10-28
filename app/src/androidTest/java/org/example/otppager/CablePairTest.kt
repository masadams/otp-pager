package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CablePairTest {
    @Test fun automaticPairingWorksWithServerSettingsOnEitherUsbSide() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (configuredHost in listOf(true, false)) {
            val sa = SecureStore(context, "cable-a"); val sb = SecureStore(context, "cable-b")
            val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
            val pool = Executors.newFixedThreadPool(2)
            val ain = PipedInputStream(16384); val bout = PipedOutputStream(ain)
            val bin = PipedInputStream(16384)
            val capture = java.io.ByteArrayOutputStream()
            fun tapped(stream: java.io.OutputStream) = object : java.io.FilterOutputStream(stream) {
                override fun write(value: Int) { synchronized(capture) { capture.write(value) }; out.write(value) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { synchronized(capture) { capture.write(bytes, offset, count) }; out.write(bytes, offset, count) }
            }
            val aout = tapped(PipedOutputStream(bin)); val tappedBout = tapped(bout)
            try {
                val a = StationEngine(sa); val b = StationEngine(sb)
                (if (configuredHost) a else b).configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
                val codes = java.util.concurrent.CopyOnWriteArrayList<String>(); val ready = CountDownLatch(2)
                val review: (String, String) -> Unit = { code, _ -> codes.add(code); ready.countDown(); check(ready.await(20, TimeUnit.SECONDS)) }
                val af = pool.submit { CableProtocol.run(true, ain, aout, a, review) {} }
                val bf = pool.submit { CableProtocol.run(false, bin, tappedBout, b, review) {} }
                af.get(30, TimeUnit.SECONDS); bf.get(30, TimeUnit.SECONDS)
                val captured = capture.toString("ISO-8859-1")
                for (name in listOf("token", "secret", "padKeys")) {
                    val value = a.state!!.getString(name)
                    assertFalse("USB exposed $name", captured.contains(value.take(128)))
                }
                val padText = java.util.Base64.getEncoder().encodeToString(a.padSnapshot())
                assertFalse("USB exposed pad", captured.contains(padText.substring(3000, 3128)))
                assertFalse("Pad left in the vault", a.state!!.has("pad"))
                assertFalse(captured.contains("\"kind\":\"setup\""))
                assertEquals(2, codes.size); assertEquals(codes[0], codes[1])
                assertEquals("A", a.view().role); assertEquals("B", b.view().role)
                assertEquals(a.view().padCheck, b.view().padCheck)
                assertEquals(a.view().ownDevice, b.view().peerDevice); assertEquals(b.view().ownDevice, a.view().peerDevice)
                assertEquals(127, a.view().padRemaining); assertEquals(127, b.view().padRemaining)
                assertTrue(a.view().canSend); assertTrue(b.view().canSend)
                assertTrue(StationEngine(sa).view().pairingComplete); assertTrue(StationEngine(sb).view().pairingComplete)
                val pad = a.padSnapshot()
                a.send("CABLE PAIRED")
                val wire = a.state!!.getJSONArray("outbox").getJSONObject(0).getString("packet")
                // The snapshot has used slots zeroed, so name the pad by its stored id rather than re-hashing it.
                val stable = a.state!!.getString("padId").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                assertTrue(IdentityProtocol.isSigned(PadProtocol.open(pad, wire, 'A', stable).payload)); pad.fill(0)
                val packets = mutableListOf<String>()
                val relay = object : RelayTransport {
                    override fun request(endpoint: String, room: String, token: String, method: String, body: String?, cursor: Long): String {
                        if (method == "POST") {
                            val packet = org.json.JSONObject(body!!).getString("packet"); if (packet !in packets) packets.add(packet)
                            return "{}"
                        }
                        return org.json.JSONObject().put("protocol", 2).put("epoch", "cable-test")
                            .put("messages", org.json.JSONArray(packets.mapIndexedNotNull { i, packet ->
                                if (i + 1 > cursor) org.json.JSONObject().put("seq", i + 1).put("packet", packet) else null
                            })).toString()
                    }
                }
                val sender = StationEngine(sa, relay); val recipient = StationEngine(sb, relay)
                sender.sync(); recipient.sync(); sender.sync()
                assertEquals("CABLE PAIRED", recipient.view().messages.single().text)
                assertTrue(recipient.view().messages.single().deviceVerified)
                assertEquals("delivered", sender.view().messages.single().status)
                try { a.requireCableEligible(); fail("Operational pairing was replaceable") } catch (_: IllegalStateException) {}
            } finally {
                listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }
                pool.shutdownNow(); stores.forEach { it.forget(); it.sibling("pads").forget() }
            }
        }
    }
    @Test fun largePadsStreamIntoStorageOnBothSidesWithoutPassingThroughTheVault() {
        for (mib in listOf(16, 32, 64)) checkStreamedPad(mib * PadProtocol.MATERIAL_BYTES)
    }
    private fun checkStreamedPad(size: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "cable-large-a"); val sb = SecureStore(context, "cable-large-b")
        val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
        val pool = Executors.newFixedThreadPool(2)
        val ain = PipedInputStream(65536); val bout = PipedOutputStream(ain)
        val bin = PipedInputStream(65536); val aout = PipedOutputStream(bin)
        val slots = PadProtocol.slotsFor(size)
        try {
            val a = StationEngine(sa); val b = StationEngine(sb)
            a.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "B")
            val ready = CountDownLatch(2)
            val review: (String, String) -> Unit = { _, _ -> ready.countDown(); check(ready.await(20, TimeUnit.SECONDS)) }
            val progress = java.util.concurrent.CopyOnWriteArrayList<String>()
            val af = pool.submit { CableProtocol.run(true, ain, aout, a, review, { progress.add(it) }, false, size) }
            val bf = pool.submit { CableProtocol.run(false, bin, bout, b, review, { progress.add(it) }, false, size) }
            af.get(240, TimeUnit.SECONDS); bf.get(240, TimeUnit.SECONDS)
            assertTrue(progress.any { it.startsWith("Sending message keys") } && progress.any { it == "Receiving message keys · 100%" })
            for (engine in listOf(a, b)) {
                assertEquals(slots, engine.view().padRemaining)
                assertFalse(engine.state!!.has("pad")); assertTrue(engine.view().canSend)
            }
            assertEquals(a.view().padCheck, b.view().padCheck)
            for (store in stores) {
                assertTrue(java.io.File(context.noBackupFilesDir, "${store.namespace}.bin").length() < 1_000_000)
                assertEquals(PadMaterial.HEADER + 2L * slots * PadMaterial.RECORD,
                    PadMaterial.file(context.noBackupFilesDir, store.namespace, a.state!!.getString("padId")).length())
            }
            // The slot at the far end of the B half is identical on both phones.
            val last = slots.toLong()
            fun slot(engine: StationEngine, store: SecureStore): ByteArray {
                val keys = java.util.Base64.getDecoder().decode(engine.state!!.getString("padKeys"))
                return PadMaterial.slot(PadMaterial.file(context.noBackupFilesDir, store.namespace, engine.state!!.getString("padId")), engine.state!!.getString("padId"), keys, 'B', last)
            }
            assertArrayEquals(slot(a, sa), slot(b, sb))
        } finally {
            listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }
            pool.shutdownNow()
            stores.forEach { it.forget(); it.sibling("pads").forget(); PadMaterial.deleteAll(context.noBackupFilesDir, it.namespace) }
        }
    }
    @Test fun decliningConfirmationSendsNoCredentialsOrPad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sa = SecureStore(context, "cable-decline-a"); val sb = SecureStore(context, "cable-decline-b")
        val stores = listOf(sa, sb); stores.forEach { it.forget(); it.sibling("pads").forget() }
        val pool = Executors.newFixedThreadPool(2)
        val ain = PipedInputStream(16384); val bin = PipedInputStream(16384)
        val capture = java.io.ByteArrayOutputStream()
        val aout = object : java.io.FilterOutputStream(PipedOutputStream(bin)) {
            override fun write(value: Int) { capture.write(value); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { capture.write(bytes, offset, count); out.write(bytes, offset, count) }
        }
        val bout = PipedOutputStream(ain)
        try {
            val a = StationEngine(sa); val b = StationEngine(sb)
            val secret = Protocol.randomSecret(); val token = "s".repeat(43)
            a.configure("https://pager.example.com", token, secret, "A")
            val original = a.state.toString()
            val reject: (String, String) -> Unit = { _, _ -> error("User declined") }
            val af = pool.submit { CableProtocol.run(true, ain, aout, a, reject) {} }
            val bf = pool.submit { CableProtocol.run(false, bin, bout, b, reject) {} }
            for (future in listOf(af, bf)) {
                try { future.get(20, TimeUnit.SECONDS); fail("Declined pairing activated") } catch (_: java.util.concurrent.ExecutionException) {}
            }
            val wire = capture.toString("UTF-8")
            assertFalse(wire.contains(token)); assertFalse(wire.contains(secret)); assertFalse(wire.contains("\"pad\""))
            assertEquals(original, a.state.toString()); assertNull(b.state)
            assertNull(sa.sibling("pads").load()); assertNull(sb.sibling("pads").load())
        } finally {
            listOf(ain, bin, aout, bout).forEach { try { it.close() } catch (_: Exception) {} }
            pool.shutdownNow(); stores.forEach { it.forget(); it.sibling("pads").forget() }
        }
    }
    @Test fun oldPlaintextProtocolCannotDowngradeOrActivatePairing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "cable-downgrade")
        store.forget(); store.sibling("pads").forget()
        try {
            val bytes = java.io.ByteArrayOutputStream()
            val hello = "{\"kind\":\"hello\",\"version\":2}".toByteArray()
            java.io.DataOutputStream(bytes).apply { writeInt(hello.size); write(hello) }
            val output = java.io.ByteArrayOutputStream()
            try {
                CableProtocol.run(false, java.io.ByteArrayInputStream(bytes.toByteArray()), output, StationEngine(store), { _, _ -> fail("Old protocol reached review") }) {}
                fail("Plaintext downgrade accepted")
            } catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("Upgrade both devices")) }
            assertEquals(0, output.size()); assertNull(store.load()); assertNull(store.sibling("pads").load())
        } finally { store.forget(); store.sibling("pads").forget() }
    }
    @Test fun stagedAndInterruptedPairingRetiresPadAndCannotSend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "cable-stage"); store.forget(); store.sibling("pads").forget()
        val other = DeviceIdentity("cable-other-test")
        try {
            val station = StationEngine(store); station.cableIdentity()
            val secret = Protocol.randomSecret(); val master = Protocol.master(secret)
            val bundle = PadProtocol.generate(PadProtocol.binding(master)); master.fill(0)
            val setup = org.json.JSONObject().put("endpoint", "https://pager.example.com").put("token", "t".repeat(43))
                .put("secret", secret).put("pad", java.util.Base64.getEncoder().encodeToString(bundle)); bundle.fill(0)
            val peer = other.card(true)
            station.stageCable(setup, "A", peer)
            assertNull(store.load()) // Staging has not activated a station vault.
            val restart = StationEngine(store)
            try { restart.stageCable(setup, "A", peer); fail("Exposed pad reused after interruption") } catch (_: IllegalArgumentException) {}
            station.commitCable(false)
            val pending = StationEngine(store)
            assertFalse(pending.view().canSend)
            try { pending.send("blocked"); fail("Partial pairing sent traffic") } catch (_: IllegalStateException) {}
            try { pending.sync(); fail("Partial pairing contacted relay") } catch (_: IllegalStateException) {}
            assertEquals(127, pending.view().padRemaining)
            val previousSecret = pending.state!!.getString("secret")
            val retired = store.sibling("pads").load()!!.toString()
            pending.resetForCable()
            assertEquals("https://pager.example.com", pending.state!!.getString("endpoint"))
            assertEquals("t".repeat(43), pending.state!!.getString("token"))
            assertNotEquals(previousSecret, pending.state!!.getString("secret"))
            assertEquals(retired, store.sibling("pads").load()!!.toString())
            assertTrue(pending.view().cableEligible); assertFalse(StationEngine.hasPad(pending.state!!))
        } finally { other.forget(); store.forget(); store.sibling("pads").forget() }
    }
}
