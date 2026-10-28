# Two-device test — v0.2

Experimental AES-GCM, not OTP. Read [HARDENING.md](HARDENING.md).

1. Pull/redeploy the relay in Portainer, preserving its data volume and token.
2. Install the v0.2 APK on both devices. If debug signing differs, uninstall the
   old APK; then use a NEW pairing secret on both devices.
3. Open Link. Enter the same HTTPS hostname and relay token on each device. Generate
   the pairing secret on A and transfer it privately in person to B. Choose opposite
   station roles, Save & connect, and compare the pair-check code in person.
4. Send A → B and B → A. Keep both screens open or tap Sync. Sender statuses:
   Queued (durable local outbox), Relayed (server accepted), Delivered (peer saved).
   Delivered does not mean a person read it.
5. Take A offline and send. Close/reopen A, reconnect and Sync. Verify a single copy
   reaches B and eventually gets a delivery receipt. No background sync is expected.
6. Restart the relay between transmission and reception. Verify pending delivery.
7. Clear history, close/reopen and Sync; old traffic should remain suppressed.
8. Check Android e-ink text contrast, keyboard behavior, partial refresh, ghosting
   and battery usage. Idle polling should not reconstruct the conversation view.

Local history is encrypted at rest and retained up to 24 hours. Changing/resetting
pairing requires a fresh shared secret; active vault rollback is unsupported.
Do not use sensitive communications before independent review. Cloud emulators
can observe everything on their hosted endpoint, including pairing secrets.
