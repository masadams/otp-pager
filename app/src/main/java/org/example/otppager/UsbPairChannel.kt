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

/**
 * App-level AOA bulk channel. No ADB, root, or general-purpose USB gadget configuration.
 *
 * USB bulk transfers have no message boundaries: a read completes only when its buffer is
 * full, a short packet arrives, or a zero-length packet (ZLP) ends the transfer. A write that
 * is an exact multiple of the endpoint's packet size therefore leaves the reader waiting,
 * and Linux usbfs discards the partial data on timeout. Both directions are made independent
 * of that: the host sends an explicit ZLP after every packet-aligned write, reads one packet
 * at a time (so it never depends on the accessory kernel adding ZLPs), and the accessory treats
 * zero-length reads as "no data yet" instead of disconnection. See docs/USB_PAIRING.md.
 */
class UsbPairChannel internal constructor(private val receive: (ByteArray, Int, Int) -> Int,
    private val transmit: (ByteArray, Int, Int) -> Int, private val release: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    lifetimeMillis: Long = 180000, private val idleMillis: Long = 120000) : Closeable {
    private val closed = AtomicBoolean(false)
    private val expires = clock() + lifetimeMillis
    val input: InputStream = java.io.BufferedInputStream(object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val deadline = minOf(expires, clock() + idleMillis)
            while (!closed.get() && clock() < deadline) {
                val n = receive(b, off, minOf(len, BUFFER)); if (n > 0) return n
            }
            throw IOException("USB disconnected or pairing timed out")
        }
    }, BUFFER) // Read a full USB buffer even when DataInputStream asks for one byte.
    /** Buffered so each protocol frame leaves as one transfer at flush(), not byte by byte. */
    val output: OutputStream = java.io.BufferedOutputStream(object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off; val end = off + len
            while (at < end) {
                check(!closed.get() && clock() < expires) { "USB connection closed or timed out" }
                val n = transmit(b, at, minOf(end - at, BUFFER))
                if (n <= 0) throw IOException("USB write failed")
                at += n
            }
        }
    }, BUFFER)
    override fun close() { if (closed.compareAndSet(false, true)) try { release() } catch (_: Exception) {} }
    companion object {
        const val BUFFER = 16384
        private val EMPTY = ByteArray(0)
        /** Host IN: request exactly one max-size packet so every packet completes the read. */
        internal fun hostReceive(maxPacket: Int, bulk: (ByteArray, Int, Int, Int) -> Int): (ByteArray, Int, Int) -> Int {
            require(maxPacket > 0)
            return { b, at, n -> bulk(b, at, minOf(n, maxPacket), 2000) }
        }
        /** Host OUT: terminate packet-aligned writes with a ZLP so the accessory read completes. */
        internal fun hostTransmit(maxPacket: Int, bulk: (ByteArray, Int, Int, Int) -> Int): (ByteArray, Int, Int) -> Int {
            require(maxPacket > 0)
            return { b, at, n ->
                val sent = bulk(b, at, n, 5000)
                if (sent > 0 && sent % maxPacket == 0 && bulk(EMPTY, 0, 0, 5000) < 0) -1 else sent
            }
        }
        /** Accessory IN: a ZLP reads as 0 bytes; that is "keep waiting", never end-of-stream. */
        internal fun accessoryReceive(ready: () -> Boolean, read: (ByteArray, Int, Int) -> Int): (ByteArray, Int, Int) -> Int =
            { b, at, n -> if (ready()) read(b, at, n).let { if (it > 0) it else -1 } else -1 }

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
                    return UsbPairChannel(
                        hostReceive(input.maxPacketSize) { b, at, n, t -> connection.bulkTransfer(input, b, at, n, t) },
                        hostTransmit(output.maxPacketSize) { b, at, n, t -> connection.bulkTransfer(output, b, at, n, t) },
                        { connection.releaseInterface(intf); connection.close() })
                }
                error("Accessory has no bidirectional data interface")
            } catch (e: Exception) { connection.close(); throw e }
        }
        fun accessory(manager: UsbManager, accessory: UsbAccessory): UsbPairChannel {
            require(accessory.manufacturer == "Field" && accessory.model == "OTP Pager Pairing")
            val descriptor: ParcelFileDescriptor = manager.openAccessory(accessory) ?: error("USB accessory unavailable")
            val fd = descriptor.fileDescriptor
            return UsbPairChannel(accessoryReceive({
                val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                if (Os.poll(arrayOf(poll), 2000) <= 0) false
                else {
                    val events = poll.revents.toInt()
                    if (events and OsConstants.POLLIN == 0 && events and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL) != 0)
                        throw IOException("USB accessory disconnected")
                    true
                }
            }) { b, at, n -> Os.read(fd, b, at, n) }, { b, at, n -> Os.write(fd, b, at, n) }, { descriptor.close() })
        }
    }
}
