---
name: chatbar-prompt-pipeline
description: Maintain and diagnose ChatBar prompt text, templates, builders, assembly, and request ordering across PromptTemplates, PromptAssembler, cache layers, history grouping, RAG cards, World Book outlets, Archive/HEAD injection, and final ChatApiMessage serialization. Use whenever adding, editing, deleting, renaming, or moving any model-facing prompt in PromptTemplates.kt; also use for prompt section order, roles, headings, caching, previous-turn placement, RAG usage notes, or when actual model input differs from a preview.
---

# ChatBar Prompt Pipeline

Treat the serialized API message list as source of truth. Constant declaration order and assembled preview text do not prove what the model receives.

## First Read

- Prompt text and section labels: app/app/src/main/java/com/example/chatbar/domain/prompt/PromptTemplates.kt
- Section collection, layer rendering, RAG cards, outlets: app/app/src/main/java/com/example/chatbar/domain/chat/PromptAssembler.kt
- History and previous-turn grouping: app/app/src/main/java/com/example/chatbar/domain/chat/ContextWindowManager.kt
- Final role/message insertion and request launch: app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt
- Cleartext HTTP final role adaptation: app/app/src/main/java/com/example/chatbar/domain/chat/CleartextHttpChatTemplatePolicy.kt
- Request diagnostics: app/app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt and ui/chat/DebugLogDialog.kt
- Core tests: PromptAssemblerCharacterModeTest.kt, ContextWindowManagerTest.kt, CurrentTurnMessageOrderTest.kt, RoleplaySpeakerPromptTest.kt, PromptTemplatesTest.kt, and CleartextHttpPolicyTest.kt

Use chatbar-long-term-memory when Archive, HEAD, timeline constraints, source-turn boundaries, or RAG grouping are involved. Use chatbar-novelai-prompt for NovelAI tag-design prompts and chatbar-character-card-ai for card-generation prompts.

## Ownership Model

- Keep model-facing task text in PromptTemplates.
- Treat the `AI 提示词目录` KDoc at the start of PromptTemplates as mandatory navigation metadata. Every PromptTemplates prompt change must review it; add, remove, rename, recategorize, or revise entries in the same change whenever symbols or purposes change. Use exact searchable symbol names and never line numbers.
- A PromptTemplates prompt change is incomplete until the header directory remains accurate. Keep template constants beside their builders so directory search lands in one local area.
- Keep section selection, titles, and layer assignment in PromptAssembler.
- Keep logical ChatApiMessage roles and interleaving with raw history in ChatViewModel. StreamingChatService adapts later system roles only for opted-in `http://` requests; Debug Request JSON records this adapted transport body.
- Keep conversation grouping in ContextWindowManager and shared turn policies.
- Verify transport request fields with chatbar-model-request-runtime.

Do not move behavior between these owners without tracing every caller and test.

## Layer Invariants

- Stable layer contains reusable role, reply, supplementary, player, and core settings.
- Dynamic layer contains World Book, RAG, Archive, HEAD, and timeline material.
- Tail layer contains post-history instructions and the previous-turn heading.
- Preserve dynamic order: World Book, RAG, Archive, then HEAD/timeline constraint.
- Insert cacheable earlier history after the stable layer.
- Move a complete adjacent USER + ASSISTANT previous turn into the tail hot zone when available. Preserve opening assistants, consecutive users, unanswered users, and other abnormal messages in original order.
- Resolve the active format card by available entities: use an available session override first, then an available global default; a stale session ID must not suppress the default card.
- Build the complete current-turn requirements text once through `PromptTemplates`, using the rendered active format card and effective session reply length. Prepend that exact text to the first logical `system` message before the stable prompt, then append the current user input unchanged and repeat the same text in a logical `system` message immediately after it. Keep the requirements out of PromptAssembler stable/dynamic/tail layers, persistence, history, and memory source text.
- Derive the prompt cache key from the exact first-system content after the opening requirements are prepended; format-card or reply-length changes must produce a different key.
- Render session placeholders in separately inserted Archive and HEAD text before creating their final `ChatApiMessage`; keep persisted memory text unchanged.
- Cleartext HTTP adaptation changes later system roles to assistant in serialized and debug JSON but never moves or merges their content; HTTPS keeps the post-user requirements role as system.
- Omit empty sections and their headings.
- Base cacheability on rendered stable content. An unresolved World Book outlet in stable content disables stable-prefix caching.
- Keep cache keys aligned with exact sent stable content, including conditional history headings.

## RAG Rendering

- Partition cards by ChunkSourceType, not display labels.
- Render non-CHAT_MEMORY cards before CHAT_MEMORY cards.
- Inject RAG_CHAT_MEMORY_USAGE_NOTE once, immediately before the first memory card.
- Do not add the memory-card note to document cards.
- Keep card numbering continuous after partitioning.

## Workflow

1. Read the PromptTemplates header directory and classify the change as prompt text, section assembly, turn grouping, cache behavior, or transport.
2. Write expected final message roles and order before editing.
3. Trace both assembleSystemPrompt and assembleCachePromptLayers callers.
4. Update or verify the header directory in the same change for every PromptTemplates prompt edit.
5. Check direct chat, regeneration, cache fallback, and empty-section paths.
6. Add behavior tests for inclusion, omission, relative order, and role.
7. Inspect serialized Request JSON when delivery or ordering is disputed.

## Regression Matrix

- No history, one incomplete turn, and multiple complete turns.
- Opening assistant, consecutive users, unanswered user, and regeneration.
- Empty versus populated World Book, RAG, Archive, HEAD, and post-history sections.
- Stable outlet present versus absent.
- Document-only, memory-only, and mixed RAG cards.
- Cache path and non-cache fallback produce equivalent semantic order.
- Cleartext HTTP serialization preserves message/content order while rewriting only system roles after the first.
- Expected Archive and HEAD markers exist in final serialized messages.

## Stop Conditions

- Do not infer order from SECTION constants.
- Do not accept preview-only evidence for a request-delivery bug.
- Do not change source-turn grouping without checking direct context, RAG, and long-term memory together.
