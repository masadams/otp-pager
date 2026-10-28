# Community review package (pipeline item 12)

This is the starting point for anyone reviewing OTP Pager. It explains what the app claims,
where each claim is specified, what can be checked independently, and what is known to be
weak. Reviews of any part are welcome. Please open an issue or a pull request against the
spec you are reviewing.

## What OTP Pager is and isn't

- **It is** a small Android messenger for people who can meet in person.
  - Two phones pair over a USB cable or NFC plus local Wi-Fi, and exchange a large random pad
    and device identities.
  - Messages are XORed with fresh pad slots and authenticated, then posted through a
    self-hosted relay that only ever sees fixed-size ciphertext.
- **It isn't** a certified one-time-pad system. Pads come from the phones' cryptographic random
  generators, with both phones contributing. Authentication is computational (HMAC-SHA256,
  P-256 signatures), the code is not independently audited, and the app has no post-quantum
  claims.
- **Intended users:** the owner, family and friends. It's published, but it isn't sold or
  promoted.

## Threat model and invariants

- [THREAT_MODEL.md](THREAT_MODEL.md): adversaries and assets.
- [SECURITY_DEVELOPMENT_PLAN.md](SECURITY_DEVELOPMENT_PLAN.md): invariants S01–S12 and findings
  F01–F12.
- [SECURITY_REVIEW.md](SECURITY_REVIEW.md) and [SECURITY_INVENTORY_V014.md](SECURITY_INVENTORY_V014.md):
  earlier self-review.
- [DISCUSSION_HANDOFF_2026_10_28.md](DISCUSSION_HANDOFF_2026_10_28.md): latest decisions and
  open work.

## Specifications, by area

| Area | Spec | Code | Independent vectors |
| --- | --- | --- | --- |
| Message packets, pad slots, what the relay sees | [PAD_PROTOCOL.md](PAD_PROTOCOL.md), [PROTOCOL.md](PROTOCOL.md) | `PadProtocol`, `Protocol` | `protocol-v2.properties` |
| Pad storage and erasure | [PAD_STORAGE.md](PAD_STORAGE.md), [VAULT_KEY_ROTATION.md](VAULT_KEY_ROTATION.md) | `PadMaterial`, `SecureStore`, `VaultKeys` | none yet |
| Physical pairing (USB, NFC + Wi-Fi), protocol v9 | [USB_PAIRING.md](USB_PAIRING.md), [NFC_WIFI_HANDOFF.md](NFC_WIFI_HANDOFF.md) | `CableProtocol`, `CableSession`, `RadioHandoff`, `UsbPairChannel` | `cable-v3.properties`, `radio-v1.properties` |
| Device identity | [DEVICE_IDENTITY.md](DEVICE_IDENTITY.md) | `IdentityProtocol`, `DeviceIdentity` | `device-v1.properties` |
| Local wipe, global wipe, recovery, fresh enrollment | [LOCAL_WIPE_CONTROL.md](LOCAL_WIPE_CONTROL.md), [ACTIVE_EPOCH_DESIGN.md](ACTIVE_EPOCH_DESIGN.md), [GLOBAL_ENROLLMENT_PROTOCOL.md](GLOBAL_ENROLLMENT_PROTOCOL.md), [RETIREMENT_EVIDENCE_FORMAT.md](RETIREMENT_EVIDENCE_FORMAT.md) | `VaultRetirement`, `GlobalRetirement`, `EpochLineage`, `FreshEnrollment` | `scripts/test_enrollment_protocol.py` (model) |
| Peer wipe (unreviewed, off by default) | [PEER_WIPE_PROTOCOL.md](PEER_WIPE_PROTOCOL.md) | `ControlProtocol`, `PeerWipe` | `control-v1.properties`: **needs a second implementer** |
| Strict chats | [STRICT_CHAT_PROTOCOL.md](STRICT_CHAT_PROTOCOL.md) | `StrictChat`, `StrictChatProtocol`, relay `server.mjs` | `strict-chat-v1.properties`: **needs a second implementer** |
| Relay access and claims | [CHAT_RELAY_ACCESS.md](CHAT_RELAY_ACCESS.md), [HARDENING.md](HARDENING.md) | `relay/server.mjs` | relay tests |
| Backup relays, groups | [RELAY_FAILOVER.md](RELAY_FAILOVER.md), [GROUP_CHATS.md](GROUP_CHATS.md) | `StationEngine.sync`, `ChatBook` groups | none yet |

## What a reviewer can do without phones

1. **Reproduce the vectors in your own language.** Each `test-vectors/*.properties` file uses
   fixed TEST keys. Write your own implementation from the spec alone, without reading our
   code, and compare the bytes. The control and strict vectors matter most, because no second
   implementer has done them yet.
2. **Run the suites:**
   - relay: `cd relay && npm test`;
   - JVM: `gradle :app:testDebugUnitTest` (Gradle 8.9, JDK 17, Android SDK 35);
   - instrumentation: `gradle :app:connectedDebugAndroidTest` on an emulator (API 29, 31 or 35).

   CI runs all three plus the restart fixtures in `scripts/verify_*_restart.py` and a signed
   v0.13 → current upgrade.
3. **Attack the relay.** It is about 300 lines of Node with no dependencies. Look for claim
   bypass, replay, storage corruption, journal replay, share theft or unlock forgery.
4. **Review the invariants:**
   - a pad slot is reserved before use and never reused;
   - a retry sends the exact same ciphertext;
   - retirement is terminal;
   - local destruction never waits on the network.

## Known limits (please don't report these as new)

- Pads are computational randomness from both phones, not a true-random one-time pad.
- A compromised, unlocked or rooted phone exposes everything it holds. Flash wear-levelling
  means "deleted" is not "forensically erased".
- The relay sees timing, counts, mailbox identifiers and IP addresses, though not content or
  length. It can delay, drop or deny service.
- Peer wipe and strict chats can't force a powered-off, offline or modified phone to comply.
- A strict chat can be unlocked by whoever operates the phone online before its deadline. The
  authority key is trusted on first use.
- Groups have no cryptographic identity across pairings; members link chats by hand.
- USB host and accessory compatibility varies by phone. The two-phone checks are in
  [TWO_DEVICE_TEST.md](TWO_DEVICE_TEST.md) and [USB_PAIRING.md](USB_PAIRING.md).

## Open questions we'd most like help with

- Peer-wipe and strict frames: is the domain separation and replay handling sufficient?
  Should acknowledgements also be device-signed?
- Is mixing the peer seed (pairing v9) worth its complexity, and is the construction sound?
- Strict chats: are trust-on-first-use authority pinning and a 60-second lease the right
  trade-off for this audience?
- Anything in retirement and recovery that could revive a retired pad or credential.

## Release and physical-test gates (owner)

These need hardware or keys and can't be done from CI:
- first signed release with the owner's key;
- two-phone pairing in both USB directions at 1 and 64 MiB;
- interruption and power loss during pairing and renewal;
- strict-chat lock and unlock on real phones;
- peer wipe between two phones.
