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
child writes and cached signing handles are gated. `PagerApplication` now runs recovery
before activities/services; unreadable journal enumeration disables vault access,
while individual incomplete contacts remain terminal without blocking unrelated
contacts. Recovery UI can retry an already terminal contact; no user-facing caller initiates
retirement of an active contact. Normal Forget retains its existing semantics.

This is not complete emergency destruction. Already-captured in-flight ciphertext,
complete memory erasure, wipe-all initiation, sealed remote dispatch,
remote acknowledgements, history-key separation and physical rollback/erasure remain
separate work. Cancellation/recovery integration is described below; its physical
hardware behavior still needs validation. Live engines now drop owned state/staged/cache references at retirement; separately
retained objects and active task locals remain outside this disposal boundary.

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


## Cancellation and startup integration

`ContactOperations` gives each contact root cancellable leases. Retirement first
sets the local terminal gate, synchronously flags its leases, and dispatches close
callbacks on dedicated daemon workers. Potentially blocked disconnect/USB/socket
close callbacks never run while holding the lifecycle lock and are not awaited by
key cleanup. Late close registration is handled; normal completion unregisters.
Other contacts have separate lease sets. The close pool is bounded to two workers;
if both block, later closes queue, but terminal flags and local cleanup still proceed.
Underlying Android I/O cancellation is best effort; bytes already sent cannot be
retracted, and a blocked I/O call may take its timeout to unwind.

The relay binds transport to a contact, including through background receiving's
wrapper. A lease registers HTTPS disconnect and checks cancellation around I/O and
before returning a response. USB and Wi-Fi pairing activities register resource
closure and confirmation-latch release; NFC reads register card close. Short NFC
publication uses the lifecycle gate. NFC offers have an owner key so cancellation
of an old activity cannot clear another contact's offer; the card service rejects
retired owners even before asynchronous cleanup runs.

`PagerApplication.onCreate` recovers terminal roots before ordinary app components
start. Recovery derives roots from validated journal filenames, not the contact
index. A directory-enumeration failure leaves operational access disabled for the
process. Individual cleanup failures leave only those contact roots terminal and
record an incomplete count. No attempt is made to reset terminal generations.

Tests include registry isolation/late registration/blocked-close fault cases, a
blocked HTTPS disconnect concurrent with real key cleanup, NFC owner isolation,
and a host-driven seed → force-stop → fresh instrumentation run. The latter verifies
that Application startup deletes contact/draft/signing keys and files before access,
while another contact and reuse metadata survive. This is not physical power loss
or proof of firmware/flash erasure.


## Generation replacement and content disposal

Index schema 3 adds a stable `entryId`, initially the previous namespace; `id` stays
the operational namespace used by stores, pad owners and existing worker intents.
Migration changes only the encrypted index metadata. It leaves all operational
vaults/keys/ledgers/claims in place and rejects unknown index versions. Every index
read/mutation reloads durable metadata under the shared lifecycle lock; old object
snapshots cannot overwrite a newer generation. Full OS/index+marker rollback remains
outside this protection.

`replaceRetired` is an internal explicit action, not automatic startup behavior. It
requires a known terminal contact, retries cleanup and requires verified durable
completion before an atomic index replacement. The entry/name and selection survive;
the replacement namespace starts empty with no copied secrets, identity or history.
Candidates exclude known index entries and file/key namespaces. Old marker and reuse
guards survive permanently. An interrupted/unpublished replacement can be retried;
restoring an older index still cannot open a terminal namespace. An unreadable/missing
index with a retired legacy root fails closed, rather than resetting the application.

Weak live-engine ownership avoids retaining short-lived receivers. Terminal marking
clears owned snapshots, staged pairing and view caches synchronously; setters gate
later publication to prevent an in-flight calculation repopulating these owners.
A foreground lease posts invalidation independently of socket-close workers. The UI
clears message/composer/configuration/scan/document/dialog state, advances its display
generation and finishes; normal queued result publication uses the same lifecycle
gate. Tests deliberately block the station worker during UI disposal. The recovery UI now exposes only retries of already terminal contacts and explicit
fresh-generation replacement; no active-contact destructive control is exposed.
Managed strings, external references, in-flight task locals, queued closures and OS
copies cannot be guaranteed erased. This is reference disposal, not forensic erasure.

## Recovery/status interface and orphan-scope prerequisite

The recovery page is reached only for an already terminal indexed generation. It
shows incomplete/completed/unavailable local status, no remote request, the retained
name/reuse metadata and erasure limits. `inspect` is read-only: it checks directory
availability, the exact clean completion record, absence of all four operational
key aliases, and absence of contact/draft AtomicFile variants. A completion record
with restored keys/files is incomplete. Malformed/extra journal variants never
become a completion claim. Observation cannot establish firmware/flash behavior.

`recoverRetired` rechecks both index membership and the terminal gate before retrying;
it cannot initiate destruction of an active contact. Fresh pairing requires explicit
confirmation and another cleanup/terminal check, then the existing atomic generation
replacement. It starts empty, with no queued import/scan/document applied to it.
Retired contacts remain visible in Chats, and unrelated contacts can be selected even
when the active contact cannot open an engine. Receiving skips retired roots. The
foreground still disposes and closes immediately on internal retirement; reopening
finds the recovery page. No emergency-wipe initiation control is exposed.

`ContactInventory` adds bounded read-only discovery for the later wipe-all operation.
It finds current production roots from exact vault/draft/pad/journal filenames and
Keystore aliases, including key-only and damaged-file orphans, without decrypting
vaults or reading the contact index. Partial/enumeration/capacity failure is an error,
not a complete empty scope. It does not delete anything or implement wipe-all. New
persistent contact artifacts must extend both discovery and deletion/status scope
before they can support a cleanup claim.

## Remaining local implementation batches — decisions before coding

### Global terminal scope and wipe-all

A loop over `ChatBook.chats()` is insufficient. The next destructive batch needs a
versioned durable global intent, immediate in-process freeze of contact creation/
replacement, cancellation of every known live root, and a dedicated local executor
that cannot sit behind networking. Scope combines validated disk/key discovery with
readable index entries when available; a corrupt index must not suppress orphans.
Global intent is checked by store/key creation and recovery before any normal app
component opens. Individual errors do not stop cleanup of other targets.

Retain pad ledgers/global reuse claims and per-generation markers, even after removing
contact names/index keys. Otherwise a new default index can admit an original pad
again. Do not delete the index until its names/selection and every operational root
have an explicit recovery policy; unreadable index and missing reuse-registry cases
must fail closed. No operation may remove global intent merely because a directory
scan returns no contacts. Define a separate explicit restart/new-enrollment transition
that preserves old exclusions and works after interrupted index deletion. Persistent
registration of each newly allocated generation may be needed to close discovery vs
creation races; resolve this before adding a wipe-all button.

Fault matrix: interruption before/after global marker and freeze, each root deletion,
index/name deletion and completion; unreadable index/directory/Keystore; key-only,
draft-only and journal-only orphans; capacity failure; concurrent add/replacement;
one failed root with others successfully cleaned; process restart and stale worker
publication at every boundary. Complete status requires full scope enumeration and
verified outcomes, not just the number of successful per-contact calls.

### Independently erasable history — migration prerequisite

Source review confirms two primary retained plaintext forms inside the operational
snapshot: `opened[wire].payload` (`cacheOpened`/`retireUsed`/`retained`) and renewal's
`history[].text` (`stageRenewal`). `view()` uses both. Current `clearHistory` removes
history and inbox entries but intentionally keeps queued outbox packets; `retireUsed`
rebuilds the corresponding opened cache, including delivery/readiness recovery data.
Drafts already use their own wrapping key. Moving only `history[]` to a separate file
would therefore leave recoverable messages in `opened` and older snapshots.

Before implementation, partition message payload/history from the minimum immutable
wire packets, accepted-packet hashes, replay windows, pad reservations and receipt
retransmission evidence required for safe delivery. Do not delete evidence merely to
empty a screen; do not keep plaintext in operational recovery records unnecessarily.
Specify how queued unsent text, receipts and legacy AES packet recovery behave after
history-key deletion. Queued ciphertext retention is a distinct policy and cannot be
presented as complete message destruction.

A stronger migration must also retire the old operational wrapping key after a
crash-safe rewrite excluding historical plaintext and already-consumed pad material.
Otherwise old ciphertext copies remain readable with the surviving key. This needs
a versioned envelope/key selection journal, verified destination commits, independent
reuse ledgers, bounded resume behavior and a rule that never falls back to an older
pad/counter snapshot. No old key may be destroyed before the required new durable
state is verified; interrupted/ambiguous transitions must disable messaging rather
than reset accounting. Exact journal/envelope format and rollback limits need review.

Required tests include every key/file/journal boundary, missing destination key/file,
old `.bak`/`.new` restoration, queued message and receipt loss, renewal history, legacy
upgrade, expiry/clock rollback and later compromise of surviving keys. Prove that
history-key deletion actually removes all intended recovery paths. Retained exports,
external copies, live-process strings and coordinated OS rollback remain excluded.
Neither global wipe-all nor independent-history migration is implemented by this PR.

## Global local-retirement backend — package B1

`GlobalRetirement` is an internal local-only backend, on a separate branch/PR stacked
on the contact-recovery foundation. It exposes no active-conversation wipe button,
peer command or unfreeze API. `submit` immediately latches a directory-wide gate,
drops live engine snapshots/staging/cache references, clears NFC offers and synchronously
cancels matching operation leases. Recovery pages hold an index lease as well, so
their retained contact labels are cleared even without an active contact engine. The HCE service also checks the global gate for
an owned offer even if its lease is no longer registered. Close callbacks run separately. Coalesced cleanup cannot be cancelled by its result Future and uses
its own daemon executor, independent of `StationRuntime.worker` and network I/O.

The gate covers **all** SecureStore-backed namespaces, including contact index and
reuse registry access and ordinary `forget` resets, so stale metadata objects cannot
publish or remove retained reuse guards while frozen. The backend's separately
scoped raw cleanup deliberately preserves those guards. The
reserved default `station-v2` generation is included even when empty. Cleanup keeps
pad ledgers, their wrapping keys, global reuse claims and all terminal records. It
independently removes operational contact/draft/signing keys and files, index/name
vault and identity keys plus AtomicFile variants, disables receiving preference,
attempts service stop, and clears this app's notifications. Notification publication
(including foreground registration) is serialized against the global gate so a stale
receiver cannot post after cleanup. Pad-size preference and external exports are outside
this scoped operation. Neither service-close timing nor already-sent bytes are an
erasure guarantee.

`global-retirement-v1.json` is an app-private plaintext record containing only version
1, pending/complete state and sorted validated root IDs. No names, message secrets,
commands or submit credentials. Any base/backup/new variant keeps the global freeze
latched. Root lists from valid variants are unioned; unreadable/malformed variants
stay untouched and prevent a completion claim, while cleanup of known targets still
runs. File size/root count are bounded. File and directory synchronization plus
readback verify writes. Failure after AtomicFile finalization preserves the terminal
file rather than calling failWrite and deleting it.

The machine first attempts a pending intent, then independently discovers disk and
Keystore targets without decrypting a contact or index. Discovery preserves known
roots when a source fails, never reports partial scope as complete, and closes the
directory stream even at a capacity boundary. A second pending commit records all
known roots **before** deleting their last artifacts. Each contact and metadata
step runs despite another failure. A final scan must be complete and find no new
roots before completion can be recorded. Newly found roots are retained for a later
recovery pass. Intent-persistence failure still permits best-effort cleanup but does
not establish a restart guarantee. No protection claims exceed retained state.

Application startup recognizes global intent before ordinary per-contact recovery,
repeats cleanup even for a complete record and keeps normal access frozen afterward.
Partial cleanup counts as incomplete; unreadable global record never becomes an
empty app. Filesystem-write access can deliberately trigger local destruction via
this reserved record, just as with contact journals: compromised endpoint/OS is an
explicit boundary. Relay traffic has no access to this authority.

Tests cover fault boundaries, failed journal/contact/metadata steps, partial scope,
late discovery, corrupt journal and retry. An exclusive host fixture runs after all
ordinary tests: seed → force-stop → recover/verify → force-stop → restored-state
verify → force-stop → malformed-journal verify. It uses a corrupt index, index-less
key/file orphans, one undeletable contact, retained reuse hashes, blocked app worker
and close callback, later restored files/keys, and refused fresh work. The disposable
emulator app is intentionally left frozen; production has no test reset path.

### Next gate: explicit enrollment after global retirement (package B2)

Cleanup completion does **not** authorize deleting the global record or recreating a
default index. Global recovery/status UI now observes/retries already-terminal cleanup; new enrollment remains unimplemented.
Before enrollment code, specify a persistent epoch/allowlist transition that blocks
pre-wipe **index and claims objects** as well as old contacts. Per-contact markers
alone do not prevent stale metadata writers repopulating a newly created index.
Validate retained reuse registries and old exclusions; an absent/corrupt registry
must not silently become empty. Define an interrupted new-index/key commit and a
forward-only epoch publication, with no fallback to old pad/counter snapshots.
A later journal version may need explicit retained-guard evidence. Current v1 means
frozen permanently, including after complete. Coordinated full OS rollback and
forensic erasure remain outside the guarantee.


### Global recovery observation and enrollment review candidate

The frozen app now routes to `GlobalRecoveryScreen`, outside the normal contact/index
loading path. It uses a dedicated status worker and permits only `retryExisting`;
that entry point rejects an app without an existing global freeze before any intent
is created. Status checks do not repair/erase anything: they require a clean complete
journal including the reserved default root, complete bounded disk/key inventory,
no roots outside recorded scope, verified per-contact completion, absent index/name
keys/files, receiving disabled and no active app notifications. A restored artifact
or malformed variant invalidates completion. Unavailable storage is not completion.
“Local cleanup verified” describes this scoped observation, not forensic erasure,
validity of retained reuse guards, remote destruction or readiness for enrollment.

The UI cannot activate wipe-all or fresh enrollment. All pairings stay disabled.
Retries refresh startup result metadata, never the terminal gate; result publication
is discarded after the screen's activity/generation stops. No status or retry work
queues behind `StationRuntime.worker`. See the
[global enrollment protocol candidate](GLOBAL_ENROLLMENT_PROTOCOL.md) for the next
review boundary and v1 guard-provenance limitation. The executable specification is
a design oracle, not a production implementation or Android adapter test.
