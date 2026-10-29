# Post-quantum status

Owner direction (2026-10-29): everything that isn't the one-time pad itself should be
post-quantum. This page lists every public-key use in the project. Each entry says whether it
is post-quantum, what a quantum attacker could do with it, and what comes next.

Message text is protected by the one-time pad, which a quantum computer can't break; that
needs no change. Symmetric primitives (AES-256-GCM for storage and channels,
HMAC-SHA256, SHA-256) keep at least 128-bit security against Grover's algorithm and also stay
as they are.

## Done

### USB pairing channel: hybrid ECDH P-256 + ML-KEM-768 (pairing protocol v10)

The pad, chat secret, relay token and control keys all cross the USB cable inside this
channel. It is the main harvest-now-decrypt-later target: whoever recorded the cable
traffic and can later break the key exchange would get the whole pad.

- Each phone's hello carries an ephemeral ML-KEM-768 (FIPS 203) encapsulation key next to its
  ephemeral ECDH key. Both are checked: exact size and the FIPS 203 modulus check.
- Each phone encapsulates to the other. Both ciphertexts and both ML-KEM keys go into the
  code the two people compare, so a substitution changes the code.
- Session keys come from HKDF-SHA256 over `ECDH secret ‖ A's ML-KEM secret ‖ B's ML-KEM
  secret`, salted with that transcript (`CableSession.establish`). An attacker has to break
  **both** ECDH and ML-KEM. A flaw in either one alone doesn't expose the pad.
- The same channel carries renewal, and the paused NFC + Wi-Fi transfer when it returns.
- `test-vectors/cable-v4.properties` comes from independent Python code
  (`scripts/cable_vectors.py`, `cryptography` library); `CableSessionTest` must reproduce it.
  `PostQuantumTest` covers ML-KEM round trips, implicit rejection and input checks.
- Implementation: Bouncy Castle `bcprov-jdk18on` 1.86, lightweight API only. No JCA
  provider is registered, so Android's own provider is unaffected.
- Both phones need a v10 build. An older peer is refused with the existing "upgrade both
  devices" message. Existing pairings are untouched; this only affects new pairings and
  renewals.

### Relay HTTPS: hybrid X25519MLKEM768 offered first (phone side)

Recorded relay traffic exposes relay tokens, mailbox IDs and timing, never message text.

- Every relay connection now goes through a bundled Conscrypt (BoringSSL, `RelayTls`). Every
  Android version therefore offers `X25519MLKEM768` first, then X25519, P-256 and P-384.
- Certificates and hostnames are still checked by the phone's own trust manager and this
  app's network security config. User-installed CAs stay untrusted.
- If the native library can't load on a device, the app falls back to Android's built-in TLS.
- The hybrid exchange only happens if your proxy also supports it (next section).
  **Friend details → Test server connection** shows a "Post-quantum TLS" line saying whether
  it did.
- `RelayTlsTest` (on the emulator) runs local TLS 1.3 servers that accept only the hybrid group
  or only X25519. It checks that both work, that the probe reports each correctly, and that
  an untrusted certificate is still refused.

## Not yet post-quantum

| Use | Today | Quantum risk | Plan |
|---|---|---|---|
| Device identity: signs messages, receipts and pairing approvals | ECDSA P-256, non-exportable Android Keystore key | Real-time forgery only; nothing recorded becomes readable. Each message is inside the OTP layer and carries the pad's HMAC, so forging a message needs the pad, not just the signature. | Per-message ML-DSA signatures don't fit: every message must fit one 4 KiB pad block, and an ML-DSA-65 signature alone is 3.3 KB. The useful place is the pairing approvals and the pinned identity (hybrid ECDSA + ML-DSA, with the ML-DSA key in the encrypted vault because Android Keystore has no ML-DSA). |
| Strict-chat unlock requests and grants | ECDSA P-256: device key on the phone, authority key on the relay | Real-time forgery of a grant or unlock | Hybrid signatures as above, plus the relay authority (Node: ML-DSA via `node:crypto` once the base image's OpenSSL supports it, or a vetted library). |
| Relay HTTPS, server side | Your Nginx/proxy decides | As above, until the proxy offers the hybrid group | Enable `X25519MLKEM768` on the proxy (below), then check the connection test. |
| APK signature | RSA-3072 | Future forgery of an update, which would need the attacker to also get the APK onto your phone | Android only accepts RSA/EC/DSA APK signatures. Nothing to do until Android supports a post-quantum scheme. |

### Relay TLS: enabling hybrid key exchange on the proxy

If your Nginx is built against OpenSSL 3.5 or newer, add this to the `server` block in
`deploy/nginx.conf.example`:

```nginx
ssl_ecdh_curve X25519MLKEM768:X25519:prime256v1;
```

Check `nginx -V` first: older OpenSSL builds refuse to start with an unknown group. The app
offers the hybrid group on every Android version; clients that don't still get X25519. Message
contents never depend on TLS.

## Limits

- ML-KEM and the hybrid design are standard (FIPS 203, as in TLS `X25519MLKEM768`). This
  project's way of combining them hasn't been independently reviewed yet, the same as the rest
  of the protocol (see [SECURITY_REVIEW.md](SECURITY_REVIEW.md)).
- The comparison code and physical presence still provide authentication. A person who skips
  comparing the code isn't protected against a machine-in-the-middle, quantum or not.
- Bouncy Castle adds roughly 7 MB to the APK and Conscrypt roughly 5 MB.
