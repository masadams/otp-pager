# Development continuation — 2026-10-28

This record is sufficient to continue this batch without conversation history.
Baseline main: `9a968a8750657b438524902dbdac98429b080b46`. All PRs below are left
unmerged for owner review. No deployment, production signing or release occurred.

## Review graph and implemented scope

| PR | Required base | Source head | Scope |
| --- | --- | --- | --- |
| [#45](https://github.com/masadams/otp-pager/pull/45) | #42 | Documentation-only; see PR head | Current-state reconciliation and this continuation record |
| [#46](https://github.com/masadams/otp-pager/pull/46) | #43 | `a735761384116c8a5e6c8de4053930c3f89c89bf` | USB discovery controller, explicit permission retry, stale-callback/transport tests, 16/32/64 MiB streaming fixture |
| [#47](https://github.com/masadams/otp-pager/pull/47) | #41 | `c96a737f6f8f6580d630f761d5e79de0f4cf0364` | Production JSON serializer coverage; packet-size and HTTP-body-size distinction |
| [#48](https://github.com/masadams/otp-pager/pull/48) | #44 | `bbcbfa6ba1e202680f42484cc42555eac0a19c68` | Unwired strict-chat codec, signer verification, pending-unlock guard and Python/Kotlin fixtures |
| [#49](https://github.com/masadams/otp-pager/pull/49) | main | `39b579c692bcd0b724ad60734752a0481886fa31` | Local retirement before transport and despite ACK preparation failure; truthful incomplete result |

Review #41→#47, #42→#45, #43→#46, #44→#48 as separate stacks. Preserve both #42
and #44's overlapping pipeline additions, plus each follow-up's handoff/plan entries
when integrating. Recheck main and PR heads; do not automatically merge or replace
existing PRs. #27 already owns the proposed Node major-version upgrade.

## Validation record

Local checks used Gradle 8.9, Android platform 35, and OpenJDK 21 targeting JVM17.
CI remains the Java17 reference. Local SDK default build-tools resolved to 34.0.0;
both build-tools 34 and 35 were installed. Existing deprecation/lint warnings are
not independent security evidence.

| Source | JVM unit tests | Lint + debug APK + instrumentation APK |
| --- | ---: | --- |
| #46 | 93 passed | Passed |
| #47 | 84 passed | Passed |
| #48 | 91 passed | Passed |
| #49 | 88 passed | Passed |

Other local checks: enrollment model 9 passed; signing tooling 7 passed; relay
15 passed; strict Python fixture regeneration passed; diff whitespace and changed
document relative links passed. No local Android emulator or physical phone was
available. Compiling instrumentation does not execute its assertions.

CI evidence must be attached to its exact source head. For #45's original document
commit `d078b8bebc1052fc9c4aab2137542943f95ee51e`, Android run 40765603628,
signed-upgrade run 40765603559 and relay run 40765603557 all succeeded. That is
historical evidence for that commit, not a result for a subsequent documentation head.
Exact-head runtime CI results, verified 2026-10-28:

| PR/head from the source table | Android | Signed upgrade | Relay | Conclusion |
| --- | --- | --- | --- | --- |
| #46 | 40766810823 | 40766810463 | 40766810460 | All success |
| #47 | 40766885102 | 40766884836 | 40766884755 | All success |
| #48 | 40767114914 | 40767114948 | 40767114896 | All success |
| #49 | 40776063308 | 40776063317 | 40776063335 | In progress at this snapshot |

Run links use `https://github.com/masadams/otp-pager/actions/runs/<id>`.
Recheck #49 before review; do not inherit results from main or a different PR. Physical validation and independent review remain open even
when all workflows pass.

Reproduce from each PR branch:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/test_enrollment_protocol.py
python3 -m unittest discover -s scripts -p 'test_android_signing.py' -v
node --test relay/test.mjs
git diff --check
```

On #48 additionally run `python3 scripts/strict_chat_vectors.py --check`.
Run instrumentation, lifecycle restart fixtures and signed-upgrade verification via
the existing workflows on disposable emulators only, never an owner's paired phone.

## Implemented versus next work

USB retry state and tests are implementation candidates; the reported physical
regression remains unverified. Capture both device models, Android/app versions,
cable, host/accessory roles, permission prompts and precise failure stage. Test
both host directions, 16/32/64 MiB, mid-transfer disconnect, renewal and power loss.
Compare known-working and new builds without uninstalling active pad material.

Strict chats are **not implemented end to end**. #48's contract and frames are a
candidate, not independent review. Next slices are physical enrollment authority
binding and relay durable authorization/revocation, then the strict vault envelope,
control state accessible while locked, lifecycle/UI enforcement and physical pairing.
The current local-only SecureStore key cannot enforce strict protection. Preserve
immutable ownership, retained reuse guards and existing constructor ABIs.

Decisions: one strict-unlock authority; no inactivity-based share deletion; new
physical pairings only for v1; maximum 60-second foreground lease from request
creation, cleared on background/screen lock/wipe. A transport fallback cannot issue
an unlock. An exported static share, compromised OS/RAM or rolled-back authority
backup is not protected by the candidate codec.

Then implement relay failover with ordered endpoints, endpoint-specific claims,
cursors/epochs, shared cryptographic replay state and exact-ciphertext retries.
Revocation/authentication failure must not be treated as an outage to bypass.
Report partial revocation across relays honestly. Preserve original expiry.

Group v1 default: fixed roster, every participant physically paired with every other,
authenticated roster agreement, independent encryption/allocation for each recipient,
per-recipient durable progress and explicit partial delivery. Membership changes
create a new group. No shared group pads, hub forwarding or unprotected aggregate
copy of strict content. This default was selected after the owner expressed no
strong preference; it is not existing functionality.

Peer wipe remains unreviewed/off by default. The ordering correction does not solve
unfinished ACK recovery, backup tampering, offline peers or physical erasure. Work
through remaining retention, entropy wording, relay scaling and review-package tasks
as separate tested PRs after the higher-priority feature work.
