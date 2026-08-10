---
name: chatbar-novelai-prompt
description: Maintain ChatBar project rules for NovelAI image prompt generation. Use when changing NovelAI prompt design, NovelAI system prompt usage, reference-image reverse prompting, NAI tag conversion, or code that builds NovelAI prompts for generated images.
---

# ChatBar NovelAI Prompt

## Scope

Use before editing NovelAI-related prompt flow:

- `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiPromptDesigner.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiTagResearchService.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiStyleCatalog.kt`
- `app/app/src/main/assets/presets/image_styles/default-image-styles.json`
- code that converts image intent into NovelAI tags
- code that adds prompt text for NovelAI image generation

Do not use this skill for non-NAI image planning, UI, storage, scheduling, memory, chat behavior, or feature policy unless those changes alter NovelAI prompt construction.

Modifying existing NovelAI prompt text requires user confirmation. Adding new NovelAI prompt text for a requested feature does not require separate confirmation.

Use chatbar-image-generation-runtime for NovelAI HTTP generation, streaming frames, retries, metadata, regeneration, concurrency, or file replacement.

## Shared NovelAI Entry

- `NOVELAI_IMAGE_PROMPT_SYSTEM` is the shared system prompt for NovelAI tag design.
- Reuse it through the feature-appropriate `PromptTemplates` helper: the composed system helper for card-backed flows and `novelAiImagePromptCoreSystem()` for the prompt tool.
- Send final user-specific image requirements through `novelAiImagePromptPreferenceUser(...)` as the last user-role message. Do not turn them into another system message: cleartext chat-template adaptation may rewrite system roles, and the final adapted request must not end on an assistant role.
- Prompt-tool reference-image reverse design appends `PromptTemplates.novelAiImagePromptReferenceImageUser()` and still uses the shared system prompt. Keep `referenceImageProvided` independent from direct image payloads so vision-model description fallback retains this instruction.
- `ImagePromptToolViewModel` sends the source image directly when the selected design model is multimodal; otherwise `ImageUnderstandingService` produces a description for the same shared NovelAI prompt flow.
- Character-card cover image user prompt lives in `PromptTemplates.novelAiImagePromptCharacterCard(...)`; `NovelAiPromptDesigner` should call it instead of embedding cover prompt text.
- Character-card built-in style presets use `presets/image_styles/default-image-styles.json` as sole catalog source. `NovelAiStyleCatalogService` validates entries and preview availability; previews belong under `presets/image_styles/previews/`. Character editor one-click fill replaces only `CharacterCard.defaultImagePrompt`, persists no style key, and leaves negative prompt plus generation flow unchanged.
- Default negative tags live in `PromptTemplates.DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT`; card-level `CharacterCard.defaultImageNegativePrompt` flows into `NovelAiPromptPlan.negativePrompt`, with the template used only when that value is blank. `NovelAiImageService` disables quality-tag injection, uses `ucPreset=3` (None), and sends that one effective negative text identically through legacy and V4 fields without combining another preset.
- Multi-character designer output contains character `caption` only; do not request per-character centers. `NovelAiImageService` keeps V4 `use_coords=false` so NovelAI chooses placement.
- Card-backed prompt design never sends `CharacterCard.defaultImagePrompt` to the AI. The card-specific system instruction forbids style output; `NovelAiPromptDesigner.convert` prepends the untouched card style to the normalized AI-designed scene in final `baseCaption`.
- Character avatar generation goes through `NovelAiPromptDesigner` shared NovelAI flow: only current person tags are passed as `Character preset prompts`; the card style is prepended after AI design. Add global image Prompt preference plus `PromptTemplates.CHARACTER_AVATAR_NAI_COMPOSITION_TAGS`, then append the fixed tags to the final plan. Freeform source is page-local manual positive Prompt. Output size is always `1024x1024`.
- Do not add feature-specific NovelAI system prompt constants.
- Do not add full feature-specific NAI templates such as `NOVELAI_IMAGE_PROMPT_MOMENT_TEMPLATE`.
- If feature needs extra visual guidance, add a small `PromptTemplates` helper that supplies only modifiers: target style, composition preference, mood, brief image intent.

## Danbooru Tag Research

- Every AI-backed `NovelAiPromptDesigner.design*` flow first runs `NovelAiTagResearchService`: same design model plans up to six short Chinese fuzzy queries in one call, then all TagSuggest lookups run concurrently and their candidates/counts become optional evidence. Do not use repeated AI calls or English-tag validation.
- Planner protocol contains only `action` and optional `queries`; do not add `purpose` or `reason`. TagSuggest normalizes unavoidable English spaces to Danbooru underscores before HTTP lookup.
- Prompt-tool reference images enter both planning and final design requests. TagSuggest receives only planner queries.
- Planning/search failure leaves final design message chain unchanged. Combined content progress records planning, each lookup, final design, and JSON repair; cancellation stops before final design.
- Manual Prompt generation, generation from an existing Prompt, and image regeneration bypass research because they do not invoke AI Prompt design.
- TagSuggest candidates are process-memory cached only. Do not add credentials, settings, or persisted fields for this flow.

## Prompt Shape

Preferred flow:

1. Feature code or feature AI produces short image intent.
2. `NovelAiPromptDesigner` obtains one batch search plan and concurrently retrieves every TagSuggest result.
3. `NovelAiPromptDesigner` sends shared NovelAI system prompt plus optional evidence.
4. Final user prompt asks for NAI tags using minimal modifiers.
5. Output stays tag-focused and avoids app implementation context.

Keep backend limits, scheduling rules, UI state, storage details, and business policy out of NovelAI prompt text unless directly needed for visual result.

## Current Project Rule

For 朋友圈 image generation, keep using shared `NOVELAI_IMAGE_PROMPT_SYSTEM`. Add only small modifiers such as target photo style or suitable composition. Do not create separate moment-specific NAI prompt template.

The 朋友圈文案 generation system prompt has an image version `MOMENT_GENERATION_SYSTEM_PROMPT` and a text-only version `MOMENT_GENERATION_TEXT_SYSTEM_PROMPT` (`momentGenerationTextSystemPrompt`). The text-only version writes a slightly longer (~40-90 char), more scenic `text` but keeps a hidden `imageBrief` field so `designForMoment` can still build the NAI prompt later on demand. Do not drop the hidden `imageBrief` from the text-only JSON schema.

## Verification

After NAI prompt-flow code changes:

- Run `.\gradlew.bat :app:compileDebugKotlin` from `app/`.
- Run `.\gradlew.bat test` when prompt helpers, parsing, or generation decisions change.
- Keep a request-shape test covering message roles before and after cleartext chat-template adaptation.
