# Bounded batch consent — proposed defaults (design proposal, not implemented)

Status: 採用は決定済み（2026-11-25、owner 決定 2）。**既定値 N / B / 期限は未定**で、この文書の数値は提案である（決定と既定値を混ぜない）。shared runtime の実装は未着手で、製品はまだ消費できない。

## Why

Per-operation consent breaks down for pack batches: one 32 MiB snapshot
save is about 2–3 data packs (12 MiB transport cap each) plus one capsule,
and every pack costs a PUT plus a readback GET. An earlier draft default of
128 operations / 10 minutes / 1 GiB described a large evacuation, not one
save, and put the ceiling in front of the user as the suggested size.

## Proposed batch consent

One approval covers exactly one save, with separate defaults and hard caps
shown separately in the consent UI and in the consent ledger.

| Quantity | Default (what one approval grants) | Hard cap (runtime refuses above, shown separately) |
| --- | --- | --- |
| Pack publishes (data packs + capsule) | 4 | 32 |
| Broker operations (PUT + readback GET each) | 8 | 128 |
| PUT bytes total | 48 MiB | 1 GiB |
| Batch deadline | 5 minutes from approval | 10 minutes |

Derivation (from the current sync profile, measured in code):

- Transport cap is 12 MiB per pack; the plaintext snapshot cap is 32 MiB,
  so one save is 2–3 data packs plus 1 capsule publish.
- 4 publishes × 2 operations (PUT + readback GET) = 8 operations; byte
  budget 4 × 12 MiB = 48 MiB.
- The hard caps keep the earlier 128 ops / 10 min / 1 GiB figures as
  absolute ceilings for a future large evacuation, never as the offered
  default. A batch consent request that exceeds a hard cap is refused
  rather than partially granted.

Deadline semantics:

- The batch expires at the deadline; unused operations are void.
- Expiry does not abort an in-flight operation, which follows the existing
  per-operation rules (receipt + full readback before commit).
- Each new save needs a fresh approval; the default batch is never reused.

Consent ledger (one entry per approval):

- endpoint, operation class, granted defaults (publishes/ops/bytes) and the
  deadline, plus the hard caps shown at approval time;
- outcome per operation (committed, refused, expired-unused), so a later
  audit can tell granted from used.

## What stays unchanged

- Every non-batch operation keeps per-operation consent.
- Caps still protect transport, storage, and the snapshot itself
  (12 MiB packs, 32 MiB plaintext, 12 MiB pack protocol limits).
- No silent fallback, redirect, or native HTTP bypass; denials send no
  request.
- Owner asks later: defaults above are safe-side proposals for the owner
  decision, not shipped values.
