---
name: chatbar-character-card-ai
description: Maintain ChatBar character-card AI workflows. Use when changing AI auto-fill, AI rewrite, rewrite candidate preview, apply candidate behavior, text diff display, generated cover or per-character avatar candidates, character-card merge/materialize logic, or tests around CharacterAutoFillService and CharacterRewriteService.
---

# ChatBar Character Card AI

Keep AI role-card work narrow. Read these files before broad search.

Use chatbar-model-request-runtime for provider parameters, model fallback, authentication, or streaming transport. Use chatbar-image-generation-runtime for shared NovelAI HTTP generation, retry, metadata, regeneration, and file lifecycle. Keep card candidate/apply policy in this skill.

## First Read

- UI dialog, candidate preview, diff display: `app/app/src/main/java/com/example/chatbar/ui/character/CharacterEditScreen.kt`
  - Search anchors: `CharacterAutoFillDialog`, `CharacterRewriteDialog`, `AutoFillDraftPreview`, `RewriteCandidatePreview`, `RewriteDiffPreview`.
- UI state, generation triggers, candidate apply, cover handoff: `app/app/src/main/java/com/example/chatbar/ui/character/CharacterEditViewModel.kt`
  - Search anchors: `generateAutoFillDraft`, `generateRewriteDraft`, `applyAutoFillDraft`, `applyRewriteDraft`, `generateRewriteCoverImageCandidate`, `generateCharacterAvatar`, `buildRewriteDiff`.
- Generation, parsing, materialization, merge: `app/app/src/main/java/com/example/chatbar/domain/card/CharacterAutoFillService.kt`, `app/app/src/main/java/com/example/chatbar/domain/card/CharacterRewriteService.kt`.
- Tests: `app/app/src/test/java/com/example/chatbar/domain/card/CharacterAutoFillServiceTest.kt`, `app/app/src/test/java/com/example/chatbar/domain/card/CharacterRewriteServiceTest.kt`.

## Domain Rules

- If AI output seems wrong, first inspect service parsing/materialization and UI merge paths.
- Preserve data: applying a candidate mutates in-memory edit fields only; saving remains separate.
- Preserve mode split: structured mode uses `characters`; freeform mode uses `freeformCharacterText`.

## Rewrite Model

- `CharacterRewriteService.rewriteStreaming` returns a materialized draft: omitted existing fields are filled from current card.
- `mergeInto(current, draft)` is source of truth for post-apply card content.
- Existing structured characters must keep stable `id`; new characters get generated ids; deletions use `deleteCharacterIds`.
- Diff UI should compare `buildCurrentCard(markDirty = false)` against `mergeInto(current, draft)`, not raw AI JSON.
- Cover candidate applies to both `avatar` and `chatBackground` only when candidate is applied.
- Per-character avatar candidate uses `NovelAiPromptDesigner`, then NovelAI. Structured mode sends card style as `Preset style prompt` and `CharacterInfo.imagePrompt` as `Character preset prompts`; the avatar user task asks the AI to keep them in `baseCaption` and `characters[].caption` unless they explicitly conflict. Freeform mode sends temporary manual positive Prompt through the same shared flow. Designer receives global image Prompt preference plus `PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS`; final plan appends the same fixed tags, uses card negative Prompt, and generates square images.
- Avatar prompt debugging is in `CharacterAvatarImageUiState`: source input, Designer reasoning, Designer raw output, and final NovelAI positive Prompt are displayed by `CharacterAvatarEditor`.
- Failed cover/avatar retries reuse completed Prompt design; if NovelAI already returned a final image and only saving failed, retry saves those bytes without generating again. Changed avatar source input invalidates its checkpoint.
- Auto-fill/rewrite failure retry inherits completed image understanding, research plan, prepared research, and final raw output, then resumes from the next unfinished phase without clearing prior results. Cleaned source excerpts remain only until an AI-organized research brief succeeds; that brief then replaces the excerpts in UI state, checkpoints, and final card-generation input. Failed organization retains cleaned sources for fallback.
- Candidate dialogs expose a separate final-result retry. It clears only final raw output while retaining prepared research/image context; normal generate starts a fresh pipeline. Changed input, model, source image, or current card invalidates reuse.
- Auto-fill and rewrite dialogs own separate persisted encyclopedia-search choices in `AppSettings.characterAutoFillWebSearchEnabled` and `characterRewriteWebSearchEnabled`; no shared global-settings switch controls them. Changed search choice also invalidates prepared retry context.
- `CharacterInfo.appearanceImage` means character-owned chat/Moments avatar. It must not enter chat-model image understanding or character appearance text.

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
