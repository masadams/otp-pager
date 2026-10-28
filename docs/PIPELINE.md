# Development pipeline — owner direction 2026-10-28

This is the ordered work list after the 2026-10-28 use and security assessment. It takes
precedence over older "next task" notes where they differ. Each item ships as its own
reviewable PR with tests; the plan's invariants S01–S12 still apply.

## Owner answers (assessment, 2026-10-28)

| Question | Answer |
| --- | --- |
| Users | The owner now; family and friends soon. Public on GitHub; not sold or promoted |
| Threats | As many threat vectors as possible |
| Devices | Regular Android phones are the main target (~99 % of use); Kompakt is the owner's own device |
| Relay | Oracle Cloud now; possibly a home relay too; interested in multi-relay designs |
| Retention | 24 hours is fine |
| Peer wipe vs strict chats | Both are required |
| Review | Community review after the flagged issues and the original plan are worked through |
| Capacity | Pads should be 10 MiB or larger |
| Added scope | Every issue flagged in the assessment goes into this pipeline; add group chats |

**Changed gate (owner confirmed 2026-10-28):** the original plan required independent
protocol review *before* destructive peer-wipe code. The owner prefers a community review at
the end. Peer wipe is therefore implemented behind an **off-by-default, clearly labelled
"unreviewed"** setting, so the community review covers shipped code. The spec's vector gate
moves to the review package.

Also confirmed 2026-10-28: separate pad storage before bigger pads ([design](PAD_STORAGE.md));
group chats by per-member fan-out; multi-relay ideas kept in the plan (items 6 and 8).

## Current status and physical USB regression (2026-10-28)

Items 1–3 below have now merged into `main` via PRs #38, #39 and #40. Item 5 is under review in PR #41. The table below records implementation status; review and physical validation remain separate gates. Peer wipe is unreviewed and off by default; successful CI is not evidence of physical peer-wipe safety.

**Open physical USB pairing regression:** the owner reports that transfer worked in an earlier APK but failed with the newer October 28 build. Before changing code, capture both device models, Android versions, host/accessory roles, cable, permission prompts, app versions and exact failure stage. Compare with the last known working build without uninstalling active pad material. Add a two-device physical USB check to the release gate; emulator tests do not establish compatibility. This remains unresolved.

## Continuation decisions — 2026-10-28

Owner authorized dependent follow-up PRs stacked on existing branches, all unmerged.
Work order: USB reliability and regression tests; strict chats; relay failover; pairwise
fan-out groups; remaining security/reliability/usability work.

- Strict chats: one authoritative unlock relay, no unlock failover and no automatic
  deletion after inactivity. New physical pairings only for v1. Proposed default:
  60-second foreground lease, cleared immediately on background/screen lock/wipe.
- Groups: default v1 is fixed membership with physical pairings between all members;
  membership changes create a new group. This is a chosen implementation default,
  not an existing feature or a claim of owner preference.
- #41 audits packet sizes; #42 records the physical regression; #43 fixes the stuck
  USB negotiation flag; #44 defines strict-chat acceptance gates. All remain open.
  Dependent PRs must reuse them and name their base branch. Preserve both #42 and
  #44's additions when their overlapping PIPELINE edits are integrated.
- Strict protocol/relay/storage/UI, failover and groups remain **unimplemented**.
  The proposed work sequence is not release evidence.

## Ordered pipeline

| # | Item | Why | Notes |
| --- | --- | --- | --- |
| 1 | **Separate pad storage** ([design](PAD_STORAGE.md)) | Pads live inside the JSON vault, rewritten on every save; 10+ MiB pads would make every message slow | **Merged (#38):** write-once per-slot AES-GCM file per chat; slot keys in the vault are zeroed on use; in-vault pads migrate on first open |
| 2 | **Bigger pads (16, 32, 64 MiB)** | Owner wants ≥10 MiB; group fan-out multiplies slot use | **Merged (#39):** pairing protocol v5 streams the pad in 32 KiB authenticated chunks into storage on both phones; default 16 MiB; manual pad file stays ≤4 MiB |
| 3 | **Peer wipe** | Wipe should reach the friend's phone | **Merged (#40):** implemented behind an off-by-default "Peer wipe (unreviewed)" setting; pairing protocol v6 provisions control keys; independent vector reproduction still required |
| 4 | **Strict chats** | A seized offline phone should not open high-risk chats | Appendix A of the peer-wipe spec; after 3 (shares control mailbox) |
| 5 | **Fixed-size packets** | Message length should leak nothing to the relay | Pad slots already fixed; check the wire frame and receipts |
| 6 | **Relay failover** | Availability with more than one relay | Ordered relay list per chat; same capability on each |
| 7 | **Group chats** | Owner request | Fan-out: each message encrypted separately per member with the existing pairwise pads; no shared pads between 3+ phones |
| 8 | **Split-relay experiment** | Neither relay sees both phones' connections | Post via relay A, fetch via relay B, private link between them (e.g. ZeroTier); timing still links traffic without delays/cover traffic |
| 9 | **Entropy and wording** | Pads come from SecureRandom, not a true OTP | Optional extra entropy mixed into pad generation; plainer user-facing wording |
| 10 | **Relay storage scaling** | Whole state file rewritten per message | Append-only log or per-mailbox files |
| 11 | **Owner tasks** | Cannot be done from CI | Release signing key, first signed release, two-phone hands-on test incl. power loss |
| 12 | **Community review package** | Owner's chosen review route | Threat model, specs, vectors, known limits, how to test |

Already done on 2026-10-28: relay strict claims (#34, F06 first step); base image pinned (#25).
Per-device relay credentials were considered and dropped: both phones share the pad and
secret, so revoking one phone's relay access protects nothing extra.
