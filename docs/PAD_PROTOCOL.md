# Field finite-pad experiment (app 0.4)

This is an experimental, independent Field protocol, not a claim of information-theoretic security. The app's generator uses Android SecureRandom, not a certified physical entropy source. Authentication uses conventional HMAC-SHA-256 (RFC 2104/4231), not an information-theoretic one-time MAC. No cryptographic audit has been performed. AES-GCM remains the clearly labelled no-pad test mode. Installing a pad disables that mode for the pairing; exhaustion never falls back.

## Physical setup

1. Install this APK on both physical Android devices. Do not put real pads in a cloud emulator.
2. Start a **fresh pairing** with no message traffic: same new pairing secret, relay address and token, different roles A/B. Save link settings on both.
3. Station A: Pad → Create & export pad file. Choose offline USB storage in Android's file picker. This is a **plaintext secret file containing both directions**. Android can offer cloud providers; the app cannot enforce offline storage in the picker.
4. Station B: Pad → Import pad file from that physical medium. The file must match the pairing secret. Compare the entire 32-hex-character PAD CHECK on both devices in person before sending. Verify on each station, entering the other station’s last eight characters only after comparing the full code, then sync both until PEER VERIFIED appears.
5. Both stations start with 127 outgoing slots; the readiness announcement consumes one, leaving 126. A text and an automatic delivery receipt each consume a slot in their sender's direction. With balanced traffic this allows roughly 63 acknowledged texts per station; one-way traffic allows up to 126 texts and 126 receipts. Lost reservations reduce the reserve. A receiver with no outgoing slots can still receive but cannot acknowledge new messages.
6. Protect the transfer medium. Retaining copies preserves decryption capability. Deleting files on flash is not a verified secure erase. Do not share pads through the relay, logs, Git, cloud services, or email.

A failed export/installation retires that attempt: forget pairing on both devices and create a fresh secret and pad. Existing pads cannot be exported again through the app. This is deliberate. Reloading a pad to replenish the reserve is blocked within the current installation. Forget pairing retains a separate encrypted retirement ledger. **After uninstall, application data clearing, backup restoration, cloning, or uncertain storage recovery, create a fresh pad on both devices.** Those events are not reliably detectable across devices.

## Format and allocation

Bundle: ASCII `FIELDP03` (8 bytes), SHA-256(`Field pad binding v3` UTF-8 || decoded 32-byte pairing secret), then exactly 1,048,576 pad bytes. Total 1,048,616 bytes. No shared secret or relay token is exported. The binding detects wrong pairing, not malicious file modification; physical exchange and full check comparison authenticate the bundle. Pad ID = first 16 bytes SHA-256(material). Human pad check is all 16 bytes in hexadecimal.

Split at byte 524,288: A transmits using the first half, B using the second. Each fixed slot consumes 4,128 bytes: 4,096 XOR pad bytes followed by an independent 32-byte HMAC key. There are 127 slots per direction; trailing bytes are unused. No expansion or derivation produces additional XOR pad bytes or authentication keys.

Packet: version 3 byte, ASCII role byte, positive unsigned-compatible big-endian signed-long slot number 1–127, 16-byte pad ID, 4,096-byte ciphertext, 32-byte HMAC. Base64 uses the existing opaque relay API. Ciphertext = pad XOR (two-byte big-endian UTF-8 JSON length || JSON payload || zero padding). Payload retains v2 text/receipt semantics. Text is limited to 2,048 UTF-8 bytes, and the escaped JSON including metadata must fit 4,094 bytes; oversized encodings are rejected before reservation. The HMAC authenticates the complete header and ciphertext, checked in constant time before decrypting or changing replay state. Fixed length conceals text length within a packet, not message counts, timing, station roles, pad ID, or slot numbers. Server transport protocol stays 2; encrypted packet version is 3. Each mode ignores the other mode's packets.

## Commit order and limits

A separate Keystore-encrypted ledger records pad IDs and outgoing reservation high-water marks. Every reservation is committed and verified **before** encrypting a new ciphertext. An interruption after reservation wastes that slot. Inbox, replay window, queued receipt and transport cursor commit in the main vault together; receipt reservations may be wasted if that transaction aborts. Unauthenticated frames never reserve slots. Network retries reuse the exact saved packet and slot. Incoming authenticated duplicates do not create another receipt. Both ledgers use explicit file fsync, AtomicFile completion, byte-for-byte ciphertext readback with no remaining AtomicFile recovery sidecars, then parent-directory fsync; failures propagate. A ledger write error locks that ledger instance until restart. Missing files with an existing Keystore key fail closed. A missing or lagging ledger relative to a stored send counter also fails closed.

These are crash-consistency measures under Android's storage semantics, not a hardware monotonic counter. Restoring both vault and ledger to older coherent snapshots cannot be detected. Nor can installing the same bundle on another physical device or reinstalling the app. Keep one A and one B per bundle. Do not restore app state. Tests exercise pre-encryption reservation loss, restart/retry, deduplication, unread state, receipt accounting, retirement and exhaustion; they do not prove resilience against arbitrary hardware failure, malicious Android, or every power-loss point.

Pad bytes remain in the encrypted vault so recent history can be displayed. There is no verified per-slot erasure or forward secrecy. Java strings and copied JSON cannot be reliably zeroized. Local storage is protected by Android Keystore AES-GCM; this is another computational dependency. Protect both devices and transfer media.

## Sources

This pad format and its wire/storage formats are this project's own. No third-party
messenger code is included, and no interoperability with other clients is claimed.

Primary references:
- https://www.rfc-editor.org/rfc/rfc2104
- https://www.rfc-editor.org/rfc/rfc4231

Next work: independent protocol/storage review; physical entropy input and provenance; reviewed information-theoretic authentication if that guarantee is required; hardware failure injection; owner-controlled release signing; device e-ink and lifecycle testing.

## Android 0.4: verified setup gate

Upgrade **both stations** to 0.4. The Pad tab separates physical provisioning from relay settings. After installation, compare **all 32 hexadecimal characters** of the pad check in person. On each device tap Verify codes & announce and enter the last eight characters from the other station. That entry is an extra typing check, not a substitute for comparing the full code.

Each station queues one encrypted, authenticated `{kind:"ready",protocol:4}` packet using its normal directional pad allocation. This consumes one outgoing slot, leaving 126 before texts/receipts. New pad texts are blocked until local verification is saved and a valid readiness packet from the opposite station is saved. The relay cannot set readiness by claiming a peer is present. The indicator records verified setup, not current peer liveness. AES test mode remains separate and visibly labelled.

Use Sync on both devices until the indicator says PEER VERIFIED. If readiness was skipped by an older app or expired on the relay, Send readiness again queues the **exact original packet** retained in the encrypted vault, with no new slot consumption. Both stations need to upgrade: 0.3 does not understand readiness packets. Existing pending text retries and receiving remain available; the gate applies to new texts.

Existing 0.3 pad vaults migrate to schema 3, preserving history, pad reservations and receive replay state. The transport cursor resets to retrieve readiness packets an older client may have skipped. Installed pads must be verified on each upgraded station before new sends. Schema 3 is rejected by older APKs, preventing them from silently treating an installed pad as AES test mode. This does not prevent deliberate restoration of both old vault and ledger; the original rollback limits still apply. After uninstall/data clearing/cloning or uncertain recovery, generate a new physical pairing and pad.

Unread messages remain unread while you are scrolled away from the bottom. Jump to latest or scroll to the bottom to mark the received history read. Delivery still means peer storage, not a human reading the text. Foreground-only sync is unchanged.

## What the relay can learn from packet size (pipeline item 5, checked 2026-10-28)

- **Messages, receipts and readiness signals are all one length.** Each is a full pad slot:
  header (26) ‖ XOR block (4096) ‖ tag (32) = 4,154 bytes, which is 5,540 characters of base64,
  whatever the text length. `PadProtocolTest.everyPadPacketHasTheSameWireLengthWhateverItCarries`
  pins this. The relay can't tell a one-letter message from a full one, or a message from a receipt.
- **The POST body** is `{"packet":"…"}`. Android's JSON writer escapes `/` as `\/`, so the
  body length varies with the slash count in the base64 text. The variation comes from
  ciphertext randomness rather than plaintext length; fixed packet size does not hide traffic metadata.
  `PacketSerializationTest` checks the production serializer on Android, including slash escaping.
- **Still visible to the relay:**
  - timing and count of posts and fetches (one post per message or receipt);
  - which mailbox is used;
  - IP addresses;
  - for peer wipe, the control mailbox frame sizes (89-byte request, 60-byte acknowledgement).
    The relay already knows that mailbox is the control mailbox.

  Hiding timing would need cover traffic, which costs pad slots. That is a possible later
  option, not planned.
- **Legacy pre-pad chats** only ever send receipts under AES-GCM, which have variable length.
  Text can't be sent without a pad.

## Where pad randomness comes from (pipeline item 9, 2026-10-28)

From pairing protocol v9, **both phones contribute randomness**:
- the receiving phone sends a fresh 32-byte seed inside the authenticated pairing channel;
- the host XORs its own `SecureRandom` output with an AES-256-CTR keystream keyed by
  SHA-256("OTP Pager pad mix v1" ‖ peer seed ‖ host seed).

A weak, broken or backdoored random generator on one phone alone then no longer determines
the pad (`PadMixerTest`). This is defence in depth, not a change of kind. Pads are
computer-generated randomness, not a certified true-random one-time pad, and the app says so
in plain words. The manual plaintext pad file (Advanced setup, ≤ 4 MiB) still comes from one
phone only.
