# Security code review — Android 0.8 (proposed)

Scope: first-party client encryption/authentication, pad accounting, physical pairing,
local storage, chat separation, relay HTTP/storage and checked-in deployment examples.
This is a developer-led code review and regression testing, not an independent audit,
penetration test of the owner's server, cryptographic proof, dependency vulnerability
certification or verification of the live Nginx/Portainer configuration.

## Findings addressed in 0.8

| Finding | Consequence | Change |
| --- | --- | --- |
| USB exposed pad/token/secret to passive cable capture | High, captured provisioning material compromises a chat | New version-3 provisioning uses ephemeral P-256 ECDH and HKDF-SHA256 directional AES-256-GCM keys. Both ephemeral public keys, identities, nonces and selected endpoint are bound into the physically compared code and signed approvals. Credentials, pad and completion frames are encrypted only after both approvals; strict counters, direction/transcript AAD and bounded binary records reject replay/reflection/tampering. Old plaintext pairing is rejected, with no downgrade. |
| Normal UI allowed sending with only server/setup credentials | Misleading OTP readiness / accidental use of legacy AES test mode | Require a physical pad before enabling Send in the normal conversation UI and re-check in its worker action. Legacy engine decoding/queued packets remain compatible. |
| Keyboard could learn sensitive input | Keyboard-dependent content retention | Request no personalized learning and no extract UI for message/setup/name fields. A malicious keyboard can still read typed text. |
| Non-system overlays could obscure pairing/secret windows | Tapjacking / misleading prompts | Hide application overlays on Android 12+, including private dialogs; retain obscured-touch filtering and screenshot protection. System/accessibility overlays and compromised OS remain outside this protection. |

**Compatibility:** upgrade both devices before a new USB pairing. Existing 0.7 paired
chats and message packets are unchanged. No server update is required for these changes.
This is conventional computational encryption, not post-quantum provisioning. A future
break of recorded P-256 ECDH could expose captured provisioning sessions. Public
hello/approval metadata, endpoint, frame sizes and timing remain observable. A compromised
endpoint can read material after decryption; Java/JSON strings and provider private-key
objects cannot be reliably erased. Temporary byte-array keys and frame buffers are cleared
on normal/failure exit where accessible. Physical comparison remains mandatory.

## Findings addressed in 0.7

| Finding | Severity / consequence | Change |
| --- | --- | --- |
| Reusing a pad in two local chat vaults would reset its directional allocation | High, key reuse could expose plaintext | Persistent cross-chat pad claims, seeded from legacy retirement records; reject a pad claimed by another chat even if its bundle is rewrapped with a different setup binding |
| Reusing a mailbox secret across friends could mix traffic and weaken identity boundaries | High, friend isolation fails | Persistent mailbox claims; new chats generate fresh secrets and copy only server URL/token; forgetting a configured chat does not make its secret reusable |
| Relay storage loss left accepted messages and lost receipts stranded | Medium, availability and misleading delivery state | Recover retained original ciphertext/receipts; preserve replay state, original expiry and pad reservations; authenticate device signatures before replay-triggered receipt recovery |
| Sensitive dialogs relied on the activity window rather than an explicit dialog-window policy | Low, possible screenshot exposure of dialog contents on differing window behavior | Apply FLAG_SECURE before showing every dialog and dismiss all dialogs when the activity stops |
| Android 12 and older implicitly exported the dynamically registered USB receiver | Low, another app could spoof disconnect/permission events to disrupt pairing; actual USB permissions still checked | Register the receiver as not exported through AndroidX across supported API levels |
| Local operations changed network backoff and some storage/file failures looked like network failures | Low, misleading connection/retry indicators | Explicit network-task classification; local operations preserve network backoff; monotonic elapsed time for contact age |

## Material risks still open

| Risk | Present consequence / boundary |
| --- | --- |
| Shared relay token | This is operator-wide access, not per-user/per-mailbox authorization. A token holder who knows a mailbox ID can fetch ciphertext and inject junk, and can consume global capacity. Clients still authenticate contents. Per-mailbox capabilities remain future work. |
| Full pad remains stored on device | A later endpoint/pad compromise exposes captured traffic for that pad, including earlier slots. The ledger prevents reuse; it does not erase consumed pad bytes or provide forward secrecy. |
| Experimental cryptography | SecureRandom-generated material and conventional HMAC/signatures do not establish information-theoretic OTP security. Protocol composition needs independent review. |
| Device state rollback/cloning | Restoring coordinated old vault/ledger/registry snapshots can defeat local high-water checks. Uninstall removes these records. Never restore operational pads or reinstall and reuse them. |
| USB provisioning cryptographic assurance | Version 3 prevents passive plaintext capture using conventional ECDH/AES-GCM, but the composed protocol is not independently audited or post-quantum. Verify the complete displayed code on both devices and use a trusted direct cable. Compromised endpoints remain a threat. |
| Secret QR is a bearer credential | A photographed setup QR remains valid after its one-minute display closes. It contains relay token and pairing secret, not the pad or private signing key. It is only for direct trusted physical transfer. |
| Release/update signing | CI produces debug APKs; owner-controlled stable release signing and verified updates are unfinished. An incompatible-signature uninstall erases pairing state; provision fresh pads afterward. |
| Metadata and denial of service | Relay operators see mailbox IDs, timing, directional packet headers and request addresses; they can drop/delay/replay traffic or lie about accepting it. They cannot be forced to deliver. |
| Endpoint and e-ink exposure | An unlocked or compromised OS, keyboard/accessibility service, camera or residual e-ink image can expose content. FLAG_SECURE and the optional Android credential gate do not solve these threats. |

## What is exposed on the configured relay

- Public `GET /health` returns a service-health status, without credentials or messages.
- `/v1/mailbox/<64-hex-mailbox-id>` GET/POST requires the shared bearer token.
  No mailbox enumeration, account registration, shell, pairing or plaintext-decryption
  endpoint exists in this server. Wrong/absent credentials receive 401.
- The persisted volume contains packet ciphertext, mailbox IDs, timestamps, sequence
  numbers and a storage epoch. The server process also holds its access token through
  environment/configuration. It does not receive pairing secrets, pads or device
  private keys through the client protocol.
- Nginx/Docker/Portainer administrators may have broader host access. The example
  Portainer stack publishes no relay host port and uses an external proxy network;
  root-compose defaults to a loopback-only published port. This is source configuration,
  not evidence of what the owner's running host currently exposes.
- HTTPS certificate validation is enabled in the client; redirects and cleartext HTTP
  are rejected. Reverse-proxy logs may expose mailbox IDs unless sanitized/disabled.
- Standard relay retention is 24 hours, not guaranteed forensic erasure of disks,
  backups, snapshots or previously captured network traffic.

## Multi-chat boundaries

Each of up to 12 contacts gets a distinct encrypted vault/Keystore alias, directional
pad ledger, replay state and device signing key. The encrypted index stores local
names and the selected vault ID. The previous station-v2 vault is referenced in place,
not copied or re-encrypted during migration. Chat IDs are random app-generated path
components; user-entered names never form filenames. Duplicate names and hidden
format/control characters are rejected. Missing/damaged index state fails closed.

The cross-chat claims registry retains pad IDs and derived mailbox IDs after Forget
pairing. Keys/pads are not copied when a new friend is added. QR review can replace only unused server-only setup; installed pads or any send/receive
activity blocks replacement. Existing paired chats cannot silently change role/secret or pinned peer key. USB pairing is explicitly
bound to the selected chat ID. A packet moved between independently paired friends
fails authentication. These are independent two-device chats, not group encryption.
A friend deliberately copying shared material to another device remains outside
what a local registry can prevent.

## Regression validation

The automated suite exercises encryption tampering, wrong direction/key, replay,
slot burn before encryption, restart/retirement/exhaustion, signed transcript binding,
physical-pairing proofs, malformed QR and scanner runtime, vault faults, separate
friend delivery, cross-chat key reuse rejection, bounded index/queue and exact delivery
recovery. Native screenshots and keyboard checks cover a 480 × 800 pixel viewport
at 240 dpi (320 dp width), alongside normal phone dimensions. The keyboard-visible
Android 12 capture copies the app window surface; the IME itself is not included
in that image. A separate assertion verifies the keyboard is shown and Send/traffic
remain visible. CI targets Android
API 29, Android 12/API 31 and API 35. Successful execution is reported in the PR/CI;
the presence of test source alone is not a passing test result.

Actual Mudita Kompakt firmware/USB compatibility, physical power-loss fault tests,
owner server exposure and signing-key custody have not been verified by this review.

Platform reference: https://developer.android.com/privacy-and-security/keystore
Mudita specifications: https://store.mudita.com/store/mudita-kompakt-global


Completed pairings hide administrative navigation from the normal conversation.
Chat settings remains available through the picker/status panel; this is a layout
choice, not an authorization boundary. Tests verify both hiding and access to settings.

0.8 regression additions cover encrypted large USB transfer in both directions, wire capture without setup secrets, tag/sequence/transcript changes, reflection, truncation, oversized lengths, replay and session invalidation after failed writes. Test execution must be verified in CI before release.

References: https://www.rfc-editor.org/rfc/rfc5869.html ; https://developer.android.com/security/fraud-prevention/activities ; https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING
