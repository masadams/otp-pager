# v0.14 security inventory and initial threat model

Baseline: `cfe01af255bebbd195337b9d1b0c72017e39f7c1`, reviewed 2026-10-26.
Scope: targeted first-party source review, not an independent audit, a complete
cryptographic proof, live-host inspection or physical-device test.
Requirements/status: [plan](SECURITY_DEVELOPMENT_PLAN.md), [progress](SECURITY_PROGRESS.md).
Paths below refer to this baseline; update evidence when implementation changes.

## Assets and persistent copies

Android source prefix: `app/src/main/java/org/example/otppager/`.

| Location / owner | Contents and protection | Lifecycle / limits |
| --- | --- | --- |
| `SecureStore.kt`, `noBackupFilesDir/<namespace>.bin` | AES-256-GCM snapshot under Android Keystore alias `otp-pager-<namespace>` | AtomicFile `.bak`/`.new` variants; file and directory sync; same key protects successive snapshots; missing file with surviving key fails closed |
| Contact snapshot, `StationEngine.kt` | Base64 directional pad, pairing secret, setup token, endpoint, public identities, counters/replay/cursors, inbox/outbox, opened-payload cache, migrated history, readiness/recovery evidence | Plaintext inside encrypted vault; JSON/String copies in process; logical 24h message filtering/pruning; readiness packet and accepted hashes can outlive visible history |
| `<contact>-draft`, `ChatDraft.kt` | Independent encrypted text, room, draft ID/time; own Keystore alias | 32 KiB bound, 24h check on read; clear destroys draft key; not a scheduled powered-off purge |
| `<contact>-pads`, `PadLedger.kt` | Pad IDs, capacities, outgoing high-water reservations | Encrypted sibling store; intentionally survives Forget; no pad bytes; coordinated snapshot rollback/uninstall defeats local-only guard |
| `chat-index`, `ChatBook.kt` | Names, local IDs, active selection | Encrypted index; Forget pairing keeps contact entry/name |
| `chat-index-claims`, `PairingClaims` in `ChatBook.kt` | Pad IDs, derived mailbox IDs and local owners | Encrypted registry intentionally survives Forget; metadata retained to block reuse |
| `DeviceIdentity.kt` | Per-contact P-256 private signing key in Keystore, public card in vault | `forget()` deletes alias; actual hardware backing/device behavior needs verification |
| SharedPreferences, `ReceiveService.kt` / `NetworkActivity.kt` | Receiving preference and selected pad size | App-private settings, outside contact vault; not message/pad secrets |
| `NetworkActivity.kt` document export/import | Plaintext original pad bundle in user-selected document provider; public identity card files; imported QR image | Export is outside vault control; remote/cloud document provider is technically selectable despite warning; app cannot erase external copies |
| USB/NFC/Wi-Fi provisioning | Secrets, full pad and JSON setup in process, ephemeral key objects/buffers; staged renewal snapshot | `CableProtocol.kt`, `CableSession.kt`, `RadioPairActivity.kt`, `RadioHandoff.kt`, `NfcSetupActivity.kt`; encrypted transfer does not guarantee memory erasure or quantum-resistant provisioning |
| UI / notifications / OS | Composer, rendered text, cached views; generic message notification, receiving count/status | `WindowPrivacy.kt`, private dialogs, no view-state persistence; keyboards/accessibility/root/camera/e-ink remain outside guarantee |
| `relay/server.mjs`, `/data/mailboxes.json` | Ciphertext, room, global sequence, time, epoch, hashed capability/revoked flag | Atomic fsynced whole-state replacement via `.tmp`; message TTL default 24h, minute cleanup plus requests; claim/revocation records permanent within this volume |
| Relay process/config | Setup token, live bearer credentials, packet/request buffers | Token env or `RELAY_TOKEN_FILE`; no first-party request/body exception logging; host/proxy/container tooling may expose additional data |
| Deployment / backup / updates | Persistent volume, proxy logs, snapshots, APK/signer artifacts | Host copies not inventoried remotely; Docker source pins deployment image; Dockerfile base pinned by digest since 2026-10-28 (Dependabot updates); owner signer custody not validated here |

No photo/attachment send path was found in the reviewed messaging paths. QR-image
import is setup, not encrypted photo messaging. Future attachment storage is not covered.

## Message and deletion flow

1. UI composer may persist a per-contact encrypted draft. Normal send UI requires
   pad setup, but the engine still contains a legacy no-pad message path.
2. `StationEngine.queue()` reserves a pad slot through `PadLedger.reserve()` before
   encryption. Failed/uncertain reservations burn capacity. Snapshot commit precedes
   publication; retry retains the exact packet rather than encrypting anew.
3. `retireUsed()` caches retained opened payloads inside the vault and clears used
   outgoing/accepted incoming pad slots. Out-of-order unaccepted slots remain available.
4. HTTPS relay claim/access and packet POST/GET transport ciphertext. Relay stores
   after file/directory fsync, deduplicates identical packets and rechecks authorization
   after asynchronous body reads. No delivered-packet delete/ack protocol exists.
5. Recipient validates/decrypts and checks device binding/payload before accepting
   traffic and updating replay state. Receipts mean saved on device, not read by a person.
6. `prune()` filters history/inbox/final outbox by 24h and bounds; transaction retirement
   rebuilds opened cache. `clearHistory()` preserves queued packets and accounting.
   Turning off or force-stopping the app leaves encrypted files until later execution.
7. `revokeRelayAccess()` uses the chat capability to DELETE the relay mailbox and then
   marks local relay revocation. It does not erase either endpoint. A timeout can leave
   local knowledge uncertain even if the relay performed the operation.
8. `ChatBook.forgetActive()` clears draft then contact store. `SecureStore.forget()`
   deletes identity, vault key and files sequentially. Ledger/claims/name remain.
   There is no durable emergency marker, peer command, dispatch record or wipe-all path.

## Threat model

Protect against passive network capture, unauthorized mailbox users, malformed or
replayed traffic, accidental cross-contact use, crashes/interrupted writes and a
relay that reads storage or delays/drops/reorders packets. Computational provisioning,
authentication, TLS and local encryption are explicit dependencies.

A compromised OS/keyboard, malicious peer, copied original pad, coordinated device
rollback/cloning, malicious update or restored server revocation state can defeat
parts of the intended protections. A valid peer acknowledgement cannot attest erasure
on an untrusted device. Physical proximity alone is not peer authentication: compare
the full codes. Authentication and confidentiality claims must be reviewed separately.

Trust boundaries: human physical ceremony; entropy source; Android process/Keystore/
filesystem; UI/background/pairing workers; TLS/proxy; relay authorization/storage;
Docker/Portainer host; backup/restore; signer/build/update chain.

## Prioritized findings

Severity here is development priority relative to the new requirements, not a claim
of a remotely exploitable vulnerability. IDs persist across implementation PRs.

| ID | Priority / observed gap | Source and next action |
| --- | --- | --- |
| F01 | Critical gate: no emergency destruction protocol or durable wipe transaction | `ChatBook.forgetActive`, `SecureStore.forget`, `StationRuntime.worker`; specify local terminal gate, cancellation/stale-object handling, restart recovery and best-effort all-contact continuation (S05/S07/S12) |
| F02 | High: engine still permits no-pad conventional message encryption | `StationEngine.sendText/queue/open`, `Protocol.kt`; UI guards reduce exposure, but OTP-only policy is not an engine invariant; design explicit legacy-read migration and prohibit new conventional message sends without breaking retained packet recovery (S04) |
| F03 | High: logical retirement/history cleanup leaves older snapshots decryptable under surviving vault keys | `SecureStore`, `StationEngine.retireUsed/clearHistory/stageRenewal`; retained plaintext caches/history intentional; specify erasable history/pad key boundaries and crash-safe rekey before claiming stronger deletion (S03/S09) |
| F04 | High: deletion can stop between draft, identity, vault key and files; no persistent completion record | `ChatBook.forgetActive`, `SecureStore.forget`; fault-test each boundary, deletion exceptions and restart; do not label Forget an emergency wipe (S05/S12) |
| F05 | High: relay revocation blocks both peers' ordinary access and has no independent control channel | `revokeRelayAccess`, `ReceiveCycle`, relay DELETE/410 path; specify durable independent control retrieval and final acknowledgement before reusing revocation for wipe (S06/S10/S11) |
| F06 | High: one capability is shared by both devices of a contact; bootstrap remains global | `Protocol.relayCapability`, `CHAT_RELAY_ACCESS.md`, relay claims; independently scope device roles/operations and define migration, capacity and compromised-credential tests (S08) |
| F07 | High: device/server rollback can restore state and undo local-only/server revocation knowledge | `PadLedger`, `PairingClaims`, `RelayClient.scope`, server ENOENT/epoch/claim recovery; define supported recovery and refusal policy; no arbitrary tombstone expiry or anti-rollback claim (S03/S07) |
| F08 | High: exported original pads/QR credentials and managed-memory copies escape local key erasure | `NetworkActivity` document export/import, `PairingInvite`, `CableProtocol`, `StationEngine`; inventory user-held exports and threat boundaries; no erase-all-copies claim (S01/S09) |
| F09 | Medium: 24h is logical/app-execution-based local retention, not guaranteed deletion at deadline | `ChatDraft.read`, `StationEngine.prune/view`; review stopped-device and clock rollback behavior; queued expiry and cached readiness need explicit policy (S09/S10) |
| F10 | Medium: relay retains delivered ciphertext until TTL and tombstones until capacity exhaustion | `relay/server.mjs`; preserve lost-receipt recovery while minimizing retention; design bounded control persistence without evicting revocations (S07/S09) |
| F11 | High readiness gate: live host, firmware, independent crypto/entropy and signer custody unverified | Deployment examples are hardened in part, not host evidence; inspect logging/backups/proxy access and supply chain separately; physical two-device testing and external review remain mandatory |
| F12 | Medium: current documentation mixes historical and current versions | README v0.10 / old roadmap / security review heading; link current plan and baseline, retain historical documents with explicit context |

## Existing evidence and missing verification

Relevant existing test sources: `StorageTest`, `DeliveryTest`, `ChatBookTest`,
`DraftTest`, `RelayAccessTest`, `RenewalTest`, `RadioPairTest`, `CablePairTest`,
`UpgradeStateTest`; JVM protocol/vector suites; `relay/test.mjs`. Their presence
does not prove completion of new requirements. See progress for tests actually run.

Missing: emergency-control vectors and protocol review; per-boundary destruction
fault tests; stale worker/engine resurrection tests; all-contact partial failure;
offline surviving dispatch; independently verified peer acknowledgements; hostile
relay two-device matrix; real power-loss, firmware/Keystore deletion behavior,
backups and live server configuration. Phase 1 remains partial until these audit
boundaries and the broader code/protocol coverage are reviewed.


## Expanded review and first implementation follow-up

The following review expands coverage but is not an independent protocol assessment:

| Area | Source observations / boundary | Evidence source |
| --- | --- | --- |
| Message authentication | Pad HMAC verified before plaintext decoding; device signature transcript includes room, pad ID, role, counter, both identities and body; binding rejects unsigned traffic once active | `PadProtocol.open`, `IdentityProtocol.transcript/body`, `StationEngine.deviceBody/sync`; `PadProtocolTest`, `DeviceIdentityTest` |
| USB | Both approvals precede secret setup; ephemeral ECDH/HKDF directional AES-GCM binds transcript, role and sequence; failure poisons session; staged/partial completion requires explicit recovery | `CableProtocol`, `CableSession`, `CablePairActivity`; cable vector and `CableSessionTest`/`CablePairTest` |
| NFC / radio | Setup NFC is short-lived bearer sharing, not authenticated device pairing; radio ticket is private IPv4 address/port plus secret, mutual fresh challenge and separate encrypted channel containing signed/code-compared pairing | `NfcSetupActivity`, `RadioHandoff`, `RadioPairActivity`; `RadioHandoffTest`, `RadioPairTest`, `ReceiveTest` |
| Persistent state | First-party direct file writes found in SecureStore; other persistent Android writes use Keystore, receiving/pad-size preferences or explicit document providers | Source search across main Kotlin for file/preferences/key and import/export APIs; OS/dependency stores not exhaustively inspected |
| Lifetime/concurrency | Single production worker includes networking/provisioning; local wipe must not wait behind it; stale engine/draft/key creation must be gated at storage boundaries | `StationRuntime`, `NetworkActivity.runTask`, `ReceiveService`, `CablePairActivity`, `RadioPairActivity`; local destruction design draft |

First implementation closes the new-text entry path identified in F02: no-pad
`send`/`sendDraft` and text queueing fail, and `View.canSend` no longer enables
legacy sending. Existing conventional packet decode, exact retry and receipt
compatibility remain explicitly supported; no entire-lifecycle OTP-only claim.
`OtpOnlyEngineTest` covers no-pad rejection across restart without changing durable
message/draft state. Older recovery tests now load test-only pre-upgrade snapshots
instead of manufacturing new AES texts through the production engine.

Remaining audit limits include dependency/OS stores, exhaustive malformed protocol
and cancellation interleavings, independent crypto review, host/signing custody,
physical devices and real power-loss/rollback. F01/F03–F11 are not resolved by the
engine guard or the local lifecycle design document.
