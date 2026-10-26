# Security development progress

Read [the development plan](SECURITY_DEVELOPMENT_PLAN.md) first. This file is the
handoff record; never infer feature completion from a plan or passing baseline CI.

## Current position

- Baseline: merged v0.14, `cfe01af255bebbd195337b9d1b0c72017e39f7c1`.
- Phase 1: initial inventory plus expanded authentication/provisioning and persistence review recorded.
- Phase 2: OTP-only new-text engine guard merged (PR #9); local retirement primitive under review.
- Phases 3–8: not implemented. No emergency wipe or peer-destruction claim.
- No relay deployment, APK/signing change, key migration or destructive protocol change.

## Decisions recorded 2026-10-26

1. Local destruction never waits for a network request, including submission.
2. Offline retries may retain a sealed minimal dispatch record, not message secrets;
   exact retained metadata and submission authority require protocol review.
3. Use “peer reported wipe complete”; no remote forensic-erasure guarantee.
4. Terminal pairing generations, durable local wipe recovery and stale-worker gates
   are required before destructive peer commands.
5. Existing pairings require physical migration/fresh pairing for independent control keys.
6. Essential relay authorization/limits/log hygiene accompany control support.
7. Existing v0.14 usability work is the starting point; no return to a cryptographic dashboard.
8. Retained reuse guards are intentional metadata, distinct from erasable content.

## Work log

### 2026-10-26 — plan and initial audit

Reviewed storage/identity, contact index/claims, pad ledger, draft retention, engine
message/renewal/cleanup/revocation paths, receiving workers, provisioning/export
paths, manifest, relay persistence/auth/expiry and deployment examples. Recorded
assets, flows, threat boundaries and F01–F12 in [inventory](SECURITY_INVENTORY_V014.md).
Updated README/roadmap entry points to identify this as the active security plan.

Validation performed:
- `node --test relay/test.mjs`: 13/13 baseline relay tests passed.
- Relative documentation links checked: passed.
- `git diff --check`: passed.
- Android/JVM/emulator tests not run in this session; no runtime code changed.
- Live server, physical devices and independent protocol review not performed.

This is source/documentation work, not completion of a cryptographic or
physical-device audit.

### 2026-10-26 — OTP-only engine and local lifecycle design

The plan/inventory PR #8 was merged. Expanded source review covers identity
transcripts, USB ECDH/approval/commit/recovery, NFC setup and local radio tickets,
legacy packet decode/receipt paths, all first-party file/Keystore/SharedPreferences
locations found by source search and existing regression callers. See the inventory
addendum for scope and limits.

F02/S04: `sendText` and text queueing now reject a missing pad. `View.canSend`
requires verified, ready pad capacity. Retained legacy packets can still be read,
retried and acknowledged using the pre-existing conventional receipt path; this is
explicit compatibility, not new AES text creation. Normal pad allocation and wire
formats are unchanged. Test-only legacy snapshots replace tests that previously
created AES texts through the engine. A new Android regression verifies rejection,
unchanged vault/counter/outbox/draft and persistence across restart.

F01/F03/F04/F07/F09: [local destruction review draft](LOCAL_DESTRUCTION_DESIGN.md)
records generation identity, terminal journal, key boundaries, cancellation/locking,
restart recovery, orphan/wipe-all handling and the required fault matrix. None of
that destructive behavior is implemented yet.

Validation: local whitespace/link checks passed, relay 13/13 and signing-policy
7/7 tests passed. First Android 10 run built/linted successfully but pad tests crashed
at a 16 MiB heap limit. Logs show upstream emulator-runner v2 writes `hw.heapSize`,
an unrecognized property (upstream PR #480). CI now sets `vm.heapSize=256` before
boot in both Android workflows; this does not change production app heap policy.
The corrected CI run must pass; the failed run is not counted as validation. Android SDK/Gradle/emulator are not installed in this workspace;
Android execution is delegated to the existing GitHub CI workflows, not claimed
as locally passing. No security-critical merge until CI and invariant review complete.

### 2026-10-26 — validated send guard and local retirement primitive

PR #9 merged at `27872a78d99320f541ba3fe8e60726357c27f222` after developer invariant
review and passing Android API29/31/35 suites, signed v0.13 upgrade preservation,
wrong-signer/downgrade rejection and relay checks at `460ebb42e286f81456d25052b5efbeb350296ac5`.
Evidence: Android run 40334727375, upgrade 40334727372, relay 40334727387.

The next slice implements `VaultRetirement`: durable terminal marker, best-effort
independent cleanup, idempotent recovery without a contact index, and storage/identity
gates under one local lock. New JVM fault tests cover marker/deletion/completion
failure and interruption boundaries; Android tests cover keys/drafts, preserved
reuse metadata and unrelated contacts, malformed markers, restored snapshots,
stale objects and a queued writer. CI results belong to the implementation PR.

The first primitive slice had no UI/startup invocation. It is not a finished local or
remote emergency wipe; subsequent integration is recorded below. F01/F03/F04/F07/F09 remain open in part; F02's new-text guard
is validated, while legacy receipt/recovery cryptography remains explicit.

### 2026-10-26 — operation cancellation and cold-start recovery

PR #10 foundation at `4a5ce344acb0017fad416b3393549b3b9458ee70` passed Android API29/31/35,
signed upgrades and relay checks (Android 40359977417, upgrade 40359977495,
relay 40359977474). PR #10 remains draft while integration continues.

Added per-contact cancellation leases and asynchronous bounded close dispatch;
retirement does not wait for socket/USB close. Bound production HTTPS traffic,
including background receiving wrappers, and pairing/NFC resources to those leases.
Owned NFC offers prevent cancellation of one contact clearing another's offer.
Added Application startup recovery before activities/services can access vaults;
startup enumeration failure disables vault access, while an incomplete contact
remains isolated and blocked. Added JVM cancellation tests, blocked-HTTPS cleanup
and NFC isolation Android tests, and a host-driven cold process restart test in
all Android CI jobs. These new checks require latest-head CI, not the earlier
foundation result. Relay baseline locally 13/13, Python syntax and whitespace pass.

No destructive UI, fresh-generation index migration, wipe-all, remote command,
independent history-key erasure or physical power-loss validation is included.
Already-sent ciphertext and managed-memory copies remain explicit limits.

## Next task

Validate latest-head cancellation, pairing regressions and actual process restart
across Android versions. Then implement fresh-generation contact-index migration,
local retirement/recovery status and memory/UI invalidation before exposing any
destructive controls. Complete wipe-all scope and independent history-key erasure
as separately reviewed changes. Physical USB/NFC cancellation and target hardware
still need testing; do not claim an end-to-end emergency wipe.

Do not implement the peer-wipe packet format until Phase 3 resolves control keys,
generation binding, dispatch authority, acknowledgement and migration semantics.
