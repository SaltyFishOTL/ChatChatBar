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

- Core and stable-context layers are separate logical system messages. Core contains the resolved character-card system prompt plus the CCB creator identity. Stable context contains reusable character, reply, supplementary, and player settings. CCB assistant/user handshake messages sit between them; CCB context approval follows stable context.
- Dynamic layer contains World Book, RAG, Archive, HEAD, and timeline material.
- Tail layer contains post-history instructions only. ChatViewModel inserts the previous-turn heading before previous-turn messages, then emits final system content in this order: post-history/JailBreak, CCB continuation, optional `END` requirements.
- Preserve dynamic order: World Book, RAG, Archive, then HEAD/timeline constraint.
- Insert cacheable earlier history after CCB context approval and optional `START` requirements/history heading.
- Move a complete adjacent USER + ASSISTANT previous turn into the tail hot zone when available. Earlier assistant history may omit status and option blocks when configured, but every assistant message in the previous turn must retain its full content. Preserve opening assistants, consecutive users, unanswered users, and other abnormal messages in original order.
- Resolve the active format card by available entities: use an available session override first, then an available global default; a stale session ID must not suppress the default card.
- Build current-turn requirements once through `PromptTemplates`, then combine them with the existing reply-tail length/speaker requirements. Place the exact combined text by `formatPromptPosition`: for `START`, use a system message after CCB context approval and before earlier history; for `END`, append it inside the final system message after post-history/JailBreak and CCB continuation, immediately before the current user; for `BOTH`, use both locations. Missing persisted values default to `BOTH`. Keep requirements out of persistence, history, and memory source text.
- Format-card random-number tools remain a request-only suffix inside the current user message. Extract every `STRONG_PROMPT_SUFFIX`, preserve configured order, and combine them into one logical trailing system message after the current user. With no strong suffix, current user is the final logical message.
- Derive the prompt cache key from the exact stable logical message prefix, including roles, CCB handshake, stable context, conditional `START` requirements, and the earlier-history heading. `END` requirements remain outside that prefix.
- Render session placeholders in separately inserted Archive and HEAD text before creating their final `ChatApiMessage`; keep persisted memory text unchanged.
- Cleartext HTTP adaptation changes non-trailing later system roles to assistant. A trailing strong-prompt system is merged into the current user transport message, including multimodal content, so the serialized request ends with user; HTTPS keeps the logical trailing system.
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
- Format prompt placement at `START`, `END`, and `BOTH`: START before earlier history, END inside the final pre-user system, and BOTH at both positions.
- Opening assistant, consecutive users, unanswered user, and regeneration.
- Empty-message continue: blank user input is replaced by `PromptTemplates.continueGenerationUserPrompt()` as the current user message and is not persisted; format-requirement placement still follows the resolved model configuration.
- Format-card user tools: direct send, multimodal send, regeneration, and empty-message continue keep random values inside user while sending configured strong prompts as one trailing system; retries reuse already assembled random values.
- Empty versus populated World Book, RAG, Archive, HEAD, and post-history sections.
- Stable outlet present versus absent.
- Document-only, memory-only, and mixed RAG cards.
- Cache path and non-cache fallback produce equivalent semantic order; cache key covers exact stable role/content sequence.
- Cleartext HTTP serialization preserves non-trailing message/content order, merges a trailing strong prompt into text and multimodal user content, and never ends with an adapted assistant prompt.
- Expected Archive and HEAD markers exist in final serialized messages.

## Stop Conditions

- Do not infer order from SECTION constants.
- Do not accept preview-only evidence for a request-delivery bug.
- Do not change source-turn grouping without checking direct context, RAG, and long-term memory together.
