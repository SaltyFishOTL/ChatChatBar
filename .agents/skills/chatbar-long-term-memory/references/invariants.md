# Long-Term Memory Invariants

- Episode目标轮数是固定批次，不是上限。尾部不足目标数属于普通pending；只有两侧活跃记忆包围的历史内部单轮余数可例外提交。
- 来源语义一致性使用`sourceFingerprints`：保留source/message身份、角色、显示正文、图片及相对消息顺序；排除`updatedAt`与数值`orderKey`。
- 自动Archive与HEAD由应用级协调器串行维护；新任务仅属于当前会话，已开始的模型调用不因页面销毁或切换会话取消。

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
- Inject Archive node bodies without per-node tier names or T-range labels. Keep T metadata for ordering/UI only; retain only the semantic time-unknown warning for Legacy References.
- Never drop a nonblank active Archive body only because its T proof is missing or invalid. Order verified nodes first, use a stable fallback for unverifiable nodes, and expose an integrity warning instead of hiding text.
- Never include raw chat, compression sources, pending source text, RAG results, world book, or direct context in the long-term-memory block or complete preview.
- Keep request order: stable settings → cacheable history → world book → RAG → Archive → HEAD/constraint → previous turn → current input.
- Serialize nonblank Archive as its own system message after world book/RAG and immediately before HEAD. Before network I/O, assert that expected Archive exists in the final message list; never swallow prompt-view failures or silently send HEAD alone.
- Diagnose memory delivery from serialized Request JSON, not assembled preview alone. Show separate Archive/HEAD presence indicators in debug logs.
- Keep HEAD after RAG and treat larger T as later narrative state.
- Never prepend derived `[Txx]` markers to roleplay chat messages. Keep display T as internal memory metadata; preserve ordinary message order for model chronology. After assistant status exclusion, omit blank assistant text and unsupported assistant image records; a supported user image may be sent without synthetic text.
- Keep RAG data independent: do not index or query long-term-memory nodes and do not limit RAG by memory-node T ranges.

## Gap and Backfill

- Treat `MemoryGap` as durable evidence that source turns lack long-term memory.
- Compute ordinary backfill eligibility as missing and uncovered turns that are outside direct context and still have raw source text. A disabled-period Gap may be backfilled inside direct context, but only after that source turn contains an assistant reply and is stable.
- Make explicit memory-page refresh re-read persisted settings/chat and discover stable archived turns that are uncovered, outside normal pending, and not already in a Gap. Persist them as durable gaps without calling AI.
- When context expands, hide newly direct turns from eligibility without deleting them from durable gaps. When context shrinks, expose them again.
- Remove a gap source only after successful Episode commit, explicit supported product action, or permanent clear boundary.
- Keep ordinary Episode pending separate from backfill pending. Raw pending text belongs to chat/context storage, not memory budget or Archive.
- Honor `recordingStartsAfterSourceOrder`; permanent clear must not resurrect older turns.

## Source Mutation Repair

- Detect historical message edit/delete by comparing persisted node/HEAD source hashes with current source turns. Partial edit keeps `sourceTurnId`; whole-turn deletion remains a tombstoned timeline gap.
- Exclude stale active roots and stale HEAD from injection immediately. A stale root may expose only unchanged descendants when their hashes still match and the whole safe frontier fits the Archive budget; otherwise omit that root and warn.
- Repair only after explicit user action on the memory page. Persist ordered root work, completed count, HEAD work, pause/error state, and per-root commits; keep phase and streamed summaries runtime-only.
- Regenerate stale Episode leaves from current raw source runs. Never let one regenerated node cross a deleted-turn gap.
- Rebuild Arc/Era only from an exact, continuous one-to-one repaired child set. When deletion breaks that structure, promote the safe repaired child frontier instead of inventing a parent across the gap.
- Automatically regenerate only AI-authored stale nodes. A stale user-authored node requires explicit editor review/save.
- Rebuild or clear stale HEAD only after all queued Archive roots commit. Reject a final commit when source evidence or active root identity changed during AI work.

## HEAD and Archive

- Update HEAD independently from Archive. Failure in one does not roll back the other.
- Make HEAD describe current state through its stable source turn, not historical plot summary.
- HEAD has three explicit modes: `INITIALIZE` uses opening + first complete round when third user round starts; `BACKFILL` uses compiled Archive + penultimate stable baseline group; `UPDATE` uses previous HEAD + exactly next baseline group.
- Keep latest complete group raw at prompt tail. HEAD target is always immediately before that hot group; never summarize hot group into HEAD early.
- Blank HEAD injects no HEAD block. Expected new-chat blank state shows no backfill action; historical blank/lagging state requires backfill.
- Normal HEAD update cannot cross `MemoryGap`. Re-enable/backfill fills missing Archive first, then rebuilds HEAD.
- Before roleplay request, wait for HEAD preparation and RAG retrieval in parallel. Post-reply HEAD update remains background work.
- Keep HEAD outside Archive character budget. Its internal version and source hashes exist only for concurrency and stale-result rejection.
- Count only active Episode/Arc/Era body text toward per-session automatic budget.
- Use initial 2000 characters, +2000 per accepted expansion, maximum 20000. Do not derive budget from model context percentage.

## Compression and Coverage

- Treat `MemoryNode.content`/`body` as the single formal node text used by UI, injection, budget, diff, editing, and later compression. Keep `coverageUnits` internal and never render them as duplicate user-facing text.
- Episode covers 1–N source turns with one direct aggregate paragraph. New Episodes contain no per-source summary text; ordered source IDs, source hashes, and structural coverage hash are program-owned evidence. Old per-source coverage remains read-compatible only.
- Set Episode summary prompt targets from source-turn count: 1T=50 characters, +20 per additional T, up to 6T=150. Include the exact target in every Episode prompt, but reject only above twice that target (1T=100 through 6T=300) so AI character-count error has bounded tolerance.
- Let programs choose ordered candidates and calculate ranges. AI may only consume a legal continuous prefix.
- Preserve every child's identity and coverage. Compression changes active frontier, not immutable source nodes.
- Require exact program-owned source coverage, child coverage, hashes, legal counts, correct order, and actual text reduction before commit.
- Never cross Gap, overlap, manual inconsistency, or unverifiable Legacy Reference boundaries.
- Prefer Era windows with no prior compression; otherwise choose lowest `compressionLevel`, then earliest window.

## History, Failure, and Migration

- Keep Episode, Arc, and Era page histories independent; HEAD has no user-visible history.
- Persist each active page in ascending derived-T order. Positional replacement/reorder revisions must store an exact node-ID snapshot when add/remove delta cannot reproduce order; load may repair only complete, T-verifiable pages.
- Create visible checkpoints for compression, user edits, and restore. Do not create visible Episode history for pure append.
- Compare each editor draft against the persisted node body. Keep a visible unsaved-loss warning and emphasized save action until a successful refresh supplies the saved node; AI regeneration candidates follow the same dirty-state rule.
- Regenerate a selected Episode from its raw source turns and a selected Arc/Era from its immutable direct children. Never use the visibly wrong node body as AI evidence. Return a review candidate without persistence; replace the active node only after explicit user checkpoint save. Different target nodes may generate concurrently and must not invalidate one another when an unrelated candidate is saved.
- Stream the complete growing `summary` for every regenerated tier into the editor through ordered UI-thread updates. Clear the prior attempt when validation retries; restore the pre-request draft after final failure. Keep all partial output runtime-only.
- Restore one page without silently rewriting other pages or HEAD. Surface resulting cross-page inconsistencies.
- Bind every AI task to the exact evidence it read, never the session-wide revision. Episode commit guards its source hashes, target pending membership, and absence of competing coverage; compression guards every immutable candidate node shown to the model; HEAD guards its own version, exact input sources, and the rendered Archive for `BACKFILL`. Reload and rebase onto current state; reject only target/evidence changes, pause/disable, or competing coverage. Unrelated chat append, HEAD update, node edit, page checkpoint, or memory addition must not invalidate the task.
- Persist each successful backfill Episode immediately. Failure must retain remaining gaps and completed nodes.
- Keep streamed backfill summaries and current phase as runtime UI state; persist committed Episode count and source progress, not partial model output.
- Persist each successful source-mutation root repair immediately. Failure must retain remaining roots and already committed replacements.
- Pause orphaned persisted backfill or source-repair `RUNNING` after process restart; never pause a runner active in current process.
- Load old JSON through defaults. Make repairs idempotent. Preserve unverifiable old memory as time-unknown Legacy Reference.
- SaveSlot carries current memory snapshot, not complete revision history or runtime task objects.
