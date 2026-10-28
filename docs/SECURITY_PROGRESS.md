# Security development progress

Read [the development plan](SECURITY_DEVELOPMENT_PLAN.md) first. This file is the
handoff record; never infer feature completion from a plan or passing baseline CI.

## Current position — updated 2026-10-27

- PRs #9–15 are merged into `main`. Integration endpoint before this documentation
  refresh: `574576d0a39108a641386ca260788c3ef7df578e`.
- Phase 1 initial source inventory is recorded; live host/hardware validation remains open.
- Phase 2 includes OTP-only new-text enforcement, local/global retirement/recovery,
  authenticated initial provenance and confirmation-bound empty staging.
- Phase 2 is **not complete**: ACTIVE epochs, forward/repeat-wipe lineage, fresh
  enrollment UI and independent history-key/old-key migration remain open.
- Package A2 (#17, in review): protected local-only per-conversation wipe control on
  the retirement primitive. No peer command, wipe-all control or fresh enrollment.
- Phases 3–8 are outstanding. No peer wipe protocol, wipe-all control,
  remote acknowledgement, new release/version/signing decision or live deployment.
- Start with [the developer handoff](DEVELOPER_HANDOFF.md) for exact commits,
  validation, known blockers and ordered next steps. Earlier work-log statements
  such as “draft” or “pending latest-head CI” describe their historical slice;
  the integration record below and handoff supersede those status statements.

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

### 2026-10-26 — fresh generations and stale content disposal

Cancellation/startup head `5e3af6179d5f3bbe274e2cdbadf14d1c8f15f357` passed Android
API29/31/35 with cold-process restart (40367037080, attempt 2), signed upgrades
(40367037336), and relay checks (40367037120). Android12 required a retry after an
emulator ZIP download failure; failed attempts are not counted as validation.

The next slice migrates contact index metadata v2 → v3, preserving existing vault
namespaces, ciphertext, keys, pad ledgers and claims. Each visible contact gains a
stable `entryId`; its `id` remains the immutable operational namespace. Explicit
`replaceRetired` retries local cleanup, requires durable completion, and atomically
publishes a fresh empty namespace while preserving the entry/name and unrelated
contacts. It copies no credentials, history, pad, peer identity or draft. Fresh
setup/physical pairing remains necessary. Terminal markers and reuse claims remain.
Unknown index versions fail closed. Lost index + retired legacy root cannot silently
create a default pairing. Index operations reload durable state under the lifecycle
lock, preventing stale objects overwriting replacement metadata.

Live engine owners are weakly tracked; retirement drops their snapshot, cached view
and staged pairing references synchronously under the lifecycle gate. Later state,
view-cache and staged-pairing publication checks the same gate. A foreground contact
lease posts private UI disposal directly to the main looper, independently of blocked
transport closers or the station worker. It clears drafts, displayed messages,
credentials, pending scans/documents and dialogs, invalidates queued UI results and
finishes the stale screen. UI-result publication is gated against retirement.
This reduces reachable copies; externally retained JSON/Strings, active stack locals,
queued closures, OS/IME/clipboard and flash are not claimed to be erased.

Added Android tests for metadata-only migration, unknown versions, stale index
objects, fresh empty generations, preserved neighbor/ledger/claims, old-secret reuse,
restored older indexes, incomplete cleanup/retry, lost indexes, and foreground UI
invalidation with a deliberately blocked normal worker. Signed-upgrade checks now
verify the exact old index after reversing only the documented metadata migration;
operational state remains byte/hash checked. Latest-head CI must validate this slice;
earlier green results do not cover it. Local relay 13/13, Python syntax and whitespace
passed. The first new-slice Android10 run found two fixture failures: an existing
corruption test queried the now-invalid durable index during cleanup, and the new
unknown-index test tried to reread a version rejected by SecureStore itself. Cleanup
now uses pre-corruption IDs; the unknown-index test uses version 4 (supported by the
outer vault but invalid for a contact index). Neither failure is counted as validation.
The same head passed signed-upgrade checks. Fresh CI is required after fixture fixes
and extended UI metadata clearing. Concurrency review also removed the foreground
invalidation callback's wait on the lifecycle lock: cancellation already unregisters
its lease. The UI regression now parks the main looper, triggers retirement, then
holds the lifecycle lock while requiring private UI disposal to finish. This covers
storage-lock contention as well as a blocked app worker. Results and exact head are
recorded in PR #10.

No user-facing retirement/replacement control is exposed yet. The current open screen
closes on internal retirement; the dedicated retired-contact/recovery status screen
and its explicit fresh-pairing action are the next integration work, before any wipe
control is offered. No remote command or destruction acknowledgement exists.

### 2026-10-26 — local recovery UI and bounded orphan inventory

Generation/disposal head `eef6cd511bd6cfd83eabaf3945f2c5d6fc07c0eb` passed Android
API29/31/35 including cold restart (40419920887), signed upgrade/rejection checks
(40419920885) and relay (40419920895). Android15's test suite was slower, but passed
without retry. PR #10 records exact evidence and developer invariant review.

Added read-only local status observation that verifies the completion record AND
absence of operational keys and contact/draft files, failing closed on unavailable
storage. The recovery UI exposes incomplete/completed/unavailable states, clearly
states no remote request exists, retains a path to other contacts, and permits only
retry of already terminal contacts. Explicit confirmed fresh pairing rechecks cleanup
and publishes an empty generation; old scans/documents/secrets are not carried over.
Normal receiving/polling no longer tries to open the retired active engine. Adding
another contact while a retired one is selected does not copy its credentials.

Added bounded, read-only orphan discovery from exact production filenames/aliases,
without index or vault decryption. It discovers key-only/damaged-file contacts and
rejects partial/capacity failures. This is a wipe-all prerequisite, not wipe-all.
Expanded the local design with concrete global-freeze/index/reuse-scope decisions
and the history migration blockers: plaintext lives in both `opened` and renewal
`history`, so simply adding a history key would leave recoverable copies. Old-key
rotation and interrupted migration need review before coding. The canonical plan
now contains remaining packages A–H to support longer batches without repeated
permission prompts.

New tests cover read-only status vs restored files/keys/malformed journals/unavailable
directory, active-contact retry rejection, isolated navigation/retry/confirmation/
empty fresh pairing, screenshots, and orphan discovery/exclusions/capacity errors.
First recovery head `6cc0f76fcbbb15a03a51b41b23cadc26c13e6f30` passed API29/31,
signed upgrades and relay. Visual QA of compact-screen artifacts found disabled
fresh-pairing styling looked active and a redundant success notice; controls now
start disabled, show disabled styling, and use improved compact spacing. Fixture
cleanup explicitly removes AtomicFile variants on older Android. The corrected UI
passed API29/31 and signed upgrades; a further compact visual review found scrollbar
overlap at the right text edge. The page now reserves scrollbar space, and the UI
test scrolls to verify the bottom action buttons are fully visible and captures that
state. Fresh latest-head
CI and screenshot review are required after these changes. Local relay
13/13, signing-policy 7/7 and whitespace passed; latest CI evidence belongs to PR #10.

### 2026-10-26 — global local-retirement backend (package B1)

Recovery/UI head `3f1a9da4f214e89b67dada4c622eba63401a4650` passed API29/31/35
including cold restart (40428193658), signed upgrade/rejection (40428193562), and
relay (40428193548). Final compact recovery/action screenshots were inspected.
That foundation remains draft PR #10; global work is a separate stacked change.

Added a directory-wide terminal gate, synchronous live-engine/lease invalidation,
coalesced dedicated cleanup executor, bounded pending/complete global root journal,
independent partial disk/key discovery, index/name-key deletion, receiving stop and
notification cleanup. Late receiver notification publication is gated against the
freeze, recovery pages hold an index lease for private-UI invalidation, and NFC
offers are cleared/denied independently of lease registration.
All known contact targets and metadata steps are attempted despite individual errors.
Complete requires durable full-scope recording, successful cleanup and a complete
post-scan with no newly discovered roots. Malformed journal variants remain frozen
and cannot be overwritten as success. Startup retries complete as well as pending
records. Reuse ledgers/claims remain; there is no unfreeze or enrollment shortcut.

Added JVM fault/interruption and partial-inventory tests plus an exclusive four-phase
host test after ordinary/per-contact restart checks. It exercises a corrupt index,
key/file orphans, one failed contact while others clean, blocked worker and close,
restored artifacts, preserved reuse hashes and malformed-journal refusal. This is
source work pending latest-head CI, not a completed or deployable emergency feature.
Local relay 13/13, signing-policy 7/7, Python syntax and whitespace passed.
The initial backend head `7ccd21d84a23e341a378dbfc02d8b5984123f0e9` passed API29/31
including the exclusive global host test (40434991510). Review then closed late
notification and recovery-page label publication paths, and made the destruction
Future non-cancellable. Ordinary store resets are also blocked during a global
freeze, preventing stale resets deleting the retained reuse registry. The host test
now covers global clearing of an already
retired recovery screen, rejection of late notifications and retention of ledger keys.
Generic error copy no longer claims data is preserved when cleanup may have removed it. Fresh latest-head CI
is required for those additions; results belong to the stacked PR.

Review found a further enrollment invariant: stale global index/claims objects need
a durable epoch gate before a fresh index can be published. The local design now
records package B2's required transition. No global destructive UI, fresh enrollment,
history-key migration, peer command or production-readiness claim is included.

### 2026-10-27 — global recovery UI and enrollment protocol candidate (package B2)

B1 head `5bedce4a05f271fcf5c37399d42a97b637a4fc5a` passed API29/31/35 including
both cold-process fixtures (40437956455), signed upgrade/rejection (40437956751)
and relay (40437956639). Those results belong to PR #11, not this new slice.

Added a global recovery screen that does not construct ChatBook/StationEngine or
read the encrypted index. It works through a dedicated status worker while the
messaging worker is blocked, distinguishes verified/incomplete/unavailable local
cleanup and clearly states that no remote request exists. Only already-terminal
cleanup can be retried. Closing a screen cannot cancel cleanup; stale screen results
are generation-gated. Fresh enrollment stays unavailable, with no clear-data/reset
shortcut. A successful retry refreshes startup recovery metadata without opening
the global gate.

Read-only global status independently verifies full journal/root scope, every
contact's operational keys/files, index/name artifacts, receiving preference and
active app notifications. It never repairs journals, creates keys, or clears restored
artifacts. Unknown roots, malformed variants or restored metadata invalidate a
completion claim; storage/enumeration failures report unavailable. Retained reuse
records and forensic-erasure limits are explicit; this does not prove those retained
records sufficient for fresh enrollment.

The [enrollment protocol candidate](GLOBAL_ENROLLMENT_PROTOCOL.md) now specifies
freeze/evidence capture, immutable retained-guard cohorts, explicit staged fresh
index/claims namespaces, last-commit activation, captured epoch capabilities,
abandoned-epoch retirement and second-wipe behavior. Review found that v1 lacks
pre-cleanup guard provenance: an absent registry cannot safely imply an empty one.
No v1-to-empty enrollment migration is allowed. Added a nine-test executable design
model for staging/interruption, missing/altered evidence, stale owners, artifact loss,
abandoned epochs and repeat freeze. These are model checks, **not implemented
Android enrollment tests**. Actual epoch activation, guard reader/encoding/adapters,
physical pairing evidence and independent review remain outstanding.

Expanded exclusive Android host checks for read-only completion refusal, frozen-app
UI retry/reopen while messaging/close callbacks block, restored index keys and unknown
roots, malformed-journal retry, compact-screen action visibility and screenshots.
Added normal instrumentation tests for no-intent recovery refusal and unavailable
storage. Exclusive screenshots are now pulled after the host fixture too. Latest
head CI and visual QA are required; exact results belong to the new stacked PR.
Local model 9/9, signing-policy 7/7, relay 13/13 and Python syntax passed.
Initial UI head `847dc30d7687b0857aea39eb266ea87956318bce` passed API29/31 including
both host fixtures, signed upgrades and relay. Compact screenshot review found the
retry result could stay partly above the visible area and controls were too low.
The screen now places actions before the long explanation, removes a redundant
heading and scrolls to the result after a check/retry. The host fixture asserts the
header is fully visible before result capture. Latest-head validation is required
for this final usability correction; earlier results do not substitute for it.

### 2026-10-27 — retained guard reader and ordered task checklist

Recovery head `c188a8ab9c96a42a9a69e63fdd17ac81e7d49558` passed API29/31/35,
both restart fixtures (40491434853), signed upgrades (40491434826), relay
(40491434816) and final compact screenshot review (artifact 12728989056). Exact
results/developer review are in PR #12. No security implementation PR was merged.

Added [an ordered checked task list](SECURITY_CHECKLIST.md) at the owner's request.
Checked items distinguish implemented/tested scope from draft, independent review,
physical validation and production readiness. Phase 2 and remote wiping remain open.

The next implementation slice adds a frozen-only, bounded read-only guard adapter
with exact namespaces/commitments, no key creation or AtomicFile repair, strict
UTF-8/JSON schema and bidirectional cohort ownership validation. It returns immutable
minimal reuse metadata and never opens operational vaults. Actual Keystore tests
cover missing/restored/tampered/ambiguous/oversized data and unchanged files/keys;
Aggregate cohort byte/entry budgets are enforced while assembling authenticated
records; the validator is private. The existing exclusive host fixture now also reads retained guards after process
restart and operational key deletion. This is implementation pending latest-head CI.
See the enrollment design for exact bounds, scope and explicit non-authority.

### 2026-10-27 — authenticated initial capture and cleanup evidence

Guard reader head `77a4d1c67203dd4e782c0fb9c800367800098ca0` passed API29/31/35,
both restart fixtures (40495068317), signed upgrades (40495068447) and relay
(40495068319). Exact developer review and tree evidence are in PR #13.

The next slice connects authenticated terminal evidence to initial backend submission:
a unique generation and one-use in-process capture ticket, HMAC-SHA256 canonical
bounded binary record, independent complete disk/key inventory and authenticated
guard commitments, followed by ordinary cleanup. Evidence errors never prevent
local cleanup; startup can complete a pre-existing seal but never invent capture
from an INTENT/legacy state. All capture tickets are invalidated when cleanup begins.
Key-only interruption stays terminal; unknown key-provider availability without a
known intent blocks access without initiating destruction. Missing keys/records,
ambiguous variants, old guards and scope growth cannot authorize a reset.

Added exact format/authority/failure documentation and codec/Keystore tests. A new
exclusive host fixture, after all earlier tests, exercises sealed restart, real
submit/restart, failed/interrupted capture, old-guard rejection, key-only freeze and
cleanup of unexpected roots without extending a prior seal. It clears only the
disposable emulator's synthetic app data between scenarios; no production reset API.
Latest-head Android/upgrade/relay validation is pending, not inherited from PR #13.
No active epoch, enrollment button, peer command, signing/version change or deployment.

### 2026-10-27 — confirmation-bound empty enrollment staging

Authenticated capture head `f7a0fa2a990e62f20c30e700b4b813ad81779652` passed API29/31/35
with all three host restart fixtures (40503430055), signed upgrades (40503430063)
and relay (40503430046). Exact final root-scope binding/developer review is in PR #14.

The next slice reserves authenticated per-attempt epochs tied to current terminal
proof and one-use local confirmation, creates only new empty index/claims keys/files,
and records PREPARED without activation. Interrupted attempts are abandoned before
old global scope discovery. Reservation keys/records survive abandonment and block
nonce reuse or a silently dropped attempt; unknown/partial/tampered state never
becomes an empty index. It supports up to 32 attempts in the initial frozen lineage.

SecureStore now captures a generation capability at construction; sibling stores
inherit it, and identity callbacks/resets plus operation leases keep that owner
boundary. Constructor ABIs remain for signed-upgrade testing. There is no ACTIVE
capability or UI action: ordinary stores remain globally blocked after staging.
See [the staging contract and format](ENROLLMENT_STAGING.md) for exact scope, bounds,
recovery behavior and limitations. Current code is pending latest-head CI.

Added pure codec checks, a Keystore-backed owner/sibling/reset/lease regression, and
an exclusive host matrix covering prepared restart, abandon/new epoch, interrupted
and restored attempts, proof/cancellation/forged-token refusal, orphan/tamper/variant
state, missing slots and unknown metadata. It clears only disposable app fixtures;
all earlier restart/signing/pairing/message checks remain required. No deployment,
version/signing change, remote packet or normal-message format change.

### 2026-10-27 — owner-requested development integration and handoff

Merged #10, #11, #12, #13, #14 and #15 in dependency order using merge commits,
retargeting each successor to main. Final integrated implementation commit:
`574576d0a39108a641386ca260788c3ef7df578e`; tree
`03113c36ff76a0dbf73885b264efd22d3e49aa55`, identical to validated #15's tree.
No runtime code was changed during integration. Every original head's Android,
signed-upgrade and relay checks was rechecked; none had submitted reviews or inline
review comments. This is developer-reviewed integration requested by the owner,
not an independent approval or completed emergency feature.

Final implementation evidence at #15 head
`397851573cea8b314517f80b95dcd8a098c9eaab`: Android 40553219484,
API29/31/35 jobs 122362251466 / 122362251667 / 122362250872, respectively
90 / 89 / 90 tests, all four host restart fixtures PASS; signed upgrade
40553219658 PASS; relay 40553219400 PASS. New main workflows run separately;
inspect their actual results rather than relabelling feature-head evidence.

Developer invariant review re-read terminal gates, store/identity ownership,
cancellation, global orchestration, exact root/provenance binding and staging
creation/recovery. Cleanup does not await networking, reuse guards survive,
unknown state cannot create an empty replacement, and PREPARED has no activation
capability. Review and automated evidence do not establish physical erasure,
full two-device emergency behavior or independent protocol security.

Added a self-contained developer handoff, refreshed the ordered checklist and plan,
and linked them from README. Physical validation, independent review and release
readiness remain open. No live deployment or owner-signed APK release was performed.

### 2026-10-27 — order review and package A2 (local-only conversation wipe)

Reviewed plan order against the merged code. Kept the overall gates (local before
remote, specification review before destructive peer code, small invariant-tied PRs).
Pulled forward A2 because retirement, restart recovery and fresh-pairing replacement
were merged but unreachable, while the only user-facing destructive action was the
non-durable Forget path (F04). Deferred wipe-all initiation (no enrollment exit; clearing
storage removes reuse guards) as an owner decision. Recommended starting the package D
specification in parallel for review lead time.

Implemented `LocalWipe` (own daemon executor), `ChatBook.wipeLocal` (listed contact roots
only, retirement outside the outer lock), ⋮ menu and Friend details entry points not
gated on `busy`, typed `WIPE` confirmation, main-thread private-state disposal before
storage work and a local-evidence-only result screen leading to the existing recovery
screen. Removed a UI-thread index read from the conversation menu title.
`LocalWipeTest` covers worker-blocked completion, neighbour/ledger/claims preservation,
refusal of non-listed/non-contact namespaces and the full UI flow. Local Kotlin
type-check of main/unit/instrumentation sources against API 35 passed in the editing
workspace. CI at #17 head `2c533094b0f9453eabf7fd4ce388907111e77692`: Android
[run 40603779704](https://github.com/masadams/otp-pager/actions/runs/40603779704)
PASS (API29 job 122538118931, API31 compact Nexus S job 122538118501, API35 job
122538118753: build, unit, lint, full instrumentation suite including `LocalWipeTest`,
and all four host restart fixtures); signed upgrade
[run 40603779658](https://github.com/masadams/otp-pager/actions/runs/40603779658) PASS;
relay [run 40603779621](https://github.com/masadams/otp-pager/actions/runs/40603779621)
PASS. Per-API test counts and screenshots are in the `android-test-reports-api-*`
artifacts; artifact downloads were not reachable from the editing workspace. Not independent review; no physical-device evidence.

### 2026-10-27 — PR #17 merged; B3 design and pure core (B3a)

Merged #17 (merge commit `98056c8cfdfcff87f1083747cd0b92ac2babecac`) after all three
workflows passed on its final head `2f05516`.

Wrote [ACTIVE_EPOCH_DESIGN.md](ACTIVE_EPOCH_DESIGN.md). Finding: an ACTIVE epoch changes
the meaning of "frozen" (old terminal/staging records stay forever as provenance), so
capability, routing, guard cohorts and repeat wipes must be decided from one lineage.
`OTPTERM1` permits a single record/key per directory and only legacy guard namespaces, so
a repeat wipe needs a successor `OTPTERM2` (slice B3d). Added pure `ActiveManifestCodec`,
`EpochLineage` and `EpochScope` with `ActiveManifestCodecTest`/`EpochLineageTest` (rollback,
ambiguity, unexplained records, second-wipe freeze, order independence, scope refusal).
All 64 JVM unit tests pass locally; Android/emulator evidence belongs to #18's CI.

## Next task

Slice B3b (see ACTIVE_EPOCH_DESIGN.md): implement reviewed ACTIVE epoch capability/namespace allowlist publication and
current-index routing together with immutable retained-cohort checks during normal
pairing/messaging and repeat-wipe/forward-lineage handling. Follow the staged
acceptance tasks in [DEVELOPER_HANDOFF.md](DEVELOPER_HANDOFF.md). Do not enable fresh
enrollment UI before those gates pass. PREPARED is not ACTIVE; legacy v1 terminal
states cannot gain retrospective provenance or authorize an empty reset.

Then complete history-key migration and the independently reviewed emergency peer
protocol in plan order. Physical device/power-loss/two-device and independent
validation gates remain open.
