---
name: chatbar-long-term-memory
description: Maintain and diagnose ChatBar long-term memory v2 across source-turn identity, derived T labels, HEAD, Episode/Arc/Era Archive, durable gaps and backfill, compression budgets, tier revisions, prompt injection, SaveSlot migration, and memory UI. Use when changing long-term memory behavior or when chat/context/RAG grouping changes may affect source-turn boundaries.
---

# ChatBar Long-Term Memory

Preserve timeline coverage and user data across every layer. Treat memory changes as persisted state-machine changes, not isolated UI or prompt edits.

## Required Reads

1. Read `doc/long_term_memory_v2_handoff.md` for current work, evidence, and next test. Verify volatile claims in code.
2. Read [references/invariants.md](references/invariants.md) before any change.
3. Read [references/state-machines.md](references/state-machines.md) when touching Episode generation, backfill, compression, revisions, concurrency, or recovery.
4. Read target implementation and tests before editing.

## Entry Points

- Persisted models: `data/local/entity/LongTermMemory.kt`, `ChatMessage.kt`, `ChatSession.kt`, `AppSettings.kt`, `SaveSlot.kt`.
- Storage: `data/repository/MemoryRepository.kt`, `ChatRepository.kt`.
- Core behavior: `domain/memory/LongTermMemoryService.kt` and focused policies under `domain/memory/`.
- Timeline/context boundaries: `domain/chat/TimelineTurnPolicy.kt`, `TimelineArchiveBoundaryPolicy.kt`, `ContextWindowManager.kt`.
- Injection: `domain/chat/PromptAssembler.kt`; AI task templates: `domain/prompt/PromptTemplates.kt`.
- RAG boundary consumers: `domain/rag/ChatMemoryIndexPolicy.kt`, `RagManager.kt`, `RagRepository.kt`.
- UI orchestration: `ui/chat/ChatViewModel.kt`, `ChatSettingsDialog.kt`, `ChatScreen.kt`.
- Verification: matching JVM tests under `app/app/src/test/` and Compose tests under `app/app/src/androidTest/`.

All source paths above are relative to `app/app/src/main/java/com/example/chatbar/` unless stated otherwise.

## Workflow

1. Classify change: identity, context boundary, Archive/HEAD, gap/backfill, compression, history, injection, migration, or UI.
2. Write affected invariants and state transitions before editing.
3. Trace source-of-truth fields from persisted entity through service, ViewModel, and UI. Do not encode policy independently in multiple layers.
4. Check adjacent consumers whenever source-turn grouping changes: direct context, pending Episode input, display T, and RAG chunks.
5. Preserve old JSON with defaults and make repair/migration idempotent. Never infer lost summaries or split unverifiable coverage.
6. Add a pure policy test for each boundary and an integration/UI test for each user-visible transition.
7. Update handoff by replacing stale facts after verification or before asking for manual testing.

## Critical Review Checks

- Distinguish durable facts from current eligibility. Context changes may hide work; they must not erase missing-memory facts.
- Distinguish live in-process work from persisted crash residue. Internal reads must not cancel their own active task.
- Keep failed work visible and retryable. Preserve source data, pending ranges, gaps, and completed batches.
- Keep RAG storage independent from long-term memory while using consistent source-turn boundaries.
- Reject preview or injection changes that leak raw source turns through the long-term-memory block.
- Generate multi-turn Episode as one direct aggregate body. Keep coverage program-owned through ordered source IDs and hashes; never ask AI for per-turn proof text.
- Reject compression that skips, overlaps, reorders, duplicates, or invents coverage.

## Stop Conditions

Do not report completion while persisted compatibility, continuity checks, failure preservation, and relevant tests remain unknown. Record untested real-model and device flows in handoff instead of claiming them complete.
