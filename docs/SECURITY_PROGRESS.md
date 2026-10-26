# Security development progress

Read [the development plan](SECURITY_DEVELOPMENT_PLAN.md) first. This file is the
handoff record; never infer feature completion from a plan or passing baseline CI.

## Current position

- Baseline: merged v0.14, `cfe01af255bebbd195337b9d1b0c72017e39f7c1`.
- Phase 1: targeted source inventory and initial prioritized findings drafted.
- Phases 2–8: not implemented by this work. No emergency wipe or peer-destruction claim.
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

## Next task

Finish the Phase 1 review of authentication/provisioning, all persistence/deletion
callers and existing test coverage. Then draft the Phase 2 local lifecycle design
against F01–F04/F07/F09 before implementation: durable terminal marker, cleanup
enumeration, orphan handling, worker cancellation and restart recovery, independently
erasable key boundaries and explicit OTP-only engine migration. Map each operation
to S03/S04/S05/S07/S09/S12 and fault-test points.

Do not implement the peer-wipe packet format until Phase 3 resolves control keys,
generation binding, dispatch authority, acknowledgement and migration semantics.
