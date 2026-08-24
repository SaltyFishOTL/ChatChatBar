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
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiCodexCatalog.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiStyleCatalog.kt`
- `app/app/src/main/assets/presets/novelai/nai-codex-v1.json`
- `tools/import_nai_codex.py`
- `app/app/src/main/assets/presets/image_styles/default-image-styles.json`
- code that converts image intent into NovelAI tags
- code that adds prompt text for NovelAI image generation

Do not use this skill for non-NAI image planning, UI, storage, scheduling, memory, chat behavior, or feature policy unless those changes alter NovelAI prompt construction.

Modifying existing NovelAI prompt text requires user confirmation. Adding new NovelAI prompt text for a requested feature does not require separate confirmation.

Use chatbar-image-generation-runtime for NovelAI HTTP generation, streaming frames, retries, metadata, regeneration, concurrency, or file replacement.

## Shared NovelAI Entry

- `NOVELAI_IMAGE_PROMPT_SYSTEM` is the shared system prompt for NovelAI tag design.
- Render `${'$'}username` and `${'$'}botname` before scene-planning and final-design requests, except conversation image generation must preserve `${'$'}username` as the player-role marker while still rendering `${'$'}botname`. Normalize the effective player name in chat-source text and manual chat-image requirements back to `${'$'}username`. Use session player-name override before global player name, use `CharacterCard.effectiveBotName` for the card role, and make Debug exchanges show the exact planner system prompt sent.
- Reuse it through the feature-appropriate `PromptTemplates` helper: the composed system helper for card-backed flows and `novelAiImagePromptCoreSystem()` for the prompt tool.
- Send final user-specific image requirements through `novelAiImagePromptPreferenceUser(...)` as the last user-role message. Do not turn them into another system message: cleartext chat-template adaptation may rewrite system roles, and the final adapted request must not end on an assistant role.
- Prompt-tool reference-image reverse design appends `PromptTemplates.novelAiImagePromptReferenceImageUser()` and still uses the shared system prompt. Keep `referenceImageProvided` independent from direct image payloads so vision-model description fallback retains this instruction.
- Studio first release is text-to-image only. `NovelAiPromptDesigner` retains reference-image request support for existing compatible callers, but studio UI does not expose Img2Img/reference tools.
- Character-card cover image user prompt lives in `PromptTemplates.novelAiImagePromptCharacterCard(...)`; `NovelAiPromptDesigner` should call it instead of embedding cover prompt text.
- Character-card built-in style presets use `presets/image_styles/default-image-styles.json` as sole catalog source. `NovelAiStyleCatalogService` validates entries and preview availability; previews belong under `presets/image_styles/previews/`. Character editor one-click fill replaces only `CharacterCard.defaultImagePrompt`, persists no style key, and leaves negative prompt plus generation flow unchanged.
- Default negative tags live in `PromptTemplates.DEFAULT_CHARACTER_NAI_NEGATIVE_PROMPT`; card-level `CharacterCard.defaultImageNegativePrompt` flows into `NovelAiPromptPlan.negativePrompt`, with the template used only when that value is blank. `NovelAiImageService` disables quality-tag injection, uses `ucPreset=3` (None), and sends that one effective negative text identically through legacy and V4 fields without combining another preset.
- Multi-character designer output contains character `caption` only; do not request per-character centers. `NovelAiImageService` keeps V4 `use_coords=false` so NovelAI chooses placement.
- Card-backed prompt design never sends `CharacterCard.defaultImagePrompt` to the AI. The card-specific system instruction forbids style output; `NovelAiPromptDesigner.convert` prepends the untouched card style to the normalized AI-designed scene in final `baseCaption`.
- Character avatar generation does not use `NovelAiPromptDesigner`, tag research, or global image Prompt preference. `CharacterAvatarImagePolicy` deterministically joins card style, current person Prompt, and `portrait, upper body`; freeform source is page-local manual positive Prompt. Output size is fixed Small Square `512x512`.
- Do not add feature-specific NovelAI system prompt constants.
- Do not add full feature-specific NAI templates such as `NOVELAI_IMAGE_PROMPT_MOMENT_TEMPLATE`.
- If feature needs extra visual guidance, add a small `PromptTemplates` helper that supplies only modifiers: target style, composition preference, mood, brief image intent.
- Studio adds only `novelAiImageTargetModelUser(...)` as a small target-model modifier; do not fork the core prompt by V4.5/V5.

## Danbooru Tag Research

- Every AI-backed `NovelAiPromptDesigner.design*` flow first runs `NovelAiTagResearchService`: the same design model produces one detailed natural-language `sceneDescription` plus up to six Chinese `queries`, each no longer than three characters, in one call. The scene must identify each visible person, individual action and clothing state, spatial relationship, environment, composition, camera, and visible atmosphere before any tag design.
- Exception: studio natural-language mode calls `planSceneOnly`, consumes `sceneDescription`, and stops. It must not call TagSuggest, local codex retrieval, final Tag design, or repair. “AI 转化” still runs the complete research/design flow and changes positives only after success.
- NovelAI task calls override only the selected model's `thinking_budget`: scene planning uses 256, while final Prompt design and JSON repair use 512. Keep the stored model configuration unchanged.
- Planner output contains only `sceneDescription` and `queries`; do not add `action`, `purpose`, or `reason`. TagSuggest receives only the planned queries and normalizes unavoidable English spaces to Danbooru underscores before HTTP lookup.
- Local codex compiler preserves each selected source file's complete `###` section as one reference block. Retrieval uses only Chinese text extracted from the whole section, scored by corpus-weighted Chinese two/three-character overlap against both detailed scene and queries; English prompt tags and rewrite aliases never affect local recall. Select the five most relevant blocks, randomize only their delivery order, and never penalize recently used blocks. Send each selected original section intact as optional final-design evidence. All TagSuggest lookups run concurrently.
- Prompt-tool reference images enter both planning and final design requests. Planning failure uses the original task text for local codex recall, exposes the failure in progress, skips TagSuggest, and continues final design; cancellation still stops before final design.
- Prompt-tool style text is a UI-owned generation modifier: keep it out of AI design input, include the shared style-exclusion system message, and prepend the editable style to the designed/manual base caption only when the user starts NovelAI generation.
- Manual Prompt generation, generation from an existing Prompt, and image regeneration bypass research because they do not invoke AI Prompt design.
- TagSuggest candidates are process-memory cached only. Do not add credentials, settings, or persisted fields for this flow.
- Studio live completion reuses the same application-scoped `TagSuggestClient` and cache as research. Keep its 250ms UI debounce, cancellation, max-eight candidates, and nonblocking failure state outside prompt text.
- V5 text-rendering convenience belongs to deterministic request construction, not AI prompt design: quoted positive text is mirrored into a trailing base `Text:` block unless any positive prompt already contains an explicit `Text:` block. Keep stored/designed text unchanged and never apply this to V4.5.

## Studio Character Capacity

- V4.5 Full supports at most six ordered character captions; V5 Full supports at most 22 in studio.
- Imported character-card image prompts are persisted, unlimited AI design references. Send all of them to scene planning and final design as a candidate reference catalog; only task-requested visible characters may match and consume entries, and catalog membership must never create a character. Never count references against the target model's character limit, copy them over handwritten studio character prompts, or place them directly in the NovelAI generation plan.
- `designForPromptTool` materializes up to the selected target model limit. Card/chat/Moments conversion keeps the legacy six-character cap.
- AI result merge preserves existing per-index negatives, gives new items empty negatives, and retains unmatched manual tail items. Model switches and imports never silently truncate.

## Prompt Shape

Preferred flow:

1. Feature code or feature AI produces short image intent.
2. `NovelAiPromptDesigner` obtains one detailed natural-language scene plus a batch query plan.
3. The scene plus queries recall local codex templates while queries concurrently retrieve TagSuggest results.
4. `NovelAiPromptDesigner` sends the scene, optional codex/tag evidence, shared NovelAI system prompt, character presets, and the original final user request in preserved role order.
5. Deterministic processing runs `TagCanonicalizer`, `SyntaxNormalizer`, and `PromptLinter`; card-backed flows then prepend the locked card style and produce `NovelAiPromptPlan`.

Keep backend limits, scheduling rules, UI state, storage details, and business policy out of NovelAI prompt text unless directly needed for visual result.

## Current Project Rule

For 朋友圈 image generation, keep using shared `NOVELAI_IMAGE_PROMPT_SYSTEM`. Add only small modifiers such as target photo style or suitable composition. Do not create separate moment-specific NAI prompt template.

The 朋友圈文案 generation system prompt has an image version `MOMENT_GENERATION_SYSTEM_PROMPT` and a text-only version `MOMENT_GENERATION_TEXT_SYSTEM_PROMPT` (`momentGenerationTextSystemPrompt`). The text-only version writes a slightly longer (~40-90 char), more scenic `text` but keeps a hidden `imageBrief` field so `designForMoment` can still build the NAI prompt later on demand. Do not drop the hidden `imageBrief` from the text-only JSON schema.

## Verification

After NAI prompt-flow code changes:

- Run `.\gradlew.bat :app:compileDebugKotlin` from `app/`.
- Run `.\gradlew.bat test` when prompt helpers, parsing, or generation decisions change.
- Keep a request-shape test covering message roles before and after cleartext chat-template adaptation.
