# Long-Term Memory Invariants

## Identity and Timeline

- Use immutable `sourceTurnId` as persisted identity. User turn, matching AI reply, appended reply, and derived image stay in the same source turn.
- Keep source order stable and never reuse tombstoned identities.
- Derive display `T` from the current memory timeline. AI never owns T numbering.
- Exclude the newest unstable source turn from long-term-memory generation.
- Treat source-turn grouping as shared boundary logic. Direct context, Episode pending, and RAG may store different data but must agree on turn identity.
- Configure Episode size globally with `episodeMaxSourceTurns` in range 1–6, default 2. Apply changes only to future uncommitted grouping.

## Injection Ownership

- Long-term memory emits only compiled Archive plus HEAD/timeline constraint.
- Archive contains only active Episode, Arc, and Era text ordered by derived T.
- Never include raw chat, compression sources, pending source text, RAG results, world book, or direct context in the long-term-memory block or complete preview.
- Keep request order: stable settings → cacheable history → Archive → world book/RAG → HEAD/constraint → previous turn → current input.
- Keep HEAD after RAG and treat larger T as later narrative state.
- Keep RAG data independent: do not index or query long-term-memory nodes and do not limit RAG by memory-node T ranges.

## Gap and Backfill

- Treat `MemoryGap` as durable evidence that source turns lack long-term memory.
- Compute current backfill eligibility as missing and uncovered turns that are outside direct context and still have raw source text.
- Make explicit memory-page refresh re-read persisted settings/chat and discover stable archived turns that are uncovered, outside normal pending, and not already in a Gap. Persist them as durable gaps without calling AI.
- When context expands, hide newly direct turns from eligibility without deleting them from durable gaps. When context shrinks, expose them again.
- Remove a gap source only after successful Episode commit, explicit supported product action, or permanent clear boundary.
- Keep ordinary Episode pending separate from backfill pending. Raw pending text belongs to chat/context storage, not memory budget or Archive.
- Honor `recordingStartsAfterSourceOrder`; permanent clear must not resurrect older turns.

## HEAD and Archive

- Update HEAD independently from Archive. Failure in one does not roll back the other.
- Make HEAD describe current state through its stable source turn, not historical plot summary.
- HEAD has three explicit modes: `INITIALIZE` uses opening + first complete round when third user round starts; `BACKFILL` uses compiled Archive + penultimate stable baseline group; `UPDATE` uses previous HEAD + exactly next baseline group.
- Keep latest complete group raw at prompt tail. HEAD target is always immediately before that hot group; never summarize hot group into HEAD early.
- Blank HEAD injects no HEAD block. Expected new-chat blank state shows no backfill action; historical blank/lagging state requires backfill.
- Normal HEAD update cannot cross `MemoryGap`. Re-enable/backfill fills missing Archive first, then rebuilds HEAD.
- Before roleplay request, wait for HEAD preparation and RAG retrieval in parallel. Post-reply HEAD update remains background work.
- Keep HEAD outside Archive character budget and visible revision history.
- Count only active Episode/Arc/Era body text toward per-session automatic budget.
- Use initial 2000 characters, +2000 per accepted expansion, maximum 20000. Do not derive budget from model context percentage.

## Compression and Coverage

- Treat `MemoryNode.content`/`body` as the single formal node text used by UI, injection, budget, diff, editing, and later compression. Keep `coverageUnits` internal and never render them as duplicate user-facing text.
- Episode covers 1–N source turns with one direct aggregate paragraph. New Episodes contain no per-source summary text; ordered source IDs, source hashes, and structural coverage hash are program-owned evidence. Old per-source coverage remains read-compatible only.
- Enforce Episode summary hard limits from source-turn count: 1T=50 characters, +20 per additional T, up to 6T=150. Include exact computed limit in every Episode prompt.
- Let programs choose ordered candidates and calculate ranges. AI may only consume a legal continuous prefix.
- Preserve every child's identity and coverage. Compression changes active frontier, not immutable source nodes.
- Require exact program-owned source coverage, child coverage, hashes, legal counts, correct order, and actual text reduction before commit.
- Never cross Gap, overlap, manual inconsistency, or unverifiable Legacy Reference boundaries.
- Prefer Era windows with no prior compression; otherwise choose lowest `compressionLevel`, then earliest window.

## History, Failure, and Migration

- Keep Episode, Arc, and Era page histories independent; HEAD has no user-visible history.
- Create visible checkpoints for compression, user edits, and restore. Do not create visible Episode history for pure append.
- Restore one page without silently rewriting other pages or HEAD. Surface resulting cross-page inconsistencies.
- Capture base revision plus source hash for AI work. Reject stale results after edits, restores, new commits, or source changes.
- Persist each successful backfill Episode immediately. Failure must retain remaining gaps and completed nodes.
- Keep streamed backfill summaries and current phase as runtime UI state; persist committed Episode count and source progress, not partial model output.
- Pause orphaned persisted `RUNNING` after process restart; never pause a runner active in current process.
- Load old JSON through defaults. Make repairs idempotent. Preserve unverifiable old memory as time-unknown Legacy Reference.
- SaveSlot carries current memory snapshot, not complete revision history or runtime task objects.
