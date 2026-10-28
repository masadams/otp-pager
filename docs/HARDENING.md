# Android station v0.2: security, reliability, and e-ink

This is an experimental AES-GCM messenger, **not a true OTP**,
and not independently audited. Android (including Android e-ink readers) is now
the primary target. T-Deck / Thistle is optional future work, not a prerequisite.

## What the internet-facing server can and cannot do

| If the relay is compromised | Effect |
| --- | --- |
| Read the message database | Ciphertext, timestamps, mailbox IDs, sequence counters; no pairing keys or plaintext |
| Observe requests | Source/forwarded addresses at Nginx, timing, packet size and mailbox correlation |
| Alter ciphertext or authenticated headers | New client rejects it; authentication is checked before replay state changes |
| Replay old authenticated traffic | New client rejects duplicates across normal restarts, within the persisted counter/window policy |
| Delay, reorder or delete packets | Availability can be disrupted; cryptography cannot force delivery |
| Pretend a POST succeeded | Sender can show Relayed, but Delivered requires a valid receipt from the other station |
| Steal the relay access token | Can access opaque mailboxes; the separate pairing secret is still needed to decrypt/forge |
| Flood the host/network | App limits help with resource abuse; they are not a substitute for edge/firewall or upstream DDoS protection |

The relay token is a shared operator credential for a two-station/private test.
It is **not** per-user authorization. Keep it distinct from the pairing secret.
The two stations use a symmetric shared secret; either station possesses both
message-direction keys. No claim of nonrepudiation or forward secrecy is made.
A future compromise of a pairing secret can decrypt retained traffic for that
pairing; physical key rotation and eventual OTP work remain separate milestones.

## Changes in this version

- AES-256-GCM v2 frames authenticate version, direction, message counter, random
  128-bit message identifier and 96-bit random nonce. Direction keys use RFC 5869
  HKDF-SHA256 with distinct labels. A shared Node/Kotlin test vector checks framing.
- Each outgoing message is encrypted **once**, then durably queued before upload.
  A retry sends the same wire bytes; the relay deduplicates them, including after
  a restart. Relayed means accepted by the relay, not read by a person.
- The recipient atomically persists ciphertext, replay state, transport cursor,
  and an encrypted delivery receipt before advancing. A receipt means the peer
  saved the message. It does not prove a person read it.
- Sender counters and a 4,096-counter replay window persist. Reordered messages
  within that window are accepted once; older ones are rejected. Very large
  reordering can discard legitimate late traffic. Clearing history retains replay
  state and send counters.
- One Android Keystore AES-GCM key encrypts the complete atomic local snapshot:
  pairing credentials, counters, ciphertext inbox and outbox. Stored state resides
  in no-backup storage. Hardware backing depends on the Android device; cloud
  emulators are not private endpoints. There is no plaintext message database.
- A corrupt or unreadable local vault fails closed. The app does not silently
  start a new counter sequence. Forget pairing explicitly invalidates its Keystore
  key and deletes the local state. Use a NEW pairing secret on BOTH devices after
  forgetting/reinstalling; do not reuse an old secret with reset counters.
- Android device credentials gate entry when the device has a screen lock. Content
  and draft fields clear when the activity stops. This UI gate is additional to
  Keystore storage, not per-operation biometric binding of the storage key.
- Screenshots are blocked, credential autofill/UI state saving is disabled, and
  message input disables suggestions. A compromised OS, keyboard, accessibility
  service or unlocked endpoint remains a threat. JVM strings are not reliably
  zeroizable and crash/flash forensic erasure is not claimed.
- Foreground-only sync every 15 seconds; exponential error backoff up to 60 seconds.
  No wake lock, background service or push provider. Stop/lock the app and automatic
  delivery stops. Manual Sync remains available when automatic sync is off.
- White backgrounds, black text/borders, no custom animation, stable message views,
  separate Messages and Link screens. Idle polling does not reconstruct the
  conversation or rewrite an unchanged vault. Decrypted view state is cached only
  for the foreground session. Actual e-ink ghosting, refresh behavior and battery cost still
  need measurement on the user's Android e-ink device.

## Bounds and retention

Messages: 1–2,048 UTF-8 bytes. Local pending queue: 50 packets (receipts included).
Up to 100 recent inbox and 100 completed outgoing text packets are retained for
24 hours; the UI shows up to 100 recent messages total.
Local expiry/pruning happens during app operations; disk bytes are not guaranteed
removed while the app is closed. Clear history removes local received/completed
traffic immediately while preserving pending sends. Queued traffic older than
24 hours expires rather than being transmitted unexpectedly later.

Relay: 200 packets per mailbox, 2,000 globally, 24-hour expiry, 50-packet response
pages, 16 KiB bodies, 128 simultaneous sockets, request/header/read deadlines,
canonical base64 validation and independent per-peer budgets for authorized and
unauthorized requests. Proxy peer addresses are used, never an untrusted forwarded
header. Thus authorized budgets are shared behind a single Nginx proxy; configure
per-public-IP limits at the edge. Storage errors poison the running process and
return 503 (including health) until restarted. No successful POST acknowledgement
is issued for a failed durable commit. Persisted volume schema v1 migrates in place
without resetting existing counters or dropping ciphertext.

Logical deletion is not physical media erasure. There are no automatic backups;
snapshot restoration can roll back counters/replay state and is not supported for
active pairings. The relay storage epoch detects a replacement volume and permits
transport resync while retaining the client's replay state. A malicious relay can
lie about transport metadata; it still cannot forge authenticated message contents.

## Upgrade your running setup

1. In Portainer, pull/redeploy the relay image. Keep the named data volume and your
   existing relay token. The server remains compatible with old APK traffic while
   you update phones; new v2 clients require the upgraded relay.
2. Install the v0.2 APK on BOTH devices. v2 deliberately ignores legacy v0.1 packet
   contents. Mixed client versions cannot exchange readable messages.
3. Debug APK signing is not a production release pipeline. GitHub-hosted debug
   keys may differ between workflow runs. If Android reports an incompatible
   signature, uninstall the previous debug app first. This erases local state:
   create a fresh pairing secret on BOTH devices. Stable release signing using
   owner-controlled Actions secrets is still required before routine updates.
4. On Link, enter your HTTPS hostname, relay token, new shared pairing secret and
   opposite A/B roles. Save & connect, then compare the pair-check code in person.
5. Test sending while offline, close/reopen, reconnect and Sync. Confirm one copy
   arrives and the sender eventually shows Delivered after the peer syncs.
6. Restart the relay between send/receive and confirm delivery still works. Clear
   history and confirm old packets do not reappear after reopening.

## Nginx / Nginx Proxy Manager

Keep only Nginx's HTTPS entry point public; the Portainer relay stack publishes no
host port. Never expose the Docker socket or Portainer/NPM admin UI to the public
internet without their own strong access controls. Keep the host, proxy and Docker
images patched. Set a long random relay token; rotate it in Portainer and both
phones if entered into a third-party cloud emulator. A cloud emulator can observe
the secrets and plaintext handled by the hosted device.

For ordinary Nginx use `deploy/nginx.conf.example` (zones in `http {}`, TLS and
connection/request limits in the server). For Nginx Proxy Manager, see
`deploy/npm-http-top.conf.example` and `deploy/npm-proxy-advanced.conf.example`.
These are opt-in operator configuration; this repository cannot apply them to your
already running Nginx. Test with `nginx -t` in the proxy container before reloading.
If a CDN/reverse proxy precedes Nginx, configure trusted real-IP handling there;
do not blindly trust public X-Forwarded-For headers. Disable/sanitize access logs
that contain mailbox URIs, and never log tokens or request bodies. Keep HTTPS with
valid certificates. No certificate-validation bypass is available in the APK.

An optional private overlay (ZeroTier/Nebula) can restrict who reaches Nginx.
It is useful network access control, not a replacement for message encryption,
endpoint protection, or correct key accounting. No VPN engine is embedded in this APK.

## Remaining work

Physical pad exchange / OTP accounting; an independent protocol/security review;
owner-controlled release signing; per-mailbox access capabilities; stronger
metadata resistance and forward secrecy; hardware-backed key attestation where
appropriate; background delivery if desired; real e-ink performance measurement.

Sources for platform/operator behavior:
- https://developer.android.com/privacy-and-security/keystore
- https://developer.android.com/privacy-and-security/cryptography
- https://nginxproxymanager.com/advanced-config/
