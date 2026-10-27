# Global fresh enrollment — protocol candidate (package B2)

Status: **design candidate, not implemented or independently reviewed**. The current
v1 global journal stays terminal. The recovery screen offers observation/retry only.
This document specifies a local storage transition, not a remote-wipe protocol.
Read [the development plan](SECURITY_DEVELOPMENT_PLAN.md) and
[local destruction design](LOCAL_DESTRUCTION_DESIGN.md) first.

## Security boundary and important v1 limitation

A fresh enrollment must never delete a global journal and recreate `station-v2`.
Old engine, identity, index, claims, lease and queued UI objects must remain invalid,
even when their namespace is not a contact root. Per-contact tombstones alone do not
protect metadata. A new empty registry must never legitimize previously used pads.

The v1 destruction journal records roots, not a pre-cleanup inventory of retained
reuse guards. A missing registry after cleanup cannot generally be distinguished
from a registry that never existed. A successful v1 cleanup therefore does **not**
establish eligibility for fresh enrollment. Do not add a v1-to-empty migration or
infer empty history from missing files/keys. Existing v1 terminal states remain
frozen unless a separately reviewed migration proves the required evidence.

## Proposed durable records and authority

Use a new, explicitly versioned lifecycle manifest. Keep the original terminal
record and all per-contact markers; enrollment does not mutate them into active
states. The exact authenticated envelope/encoding remains an implementation review
item. Reject unknown versions, duplicate fields/IDs, invalid namespaces, oversized
records, unreadable variants and conflicting base/backup/new records. No record is
allowed to contain message/pad bytes, names, relay credentials or peer commands.

The manifest records:

- A unique epoch ID and predecessor epoch/terminal-record commitment.
- Phase: `frozen`, `preparing`, `active`, or terminal `failed`.
- Explicit approved metadata namespaces and contact roots for that epoch.
- Immutable retained-guard descriptors: schema, exact namespace/key alias, ciphertext
  commitment, and the expected file/key presence observed at the freeze boundary.
- A complete bounded root/guard inventory and validation outcome. Missing/corrupt or
  partial evidence makes fresh enrollment ineligible; cleanup still proceeds.
- Specific staged fresh-index/key identities and their verified commit commitments.

Keep a small forward chain of immutable guard cohorts. Every active claim consults
all retained cohorts plus the active epoch's writable delta. Retained cohorts are
read-only and cannot be removed with ordinary history/pairing reset. No consolidation
or chain truncation without its own crash-safe design; a capacity failure disables
fresh enrollment, never destruction. Exact byte/count limits need implementation
review and tests. Plaintext filesystem control is not an authorization boundary
against a compromised app/OS; relay packets cannot write lifecycle records.

## Freeze, evidence capture and destruction

1. Latch the global gate and synchronously invalidate all live owners/leases. Never
   wait for networking, a close callback, normal worker, or enrollment preparation.
2. Attempt a durable terminal intent. Inventory operational roots and retained
   guard files/keys independently, preserving partial evidence and failure state.
3. Validate retained claims and ledgers using a narrowly scoped read-only path that
   cannot create keys/files, reset counters, or open operational contact vaults.
   Seal immutable ciphertext commitments while writers remain blocked. A path-only
   listing or key-alias presence alone is insufficient evidence of valid guards.
4. Persist full known cleanup scope and guard evidence before deleting its last
   artifacts. If persistence/evidence fails, continue best-effort destruction and
   leave enrollment ineligible. Destruction must not depend on enrollment success.
5. Delete operational/index/name keys and files; retain immutable reuse cohorts,
   ledger exclusion state and terminal records. Complete the independent post-scan.
   Unexpected survivors or new roots prevent cleanup completion/enrollment.

A first-install system with no guards needs affirmative durable evidence of that
state, obtained before destroying inventory. Missing evidence never means empty.
Current v1 files cannot supply this retrospectively.

## Explicit fresh enrollment transaction

Run only after a deliberate local confirmation and verified complete cleanup plus
valid complete guard evidence. No remote packet authorizes this transition.

1. Recheck terminal state and retained ciphertext commitments, AEAD decryptability,
   schema and cross-guard consistency under the lifecycle lock. Refuse missing,
   altered, unreadable or key-only/file-only guards. Preserve the failure evidence.
2. Reserve a new epoch and new contact/index/claims-delta namespaces that have never
   existed in any manifest, marker, file or key alias. Persist `preparing` with its
   exact intended scope. Old epochs stay terminal; no namespace/key is recycled.
3. Construct a fresh empty index and writable claims delta using the designated new
   keys. No old credentials, contacts, history, pads, counters, scans or drafts are
   copied. Independent retained cohorts continue to reject old material.
4. Verify authenticated readback, key/file presence and file/directory durability
   for every staged artifact. A failed/ambiguous staging operation remains frozen.
   Before retrying from a failed stage, terminally abandon that epoch, clean its
   staging keys/files and allocate a different epoch. Never create a replacement
   key over a missing committed file or treat a surviving key as a blank vault.
5. Revalidate guards and publish `active` as the **last** durable commit. Only then
   may newly constructed owners acquire that epoch's capabilities. Background
   receiving remains off. New physical pairing is required before messaging.

On restart, `preparing`, failed or ambiguous state is globally frozen. An `active`
manifest grants access only if its authenticated committed artifacts and every
retained guard verify; there is no fallback to an older manifest/index/pad snapshot.
Do not automatically finish an interrupted confirmation by opening an empty app.
Recovery may inspect/clean abandoned staging; activation needs explicit local intent.

## Capability and stale-object rules

Every SecureStore, identity callback, ChatBook, PairingClaims, engine, lease, NFC
offer and asynchronous publisher captures an immutable epoch capability when
constructed. Legacy objects capture a distinct legacy capability, never a mutable
reference to “whatever epoch is current.” Every load/save/key/forget/publish checks:

- current lifecycle state is fully verified active;
- captured epoch equals active epoch;
- exact namespace/root is in that epoch's durable approved scope;
- the contact is not terminally retired;
- retained guard validation remains available; no error creates an empty registry.

Guard readers use a separate read-only capability, limited to manifest-listed
immutable cohorts. A production general-purpose `ignoreFreeze`/raw-store flag is
forbidden. New contact allocation appends its namespace durably before creating
its first key/file; stale metadata writers cannot append through another epoch.
New wipe-all revokes the active epoch before cleanup. Boot recovery must distinguish
old retired scope from explicitly permitted new scope; B1's indiscriminate global
rescan cannot run against an active enrolled epoch and destroy the fresh contacts.

## Failure and rollback limits

| Observed state | Required behavior |
| --- | --- |
| Complete v1 cleanup, absent guard provenance | Stay frozen; no empty-registry inference |
| Intent/guard capture fails | Attempt all local destruction; no enrollment |
| Missing/corrupt retained file or key | Freeze access; preserve evidence; never reset |
| Interrupted `preparing` or staged key/file mismatch | Freeze; abandon epoch before a fresh explicit attempt |
| `active` record without matching verified index/delta/guards | Freeze; no legacy/default fallback |
| Restored old operational ciphertext or index | Old key/capability rejected; no conversation resurrection |
| Old metadata/claims/identity/lease object after activation | Reject before reading, writing, key creation or publication |
| Global record/manifest variant conflicts | Freeze; never pick the most permissive variant |
| Discovery capacity/permission failure | Partial scope, no completion/enrollment claim |
| Compromised OS or coordinated full snapshot rollback | Outside guarantee; disclose explicitly |

The model cannot supply a trusted monotonic counter across coordinated OS/Keystore
rollback. Fresh namespaces, deleted old operational keys and immutable captured
capabilities prevent defined app-level stale-object/partial-restore cases; they do
not establish physical erasure, hardware rollback resistance or remote compliance.

## Evidence required before production activation

`scripts/test_enrollment_protocol.py` is an **executable specification**, not tests
of implemented Android enrollment. It checks transaction ordering, stale capability
refusal, missing/altered guard handling, orphan staging and interruption boundaries.
It deliberately models validity as supplied evidence; it cannot validate the real
Keystore, AtomicFile behavior, ciphertext parser or filesystem durability.

Before production code: review record encoding, cohort schemas/limits, lock ordering,
scoped guard reader, key/index commit and abandoned-stage recovery against these
invariants. Then add actual adapter fault tests at every file/key/journal boundary,
corrupt/lost guard tests, stale metadata owners/publishers, cold process restarts,
legacy upgrades, second wipe/enrollment, post-wipe secret-reuse attempts, and exact
fresh-generation two-device pairing/message/recovery behavior. Independent storage
review and physical power-loss tests remain required. Keep the UI's fresh enrollment
action disabled until this implementation and evidence exist.


## Implemented adapter slice: retained guard reader

`RetainedGuardReader` is a narrow, read-only prerequisite under review. It is not
called by normal messaging or enrollment; the app still cannot activate a fresh
post-global-wipe epoch. `readFrozen` requires an existing directory freeze, an exact
production guard namespace (`chat-index-claims`, `station-v2-pads`, or a production
`chat-<20 hex>-pads`) and a caller-supplied SHA-256 ciphertext commitment. It never
opens an operational contact/index/draft vault, creates a key, invokes AtomicFile
recovery, or provides an `ignoreFreeze` flag. Missing files/keys never become empty
state. A commitment supplied after cleanup is only an observation, not provenance.

The adapter opens only a regular non-symlink base file, refuses any backup/new
variant, bounds ciphertext to 1 MiB, matches the commitment and authenticates the
existing v2 AES-GCM vault envelope with its existing Keystore key. A fixed-depth
strict streaming UTF-8/JSON schema rejects duplicates, unknown fields, coercions,
invalid owners/IDs, trailing data and invalid counters/capacities. Each record is
bounded to 4096 map entries across all its maps; a counter and capacity entry each
consume one entry. Parsed results contain only immutable hash/owner/reservation
metadata; decrypted byte buffers are cleared. Managed runtime copies remain a limit.

Cohort consistency requires one claims record, unique guard namespaces, declared
known owners, matching pad ownership in both claims and ledgers and at most 4096
ledger pad entries in total. It does not establish complete discovery, guard
provenance, immutable-cohort persistence, epoch capabilities or safe activation.
Those require the lifecycle manifest transaction; no validator return value may
substitute for that authority. Missing room-only owners in the declared scope also
fail. Existing guard versions other than schema 2 fail closed.

Tests use actual Android Keystore ciphertext, altered valid older snapshots,
missing file/key combinations, AEAD tampering, side variants, symlinks, oversized
files, parser ambiguities, budget boundaries, unknown/mismatched owners and unchanged
file/key evidence. The exclusive global cold-start fixture additionally checks that
retained guards still authenticate after restart and operational-key cleanup. Its
v1 reference hashes are test observations, never retrospective enrollment evidence.
Latest-head results belong to the implementation PR. Independent review remains open.
