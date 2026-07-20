---
name: chatbar-prompt-pipeline
description: Maintain and diagnose ChatBar prompt assembly and request ordering across PromptTemplates, PromptAssembler, cache layers, history grouping, RAG cards, World Book outlets, Archive/HEAD injection, and final ChatApiMessage serialization. Use when changing prompt section order, roles, headings, stable/dynamic/tail caching, previous-turn placement, RAG usage notes, or when actual model input differs from a preview.
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
- Core tests: PromptAssemblerCharacterModeTest.kt, ContextWindowManagerTest.kt, RoleplaySpeakerPromptTest.kt, and PromptTemplatesTest.kt

Use chatbar-long-term-memory when Archive, HEAD, timeline constraints, source-turn boundaries, or RAG grouping are involved. Use chatbar-novelai-prompt for NovelAI tag-design prompts and chatbar-character-card-ai for card-generation prompts.

## Ownership Model

- Keep model-facing task text in PromptTemplates.
- Keep section selection, titles, and layer assignment in PromptAssembler.
- Keep logical ChatApiMessage roles and interleaving with raw history in ChatViewModel. StreamingChatService adapts later system roles only for opted-in `http://` requests.
- Keep conversation grouping in ContextWindowManager and shared turn policies.
- Verify transport request fields with chatbar-model-request-runtime.

Do not move behavior between these owners without tracing every caller and test.

## Layer Invariants

- Stable layer contains reusable role, format, reply, supplementary, player, and core settings.
- Dynamic layer contains World Book, RAG, Archive, HEAD, and timeline material.
- Tail layer contains post-history instructions and the previous-turn heading.
- Preserve dynamic order: World Book, RAG, Archive, then HEAD/timeline constraint.
- Insert cacheable earlier history after the stable layer.
- Move a complete adjacent USER + ASSISTANT previous turn into the tail hot zone when available. Preserve opening assistants, consecutive users, unanswered users, and other abnormal messages in original order.
- Append current user input last. Per-turn speaker/length constraints remain system messages near the tail, not merged into user text.
- Render session placeholders in separately inserted Archive and HEAD text before creating their final `ChatApiMessage`; keep persisted memory text unchanged.
- Cleartext HTTP adaptation changes later system roles to assistant in serialized JSON but never moves or merges their content; stable prefix and tail positions remain unchanged.
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

1. Classify change as prompt text, section assembly, turn grouping, cache behavior, or transport.
2. Write expected final message roles and order before editing.
3. Trace both assembleSystemPrompt and assembleCachePromptLayers callers.
4. Check direct chat, regeneration, cache fallback, and empty-section paths.
5. Add behavior tests for inclusion, omission, relative order, and role.
6. Inspect serialized Request JSON when delivery or ordering is disputed.

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
