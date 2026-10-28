# Discussion handoff and outstanding work — 2026-10-28

Snapshot of main `356cbe2` and open PRs as checked on 2026-10-28 (America/Toronto).
This records the discussion's decisions and known unfinished work so continuation
requires no chat history. It is not a new audit, an exhaustive vulnerability list,
or a release-readiness claim. Recheck PR heads and main before starting work.

## Current implementation and review map

- Main includes local/global retirement and recovery, fresh ACTIVE enrollment,
  retained reuse guards, vault-key rotation, separate pad storage (#38), large-pad
  streaming (#39), and opt-in peer wipe (#40). These are development functionality.
- #50 integrated #41–47 and #49: fixed packet/serialization coverage, USB retry and
  regression work, documentation/acceptance gates, and local-first peer-wipe
  recipient handling. Do not recreate those PRs from older continuation notes.
- #51 USB diagnostics and #52 loss-of-contact design notes are now merged. Their
  merge is not evidence of hardware verification or implementation of the policy.
- [#53](https://github.com/masadams/otp-pager/pull/53), head
  `8337eaa382d595ce40143bfc06073b54611f64bb`, remains open: completed retired-chat
  notices can be dismissed; see behavior and evidence below.
- [#48](https://github.com/masadams/otp-pager/pull/48) remains open: candidate
  `OTPSTR01` codec/fixtures/unlock guard, originally stacked on #44. It is not an
  end-to-end strict-chat implementation.
- [#54](https://github.com/masadams/otp-pager/pull/54), head
  `43687139d279933bf57e4239a7a19ee2040e4ae6`, remains open. Its author reports a
  combined strict vault, signed foreground unlock lease and relay check-in expiry,
  incorporating #48. Review that PR rather than implementing a competing strict
  stack. Its full implementation and test claims were not re-audited for this note.
- [#27](https://github.com/masadams/otp-pager/pull/27) owns the Node major-version
  upgrade. Assess compatibility, image pinning and deployment separately.
- Relay failover, groups and automatic peer-loss retirement are not implemented
  on this main snapshot. No deployment or owner-signed release was performed in
  this discussion. Leave review PRs unmerged unless the owner approves merging.

## Decisions and behavior agreed in this discussion

### Optional loss-of-contact retirement

The owner wants an advanced, off-by-default option, outside normal setup, for
people who explicitly accept deletion after prolonged loss of contact. It must
not silently enroll normal conversations. See the merged
[design boundaries](LOSS_OF_CONTACT_POLICY.md), which remain **proposed**.

The recommended design uses mutual consent, authenticated fresh peer check-ins,
and local enforcement at each enrolled endpoint. A relay may transport events;
it must not gain arbitrary wipe-forging authority. Withholding traffic can still
force expiry, so outages, dead batteries and travel can cause data loss. Being
unreachable is not evidence that a device is compromised.

Timeouts, grace periods, enrollment/change/cancellation semantics, durable clock
and reboot rules, freshness/replay handling, group consent and enforcement details
are not settled. Do not interpret this note as authorization for arbitrary
server-triggered mass wiping. A powered-off phone cannot execute cleanup; a
compromised OS can suppress it. Previously copied plaintext, pads, keys or shares
cannot be recalled. Authority share expiry is a separate mechanism, not proof that
all peer devices erased their copies.

### Retired-chat list cleanup

The owner reported that completed retired chats crowd the list and wanted them
removed once read or after some time. #53 implements the acknowledgement option:
**Done · remove from Chats**, returning to Chats, or system Back from a completed
notice removes its row. No elapsed-time deletion of unread notices is implemented.
Incomplete/unavailable local cleanup remains visible for recovery.

Dismissal rechecks actual cleanup under the lifecycle lock, removes only the
matching generation's index entry, and retains pad ledgers, pairing claims,
terminal markers and queued peer-wipe delivery. Last-row removal uses a fresh
unused setup slot hidden by the existing empty-list UI. A stale dismissal cannot
remove a fresh replacement. Removing the row does not attest remote erasure.

### Development discipline and retained direction

Continue separate, tested PRs in this order: USB reliability, strict chats, relay
failover, independent-pairwise-pad groups, then remaining security/reliability/UI
work. Preserve OTP reservation-before-encryption, exact-ciphertext retries,
non-reuse, immutable ownership and terminal retirement invariants (S01–S12).
Update the plan and handoff with dependencies, limits and physical tests per PR.

Peer wipe stays labelled unreviewed and off by default. Owner direction moved
independent review to the final community review package, not out of the plan.
24-hour retention was accepted as a policy target, not guaranteed powered-off or
forensic deletion. Separate ordinary per-device relay credentials were considered
and dropped; do not revive that work without new direction.

## Strict-design reconciliation required before integration

Earlier #44/#48 and main's plan selected one unlock authority, a maximum 60-second
foreground lease, fresh physical enrollment and **no inactivity share deletion**.
#54 now reports a later owner decision to combine leases with 12-hour/3-day/14-day
relay check-in deadlines, selected during strict pairing, and excludes strict drafts
and background receiving. This note records the PR's reported direction without
silently rewriting the earlier discussion or treating it as merged behavior.

The reviewer must reconcile these specifications, consent/default settings and
acceptance tests, including whether expiry is inseparable from strict mode or an
additional opt-in. Preserve the owner's requirement that automatic loss-of-contact
retirement is optional and outside normal setup. Authority-side expiry of a phone's
share is not the same as deleting every conversation participant's local data when
one peer disappears. Confirm #54's incorporation of #48 before closing or merging
either; do not merge both blindly. Reconcile overlapping documentation edits in
#53/#54 and this note when integrating.

## Remaining work by priority

1. **USB reliability:** reproduce the reported regression on physical phones with
   #51 diagnostics. Record models, OS/app builds, cable, host direction, permission
   prompts and failure stage. Exercise both directions, denial/retry, disconnect,
   renewal, 16/32/64 MiB transfers, process interruption and power loss. Do not clear
   or restore operational pads to make a test pass.
2. **Strict chats:** review #54's protocol, authority pinning/enrollment, signed grants,
   freshness, clocks, lease expiry, vault wrapping, memory/cache clearing and locked
   control processing. Test background/screen lock, reboot, cancellation, failed
   storage/network, expiry and local wipe while offline. Review migration refusal,
   authority backups/rollback, stolen-device access and copied-share limitations.
   Independently reproduce vectors; CI is not cryptographic review. Any relay
   deployment required by #54 is a separate owner-controlled step.
3. **Relay failover:** ordered endpoints, per-endpoint claims/cursors/epochs, shared
   replay/accounting state, exact-packet retry and original expiry. Never treat
   revocation/authentication failure as an outage that permits bypass. Report partial
   revocation honestly; transport fallback cannot manufacture a strict unlock.
4. **Groups:** independent pairwise pads for every sender/recipient; no shared
   multi-party pad. Proposed v1 is a fixed roster with all participants physically
   paired, authenticated roster agreement, per-recipient durable progress and
   explicit partial delivery; membership change creates a new group. Avoid an
   unprotected aggregate copy of strict content. These are design defaults, not
   existing functionality or a finalized group protocol.
5. **Peer wipe and lifecycle:** independent control-protocol/vector review, priority
   control persistence/tombstone/ACK requirements versus #40's actual scope,
   recipient restart and partial-cleanup recovery, simultaneous wipes and offline
   dispatch. Verify connected/offline/interrupted/malicious-relay cases on two phones.
   #49 fixes ordering/ACK-preparation failure; it does not close all recovery work.
   Review local/global destruction fault and upgrade boundaries, orphan discovery,
   stale workers, retained cohorts, repeat wipes and ACTIVE enrollment invariants.
6. **Retention and relay hardening:** finalize local/relay expiry and receipt-loss
   recovery, minimize delivered ciphertext without breaking retry, and define bounded
   storage without evicting revocations. Review append-only/per-mailbox scaling.
   Inspect actual Docker/Nginx/Portainer permissions, secrets, logs, backups, exposure
   and restore procedures; checked-in examples are not live-host evidence.
7. **Other planned improvements:** split-relay experiment (timing remains linkable),
   entropy-source review/optional entropy mixing and accurate OTP wording, protected
   emergency controls, pad-capacity/delivery/destruction indicators, and physical
   compact e-ink/phone usability. Validate #53 navigation/accessibility and pending
   peer-wipe delivery after dismissal on real devices.
8. **Release gates:** independent crypto, Android, relay and provisioning review;
   firmware/Keystore/entropy/power-loss tests; owner signing-key custody, first signed
   release, upgrade/anti-downgrade and supply-chain review; community review package
   with threat model, specs, vectors, known limits and reproduction instructions.

## Audit finding cross-reference

The [v0.14 inventory](SECURITY_INVENTORY_V014.md) and
[older security review](SECURITY_REVIEW.md) are historical source reviews, not
independent audits. Do not read their baseline gaps as all still unimplemented.

| Findings | Current interpretation / remaining verification |
| --- | --- |
| F01/F04 | Retirement and recovery foundations implemented; exhaustive fault/restart and physical validation remain. Routine Forget is not an emergency-erasure guarantee. |
| F02 | New text is OTP-only at the engine boundary (#9); legacy read/retry compatibility is intentional. |
| F03 | Key rotation (#22) and separate pad storage (#38) address snapshot/key-lifecycle scope; review crashes/upgrades and copied-key/rollback limits. |
| F05 | Independent peer-wipe control implemented in #40 with #49 ordering fix; broader queue/ACK/recovery review remains. |
| F06 | Per-chat/strict claims implemented (#34); ordinary per-device credentials deliberately dropped, broader authorization review remains. |
| F07/F08 | Rollback/cloning, exported pads/QR credentials, backups, managed-memory copies and compromised endpoints remain limits. No erase-all-copies or universal anti-rollback claim. |
| F09/F10 | Retention, receipt recovery, stopped-device timing, relay persistence/capacity and tombstone policy need completion. |
| F11 | Live host, firmware, independent review, physical testing and signer custody remain readiness gates. |
| F12 | This note repairs prominent stale status and links historical documents; keep status tied to commits/PRs in future updates. |

## Verification evidence for the retirement-list change

#53 source `8337eaa382d595ce40143bfc06073b54611f64bb`: 97 JVM tests,
lint, debug APK and instrumentation APK passed locally. Added Android tests cover
cleanup rejection, retained guards/dispatch, cross-owner reuse rejection, durable
last-entry dismissal, stale replacement, Done and system Back.

CI checked at this snapshot: [Android/emulator](https://github.com/masadams/otp-pager/actions/runs/40780706095),
[signed upgrade](https://github.com/masadams/otp-pager/actions/runs/40780706162), and
[relay](https://github.com/masadams/otp-pager/actions/runs/40780706015) all succeeded.
Physical-device checks remain outstanding. These results do not transfer to a new
integration commit or establish security of #54. For earlier PR evidence see the
[historical continuation record](CONTINUATION_2026_10_28.md).
