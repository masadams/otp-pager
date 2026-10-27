# Authenticated terminal evidence — initial-generation implementation

Status: implementation candidate, not independently reviewed or production-ready.
This is a **local storage record**, not the peer revocation protocol. It supports
one initial terminal lineage while enrollment remains disabled. It does not add an
ACTIVE phase, epoch capabilities, a fresh-enrollment button or a release claim.

## Purpose and authority

`retirement-evidence-v1.bin` binds a random 128-bit cleanup generation, a complete
bounded root inventory and exact retained-guard ciphertext commitments to one
non-exportable Android Keystore HMAC-SHA256 key. It lives in noBackupFilesDir.
The key alias is `otp-pager-terminal-` plus the first 32 lowercase hex digits of
SHA-256 of that directory's absolute path. The alias contains no message secrets.
Moving the app directory can make this key unavailable and leaves the state frozen;
there is no key/path migration or automatic replacement.

A lifecycle key or any base/backup/new record variant is a terminal latch even when
the main record is missing. A key-provider error without an independently observed
intent blocks access and does not initiate destruction. Existing legacy intent or
a record/key still permits best-effort cleanup despite evidence authentication errors.
A fresh probe runs before Application recovery; normal access caches only confirmed
absence to avoid repeated Keystore calls. Creation invalidates that cache first.
External coordinated filesystem/Keystore rollback remains outside the guarantee.

Fresh capture requires an in-process ticket from an initial authorized backend
submission, after global freeze and before cleanup. No existing legacy intent,
evidence file/variant, or lifecycle key can be overwritten to mint a new ticket.
Key creation is permitted only on this path. Reads and recovery never create keys.
Cleanup closes the capture window and invalidates all pending tickets before any
work; sealing consumes its exact live ticket before I/O. Restart cannot reissue it.
There is no v1-to-empty migration or retrospective evidence capture.

## States

| State | Meaning | Allowed next step |
| --- | --- | --- |
| INTENT (0) | Authenticated terminal intent; guard provenance not established | A single live initial ticket may seal before cleanup; otherwise remain INTENT |
| SEALED (1) | Complete supplied inventory/guard commitments authenticated before deletion | Recovery may observe successful matching cleanup and record CLEANED |
| CLEANED (2) | Matching cleanup was observed at a commit boundary | Revalidate current guards and actual local cleanup whenever used |

CLEANED is historical evidence, not a reusable authorization token or current erasure
proof. `verifyCompleted` checks authentication, unchanged complete guard scope and
commitments, CLEANED phase, and actual local cleanup observation. Restored keys/files,
new roots, missing/old/corrupt guards or record variants make this fail. The ordinary
cleanup journal must have exactly the authenticated root scope, so a partial restore
that also loses an old retirement marker cannot bless an underdeclared journal. It still does
not activate enrollment. The eventual confirmation must bind this generation and
manifest commitment; that confirmation/capability transaction is not implemented.

An interrupted INTENT can never become SEALED during startup, even if surviving
guards appear valid. A failed capture does not prevent local destruction. Root scope
growth is cleaned independently but cannot be silently appended to an earlier seal.
A corrupt/missing evidence record is preserved as failed evidence; it is not repaired
or replaced with a permissive state. Local destruction and enrollment eligibility
are deliberately separate outcomes.

## Exact binary envelope

All numeric lengths/counts are unsigned, big endian. Strings are length-prefixed
ASCII without NUL. There is no JSON, optional extension, duplicate field or alternate
ordering. Maximum envelope: 512 KiB including the 32-byte tag.

| Field | Encoding |
| --- | --- |
| Magic/version | 8 literal ASCII bytes `OTPTERM1` |
| Phase | 1 byte, only 0/1/2 |
| Generation | 16 random bytes |
| Root count | 2 bytes, at most 4096 |
| Roots | Each: 1-byte length followed by production root ASCII; strictly increasing |
| Guard count | 2 bytes, at most 4096 |
| Guards | Each: 1-byte namespace length, ASCII namespace, 32-byte SHA-256 ciphertext commitment; increasing namespace order |
| Authentication | HMAC-SHA256 over all preceding bytes, 32 bytes |

Only `station-v2` / `chat-<20 lowercase hex>` roots and `chat-index-claims` /
`<root>-pads` guard namespaces are supported. Strings are 1–40 bytes. INTENT has zero
roots/guards. SEALED/CLEANED include `station-v2`, exactly one claims guard, unique
root/guard entries and every ledger's owner root. Unknown phase/version, malformed
counts, non-ASCII, invalid IDs, duplicate or reordered entries, truncation, extensions
and trailing bytes fail closed. The decoder re-encodes to verify canonical bytes.
The envelope's tag is checked before interpreting its bounded payload.

Payloads contain no names, plaintext, pad bytes, device/mailbox credentials or remote
commands. Ciphertext commitments and random roots remain metadata with a deliberate
retention cost. HMAC/Keystore/SHA-256 are conventional cryptographic dependencies;
this is not an information-theoretic or unconditional quantum-security claim.

Writes use AtomicFile, file synchronization, exact readback/authentication and directory
synchronization. A failure after finalization never calls failWrite and removes the
terminal base. A surviving side variant is ambiguous and stays frozen; this revision
does not pick the most permissive backup or provide a repair/reset button.

## Inventory and capture contract

1. Freeze all application access and cancel stale owners before capture.
2. Attempt the authenticated INTENT (key/file failures still allow ordinary cleanup).
3. Independently enumerate disk names and Keystore aliases under a shared 4096-entry
   source budget. Partial enumeration fails evidence capture, never yields empty scope.
4. Require an existing index file/key footprint and an authenticated claims record.
   The index may be corrupt; its names/plaintext are never opened. Missing footprints
   or claims are not treated as proof of an unused installation. This conservative
   bootstrap intentionally leaves some empty/legacy states ineligible.
5. Discover exact guard filenames/aliases, reject unexpected retained identity keys,
   authenticate each bounded guard, include authenticated historical owners in scope,
   and enforce total byte/entry budgets incrementally. Reauthenticate the resulting
   commitments/cohort and require unchanged source inventories before sealing.
6. Persist SEALED before invoking ordinary root/index destruction. Include all sealed
   roots in durable cleanup scope, even owners that now have no operational artifacts.
7. Continue all ordinary cleanup despite evidence failures. Only matching complete
   cleanup plus current guard/scope and actual key/file observation can become CLEANED.

Guard reads retain their 1 MiB/file, 8 MiB/cohort and 4096-map-entry bounds. Capture
uses two bounded authenticated passes, up to 16 MiB of guard ciphertext processing,
not an unbounded directory decrypt. Ledger and claims ownership must agree in both
directions; room-only owners are retained as terminal roots too. Ordinary vault/index
plaintext, pads and keys are never copied to the evidence file.

## Evidence and remaining gates

JVM codec tests cover canonical phase roundtrips, all truncations of a fixture,
ordering, namespaces, sizes, duplicate/invalid counts and unsupported phases. Real
Android Keystore tests cover authenticated INTENT, tampering, ambiguous variants,
missing-key and key-only states, no replacement and refusal to capture legacy intent.
The exclusive disposable-emulator host matrix clears only test app data between
scenarios and force-stops between phases: SEALED restart, actual submit/restart,
failed capture, INTENT-only interruption, older guard restoration, key-only restart,
and scope-growth cleanup. Existing ordinary and v1 global fixtures still run first.
Exact latest-head CI results belong to the stacked implementation PR.

Not covered by these tests: physical power loss, actual disk-full/fsync/provider
outages at every instruction, coordinated OS rollback, firmware/flash erasure or
independent review. Future work must provide a reviewed multi-epoch chain, immutable
cohort migration/lifetime limits, scoped read capabilities during active enrollment,
new index/claims-key staging, abandonment recovery and confirmation-bound activation.
History-key migration and the independently reviewed peer protocol remain separate.
