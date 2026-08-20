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
- Per-model format prompt placement: app/app/src/main/java/com/example/chatbar/data/local/entity/ModelConfig.kt
- Cleartext HTTP final role adaptation: app/app/src/main/java/com/example/chatbar/domain/chat/CleartextHttpChatTemplatePolicy.kt
- Request diagnostics: app/app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt and ui/chat/DebugLogDialog.kt
- Core tests: PromptAssemblerCharacterModeTest.kt, ContextWindowManagerTest.kt, CurrentTurnMessageOrderTest.kt, RoleplaySpeakerPromptTest.kt, PromptTemplatesTest.kt, and CleartextHttpPolicyTest.kt

Use chatbar-long-term-memory when Archive, HEAD, timeline constraints, source-turn boundaries, or RAG grouping are involved. Use chatbar-novelai-prompt for NovelAI tag-design prompts and chatbar-character-card-ai for card-generation prompts.

## Ownership Model

- Keep model-facing task text in PromptTemplates.
- Treat the `AI 提示词目录` KDoc at the start of PromptTemplates as mandatory navigation metadata. Every PromptTemplates prompt change must review it; add, remove, rename, recategorize, or revise entries in the same change whenever symbols or purposes change. Use exact searchable symbol names and never line numbers.
- A PromptTemplates prompt change is incomplete until the header directory remains accurate. Keep template constants beside their builders so directory search lands in one local area.
- Keep section selection, titles, and layer assignment in PromptAssembler.
- Keep logical ChatApiMessage roles and interleaving with raw history in ChatViewModel. StreamingChatService adapts later system roles and merges a trailing requirement into the current user only for opted-in `http://` requests; Debug Request JSON records this adapted transport body.
- Keep conversation grouping in ContextWindowManager and shared turn policies.
- Verify transport request fields with chatbar-model-request-runtime.

Do not move behavior between these owners without tracing every caller and test.

## Layer Invariants

- Stable layer contains reusable role, reply, supplementary, player, and core settings.
- Dynamic layer contains World Book, RAG, Archive, HEAD, and timeline material.
- Tail layer contains post-history instructions and the previous-turn heading.
- Preserve dynamic order: World Book, RAG, Archive, then HEAD/timeline constraint.
- Insert cacheable earlier history after the stable layer.
- Move a complete adjacent USER + ASSISTANT previous turn into the tail hot zone when available. Earlier assistant history may omit status and option blocks when configured, but every assistant message in the previous turn must retain its full content. Preserve opening assistants, consecutive users, unanswered users, and other abnormal messages in original order.
- Resolve the active format card by available entities: use an available session override first, then an available global default; a stale session ID must not suppress the default card.
- Build the complete current-turn requirements text once through `PromptTemplates`, using the rendered active format card and required integer session reply length (default 300), rendered as `N字`. When configured status exclusion affects earlier assistant history and an active format card exists, include the format-continuity notice in that same text. Place that exact text according to the resolved chat model's `formatPromptPosition`: prepend it to the first logical `system` message for `START`, add it as a logical `system` message immediately after the final current user API message for `END`, or do both for `BOTH`. Format-card user tools may append a request-only ordered suffix to that final user API message; keep persisted/displayed user text, retrieval input, history, and memory source text unchanged. Missing persisted values default to `BOTH`. Keep the requirements out of PromptAssembler stable/dynamic/tail layers, persistence, history, and memory source text.
- Derive the prompt cache key from exact first-system content. Format-card or reply-length changes affect the key when placement includes `START`; `END` keeps those dynamic requirements outside the cached opening prefix.
- Render session placeholders in separately inserted Archive and HEAD text before creating their final `ChatApiMessage`; keep persisted memory text unchanged.
- Cleartext HTTP adaptation changes non-trailing later system roles to assistant. A configured post-user requirement at the request tail is merged into the current user transport message, including multimodal content, so the serialized request ends with user; logical messages and persisted user text remain unchanged. HTTPS keeps the requirement as system.
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
- Format prompt placement at `START`, `END`, and `BOTH`, including old model data defaulting to `BOTH`.
- Opening assistant, consecutive users, unanswered user, and regeneration.
- Empty-message continue: blank user input is replaced by `PromptTemplates.continueGenerationUserPrompt()` as the current user message and is not persisted; format-requirement placement still follows the resolved model configuration.
- Format-card user tools: direct send, multimodal send, regeneration, and empty-message continue each render one request-only suffix; one request's transport retries reuse the already assembled random values.
- Empty versus populated World Book, RAG, Archive, HEAD, and post-history sections.
- Stable outlet present versus absent.
- Document-only, memory-only, and mixed RAG cards.
- Cache path and non-cache fallback produce equivalent semantic order.
- Cleartext HTTP serialization preserves non-trailing message/content order, merges a trailing requirement into text and multimodal user content, and never ends with an adapted assistant prompt.
- Expected Archive and HEAD markers exist in final serialized messages.

## Stop Conditions

- Do not infer order from SECTION constants.
- Do not accept preview-only evidence for a request-delivery bug.
- Do not change source-turn grouping without checking direct context, RAG, and long-term memory together.
