# Roadmap

## Gate 0 — repository and security specification
- [x] Public repository
- [x] Threat-model draft and design invariants
- [x] Jericho review checklist
- [ ] Pin and inspect Jericho upstream source/license

## Gate 1 — reproducible Android skeleton
- [ ] Kotlin/Gradle Android project, offline build in GitHub Actions
- [ ] Compose or native minimal pager UI
- [ ] No INTERNET permission until transport is implemented
- [ ] Unit tests for storage state machine and protocol vectors

## Gate 2 — physical pairing
- [ ] Authenticated local exchange with human verification
- [ ] 1 MiB key allocation, direction separation, integrity checks
- [ ] Import/export and explicit destructive re-pairing

## Gate 3 — messaging
- [ ] Authenticated packets and replay protection
- [ ] Crash-safe reservation and single-use pad accounting
- [ ] Interoperability tests against Jericho
- [ ] Minimal relay, deletion/retention controls

## Gate 4 — handheld
- [ ] Verify T-Deck Pro board revision and sensors
- [ ] Compare stripped ThistleOS with single-purpose ESP-IDF
- [ ] Test hardware entropy, network traffic, boot/update chain
- [ ] Independent security review before real-world use
