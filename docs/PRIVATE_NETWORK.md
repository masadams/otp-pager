# Integrated private networking: proposed next feature

Status: design only; **not implemented in the 0.4 APK or current Portainer stack**. The existing relay/unread/peer indicators are not VPN indicators.

## Choice and user flow

Use the official embeddable WireGuard Android tunnel library for a client-to-server private network. One self-hosted Linux gateway and a private mailbox relay fit this project better than a full mesh control plane. Nebula remains an alternative if direct device meshes become a requirement. No dependency on a hosted VPN account or hosted coordination service is planned.

1. Deploy an optional Portainer stack containing the gateway, a narrowly scoped enrolment component and the existing unprivileged relay. Persist gateway identity and peer assignments across restarts.
2. Server initialisation creates its identity once. An authenticated operator generates a short-lived, single-use enrolment code through a server management flow. No hand-edited peer configurations or separate client VPN app should be required.
3. Field's Network setup imports the trusted server address, server public key and enrolment grant. The device generates its own WireGuard private key locally; only the public key goes to enrolment. Keep the private key encrypted using Android Keystore-backed storage. Do not claim the native tunnel can use a non-exportable Keystore key directly.
4. Android displays its mandatory system VPN permission dialog. Thereafter Field manages tunnel connection and retries. If permission is denied or another VPN takes over, the UI must explain the condition and stop private relay requests.
5. In required-private mode, messages remain queued whenever the tunnel or private relay cannot be verified. There is no silent switch to the public relay.

There is unavoidable one-time infrastructure work: the server needs a reachable UDP endpoint, and routers/cloud firewalls may require an explicit port mapping. An ordinary Nginx HTTP proxy host does not provide this UDP forwarding. The app cannot silently open somebody else's router or bypass Android's permission system. Android permits one active VPN per user/profile, even with per-app routing.

## Server boundaries

- The relay's message API should be reachable only through the WireGuard network in the private deployment. Keep HTTPS and existing relay authentication inside the tunnel; resolve certificate/name routing explicitly rather than permitting cleartext or trusting arbitrary certificates.
- Keep gateway/enrolment administration separate from the relay. Do not give the relay NET_ADMIN, the Docker socket or blanket privileged-container access.
- The gateway needs only the Linux capabilities/device access required by the selected backend. Verify host kernel support and Portainer compatibility on both amd64 and arm64 before selecting a deployment image.
- Persist keys, allocations, grant consumption and revocations. A missing/corrupt existing identity must stop startup, not silently generate a replacement that invalidates clients.
- Public enrolment, if used, must require an unguessable scoped grant, expire it quickly, consume it atomically, rate-limit attempts and return only the requesting peer's configuration. Issue grants only through an authenticated management path. Do not expose a general public peer-registration or administrative API.
- Avoid placing secrets in Git, container environment examples, startup logs, image layers, ordinary health responses or public QR screenshots. Decide secure bootstrap and administrator recovery before implementing enrolment.

## Android and indicators

Use per-app routing limited to Field and routes limited to the required private services. Keep tunnel keys in a separate encrypted store from pairing/pad material. Never include VPN private keys or administrative credentials in pad exports.

Provide Connect / Disconnect, automatic reconnect, permission/error recovery and these distinct indicators:

- **Tunnel:** off, permission needed, connecting, verified connection, stale handshake, or failed. An interface marked UP alone is not proof of server reachability.
- **Relay:** last successful authenticated request through the intended private path and its age.
- **Peer:** authenticated pad readiness and message delivery, as in 0.4. This is not a live-presence claim.

Required-private mode must enforce the actual request path: route loss, revocation, process restart, VPN permission withdrawal, other-VPN takeover, DNS changes and IPv6 must not let queued requests escape through the ordinary network. Verify backend support for socket binding/routing and enforce a transport gate; a green label or a route configured once is insufficient.

Keep background tunnel lifecycle separate from messaging lifecycle. A tunnel connection alone does not add background message delivery. Foreground-service, always-on and battery behaviour need device validation against the selected Android library/target SDK before promising them.

## Implementation and acceptance

1. Pin and review the official Android tunnel artifact, its native dependencies, supported ABIs, SDK requirements and licences. Build a minimal real-device connection test.
2. Build the private gateway/relay Compose option with documented HTTPS/DNS routing and least privilege. Test persistence, identity loss, reboot and peer revocation.
3. Implement scoped enrolment, local key generation, encrypted persistence, one-use grants and operator recovery. Provisioning must remain separate from physical pad exchange.
4. Add the real Network controls and measured indicators. Distinguish local tunnel creation from a successful handshake and private relay request.
5. Exercise Wi-Fi/mobile switching, unreachable UDP, denied permission, another VPN, DNS/IPv6 route failures, process death and server restart. Capture traffic on the ordinary interface and prove no private relay requests leak when required-private mode fails.
6. Test physical Android/e-ink devices. Publish a separately identified networking build only after these checks; do not retrofit a misleading VPN badge onto the current direct-HTTPS transport.

Primary sources checked:
- https://www.wireguard.com/embedding/
- https://git.zx2c4.com/wireguard-android/about/
- https://developer.android.com/develop/connectivity/vpn
- https://nebula.defined.net/docs/

## Split-relay experiment (pipeline item 8): plan, not implemented

Goal: no single relay sees both phones' connections. Phone A posts only to relay **P**; phone B
fetches only from relay **F**. P forwards each accepted packet to F over a private link (for
example ZeroTier between the owner's Oracle Cloud and home servers).

What it would take:

1. **Relay:** an optional `RELAY_FORWARD_URL` and `RELAY_FORWARD_TOKEN`. After committing a
   post, P re-posts the exact packet to F for the same room. F accepts it with a dedicated
   *peer* token that may only append, never read or claim. Claims stay per relay.
2. **App:** per chat, a "post via" relay and a "fetch via" relay. Backup relays (item 6) already
   give per-relay cursors and claims, so this is mainly a routing preference. Receipts flow
   the same way in reverse.
3. **Limits to state honestly:**
   - P and F together, or anyone watching both, still link traffic by timing unless forwarding
     adds random delay;
   - delay costs latency;
   - cover traffic costs pad slots;
   - a single operator running both relays gains nothing.

Owner step first: stand up a second relay and a private link between the two. The code is
worth writing only once that exists to test against.
