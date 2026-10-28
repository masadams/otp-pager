# Backup relays (pipeline item 6)

Status: implemented for review (pairing protocol v8). Each chat can have up to two backup
relays, each with its own address and setup token, in addition to its main server.

- **Reading:** every check reads the main server and every reachable backup. Each relay has its
  own cursor, storage epoch and chat claim; the same chat capability is claimed on each.
  Duplicate packets are harmless, because inbox matching and the replay window already make
  delivery idempotent. A backup's storage reset only restarts that backup's cursor.
- **Sending:** queued packets (messages and receipts) go to the main server. Only if it fails do
  they go to the first backup that accepts them. A packet is never re-encrypted; the same
  ciphertext is retried.
- **Not routed around:**
  - revocation (HTTP 410) on the main server is final and never falls back to a backup;
  - the claim and lost-volume rules for the main server are unchanged;
  - strict-chat unlocks and shares stay on the main server only, so there is one unlock authority
    and failover can't copy a share.
- **Sharing the list:** both phones need the same backups. Pairing copies them: the phone that
  supplies the server also supplies its backups, inside the signed setup (v8 binds them into
  the setup digest). For existing chats, add the same backup on both phones from the
  conversation menu → **Backup relays**.
- **Tests:** `RelayFailoverTest` covers:
  - pairing copies the list;
  - traffic stays on the primary while it works;
  - send, receive and receipt go through the backup while the primary is down;
  - re-posted duplicates are absorbed;
  - with every relay down the error is reported and the outbox is kept;
  - revocation is never routed around.

Not done: automatic health ranking, and the split-relay experiment (item 8: post via one relay,
fetch via another).
