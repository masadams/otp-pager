package org.example.otppager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingQrTest {
    @Test fun generatedBitmapAndImportedPngDecodeWithoutNetwork() {
        val invite = PairingInvite("https://pager.example.com", "t".repeat(128), Protocol.randomSecret(), "B")
        val bitmap = PairingQr.bitmap(invite.encode(), 640)
        try {
            assertEquals(invite.encode(), PairingQr.decode(bitmap))
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            val decoded = PairingInvite.decode(PairingQr.readImage(out.toByteArray().inputStream()))
            assertEquals(invite.secret, decoded.secret); assertEquals("B", decoded.role)
        } finally { bitmap.recycle() }
        try { PairingQr.readImage(ByteArray(4 * 1024 * 1024 + 1).inputStream()); fail("Oversized image accepted") } catch (_: IllegalArgumentException) {}
        try { PairingQr.readImage("not an image".byteInputStream()); fail("Invalid image accepted") } catch (_: IllegalArgumentException) {}
    }
    @Test fun exportGivesOppositeRoleWithoutChangingVaultOrCounters() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStore(context, "qr-pair"); store.forget()
        try {
            val engine = StationEngine(store)
            engine.configure("https://pager.example.com", "t".repeat(43), Protocol.randomSecret(), "A")
            val before = engine.state.toString()
            assertEquals("B", PairingInvite.decode(engine.pairingInvite().encode()).role)
            assertEquals(before, engine.state.toString())
        } finally { store.forget() }
    }
}
