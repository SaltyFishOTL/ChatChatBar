---
name: chatbar-novelai-prompt
description: Maintain ChatBar project rules for NovelAI image prompt generation. Use when changing NovelAI prompt design, NovelAI system prompt usage, NAI tag conversion, or code that builds NovelAI prompts for generated images.
---

# ChatBar NovelAI Prompt

## Scope

Use before editing NovelAI-related prompt flow:

- `app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt`
- `app/app/src/main/java/com/example/chatbar/domain/image/NovelAiPromptDesigner.kt`
- code that converts image intent into NovelAI tags
- code that adds prompt text for NovelAI image generation

Do not use this skill for non-NAI image planning, UI, storage, scheduling, memory, chat behavior, or feature policy unless those changes alter NovelAI prompt construction.

## Prompt Ownership

- Put new hardcoded prompts, prompt templates, and prompt-builder text in `PromptTemplates.kt`.
- Feature code must call `PromptTemplates`; do not hide prompt text inside services, repositories, ViewModels, or Composables.
- New natural-language prompt text must be Chinese by default.
- English allowed only for protocol tokens, JSON keys, model tag formats, external IDs, and NovelAI/booru tags when required by model behavior.
- Do not rewrite unrelated existing prompts while touching NAI flow.

## Shared NovelAI Entry

- `NOVELAI_IMAGE_PROMPT_SYSTEM` is the shared system prompt for NovelAI tag design.
- Reuse it through `PromptTemplates.novelAiImagePromptSystem(...)`.
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
