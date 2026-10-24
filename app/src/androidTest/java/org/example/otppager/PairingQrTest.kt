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
    @Test fun cleanImportedImagesDecodeAcrossAllQrMasks() {
        val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val payload = PairingInvite("https://pager.example.com", "t".repeat(128), secret, "B").encode()
        for (mask in 0..7) {
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, 640, 640,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 4, com.google.zxing.EncodeHintType.QR_MASK_PATTERN to mask,
                    com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M))
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            val bitmap = android.graphics.Bitmap.createBitmap(pixels, matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                assertEquals("Mask $mask", payload, PairingQr.decode(bitmap))
                val png = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)
                assertEquals("PNG mask $mask", payload, PairingQr.readImage(png.toByteArray().inputStream()))
            } finally { bitmap.recycle(); pixels.fill(0) }
        }
    }
    @Test fun embeddedScannerInflatesWithSecureWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.CAMERA)
        val activity = instrumentation.startActivitySync(android.content.Intent(context, PairingScannerActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("BEEP_ENABLED", false).putExtra("BARCODE_IMAGE_ENABLED", false))
        try {
            instrumentation.runOnMainSync {
                assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertNotNull(activity.findViewById<android.view.View>(com.google.zxing.client.android.R.id.zxing_barcode_scanner))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
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
