# Roadmap

## Active security work — v0.14 baseline

**Current status (0.17.0, 2026-10-29):** strict chats, relay failover, groups, post-quantum
USB pairing and relay TLS are implemented and merged; NFC + Wi-Fi pairing is paused ("Coming
soon"). Still open: independent review, physical two-phone USB/e-ink/battery/power-loss
validation, and post-quantum pairing signatures. See [PIPELINE.md](PIPELINE.md) and
[POST_QUANTUM.md](POST_QUANTUM.md).

PRs #9–15 are merged development foundations. See the [ordered checklist](SECURITY_CHECKLIST.md)
and [developer handoff](DEVELOPER_HANDOFF.md) for current completion, evidence and blockers.
Phase 2 remains open; remote wiping is not implemented.

Use [Security development plan](SECURITY_DEVELOPMENT_PLAN.md) for current security,
data lifecycle, relay hardening and emergency destruction work. Read
[progress and next task](SECURITY_PROGRESS.md) and [baseline findings](SECURITY_INVENTORY_V014.md)
before continuing. The sections below preserve the earlier roadmap and milestone
history; their version-specific UI and open-task descriptions may be superseded.

Android, including Android e-ink readers, is the primary platform. Dedicated
hardware is optional future work. Current Android 0.7 supports experimental finite-pad
traffic, device signatures and USB provisioning; AES-GCM remains a labelled test mode.

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

## Protocol and pad accounting milestones

Finite-pad mechanics are implemented below. Independent entropy validation,
information-theoretic authentication and a cryptographic audit remain open. The
following is the original protocol acceptance plan, not a claim that each step is complete.

1. Define this project's own packet construction, authentication, physical key import and
   consumption behavior, with reproducible reference vectors. (Decided 2026-10-28: OTP
   Pager follows its own design; no interoperability with other messengers is planned.)
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


### 0.5–0.6 device identity and physical setup

- Device-bound signed messages and receipts with pinned Keystore public keys.
- Offline QR setup and public identity scanning with explicit previews.
- USB host/AOA pairing exchanges server settings, fresh pad/secret and device keys
  with one public confirmation code and signed approvals on both devices.
- Owner reports successful physical USB pairing; device models and broader
  compatibility matrix have not been recorded.

### 0.7 delivery recovery and compact e-ink layout

- Restore retained ciphertext and cached receipts after a relay storage epoch change.
- Manual delivery recovery, original expiry, unchanged pad reservations and replay state.
- Separate queued, unread and awaiting-receipt indicators; tap for detailed status.
- Compact header/navigation and keyboard-aware conversation layout.

## Next practical work

- Target Mudita Kompakt and regular Android phones; verify actual USB pairing,
  keyboard, e-ink refresh and battery on that hardware.
- Stable owner-controlled release signing and verified updates.
- Continue the self-hosted private-network design in [PRIVATE_NETWORK.md](PRIVATE_NETWORK.md)
  after these reliability changes are validated. There is no built-in VPN in this APK.
- Independent cryptographic review, actual power-loss fault testing, and operational
  recovery/key replenishment design before sensitive use.


### Multiple friends in 0.7

- Up to 12 named, isolated two-device chats with individual vaults and device identities.
- Legacy station data stays in place. New chat creation copies only relay settings.
- Cross-chat pad/mailbox claims prevent local pairing-material reuse after forgetting.
- Foreground inactive-chat rotation and unread badges; no group-chat protocol.
- Developer security findings and remaining risks are recorded in [SECURITY_REVIEW.md](SECURITY_REVIEW.md).
