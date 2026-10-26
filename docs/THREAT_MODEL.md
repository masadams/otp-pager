# Threat model (draft)

Current v0.14 source inventory, trust boundaries and prioritized findings are in
[SECURITY_INVENTORY_V014.md](SECURITY_INVENTORY_V014.md). The active
[development plan](SECURITY_DEVELOPMENT_PLAN.md) defines acceptance invariants and
review gates. This earlier sketch is retained as background, not completion evidence.

## Intended protections
- Passive network interception, relay operators reading ciphertext, and unauthorized remote pairing.
- Replay, reordering, message tampering, and accidental key reuse.
- Unnecessary metadata and outbound device telemetry.

## Not guaranteed
- Safety after a device or its operating system is compromised.
- Erasure of historical plaintext from flash, backups, screenshots, accessibility services, or e-ink persistence.
- Traffic-analysis anonymity merely because ciphertext uses an OTP.
- Information-theoretic confidentiality from a CSPRNG-expanded seed.

## Trust boundaries
1. Physical pairing ceremony and transfer medium.
2. Hardware entropy source, extraction, and testing.
3. Key storage, monotonic consumption state, rollback/crash behavior.
4. Message authentication and relay transport.
5. Device firmware, Android OS, and build/update supply chain.

## Design invariants
- Separate independent pads for A→B and B→A.
- Never reuse a byte of pad material, including after power loss.
- Authenticate before displaying plaintext.
- Reject duplicates and stale messages without consuming an inconsistent key range.
- Never log pad bytes, plaintext, or secrets.
- Fail closed on storage corruption, counter rollback, or ambiguous delivery state.
- Never put actual secrets in Git or test fixtures.

## Open questions
- Jericho interoperability requirements and authenticated message format.
- Whether the available hardware entropy source can support full-entropy OTP claims.
- Safe durable allocation/reservation of pad offsets on Android and ESP32 flash.
- Exact authenticated physical-transfer mechanism.
- Threat-model review of pairing, relay identifiers, and message-length leakage.
