# Vault key rotation — erasable history (package C)

Status: implementation candidate (#22). Targets F03 and S09.

## Problem

Contact vaults (`station-v2`, `chat-<20 hex>`) hold plaintext history (`opened[wire].payload`,
renewal `history[].text`), pad material and secrets, encrypted under one Keystore key for
the vault's whole life. Clearing history or pruning removed content from the *current*
snapshot, but any older ciphertext copy (flash remnant, interrupted-write backup, restored
file) stayed decryptable while that key existed.

## Approach: rotate the wrapping key, not split the file

Splitting history into its own file still leaves the old copies under the old key, so it
would not achieve erasure by itself. Instead each contact vault is rewritten under a fresh
key and the previous key is destroyed:

- Generation 0 is the original alias `otp-pager-<ns>`; generation g ≥ 1 is
  `otp-pager-<ns>~g`. `~` cannot occur in a namespace, so aliases are unambiguous.
- New `v3` envelope: `[3][generation u32][iv 12][ciphertext+tag]`, with AAD bound to the
  namespace and generation (a file cannot be moved to another vault). `v2` files still open
  and move to `v3` on their next rotation.
- Rotation happens on the first save of a vault in each process, at most every 10 minutes
  afterwards, and always when a save removes any message: **Clear message history**,
  **Delete message history now**, a long-press delete of one message, a shorter retention,
  and every expiry (`StationEngine.contentKeys` compares the message set before and after).

## Retention (0.18)

Each chat keeps messages on this phone for 1 h, 6 h, 24 h (default) or **until read**
(`state.retention`, validated against `StationEngine.RETENTIONS` / `AFTER_READ`). "Until read"
removes a read incoming message, and a delivered or expired outgoing one, about a minute later
(`readAt` / `doneAt`). Queued messages always stay until sent or until the relay's 24 h
expiry. Expiry runs on open and on every check, and removed messages are erased by the rekey
above. Retention is local: the friend's phone keeps its own copy under its own setting.
- Order: create key g+1 → write and fsync the new snapshot → verify readback and directory
  sync → only then delete every other generation's key. A key for an uncommitted generation
  is a leftover from an interrupted rotation and is replaced. A crash at any point leaves
  exactly one committed snapshot whose key exists.
- Every scanner that finds or deletes contact keys (`ContactInventory`, `VaultRetirement`
  delete/inspect, staging abandonment/verification, allocation collision checks) now uses
  `VaultKeys.all` / `VaultKeys.SUFFIX`, so no rotated key can survive a wipe.
- Index, claims, ledgers and drafts are unchanged (single v2 key): they hold no message
  content, and drafts already destroy their key on clear.

## Limits

Erasure is cryptographic: it depends on Keystore actually destroying deleted keys. Content
expiry becomes erasure at the next open or check (the view hides expired messages before then). The current
snapshot still holds what the app currently shows or must retry. Copies outside the vault
(exports, screenshots, memory, keyboards) are not covered.

## Tests

`VaultKeysTest` (JVM): alias parsing/ambiguity, discovery of rotated keys.
`VaultRotationTest` (instrumentation): v3 envelope, clear-history rotation, an older
snapshot copy fails to open after rotation while the current one opens; retirement removes
every generation including a leftover from an interrupted rotation. `MessageDeletionTest`:
single-message delete, retention shortening and "until read" remove the messages and rotate
the key; an older snapshot holding a deleted message no longer opens. Existing restart and
retirement suites assert every generation is gone.
