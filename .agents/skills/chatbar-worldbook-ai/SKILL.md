---
name: chatbar-worldbook-ai
description: Maintain ChatBar world-book AI creation, blank-content filling, candidate preview/apply, research sources, checkpoints, and related service tests.
---

# ChatBar World Book AI

Read these entry points before broad search.

## First Read

- UI, source selection, candidate preview, resume controls: `app/app/src/main/java/com/example/chatbar/ui/worldbook/WorldBookEditScreen.kt`.
- State, generation orchestration, checkpoint compatibility, apply/save split: `app/app/src/main/java/com/example/chatbar/ui/worldbook/WorldBookEditViewModel.kt`.
- Batched creation/fill, JSON parsing and repair, candidate merge: `app/app/src/main/java/com/example/chatbar/domain/worldbook/WorldBookAiService.kt`.
- Encyclopedia/manual-page/reference-document preparation: `app/app/src/main/java/com/example/chatbar/domain/search/WorldBookResearchService.kt` and `CharacterReferenceDocumentRetriever.kt`.
- Model-facing text: `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`; use `chatbar-prompt-pipeline` for prompt assembly changes.
- Wiring/settings: `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt` and `data/local/entity/AppSettings.kt`.
- Tests: `WorldBookAiServiceTest.kt` and `WorldBookEntryModalStateTest.kt` under `app/app/src/test/java/com/example/chatbar/`.

## Domain Rules

- Creation produces at most 50 candidates in batches of 5. Blank-content fill freezes target entries at task start and processes batches of 5.
- Generated results remain selectable candidates. Applying selected candidates updates editor draft only; normal world-book save remains separate.
- Resume reuses completed batches only when operation input, selected model, research material, and target book signature remain compatible. Changed input invalidates checkpoint and starts fresh.
- Research modes share `CharacterResearchSourceMode`: offline, encyclopedia search, specified URLs, or both. One transient reference document may augment every mode; source files and fetched pages are not persisted into world book.
- Keep candidate identity stable across checkpoints. Fill may update only selected target IDs with nonblank content; creation appends selected new entries without rewriting current entries.
- Parsing/repair failures and unusable requested research must stay visible. Partial encyclopedia/manual-source failures may continue only when other usable requested material exists.

## Workflow

1. Trace UI operation through ViewModel into `WorldBookAiService` and research service.
2. Preserve candidate-preview, explicit-apply, checkpoint, and draft-save boundaries.
3. Update inline-fixture JVM tests for batching, parsing, merge, or resume behavior.

## Verification

Run focused JVM tests, then repository release checks when publishing.
