# OTP Pager

Experimental, open-source, physically paired text messenger. Android, including Android e-ink readers, is the primary target. Dedicated handhelds remain an optional future target.

> **Security status: NOT AUDITED. NOT READY FOR SENSITIVE COMMUNICATIONS.** This repository begins with specifications and testable scaffolding, not a claim of secure or Jericho-compatible messaging.

## First field release: 0.7.0

[Download the APK and deployment files](https://github.com/masadams/otp-pager/releases/tag/v0.7.0).
Install the same `otp-pager-0.7.0-debug.apk` on both devices. Server image:
`ghcr.io/masadams/otp-pager-relay:0.7.0` (amd64/arm64).
Portainer: `refs/heads/main`, compose path `deploy/portainer-stack.yml`.
See [deployment instructions](docs/DEPLOYMENT.md) and [security review](docs/SECURITY_REVIEW.md).
This is the first integrated field-test prerelease; owner-controlled stable signing
and independent security review remain unfinished.

## Goals
- No phone-number account or remote contact enrollment.
- In-person, authenticated key exchange only.
- Independent, non-overlapping send/receive key pools per contact.
- Correct one-time-pad consumption, crash recovery, and authenticated message processing.
- Small, inspectable protocol with a path to native Android and minimal embedded firmware.
- No telemetry, analytics, or automatic cloud backup. The offline QR scanner uses the open-source ZXing SDK; see [third-party notices](docs/THIRD_PARTY_NOTICES.md).

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

The proposed next networking feature is [integrated, self-hosted WireGuard](docs/PRIVATE_NETWORK.md), including server enrolment, in-app tunnel controls and measured connection indicators. This is a design, not a feature in the current APK.

### Android 0.5: device identity binding

The Device tab adds an explicit physical exchange of public identity cards after
pad setup. Compare full fingerprints on the actual devices and pin each other.
New text messages and delivery receipts then require the pinned device's signature,
including when received later while the sender is offline. Android Keystore holds
the private signing key; the app reports hardware/software protection. Older
history stays labelled shared-secret-only. No continuous handshake, TOTP, or server
identity verdict is involved. This experimental signed-message profile creates
verifiable attribution and does **not** provide deniability. Read
[setup, upgrade rules, signed bytes and limits](docs/DEVICE_IDENTITY.md) before
starting. Integrated WireGuard is still a design, not implemented.

### Android 0.6: scan setup instead of typing secrets

Link → Show setup QR shares a configured station's relay URL, token and pairing
secret with the opposite station role. A new station can Scan setup QR (embedded,
offline camera scanner) or Import QR image, review, then save. Used chats and installed pads
cannot be overwritten by scans. Explicit review can replace unused server-only setup. The QR is secret, screenshot-protected and closes
after one minute; photographed credentials remain valid. Pad transfer and device
identity verification remain separate. See [setup and security details](docs/QR_PAIRING.md).

The same iteration adds **Pair devices · USB cable**, an automatic physical-pairing
wizard: relay settings can be on either device; it creates a fresh secret/pad,
assigns roles, exchanges identities and uses one confirmation code on both screens.
Android USB host/AOA support and a data cable are required. The exchange is tested
with virtual duplex streams, and the owner reports a successful physical USB pairing.
Device models were not recorded; wider USB compatibility and target e-ink behavior still need validation. Operational pads are never copied. Replacing a conversation requires explicit confirmation and retains the server settings.
Device identity cards also have Show/Scan QR controls for the fallback setup path.


### Android 0.7: delivery recovery and compact screens

A relay storage change now queues retained, unconfirmed messages and cached receipts
for recovery using their original ciphertext. Link → Retry undelivered messages also
rechecks stored receipts. Retries consume no pad slots and preserve the original
24-hour local expiry. Receipts created before this upgrade have no recovery cache;
clearing history removes that cache. Recovery needs both devices to sync and cannot
restore expired or deleted message content. See [delivery recovery](docs/DELIVERY_RECOVERY.md).

The status panel distinguishes new messages, locally queued packets and messages
waiting for a receipt. Tap it for last relay contact, pad reserve and device identity
status. Smaller displays use less header space; opening the keyboard temporarily
hides the header to leave room for the conversation and composer. Connection age
uses a monotonic clock, and local actions no longer reset network retry backoff.


### Multiple private chats

Tap the chat name at the top → **New chat**, enter a friend's name, then pair with
that friend by USB or the existing QR/file path. Up to **12 separate chats**, with
**two devices per chat**, are supported. Existing station data stays in place as
Chat 1. Rename it from the chat picker. New chats inherit only the selected chat's
relay URL/token and generate a fresh setup secret. Pads, identities, counters and
history stay separate; a persistent device-wide registry blocks assigning the same
pad or pairing secret to another chat. Forget pairing affects the selected chat.

While open, automatic sync checks the selected chat and one other chat per cycle;
other friends are checked in rotation. Unread badges appear in the picker and the
header. There is no background delivery when the app is closed. A group chat is
not implemented: do not give the same pad to three or more devices.

The compact layout targets Mudita Kompakt's 480 × 800 screen as well as regular
Android phones. Android 12/API 31 emulator coverage exercises the platform family;
actual Kompakt USB host/AOA, keyboard, ghosting and battery behavior still require
hardware testing. See [security code review](docs/SECURITY_REVIEW.md).


After a complete USB pairing (or completed manual pad/device verification), Messages
becomes the main view and the setup tabs are hidden. Open the chat picker → **Chat
settings**, or tap status → **Settings**, to reach Link, Pad and Device. Returning
to Messages hides those tabs again. Unpaired chats keep their setup navigation.

### Android 0.8 development: encrypted USB and clearer setup

Unpaired conversations explain the next step and offer a direct setup action.
The normal UI requires a physical pad before sending, avoiding accidental AES test traffic. Sensitive
input requests no personalized keyboard learning; Android 12+ hides application overlays
on private activities and dialogs. These protections cannot secure a compromised keyboard,
accessibility service or operating system.

New USB provisioning uses ephemeral P-256 ECDH, HKDF-SHA256 and directional AES-GCM.
The physical comparison code and signed approvals bind both ephemeral keys; secrets and
pads transfer only after both devices approve. Both devices need 0.8 for a **new** USB
pairing; there is no plaintext compatibility fallback. Existing paired chat state and
relay/message formats are unchanged. This is conventional, unaudited encryption, not
post-quantum provisioning. See [the security review](docs/SECURITY_REVIEW.md) for open risks.
The 0.7 field-release links above remain pinned until a new build is verified and released.

### Permanent signing and update verification (proposed)

[Owner setup and update instructions](docs/ANDROID_UPDATES.md) provide a permanent,
locally generated release signer, main-only signed builds and a publisher that rejects
new debug-signed releases. An emulator job installs a second non-debuggable release APK
over a seeded first one and checks Keystore identity, chat state, queued ciphertext,
replay state and pad allocation; wrong-signature and downgrade installs must fail.
The one-time switch from existing temporary debug keys still requires fresh pairings.
The release key is not configured until the owner runs setup on their phone or PC.
Android/Termux setup includes a compact Bitwarden Secure Note backup and tested restore.

During temporary development, unsigned CI review APKs can instead be signed privately
with the owner's authorized temporary signer and delivered as test APKs. No signing key
is stored in the public repository or Actions artifacts. Production custody and the
initial signature transition remain explicit; see the update instructions.

The v0.10 development pass adds [per-chat relay access and revocation](docs/CHAT_RELAY_ACCESS.md), clearer connection/delivery status and draft-aware sending. Update both clients before enabling chat protection on the new relay.
