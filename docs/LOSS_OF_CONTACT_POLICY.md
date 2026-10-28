# Optional loss-of-contact retirement policy

Status: proposed, not implemented. Owner direction, 2026-10-28: make this an
advanced opt-in option for people who accept strict availability tradeoffs; keep
it out of normal setup. This document is a design boundary, not a wire specification.

Each enrolled endpoint would retire its own conversation when it has not received
an authenticated fresh peer check-in within an agreed interval. A relay can deliver
check-ins or notifications, but must not acquire authority to forge a peer check-in
or an arbitrary destructive command. Lack of contact is not proof of compromise.
A relay or network attacker can still force expiry by withholding traffic.

Enrollment must require explicit consent from both peers, preferably during physical
pairing, binding the policy, timeout, identities and pairing generation. Changing,
disabling, renewing or withdrawing consent needs a specified authenticated transition;
replayed check-ins and changes from an old generation must not extend a deadline.
No unilateral setting may silently enroll another person's phone.

This is separate from the ordinary strict-chat design's no-inactivity-deletion
choice. Strict authority share expiry could separately limit access on a missing
phone; it does not guarantee deletion there. A powered-off phone cannot execute
cleanup, Android may defer background work, and a compromised OS can suppress app
cleanup. Previously extracted plaintext, keys and copied shares cannot be recalled.
Do not present this as guaranteed remote erasure or as universally safer.

## Required design decisions before implementation

- Specify check-in authentication, freshness, sequence persistence and identity
  binding without consuming or reusing message OTP bytes for repeated heartbeats.
- Define deadline start/reset, reboot and clock rollback handling, offline time,
  Android suspension and what happens if durable time evidence is unavailable.
- Choose timeout bounds and whether a visible grace period is allowed. Explain
  battery loss, travel and relay outages before enrollment; default remains off.
- Scope retirement to the enrolled conversation generation. Local destruction must
  run without relay access, preserve reuse guards, and remain retryable after failure.
  Notification delivery and a list-row dismissal are not proof of remote destruction.
- Specify what devices do after expiry or asymmetric recovery; reconnecting must
  never resurrect retired pads. Fresh physical pairing creates a new generation.
- Define group membership and consent separately. Future group conversations use
  independent pairwise pads; one absent member must not silently cascade deletion
  beyond the policy each participant explicitly accepted.
- Decide whether authority-side share expiry is supported and how it composes with
  strict leases, revocation and relay failover. Transport failover must not bypass
  policy expiry, and ordinary relay downtime must not grant wipe-forging authority.

## Implementation and acceptance work (all outstanding)

Write the protocol/state machine and threat review first, then separate PRs for
persistence/clock rules, endpoint enforcement, optional relay support and consent UI.
Include deterministic tests for replay, delay/drop/reordering, forged events, clock
rollback, reboot, app suspension, storage failure, concurrent wipe/send/renewal,
policy mismatch, cancellation and generation changes. Assert pad accounting and
terminal retirement under every failure path. Test two real devices with airplane
mode, dead battery, force-stop, OS background limits, restart and relay failure.
Independent security review and accurate limitations remain release requirements.
