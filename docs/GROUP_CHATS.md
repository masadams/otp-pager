# Group chats (pipeline item 7)

Status: implemented for review (v1). Fixed membership, with every member paired with every
other member in person.

- **What a group is:** a label on ordinary pairwise chats. There is no shared group key and no
  shared pad. Sending to a group sends **one separately pad-encrypted copy to each member**,
  using that friend's own pad (fan-out). One group message therefore spends one slot per member.
- **Where membership lives:** in each member chat's own vault (`groups`: id → name and member
  names). Wiping or retiring a chat removes it from every group automatically.
- **Creating a group** (app menu → **Groups** → **New group**): choose 2–8 paired friends. Each
  gets an announce message naming the group and its members. Receivers link the announcing
  chat to the group. Each member links their other members' chats by hand (**Link a member to a
  chat**), because the app can't identify the same person across two separate pairings. A
  message tagged with a group that its chat isn't linked to is shown as an ordinary pairwise
  message, so a non-member can't inject messages into a group thread.
- **The thread:** messages from every linked chat, by friend name. Your own copies merge into
  one row by a shared message id, showing the weakest delivery status.
- **Older app versions:** these see group messages as normal messages, because the extra
  fields are ignored.
- **Strict chats and backup relays:** these keep their own rules per member chat. A locked
  strict member is skipped until it's unlocked.

Not in v1:
- membership changes (make a new group instead);
- cryptographic group identity across pairings;
- read state per member;
- background notifications per group.

Tests: `GroupChatTest`:
- the announce links both members;
- one send gives two distinct ciphertexts sharing a group message id;
- the sender's thread has one row;
- a member's reply is attributed;
- an unlinked chat's tag stays pairwise.
