# Deploy OTP Pager 0.7.0

The server is an opaque mailbox relay in Docker. The client is the Android APK.
Finite-pad/device-bound mode and labelled AES-GCM test mode use the same relay API.
The relay does not receive pad material, pairing secrets or private device keys.
This is an experimental field release; see [security review](SECURITY_REVIEW.md).

## Release files

https://github.com/masadams/otp-pager/releases/tag/v0.7.0

- `otp-pager-0.7.0-debug.apk`: the exact APK from the successful Android matrix.
- `portainer-stack.yml`: deployment stack using the versioned image by default.
- `relay-image.txt`: versioned image and immutable multi-platform digest.
- `SHA256SUMS`: file checksums; `SECURITY_REVIEW.md`: known risks and boundaries.

Image: `ghcr.io/masadams/otp-pager-relay:0.7.0` (Linux amd64 and arm64).
Version tags are only created from an already tested image. The release publisher
rejects conflicting existing tags and checks both platform manifests. `edge` remains
a moving development tag; use the version or digest for a deployed release.
Publishing a release does not automatically redeploy an owner's private server.

## Update an existing Portainer stack

Use the EXISTING stack name, token, named data volume and proxy network. Creating a
second stack with another name can create an empty data volume. Do not delete the
volume or restore an old snapshot as part of a normal update.

1. Set the stack Git reference to `refs/heads/main` and keep compose path
   `deploy/portainer-stack.yml` from `https://github.com/masadams/otp-pager`.
2. Set `RELAY_IMAGE=ghcr.io/masadams/otp-pager-relay:0.7.0`, or the immutable reference
   from the release's `relay-image.txt`. Existing environment overrides win over
   the compose default, so replace a previously configured `edge` value explicitly.
3. Keep `RELAY_TOKEN` and `PROXY_NETWORK` unchanged. Use **Pull and redeploy**, with
   **Re-pull image** enabled. The relay keeps its existing protocol/storage format.
4. Verify the container is healthy and `https://YOUR_HOST/health` returns
   `{"status":"ok"}`. Test message delivery in both directions using the paired
   clients. Health alone does not prove authenticated access or writable storage.
5. Reload Nginx if it cached the old upstream container address after recreation.

Rollback uses the previous image digest with the SAME named volume. Do not downgrade
the APK or roll back client vault/ledger snapshots. Ordinary server restart/upgrade
preserves stored ciphertext, cursor sequence and storage epoch.

## First Portainer installation

Docker Standalone, one replica, one local named volume. Swarm/shared NFS storage is
not supported. No registry login or local image build is needed for the public image.

| Portainer setting | Value |
| --- | --- |
| Stack name | `otp-pager` |
| Repository URL | `https://github.com/masadams/otp-pager` |
| Repository reference | `refs/heads/main` |
| Compose path | `deploy/portainer-stack.yml` |
| Git authentication | Off for this public repository |

| Environment variable | Value |
| --- | --- |
| `RELAY_TOKEN` | Private random URL-safe token, 43–128 characters |
| `PROXY_NETWORK` | Existing bridge network shared with Nginx; default `proxy` |
| `RELAY_IMAGE` | `ghcr.io/masadams/otp-pager-relay:0.7.0`, or release digest |

Generate the token locally with a trusted password manager or:

```sh
openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
```

Keep it different from the phones' pairing secret. Do not store it in Git, URLs,
screenshots or logs. Docker/Portainer administrators can inspect container environment.
The current token is operator-wide rather than a per-friend access capability.

The stack publishes no host port. Nginx reaches `otp-pager-relay:8080` across the
shared proxy network. Keep Portainer/admin interfaces separately protected.

## Nginx / Nginx Proxy Manager

In NPM create a Proxy Host with your HTTPS hostname, upstream scheme `http`, forwarding
hostname `otp-pager-relay`, port `8080`. Enable a trusted TLS certificate and Force SSL.
WebSockets are unnecessary. Additional Basic Auth conflicts with the APK bearer token.

For ordinary Nginx adapt `deploy/nginx.conf.example`. Put its rate-limit zones in the
`http {}` context. NPM needs the optional `deploy/npm-*.conf.example` snippets for
similar limits; a basic proxy host alone does not install them. Test configuration
with `nginx -t` before reloading. Do not blindly trust public X-Forwarded-For headers.
Avoid access logs containing mailbox URIs, authorization headers or request bodies.
The APK rejects HTTP, redirects and untrusted/self-signed certificates.

## Android setup

Install the SAME release APK on both devices. It is a debug-signed field build;
owner-controlled stable release signing is still unfinished. If an incompatible
signature requires uninstalling an older debug app, create fresh pads/secrets on
both devices afterward. Never restore an operational pad after uninstalling.

For each friend, tap the chat name → New chat and give it a name. Save server address
and token on at least one unused station, open **Pair devices · USB cable** on both,
connect a trusted data cable, approve USB prompts and compare the complete confirmation
code on both physical screens. This creates a fresh secret/pad and exchanges device
identities. Operational pairing material is not cloned between chats. Up to 12
separate two-device chats are supported; group chats are not implemented.

After successful pairing, setup tabs hide. Open the chat picker → **Chat settings**
to reach Link, Pad and Device. The QR/file fallback is documented in
[QR_PAIRING.md](QR_PAIRING.md). Sync is foreground-only: the selected chat plus one
inactive chat are checked each cycle; inactive chats rotate.

## Checks and operation

- Authenticated mailbox traffic, not only `/health`, must work after deployment.
- Unauthenticated mailbox requests return 401. There is no public mailbox-list API.
- Named volume `relay-data` retains ciphertext, sequence numbers and storage epoch.
  A replacement volume triggers client recovery but cannot restore expired content.
- Relay/client retention is up to 24 hours; logical expiry is not forensic erasure
  of disks, flash, backups or captured packets.
- Limits: 200 live packets per mailbox, 2,000 globally, 50 per response page, 16 KiB
  request bodies and 12,000 encoded characters per packet. Full capacity returns 429.
- Corrupt or unwritable state fails closed. Non-root user, read-only root filesystem,
  dropped capabilities, health checks and bounded logs are configured in the stack.
- `docker compose down` preserves volumes; `down -v` destroys them. Preserve data
  through ordinary updates. Use exactly one relay process per volume.
- The source examples do not establish what ports or admin pages the owner's actual
  server exposes; that requires access to the running deployment.

## Optional host build / private networking

`compose.yaml` supports a local build and binds `127.0.0.1:8080` by default. Supply
`RELAY_TOKEN`, then use `docker compose up -d --build`. Host Nginx forwards to that
loopback address. Container Nginx instead needs the shared-network Portainer stack.

An external overlay can restrict access to Nginx while retaining HTTPS and message
encryption. Built-in WireGuard/ZeroTier/Nebula is not implemented in this release.
See [PRIVATE_NETWORK.md](PRIVATE_NETWORK.md) for the proposed integration.
