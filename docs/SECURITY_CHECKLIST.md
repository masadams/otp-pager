# Security development checklist

In build order. Checked means implemented and tested in the stated scope, **not
merged, independently reviewed, deployed, or production-ready**. Local retirement,
global cleanup and recovery remain draft PRs #10–12. Latest validated recovery head:
`c188a8ab9c96a42a9a69e63fdd17ac81e7d49558` (API29/31/35, both restart fixtures,
signed upgrades, relay and compact screenshot review passed).

[Canonical development plan](SECURITY_DEVELOPMENT_PLAN.md) ·
[Detailed progress/evidence](SECURITY_PROGRESS.md) ·
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
- [ ] Validate retained reuse-protection records and bind complete inventory/provenance.
  The frozen-only guard reader passed in draft PR #13. Initial authenticated capture
  is the next review slice; multi-epoch provenance and enrollment remain unfinished.
- [ ] Implement durable enrollment generations and reject stale metadata writers.
  Confirmation-bound empty staging and captured owner capabilities are the current
  review slice; ACTIVE publication, routing and multi-epoch operation remain open.
- [ ] Safely enable fresh enrollment after global destruction.
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

- [ ] Add selected-conversation and all-conversation emergency actions.
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

Phase 2 remains open. Remote wiping has not been implemented. Source tests and
emulators do not establish physical erasure, compromised-peer compliance, trusted
rollback resistance or live-host security. Earlier usability/version work is not a
completion claim for these security phases.
