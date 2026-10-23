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
