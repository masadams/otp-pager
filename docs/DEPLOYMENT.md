# Docker / Portainer deployment

This deploys the **server relay**. The client is the Android APK; it does not run
inside Docker. Current transport is experimental AES-256-GCM, not OTP or Jericho.
The relay sees ciphertext, mailbox identifiers, timing and connection addresses.

## Portainer: deploy directly from Git

No local build, image download or shell access is required once the published
GitHub container package is Public. GitHub Actions tests the relay and builds an
image for **amd64 and arm64**, then pushes it to:

`ghcr.io/masadams/otp-pager-relay:edge`

Each publication also has `sha-<full Git commit SHA>` and a content digest. `edge`
is the moving development tag. Updates are manual by default; for a fixed build,
set `RELAY_IMAGE` to a published digest such as
`ghcr.io/masadams/otp-pager-relay@sha256:<digest>`.

### One-time GitHub package visibility

GitHub defaults new container packages to Private, even in public repositories.
After the first successful image publication, open:

https://github.com/users/masadams/packages/container/otp-pager-relay/settings

Under **Danger Zone → Change visibility**, select **Public**. This makes the
compiled public-project image available anonymously; it does not expose your
relay token or message volume. The workflow checks an anonymous pull and reports
whether this setting is still required. Package visibility is an account setting
and is not changed by the GitHub connector. Alternatively configure an
 authenticated GHCR registry in Portainer with a suitable `read:packages` token.

### Portainer settings

Docker **Standalone** is the target; Swarm/multiple replicas are not supported.

1. In Portainer, ensure Nginx / Nginx Proxy Manager is attached to a shared Docker
   bridge network. You can create/attach this in Portainer's Networks/Containers
   screens; use the network's actual name below.
2. Stacks → Add stack → **Repository / Git repository**:

   | Setting | Value |
   | --- | --- |
   | Name | `otp-pager` |
   | Repository URL | `https://github.com/masadams/otp-pager` |
   | Repository reference | `refs/heads/feat/two-device-relay` |
   | Compose path | `deploy/portainer-stack.yml` |
   | Git authentication | Off (public repository) |

3. Set environment variables in Portainer:

   | Variable | Value |
   | --- | --- |
   | `PROXY_NETWORK` | Existing Docker network shared with Nginx; default `proxy` |
   | `RELAY_TOKEN` | Private random URL-safe token, 43–128 characters |
   | `RELAY_IMAGE` | Optional; defaults to `ghcr.io/masadams/otp-pager-relay:edge` |

   Generate the token with a trusted password manager (at least 43 random
   letters/digits/`_`/`-`), or run locally:

   ```sh
   openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
   ```

   Keep it different from the phones' pairing/encryption secret. Do not put the
   token in Git, the URL, screenshots or logs. Docker administrators can inspect
   container environment variables.

4. Deploy. Portainer pulls the published image. No relay port is published on the
   host; Nginx reaches **`otp-pager-relay:8080`** across the shared network.
5. Nginx Proxy Manager: add Proxy Host `pager.yourdomain.ca`, scheme **http**,
   forwarding hostname **otp-pager-relay**, port **8080**. Enable a valid trusted
   TLS certificate and Force SSL. Websockets are not required. Avoid an additional
   Basic Auth layer: the APK uses Authorization for its bearer token.
6. For ordinary Nginx, adapt `deploy/nginx.conf.example`; the rate-limit zone belongs
   in `http {}`. Configure equivalent edge limits if using NPM; a standard Proxy
   Host does not add the example's rate limit. Avoid logging mailbox URIs,
   Authorization headers and request bodies.
7. Check `https://pager.yourdomain.ca/health` returns `{"status":"ok"}`.
8. Install the APK from the successful **Android debug build** workflow's
   `otp-pager-debug-apk` artifact. Enter your HTTPS URL, the relay token, the shared
   pairing secret, and opposite A/B station roles. Use TEST LINK, then TRANSMIT
   and RECEIVE in each direction. Health is only liveness; transmitting verifies
   writable storage. Pairing secret entry remains manual and in person.

For updates use Portainer **Pull and redeploy**, with **Re-pull image** enabled.
Automatic GitOps updates are optional; leave them off while testing. A fixed
image digest stays fixed even when you re-pull. Nginx may need reloading after
an upstream container is recreated with a different Docker address.

## Optional local build

For offline use or a local image instead of GHCR:

```sh
git clone --branch feat/two-device-relay https://github.com/masadams/otp-pager.git
cd otp-pager
docker build -t otp-pager-relay:local ./relay
```

Then set `RELAY_IMAGE=otp-pager-relay:local` in Portainer. The **Relay tests**
workflow also uploads `otp-pager-relay-image` with `relay-image.tar` for amd64
hosts; extract and use `docker load -i relay-image.tar` as another fallback.

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
