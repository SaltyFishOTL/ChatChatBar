---
name: chatbar-novelai-prompt
description: Maintain ChatBar project rules for NovelAI image prompt generation. Use when changing NovelAI prompt design, NovelAI system prompt usage, reference-image reverse prompting, NAI tag conversion, or code that builds NovelAI prompts for generated images.
---

# ChatBar NovelAI Prompt

## Scope

Use before editing NovelAI-related prompt flow:

- `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiPromptDesigner.kt`
- code that converts image intent into NovelAI tags
- code that adds prompt text for NovelAI image generation

Do not use this skill for non-NAI image planning, UI, storage, scheduling, memory, chat behavior, or feature policy unless those changes alter NovelAI prompt construction.

Modifying existing NovelAI prompt text requires user confirmation. Adding new NovelAI prompt text for a requested feature does not require separate confirmation.

Use chatbar-image-generation-runtime for NovelAI HTTP generation, streaming frames, retries, metadata, regeneration, concurrency, or file replacement.

## Shared NovelAI Entry

- `NOVELAI_IMAGE_PROMPT_SYSTEM` is the shared system prompt for NovelAI tag design.
- Reuse it through the feature-appropriate `PromptTemplates` helper: the composed system helper for card-backed flows and `novelAiImagePromptCoreSystem()` for the prompt tool.
- Prompt-tool reference-image reverse design appends `PromptTemplates.novelAiImagePromptReferenceImageUser()` and still uses the shared system prompt. Keep `referenceImageProvided` independent from direct image payloads so vision-model description fallback retains this instruction.
- `ImagePromptToolViewModel` sends the source image directly when the selected design model is multimodal; otherwise `ImageUnderstandingService` produces a description for the same shared NovelAI prompt flow.
- Character-card cover image user prompt lives in `PromptTemplates.novelAiImagePromptCharacterCard(...)`; `NovelAiPromptDesigner` should call it instead of embedding cover prompt text.
- Default negative tags live in `PromptTemplates.DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT`; card-level `CharacterCard.defaultImageNegativePrompt` flows into `NovelAiPromptPlan.negativePrompt`.
- Multi-character designer output contains character `caption` only; do not request per-character centers. `NovelAiImageService` keeps V4 `use_coords=false` so NovelAI chooses placement.
- Character avatar generation goes through `NovelAiPromptDesigner` shared NovelAI flow: card style is passed as `Preset style prompt`, current person tags as `Character preset prompts`, and the avatar user task asks the AI to keep them in `baseCaption` and `characters[].caption` unless they explicitly conflict. Add global image Prompt preference plus `PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS`, then append the fixed tags to the final plan. Freeform source is page-local manual positive Prompt. Output size is always `1024x1024`.
- Do not add feature-specific NovelAI system prompt constants.
- Do not add full feature-specific NAI templates such as `NOVELAI_IMAGE_PROMPT_MOMENT_TEMPLATE`.
- If feature needs extra visual guidance, add a small `PromptTemplates` helper that supplies only modifiers: target style, composition preference, mood, brief image intent.

## Prompt Shape

Preferred flow:

1. Feature code or feature AI produces short image intent.
2. `NovelAiPromptDesigner` sends shared NovelAI system prompt.
3. User prompt asks for NAI tags using minimal modifiers.
4. Output stays tag-focused and avoids app implementation context.

Keep backend limits, scheduling rules, UI state, storage details, and business policy out of NovelAI prompt text unless directly needed for visual result.

## Current Project Rule

For 朋友圈 image generation, keep using shared `NOVELAI_IMAGE_PROMPT_SYSTEM`. Add only small modifiers such as target photo style or suitable composition. Do not create separate moment-specific NAI prompt template.

## Verification

After NAI prompt-flow code changes:

- Run `.\gradlew.bat :app:compileDebugKotlin` from `app/`.
- Run `.\gradlew.bat test` when prompt helpers, parsing, or generation decisions change.
