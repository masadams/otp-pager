# Strict chat v1 — candidate protocol and unwired codec

Status: **not a usable strict-chat feature**. `StrictChatProtocol` implements only
canonical frames, pinned-signer verification and one pending unlock response guard.
No relay endpoints, strict vault envelope, pairing enrollment or UI are wired.
Depends on #44's acceptance contract. Python fixtures and Kotlin tests are produced
in the same development effort; they are not independent review.

## Fixed decisions and security boundary

One HTTPS authority per pairing, authenticated by a P-256 public key pinned during
physical pairing on both phones. Message transport may later fail over; unlock
must not. New physical pairings only for v1. Ordinary chats stay unchanged. No
inactivity-based deletion of relay shares. A missing share is an error, never a
request to recreate the same generation. A new physical pairing creates a new G.

The authority holds one random 32-byte share per device/role, neither an OTP pad nor
a message key. Each device also needs its local Keystore contribution to open its
strict vault. A fetched share and unwrapped vault key must never be persisted.
An unlock lasts at most 60 seconds from request creation using a monotonic clock;
background, screen lock, wipe, renewal and cold start lock immediately. No strict
content processing in background v1. Ordinary chats remain available offline.

The proposed storage construction and crash-safe integration are a **later PR**:
this codec does not establish offline protection. Full OS compromise, captured RAM,
previously exported content, a malicious authority and coordinated rollback remain
limitations. A static relay share copied by a compromised phone cannot be recalled.
The relay sees unlock timing and can deny access. A retained relay backup may retain
a deleted share or undo a tombstone; no forensic-erasure claim is made.

## Canonical signed frame: OTPSTR01

178 bytes, big-endian. No optional fields, trailing bytes or alternate encodings.

| Field | Bytes | Meaning |
| --- | ---: | --- |
| magic | 8 | ASCII `OTPSTR01` |
| kind | 1 | 1 ENROLL, 2 UNLOCK, 3 GRANT, 4 REVOKE, 5 REVOKED, 6 ENROLLED |
| role | 1 | ASCII A or B |
| generation | 16 | Fresh physically agreed pairing generation G |
| device | 32 | SHA-256 of enrolled canonical P-256 SPKI DER |
| authority | 32 | SHA-256 of pinned authority canonical P-256 SPKI DER |
| epoch | 8 | Positive signed-compatible authorization epoch |
| nonce | 32 | Fresh CSPRNG request nonce; response echoes it |
| issued | 8 | Positive Unix milliseconds for bounded requests |
| expires | 8 | Greater than issued, at most 60,000 ms later |
| share | 32 | Share only for ENROLL/GRANT; otherwise all zero |

REVOKE/REVOKED instead require issued=expires=0: terminal revocation is replayable
and must survive an offline dispatch backlog. Its authority is restricted to G.
Other kinds use the bounded timestamps. Requests use the enrolled device signature;
responses use the pinned authority signature. Sign all 178 bytes with existing
SHA256withECDSA/P-256; signature is separate DER (8–80 bytes, provider-validated).
Kind and magic provide domain separation. Verify signatures and pinned fingerprints
before acting. TLS is mandatory because frames may contain a share.

Proposed HTTP interface (not implemented): POST `/v1/strict/<G>/enroll`, `/unlock`,
`/revoke`, JSON `{frame, signature}` using canonical padded base64. Responses use
the same envelope. Reject unknown fields, wrong route/kind, excessive bodies and
noncanonical base64. All device IDs/keys and both roles are bound by the physically
approved enrollment transcript; a setup token alone never grants unlock authority.

## Server transition contract — not implemented

- Physical enrollment must bind G, both device keys, mode, authority key/URL and
  each role's share commitment. Start at epoch 1. Commit both enrolled roles before
  exposing a strict chat. Idempotent duplicate enrollment must match exactly;
  conflicting enrollment is rejected, never an overwrite. ENROLLED echoes request
  context with zero share. Enrollment authentication and transaction persistence
  must be supplied by the physical-pairing integration, not inferred from this codec.
- UNLOCK requires an active matching G/role/device/epoch, valid device signature and
  a currently valid request time window. Fail closed on clock mismatch. Persist a
  bounded nonce replay record before issuing GRANT; reject repeated nonces. GRANT
  echoes all request context and returns only that device's share.
- A valid REVOKE signed by either enrolled device terminally revokes **both roles**
  of G. Atomically remove shares and persist a permanent tombstone before REVOKED.
  REVOKED echoes the signed request context; it is an acknowledgement of terminal
  state, not an unlock epoch. No re-enrollment or unlock of a terminal G.
- Serialize unlock/revocation commits. A grant already issued cannot be recalled;
  its foreground lease bounds compliant-client use. Never promise instant erasure
  on a disconnected or modified phone.
- Bound enrollment, replay cache and tombstone capacity. Capacity exhaustion refuses
  work; it never evicts tombstones. Storage errors poison authorization until safe
  recovery. Missing server state must not use mailbox re-claim logic to recreate it.

## Client acceptance and lifecycle integration

`PendingUnlock` snapshots the canonical request; it accepts at most one signed GRANT
matching kind, G, role, device, authority, epoch, nonce and both timestamps. Both wall
clock validity and elapsed time since request creation must hold. A rollback below
issued, elapsed-clock regression, expiry, cancellation or second acceptance fails.
Invalid responses cannot consume the pending request. The caller must pin the
correct authority card, cancel pending objects on lifecycle changes, and keep any
returned share in memory only. These caller responsibilities are not wired yet.

Future storage/control integration must keep minimal wipe/control authority available
without unlocking content, cover it in retirement and epoch inventory, seal a terminal
REVOKE before local destruction, then dispatch independently. Local destruction must
never wait for authority access. Keep offline revocation status honest. Relay
acceptance is not proof that either phone erased content.

## Tests and remaining gates

`python3 scripts/strict_chat_vectors.py --check` verifies committed codec fixtures;
Android CI runs it. `StrictChatProtocolTest` consumes all six Python frames and checks
malformed lengths/fields, signatures with every byte altered, signer substitution,
wrong generation/device/role/epoch/nonce, replay, cancellation, expiry, clock rollback
and defensive request capture. Generated signing keys are synthetic JVM test keys.

Before calling strict chats implemented: relay persistence/restart/race tests; actual
vault/draft/pad-key offline tests; foreground lease/cache invalidation; stale response
and wipe tests; downgrade rejection; physical enrollment; signed-upgrade behavior;
API29/31/35 instrumentation; two-phone and power-loss evidence. Independently produced
vectors and protocol/storage review remain required before security claims.
