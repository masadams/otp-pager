# Per-chat relay access (v0.10 test)

Update **both Android apps first**, then update the relay. An old app using the shared token cannot access a mailbox after a new app protects it. The new app can use the old relay temporarily and displays shared access in its status details; this is not presented as protected chat access.

## Portainer test deployment

The chat-access changes are merged into `main`. The default Portainer stack pins this tested multi-platform image:

```text
ghcr.io/masadams/otp-pager-relay@sha256:7e90cc527e1150e448b70c36bac1bb33ce4f7131a56dd4d367fda2480df85c51
```

Keep the existing stack name, token, Nginx configuration and `relay-data` volume. If `RELAY_IMAGE` is already set in Portainer, update that override to the pinned image above; otherwise the updated stack default applies. Pull/redeploy without removing or recreating the volume. Main builds also publish `edge` and commit tags after relay/container checks; prefer a verified digest for deployment. The APK remains a temporary-signer test build; this deployment pin does not declare a production Android release.

Open the app and Sync on both devices. Tap the status strip, then **Chat access**. It should report **CHAT ACCESS PROTECTED**. No extra pairing code, QR, pad slot or device signature exchange is needed. Existing pairings and queued ciphertext remain in place.

## Authorization

`capability = Base64url(SHA256("OTP Pager / relay capability v1" || master))` (no padding). This is a separate computational authorization credential derived from the 32-byte pairing secret, not pad material or an encryption key. The existing mailbox ID and encrypted packet format are unchanged. Claims are sent only over the configured HTTPS connection.

The first client uses the setup token to `POST /v1/mailbox/<room>/claim` with `{capability}`. A repeated matching claim is idempotent; a different credential cannot replace it. The server persists only its SHA-256 hash, a revocation flag and the existing opaque mailbox metadata/ciphertext. GET/POST for that protected room require its capability: the global setup token and another chat's capability are rejected. The shared token still permits creating claims and accessing legacy unclaimed mailboxes; this is a staged migration, not elimination of all operator-wide provisioning authority. A leaked setup token can still claim known *unprotected* rooms or consume bounded provisioning capacity. Protect every active chat, rotate a leaked setup token and control operator access.

The client saves successful protection before traffic. A protected chat never falls back to the setup token for message GET/POST. If the relay volume is replaced, the client may recreate the same claim using the setup token, then requests traffic only with its chat capability; a legacy relay or failed claim cannot trigger downgrade. Preserving the original volume is the supported deployment path. Losing it also loses revocation records, ciphertext and epoch; clients that never learned a previous revocation cannot reconstruct that lost server history. Backups may likewise roll back revocations. Server administrators remain trusted for access control, not message confidentiality.

## Revocation

Status → Chat access → Revoke this chat → confirm. This sends DELETE using the chat credential, removes its server packets and saves a permanent revocation tombstone. **Both devices** lose relay access for this pairing. Local history and pad reservations remain. A new physical pairing with a new secret/pad is required to continue. This is per-chat revocation, not separate credentials per device and not erasure of downloaded copies or forensic disk cleanup.

Revoked claims are not removed on message expiry. The default limit is 1,000 total protected/revoked claims per relay; exhaustion refuses new claims, never evicts old protections. Claims and revocations are atomic and fsynced with mailbox state. An in-flight upload rechecks access after reading its body, so concurrent protection/revocation cannot be bypassed. Protected state uses disk schema 2: older relays reject it instead of silently reopening shared access. Do not roll back the relay or restore old state as an access-recovery shortcut.

## Interface meanings

- **Checking relay**: a request is in progress.
- **Relay checked**: this app completed a recent authenticated relay sync, not proof the peer is online.
- **Queued**: encrypted packet saved locally, waiting to be uploaded.
- **At relay**: relay acknowledged storage; peer confirmation is still pending.
- **Peer saved**: the other device accepted/stored the message, not a human read receipt.

Foreground delivery, signature verification, finite pads and existing retention limits are unchanged. Full server compromise can observe request credentials/metadata, disrupt delivery or alter its access policy; message contents still depend on the endpoint/pad protections. This has not received an independent security audit.
