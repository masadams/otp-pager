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
uses experimental AES-GCM; true OTP and Jericho compatibility remain future work.

## Android v0.2 hardening

See [security boundaries, upgrades, delivery and e-ink behavior](docs/HARDENING.md).
The new Messages/Link interface adds Keystore-encrypted pairing storage, a durable
outbox, authenticated delivery receipts and persistent replay protection. Update
the relay and **both** APKs together; legacy v0.1 messages are not imported.
