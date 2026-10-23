# Android 0.5: physically pinned device signatures

Experimental signed-message profile; no independent security audit. This is not
X3DH, a Signal implementation, remote attestation, or a deniable authenticated
session. Standard Android/JCA ECDSA primitives are used; the application framing
and integration are project-specific and need review.

## Setup

1. Upgrade BOTH stations to 0.5. Complete pad comparison/readiness and sync all
   queued packets first. Receive any outstanding old traffic before upgrading the
   pairing to device binding. Ordinary app upgrades preserve history, counters,
   queued ciphertext and pad retirement records; starting device binding requires
   the outbox to have no queued packets.
2. Open Device and create an identity on BOTH stations. This creates a signing-only
   P-256 key in Android Keystore. The UI reports whether that key is hardware or
   software backed. Public card generation/verification uses no pad slots.
3. Select and copy the PUBLIC CARD text. Transfer it to the other station through
   physical storage or a trusted local connection; these are public keys, not the
   secret pad file. Paste the other station's card into its Device tab.
4. Tap Compare & pin. Compare ALL 64 hexadecimal fingerprint characters against
   the fingerprint displayed on the OTHER physical device. The final eight
   characters entered from that device are only an additional typing check.
   Repeat in the opposite direction. Do not trust fingerprints supplied by a relay.
5. New messages can be sent after the peer key is pinned locally. The recipient
   can finish pinning and receive later; there is no recurring peer handshake or
   TOTP code. Complete both exchanges promptly: the relay still expires traffic
   after 24 hours. The badge says DEVICE KEY PINNED, not peer online.

Starting identity creation pauses new sends until pinning finishes and rejects
new unsigned incoming messages/receipts. A signed frame arriving during physical
exchange is held at the transport cursor until pinning completes. Previously
stored history remains readable and is labelled shared-secret/pad-only; it is
never retroactively labelled device verified. Device pinning resets only the
transport cursor/epoch to recover held frames; the replay window and pad counters
are retained. A full relay page consisting of already-seen packets may require
several syncs to reach the new traffic.

No in-pairing key replacement or downgrade button exists. A missing/replaced
private key fails closed; there is no automatic regeneration. Changing devices
requires a fresh physical pairing with a fresh secret AND pad. Forget pairing
removes the private identity key but retains the existing pad retirement ledger.
Vault schema 4 prevents older APKs from opening a device-binding vault. Uninstall,
coherent storage rollback and copying operational secrets remain outside the
app's reliable detection boundary.

## Signed bytes and packet processing

`IdentityProtocol` signs with SHA256withECDSA using named curve secp256r1. Only
canonical Base64-encoded DER SubjectPublicKeyInfo P-256 cards are accepted. The
fingerprint is SHA-256 over those canonical public-key bytes. Identity signatures
are INSIDE the existing fixed-size v3 encrypted pad frame; relay framing and
Docker/Portainer/Nginx configuration do not change.

The signature transcript, in order:

- 4-byte big-endian length + ASCII domain
  `Field device signature v1 / SHA256withECDSA / P-256`;
- length-prefixed ASCII mailbox ID (64 lowercase hex), then pad ID (32 hex);
- role byte (`A`/`B`), then 8-byte big-endian positive pad slot counter;
- length-prefixed sender public-key DER, then intended recipient public-key DER;
- length-prefixed exact UTF-8 JSON body, including text/receipt kind and contents.

The inner plaintext is `FD1:<Base64 UTF-8 body>:<Base64 DER ECDSA signature>`.
The outer pad authentication is checked before device signature verification;
JSON payload processing, replay-window updates, inbox writes and receipt generation
happen only after both authentication checks pass. A copied signature cannot be
moved into another role, pad slot, mailbox, recipient identity, or payload.
Unsigned traffic is rejected after identity creation. All newly generated text
and receipt packets in a pinned pairing use this profile. Previously stored
unsigned readiness ciphertext is never re-announced once identity setup starts.

Durable pad reservation still precedes encryption. Retries upload exactly the
stored ciphertext/signature without spending another slot. Signing/storage
failures can waste a reservation, but do not make it reusable. Size checks account
for Base64/signature expansion before reserving. The existing 2048 UTF-8 text cap
still applies, and JSON escaping can impose a lower effective limit.

## Boundaries

A valid signature means the pinned private key signed that exact message/context.
It does not establish current presence, human identity, hardware provenance, a
trustworthy OS, or a fresh live challenge. A compromised original device may still
invoke its Keystore key. The peer does not verify hardware attestation.

Copied pads still permit decryption of messages; device signatures do not restore
confidentiality after pad theft. They prevent a pad-only clone from impersonating
the pinned sender, assuming the private identity key and endpoint remain secure.
Public signatures also create transferable attribution if plaintext/signatures
are disclosed; this mode does not offer cryptographic deniability. A reviewed
asynchronous session protocol would be a separate design change if deniability
becomes a requirement. There are no new encryption keys derived from TOTP and no
recurring encrypted pad heartbeats.

Android SecureRandom pads and HMAC remain computational/experimental. They are
not a claim of information-theoretic OTP security. WireGuard remains a separate
proposed networking feature, not implemented by this change.

## Verification

JVM tests bind every transcript field, check wrong curves and malformed inputs,
and verify an independently generated Python-cryptography transcript/signature
fixture. Android instrumentation exercises real Keystore signatures, delayed
recipient pinning, retained legacy history, lost acknowledgements/restart, replay,
pad-only clone forgery, moved signatures, pinned-key replacement rejection and
missing-key failure. The UI test captures fictional traffic only.
