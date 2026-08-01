# Long-Term Memory State Machines

## Normal Episode Generation

1. Find stable source turns outside direct context.
2. Exclude covered turns, durable gaps, current pending IDs, and permanent-clear history.
3. Append remaining IDs to ordinary Episode pending.
4. Group oldest continuous IDs into an exact N-turn batch. If trailing count is below N, leave it in normal pending with IDLE status and no Gap/warning/model call.
5. Send all N raw turns together and generate exactly one aggregate Episode `summary`; never request per-turn summaries or coverage text.
6. State the source-count target in the prompt (1T=50 characters, +20 each additional T, 6T=150), but enforce a program hard limit at twice that target (1T=100 through 6T=300). Build structural coverage from ordered source IDs, semantic fingerprints, and summary.
7. Run budget maintenance before commit.
8. Save immutable Episode, append active Episode page, then remove committed pending IDs.
9. On failure, preserve pending IDs and raw chat; expose error.

- Before Episode commit, reload current state and require only exact source fingerprints, continued target-pending membership, and no active node covering those target sources. Rebase unrelated HEAD/page/chat revisions instead of rejecting them.
- Application coordinator owns automatic and manual Archive→HEAD maintenance. It deduplicates current-session work and outlives the memory page; switching sessions prevents new old-session work without cancelling an already-started model call.
- Automatic historical catch-up commits at most one Episode per coordinator lease. If another exact batch is ready, schedule another pass after releasing the global runner lock. Retry backoff sleeps outside that lock so queued backfill or other maintenance can proceed.
- Manual gap backfill also runs in the application coordinator. Keep streaming progress process-local in the coordinator; page ViewModels subscribe and refresh persisted state after completion instead of owning the job.
- Trigger coordinator on session load, persisted assistant reply, network restoration, manual retry, and orphaned `UPDATING` recovery. Use `WAITING_FOR_NETWORK` without sending a model request when endpoint-specific network requirements fail.

## HEAD

```text
BEFORE_PROMPT
  → run beside RAG retrieval
  → INITIALIZE blank new-chat HEAD from opening + first complete round when third user round starts
  → or UPDATE existing HEAD with exactly next eligible baseline group
  → await attempt before assembling roleplay request

AFTER_REPLY
  → background UPDATE only when HEAD already exists and exactly one baseline group is next

BACKFILL
  → keep backfill RUNNING
  → rebuild from compiled Archive + penultimate stable baseline group
  → never inherit old HEAD
```

- Keep latest complete group raw at prompt tail; target the immediately preceding group.
- Treat historical blank HEAD, a watermark more than one group behind, or a Gap-crossing path as requiring explicit backfill. Omit invalid or blank HEAD from injection.
- On HEAD failure, preserve previous or blank HEAD, expose the error, and let the waiting roleplay request continue after the failed attempt finishes.
- `INITIALIZE`/`UPDATE` commit binds current HEAD version plus exact input-source fingerprints. `BACKFILL` additionally binds the exact rendered Archive supplied to AI. Session-wide revision changes and unrelated later chat do not invalidate HEAD.
- When long-term memory is disabled, omit Archive, HEAD, and timeline constraint entirely.

## Manual Regeneration

```text
FULL_REGENERATION confirmation
  → preflight model/auth/background network protection before destructive reset
  → delete derived Archive/HEAD/revisions/gaps/manual edits; preserve raw chat and session limit
  → persist fullRegenerationPending=true with a fresh current source timeline and no clear watermark
  → register every currently archivable raw source as a fresh durable Gap
  → enter the same application-owned backfill runner and runtime progress stream as one-click backfill
  → generate exact-N historical Episode batches, committing and shrinking Gap work after every batch
  → move a final below-N segment to ordinary pending; never generate an illegal edge singleton
  → pause on budget decision, ERROR, or WAITING_FOR_NETWORK without resetting again
  → run the shared BACKFILL HEAD phase after all legal Episode batches finish
  → clear fullRegenerationPending only after backfill reaches IDLE and HEAD has no error

HEAD_REGENERATION confirmation
  → preserve Archive and old HEAD
  → generate BACKFILL HEAD from currently injectable Archive + latest stable baseline
  → permit existing Gap, excluded stale roots, or Archive budget decision to remain independent
  → commit only if HEAD version, exact baseline fingerprints, and rendered Archive still match
  → replace old HEAD on success; preserve it and expose HEAD error on failure
```

- Both jobs belong to the application coordinator and use shared foreground/background protection; leaving the page does not cancel a paid model call. Full regeneration exposes the normal backfill phase, T range, source-turn count, committed Episode count, streaming summary, pause, and retry UI instead of a separate opaque progress state.
- Manual regeneration and manual Gap backfill are mutually exclusive. Normal maintenance yields when a manual job is scheduled.
- Full-regeneration `ERROR` does not silently loop expensive model calls. User retry/session maintenance resumes the persisted unfinished rebuild from committed Archive state.

## Backfill

Explicit memory-page refresh first reloads current context-window settings, source turns, timeline, active coverage, normal pending, gaps, and fingerprints. A latest uncovered suffix becomes normal pending; only uncovered history bounded by later active memory becomes a durable Gap. Refresh itself never calls AI.

```text
IDLE / PAUSED / ERROR
  → runtime WAITING_FOR_ARCHIVE while an Archive atomic step owns the coordinator lock; chat remains enabled
  → calculate eligible durable Gap sources
  → register active in-process runner
  → persist RUNNING with fixed ordered pending IDs
  → generate exact captured-N Episode batches oldest-first
  → permit one final singleton only for a live continuous internal hole bounded by active memory on both sides
  → validate fixed batch membership + source fingerprint + program-owned structural coverage
  → run budget maintenance
  → atomically save Episode and remove only committed Gap/pending IDs
  → repeat
  → keep RUNNING while BACKFILL HEAD uses compiled Archive + penultimate stable baseline group
  → IDLE only after HEAD attempt completes
```

- Save every successful batch immediately; interruption resumes from remaining IDs.
- Before selecting a backfill batch, promote any uncovered turn bounded by later active memory from ordinary pending to a durable Gap. Internal reload after the model call must retain that source in backfill pending until Episode commit; otherwise `canContinue` would silently abort a valid result. If any undersized old segment still remains, keep it pending but scan later continuous segments for the next exact-size batch; one incomplete segment never blocks unrelated later work.
- Allow up to five total AI output-validation attempts for every backfill stage, including Episode generation, Archive compression, and final HEAD rebuild.
- A disabled-period Gap is explicitly backfillable even while still inside direct context, but a user-only open source turn waits for its assistant reply; normal HEAD UPDATE never crosses it.
- User pause takes effect after current atomic model call.
- Process restart loses runner registration; convert orphaned persisted `RUNNING` to `PAUSED`.
- Internal service reads during live run must retain `RUNNING`.
- On error set `ERROR`, keep remaining Gap/pending IDs, display concrete cause, and allow retry.
- Budget expansion or compression decisions may pause backfill; do not mark it complete.
- A remaining count from 2 through N-1 waits without crossing a Gap or overlapping existing nodes. Never reread N between batches.
- During a live batch, expose source progress, phase, T range, committed Episode count, and streamed aggregate summary. Never persist partial streamed output.
- Only lock acquisition changes runtime phase from `WAITING_FOR_ARCHIVE` to `PREPARING`; only persisted backfill `RUNNING` blocks chat.

## Historical Source Mutation Repair

Refresh/load compares active Archive and HEAD semantic fingerprints with current chat sources. Detection never calls AI. Once stale, affected old summary/HEAD stops injection before repair begins.

```text
IDLE / PAUSED / ERROR
  -> user presses repair on memory page
  -> persist fixed ordered stale root IDs + stale-HEAD flag as RUNNING
  -> repair one active root outside state lock
     -> Episode: regenerate each current continuous raw-source run; deleted run may vanish
     -> Arc/Era: recursively repair children; rebuild only exact continuous one-to-one parent
     -> otherwise promote safe repaired child frontier across deletion boundary
  -> reload and validate root identity + current source fingerprints
  -> atomically save immutable replacements, active-page checkpoints, and remaining-root progress
  -> repeat
  -> rebuild HEAD from repaired Archive, or clear it when no legal baseline exists
  -> IDLE
```

- User pause takes effect after current model call; uncommitted result is discarded and root remains pending.
- Process restart converts orphaned `RUNNING` to `PAUSED`; current-process runner keeps `RUNNING`.
- On failure set `ERROR`, retain pending roots and completed commits, show exact reason, and allow retry.
- New source mutations discovered mid-run prevent final completion and remain repairable.
- Stale user-authored nodes stop automatic repair and require explicit editor save.
- While repair is required/running, hide backfill action; while running, block chat send like backfill.

## Budget Compression

- Before every tier compression, request one plain-text selection instruction from the same candidate children and forced IDs. Planner prompt targets at most 50 characters, uses `maxTokens=128`, clears inherited thinking settings, disables thinking where supported, and exposes no reasoning. Do not program-check its length, persist it, or treat it as evidence.
- Planner and final compressor each receive five output attempts. Truncation/empty/invalid output consumes one output attempt; transient request failures use a separate three-request budget. Keep planner at 128 output tokens. For final JSON compression, double the current output limit after truncation and carry it into later attempts, capped at 4096 or the configured model bound. Authentication, non-retryable HTTP errors, and cancellation stop immediately.
- Send planner output and original children to the compression request. Compression prompt targets one plain, objective 60–300-character causal/state summary; program accepts 50–400. Reject per-child retelling, ornate scene description, and non-shrinking output.

### Episode → Arc

- Enter only when staged Episode would exceed active Archive budget.
- Consume the oldest continuous prefix of 3–10 Episodes. Input may contain up to 15 children; positions 11–15 are trailing reference-only candidates for judging event boundaries.
- Generate one selective event summary. Retain only high-impact, story-bearing detail; do not allocate prose per child or concatenate Episode summaries.
- AI may return `compressible=false`. Preserve at least newest Episode.

### Arc → Era

- Attempt only after Episode → Arc returns false and budget still fails.
- Consume the oldest continuous prefix of 3–10 Arcs. Input may contain up to 15 children; positions 11–15 are trailing reference-only candidates so newer Arc context remains visible.
- Generate one selective story-stage summary rather than concatenating Arc summaries.
- AI may return false. Preserve at least newest Arc.

### Era → Era

- Attempt only after earlier tiers return false, budget still fails, and current decision did not expand budget.
- Consume a program-selected continuous prefix of 2–5 Eras. Candidate selection must provide more than 50 source characters so the minimum legal summary can still reduce text.
- Generate one selective long-range story summary.
- AI cannot return false. Set parent level to maximum child level plus one.
- Select oldest never-compressed legal window first; otherwise lowest-level then earliest window.

- Compression commit reloads and compares every candidate node shown to AI, including reference-only candidates. Target candidate edit/replacement/staleness rejects the result; unrelated node/page/HEAD/chat changes are rebased and preserved.
- Before reporting “no legal Era candidate,” run expansion-decision logic while below 20000. Only an already-refused choice or maximum limit may become explicit structural failure.

## Expansion Decisions

- Ask before compression AI call according to tier prompt state.
- On either button, first persist `pendingDecision=null`, the selected refusal/expansion flags, session limit if expanded, and Archive `IDLE`; hide the dialog/card immediately. This phase performs no model call and does not resolve model configuration.
- Then enqueue exactly one application-owned continuation. Wait for the full-regeneration, normal-Archive, or backfill runner that raised the decision to unwind before resuming its matching flow; a later valid tier choice must not be dropped as a duplicate of an older continuation.
- Expanding adds 2000 to current session, abandons current compression attempt, and never restores older lossy summaries.
- Episode and Arc refusal silences later prompts for that tier; acceptance asks again next time.
- Era refusal permits five successful Era compressions, then asks again before sixth.
- At 20000, stop offering expansion and continue legal compression.

## Scoped Evidence and Commit Boundary

1. Capture task parameters and exact evidence actually sent to AI: source IDs/fingerprints, immutable candidate nodes, target HEAD version, and `BACKFILL` Archive text as applicable. Do not capture session-wide revision as a conflict key.
2. Perform AI call outside state lock.
3. Reload state. Reject only changed target evidence, target removal/replacement/staleness, pause/disable, or competing coverage of the same source. Preserve unrelated chat, HEAD, page, and node changes.
4. Run maintenance against reloaded state. Before final save, atomically reload once more, repeat scoped validation, validate coverage/budget, and rebase the result onto that current state.
5. Save journal first, immutable node/revision/transaction next, active state pointer last, then remove journal. Session revision remains a monotonic persistence marker, not a task lock.
6. Never clear source or gap state after output-validation retries are exhausted.

## Selected Node Regeneration

1. Require one active Episode, Arc, or Era node with verifiable persisted coverage.
2. Capture the selected immutable node and evidence hash. Episode evidence is its raw source turns; Arc/Era evidence is its ordered immutable direct children. Do not bind this review-only task to the session-wide revision.
3. Re-run the tier's native generation protocol outside the state lock. The current node body is display-only and never enters AI evidence.
4. Stream each complete growing `summary` snapshot to the editor in callback order. At each validation retry, clear the invalid prior attempt before showing the new stream.
5. Require exact original child consumption for Arc/Era and the normal Episode length/summary validation for Episode.
6. Reload state and reject only when this target is no longer active or its node/raw source/child evidence changed. Allow unrelated node checkpoints and run different target nodes concurrently.
7. Return the validated body to the editor as an unsaved candidate. Only explicit checkpoint save replaces the active node; final failure restores the pre-request editor draft and leaves persisted memory unchanged.

## Minimum Regression Matrix

- Context expand then shrink: durable Gap survives and eligibility reappears.
- Live backfill internal reload: remains `RUNNING`; process-restart residue becomes `PAUSED`.
- Backfill batches: Episode persists and Gap shrinks per batch.
- Fixed batches: N=2 never emits a normal singleton; 3 pending becomes 2 + normal wait 1; historical internal 3 becomes 2 + bounded 1; disabled/deleted/declined Gap never gets singleton exception.
- Semantic fingerprint: `updatedAt` and order-key normalization stay current; body/alternative/message order/image/deletion changes go stale; safe legacy migration is idempotent.
- Lifecycle: disconnected request waits, network restoration resumes, orphan `UPDATING` converges, page destruction does not cancel manual maintenance, session switch schedules no new old-session work, retry backoff holds no global lock, and queued backfill does not block chat before lock acquisition.
- Request isolation: memory sends no thinking budget or roleplay sampling controls, uses one token field, capability-gates JSON Mode/off control, retries transient transport separately from output validation, and treats auth as non-retryable.
- Retry accounting: planner/Episode/compressor/HEAD exhaust exactly five output attempts; retryable transport exhausts exactly three requests; non-retryable request and cancellation stop once; truncation growth persists across JSON attempts; final failure reports stage and count.
- Journal recovery: crash injection after journal/dependency/revision/state writes either completes or discards idempotently without deleting reachable nodes.
- Backfill failure: reason visible; retry retains remaining work.
- Scoped concurrency: unrelated chat append/HEAD/node edit/addition during Episode or compression still commits and is preserved; changing target source, target candidate, target HEAD, or `BACKFILL` Archive rejects only that task.
- Disabled-gap backfill: user-only open source turn is excluded until an assistant reply makes it stable.
- Historical edit/delete: stale root and stale HEAD stop injection before manual repair.
- Source repair restart/pause/failure: pending roots survive; committed roots remain committed.
- Deleted interior turn: Episode splits into continuous runs; Arc/Era never rebuild across the gap.
- Safe frontier: inject only current descendants when entire expansion fits budget; otherwise omit stale root.
- Source-turn grouping: appended replies stay in one context/RAG block.
- Compression: new lower tiers accept only 3–10 consumed children, new Era self-compression only 2–5; reject skip, overlap, reorder, duplicate, fake range, missing program-owned ancestry, summary outside 50–400 characters, and non-shrinking output. Planner remains runtime-only and unconstrained by program character validation. Legacy larger parents remain regenerable.
- Preview: contains unlabeled ordered Archive bodies + HEAD only; no per-node tier/T labels.
- SaveSlot and old JSON: repeated migration/import remains idempotent and preserves unverifiable data.
