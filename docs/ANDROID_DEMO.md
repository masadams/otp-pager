# Android offline demo v0.1

This is a **single-device educational demonstration**, not an operational encrypted messenger.

## What works
- Type a message.
- Generate fresh per-message bytes with Android SecureRandom.
- XOR plaintext with equal-length bytes.
- Authenticate ciphertext with independently generated HMAC-SHA256 key.
- Simulate one-time opening by deleting the receiver's in-memory key references.
- Disable Android application backup and screenshots of this activity.

## What does NOT work
- No two-device communication, physical pairing, 1 MiB key pool, durable offsets, relay, or Jericho interoperability.
- No proof that SecureRandom output is full-entropy true OTP material.
- No forensic deletion guarantees; text remains in UI memory, input widgets and potentially system-managed memory.
- No independently reviewed protocol.

## Building
GitHub Actions runs the Android debug build on the PR and uploads `otp-pager-debug-apk` as an artifact after a successful run. The workflow uses Gradle 8.9, Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Java 17 and Android API 35. No local Android SDK was available to validate compilation here.

## Next steps
1. Inspect Jericho v2.1 source, pin commit, and create protocol test vectors.
2. Implement physically authenticated two-device pairing.
3. Implement crash-safe, never-reused per-direction key consumption.
4. Add relay transport and independently validate interoperability.
