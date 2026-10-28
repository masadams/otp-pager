# OTP Pager developer handoff

Updated 2026-10-27. Repository: `masadams/otp-pager`. Start from `main`.
This file is sufficient to resume without the original chat. The owner requested
integration of the completed development work, an accurate plan and a transferable
handoff. Do not interpret merged foundations as a completed emergency-wipe feature.

## Read first

1. [Ordered checklist](SECURITY_CHECKLIST.md): what is implemented and what remains.
2. [Canonical development plan](SECURITY_DEVELOPMENT_PLAN.md): philosophy, phases,
   dependencies, acceptance invariants S01–S12 and review gates.
3. [Baseline inventory](SECURITY_INVENTORY_V014.md): assets, flows and F01–F12 findings.
4. [Local destruction design](LOCAL_DESTRUCTION_DESIGN.md),
   [enrollment protocol candidate](GLOBAL_ENROLLMENT_PROTOCOL.md),
   [initial evidence format](RETIREMENT_EVIDENCE_FORMAT.md) and
   [staging contract](ENROLLMENT_STAGING.md).
5. [Historical progress](SECURITY_PROGRESS.md) and linked PRs for slice-specific
   review/test history. Historical “draft/pending CI” entries are superseded here.

## Integrated implementation and evidence

PRs #10–15 merged in dependency order with merge commits, preserving their original
heads. No implementation edits were made during integration. The integration
endpoint is `574576d0a39108a641386ca260788c3ef7df578e`; its tree is
`03113c36ff76a0dbf73885b264efd22d3e49aa55`, identical to the tested #15 head.
Later documentation commits do not change the runtime implementation.

| PR | Implemented/tested scope | Merge commit |
| --- | --- | --- |
| [#9](https://github.com/masadams/otp-pager/pull/9) | OTP-only new user text, legacy-read/retry compatibility | `27872a78d99320f541ba3fe8e60726357c27f222` |
| [#10](https://github.com/masadams/otp-pager/pull/10) | Contact retirement, cancellation, startup recovery, per-contact recovery/fresh pairing UI, orphan inventory | `f2a3423bedb152ae12762bf97e332fb55dd58fe1` |
| [#11](https://github.com/masadams/otp-pager/pull/11) | Global freeze, index-independent cleanup, failure continuation and restart | `821562b5e2ef8829352c8fdf0358dfb03261d9e4` |
| [#12](https://github.com/masadams/otp-pager/pull/12) | Global read-only recovery status/UI and enrollment design/model | `71fa94b065cb900a6859f1c00ed4db7fca6bb2d2` |
| [#13](https://github.com/masadams/otp-pager/pull/13) | Bounded authenticated frozen-only retained-guard/cohort reader | `9843b857ead9f61b83cb8d16fc8e9ccf6a96cddb` |
| [#14](https://github.com/masadams/otp-pager/pull/14) | Authenticated initial pre-cleanup inventory/provenance and exact root-scope completion | `ce2309644ad98ec31f8e477c8a928c882df4bcf2` |
| [#15](https://github.com/masadams/otp-pager/pull/15) | Captured owners, one-use confirmation, empty staging, safe abandonment/restart | `574576d0a39108a641386ca260788c3ef7df578e` |

All six original heads' Android, signed-upgrade and relay workflow conclusions were
rechecked as successful before integration; no submitted or inline reviews were
present. Developer invariant review re-read the terminal gates, cancellation,
store/identity ownership, global orchestration, evidence binding and staging paths.
This is not independent security review. Full physical two-device emergency behavior
has not been demonstrated, and the overall emergency feature remains incomplete.

Final cumulative implementation evidence at #15 head
`397851573cea8b314517f80b95dcd8a098c9eaab`:

| Check | Evidence | Result |
| --- | --- | --- |
| Android build, unit, lint, instrumentation, restart matrix | [Run 40553219484](https://github.com/masadams/otp-pager/actions/runs/40553219484) | PASS |
| API29 / Android 10 | Job 122362251466 | 90 instrumentation tests; all four host fixtures PASS |
| API31 / Android 12, compact Nexus S | Job 122362251667 | 89 instrumentation tests; all four host fixtures PASS |
| API35 / Android 15 | Job 122362250872 | 90 instrumentation tests; all four host fixtures PASS |
| Signed upgrade/state preservation, wrong signer, downgrade rejection | [Run 40553219658](https://github.com/masadams/otp-pager/actions/runs/40553219658) | PASS |
| Relay tests/container checks | [Run 40553219400](https://github.com/masadams/otp-pager/actions/runs/40553219400) | PASS |

Compact UI screenshots were reviewed for #10 (artifact 12700887852) and #12
(12728989056). These artifacts may expire; fixtures regenerate them. Current #15
made no UI changes. Main-branch workflows are separate runs: inspect their actual
head/conclusion, never call a feature-head run a main-branch result.

## What works, and what remains deliberately unavailable

New user text requires a ready verified pad. Legacy packet decoding, exact ciphertext
retries and conventional legacy receipts remain for recovery. No new AES text fallback.

Contact retirement drops operational/draft/signing keys and files, blocks stale
objects/workers and preserves reuse metadata. Recovery can retry an already retired
contact and explicitly replace it with an empty fresh physical-pairing generation.
Unrelated contacts remain usable. Ordinary Forget is not emergency destruction.

Package A2 (#17, in review) adds a protected local-only **Wipe this chat from this
phone** action (⋮ menu and Friend details, typed `WIPE` confirmation). It runs the same
retirement on its own thread, never behind the station worker, drops private UI state
first and reports only local evidence; the peer is never contacted or described as
wiped. See [LOCAL_WIPE_CONTROL.md](LOCAL_WIPE_CONTROL.md) for exact CI evidence
(head `2c533094`, Android run 40603779704, upgrade 40603779658, relay 40603779621).

Global backend cleanup freezes all stores and metadata, attempts independent roots
and metadata steps despite failures, and recovers on cold startup. Recovery UI
checks actual keys/files and exact scope instead of trusting a completion flag.
There is no wipe-all initiation control: until fresh enrollment exists, a global
wipe has no exit except clearing app storage, which also removes reuse protection.

Initial authenticated evidence binds the pre-cleanup retained guard inventory.
Empty staging is backend-only and confirmation-bound. PREPARED never means ACTIVE.
The normal app cannot route to or use staged storage. Global fresh enrollment,
repeat-wipe epochs, separate history erasure, peer commands, remote acknowledgements
and new relay control queues are unimplemented.

## Source map and invariants to preserve

All paths below are under `app/src/main/java/org/example/otppager/`.

| Files | Responsibility / rule |
| --- | --- |
| `VaultRetirement.kt`, `GlobalRetirementMachine.kt`, `GlobalRetirement.kt` | Terminal contact/global gates; independent best-effort cleanup; durable scope; no wait for network/transport close; no unretire transition |
| `LocalWipe.kt`, `ChatBook.wipeLocal`, `NetworkActivity.confirmWipe/wipeChat` | User-initiated local-only contact wipe: listed contact roots only, own thread (never `StationRuntime.worker`), UI disposal before storage work, never cancelled, honest local-only result |
| `PagerApplication.kt` | Recovery before components open vaults. Uncertain intent/provider state blocks access without inventing destruction |
| `ContactInventory.kt` | Bounded file/key orphan discovery independent of readable index |
| `ContactOperations.kt`, `StationEngine.kt`, `NetworkActivity.kt`, `ReceiveService.kt` | Cancellation, owner checks, stale cache/UI/notification publication prevention. Never wait for worker/socket closure under lifecycle lock |
| `SecureStore.kt`, `DeviceIdentity.kt`, `VaultGeneration.kt` | Captured owner capability inherited by siblings, identities and leases. LEGACY/BLOCKED only; old owners never gain a future epoch |
| `ChatBook.kt` | Index schema 3 separates stable entryId from operational id; reload durable metadata per operation; confirmed per-contact replacement copies no old secrets |
| `RetainedGuardReader.kt`, `RetainedGuardInventory.kt` | Exact authenticated immutable reuse metadata; strict parsing/bounds; no key creation, repair, operational-vault opening or missing-as-empty fallback |
| `RetirementEvidenceCodec.kt`, `RetirementEvidence.kt` | `OTPTERM1`: INTENT/SEALED/CLEANED; one initial lineage, one-use pre-cleanup capture ticket. Authenticated roots must equal cleanup journal roots |
| `ActiveManifestCodec.kt`, `EpochLineage.kt` | Pure B3a core: `OTPACT01` canonical codec; lineage resolution with no head pointer (any inconsistency → Frozen); ACTIVE namespace allowlist. Not yet consulted by storage |
| `EnrollmentStageCodec.kt`, `EnrollmentStaging.kt` | `OTPSTG01`: PREPARING/PREPARED/ABANDONED only; up to 32 attempts. Auth key + record permanently reserve each attempt; new empty AES slots only |

Per-contact operational namespaces are `station-v2` and `chat-<20 lowercase hex>`.
Retained guards are `chat-index-claims` and `<contact>-pads`; do not erase their keys
as ordinary history cleanup. Their identifiers/counters are intentional retained
metadata. All lifecycle ownership checks use the reentrant `VaultLifecycle.lock`.
External Strings/JSON, active stack locals, OS/IME/clipboard, flash and exported pads
are outside any claim of guaranteed erasure.

## Next implementation package — finish Phase 2/B before enrollment UI

**B3 design is now written:** [ACTIVE_EPOCH_DESIGN.md](ACTIVE_EPOCH_DESIGN.md) treats steps
1–4 below as one lineage model and splits the work into slices B3a–B3f. B3a (#18) adds the
pure, unwired decision core: `ActiveManifestCodec` (`OTPACT01`), `EpochLineage.resolve`
and `EpochScope.permits`, with JVM tests. **Next: B3b** — sealed capability,
namespace-aware `requireCurrent`, lineage-based `isFrozen`, `ChatBook` routing and
manifest append, with real-record adapters (still no manifest writer).

In parallel (documentation only, no destructive code): start the Phase 3/D peer-wipe
specification so independent protocol review — the long-lead gate — can begin early.

1. Design/review ACTIVE capability and durable namespace allowlist. Bind every
   store, sibling, identity, lease, NFC offer and UI publication to one immutable
   epoch. Objects captured LEGACY or BLOCKED must never upgrade. Preserve public
   SecureStore/DeviceIdentity constructor ABIs used by signed-upgrade fixtures.
2. Introduce authenticated active-manifest/current-index selection and last-commit
   publication. ChatBook still defaults to legacy `chat-index`; a boolean gate or
   deletion of old terminal markers is insufficient. Missing/corrupt/ambiguous
   current state must never choose a legacy/default/empty index.
3. Enforce immutable historical guard cohorts plus writable current claims/ledgers
   during pairing and messaging. Frozen-only readers cannot simply bypass the
   global gate. An empty new claims delta alone loses prior reuse exclusions.
4. Implement forward lineage and repeat global wiping before activation. Current
   initial evidence has one key/one lineage; staging recovery assumes no attempt
   was active and can erase unexpected staged root keys. Global metadata cleanup
   knows legacy index scope. Extend these together before admitting live epochs.
5. Preserve old exclusions and all abandoned reservations in allocation decisions.
   Never-active staged roots are reserved by authenticated records/keys, not ordinary
   `.retired` markers. Future root allocation must consult those reservations.
6. Test activation interruption at every commit/key boundary, stale writers,
   unknown manifests, missing/altered guards, restored old indexes/attempts, and at
   least two full active → wipe → enroll cycles with old material refused. Then
   expose confirmed fresh enrollment UI and verify compact-screen behavior.

Read the candidate protocol first; implemented format contracts override any model
assumption that has not yet been implemented. Legacy v1 cleanup cannot acquire
retrospective provenance or authorize an empty reset. No supported recovery path
clears tombstones/claims to make a failed test pass. Coordinated OS/filesystem and
Keystore rollback remains an explicit limitation, not a solved invariant.

## Remaining work after that, in order

| Order | Deliverable / completion gate |
| --- | --- |
| Phase 2/C | Separate erasable history and crash-safe old operational wrapping-key migration. Plaintext exists in both `opened[wire].payload` and renewal `history[].text`; a new history file/key alone leaves older decryptable copies. Preserve counters, pending exact retries and reuse guards across interruption |
| Phase 3/D | Exact independent emergency-control keys, canonical targeted/replay-resistant command, minimal offline dispatch authority, ack semantics, simultaneous wipe, expiry and physical migration. Obtain protocol review and independently produced vectors **before destructive peer packet code** |
| Phase 4/E | Per-device/per-pairing scopes; durable priority control queues, tombstones, ack transport, migration/restart/restore/tamper/duplicate/isolation tests |
| Phase 5/F | Android selected/all emergency actions and authenticated recipient destruction. Local destruction never waits for submission/network/ack. Full connected/offline/interrupted/malicious-relay two-device matrix; unrelated contacts unaffected |
| Phase 6/G | Ciphertext/metadata retention, expiry and receipt-loss recovery; Docker/Nginx/Portainer/secrets/log/backup hardening and actual live-host recovery evidence |
| Phase 7/H | Quick protected controls distinct from ordinary deletion; honest local/queued/peer status, pad capacity; phone and physical compact e-ink usability |
| Phase 8/H | Independent crypto/Android/relay/provisioning/entropy review; physical power-loss/two-device testing; signing/update/supply-chain review and documented readiness decision |

Existing physical pairings have no independent emergency-control keys. Require
reviewed physical migration/fresh pairing. A relay acknowledgement is not proof of
peer destruction; say “peer reported wipe complete” only after authenticated peer
confirmation. Modified or permanently disconnected peers may never comply.

## Reproducible checks

Use Java 17, Gradle 8.9 and Android SDK/build-tools 35.0.0; Node 22 for relay CI.
There is no checked-in Gradle wrapper. The existing workflows are the reference
execution environment; Android SDK/Gradle were not installed in the editing workspace.

```sh
python3 scripts/test_enrollment_protocol.py
python3 -m unittest discover -s scripts -p 'test_android_signing.py' -v
node --test relay/test.mjs
git diff --check
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The Python protocol model is design evidence, not implemented Android activation.
For disposable API29/31/35 emulators run the sequence in `.github/workflows/android.yml`:

```sh
gradle --no-daemon :app:connectedDebugAndroidTest
python3 scripts/verify_retirement_restart.py
python3 scripts/verify_global_retirement_restart.py
python3 scripts/verify_lifecycle_evidence_restart.py
python3 scripts/verify_enrollment_staging_restart.py
```

The exclusive host fixtures intentionally force-stop and clear **synthetic emulator**
app data between scenarios. Never run them against an owner's paired device.
Retain the workflow's `vm.heapSize=256` AVD workaround; emulator-runner v2's
`hw.heapSize` setting is ineffective. This is an emulator setting, not app heap policy.
Signed upgrade uses `.github/workflows/android-upgrade.yml` and disposable signing
material; normal build tests alone do not exercise old-APK/new-test constructor ABI.
Relay workflow additionally checks Compose, persistent-volume restart and images.
Record the exact tested commit and failures/retries; don't inherit an old green run.

## Release, deployment and repository housekeeping

VersionCode remains 14, versionName 0.14.0. No new owner-signed release or deployed
host change was performed for this lifecycle integration. Main pushes trigger the
existing CI, including relay image publication; this does not redeploy Portainer.
The deployment stack retains its pinned relay digest. Verify release versioning,
anti-downgrade policy and owner signing before any future activation release.

Merged feature branches are historical references and may remain on GitHub; the
connector has no branch-delete capability. All continuation should branch from
main, not from an old stacked base. Do not delete history or deployment volumes as
cleanup. Keep future work in reviewable slices; update this handoff, checklist and
progress with actual implemented scope and exact evidence. Passing CI alone never
replaces invariant review, independent assessment or physical two-device validation.
