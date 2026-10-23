# Roadmap

Android, including Android e-ink readers, is the primary platform. Dedicated
hardware is optional future work. The current v0.2 transport is AES-GCM, not OTP.

## Current baseline: Android hardening

- [x] Public source, Android APK build and public multi-architecture relay image
- [x] Native high-contrast Messages/Link interface
- [x] Versioned authenticated frames and shared Kotlin/Node test vector
- [x] Encrypted Android Keystore vault, durable outbox and receipt implementation
- [x] Persistent replay window and sender counter implementation
- [x] Bounded relay, retry deduplication, storage migration/failure tests
- [x] Android emulator storage/delivery/UI tests for v0.2 (five tests pass; screenshot collection corrected afterward)
- [ ] Measure keyboard, ghosting, refresh and battery behavior on actual Android e-ink hardware
- [ ] Owner-controlled stable release signing, followed by independent security review

## NEXT: OTP protocol and pad accounting

Begin after v0.2 verification. This is the next main development milestone.

1. Pin Jericho upstream source and review its license, packet construction,
   authentication, physical key import and consumption behavior. Build reproducible
   reference vectors. Decide and document compatibility rather than silently
   claiming it; current AES frames are not Jericho-compatible.
2. Specify a versioned 1 MiB physical pairing bundle, split into non-overlapping
   A→B and B→A pools. Account explicitly for message padding, identifiers and
   authentication bytes; usable text capacity is less than the raw pad size.
3. Select a reviewed one-time authentication construction with explicit independent
   key allocation. OTP XOR alone does not detect message modification.
4. Implement a small portable pad-accounting state machine. Reserve/burn bytes
   durably before exposing ciphertext to the transport. Once reserved, uncertain
   or interrupted allocations stay retired. Retry only previously stored ciphertext.
5. Test crash/restart at every reservation/encryption/queue/send/receipt boundary,
   concurrent sends, duplicate delivery, out-of-order traffic, truncated storage,
   exhausted pads, re-imports, and attempted rollback. No path may reissue consumed
   or uncertain pad ranges. Account for the actual durability guarantees of the
   Android storage primitives; the existing atomic vault alone is not proof.
6. Implement offline key-bundle transfer and human verification. The key material
   must never pass through the internet relay, plaintext app logs, public Git, or
   a cloud emulator. Define safe failure/retry and explicit re-pair behavior.
7. Connect the tested OTP engine to the existing outbox/relay/UI. Show remaining
   send/receive reserve, exhaustion and replenishment clearly. Keep test AES
   transport visibly distinguished until the migration is complete.

Acceptance: two physically provisioned Android devices exchange authenticated
messages through the relay; counters/reserves agree; fault tests never reuse pad
bytes; retry is byte-identical ciphertext; relay capture contains no pad material.

For Android testing, SecureRandom-generated pads exercise protocol mechanics but
are not independently validated full-entropy physical OTP material. Entropy-source
validation is a separate requirement before claiming information-theoretic secrecy.

## Later: endpoint and operational improvements

- Stronger authenticated physical provisioning and key replenishment
- Per-mailbox access capabilities, transport metadata reduction and optional
  private-network deployment
- Optional background delivery and message-retention policy choices
- Stable owner-controlled release signing and update verification
- Independent cryptographic/protocol review and adversarial testing
- Optional dedicated hardware, entropy measurements, and a minimal firmware review

### 0.3 finite-pad experiment implemented

See [PAD_PROTOCOL.md](PAD_PROTOCOL.md) for physical setup, exact allocation and limitations. Includes import/export, directional XOR slots, separate HMAC keys, burn-before-encryption reservation ledger, exact retries, receipts, retirement and exhaustion UI. This deliberately uses conventional HMAC and Android-generated material, so the information-theoretic authentication/entropy milestone above remains open. Restored/cloned state is not rollback-proof.

### 0.4 verified setup and unread navigation

- Dedicated Pad tab, physical setup steps, full-code comparison with suffix entry.
- Local verification and authenticated peer readiness gate new pad sends.
- Retained readiness ciphertext supports exact retries/reannouncement without new slots.
- Schema migration preserves pad/replay counters and blocks old APKs reading migrated pad vaults.
- New-message navigation preserves unread state while reading older traffic.
- Entropy validation, independent audit, full power-loss testing and stable release signing remain open.
