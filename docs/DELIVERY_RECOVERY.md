# Android 0.7 delivery recovery

## What each status means

- **Queued:** the encrypted packet is saved locally, awaiting relay acceptance.
- **At relay / waiting:** the relay accepted it; there is no saved receipt yet.
- **Peer saved:** an authenticated receipt says the other app stored the message.
  This does not establish that a person read it or that the peer is online now.

The header counts queued packets (including receipts), unread messages, and outgoing
texts awaiting receipts separately. Tap the header for last successful relay contact,
remaining outgoing pad slots, and identity status. Connectivity indicates an HTTPS
relay check, not an active private tunnel. Automatic polling remains foreground-only,
15 seconds normally and up to 60 seconds after failed syncs. The selected chat
and one inactive chat are checked per cycle; inactive chats rotate, so their checks
are less frequent when more friends are paired. Network time is additional.

## Recovery after lost relay storage

When the relay reports a different storage epoch, the app preserves its cryptographic
receive replay window and pad ledger, resets only its transport cursor, and queues
retained unconfirmed text and cached receipts for the next sync. Both devices must
sync; this can take several polling cycles. A normal restart with its volume intact
does not change the relay epoch or trigger recovery.

For a manual check, use **Link → Retry undelivered messages**, or tap the status panel
and choose **Retry**. This starts a sync even with automatic polling off.
It rechecks retained relay receipts and reposts existing encrypted packets. The relay
deduplicates them. The app never re-encrypts a retry, allocates another pad slot, changes
the message ID, or resets the receive replay window. A full local queue limits each
recovery action to available space (50 pending packets); let sync drain it, then retry
again if messages are still waiting.

For each newly received text the app saves its exact encrypted receipt in the same
vault transaction as the received message. An authenticated replay matching that
saved text can requeue the cached receipt, without duplicating the message or unread
alert. Replay-triggered reannouncements are limited to once per minute per receipt.
Explicit recovery or a new relay epoch can requeue it sooner. Device-bound frames
must pass their pinned-device signature check before replay recovery is allowed.

## Limits and upgrades

- Local text and its cached receipt expire within 24 hours. Retry preserves original
  local creation times. It cannot recover content that both relay and devices deleted.
- Clearing history removes saved receipt recovery. Pending encrypted packets remain
  queued, and replay protection still rejects historical messages.
- Messages received on older APKs have no cached receipt; this upgrade does not
  create one retroactively or spend extra pad material to invent one.
- Exhausted pads can still retry stored packets/receipts. Receiving a new message
  with no outgoing slot available cannot create a new receipt.
- Shared-secret legacy frames keep their existing authentication behavior. Signed
  messages and receipts require the pinned peer identity. This is experimental,
  unaudited software; recovery does not strengthen the underlying cryptography.
- No new server API or relay update is required. Existing v2 relay epochs and
  byte-identical retry deduplication are used.

Tests cover signed pad traffic across app restart and two relay storage losses,
recovery before delivery and after a saved receipt is lost, tampered replays,
receipt retry throttling, history deletion, queue bounds, expiry and unchanged pad
reservations. A compact-display instrumentation test exercises a 480 × 800 pixel (320 dp wide)
viewport and the actual Android keyboard. Physical e-ink refresh and battery testing
and independent protocol review remain separate work.
