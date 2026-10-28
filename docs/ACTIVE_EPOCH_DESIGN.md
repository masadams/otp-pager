# ACTIVE epochs, lineage and current-index routing — design (package B3)

Status: **design for review plus a pure decision core (slice B3a)**. No code path can
create an ACTIVE manifest; the app behaves exactly as before. Builds on
[the enrollment candidate](GLOBAL_ENROLLMENT_PROTOCOL.md),
[staging](ENROLLMENT_STAGING.md) and [terminal evidence](RETIREMENT_EVIDENCE_FORMAT.md).
Implemented format contracts override this document where they differ.

## Why steps 1–4 of the handoff are one design

An ACTIVE epoch changes what "frozen" means. Today `GlobalRetirement.isFrozen` is true
whenever any terminal evidence or staging artifact exists. After activation those records
stay forever (they are the provenance of the new epoch), so freeze must be decided from
the **lineage**, not from presence. The same lineage decides which index a `ChatBook`
opens, which namespaces an owner may touch, and what a second wipe must cover. Designing
capability, routing, guard cohorts and repeat wipes separately would let them disagree.

## Lineage

```
legacy ──T0 (OTPTERM1, CLEANED)──▶ E1 (stage PREPARED + manifest) ──T1 (OTPTERM2, bound E1, CLEANED)──▶ E2 …
```

- **T** — authenticated terminal evidence. T0 is today's `OTPTERM1`, implicitly bound to
  `legacy`. Later wipes need a successor format (`OTPTERM2`, slice B3d) that names the epoch
  it terminates and uses a per-generation file and key, because `OTPTERM1` allows exactly
  one record and key per directory and only legacy guard namespaces.
- **Stage** — existing `OTPSTG01` attempt bound to a terminal generation and commitment.
- **Manifest** — new `OTPACT01` record (below). It exists only for an epoch whose stage is
  PREPARED and whose staged artifacts verified; writing it is the activation commit.

`EpochLineage.resolve` (implemented, pure) walks the chain from `legacy`:

| Observation | Result |
| --- | --- |
| Nothing present | `Legacy` |
| Any unreadable/unauthenticated/variant record | `Frozen` |
| v1 legacy journal without terminal evidence | `Frozen` (v1 cannot gain provenance) |
| Terminal for the current link not CLEANED | `Frozen` (wipe in progress or incomplete) |
| CLEANED terminal, no manifest bound to it | `Frozen` (awaiting explicit enrollment) |
| More than one terminal or manifest for one link | `Frozen` (never pick the most permissive) |
| Manifest without matching PREPARED stage, hash mismatch, wrong predecessor commitment | `Frozen` |
| Other stages for a link not ABANDONED | `Frozen` |
| Any record not consumed by the chain | `Frozen` |
| Chain ends at a manifest with no terminal bound to its epoch | `Active(manifest)` |

There is no head pointer and no "latest wins": restoring an older manifest, terminal or
stage either still forms one consistent chain or freezes. Chain length is bounded by the
existing 32-attempt staging capacity.

## `OTPACT01` manifest record

File `epoch-active-<epoch>.bin`, authenticated with the epoch's **existing** permanent
staging reservation key (HMAC-SHA256). The distinct 8-byte magic gives domain separation
from `OTPSTG01` payloads under the same key. Canonical payload:

| Field | Bytes |
| --- | ---: |
| ASCII `OTPACT01` | 8 |
| Epoch | 16 |
| Predecessor terminal generation | 16 |
| Predecessor terminal-envelope SHA-256 | 32 |
| Staged index ciphertext SHA-256 (from PREPARED) | 32 |
| Staged claims ciphertext SHA-256 (from PREPARED) | 32 |
| Sequence (u32, ≥ 1) | 4 |
| Root count (u16, 1..512) | 2 |
| Roots: 10 binary bytes each (`chat-` + 20 hex), strictly ascending | 10 × n |

The epoch's initial root `chat-<first 20 epoch hex>` must be listed. No hash may be zero.
Decoding rejects truncation, trailing bytes, unknown magic, unsorted or duplicate roots,
and anything whose re-encoding differs. Roots stay listed after contact retirement, so a
later wipe knows the full scope.

**Append rule:** a new contact root is written into the manifest (sequence + 1, verified
readback, directory sync) **before** its first key or file exists. A manifest rolled back
to an earlier sequence therefore only *removes* permission from newer roots (fail closed);
it can never grant one.

## Capability and namespace allowlist

`VaultGeneration.Capability` becomes `Legacy | Blocked | Active(epoch)` (slice B3b). Every
owner keeps capturing once at construction; siblings, identities and leases inherit it.
`requireCurrent(directory, captured, namespace)` passes only when:

- `Legacy` and the lineage resolves to `Legacy`; or
- `Active(E)`, the lineage resolves to `Active` for the same `E`, and `EpochScope.permits`
  (implemented, pure) accepts the namespace: `enr-<E20>`, `enr-<E20>-claims`, or any
  namespace whose root is listed in the manifest (`chat-<x>`, `chat-<x>-draft`,
  `chat-<x>-pads`, and their `-device` identity aliases).

`station-v2`, `chat-index`, `chat-index-claims`, other epochs' namespaces and unlisted
roots are never permitted in an ACTIVE epoch. Lineage resolution is cached per directory
under `VaultLifecycle.lock` and invalidated by every in-process lifecycle writer (freeze,
evidence, stage, manifest append); external filesystem writes are the documented
compromised-endpoint boundary. Public `SecureStore`/`DeviceIdentity` constructor ABIs used
by the signed-upgrade fixture stay unchanged.

## Current-index routing

`ChatBook(context)` resolves its namespaces from the lineage instead of defaulting to
`chat-index`: `Legacy` → `chat-index`/`station-v2` as today; `Active(E)` → `enr-<E20>`,
`enr-<E20>-claims`, initial root `chat-<E20>`. In ACTIVE, a missing or unreadable index
freezes; it is never recreated, and there is no fallback to `chat-index`.
`freshNamespace` appends the new root to the manifest before returning it.

## Retained guard cohorts during messaging (slice B3c)

`PairingClaims` in an ACTIVE epoch checks, for every room and pad claim: every retained
cohort listed by the terminal records in the chain (read through an extended
`RetainedGuardReader` that verifies each guard against its sealed commitment, read-only,
never creating keys), plus the epoch's writable claims delta. A hit in any retained cohort
is refused. A retained guard that is missing, altered or unreadable freezes the epoch; it
never reads as empty. Retained cohorts are never written or erased by ordinary operations.

## Repeat wipe (slice B3d)

Wipe-all in `Active(E)`: latch freeze for `E` in-process first, then write `OTPTERM2`
INTENT bound to `E` under a new per-generation key; capture the epoch's claims delta and
its roots' pad ledgers as a new sealed cohort; destroy listed roots, discovered unlisted
`chat-*` artifacts and `enr-<E20>` index keys/files; keep every cohort and record. Boot
recovery distinguishes scopes by lineage: B1's legacy rescan must never run against an
ACTIVE epoch's contacts without that epoch's terminal intent.

## Activation transaction (slice B3e)

Under the lifecycle lock after explicit local confirmation: verify the CLEANED terminal,
its commitment and every retained cohort; verify the PREPARED stage's staged artifacts;
write `OTPACT01` sequence 1 listing only the initial root as the **last** commit; verify
readback; invalidate caches. Only owners constructed afterwards capture `Active(E)`.
Interruption before the manifest commit leaves PREPARED (frozen, retryable); after it, the
lineage is `Active`. Background receiving stays off; physical pairing is required.

## B3b implementation notes

- `EpochManifests` reads/appends authenticated manifests; `create` (sequence 1) has no
  production caller until B3e. `EpochState.observe` turns real evidence, staging and
  manifest files into an observation; any read failure is `unreadable` → Frozen.
- **Behaviour without a manifest is unchanged.** `isFrozen`, `capture` and `ChatBook`
  routing consult the lineage only when an `epoch-active-*` file exists; otherwise they
  apply exactly the previous presence rules with no extra reads. A stray manifest file now
  counts as staging presence (fail closed).
- An in-process freeze latch always wins over an ACTIVE lineage.
- Lineage reads are uncached. B3e must add a validated cache before ACTIVE makes them a
  per-operation path, and must clear or reconcile the in-process freeze latch left by the
  preceding wipe (simplest: activation completes, then the process restarts).
- Interim guard for B3d: interrupted-staging recovery refuses to abandon a stage that has a
  manifest (an active epoch's index/claims delta are live state and retained guards); the
  cleanup reports incomplete instead of destroying them.
- End-to-end ACTIVE routing needs a real CLEANED terminal record, which only a full global
  wipe produces; it is tested in B3e's exclusive host fixture, not in shared-process tests.

## Slice plan and gates

| Slice | Content | Reachable by users? |
| --- | --- | --- |
| **B3a (this PR)** | Design; `ActiveManifestCodec`, `EpochLineage`, `EpochScope` pure logic + JVM tests | No |
| **B3b (#19)** | Sealed capability, namespace-aware `requireCurrent`, lineage-based `isFrozen`, `ChatBook` routing, manifest append; adapters read real records | No (no manifest writer) |
| B3c | Active retained-cohort reader and `PairingClaims` enforcement | No |
| B3d | `OTPTERM2`, per-generation evidence keys, epoch-scoped wipe and recovery | No |
| B3e | Activation transaction; interruption tests at every boundary; two full wipe→enroll cycles refusing old material | Backend only |
| B3f | Confirmed fresh-enrollment UI on the global recovery screen; compact-screen checks | Yes |

Each slice needs its own tests; B3e/B3f additionally need the restart fixture matrix and
physical two-device checks before any release. Coordinated OS/Keystore rollback remains
outside the guarantee.
