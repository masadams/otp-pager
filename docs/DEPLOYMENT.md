# Deploy OTP Pager 0.18

The server is an opaque mailbox relay in Docker; the client is the Android APK. The relay never
receives pad material, pairing secrets or private device keys. This is an experimental field
release, not independently audited; see the [security review](SECURITY_REVIEW.md).

## Current release: 0.18.0

- **Phones:** install `otp-pager-0.18.0.apk` from the GitHub release on **both** phones. It
  updates 0.15–0.17 in place (same signing key) and keeps chats and pairings. New pairings and
  renewals need 0.18 on both phones (pairing protocol v11: two-phone pad generation and
  one-time MAC).
- **Relay:** the stack's default image is pinned by digest in `deploy/portainer-stack.yml`
  (the 0.17.0 relay, also tagged `ghcr.io/masadams/otp-pager-relay:0.17.0`). Linux amd64/arm64.
  0.18 has no relay changes: if you already run the 0.17.0 relay, there's nothing to redeploy.
- Update the relay first or the phones first; either order works. With an older relay, a lapsed
  strict chat shows as "unavailable" instead of being retired.

## Update an existing Portainer stack

Use the EXISTING stack name, token, named data volume and proxy network. Creating a second stack
with another name creates an empty data volume. Never delete the volume or restore an old snapshot
as part of an update.

1. **Stacks → otp-pager → Editor** (Git-based stack). Check:
   - Repository URL `https://github.com/masadams/otp-pager`;
   - Repository reference `refs/heads/main`;
   - Compose path `deploy/portainer-stack.yml`.
2. **Environment variables:**
   - remove any `RELAY_IMAGE` override (for example an old `0.7.0`, `edge` or digest value), so
     the pinned default is used. Overrides win over the compose default.
   - keep `RELAY_TOKEN`, `PROXY_NETWORK` and `RELAY_STRICT_CLAIMS` unchanged.
3. Click **Pull and redeploy**, with **Re-pull image and redeploy** switched on.
4. Check:
   - the container is running and healthy (**Containers → otp-pager-relay-1**);
   - `https://YOUR_HOST/health` returns `{"status":"ok"}`;
   - on a phone, **Friend details → Test server connection** shows every line as ✓ (or "!" for
     post-quantum TLS until the proxy step below);
   - a message and a reply go through in both directions.
5. If Nginx Proxy Manager then answers 502, it cached the old container address: open the proxy
   host and click **Save** without changes (this reloads it), or restart the NPM container.

The data volume keeps ciphertext, cursors, the storage epoch, access records, the strict-chat
authority key and, from 0.17, permanent tombstones for retired strict-chat shares.

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

## Nginx Proxy Manager

### Proxy host (first installation)

**Hosts → Proxy Hosts → Add Proxy Host:**

| Tab | Setting | Value |
|---|---|---|
| Details | Domain Names | your relay hostname, e.g. `pager.example.com` |
| Details | Scheme / Forward Hostname / Port | `http` / `otp-pager-relay` / `8080` |
| Details | Block Common Exploits | on |
| Details | Websockets Support | off (not used) |
| SSL | SSL Certificate | a trusted certificate (Let's Encrypt) |
| SSL | Force SSL, HTTP/2, HSTS | on |
| Access | Access List | none (Basic Auth breaks the app's bearer token) |

NPM and the relay must share the Docker network named in `PROXY_NETWORK` (default `proxy`).
The app refuses plain HTTP, redirects and self-signed certificates.

### Rate limits (recommended)

1. In the NPM data folder, append `deploy/npm-http-top.conf.example` to
   `/data/nginx/custom/http_top.conf` (create the file if it doesn't exist).
2. Paste `deploy/npm-proxy-advanced.conf.example` into this proxy host's **Advanced** tab →
   Custom Nginx Configuration. **Save**.

### Post-quantum TLS (0.17, optional)

The app now offers the hybrid post-quantum key exchange `X25519MLKEM768` on every relay
connection. Your proxy uses it only if its OpenSSL is 3.5 or newer:

1. Check NPM's OpenSSL: `docker exec <npm-container> openssl version` (or Portainer →
   Containers → NPM → Console → `openssl version`).
2. **3.5 or newer:** add this line to the proxy host's **Advanced** tab (below the rate-limit
   lines) and **Save**:
   ```nginx
   ssl_ecdh_curve X25519MLKEM768:X25519:prime256v1;
   ```
   If NPM reports the host as offline afterwards, remove the line again. That means its Nginx
   doesn't support the group.
3. **Older than 3.5:** don't add the line, because Nginx refuses to start with an unknown group.
   Wait for an NPM image built on OpenSSL 3.5+. Messages stay OTP-protected either way; this only
   protects tokens and metadata in transit.
4. Verify on a phone: **Friend details → Test server connection**. The "Post-quantum TLS" line
   shows ✓ when the proxy accepts the hybrid group.
5. **From 0.19, keep it on.** Once a phone has seen the server accept the hybrid group (that
   test, or its own daily check), it offers only that group to it, so nobody on the network can
   force classical TLS. If you later remove the line or move to an older proxy, those phones show
   "Refused: this server accepted post-quantum … before" and stop connecting. Either put the line
   back, or run **Test server connection** on each phone and tap **Allow classical TLS**.

### Plain Nginx

Adapt `deploy/nginx.conf.example` (rate-limit zones go in the `http {}` context, the same
`ssl_ecdh_curve` rule applies). Test with `nginx -t` before reloading. Don't trust public
X-Forwarded-For headers, and avoid access logs containing mailbox URIs, authorization headers or
bodies.

## Android setup

Install the same signed release APK on both devices.
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


## Relay storage files (2026-10-28)

The data volume holds:
- `mailboxes.json`: snapshot;
- `journal.log`: recent posts, expiries and strict-chat check-ins, one fsynced line each, folded
  into the snapshot every 256 changes;
- `authority-key.pem`: the strict-chat unlock authority key.

Back up or move all three together. A `journal.log` from a different snapshot is ignored by
design. Never copy `authority-key.pem` between relays, because each relay is its own unlock
authority.
