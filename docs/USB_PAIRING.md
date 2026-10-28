# USB pairing transport

USB pairing runs `CableProtocol` over an Android Open Accessory (AOA) bulk link
(`UsbPairChannel`). One phone acts as USB host and switches the other into
accessory mode. The host's chosen pad size is the one used.

## Packet boundaries (fixed in this change)

USB bulk transfers are a byte stream cut into packets of at most the endpoint's
max packet size (512 bytes at high speed, 1024 at SuperSpeed). A read completes
only when its buffer is full, a short packet arrives, or a zero-length packet
(ZLP) arrives. Earlier builds asked for 16 KiB per read and never sent ZLPs, which
caused two problems:

- If a frame happened to be an exact multiple of 512 bytes, the reader kept
  waiting for more data, but the sender was already waiting for a reply.
- On the host, Linux usbfs throws away a read's partial data when it times out
  (after 2 s), so the frame was lost and pairing failed or hung.

The chance was roughly 1 in 32 per frame, so pairing failed intermittently.

The channel now:

| Side | Direction | Behaviour |
|---|---|---|
| Host | OUT | After every write that is a multiple of the packet size, it sends an explicit ZLP. |
| Host | IN | It reads one max-size packet per call, so every packet completes the read whether or not the accessory kernel adds ZLPs. |
| Accessory | IN | A 0-byte read means a ZLP, which it treats as "no data yet". Before this change a 0-byte read meant disconnect. Disconnection is detected from `POLLHUP`/`POLLERR`, read errors and the Android detach broadcast. |
| Both | OUT | Writes are buffered, so each protocol frame goes out as one transfer when it is flushed, not byte by byte. |

`UsbPairChannelTest` models these USB semantics:

- packets of at most 512 bytes;
- reads that complete only when full, on a short packet or on a ZLP;
- usbfs dropping partial data on timeout;
- accessory kernels with and without automatic ZLPs.

The test runs encrypted ping-pong frames of aligned and unaligned sizes up to
1.4 MB. It also proves that the old adapters lose an aligned reply under the
same model.

The protocol version is unchanged. An old peer that receives a ZLP fails cleanly
instead of hanging, so update both phones before pairing.

## Physical check (owner)

Emulators can't do USB host or accessory mode, so this needs two real phones.

1. Install the same build on both phones. Add a friend on each and open
   **Pair devices · USB cable**.
2. Connect a USB-C data cable directly between the phones, with no hub.
3. Approve the USB permission prompts. There are usually two on the host phone,
   because it reconnects after switching the other phone into accessory mode.
4. Compare every group of the code and tap **Codes match** on both phones.
5. Expected result: "Paired" appears on both within a few seconds for 1 MiB. At 16–64 MiB,
   "Sending/Receiving message keys · N%" counts up first. Please note how long 64 MiB takes.
6. Repeat with the cable reversed so the other phone is host. Repeat at 64 MiB.
7. Pull the cable mid-transfer once. Both phones should report the interruption,
   and a fresh pairing should then succeed.

When reporting a problem, include both phone models, which phone was host, the
pad size and the last status line shown.
