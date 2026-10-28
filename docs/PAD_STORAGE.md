# Separate pad storage and larger pads (pipeline items 1–2)

Status: item 1 (separate storage) implemented; item 2 (bigger pads, streamed transfer) next.
Owner direction 2026-10-28: pads should be 10 MiB or larger.

## Problem

Today a chat's whole pad (1–4 MiB) is a base64 string inside the encrypted JSON vault
(`StationEngine` state field `pad`). Every save, which happens on every send, receive and
receipt, re-encrypts and rewrites it, and key rotation re-encrypts it again. Used slots
are zeroed inside that string. Pairing and renewal also carry the whole pad as one base64
JSON field (`CableProtocol`, `RadioHandoff`; limit 5.6 MB of text).

At 64 MiB this means about 85 MB of base64 text, roughly 170 MB of Java `String` in memory,
on every save. That is slow on regular phones and can exhaust the app's memory.

## Design

### Material file, one key per slot (implemented)

- `padmaterial/<root>/<padId>.bin` (`PadMaterial`): a 48-byte header (magic `OTPMAT01`, random
  file nonce, pad id, slot count, pad size), then one record per slot:
  `iv (12) ‖ AES-256-GCM(slotKey, slot bytes) (4128 + 16)`, with AAD = magic ‖ file nonce ‖ slot index.
  - Records are bound to the per-file nonce rather than the pad id. That lets `PadMaterial.Writer`
    stream a pad in before its id (a hash of the whole pad) is known, which item 2 needs for
    16–64 MiB pads.
  - The pad id in the header ties the file to the vault.
  - The file is written once as `pending-<nonce>.new`, fsynced, renamed, directory-synced and fully
    verified, then never rewritten.
  - Already-used slots (all zero in a migrated pad) are written as zeros and get no key. A migrated
    pad keeps its vault-authenticated id, because its zeroed bytes no longer hash to it.
- **Key table:** the 32-byte random `slotKey`s are one base64 field, `padKeys`, inside the contact's
  existing encrypted, rotating vault. This replaces the in-vault `pad` field. An all-zero key means
  "used or retired". Using a slot zeroes its key in the same atomic vault save that records the
  message. That zeroing, plus vault key rotation, is the erasure: consumed slot bytes may remain in
  the material file and in flash, but nothing can decrypt them.
  - *Change from the first draft:* the table stays inside the vault rather than in a separate
    `-padkeys` store. This keeps one atomic commit for "message recorded + slot erased". It also
    puts the table under the existing contact retirement, rotation and epoch scope unchanged.
  - Size: 32 bytes × slots, which is about 43 KB of base64 at 4 MiB and about 700 KB at 64 MiB.
    Today's vault carries 5.6 MB of base64 at 4 MiB.
- Wire format, message ids and pad check codes are unchanged (`PadProtocol.sealSlot/openSlot`
  produce exactly what `seal/open` produce from the whole pad).

### Ordering and crash safety

- **Install / pairing / renewal:** register the pad in the ledger, write and verify the material
  file, then commit `padKeys` and `padId` in the vault. A crash before the commit leaves an
  unreferenced file. Unreferenced files are removed when a pairing, install or reset commits, or
  when an engine opens while it is the only live owner of that chat. A file is never removed
  while another owner may have a staged pairing that refers to it.
- **Send:** reserve in the ledger (unchanged, before encryption), decrypt that one slot,
  encrypt, and zero its key in the same transaction that records the outbox entry. A crash
  after the reservation burns the slot, exactly as before.
- **Receive:** decrypt the slot named by the counter. A zeroed key means the slot was used or
  retired, the same as the old all-zero check.
- **Missing or damaged material** fails closed: the engine refuses to open, and a record that
  fails authentication is rejected. Nothing falls back to another key.
- **Retirement and wipes:** `VaultRetirement` deletes `padmaterial/<root>/` (step
  `pad-material`), and `inspect` reports COMPLETE only once it is gone. The global wipe runs the
  same contact retirement. *Forget pairing* deletes it too. The `-pads` ledger stays retained as now.

### Identity, check codes and export

`padId` stays SHA-256(pad)[0:16], computed while streaming at generation or import.
The pad check shown to users is unchanged. The plaintext export file stays available, but
streamed. The 64 MiB in-memory array goes away.

### Transfer (item 2)

USB and Wi-Fi pairing send the pad in chunks over the existing encrypted channel (for example
64 KiB frames with a running hash), after the approvals and before the final confirmation.
The receiver writes it straight into the material file. JSON setup carries only metadata.

### Migration (implemented)

On first open after the update, an in-vault pad is moved. The material and table are built from
the current pad; the pad already has zeros for used slots, and those get no key. The material is
verified, then `pad` is replaced by `padKeys` in a forced-rotation save. Until that save commits,
the in-vault pad stays authoritative. A crash leaves the old vault untouched and an unreferenced
file. The signed-update CI job exercises this with a real v0.13 install followed by `install -r`.

## Sizes

After item 2: 1, 2, 4, 16, 32 and 64 MiB. That is 127, 254, 508, 2,032, 4,064 and 8,128 slots per
direction. In an even conversation with receipts, 64 MiB lasts about 4,000 messages each before
meeting again.

## Tests

- **JVM (`PadMaterialTest`):**
  - every slot round-trips at 1, 2 and 4 MiB;
  - wire output is identical to the whole-pad path;
  - no plaintext pad bytes appear in the file;
  - retired slots have no key;
  - swapped records, swapped keys, flipped bits, the wrong pad id and a truncated table are all rejected;
  - prune and delete stay inside one root and reject path tricks.
- **Instrumentation:**
  - `PadStorageTest`:
    - a 4 MiB pad leaves the vault under 120 KB;
    - each send erases exactly one key, and the material file is never rewritten;
    - missing material fails closed;
    - forget removes the material;
    - an in-vault pad with used and reserved slots migrates.
  - `LocalWipeTest` checks that the wipe removes the material.
  - The delivery, migration, draft, pairing and upgrade suites now read the pad through
    `padSnapshot()`, and they check that no pad remains in the vault.
