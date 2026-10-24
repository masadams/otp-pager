# v0.11 UI and pager operation

The regular message screen emphasizes the friend's name, readable delivery states, unread counts and a Receive control. Technical connection fields are behind Advanced connection setup for a paired chat. Configure a relay on one unused station, then use USB pairing on both.

## Background receiving

Receive → Turn on explicitly starts a foreground text-messaging service with a persistent notification and Stop action. Android 13+ asks for notification permission first. Notifications contain no message text or friend names; opening one goes through the existing app unlock. A bounded round-robin checks up to three chats per 30-second cycle, with backoff for failed connections. The receiver pauses while the UI or USB exchange owns the single vault worker. Incoming messages, receipts and replay accounting use the same engine as foreground sync.

This is not an instant push service. Android Doze, battery settings and vendor firmware can delay checks. Battery settings are linked from Receive; unrestricted battery use may be needed on the Kompakt. Only a bounded wake lock covers active network work, never the idle interval. A system restart or Force stop can require opening the app and tapping Resume. No boot auto-start or Google services are used. Service lifecycle tests do not establish physical Kompakt screen-off battery life or notification behavior.

References: https://developer.android.com/develop/background-work/services/fgs/service-types#remote-messaging ; https://developer.android.com/training/monitoring-device-state/doze-standby

## USB pad choices and renewal

New USB pairing offers 1, 2 and 4 MiB pads; 4 MiB is the initial UI choice. Capacities are 127, 254 and 508 fixed-size slots in EACH direction. Texts and delivery receipts consume slots, so this is not a promise of that many user messages. The USB host's choice is displayed with the physical confirmation code. Both devices need v0.11 for the new USB protocol; existing v0.10 paired traffic remains compatible until a larger pad or renewal is installed.

Chat settings → Renew pad → choose size → open the same operation for the same friend on both devices → connect USB → compare the entire confirmation code. Sync both devices first until queued messages have delivery confirmations. Renewal preserves the chat, relay authorization, station roles, pinned device identities, and retained history. It exchanges genuinely fresh pad material, registers its new ID in the durable anti-reuse ledger, and resets allocation/replay state only within that new pad ID. Old packets fail the new pad check. History remains within the existing 24-hour retention limit, stored in the encrypted vault with its verification status captured before the old material is removed.

Both stations persist a paused state before changing pads. If disconnected, reopen Renew pad on BOTH: matching current pads allow authenticated recovery without copying a used pad or resetting counters; mismatched staged pads remain paused and receive a new unused candidate. Queued messages cannot be silently discarded for renewal. A failed candidate remains retired in the ledger. Interrupted cable completion is not falsely reported as an atomic cross-device commit.

Vault schema 6 is used for larger pads and renewal. Older apps reject it, and ordinary Android downgrade protection remains in place. Logical used-pad cleanup is not forensic flash erasure or forward secrecy.

## NFC and the wireless path

NFC setup sharing is an explicit 60-second, foreground-only equivalent of the secret setup QR. It uses Android reader mode and host card emulation where supported, sends only small link setup data, requires review before saving, and refuses to clone an operational pad or overwrite an active pairing. It contains the setup token/pairing secret and must only be shown to the intended physical device. NFC proximity does not prove identity or provide secrecy against eavesdropping/relay attacks. It does NOT transfer a full pad or substitute for USB device verification. Physical Kompakt HCE compatibility is unverified.

Full NFC pad transfer is technically possible with chunking, but needs throughput measurements and interruption recovery. An NFC-authenticated Wi-Fi/Bluetooth handoff is the intended faster wireless design. It is not implemented here. Shipping OTP material over RF would make confidentiality depend on the transfer cryptography, so it needs a reviewed post-quantum/hybrid key exchange and transcript/identity binding; the current USB P-256 handshake is conventional, not post-quantum. No wireless pad route or unreviewed home-grown PQ algorithm is enabled in this build.

References: https://developer.android.com/develop/connectivity/nfc/hce ; https://developer.android.com/reference/android/nfc/tech/IsoDep ; https://nfc-forum.org/build/specifications/connection-handover-technical-specification/ ; https://csrc.nist.gov/pubs/fips/203/final
