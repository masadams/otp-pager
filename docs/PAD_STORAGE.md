# Separate pad storage and larger pads (pipeline items 1–2)

Status: design. Owner direction 2026-10-28: pads should be 10 MiB or larger.

## Problem

Today a chat's whole pad (1–4 MiB) is a base64 string inside the encrypted JSON vault
(`StationEngine` state field `pad`). Every save, which happens on every send, receive and
receipt, re-encrypts and rewrites it, and key rotation re-encrypts it again. Used slots
are zeroed inside that string. Pairing and renewal also carry the whole pad as one base64
JSON field (`CableProtocol`, `RadioHandoff`; limit 5.6 MB of text).

At 64 MiB this means about 85 MB of base64 text, roughly 170 MB of Java `String` in memory,
on every save. That is slow on regular phones and can exhaust the app's memory.

## Design

### Material file, one key per slot

- `<root>-material.bin`: immutable after installation. A header (magic `OTPMAT01`, pad id,
  size, slot count per direction), then one record per slot:
  `iv (12) ‖ AES-256-GCM(slotKey, slot bytes) (4128 + 16)`, with AAD = pad id ‖ direction ‖ slot index.
  Written once, streamed in, fsynced, verified, then never rewritten.
- `<root>-padkeys`: a contact-vault store (the same rotating `VaultKeys` envelope) holding
  each unused slot's 32-byte random `slotKey`. Using a slot deletes its key from this table.
  That deletion, plus the table's own key rotation, is the erasure: consumed slot bytes may
  remain in the material file and in flash, but nothing can decrypt them.
- Table size: 32 bytes × slots. 64 MiB is about 16,000 slots (about 512 KB), a small fraction of
  today's per-save rewrite.

### Ordering and crash safety

- **Install:** write the table (all keys) first, then stream the material, then commit `padId`
  and `padStorage: 1` in the vault last. Interruption leaves unreferenced files that the next
  install or retirement removes; the ledger reservation rules are unchanged.
- **Send:** reserve in the ledger (unchanged, before encryption), decrypt that one slot,
  encrypt, and delete its key in the same transaction that records the outbox entry. A crash
  after the reservation burns the slot, exactly as today.
- **Receive:** decrypt the slot named by the counter. A missing key means the slot was used
  or retired, the same as today's all-zero check.
- **Retirement and wipes:** `<root>-material` and `<root>-padkeys` join the contact's
  destroyed scope. Discovery patterns and `VaultRetirement` add them. The `-pads` ledger
  stays retained as now.

### Identity, check codes and export

`padId` stays SHA-256(pad)[0:16], computed while streaming at generation or import.
The pad check shown to users is unchanged. The plaintext export file stays available, but
streamed. The 64 MiB in-memory array goes away.

### Transfer (item 2)

USB and Wi-Fi pairing send the pad in chunks over the existing encrypted channel (for example
64 KiB frames with a running hash), after the approvals and before the final confirmation.
The receiver writes it straight into the material file. JSON setup carries only metadata.

### Migration

On first open, an existing in-vault pad is moved: build the table and material from the current
pad, which already has zeros for used slots (those get no key), verify, then remove `pad` from the
vault in a forced-rotation save. Until verified, the in-vault pad stays authoritative.

## Sizes

After item 2: 1, 2, 4, 16, 32 and 64 MiB. That is 127, 254, 508, 2,032, 4,064 and 8,128 slots per
direction. In an even conversation with receipts, 64 MiB lasts about 4,000 messages each before
meeting again.

## Tests

JVM: material record codec, AAD binding, wrong-slot and wrong-pad refusal, table encode/decode.
Instrumentation: install/send/receive across restart, crash at each install step, migration from
v3–v6 in-vault pads with used slots, retirement removing both new files and every key
generation, export round trip. Existing delivery, renewal and pairing suites must pass unchanged.
