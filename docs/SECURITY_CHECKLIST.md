# Security development checklist

Updated 2026-10-27. Checked means implemented and tested in the stated scope.
PRs #9–15 are merged into `main`; a check does **not** imply independent review,
physical validation, deployment or production readiness. Phase 2 remains open.

[Developer handoff and exact evidence](DEVELOPER_HANDOFF.md) ·
[Canonical development plan](SECURITY_DEVELOPMENT_PLAN.md) ·
[Historical progress](SECURITY_PROGRESS.md) ·
[Fresh enrollment design](GLOBAL_ENROLLMENT_PROTOCOL.md)

## 1. Audit and threat model — initial source review done

- [x] Save the development plan in the repository.
- [x] Inventory storage, pads, credentials, deletion and message flows.
- [x] Record prioritized findings and testable security rules.
- [ ] Validate live-server and physical-device assumptions during later reviews.

## 2. Local storage and destruction — current phase

- [x] Enforce OTP-only encryption for new text messages (merged PR #9).
- [x] Implement per-conversation local key/file retirement.
- [x] Preserve pad-reuse protections through local deletion.
- [x] Block stale workers, pairing operations and UI from restoring retired conversations.
- [x] Recover interrupted local cleanup after app restart.
- [x] Add per-contact recovery and empty fresh-pairing generations.
- [x] Implement global freeze, orphan discovery and cleanup despite individual failures.
- [x] Add global recovery UI with independently verified local cleanup status.
- [x] Draft the fresh-enrollment protocol and executable failure model (design only).
- [x] Authenticate retained reuse guards with bounded frozen-only reads and cohort validation (#13).
- [x] Capture initial guard inventory/provenance before cleanup and authenticate terminal evidence (#14).
- [x] Bind cleanup completion to the exact authenticated root scope (#14).
- [x] Prepare fresh empty storage with one-use confirmation and captured owner capabilities (#15).
- [x] Recover/abandon interrupted staging without reusing reserved generations (#15).
- [x] Expose a protected, local-only "wipe this chat from this phone" control on the
      existing retirement primitive (#17; emulator CI only, see [design](LOCAL_WIPE_CONTROL.md)).
- [x] Design ACTIVE epochs as one lineage (capability, routing, cohorts, repeat wipe) and
      implement the pure `OTPACT01` codec, lineage resolver and namespace scope (#18, B3a;
      [design](ACTIVE_EPOCH_DESIGN.md)). Not wired to storage access.
- [x] Wire ACTIVE epoch capabilities, namespace allowlists, lineage-based freeze and current-index routing (#19, B3b; unreachable until activation, behaviour unchanged without a manifest).
- [x] Enforce immutable retained-cohort exclusions during active pairing/messaging (#20, B3c).
- [x] Implement repeat-wipe/forward lineage (`OTPTERM2`) and preserve every prior exclusion/reservation (#20, B3d).
- [x] Safely enable fresh enrollment after global destruction (#20, B3e activation, B3f UI; emulator CI only).
- [ ] Separate erasable history keys and safely migrate older storage/operational keys.
- [ ] Complete local-protection invariant review and remaining fault/upgrade tests.

## 3. Remote revocation specification

- [ ] Specify independent emergency keys, targeted commands and replay protection.
- [ ] Specify offline dispatch, acknowledgements, simultaneous wipes and physical migration.
- [ ] Obtain protocol review and independent vectors before destructive protocol code.

## 4. Relay support

- [ ] Strengthen per-device/per-pairing access scopes (review existing scoped claims too).
- [ ] Add durable priority control queues, tombstones and acknowledgement transport.
- [ ] Test migration, restart, tampering, duplication, isolation and restoration.

## 5. Android emergency wipe

- [ ] Add selected-conversation and all-conversation emergency actions (local-only
      selected and wipe-all controls exist, #17/#20; peer command remains).
- [ ] Implement authenticated peer destruction and honest local/remote status.
- [ ] Verify two-device connected/offline/interrupted/malicious-relay behavior.

## 6. Server minimization and hardening

- [ ] Finalize ciphertext retention, expiry and receipt-loss recovery behavior.
- [ ] Harden Docker, Nginx, Portainer, secrets, logs, permissions and backups.
- [ ] Verify the actual deployment and recovery/restore procedures.

## 7. Final interface refinement

- [ ] Add quick, protected emergency controls, distinct from routine deletion.
- [ ] Finish pad-capacity, delivery and destruction indicators.
- [ ] Validate phone and target compact e-ink usability on physical devices.

## 8. Independent validation and release readiness

- [ ] Independent cryptographic, Android, relay and provisioning reviews.
- [ ] Physical-device, power-loss, entropy and full two-device testing.
- [ ] Signing/update/supply-chain review and production-readiness decision.

Phase 2 remains open (history-key separation). Remote wiping has not been implemented. Source tests and
emulators do not establish physical erasure, compromised-peer compliance, trusted
rollback resistance or live-host security. Earlier usability/version work is not a
completion claim for these security phases.
