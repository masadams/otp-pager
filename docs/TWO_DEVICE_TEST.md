# Two-device network test — experimental

## Architecture
Two Android phones use an in-person shared 32-byte secret and exchange opaque AES-256-GCM packets via a mailbox relay. The shared secret is encoded as URL-safe Base64; it is **not an OTP**. Both devices must manually enter the same secret and the same HTTPS relay URL.

## Steps
1. Deploy the Docker/Portainer relay using [DEPLOYMENT.md](DEPLOYMENT.md). Run `cd relay && npm test` for local verification.
2. Expose the relay behind an HTTPS reverse proxy with a valid TLS certificate. **Never use plaintext HTTP across the internet.**
3. Build the Android branch via GitHub Actions and install the debug APK on both phones.
4. On device A tap GENERATE PAIRING SECRET. Transfer the displayed secret **in person** to B; do not send it over the relay.
5. Enter the same HTTPS relay URL and relay access token on both phones, paste the pairing secret into B, and choose opposite station labels (A/B). Tap TEST LINK on each phone; expect LINK VERIFIED.
6. TRANSMIT on one phone and RECEIVE on the other. Repeat in reverse. Restart the relay container after a transmission and check that the other phone can still receive it. RECEIVE is manual; there is no background polling yet.

## Security limits
- **Not Jericho-compatible; not a one-time pad; not audited.** AES-GCM is a temporary test transport.
- The relay persists ciphertext in a Docker volume for up to 24 hours and requires a separate operator bearer token. Mailbox/global capacity and body limits are enforced; configure request rate limits at Nginx. All token holders share relay access privileges. This is a small private deployment, not a public registration service.
- A mailbox ID derived from the shared secret lets the relay correlate all packets within the pair; IP addresses and timing remain visible.
- Both phones share a secret; physical entry is not an authenticated key-agreement protocol, and an attacker observing the exchange compromises the channel.
- The demo stores no pairing secret persistently; restarting requires re-entry. No offline message queue on the client.
- A malicious relay can replay, drop, delay, or reorder messages. AES-GCM detects modifications but does not solve replay. This demo displays repeated packets after restart.
- Do not use for sensitive communications.

## Future
Review Jericho protocol and licensing, implement verified physical pairing, durable key accounting, and anti-replay, then replace this temporary encrypted transport.

