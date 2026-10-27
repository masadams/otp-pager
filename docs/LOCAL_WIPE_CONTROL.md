# Local-only conversation wipe control (package A2)

Status: implementation candidate, CI-tested on emulators only. **This phone only.**
No peer command, wipe-all control, remote acknowledgement or fresh global enrollment
is added. Targets F01/F04 and S02/S05/S07/S10 in
[the security plan](SECURITY_DEVELOPMENT_PLAN.md).

## Why this comes before the rest of Phase 2/B

Until now the only destructive control in the app was **Forget pairing**. Finding F04
records that Forget runs its deletions sequentially, can stop part-way and leaves no
durable completion record. The crash-safe retirement primitive (#10) and its
recovery/fresh-pairing screen already existed but had no user-facing entry point.

Exposing that primitive gives users a real, honest per-conversation wipe now, using
code that is already merged and restart-tested. It does not depend on ACTIVE epochs
or fresh global enrollment, because per-contact recovery already starts a fresh
empty generation while keeping the pad ledger and reuse claims. Phase 2/B stays the
next storage package. A wipe-all button stays deferred: until fresh enrollment
exists it would leave the app permanently frozen, and the only way out (clearing app
storage) also removes local pad-reuse protection. That trade-off is an owner decision.

## User flow

Entry points: the conversation **⋮** menu → *Wipe this chat from this phone*, and the
same action at the end of *Friend details*. The ⋮ button and the confirmation are not
gated on the app's `busy` flag, so a stuck relay check never hides or delays the wipe.

The confirmation dialog names the friend and states, in order: what is deleted on
this phone and that it cannot be undone; that the friend's phone is **not** wiped and
nothing is sent to it; that other chats are unaffected and in-person pairing is
needed to talk again. **Wipe** stays disabled until the user types `WIPE`
(case-insensitive, surrounding spaces ignored). This blocks a stray tap without
adding a second dialog. Cancelling changes nothing.

## Execution order

1. Main thread, before any storage work: bump the screen generation (queued results
   and publications for this chat are dropped), stop polling and draft saving,
   dismiss dialogs, close the screen's own contact lease (so its cancellation does not
   close the whole activity), and clear composer, draft ownership, setup fields and
   rendered messages **without** saving the draft again. A progress screen replaces
   the conversation.
2. `LocalWipe` runs `ChatBook.wipeLocal(id)` on its own daemon thread, never on
   `StationRuntime.worker`, which may be blocked by networking or pairing (S05).
   A submitted wipe is never cancelled, including when the screen closes.
3. `wipeLocal` accepts only a contact root (`station-v2` or `chat-<20 hex>`) that is
   listed in the current index, then calls the existing
   `VaultRetirement.retireContact`: in-process denial and stale-engine invalidation,
   lease cancellation, durable `.retired` marker, independent deletion of vault, draft
   and signing keys and files, directory sync, completion marker. Pad ledger, reuse
   claims and the contact entry/name remain (S02, S03).
4. The result screen reports only local evidence (S10):

| Local result | Title shown |
| --- | --- |
| Marker and every cleanup step succeeded | Wiped from this phone |
| Marker durable, some step failed | Wipe not finished (chat stays permanently disabled; retry offered) |
| Marker could not be written | Wipe not recorded (disabled only in this process; retry now) |
| Request rejected before retirement | Wipe did not start |

Every outcome says the friend's phone was not changed, and that exported pad files,
screenshots, backups and the other phone are outside the wipe. **Continue** opens the
existing retired-pairing screen (retry cleanup / start fresh pairing).

Restart recovery is unchanged: `PagerApplication` retries any marked contact before
other components open (S07). A process death before the marker is written leaves the
contact intact; the user can wipe again.

## Limits

Local key deletion is not forensic erasure: flash wear-levelling, managed-memory
copies, OS/keyboard caches, backups, exported pads, screenshots and e-ink ghosting are
outside the claim. Queued ciphertext already accepted by the relay stays there until
relay expiry. The peer keeps its copy; a peer command needs independent emergency
control keys (Phase 3/D review first, then Phase 5/F). Ordinary **Forget pairing**
keeps its existing semantics in Advanced settings.

## Tests

`LocalWipeTest` (instrumentation, all three CI API levels):

- Wipe completes while the station worker is deliberately blocked; target vault/draft
  files and keys are gone; live engine snapshot is dropped; ledger, claims, names
  and a neighbouring contact's ciphertext are byte-identical and the neighbour still
  opens; a repeat wipe only retries; fresh pairing starts empty.
- Unlisted roots, the index, its claims and a `-pads` namespace are refused without
  creating any retirement marker.
- UI: the ⋮ menu offers the action with a worker-captured title; Wipe is disabled
  until `WIPE` is typed; with the worker blocked the wipe still completes; composer,
  setup fields, engine/view/draft ownership and on-screen private text are cleared;
  the result states the friend's phone was not changed; Continue reaches the
  retired-pairing screen with fresh pairing enabled.

Existing retirement, restart and signed-upgrade suites must keep passing. Physical
device, power-loss and two-device validation remain open.

CI at #17 head `2c533094b0f9453eabf7fd4ce388907111e77692`: Android
[run 40603779704](https://github.com/masadams/otp-pager/actions/runs/40603779704)
PASS (API29 job 122538118931, API31 compact Nexus S job 122538118501, API35 job
122538118753: build, unit, lint, full instrumentation suite including `LocalWipeTest`,
and all four host restart fixtures); signed upgrade
[run 40603779658](https://github.com/masadams/otp-pager/actions/runs/40603779658) PASS;
relay [run 40603779621](https://github.com/masadams/otp-pager/actions/runs/40603779621)
PASS. Per-API test counts and screenshots are in the `android-test-reports-api-*`
artifacts; artifact downloads were not reachable from the editing workspace.
