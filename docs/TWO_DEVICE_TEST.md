# Two-device network test — experimental

## Architecture
Two Android phones use an in-person shared 32-byte secret and exchange opaque AES-256-GCM packets via a mailbox relay. The shared secret is encoded as URL-safe Base64; it is **not an OTP**. Both devices must manually enter the same secret and the same HTTPS relay URL.

## Steps
1. Run `cd relay && npm test && npm start` on a Node 20+ machine.
2. Expose the relay behind an HTTPS reverse proxy with a valid TLS certificate. **Never use plaintext HTTP across the internet.**
3. Build the Android branch via GitHub Actions and install the debug APK on both phones.
4. On device A tap GENERATE PAIRING SECRET. Transfer the displayed secret **in person** to B; do not send it over the relay.
5. Enter the same HTTPS relay URL on both phones, paste the secret into B, and choose opposite station labels (A/B).
6. SEND on one phone and RECEIVE on the other. Repeat in reverse.

## Security limits
- **Not Jericho-compatible; not a one-time pad; not audited.** AES-GCM is a temporary test transport.
- The relay stores ciphertext in RAM for up to 24 hours and loses it on restart; there is no authentication or rate limiting, so do not deploy publicly without access controls and abuse protections.
- A mailbox ID derived from the shared secret lets the relay correlate all packets within the pair; IP addresses and timing remain visible.
- Both phones share a secret; physical entry is not an authenticated key-agreement protocol, and an attacker observing the exchange compromises the channel.
- The demo stores no pairing secret persistently; restarting requires re-entry. No offline message queue on the client.
- A malicious relay can replay, drop, delay, or reorder messages. AES-GCM detects modifications but does not solve replay. This demo displays repeated packets after restart.
- Do not use for sensitive communications.

## Future
Review Jericho protocol and licensing, implement verified physical pairing, durable key accounting, and anti-replay, then replace this temporary encrypted transport.
