# Security development checklist

Updated 2026-10-28 against main `9a968a8`. Checked means implemented and tested in
the stated scope, not independent review, physical validation, deployment or
production readiness. See [continuation evidence](CONTINUATION_2026_10_28.md) for
unmerged #45–49 and [PIPELINE.md](PIPELINE.md) for the current work order.

[Developer handoff and exact evidence](DEVELOPER_HANDOFF.md) ·
[Canonical development plan](SECURITY_DEVELOPMENT_PLAN.md) ·
[Historical progress](SECURITY_PROGRESS.md) ·
[Fresh enrollment design](GLOBAL_ENROLLMENT_PROTOCOL.md)

## 1. Audit and threat model — initial source review done

- [x] Save the development plan in the repository.
- [x] Inventory storage, pads, credentials, deletion and message flows.
- [x] Record prioritized findings and testable security rules.
- [ ] Validate live-server and physical-device assumptions during later reviews.

## 2. Local storage and destruction — integrated foundations, review remains

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
      [design](ACTIVE_EPOCH_DESIGN.md)); subsequently wired by #19/#20.
- [x] Wire ACTIVE epoch capabilities, namespace allowlists, lineage-based freeze and current-index routing (#19, B3b; unreachable until activation, behaviour unchanged without a manifest).
- [x] Enforce immutable retained-cohort exclusions during active pairing/messaging (#20, B3c).
- [x] Implement repeat-wipe/forward lineage (`OTPTERM2`) and preserve every prior exclusion/reservation (#20, B3d).
- [x] Safely enable fresh enrollment after global destruction (#20, B3e activation, B3f UI; emulator CI only).
- [x] Make removed history crypto-erasable: crash-safe contact-vault key rotation and v2→v3 migration (#22, [design](VAULT_KEY_ROTATION.md)).
- [ ] Complete local-protection invariant review and remaining fault/upgrade tests.

## 3. Remote revocation specification

- [x] Specify independent emergency keys, targeted commands and replay protection (PEER_WIPE_PROTOCOL.md; draft with implemented-vs-proposed notes).
- [x] Specify offline dispatch, acknowledgements, simultaneous wipes and physical migration (draft; physical migration uses fresh pairing).
- [ ] Obtain independent protocol review and independently reproduced vectors; owner moved this gate to the final review package. Peer wipe must remain unreviewed/off by default.

## 4. Relay support

- [x] Add per-chat claims and optional strict claims (#34); per-device credentials were considered and dropped by owner direction.
- [ ] Complete broader access/revocation invariant review, including multi-relay behavior.
- [ ] Add durable priority control queues, tombstones and acknowledgement transport.
- [ ] Test migration, restart, tampering, duplication, isolation and restoration.

## 5. Android emergency wipe

- [x] Add selected-conversation and all-conversation local emergency actions (#17/#20).
- [x] Add opt-in authenticated peer-wipe implementation and status (#40, unreviewed).
- [ ] Review #49's local-first recipient/ACK-failure correction; it remains unmerged.
- [ ] Complete recipient restart/partial-cleanup and acknowledgement recovery review.
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

Contact-vault key rotation supplies the implemented history-erasure approach (#22),
not a separate history file. Peer wipe is implemented in the documented #40 scope;
strict chats, failover and groups remain future work (only #48's unwired strict codec
is in review). Source tests and emulators do not establish physical erasure,
compromised-peer compliance, trusted
rollback resistance or live-host security. Earlier usability/version work is not a
completion claim for these security phases.
