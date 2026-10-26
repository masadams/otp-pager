# Local destruction design — review draft

Targets: F01/F03/F04/F07/F09 and S03/S05/S07/S09/S12 in
[the security plan](SECURITY_DEVELOPMENT_PLAN.md). The local prerequisite prototype
below is under implementation review; no emergency wipe guarantee or peer-control
format is established by this document.

## First implementation slice: local contact retirement primitive

`VaultRetirement` accepts only current contact root namespaces (`station-v2` or
`chat-<20 hex>`). An AtomicFile `<root>.retired` outside the encrypted content records
version 1 pending/complete; file and directory are fsynced and contents checked.
Any marker variant, including malformed `.new`/`.bak`, disables that generation.
The marker is app-private plaintext metadata without secrets; filesystem write
access can cause denial/destruction, an explicit compromised-endpoint boundary.
There is no reset/unretire API. Future fresh pairing needs a new namespace.

One process-wide reentrant lock (also used by pad reservation and reuse claims to
avoid lock-order inversion) orders terminal marking, vault load/save/key creation,
device-key operations and engine transactions; no network is performed under that
lock. Contact root detection also gates direct draft/ledger constructors. Cleanup
attempts vault/draft wrapping keys, signing keys and vault/draft AtomicFile variants
independently, then directory sync. Pad ledger and global reuse claims survive.
Completion is reported only after all steps and durable completion-marker success.
If marker persistence fails, cleanup still attempts key destruction and the running
process denies the namespace; durable restart exclusion is not claimed for that case.

`recoverPending` derives validated roots from marker filenames rather than the
contact index. It retries cleanup idempotently even for complete markers, to remove
restored ciphertext. Fresh store objects, stale engine views/sends/exports, direct
child writes and cached signing handles are gated. No startup/UI caller invokes
retirement or recovery yet; normal Forget retains its existing semantics.

This is not complete emergency destruction. Live transport/provisioning cancellation,
already-captured in-flight ciphertext, process-memory clearing, terminal index/UI
migration, wipe-all initiation, automatic startup recovery, sealed remote dispatch,
remote acknowledgements, history-key separation and physical rollback/erasure remain
separate work. A cached internal engine state object may still contain secrets until
its owner drops it; Keystore/file deletion cannot erase those objects.

## Required storage identities

Distinguish a visible contact entry from its immutable operational generation. A
contact can be paired again only into a fresh generation/namespace with fresh keys.
A terminal generation can never be reopened by a stale engine, draft save, import,
renewal, index restore or default-key creation. Do not simply clear a flag and
reuse a namespace. Existing v0.14 records need a versioned migration preserving
pad/ledger/claims and all counter/replay state without allocating another pad owner.

Separate five categories:

1. Operational contact vault and device signing alias.
2. History/draft keys and their files; review whether legacy snapshots under the
   surviving operational key defeat a proposed finer-grained history deletion.
3. Minimal non-content terminal journal, generation identifiers and reuse guards.
4. Global contact index/preferences; selected wipe keeps unrelated contacts, while
   wipe-all removes selected personal metadata with a documented orphan sweep.
5. Future independent sealed dispatch/ack records; no operational message keys.

The terminal journal contains linkable metadata. Minimize it, protect it locally,
document its retention, and do not call it “all data erased.” It cannot be stored
only inside the vault it must disable. A single global journal can stop all work if
corrupt; the design must explicitly choose that fail-closed availability tradeoff.

## Persistent transitions

| State | Permitted behavior |
| --- | --- |
| Active | Normal operations subject to existing pad/identity gates |
| Terminal intent | No send, sync, draft save, import, identity/key creation or renewal for that generation; cleanup may resume |
| Destruction incomplete | Same terminal gate; retry individual cleanup steps and report incomplete status |
| Local destruction completed | Only minimal terminal/reuse evidence and permitted sealed dispatch/status remain; no operational reopening |

Durably record terminal intent before deleting keys when storage permits. If intent
cannot be persisted, still attempt local key destruction; record/report uncertainty
and fail closed in the running process. Restart behavior after that failure must
be tested rather than promised. Missing or unreadable journal state must never be
treated as permission to recreate a previously used generation.

Destroy all targeted wrapping/signing keys and files through idempotent steps.
Attempt each independent deletion even if another fails. Retain “incomplete” until
the required outcomes can be checked; do not infer completion solely from absence
of a filename. Test identity deletion failure separately from vault-key deletion,
AtomicFile variants and directory sync. Guaranteed flash/memory erasure is excluded.

## Concurrency and responsiveness

Current production work uses one `StationRuntime.worker`; network and pairing can
hold it. Enqueueing wipe behind this queue alone violates prompt local destruction.
An implementation needs a coordinated cancellation/terminal gate, including:

- Mark a generation unusable in memory immediately upon an authorized wipe action.
- Close/cancel its active transport and provisioning channels; distinguish pending
  tasks from operations already inside encryption, Keystore or file commit.
- Serialize the terminal marker and all vault/key creation/save operations so an
  in-flight write cannot commit after deletion and recreate a key.
- Recheck terminal generation before publishing UI results or starting new work;
  discard stale engine/views and clear composer/rendered content/notifications.
- Run restart recovery before any contact can be opened or background receive runs.

Do not add ad-hoc checks only to the UI. Enforcement belongs at every persistent
write/key creation and operational entry point. Define lock order and cancellation
ownership before code; never hold a lifecycle lock during network I/O. Filesystem
or Keystore calls themselves may stall, so “immediate” means no network dependency,
not an unsupported hard real-time guarantee.

## Wipe-all and corrupted state

Record global terminal intent, freeze new pairing and enumerate known contacts plus
recognized orphan vault/key namespaces without depending on a readable contact
index. Bounds, path validation, migration compatibility and ownership must prevent
deleting unrelated app/system aliases. Continue other contacts if one fails.
Decide how persistent reuse guards survive removal of the contact index. Do not
delete them merely to present an empty app; that can allow original-pad re-import.

## History-only deletion

Keep this distinct from terminal revocation. Existing clear-history preserves
queued ciphertext and counters; say so accurately. A stronger independent-history
key design needs migration/rekey atomicity and failure tests before claiming that
earlier snapshots are unreadable. Draft cleanup, delivery evidence and receipt
recovery must each have explicit policies. Turning off the device prevents timed
cleanup; expired display state is not evidence of erased storage.

## Required fault matrix before implementation completion

Inject interruption before/after terminal journal commit, each key deletion, each
file deletion, cleanup completion and UI acknowledgement. Combine with hung network,
in-flight send/save/renewal, queued background polling, draft onStop save, disk full,
corrupt index/journal/vault, missing key, orphan store, clock rollback and simultaneous
wipes. Restart after every boundary and attempt send/import/re-pair from stale and
fresh engine instances. Verify unrelated contacts still work, pads do not reuse,
incomplete operations remain terminal and all-contact cleanup continues on failure.

Review questions still open: journal format/authentication and recovery; storage
generation migration; deletion error enumeration; key separation/rekey protocol;
transport cancellation ownership; metadata retention; complete-purge behavior; OS
rollback guarantees. These require a reviewed specification and tests, not assumed
completion based on CI.
