---
name: chatbar-character-card-ai
description: Maintain ChatBar character-card AI workflows. Use when changing AI auto-fill, AI rewrite, rewrite candidate preview, apply candidate behavior, text diff display, generated cover or per-character avatar candidates, character-card merge/materialize logic, or tests around CharacterAutoFillService and CharacterRewriteService.
---

# ChatBar Character Card AI

Keep AI role-card work narrow. Read these files before broad search.

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
