# Confirmation-bound enrollment staging

Status: implementation candidate. **No ACTIVE state, normal messaging access, fresh
enrollment UI or release authorization is implemented.** Prepared storage is empty
and remains globally blocked. This slice adds the construction/recovery transaction
and captured-owner boundary needed before actual enrollment can be enabled.

## Entry and evidence binding

`EnrollmentStaging.confirmation` requires current verified CLEANED terminal evidence,
exact authenticated cleanup scope, unchanged retained guards and actual local key/file
cleanup. It refuses an existing non-abandoned attempt, missing file/key pairs, unknown
staging artifacts and abandoned objects whose keys/files were restored.

The one-use in-process confirmation binds the terminal generation, exact SHA-256 of
the authenticated terminal envelope, and app directory. Cancellation removes it;
preparation consumes it before I/O and rechecks the proof. A forged token object,
stale/repaired proof or process restart cannot resurrect consent. The future recovery
screen must explicitly obtain/cancel this confirmation according to its own Activity
generation; this build has no caller in normal UI and cannot silently enroll.

## Durable attempt and storage

Each attempt has a random 128-bit epoch and its own permanently retained non-exportable
HMAC-SHA256 authentication/reservation key. The alias is `otp-pager-enroll-` plus 32
hex characters of the absolute-directory SHA-256, a hyphen, and the 32-hex epoch.
The record is `enrollment-stage-<epoch>.bin` in noBackupFilesDir. Neither key nor
record is deleted when an attempt is abandoned. Up to 32 attempts are supported;
capacity fails closed, with no truncation, nonce reuse, reset or compaction shortcut.

There is no mutable head pointer: complete file/key discovery must agree. A missing
record/key, unknown reserved prefix, extra unregistered `enr-` metadata, malformed
record, variant or duplicate namespace blocks new preparation. A prior record alone
cannot hide a later reserved epoch whose Keystore key still exists. Coordinated loss
of all corresponding OS/Keystore state is outside the rollback guarantee.

Namespace allocation derives `enr-<first 20 epoch hex>` for the index, its `-claims`
sibling for the writable delta, and `chat-<same 20 hex>` for the empty contact root.
Allocation checks all existing files/keys, terminal roots, prior attempts and namespace
prefix collisions. No operational root vault/key, pads or identity is created.

Order:

1. Revalidate and consume explicit confirmation; reserve a never-used epoch.
2. Invalidate the confirmed-absence cache and create its HMAC reservation key once.
3. Durably authenticate PREPARING before either fresh AES key/file is created.
4. Create only the fixed empty index (`Chat 1`) and empty claims delta, each with a
   new AES-256-GCM Keystore key and existing v2 vault envelope/AAD. No caller-provided
   plaintext, names, credentials, histories, counters, peers, pads or old deltas are copied.
5. Authenticate exact empty plaintext/readback, hashes, file/key presence and absence
   of root/metadata identity artifacts. Recheck terminal evidence/commitment.
6. Commit PREPARED last. This records preparation only and grants no normal access.

The previous guard cohort stays encrypted under its original retained keys and
commitments. An empty delta is never a replacement for those exclusions. Actual
activation must consult every retained cohort plus the new delta; that scoped active
reader and forward epoch chain remain unimplemented.

## Exact record format

Canonical fixed payload, 137 bytes, followed by a 32-byte HMAC-SHA256 tag (169 total):

| Field | Bytes |
| --- | ---: |
| Literal ASCII `OTPSTG01` | 8 |
| Phase: PREPARING=0, PREPARED=1, ABANDONED=2 | 1 |
| Epoch | 16 |
| Terminal generation | 16 |
| Terminal-envelope SHA-256 | 32 |
| Staged index ciphertext SHA-256 | 32 |
| Staged claims ciphertext SHA-256 | 32 |

PREPARING/ABANDONED have zero slot hashes; PREPARED requires both nonzero. Terminal
commitment is nonzero. All lengths are exact, no trailing extension or ACTIVE ordinal
is accepted, and tags verify before payload interpretation. The file epoch must equal
the authenticated payload. Source enumeration shares a 4096-entry file/alias budget.
Staged ciphertext is at most 8192 bytes per slot and must authenticate to the exact
internally generated empty document. Reads never create keys or repair variants.
Writes use AtomicFile, file synchronization, verified readback and directory sync;
post-finalization failure never calls failWrite to delete finalized state.

## Captured owner generation

SecureStore constructors capture an immutable capability. Existing/default constructor
ABIs are retained for signed upgrade compatibility. This build has only LEGACY and
BLOCKED capabilities, with no ACTIVE capability. Every load/save/reset, identity
operation and lease callback checks its captured generation; sibling stores inherit
the parent's capability rather than capturing a newer one. Store-owned identity
reset also checks this boundary while retaining per-contact cleanup semantics.

An owner constructed while blocked remains blocked even if external state deletion
later makes a directory look legacy. Old owners cannot acquire a future active epoch
through a mutable “current generation” lookup or by creating a sibling. Actual active
epoch matching, namespace allowlists, leases/NFC/UI publication and new-index selection
still need the subsequent activation transaction. Raw legacy identity constructors
remain for binary compatibility/test fixtures; production stores use scoped callbacks.

## Interruption and abandonment

Startup inspects stages before ordinary global root discovery. It never resumes
PREPARING encryption, recreates a missing key, or automatically activates PREPARED.
Interrupted PREPARING is abandoned. Valid PREPARED stays staged; missing/altered slots
or unexpected root keys cause its authenticated scope to be abandoned instead.
ABANDONED scopes are swept again to remove restored files/keys.

Abandonment independently deletes only that attempt's new index/delta/root keys and
file variants, verifies absence and synchronizes the directory before committing
ABANDONED. Failures stay blocked for retry; the reservation key/record survives. A
retry requires new confirmation and a new epoch. No ordinary `.retired` root marker
is created for a root that was never active: its reservation lives in the authenticated
attempt and key, keeping it outside the earlier cleanup's sealed root scope.

A key-only/record-only attempt, invalid tag or ambiguous variants cannot be rebuilt
into a permissive record. Unknown state remains blocked; this revision has no orphan
reconstruction, repair or reset UI. Corruption may leave encrypted **empty** staging
artifacts awaiting review; old local cleanup still proceeds independently. Before any
ACTIVE implementation, emergency wipe integration must explicitly retire the active
lineage and staged/new metadata as part of its defined scope.

Normal messaging behavior and relay packets are unchanged. Keystore/HMAC/AES/SHA-256
are conventional dependencies, not unconditional security. Directory relocation,
provider outages, physical flash/memory copies and coordinated OS rollback remain
limits. No physical-power-loss or independent/two-device review is implied by CI.

## Tests and next gate

Pure codec tests cover canonical phases, lengths, every truncation, extensions,
unknown phase/version and phase/hash constraints. A real Keystore test covers captured
legacy/blocked owners, identities (including reset), operation leases and inherited
sibling capabilities. The exclusive host matrix force-stops across prepared restart,
explicit abandonment/new epoch, interrupted encryption, restored older attempts,
proof-change/cancelled/forged consent, key-only records, tampering, variants, incomplete
file/key pairs and unknown metadata. Only disposable emulator app data is cleared.
Existing signed-upgrade and all three earlier restart fixtures remain mandatory.

Next: reviewed ACTIVE capability/allowlist publication, immutable retained-cohort
access during normal operation, current-index routing and repeat-wipe/multi-epoch
transactions, then explicit user-facing confirmation/recovery UI and two-device
physical-pairing evidence. PREPARED must never be treated as active by deleting old
terminal markers or swapping a default index.
