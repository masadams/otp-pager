# Current transport: v0.2 AES-GCM

The original OTP sketch below is future work. Current shipped transport is a
versioned AES-GCM prototype; see [HARDENING.md](HARDENING.md).

Frame, in network byte order: `version=2 (1 byte) | sender=A/B (1 byte) |
counter (8 bytes, positive signed-long range) | random id (16 bytes) |
random GCM nonce (12 bytes) | ciphertext | GCM tag (16 bytes)`.
The complete 38-byte header is authenticated AAD. Keys are HKDF-SHA256:
extract salt `OTP Pager / protocol v2`, input 32-byte pairing secret; expand info
`message:A` or `message:B`, length 32 bytes. Payload is UTF-8 JSON: text
`{"kind":"text","text":"..."}` or delivery `{"kind":"receipt","for":"<32 hex id>"}`.
A shared deterministic vector is in `test-vectors/protocol-v2.properties`.

The recipient authenticates before advancing its persisted 4,096-counter replay
window. Inbox/cursor/replay state/receipt queue commit together in the local vault.
Send counter allocation and wire ciphertext commit before upload; retries reuse
that exact wire packet. Relay API remains `/v1/mailbox/<room>` for compatibility,
with `protocol:2`, durable `epoch`, and `limit=1..50` pagination on GET. Duplicate
POST wire bytes return the existing sequence with HTTP 200; new packets return 201.
Existing schema-v1 relay volumes migrate without dropping queued traffic.

---

# Protocol sketch — NOT IMPLEMENTED OR AUDITED

This is a requirements sketch, **not a finalized cryptographic protocol**.

## Pairing
1. Devices meet physically.
2. Display and verify peer identities / pairing confirmation over an authenticated local channel.
3. Transfer independent A→B and B→A key pools, plus separate authentication material.
4. Both sides verify the exact transfer transcript and confirm successful import before enabling communication.
5. Never permit remote contact enrollment.

## Messaging
- Each direction maintains its own monotonically advancing, durable key offset.
- Allocate a disjoint key range *before* encryption/transmission, with crash-safe persistence.
- Encrypt plaintext with equal-length truly random OTP bytes (XOR).
- Use an independently keyed, reviewed authentication scheme. **Do not use OTP bytes as an ad hoc MAC key.**
- Authenticate the packet, peer, direction, offset, and protocol version; reject replay.
- Padding and cover traffic are optional future features and must be budgeted.
- Acknowledgments must not cause previously allocated key ranges to be reused.
- Message loss can waste pad ranges; safety is more important than efficiency.

## Initial pad budget
Nominal 1 MiB per pair, split equally by direction (512 KiB each), before any separate authentication-key allocation. Exact message capacity depends on final packet format.

## Relay
A relay stores opaque authenticated ciphertext envelopes, with no plaintext or keys. Transport confidentiality and authentication are still required. Metadata minimization is a separate problem.

## Compatibility gate
Do not assert compatibility with Jericho until independent message vectors and actual cross-client exchange succeed.

