# Security, data lifecycle and emergency destruction development plan

For the current review graph, exact source heads, execution evidence and next steps,
read [the continuation record](CONTINUATION_2026_10_28.md).

Status: active development reference; security features below are requirements, not release claims.
Owner direction: 2026-10-26. Baseline: Android v0.14, main commit
`cfe01af255bebbd195337b9d1b0c72017e39f7c1`.

For a checked task list in build order, see [the security checklist](SECURITY_CHECKLIST.md).
For continuation without chat history, read [the developer handoff](DEVELOPER_HANDOFF.md).

## Current delivery status — 2026-10-28

Verified baseline: main `9a968a8750657b438524902dbdac98429b080b46`.
Local/global retirement, ACTIVE epochs/fresh enrollment and vault-key rotation are
merged development functionality. #38 adds separate pad storage, #39 streamed large
pads, and #40 peer wipe behind an off-by-default unreviewed setting. PRs #41–44
remain open; the handoff records branches and dependencies. Strict chats, failover
and groups are not implemented. No new physical, independent-review or release
readiness evidence is implied by this status reconciliation.

[PIPELINE.md](PIPELINE.md) supersedes older ordering and review-gate statements below:
the owner moved independent peer-wipe review to the final community review package;
the unreviewed label and default-off setting remain required. Per-device ordinary
relay credentials were considered and dropped. Pairwise fan-out groups are now in
scope; shared multi-party pads remain prohibited.

Continue USB regression tests first, then strict protocol/relay/storage/UI, failover
and groups. Strict v1 uses one authority, no inactivity share deletion and fresh
physical enrollment. A bounded foreground lease must protect actual decryption,
including drafts and caches; control processing and offline local wipe must remain
available while content is locked. Transport failover cannot bypass strict revocation.
All follow-up PRs stay unmerged and record tests, limitations and hardware gates.

## Historical delivery status — 2026-10-27

> 2026-10-28: owner answers and the ordered pipeline are in [PIPELINE.md](PIPELINE.md); it
> takes precedence over the package list below where they differ.

PRs #9–15 are merged. Phase 1's initial source audit and Phase 2's local retirement,
global cleanup/recovery, authenticated initial evidence and empty storage staging
are implemented/tested in their documented scope. Phase 2 remains open: no ACTIVE
epoch, repeat-wipe lineage, global fresh enrollment or history-key migration yet.
Phases 3–8 remain outstanding. Remote wipe and wipe-all controls are not
implemented; no production-readiness decision or live deployment follows from merging.

2026-10-27 order review: package A2, a protected **local-only** per-conversation wipe
control, is pulled forward ahead of the rest of package B. The retirement primitive and
its recovery/fresh-pairing screen were complete but unreachable, while the only
user-facing destructive action (Forget) is the non-durable path recorded in F04. A2
reuses merged code, needs no ACTIVE epoch and keeps reuse guards; see
[the A2 design](LOCAL_WIPE_CONTROL.md). Wipe-all initiation stays deferred until fresh
enrollment exists, because the only current exit from a global freeze (clearing app
storage) also removes pad-reuse protection. Recommended in parallel with package B:
draft the package D peer-wipe specification early, because independent review is the
long-lead gate for any peer destruction.

The handoff contains exact merged commits, final-head CI evidence, source entry
points, test commands, known limitations and ordered continuation criteria. The
checklist is the current task state; historical log entries retain their original
status for traceability.


Start here on every continuation: read this plan, [progress](SECURITY_PROGRESS.md),
and the [baseline inventory and findings](SECURITY_INVENTORY_V014.md). Record the
next task, decisions, evidence and unresolved risks before ending a work session.
The older [roadmap](ROADMAP.md) retains historical context; this plan controls the
security/data-lifecycle work when those documents differ.

## Objective and philosophy

The server transports messages. Devices own the secrets. Users control their data.
Build a small, physically paired network with independently protected and
independently destroyable relationships. Do not optimize for unrestricted remote
enrollment, group chat, unlimited attachments or mainstream-messenger convenience.

1. OTP-only new message confidentiality: independent, uniformly random, secret,
   directional pad bytes, durably reserved and never reused. No automatic downgrade.
2. The relay receives no operational pads, plaintext messages or message-decryption keys.
3. Physical pairing deliberately authenticates the devices and protects provisioning.
4. Each relationship isolates pads, identity, credentials, history and revocation.
5. Minimize content, metadata, temporary files, logs and retention.
6. Prefer destruction of relevant encryption keys over database/file deletion alone.
7. Storage uncertainty, crashes and exhausted pads fail closed.
8. Security indicators describe evidence, not assumptions.
9. Everyday messaging stays simple; diagnostics and cryptographic maintenance are secondary.
10. Small inspectable protocols, established primitives, reproducible vectors and independent review.

Android SecureRandom pads, HMAC, P-256 signatures, AES local storage and ECDH
provisioning do not establish an entirely information-theoretic or unconditionally
quantum-proof system. Record each dependency and its compromise consequences.
Local key deletion cannot guarantee erasure from compromised OSes, memory, flash,
snapshots, exported original pads, screenshots or residual e-ink images.

## Architecture and responsibilities

Devices generate/receive physically exchanged material, maintain separate directional
pools, authenticate before processing, enforce durable allocation, retain encrypted
contact-isolated state, retire consumed material and enforce local lifecycle rules.
The recipient authenticates destructive commands independently of server authorization.

The relay authenticates scoped access, queues opaque packets with bounded retention,
preserves idempotent recovery, delivers control traffic and retains minimal terminal
revocation records. It cannot authorize peer destruction or manufacture a valid wipe.
Administrators can still observe traffic metadata, delay/drop packets and restore
old server state; confidentiality and availability guarantees must distinguish these.

## Data lifecycle requirements

- Inventory unused/consumed pads, authentication secrets, identities, history, drafts,
  queues, receipt caches, counters, pairing staging, exports, logs and backups.
- Reserve durably before encryption; retries use exact retained ciphertext.
- Interrupted reservations remain burned. Missing/corrupt ledgers never reset silently.
- Keep reuse/retirement guards independent of erasable content; minimize their metadata.
- Separate erasable history/draft keys from operational pad keys where the reviewed
  design requires it. Review older snapshots under surviving keys and rekey failure.
- Define logical expiry separately from physical removal and cryptographic erasure.
  A powered-off app cannot execute scheduled cleanup; wall-clock rollback is a test case.
- No attachment implementation is authorized by this milestone. Future photographs
  require a separate format, pad budget, authentication, bounds and temporary-file review.
- Restoring coordinated old device state remains a documented rollback limitation
  unless a reviewed mechanism demonstrably prevents it. Never restore operational pads.

## Emergency operations

### Wipe current conversation

Target one immutable pairing generation, never a display name alone. Disable use of
that generation and prepare a fresh authenticated, replay-resistant command with
independent emergency-control material. If possible, durably preserve its sealed
dispatch record. Local destruction proceeds without waiting for network activity,
relay acknowledgement or remote confirmation. Preparation/persistence failure must
not prevent the best achievable local destruction; report the remote outcome unknown.

Destroy the targeted conversation's operational keys, identity, unused pads, drafts,
history, caches and credentials according to the reviewed state machine. Resume
incomplete destruction on restart. Stop stale UI/background/pairing jobs from writing
or recreating keys. Retire the generation permanently. Other contacts remain usable.

The peer processes only a valid command bound to this pairing/generation and initiator,
then destroys its corresponding relationship. A new physical pairing uses fresh
material and generation; delayed old control packets cannot affect it.

### Wipe all conversations

Apply independent per-pairing commands and destruction. One corrupt vault or failed
recipient must not stop cleanup of the others. Wipe-all must cover orphaned contact
stores/keys and name/index metadata under a documented scope, not just a healthy index.
Recipients destroy only their relationship with the initiator, never unrelated contacts.

### Dispatch and acknowledgement semantics

Specify a minimal post-wipe outbox: immutable authenticated command, minimal routing,
restricted submit authority, request ID, expiry/retry state and acknowledgement
verification material. No recoverable message secrets or reusable command-generation
authority survive solely for retries. Retained routing/status metadata is an explicit
exception to content destruction. A separate complete local purge may remove this
outbox and sacrifice retry/status; define that behavior before UI implementation.

The reviewed protocol must resolve:

- Independent directional control keys provisioned physically, separate from normal
  pads and ordinary signatures; construction, allocation and authentication bounds.
- Canonical versioned encoding; pairing ID/generation, direction, command and request
  ID binding; reject unknown versions and ambiguous encodings before acting.
- Crash-safe terminal state transitions, duplicate/reordered packets and simultaneous wipes.
- How the peer prepares and retransmits a verifiable final acknowledgement while
  destroying its operational keys, without keeping those keys for retry.
- Control submission/retrieval/acknowledgement after ordinary credentials are revoked.
- Control retention, unavailable peers, request expiry and explicit loss of guarantees.
- Migration: existing pairings have no reserved control keys; require a reviewed
  physical upgrade/fresh pairing, never pretend support was retroactively established.

UI evidence states: local destruction incomplete/completed; request retained locally;
relay reports durable acceptance; peer receipt authenticated; peer reported wipe
complete; expired/unknown/failed. A relay can lie about acceptance. A peer report
is not proof of forensic erasure or honest behavior by a modified device.

## Relay and infrastructure requirements

- Replace shared provisioning dependence with independently scoped device/pairing
  authorization, least privilege, rotation and cross-mailbox denial tests.
- Preserve immutable ciphertext retries, original expiry and recovery compatibility.
- Bound packets, connections, queues, control traffic and permanent tombstone growth.
  Resource exhaustion must refuse work rather than silently evict protections.
- Priority authenticated control retrieval before ordinary traffic processing;
  durable acceptance and idempotent recipient behavior through restarts.
- Terminal tombstones contain no plaintext/pads; lifetime covers replay/credential
  lifetime, not a convenient short timeout. Define backup restoration and loss behavior.
- Reduce delivered-packet retention only after reviewing receipt-loss/recovery needs.
- Validate TLS, proxy timeouts/rate limits/logs, exposed ports, Portainer access,
  container privileges, secrets, volume permissions, snapshots, supply chain, pinned
  images and resource limits. Source examples do not prove live-host configuration.
- Recovery must not silently re-enable revoked relationships or reissue expired traffic.

## Phased build order and gates

| Phase | Work and required deliverable | Exit gate |
| --- | --- | --- |
| 1 — Inventory and threat model | Audit v0.14 paths, storage/retention, deletion, provisioning, relay, backups and tests; prioritized source-linked findings | Reviewed scope, known gaps and invariant/test mapping; live host/hardware limits explicit |
| 2 — Local protection | Durable local destruction state machine; separate erasable data where justified; retirement and OTP-only engine enforcement; upgrade/restart/fault tests | No reuse or resurrection in tested failures; local cleanup independent of network and stale work |
| 3 — Revocation specification | Exact control format/keys, migration, dispatch outbox, acknowledgement, terminal generations and server recovery | Protocol review, independently produced vectors, failure/abuse cases resolved before destructive protocol code |
| 4 — Relay controls and access | Scoped authorization, durable control queue/tombstones, priority delivery, essential limits/log hygiene and migration | Versioned integration tests; restart/restore/duplicate/tamper/isolation evidence |
| 5 — Android emergency wipe | Selected/all, authenticated peer action, honest status; isolated local-first destruction | Two-device connected/offline/interrupted/malicious-relay matrix, no unrelated-contact damage |
| 6 — Operational minimization | Ciphertext retention, deployment/secret/log/backup hardening and recovery exercises | Hardened configuration and deployment checklist; distinguish source checks from host evidence |
| 7 — UX completion | Quick protected destructive actions, clear pad capacity/status and optional diagnostics | Phone/compact e-ink usability validation; no false security indicators |
| 8 — Independent readiness | Cryptographic, Android, relay, USB/NFC, entropy, physical power-loss, signing and supply-chain assessments | Critical findings resolved; documented production-readiness decision |

No security-critical merge solely because CI passes. Before calling a feature complete,
review its invariants, full two-device evidence and remaining limitations. Plan/spec
documents may merge independently; they do not authorize release claims or deployment.

## Acceptance invariants and evidence

| ID | Required invariant | Evidence required |
| --- | --- | --- |
| S01 | Relay never obtains message secrets/plaintext | Packet/storage/log inspection plus provisioning/data-flow review |
| S02 | Relationships are independently isolated | Cross-contact substitution, credential/key mixing and one-contact wipe tests |
| S03 | Pad allocation never repeats in supported failure model | Fault injection at reserve/encrypt/commit/transmit/renewal; rollback limits documented |
| S04 | New user messages cannot use conventional-encryption fallback | Engine-level negative tests, UI/worker/legacy migration path review |
| S05 | Local wipe does not wait for networking | Hung request, no connectivity, unavailable/corrupt control material, restart tests |
| S06 | Only a valid targeted command triggers peer wipe | Forgery, wrong role/generation/contact/version, duplicates/reordering and simultaneous wipes |
| S07 | Revoked generations cannot resurrect | Stale engine, background work, renewal, delayed packets, restart and backup/restore tests |
| S08 | No unrelated mailbox access | Per-device scope, stolen capability, revoke/rotate, claim race and capacity tests |
| S09 | Retention and deletion cover documented data copies | History/draft/retry/pairing/export/cache inventory and clock/offline tests |
| S10 | Status reports only available evidence | Relay lies, lost ack, expired request, modified/offline peer and partial wipe UI tests |
| S11 | Destruction remains usable after pad exhaustion | Exhausted/retired normal keys and physically migrated control-key tests |
| S12 | Wipe-all continues despite partial failure | Corrupt index/vault, one key deletion error, orphan stores and concurrent work tests |

## Change discipline

Keep implementation PRs small and tied to finding IDs and invariant IDs. Update
progress with exact commits, tests actually run, unrun checks and next action.
Do not weaken a gate or invent a passing result to make progress appear complete.
No live server deployment or production-readiness claim follows from this plan alone.


## Historical work packages and continuing change discipline

These are dependency-ordered work packages, not a fixed number of chat turns or
required user approval prompts. Physical-device evidence and independent review
remain separate gates; implementation progress alone does not establish readiness. Batch connected work through a reviewable gate; split a package when a
security finding requires it. Tests alone never satisfy an independent-review gate.

| Package | Scope / natural stopping point |
| --- | --- |
| A — implemented/tested, merged #10 | Local recovery status, safe per-contact fresh-pairing UI and read-only orphan discovery; physical validation remains open |
| A2 — implementation candidate, #17 | Protected local-only "wipe this chat from this phone" on the retirement primitive; honest local-only status; never waits for the station worker. No peer command or wipe-all |
| B3 — design + pure core #18 | [ACTIVE epoch lineage design](ACTIVE_EPOCH_DESIGN.md) with slices B3a–B3f; B3a adds `OTPACT01`, `EpochLineage`, `EpochScope` with JVM tests, unwired |
| B — partial, merged #11–15 | Global freeze/cleanup/recovery UI, guard reader, initial provenance and empty staging implemented/tested. ACTIVE publication/routing, retained-cohort enforcement and repeat-wipe lineage remain open |
| C | Independently erasable history and reviewed crash-safe old-key migration |
| D | Exact emergency-control/dispatch/ack protocol and migration specification; independent review and vectors before code |
| E | Relay scoped authorization, durable control/tombstone support and recovery/migration tests |
| F | Android selected/all peer-wipe integration and full connected/offline/tamper/interruption matrix |
| G | Relay retention, Docker/Nginx/Portainer/secrets/log/backup hardening and live-host evidence |
| H | Final phone/e-ink UX, physical provisioning/power-loss checks, independent assessments and readiness decision |

Packages B/C remain Phase 2 requirements. Read-only inventory or a design draft does
not complete them. Remote destruction implementation stays behind package D's
review gate. Keep exact completion and test evidence in SECURITY_PROGRESS.md and
the implementation PR, rather than asking the user to reconstruct prior messages.


Current B2 reference: [global enrollment protocol candidate](GLOBAL_ENROLLMENT_PROTOCOL.md);
B3 continuation: [ACTIVE epoch lineage design](ACTIVE_EPOCH_DESIGN.md).
Global recovery observation/retry and empty staging are merged development foundations. Epoch
activation remains unimplemented; v1 terminal journals cannot prove absent reuse
registry provenance and must never authorize an empty reset. Executable design-model
checks do not complete package B or replace Android lifecycle/physical evidence.

## Packet serialization follow-up — 2026-10-28

Branch `test/production-packet-serialization` stacks on open #41. The production
POST serializer is shared with an Android instrumentation test; the misleading
hand-built JSON length assertion is removed. Binary/base64 lengths remain fixed;
Android JSON slash escaping can vary HTTP body size. No wire, pad reservation or
retry behavior changes. Local build/test evidence is recorded in the PR; emulator
execution and physical-device validation must not be inferred from compilation.

## Peer-wipe recipient ordering correction — 2026-10-28

Independent branch `fix/peer-wipe-local-first` starts from main 9a968a8. A verified
peer request previously posted RECEIVED before retirement, and ACK persistence
failure could prevent local destruction. The recipient now attempts local retirement
before any transport and despite ACK preparation errors (S05); final status preserves
actual cleanup evidence (S10). JVM tests block transport and inject persistence
failures; the end-to-end instrumentation fixture asserts POST occurs only after
retirement. Physical peer-wipe, partial cleanup/restart and independent review remain
open. Peer wipe stays unreviewed and off by default; see the PR for execution evidence.

## USB discovery implementation follow-up — 2026-10-28

The follow-up branch `test/usb-negotiation-lifecycle` stacks on #43 and adds tested
retry/permission/stop transitions, wider transport tests and large-pad streaming
coverage. S03/S07 are preserved by leaving pad activation/recovery in CableProtocol
and rejecting discovery after transfer starts or its captured operation is cancelled.
This is a review candidate, not a verified fix for the owner's physical regression.
Android instrumentation, signed upgrade and two-phone/power-loss evidence remain
separate from local compilation/JVM results recorded in the PR.

## Retired chat dismissal — 2026-10-28

Branch `fix/dismiss-retired-chats`, based on main after #50, implements removal of
completed retirement notices with **Done · remove from Chats** or returning to
Chats after viewing the notice. Incomplete/unavailable cleanup remains recoverable.
The index operation rechecks actual local cleanup under the lifecycle lock; it
retains pad ledgers, claims, tombstones, and queued peer-wipe delivery. Removing the
last entry creates an unused fresh setup slot (hidden by the existing empty-list UI),
never reviving an old pad namespace. No automatic unread-notice expiry is added.
Android instrumentation covers cleanup guards, durable removal, last-entry handling,
stale replacement and the Done action. See the PR for executed build/CI results;
physical device accessibility/back-navigation and pending peer-wipe delivery after
dismissal remain outstanding. No protocol or pad accounting changes.

## Optional loss-of-contact policy — proposed, 2026-10-28

Owner requests a separate off-by-default advanced option, outside normal setup,
for mutually agreed retirement after loss of authenticated peer contact. See
[design boundaries and remaining decisions](LOSS_OF_CONTACT_POLICY.md). This is
not implemented and does not change ordinary strict v1's no-inactivity-deletion
policy. Endpoint enforcement, clock/reboot rules, consent, protocol tests and
physical-device validation remain outstanding. A relay cannot be trusted with
arbitrary wipe authority; suppressed traffic can nevertheless trigger expiry.
Develop this separately after the relevant strict lifecycle/protocol decisions;
no existing USB, strict codec or diagnostics PR is superseded by this proposal.
