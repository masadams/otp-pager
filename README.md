# OTP Pager

Experimental, open-source, physically paired text messenger. Android, including Android e-ink readers, is the primary target. Dedicated handhelds remain an optional future target.

> **Security status: NOT AUDITED. NOT READY FOR SENSITIVE COMMUNICATIONS.** This repository begins with specifications and testable scaffolding, not a claim of secure or Jericho-compatible messaging.

## Goals
- No phone-number account or remote contact enrollment.
- In-person, authenticated key exchange only.
- Independent, non-overlapping send/receive key pools per contact.
- Correct one-time-pad consumption, crash recovery, and authenticated message processing.
- Small, inspectable protocol with a path to native Android and minimal embedded firmware.
- No telemetry, analytics, third-party SDKs, or automatic cloud backup.

## Documents
- [Threat model](docs/THREAT_MODEL.md)
- [Protocol sketch](docs/PROTOCOL.md)
- [Jericho review](docs/JERICHO_REVIEW.md)
- [Roadmap](docs/ROADMAP.md)

## Important distinction
A cryptographically secure pseudorandom stream is not an information-theoretically secure one-time pad. We will not describe Android's SecureRandom output as independently validated full-entropy OTP material. Physical pairing must authenticate peers and protect the transfer of key bytes. A genuine OTP additionally requires independent uniformly random secret key bytes, never reused.

## License
No project-wide code license has been selected yet. Do not copy Jericho source into this repository until its exact license and attribution obligations are reviewed.


## Two-phone deployment prototype

See [Docker / Portainer + Nginx setup](docs/DEPLOYMENT.md) and
[two-device testing](docs/TWO_DEVICE_TEST.md). The Android field terminal currently
offers experimental AES-GCM test mode and a finite-pad mode described below; validated physical entropy, information-theoretic authentication, and Jericho compatibility remain future work.

## Android v0.2 hardening

See [security boundaries, upgrades, delivery and e-ink behavior](docs/HARDENING.md).
The new Messages/Link interface adds Keystore-encrypted pairing storage, a durable
outbox, authenticated delivery receipts and persistent replay protection. Update
the relay and **both** APKs together; legacy v0.1 messages are not imported.

### Android 0.3: finite-pad experiment

The e-ink interface shows relay connectivity, unread messages, queued traffic, delivery state and remaining pad slots. Link settings can create a physical pad file on Station A and import it on Station B. Start a fresh pairing on both devices before installing pads. Read [physical setup and security limits](docs/PAD_PROTOCOL.md) first: plaintext pad files require physical transfer; Android-generated pads and HMAC authentication are experimental and do not claim information-theoretic security. Each station has 127 outgoing slots, including receipts. No automatic AES fallback at exhaustion.

### Android 0.4: verify before sending

Messages, Link and Pad now have separate tabs. After importing pads, compare the full pad-check code in person, verify on **both devices**, and sync both. New pad messages unlock only after authenticated peer readiness arrives. This uses one setup slot per station; re-announcing readiness retries identical ciphertext without spending more pad material. Upgrade both APKs. See [0.4 setup and upgrade details](docs/PAD_PROTOCOL.md#android-04-verified-setup-gate). Existing 0.3 pads migrate with their counters intact; old APKs reject the migrated pad vault.
