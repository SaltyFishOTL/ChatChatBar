---
name: chatbar-character-card-ai
description: Maintain ChatBar character-card AI workflows. Use when changing AI auto-fill, AI rewrite, image-to-appearance fill, rewrite candidate preview, apply candidate behavior, text diff display, generated cover or per-character avatar candidates, character-card merge/materialize logic, or related service tests.
---

# ChatBar Character Card AI

Keep AI role-card work narrow. Read these files before broad search.

Use chatbar-model-request-runtime for provider parameters, model fallback, authentication, or streaming transport. Use chatbar-image-generation-runtime for shared NovelAI HTTP generation, retry, metadata, regeneration, and file lifecycle. Use chatbar-fish-audio-voice for character voice binding, transfer, availability, and generation behavior. Keep card candidate/apply policy in this skill.

## First Read

- UI dialog, candidate preview, diff display: `app/app/src/main/java/com/example/chatbar/ui/character/CharacterEditScreen.kt`
  - Search anchors: `CharacterAutoFillDialog`, `CharacterRewriteDialog`, `AutoFillDraftPreview`, `RewriteCandidatePreview`, `RewriteDiffPreview`.
- UI state, generation triggers, candidate apply, cover handoff: `app/app/src/main/java/com/example/chatbar/ui/character/CharacterEditViewModel.kt`
  - Search anchors: `generateAutoFillDraft`, `generateRewriteDraft`, `applyAutoFillDraft`, `applyRewriteDraft`, `generateRewriteCoverImageCandidate`, `generateCharacterAvatar`, `buildRewriteDiff`.
- Generation, parsing, materialization, merge: `app/app/src/main/java/com/example/chatbar/domain/card/CharacterAutoFillService.kt`, `CharacterRewriteService.kt`, `CharacterAppearanceImageService.kt`.
- External research modes, URL validation, webpage retrieval, cleaning, and brief generation: `app/app/src/main/java/com/example/chatbar/domain/search/SearchModels.kt`, `ManualWebPageRetriever.kt`, `CharacterResearchService.kt`, `ResearchCleaner.kt`, `LlmResearchBriefSummarizer.kt`.
- Tests: matching `CharacterAutoFillServiceTest.kt`, `CharacterRewriteServiceTest.kt`, and `CharacterAppearanceImageServiceTest.kt` under `app/app/src/test/java/com/example/chatbar/domain/card/`.

## Domain Rules

- If AI output seems wrong, first inspect service parsing/materialization and UI merge paths.
- Preserve data: applying a candidate mutates in-memory edit fields only; saving remains separate.
- Preserve mode split: structured mode uses `characters`; freeform mode uses `freeformCharacterText`.
- Treat `CharacterInfo.fishAudioVoice` as user-owned configuration. Auto-fill/rewrite output must not create, clear, or replace it; materialization and merge must preserve the current binding for retained character IDs.

## Rewrite Model

- `CharacterRewriteService.rewriteStreaming` returns a materialized draft: omitted existing fields are filled from current card.
- `mergeInto(current, draft)` is source of truth for post-apply card content.
- Existing structured characters must keep stable `id`; new characters get generated ids; deletions use `deleteCharacterIds`. Draft entries with missing or unknown `id` first name-match (case-insensitive) existing non-deleted characters as patches; redundant duplicates of an already-matched character are dropped, unmatched visible entries become additions. Both rewrite Json configs coerce `null` lists and tolerate trailing commas.
- Diff UI should compare `buildCurrentCard(markDirty = false)` against `mergeInto(current, draft)`, not raw AI JSON.
- Cover candidate applies to both `avatar` and `chatBackground` only when candidate is applied.
- Per-character avatar candidate uses `NovelAiPromptDesigner`, then NovelAI. Structured mode sends card style as `Preset style prompt` and `CharacterInfo.imagePrompt` as `Character preset prompts`; the avatar user task asks the AI to keep them in `baseCaption` and `characters[].caption` unless they explicitly conflict. Freeform mode sends temporary manual positive Prompt through the same shared flow. Designer receives global image Prompt preference plus `PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS`; final plan appends the same fixed tags, uses card negative Prompt, and generates square images.
- Avatar prompt debugging is in `CharacterAvatarImageUiState`: source input, Designer reasoning, Designer raw output, and final NovelAI positive Prompt are displayed by `CharacterAvatarEditor`.
- Failed cover/avatar retries reuse completed Prompt design; if NovelAI already returned a final image and only saving failed, retry saves those bytes without generating again. Changed avatar source input invalidates its checkpoint.
- Auto-fill/rewrite failure retry inherits completed image understanding, research plan, prepared research, and final raw output, then resumes from the next unfinished phase without clearing prior results. Cleaned source excerpts remain only until an AI-organized research brief succeeds; that brief then replaces the excerpts in UI state, checkpoints, and final card-generation input. Failed organization retains cleaned sources for fallback.
- Candidate dialogs expose a separate final-result retry. It clears only final raw output while retaining prepared research/image context; normal generate starts a fresh pipeline. Changed input, source image, reference document, research options, or current card content invalidates reuse; switching the generation model does not (research output is model-independent and the final step always re-runs with the current model).
- Resume reuse is gated by a content signature in `CharacterEditViewModel` (`generateAutoFillDraft`/`generateRewriteDraft`) that excludes card-level `id`, `createdAt`, `updatedAt`, and the selected model: `buildCurrentCard` falls back to a fresh random UUID and timestamps for new cards on every call, so any of these fields in the signature would make resume never match. The resume decision is visible as a progress line and logged under the `CharacterEditResume` logcat tag.
- Auto-fill and rewrite dialogs separately persist `CharacterResearchSourceMode` in `AppSettings.characterAutoFillResearchSourceMode` and `characterRewriteResearchSourceMode`. Modes are `NONE`, `ENCYCLOPEDIA_SEARCH`, `MANUAL_URLS`, and `ENCYCLOPEDIA_SEARCH_AND_MANUAL_URLS`; changed mode or normalized manual URL list invalidates prepared retry context.
- Manual URL input accepts up to five transient HTTP(S) URLs and reads only those pages through `HttpManualWebPageRetriever`. `MANUAL_URLS` never calls search planning or `SearchBackend.search`; combined mode runs both encyclopedia planning/search and manual retrieval, then merges both source sets. URL text and fetched page content are not persisted. Partial page failures remain visible; combined mode may continue with encyclopedia results, but no usable source across selected paths stops generation. Prepared-source retry skips downloading and searching again.
- Auto-fill and rewrite accept one transient TXT/MD/JSON reference document. `RagCharacterReferenceDocumentRetriever` matches user input plus current card against temporary document chunks, retrieves Top 20, then `CharacterResearchService` sends those hits through the same `ResearchCleaner`, brief summarization, checkpoint, and final-generation flow as encyclopedia results. Uploaded reference documents are not added to the card document library or persisted. Changed document content invalidates prepared retry context; document RAG failure must remain visible and stop generation instead of silently generating without the requested source.
- `CharacterInfo.appearanceImage` means character-owned chat/Moments avatar. It must not enter chat-model image understanding or character appearance text.
- Per-character image-to-appearance fill uses the current default chat model when multimodal; otherwise it requires that model's exact linked multimodal `visionModelId`. Results stay as an explicit appearance/clothing candidate until apply.

## Workflow

1. Read First Read files matching request.
2. Identify affected path: auto-fill, rewrite, apply, diff, cover, or tests.
3. Prefer local helpers and existing UI kit before adding new abstractions.
4. Add/update JVM tests for service, parser, materialization, or merge changes.
5. For UI-only behavior, compile and run CI-equivalent UI checks.

## Verification

Run from `app/`:

```powershell
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble
```

If an Android device is connected, use `chatbar-emulator-test` data-preserving install flow.
