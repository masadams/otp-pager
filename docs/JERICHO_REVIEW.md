# Jericho adaptation and interoperability review

Upstream reference: https://github.com/joshua-m-david/jerichoencryption

We intend to reuse concepts and, if feasible, interoperate with the existing protocol. We have **not** yet imported upstream code or validated its security.

Before porting:
1. Pin upstream revision and read the actual LICENSE and copyright notices.
2. Document key import/export format, packet layout, authentication, counters, and relay API.
3. Build known-answer tests against the upstream implementation.
4. Review authentication construction, replay handling, nonce/counter/offset management, and error paths.
5. Review random-number generation and claims of information-theoretic security.
6. Decide whether exact wire compatibility can be maintained safely; document any breaking changes.
7. If copying code, comply with upstream copyleft/attribution and choose an appropriate project license.

**Never assume a bespoke authentication design is secure merely because the OTP encryption step is mathematically sound.**
