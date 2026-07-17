---
name: chatbar-long-term-memory
description: Maintain and diagnose ChatBar long-term memory v2 across source-turn identity, derived T labels, HEAD, Episode/Arc/Era Archive, durable gaps and backfill, historical source-mutation detection and manual repair, compression budgets, tier revisions, prompt injection, SaveSlot migration, and memory UI. Use when changing long-term memory behavior or when chat/context/RAG grouping or historical message edits/deletes may affect source-turn evidence.
---

# ChatBar Long-Term Memory

Preserve timeline coverage and user data across every layer. Treat memory changes as persisted state-machine changes, not isolated UI or prompt edits.

## Required Reads

1. Read doc/long_term_memory_v2_handoff.md for current work, evidence, and next test. Verify volatile claims in code.
2. Read references/invariants.md before any change.
3. Read references/state-machines.md when touching Episode generation, backfill, source repair, compression, revisions, concurrency, or recovery.
4. Read target implementation and tests before editing.

## Entry Points

- Persisted models: data/local/entity/LongTermMemory.kt, ChatMessage.kt, ChatSession.kt, AppSettings.kt, SaveSlot.kt.
- Storage: data/repository/MemoryRepository.kt, ChatRepository.kt.
- Core behavior: domain/memory/LongTermMemoryService.kt, MemoryHeadUpdatePolicy.kt, MemoryAiGateway.kt, and focused policies under domain/memory/.
- Historical source repair: domain/memory/MemorySourceRepairPolicy.kt and MemorySourceRepairProgress.kt.
- Timeline/context boundaries: domain/chat/TimelineTurnPolicy.kt, TimelineArchiveBoundaryPolicy.kt, ContextWindowManager.kt, ChatHistoryPromptPolicy.kt.
- Injection: domain/chat/ChatRequestMemoryPolicy.kt, PromptAssembler.kt; actual-request diagnostics: utils/DebugLogManager.kt, ui/chat/DebugLogDialog.kt; AI task templates: domain/prompt/PromptTemplates.kt.
- RAG boundary consumers: domain/rag/ChatMemoryIndexPolicy.kt, RagManager.kt, RagRepository.kt.
- UI orchestration: ui/chat/ChatViewModel.kt, ChatSettingsDialog.kt, ChatScreen.kt.
- Verification: matching JVM tests under app/app/src/test/ and Compose tests under app/app/src/androidTest/.

All source paths above are relative to app/app/src/main/java/com/example/chatbar/ unless stated otherwise.

Use chatbar-prompt-pipeline when changing general prompt layering or final message order. Long-term-memory delivery still requires this skill because Archive/HEAD have stricter evidence and injection rules.

## Workflow

1. Classify change: identity, context boundary, Archive/HEAD, gap/backfill, source mutation repair, compression, history, injection, migration, or UI.
2. Write affected invariants and state transitions before editing.
3. Trace source-of-truth fields from persisted entity through service, ViewModel, and UI. Do not encode policy independently in multiple layers.
4. Check adjacent consumers whenever source-turn grouping changes: direct context, pending Episode input, display T, and RAG chunks.
5. Preserve old JSON with defaults and make repair/migration idempotent. Never infer lost summaries or split unverifiable coverage.
6. Add a pure policy test for each boundary and an integration/UI test for each user-visible transition.
7. Update handoff by replacing stale facts after verification or before asking for manual testing.

## Critical Review Checks

- Distinguish durable facts from current eligibility. Context changes may hide work; they must not erase missing-memory facts.
- Distinguish live in-process work from persisted crash residue. Internal reads must not cancel their own active task.
- Keep failed work visible and retryable. Preserve source data, pending ranges, gaps, source-repair roots, and completed batches.
- Keep RAG storage independent from long-term memory while using consistent source-turn boundaries.
- Reject preview or injection changes that leak raw source turns through the long-term-memory block.
- Generate multi-turn Episode as one direct aggregate body. Keep coverage program-owned through ordered source IDs and hashes; never ask AI for per-turn proof text.
- Keep HEAD modes distinct: initialize from opening plus first round, update by exactly one baseline group, and backfill from compiled Archive plus latest eligible baseline group.
- Detect historical source edits/deletes without calling AI. Stop stale roots and stale HEAD from injection before repair.
- Start source repair only from explicit user action. Rebuild immutable dependencies; never accept old summary text by updating only its hash.
- Never rebuild an Episode/Arc/Era across a deleted source-turn gap.
- Reject compression that skips, overlaps, reorders, duplicates, or invents coverage.

## Stop Conditions

Do not report completion while persisted compatibility, continuity checks, failure preservation, stale-source safety, and relevant tests remain unknown. Record untested real-model and device flows in handoff instead of claiming them complete.
