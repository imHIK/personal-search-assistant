# Known Limitations

A running list of accepted trade-offs and known gaps. These are deliberately *not* fixed yet — we'll
review them together and decide the best approach once the list is more complete. Each entry records
the limitation, why it exists today, its impact, and candidate fixes.

---

## L1 — Pause/resume park-vs-rearm race

**Area:** Ingestion · cursor lifecycle (`DefaultKnowledgeService.pause/resume`, `IngestionJob.tryRun`)

**What:** Pausing a knowledge parks its claimable cursors (`AVAILABLE/IDLE → SUSPENDED`) and resuming
re-arms them (`SUSPENDED → AVAILABLE`). The ingestion loop also parks paused-knowledge stragglers as
a backstop. There is a narrow interleaving where a cursor can end up stuck `SUSPENDED` while its
knowledge is `ACTIVE`:

1. A tick reads the cursor's knowledge and sees `PAUSED`.
2. The user calls `resume()` → knowledge flips `ACTIVE` and `resumeByKnowledge` re-arms the cursors.
3. The same tick (still mid-`tryRun`, acting on its stale read) calls `suspendByKnowledge` and parks
   the just-re-armed cursor again.

The cursor is now `SUSPENDED` under an `ACTIVE` knowledge and won't be picked up.

**Impact:** Low. Requires a pause→resume within the same poll interval and an unlucky overlap with an
in-flight tick. It is **not** permanent data loss — the next user `resume()`, a `triggerSync`, or any
operation that re-arms cursors clears it; only that one knowledge's incremental sync stalls until
then. No effect on already-indexed/searchable content.

**Why we left it:** The robust fixes add ongoing cost or complexity that isn't justified for a
low-probability, self-recoverable stall (see below). Deferred pending the full limitations review.

**Candidate approaches (for later):**
- *Periodic reconcile sweep* — a scheduled job re-arms `SUSPENDED` cursors whose knowledge is
  `ACTIVE`. Simple and also heals any other way a cursor could get orphaned in `SUSPENDED`, at the
  cost of one more background loop.
- *Status-fenced park* — make the in-loop `suspendByKnowledge` conditional on the knowledge still
  being non-active at write time (compare-and-set), so a concurrent resume wins. Tighter, but pushes
  more coordination into the hot path.
- *Re-check after claim* — re-read knowledge status immediately before parking. Shrinks the window
  but doesn't fully close it.
- *Version/epoch on knowledge* — tag park writes with the knowledge status version and reject stale
  ones. Most correct, most invasive.
