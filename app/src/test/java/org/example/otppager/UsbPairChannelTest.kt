package org.example.otppager

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Models USB bulk semantics that virtual duplex streams hide: packets of at most [P] bytes,
 * a read completes only when full, on a short packet or on a zero-length packet, and an
 * incomplete read that times out loses what it gathered (Linux usbfs). Device-side "transfer"
 * boundaries are invisible to the reader except through short packets and ZLPs.
 */
class UsbPairChannelTest {
    private class Pipe(val packet: Int, val readTimeoutMs: Long, val kernelZlp: Boolean) {
        private val packets = LinkedBlockingQueue<ByteArray>()
        @Volatile var lost = 0
        fun write(b: ByteArray, at: Int, n: Int): Int {
            var i = 0
            while (i < n) { val k = minOf(packet, n - i); packets.put(b.copyOfRange(at + i, at + i + k)); i += k }
            if (n == 0 || (kernelZlp && n % packet == 0)) packets.put(ByteArray(0))
            return n
        }
        fun read(b: ByteArray, at: Int, n: Int): Int {
            require(n >= packet || n > 0)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs)
            var got = 0
            while (true) {
                val wait = deadline - System.nanoTime()
                val p = if (wait > 0) packets.poll(wait, TimeUnit.NANOSECONDS) else null
                if (p == null) { lost += got; return -1 } // usbfs: partial data is discarded on timeout.
                check(got + p.size <= n) { "Babble: packet overflowed the read request" }
                p.copyInto(b, at + got); got += p.size
                if (p.size < packet || got == n) return got
            }
        }
    }
    private val P = 512

    /** Host reads the accessory's pipe; accessory reads the host's pipe, each with the production adapters. */
    private fun link(kernelZlp: Boolean, fixed: Boolean = true, packet: Int = P): Triple<UsbPairChannel, UsbPairChannel, List<Pipe>> {
        val toHost = Pipe(packet, 150, kernelZlp); val toAccessory = Pipe(packet, 150, kernelZlp = false) // usbfs never adds ZLPs.
        val host = if (fixed) UsbPairChannel(
            UsbPairChannel.hostReceive(packet) { b, at, n, _ -> toHost.read(b, at, n) },
            UsbPairChannel.hostTransmit(packet) { b, at, n, _ -> toAccessory.write(b, at, n) }, {},
            System::currentTimeMillis, 20000, 3000)
        else UsbPairChannel({ b, at, n -> toHost.read(b, at, n) }, { b, at, n -> toAccessory.write(b, at, n) }, {},
            System::currentTimeMillis, 20000, 3000)
        val accessory = UsbPairChannel(
            if (fixed) UsbPairChannel.accessoryReceive({ true }) { b, at, n -> toAccessory.read(b, at, n) }
            else { b, at, n -> toAccessory.read(b, at, n) },
            { b, at, n -> toHost.write(b, at, n) }, {}, System::currentTimeMillis, 20000, 3000)
        return Triple(host, accessory, listOf(toHost, toAccessory))
    }
    private fun card(pair: java.security.KeyPair) = Base64.getEncoder().encodeToString(pair.public.encoded)
    // Encrypted frame on the wire = 4 (size) + 8 (sequence) + plaintext + 16 (tag).
    private fun aligned(k: Int) = k * P - 28
    private val sizes = listOf(1, aligned(1), aligned(2), aligned(8), aligned(32), aligned(32) + 1, aligned(33), 100000, aligned(400), 1400000)

    /** Ping-pong: each side waits for the other's reply, exactly the pattern that deadlocks without ZLPs. */
    private fun exchange(host: UsbPairChannel, accessory: UsbPairChannel) {
        val a = CableSession.ephemeral(); val b = CableSession.ephemeral(); val transcript = ByteArray(32) { 3 }
        val pool = Executors.newSingleThreadExecutor()
        try {
            CableSession.establish(true, a, card(b), transcript).use { hs -> CableSession.establish(false, b, card(a), transcript).use { asess ->
                val peer = pool.submit {
                    for (size in sizes) {
                        val got = asess.read(accessory.input)
                        check(got.size == size && got.all { it == (size % 127).toByte() })
                        asess.send(accessory.output, ByteArray(size) { (size % 113).toByte() })
                    }
                }
                for (size in sizes) {
                    hs.send(host.output, ByteArray(size) { (size % 127).toByte() })
                    val reply = hs.read(host.input)
                    assertEquals(size, reply.size); assertTrue(reply.all { it == (size % 113).toByte() })
                }
                peer.get(30, TimeUnit.SECONDS)
            } }
        } finally { pool.shutdownNow(); host.close(); accessory.close() }
    }

    @Test fun packetAlignedFramesCompleteInBothDirectionsWithoutKernelZlp() {
        for (packet in listOf(64, 512, 1024)) {
            val (host, accessory, pipes) = link(kernelZlp = false, packet = packet)
            exchange(host, accessory)
            assertEquals("Data dropped at packet size $packet", 0, pipes.sumOf { it.lost })
        }
    }

    @Test fun packetAlignedFramesCompleteWhenTheAccessoryKernelAddsZlp() {
        for (packet in listOf(64, 512, 1024)) {
            val (host, accessory, pipes) = link(kernelZlp = true, packet = packet)
            exchange(host, accessory)
            assertEquals(0, pipes.sumOf { it.lost })
        }
    }

    @Test fun previousAdaptersLoseAPacketAlignedReply() {
        // Regression guard: proves the model reproduces the real-device hang the adapters fix.
        val (host, accessory, _) = link(kernelZlp = false, fixed = false)
        val transcript = ByteArray(32) { 3 }; val a = CableSession.ephemeral(); val b = CableSession.ephemeral()
        try {
            CableSession.establish(false, b, card(a), transcript).use { asess ->
                asess.send(accessory.output, ByteArray(aligned(2)))
                try { CableSession.establish(true, a, card(b), transcript).use { it.read(host.input) }; fail("Aligned frame unexpectedly arrived") }
                catch (_: Exception) {}
            }
        } finally { host.close(); accessory.close() }
    }

    @Test fun hostAddsZlpOnlyAfterPacketAlignedWrites() {
        val calls = mutableListOf<Int>()
        val tx = UsbPairChannel.hostTransmit(P) { _, _, n, _ -> calls.add(n); n }
        for (n in listOf(1, 511, 512, 513, 1024, 16384)) tx(ByteArray(n), 0, n)
        assertEquals(listOf(1, 511, 512, 0, 513, 1024, 0, 16384, 0), calls)
        val failingZlp = UsbPairChannel.hostTransmit(P) { _, _, n, _ -> if (n == 0) -1 else n }
        assertEquals(-1, failingZlp(ByteArray(512), 0, 512))
        val rx = mutableListOf<Int>()
        UsbPairChannel.hostReceive(P) { _, _, n, _ -> rx.add(n); n }(ByteArray(16384), 0, 16384)
        assertEquals(listOf(P), rx)
    }

    @Test fun accessoryTreatsZeroLengthReadsAsNoDataYet() {
        var reads = 0
        val rx = UsbPairChannel.accessoryReceive({ true }) { _, _, _ -> reads++; if (reads < 3) 0 else 7 }
        val channel = UsbPairChannel(rx, { _, _, n -> n }, {}, System::currentTimeMillis, 5000, 2000)
        assertEquals(0, channel.input.read()) // Two ZLPs skipped, then real data.
        assertEquals(3, reads)
        val idle = UsbPairChannel(UsbPairChannel.accessoryReceive({ false }) { _, _, _ -> error("read without data") },
            { _, _, n -> n }, {}, System::currentTimeMillis, 5000, 200)
        try { idle.input.read(); fail("Idle channel never timed out") } catch (_: java.io.IOException) {}
    }
    @Test fun shortWritesPreserveEveryByteAndCloseReleasesOnlyOnce() {
        val sent = java.io.ByteArrayOutputStream(); var releases = 0
        val channel = UsbPairChannel({ _, _, _ -> -1 }, { b, at, n ->
            val count = minOf(n, 17); sent.write(b, at, count); count
        }, { releases++ }, { 0L })
        val bytes = ByteArray(33000) { (it % 251).toByte() }
        channel.output.write(bytes); channel.output.flush()
        assertArrayEquals(bytes, sent.toByteArray())
        channel.close(); channel.close()
        assertEquals(1, releases)
        try { channel.input.read(); fail("Read after close") } catch (_: java.io.IOException) {}
        try { channel.output.write(bytes); channel.output.flush(); fail("Write after close") } catch (_: IllegalStateException) {}
    }

    @Test fun disconnectAndFailedWritesPropagateWithoutFurtherTraffic() {
        val channel = UsbPairChannel({ _, _, _ -> throw java.io.IOException("Detached") },
            { _, _, _ -> -1 }, {}, { 0L })
        try {
            try { channel.input.read(); fail("Disconnect ignored") } catch (_: java.io.IOException) {}
            try { channel.output.write(1); channel.output.flush(); fail("Write failure ignored") } catch (_: java.io.IOException) {}
        } finally { channel.close() }
    }

}
