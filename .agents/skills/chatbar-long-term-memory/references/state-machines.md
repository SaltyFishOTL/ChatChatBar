# Long-Term Memory State Machines

## Normal Episode Generation

1. Find stable source turns outside direct context.
2. Exclude covered turns, durable gaps, current pending IDs, and permanent-clear history.
3. Append remaining IDs to ordinary Episode pending.
4. Group oldest continuous 1–N IDs; do not shorten solely because Archive budget is tight.
5. Send all 1–N raw turns together and generate exactly one aggregate Episode `summary`; never request per-turn summaries or coverage text.
6. State the source-count target in the prompt (1T=50 characters, +20 each additional T, 6T=150), but enforce a program hard limit at twice that target (1T=100 through 6T=300). Build structural coverage from ordered source IDs, source hashes, and summary.
7. Run budget maintenance before commit.
8. Save immutable Episode, append active Episode page, then remove committed pending IDs.
9. On failure, preserve pending IDs and raw chat; expose error.

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
- When long-term memory is disabled, omit Archive, HEAD, and timeline constraint entirely.

## Backfill

Explicit memory-page refresh first reloads current context-window settings, source turns, timeline, active coverage, normal pending, gaps, and source hashes. Newly archived uncovered turns become durable gaps; refresh itself never calls AI. Repeated refresh is idempotent.

```text
IDLE / PAUSED / ERROR
  → calculate eligible durable Gap sources
  → register active in-process runner
  → persist RUNNING with fixed ordered pending IDs
  → generate one Episode batch
  → validate revision + source hash + program-owned structural coverage
  → run budget maintenance
  → atomically save Episode and remove only committed Gap/pending IDs
  → repeat
  → keep RUNNING while BACKFILL HEAD uses compiled Archive + penultimate stable baseline group
  → IDLE only after HEAD attempt completes
```

- Save every successful batch immediately; interruption resumes from remaining IDs.
- Allow up to five total AI output-validation attempts for every backfill stage, including Episode generation, Archive compression, and final HEAD rebuild.
- A disabled-period Gap is explicitly backfillable even while still inside direct context; normal HEAD UPDATE never crosses it.
- User pause takes effect after current atomic model call.
- Process restart loses runner registration; convert orphaned persisted `RUNNING` to `PAUSED`.
- Internal service reads during live run must retain `RUNNING`.
- On error set `ERROR`, keep remaining Gap/pending IDs, display concrete cause, and allow retry.
- Budget expansion or compression decisions may pause backfill; do not mark it complete.
- During a live batch, expose source progress, phase, T range, committed Episode count, and streamed aggregate summary. Never persist partial streamed output.

## Budget Compression

### Episode → Arc

- Enter only when staged Episode would exceed active Archive budget.
- Send oldest continuous 4–25 Episodes; fewer than 4 returns false without AI.
- Consume continuous prefix 4–20; items 21–25 are reference-only.
- AI may return `compressible=false`. Preserve at least newest Episode.

### Arc → Era

- Attempt only after Episode → Arc returns false and budget still fails.
- Send 4–25 continuous Arcs; consume prefix 4–20; fewer than 4 returns false.
- AI may return false. Preserve at least newest Arc.

### Era → Era

- Attempt only after earlier tiers return false, budget still fails, and current decision did not expand budget.
- Send at most 15 continuous Eras and consume 3–10. Fewer than 3 is explicit failure.
- AI cannot return false. Set parent level to maximum child level plus one.
- Select oldest never-compressed legal window first; otherwise lowest-level then earliest window.

## Expansion Decisions

- Ask before compression AI call according to tier prompt state.
- Expanding adds 2000 to current session, abandons current compression attempt, and never restores older lossy summaries.
- Episode and Arc refusal silences later prompts for that tier; acceptance asks again next time.
- Era refusal permits five successful Era compressions, then asks again before sixth.
- At 20000, stop offering expansion and continue legal compression.

## Revision and Commit Boundary

1. Capture source IDs, source hash, page base revision, and task parameters.
2. Perform AI call outside state lock.
3. Reload state and reject changed source, revision, user edit, restore, pause, or competing commit.
4. Validate coverage and budget before any active-page switch.
5. Save immutable node, page revision/transaction, active state, and pending reduction as one logical commit.
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
- Backfill failure: reason visible; retry retains remaining work.
- Source-turn grouping: appended replies stay in one context/RAG block.
- Compression: reject skip, overlap, reorder, duplicate, fake range, missing coverage, and non-shrinking output.
- Preview: contains unlabeled ordered Archive bodies + HEAD only; no per-node tier/T labels.
- SaveSlot and old JSON: repeated migration/import remains idempotent and preserves unverifiable data.
