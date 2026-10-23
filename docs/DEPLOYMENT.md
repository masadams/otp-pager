# Docker / Portainer deployment

This deploys the **server relay**. The client is the Android APK; it does not run
inside Docker. Current transport is experimental AES-256-GCM, not OTP or Jericho.
The relay sees ciphertext, mailbox identifiers, timing and connection addresses.

## Build on the Docker host

Use the same branch for relay and APK (the new relay requires bearer authentication):

```sh
git clone --branch feat/two-device-relay https://github.com/masadams/otp-pager.git
cd otp-pager
docker build -t otp-pager-relay:local ./relay
node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"
```

Keep that generated token private. It is the **relay access token**, shared by your
two phones, and must be different from the pairing/encryption secret. Do not put
it in Git, the relay URL, Nginx logs or a screenshot. Set `RELAY_TOKEN` to it in
Portainer or in a local `.env` file for Compose. Docker administrators can inspect
container environment variables. No actual token is supplied in this repository.

If Node is unavailable, generate the token with the built image:

```sh
docker run --rm otp-pager-relay:local node -e "console.log(require('node:crypto').randomBytes(32).toString('base64url'))"
```

Alternatively, the successful Relay tests workflow uploads `otp-pager-relay-image`
containing `relay-image.tar`. Download/extract it and run `docker load -i
relay-image.tar` on an amd64 Docker host. The artifact image tag is
`otp-pager-relay:local`. Other architectures should build locally. No registry
account or published image is required.

## Portainer + container Nginx / Nginx Proxy Manager (recommended)

Docker Standalone is the target; Swarm/multi-replica deployment is not supported.

1. Build/load the image on the **same Docker endpoint managed by Portainer**.
2. Use an existing shared Docker bridge network for Nginx and the relay. If needed,
   create one: `docker network create proxy`, then attach your Nginx container to it.
3. Portainer → Stacks → Add stack → Web editor. Paste `deploy/portainer-stack.yml`.
4. Set `RELAY_TOKEN` to the generated token and `PROXY_NETWORK` to your shared
   network name (default `proxy`). `RELAY_IMAGE` defaults to `otp-pager-relay:local`.
5. Deploy. The relay has **no published host port**. Nginx reaches the Docker
   network alias `otp-pager-relay`, port **8080**, using **HTTP** internally.
6. In Nginx Proxy Manager: create a Proxy Host for e.g. `pager.yourdomain.ca`, scheme
   `http`, forward hostname `otp-pager-relay`, port `8080`. Enable a valid trusted
   TLS certificate and Force SSL. Websockets are not needed. Avoid an extra Basic
   Auth layer: the APK uses the Authorization header for its bearer token.
7. For ordinary Nginx, adapt `deploy/nginx.conf.example`. Its rate-limit zone
   belongs in `http {}`. Configure equivalent limits at your edge if using NPM;
   its standard proxy-host setup does not add the example's request rate limit.
   Avoid logging mailbox URIs and do not log Authorization headers or bodies.
8. Verify `https://pager.yourdomain.ca/health` returns `{"status":"ok"}`. This is
   a public liveness check, not a proof of authentication or writable storage.
   The APK's TEST LINK checks authenticated mailbox access; TRANSMIT tests writes.

Use only one instance of this stack per network alias. Nginx may need reloading
when the upstream container is recreated and its Docker address changes.

## Host Nginx + Compose

Set `RELAY_TOKEN` in `.env`, then:

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

`compose.yaml` publishes only `127.0.0.1:8080` by default. Point host Nginx at
`http://127.0.0.1:8080` and terminate TLS there. Container Nginx cannot reach the
host loopback through its own `127.0.0.1`; use the shared-network stack above.
Do not publish port 8080 to the internet. Phones always use your **HTTPS URL**.
Self-signed certificates and HTTP URLs are intentionally not accepted by the APK.

## Storage and operation

- Named volume `relay-data` stores opaque ciphertext and a monotonically
  increasing sequence counter. Writes use atomic rename and fsync before success.
- One relay process per local volume; do not share it across replicas or NFS.
- Messages expire after 24 hours. Cleanup runs every minute and on requests while
  running (expiry is not guaranteed during downtime). Counters survive expiry.
- Limits: 200 live packets per mailbox, 2,000 globally, 12,000 encoded characters
  per packet, 16 KiB request body. Capacity returns HTTP 429; expired packets free
  space. The relay does not silently evict live messages to accept new ones.
- Healthcheck, restart policy, non-root user, read-only root filesystem, dropped
  capabilities and bounded container logs are configured. Only `/data` is writable.
- Invalid/corrupt state prevents startup rather than silently resetting cursors.
- `docker compose down` preserves the volume; `down -v` destroys it. Do not restore
  an old snapshot into an active conversation: server cursors can move backwards.
- Expiry is logical deletion, not forensic erasure of flash, volumes or backups.
- Single operator token is for a small private prototype, not multi-user hosting.
  Rotate by changing the environment variable, redeploying, and updating both phones.
- This is polling via RECEIVE, not push delivery. No background notifications,
  client outbox, durable client replay protection or delivery receipts yet. Keep
  the pairing secret for a test session; closing the app requires re-entry and
  can display old messages again. A timeout after sending can mean the packet was
  committed; retrying may create a duplicate.

## Optional private network

ZeroTier and Mobile Nebula both have separate Android clients. You can run the
chosen overlay on your server/host and both phones, then reach the same HTTPS
relay hostname through it. For private-only service, make the hostname resolve to
the private overlay address (split DNS or appropriate DNS records), route to Nginx,
and retain a trusted TLS certificate; DNS-01 issuance can avoid a public web port.
Restrict firewall access to the overlay as appropriate. Container-only overlay
attachment is not sufficient unless Nginx is reachable through it too.

We are deliberately leaving VPN engines out of this APK for now. ZeroTier also
provides the embeddable libzt socket SDK with Android examples; integrating that
would require native libraries, identity lifecycle and a different socket/TLS
transport. A system-wide tunnel using Android VpnService needs explicit OS consent
and competes for the active VPN slot. Nebula's existing mobile app is another
practical route. Neither overlay replaces end-to-end encryption or OTP accounting.

References:
- https://docs.zerotier.com/sockets/
- https://docs.zerotier.com/android/
- https://nebula.defined.net/docs/guides/quick-start/
- https://developer.android.com/develop/connectivity/vpn
- https://docs.portainer.io/user/docker/stacks/add
