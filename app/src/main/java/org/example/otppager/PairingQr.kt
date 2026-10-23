package org.example.otppager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.InputStream

object PairingQr {
    fun bitmap(payload: String, edge: Int): Bitmap {
        require(payload.length <= 1024 && edge in 200..1600)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, edge, edge,
            mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    }
    fun decode(bitmap: Bitmap): String {
        require(bitmap.width.toLong() * bitmap.height <= 3000000)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        return try {
            val result = try { QRCodeReader().decode(image, mapOf(DecodeHintType.TRY_HARDER to true)) }
            catch (_: com.google.zxing.ReaderException) {
                // Clean exported QR images can confuse the photographic finder detector for
                // particular masks. ZXing's pure path samples the unskewed module grid directly.
                QRCodeReader().decode(image, mapOf(DecodeHintType.PURE_BARCODE to true))
            }
            result.text.also { require(it.length <= 1024) }
        } finally { pixels.fill(0) }
    }
    fun readImage(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= 4 * 1024 * 1024) { "QR image exceeds 4 MB" }; out.write(chunk, 0, n) }
        val bytes = out.toByteArray()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth in 1..8192 && options.outHeight in 1..8192 && options.outWidth.toLong() * options.outHeight <= 32000000) { "QR image dimensions unsupported" }
        options.inJustDecodeBounds = false; options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        val image = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) { "QR image cannot be read" }
        return try { decode(image) } finally { image.recycle(); bytes.fill(0) }
    }
}
