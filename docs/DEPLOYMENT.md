# Deploy OTP Pager v0.10

The server is an opaque mailbox relay in Docker. The client is the Android APK.
The normal client requires physical pad pairing and supports pinned device identity.
The relay does not receive pad material, pairing secrets or private device keys.
This is an experimental field release; see [security review](SECURITY_REVIEW.md).

## Current merged build: v0.10

The client changes and relay access protection are merged into `main`. Use the privately
provided **signed v0.10 test APK on both devices**, then update the relay. Ordinary CI
debug APKs are not compatible updates to that signed installation. See
[Android updates](ANDROID_UPDATES.md) for signer custody and the first debug-to-signed transition.

The default stack pins the already tested Linux amd64/arm64 relay:

```text
ghcr.io/masadams/otp-pager-relay@sha256:7e90cc527e1150e448b70c36bac1bb33ce4f7131a56dd4d367fda2480df85c51
```

This is a development field build, with temporary Android signing and no independent
audit. A separately published production-signed Android release is still pending.
The [v0.7 release](https://github.com/masadams/otp-pager/releases/tag/v0.7.0) is historical;
its debug APK and old stack are not the current v0.10 installation path. `edge` is a
moving main-build tag; use a verified digest for deployment. Publishing an image or
merging source does not redeploy the owner's private server.

## Update an existing Portainer stack

Use the EXISTING stack name, token, named data volume and proxy network. Creating a
second stack with another name can create an empty data volume. Do not delete the
volume or restore an old snapshot as part of a normal update.

1. Set the stack Git reference to `refs/heads/main` and keep compose path
   `deploy/portainer-stack.yml` from `https://github.com/masadams/otp-pager`.
2. Set `RELAY_IMAGE` to the tested digest above, or remove an old override to use
   the current compose default. Environment overrides win over the compose default,
   so a previously configured `0.7.0` or `edge` value must be updated explicitly.
3. Keep `RELAY_TOKEN` and `PROXY_NETWORK` unchanged. Use **Pull and redeploy**, with
   **Re-pull image** enabled. Mailbox traffic keeps its protocol; access claims
   migrate the server disk format to schema 2 while preserving ciphertext/cursors.
4. Verify the container is healthy and `https://YOUR_HOST/health` returns
   `{"status":"ok"}`. Test message delivery in both directions using the paired
   clients. Health alone does not prove authenticated access or writable storage.
5. Reload Nginx if it cached the old upstream container address after recreation.

After the first protected chat, use a relay compatible with disk schema 2. Older
relays reject protected state: do not roll back to 0.7 or restore old snapshots to
bypass that protection. Do not downgrade the APK or restore vault/ledger snapshots.
Ordinary restart/upgrade preserves ciphertext, cursors, epoch and access records.

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
| `RELAY_IMAGE` | Leave unset for the current pinned stack default, or use the tested digest above |
| `RELAY_STRICT_CLAIMS` | `0` (default) or `1`: the setup token only creates chat claims; see [chat access](CHAT_RELAY_ACCESS.md#strict-claims-optional-recommended-once-migrated). Needs a relay image built from this change |

Generate the token locally with a trusted password manager or:

```sh
openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n'
```

Keep it different from the phones' pairing secret. Do not store it in Git, URLs,
screenshots or logs. Docker/Portainer administrators can inspect container environment.
The token provisions chat access and still accesses legacy unclaimed mailboxes.
Protected chats require separate automatically derived capabilities. See
[chat access](CHAT_RELAY_ACCESS.md) for staged migration and revocation limits.

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

Install the SAME signed v0.10 test APK on both devices before upgrading the relay.
Update a previous same-signer test installation in place, retaining app data. Moving
from historical debug APKs or later to a different production signer requires a
deliberate fresh-install/fresh-pairing transition; see [Android updates](ANDROID_UPDATES.md).
Never restore an operational pad after uninstalling.

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
- Named volume `relay-data` retains ciphertext, sequence numbers, storage epoch and
  access/revocation records. A replacement volume loses that server history; automatic
  claim/traffic recovery cannot reconstruct forgotten revocations or expired content.
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
