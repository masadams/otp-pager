package org.example.otppager

import android.hardware.usb.*
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** App-level AOA bulk channel. No ADB, root, or general-purpose USB gadget configuration. */
class UsbPairChannel private constructor(private val receive: (ByteArray, Int, Int) -> Int,
    private val transmit: (ByteArray, Int, Int) -> Int, private val release: () -> Unit) : Closeable {
    private val closed = AtomicBoolean(false)
    private val expires = SystemClock.elapsedRealtime() + 180000
    val input: InputStream = java.io.BufferedInputStream(object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val deadline = minOf(expires, SystemClock.elapsedRealtime() + 120000)
            while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                val n = receive(b, off, minOf(len, 16384)); if (n > 0) return n
            }
            throw IOException("USB disconnected or pairing timed out")
        }
    }, 16384) // Read a full USB packet even when DataInputStream asks for one byte.
    val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off; val end = off + len
            while (at < end) {
                check(!closed.get() && SystemClock.elapsedRealtime() < expires) { "USB connection closed or timed out" }
                val n = transmit(b, at, minOf(end - at, 16384))
                if (n <= 0) throw IOException("USB write failed")
                at += n
            }
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) try { release() } catch (_: Exception) {} }
    companion object {
        fun isAccessory(device: UsbDevice) = device.vendorId == 0x18d1 && device.productId in listOf(0x2d00, 0x2d01)
        fun switchToAccessory(manager: UsbManager, device: UsbDevice) {
            val connection = manager.openDevice(device) ?: error("USB permission was not granted")
            try {
                val version = ByteArray(2)
                check(connection.controlTransfer(0xc0, 51, 0, 0, version, 2, 2000) == 2 && version[0].toInt() in 1..2) {
                    "Other device does not report Android Open Accessory support. Try the other USB host direction or use QR setup."
                }
                val strings = listOf("Field", "OTP Pager Pairing", "Physical device pairing", "1", "", "field-pairing")
                for ((i, value) in strings.withIndex()) {
                    val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
                    check(connection.controlTransfer(0x40, 52, 0, i, bytes, bytes.size, 2000) == bytes.size) { "USB accessory negotiation failed" }
                }
                check(connection.controlTransfer(0x40, 53, 0, 0, null, 0, 2000) >= 0) { "USB accessory switch failed" }
            } finally { connection.close() }
        }
        fun host(manager: UsbManager, device: UsbDevice): UsbPairChannel {
            check(isAccessory(device))
            val connection = manager.openDevice(device) ?: error("USB connection unavailable")
            try {
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                    val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: continue
                    val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: continue
                    check(connection.claimInterface(intf, true)) { "USB interface is busy" }
                    return UsbPairChannel({ b, at, n -> connection.bulkTransfer(input, b, at, n, 2000) },
                        { b, at, n -> connection.bulkTransfer(output, b, at, n, 5000) }, { connection.releaseInterface(intf); connection.close() })
                }
                error("Accessory has no bidirectional data interface")
            } catch (e: Exception) { connection.close(); throw e }
        }
        fun accessory(manager: UsbManager, accessory: UsbAccessory): UsbPairChannel {
            require(accessory.manufacturer == "Field" && accessory.model == "OTP Pager Pairing")
            val descriptor: ParcelFileDescriptor = manager.openAccessory(accessory) ?: error("USB accessory unavailable")
            return UsbPairChannel({ b, at, n ->
                val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 2000) > 0) {
                    val count = Os.read(descriptor.fileDescriptor, b, at, n)
                    if (count == 0) throw IOException("USB accessory disconnected")
                    count
                } else -1
            }, { b, at, n -> Os.write(descriptor.fileDescriptor, b, at, n) }, { descriptor.close() })
        }
    }
}
