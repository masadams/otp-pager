# Strict chat v1 — signed short lease plus check-in deadline

Status: **implemented for review, owner decision 2026-10-28: "combine both"**. It combines
the signed 60-second unlock lease below with the relay check-in deadline from
[peer wipe Appendix A](PEER_WIPE_PROTOCOL.md). It isn't independently reviewed and hasn't been
tested on two physical phones. Normal chats are unchanged.

## As built

- **Choosing it:** strict is chosen per new pairing on both phones: normal, or strict with a
  12-hour, 3-day or 14-day check-in. Pairing protocol v7 carries the choice in both hellos; it
  must match and it is bound into the compared code. Renewal keeps the chat's mode.
- **Enrollment at commit:** each phone
  - claims its own share room, derived from the control credential;
  - pins the relay's authority key (`GET /v1/strict/authority`, over HTTPS at pairing; trust on first use);
  - enrolls a fresh 32-byte share bound to its non-exportable device key
    (`PUT /v1/share/<room>`, stored once, never replaced);
  - seals its vault under an inner AES-256-GCM layer keyed by HMAC(share, chat root).
  Outside that layer only routing and public keys remain: endpoint, share rooms, control
  capability, authority key, role and generation. A relay without share support fails the
  pairing rather than creating a normal chat.
- **Opening the chat:** the phone signs an `OTPSTR01` UNLOCK with its device key and sends it to
  `POST /v1/share/<room>/unlock`.
  - The relay verifies the signature against the enrolled device key, then checks the
    authority and device fingerprints, a lease of at most 60 s, clock skew of at most 5 min,
    and nonce replay.
  - It answers with a GRANT signed by its authority key.
  - `StrictChatProtocol.PendingUnlock` accepts only the exact echo of the request, signed by the
    pinned authority.
  - The share is held **in memory only** for the rest of the lease. When the lease ends, or the
    app leaves the foreground, live engines drop their decrypted state, and an open conversation
    screen clears its messages and composer at once.
  - Leaving the foreground advances a lock epoch. An unlock reply that arrives afterwards is
    rejected and never caches the share.
  - The relay keeps every accepted nonce until its request window closes. When 32 are live it
    refuses new unlocks (429) rather than evicting one, so an accepted request can't be replayed.
- **While open:** every foreground check (about every 30 s) requests a new grant, which renews
  the lease and is also the relay **check-in**.
- **Check-in deadline:** if a share isn't unlocked within its deadline, the relay **retires** it:
  - The share is removed and a permanent tombstone (room plus enrolled device key) is kept.
  - The snapshot is rewritten, which resets the journal, so no live relay file
    (`mailboxes.json`, `journal.log`) still holds the share.
  - A retired room is never re-enrolled (410).
  - The next check or open gets **410 with a REVOKED frame signed by the pinned authority**,
    echoing that exact request (its nonce, chat generation, role and device). Only that evidence
    makes the phone wipe the chat locally (`PendingUnlock.acceptRevoked`).
  - A plain 404 (a reset relay, a wrong proxy route, a hostile answer) means *unavailable*: the
    chat stays locked and **nothing is deleted**. Without the share it can't be decrypted anyway.
  - A phone kept offline past the deadline can never open the chat again, even with a full
    storage copy.
  - **Not covered:** copies of the share in relay backups or volume snapshots, replaced file
    blocks on the server's disk, and anything an operator copied earlier. Retirement only
    removes live files. Back up relay volumes accordingly, or not at all for strict chats.
- **Wipes:**
  - wiping a strict chat (single or all) queues deletion of this phone's share, always, and of
    the friend's share when peer wipe is on;
  - a peer-wipe recipient also deletes its own share.
- **What strict chats don't do:** they skip drafts and background receiving. Strict text is
  never written to the ordinary draft store, including on Send and when a send fails. A copy an
  earlier build left there is removed when the chat opens. Messages wait on
  the relay (24 h), so open strict chats daily.
- **Code:**
  - `StrictChat` (inner layer, leases, unlock);
  - `StrictChatProtocol` (codec and grant guard from #48);
  - relay `server.mjs` (authority key in `authority-key.pem`, shares, unlock);
  - `StationEngine.establishStrict` and `checkInStrict`;
  - `PeerWipe.prepareShareDeletion`.
- **Tests:**
  - relay: the setup token never enrolls or unlocks; replay, wrong signer, long lease, clock
    mismatch, wrong authority and tampering are rejected; check-ins survive a restart and lapse
    past the deadline;
  - `StrictChatTest`: nothing secret outside the layer; lock on background; a fresh signed grant
    is needed; an impostor authority is refused; a lapsed share wipes the chat; wipe deletes the
    share; both phones must choose the same mode.

**Limits:**
- An unlocked phone, a compromised OS or RAM, screenshots and exports aren't protected.
- The relay operator can deny access and sees unlock timing.
- A seized phone that is put online before the deadline (or before a wipe deletes the share)
  can still be unlocked by whoever can operate the phone. The device key never leaves the phone,
  but a stolen storage image alone can't sign an unlock.
- The authority key is trusted on first use at pairing.
- Relay backups may retain shares.

The candidate notes below are kept from #48 for review history. Where they say "no inactivity
deletion", the owner's decision replaced that with the check-in deadline.

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
