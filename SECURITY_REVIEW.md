
### v0.9 consumed pad retirement

Schema 5 saves authenticated retained payloads inside the existing Keystore-backed encrypted vault before zeroing complete consumed pad slots (message bytes and HMAC key). Outgoing durable reservations, including interrupted sends, are retired at the next successful transaction/startup. Incoming slots are retired only after acceptance; missing out-of-order slots remain available. The original pad identifier stays fixed after retirement; new imports still verify the complete original material and reuse claims remain in force.

Retries post the original ciphertext. A duplicate received text can recover its saved receipt only when its entire packet exactly matches retained accepted history; modified packets cannot authenticate with a retired zero key. Clearing or expiring history also removes its retained payload cache. The original readiness packet remains available for setup recovery.

This is logical cleanup of the current snapshot, **not forensic erasure or forward secrecy**. Android flash wear leveling, older encrypted snapshots decryptable with the same vault key, original exported pad bundles, process memory and backups can retain material. Retained message history is intentionally readable by an unlocked/compromised endpoint. Device compromise and previously copied pads remain serious risks. An independent protocol and implementation audit is still needed.
