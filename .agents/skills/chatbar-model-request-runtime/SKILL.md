---
name: chatbar-model-request-runtime
description: Maintain and diagnose ChatBar model resolution, OpenAI-compatible request construction, provider-specific parameters, API authentication, cleartext local-model policy, thinking/reasoning controls, streaming transport, retry classification, output truncation, connection tests, and response parsing. Use for model fallback bugs, HTTP 400 compatibility errors, empty or truncated responses, unexpected one-shot failure, excessive retries, SSE stalls, stuck stop controls, user-cancellation persistence, timeout or stream-reset failures, auth differences, and auxiliary-model request issues.
---

# ChatBar Model Request Runtime

Separate model selection, request construction, transport, and output parsing. A successful HTTP call can still fail at stream or protocol parsing.

## First Read

- Model selection and fallback: domain/model/EffectiveModelResolver.kt
- Chat model availability and send gating: ui/chat/ChatViewModel.kt
- Shared HTTP client, proxy, cleartext policy, Authorization helper: domain/ProxyAwareClient.kt
- Chat/text request body, SSE parsing, retries, thinking fields: domain/chat/StreamingChatService.kt
- Auxiliary task identity, neutral confirmation, refusal evidence: domain/prompt/AiTaskRequests.kt; scene text/symbol index: PromptTemplates.aiTaskProfile. Global request inspection: ui/manage/AiRequestLogsContent.kt and utils/DebugLogManager.kt.
- Dynamic task thinking adaptation: domain/chat/ThinkingRequestPolicy.kt. Inspect original model before isolated parameters are stripped. Existing enableThinking or custom enable_thinking/thinking_budget/max_thinking_tokens selects legacy controls; otherwise explicit effort selects effort, then existing supportsDisableThinking selects legacy, and unknown models default to effort. No new model setting or name-based capability list.
- Cleartext HTTP strict-template role adaptation: domain/chat/CleartextHttpChatTemplatePolicy.kt
- Final serialized request diagnostics: utils/DebugLogManager.kt and ui/chat/DebugLogDialog.kt
- Connection-test caller: ui/manage/ManageViewModel.kt. Tracked application-scope job and isApiTesting drive the settings stop button; cancellation bypasses error conversion and prevents the next probe. EmbeddingService uses cancellable OkHttp callbacks, cancels the active call, and closes responses.
- Model editor discovery: ui/model/ModelEditViewModel.kt and ModelEditScreen.kt call domain/model/ModelDiscoveryService.kt for OpenAI-compatible `GET {baseUrl}/models` (`data[].id`). Reuses effective key and cleartext/proxy policy; no redirects or hidden retries. URL/key edits cancel and invalidate results. Search opens ModelPickerDialog.kt and fetches only when no successful list exists in the editor; refresh appears after success, retains the previous list with explicit stale/error status on failure, and never reopens a dismissed picker on completion. Picker has name-derived series filters (not capability metadata), multi-keyword search, name sorting, and selected-ID pinning. Selection only changes model ID and fills a blank display name. Listing does not prove chat capability; unsupported/empty/error results remain visible with manual entry available.
- Embedding-specific transport: domain/rag/EmbeddingService.kt
- Shared Android foreground/background protection: use chatbar-background-work-runtime.
- Auxiliary callers inheriting the selected model configuration: domain/card/CharacterAutoFillService.kt, CharacterRewriteService.kt, CharacterAppearanceImageService.kt, domain/memory/MemoryAiGateway.kt, domain/rag/RetrievalPlanner.kt, and domain/voice/FishAudioTagService.kt. NovelAiPromptDesigner.kt and NovelAiTagResearchService.kt inherit the selected model's thinking configuration.
- Tests: ModelConfigurationTest.kt, CleartextHttpPolicyTest.kt, StreamingChatServiceThinkingTest.kt, StreamingChatServiceTerminalTest.kt, InterruptedReplyPolicyTest.kt, and request-body tests near each caller

Use chatbar-message-format-repair for repair state behavior, chatbar-image-generation-runtime for NovelAI image HTTP generation, and chatbar-fish-audio-voice for Fish tag protocol, confirmation, TTS, and playback behavior.

## Resolution Rules

- Distinguish unset selection from explicitly stale selection.
- Apply fallback only where feature policy defines unset as follow-default.
- Keep selected custom, preset, embedding, and auxiliary model sources distinct. No dedicated retrieval model exists: ChatViewModel passes the current chat model to RetrievalPlanner; character/world-book research uses the operation's selected generation model.
- `ModelRepository.initialize` migrates the retired `retrieval_model_config/default` into ordinary model storage before publishing models. Preserve ID/credentials for explicit auxiliary bindings, expose it as a normal chat model, clear preset provenance, save before removing the old record, and serialize initialization. Colliding ordinary IDs retain priority; the legacy configuration gets a stable separate ID. Restoring bundled models only restores chat models and the embedding model, never the retired slot.
- Prefer each model's own API key. Use the global default key only when an HTTPS or otherwise authenticated model has a blank key.
- For an existing chat, compute usability with `status(session.modelId, appSettings)` and resolve the send model from the same ID. Use unscoped `status(appSettings)` only for flows that intentionally follow the app default, such as new-session gating.
- Gate every `ModelConfig` request, including image-prompt design, through `hasConfiguredAuthentication`; do not infer usability from raw `apiKey` blankness.
- Resolve effective API keys once. For allowed cleartext HTTP local models, a blank model key means no Authorization header and must not inherit the global key.
- Never emit an empty Bearer header.

## Request Rules

- Map model/provider capabilities before adding request fields.
- Do not blindly send max_tokens, max_completion_tokens, thinking_budget, reasoning_effort, and thinking controls together.
- `ModelConfig.outputTokenParameter` selects exactly one output-token key. Auxiliary isolated tasks strip sampling, stop, penalties, token overrides, and thinking parameters without adding task-owned output limits.
- Main chat and auxiliary callers impose no computed or fixed output-token limits. Non-isolated requests preserve explicit ModelConfig output settings; isolated tasks strip both custom aliases and maxOutputTokens. New model templates and bundled presets omit output limits. Reply-length prompts, context budgets, and thinking controls remain independent.
- Main chat uses resolved `ModelConfig.formatPromptPosition` to place combined current-turn format/length/speaker requirements after CCB contract confirmation and before character for `START`, after the current user and character post-history for `END`, or both. Old model data defaults to both positions.
- Long-term memory, retrieval, voice tags, format repair, character AI and world-book research preserve selected ModelConfig thinking, effort, sampling and explicit output settings. Task-specific JSON Mode remains capability-gated. Retrieval allows 120 seconds rather than the former 15-second thinking-off deadline.
- Task budget/enable overrides become reasoning_effort=low for effort models; disableThinking or enableThinking=false wins over other overrides and becomes none. Legacy models retain task budgets and switches, suppressing effort during task overrides. Ordinary chat without task overrides preserves configured fields. This uses configuration evidence, not API probing; unsupported effort values remain visible request errors. Image description preserves the resolved vision ModelConfig unchanged, including effort and custom thinking parameters.
- `streamText`, `completeText`, and `completeTextStreaming` accept an optional per-request `readTimeoutSeconds`; callers with legitimately long silent reasoning can extend inactivity timeout without changing the shared 120-second default. Character-card AI owns a 600-second override across generation, repair, planning, and research cleaning.
- Connection probes and Fish translation/tag requests use withoutOutputTokenLimit (domain/chat/OutputTokenRequestPolicy.kt), omitting max_tokens/max_completion_tokens and configured maxOutputTokens without mutating saved settings. They retain thinking-off adaptation and manual cancellation. Provider finish_reason=length remains an explicit incomplete-output error; do not tell users to raise a setting these tasks ignore.
- Main-chat logical tail is real current user, optional strong-prompt system, CCB acknowledgement assistant, then CCB identity-reminder user. For opted-in `http://` model requests, preserve the first system role and serialize non-trailing later system roles, including the strong prompt, as assistant. The final CCB reminder remains user. Never end a cleartext request with an adapted assistant prompt. Do not apply this adaptation to HTTPS or when cleartext access is disabled.
- Treat Debug Request JSON as the final `buildRequestBody` output after cleartext role adaptation, not the logical `ChatApiMessage` list assembled by the caller.
- Treat strict JSON or protocol parsing as a separate failure layer from HTTP transport. Preserve raw diagnostic evidence within privacy limits.
- Use `ModelRequestException` as shared request-failure evidence: missing HTTP status, 408/425/429, and 5xx are retryable; 401/403 identify authentication failure; other HTTP statuses are terminal. Preserve status, trace ID, `Retry-After`, and root cause when wrapping it.
- Assign retry ownership before adding loops. Shared request code performs one request and reports a typed result; a feature task may own bounded transport retries and separate output/parse/validation attempts. Never count a retryable request failure against an output-quality budget, and never stack caller retries over hidden shared retries.
- Auxiliary callers explicitly supply AiTaskContext(kind, stage). StreamingChatService assembles the confirmation once; completeTextStreaming delegates to streamText. AiTaskRun groups nested stages and retries; each actual request gets a fresh request ID. Main chat and connection probes bypass the auxiliary envelope. BuildConfig.AI_PROMPT_SOURCE_SHA256 fingerprints normalized PromptTemplates source; per-scene fingerprints include kind and stage, never user input.
- Auxiliary transport uses the eight-message GENERAL_* structure (system/assistant/user/assistant/user/assistant/assistant/user). All feature systems are merged into its first system; actual inputs/history occupy the fifth message. HTTP adaptation leaves this single-system structure unchanged. Logs include GENERAL_* template symbols and estimate confirmation plus multi-input heading overhead; no extra model call is made.
- IMAGE_DESIGN/GENERATE preserves the actual non-system input roles inside the GENERAL_* envelope; this variable-length block retains planner/revision assistant history. Input-heading estimates are omitted for that path. JSON repair and other scenes still merge inputs. The sole system and final user keep HTTP adaptation stable.
- AiTaskRefusalException is terminal through repair, retry and research fallback boundaries. StreamEvent.Error retains failureKind/cause; collectors must preserve asException() when forwarding failure. Do not turn a refusal or cancelled task into a fallback brief or reusable final-output checkpoint.
- withAiTaskRun records final processing/validation exceptions against the task's last request. Starting a REPAIR stage marks the immediately preceding successful response of the same task/kind as a format failure; existing transport/refusal errors retain their classification.
- Truncation remains an output failure; memory retries preserve stage/count diagnostics without adding or growing output-token limits. Cancellation bypasses retry and wrapping.
- Keep Fish voice-tag calls on the shared streaming text service with the selected model parameters intact. Keep strict ID/tag/text validation and confirmation policy in chatbar-fish-audio-voice rather than weakening shared stream parsing.
- Verify current provider behavior against official provider documentation when compatibility may have changed.

## Streaming Diagnosis

- Auxiliary `streamText` observes coroutine-scoped `domain/chat/AiStreamProgress.kt`; nested research/repair inherit the operation observer. UI uses `ui/components/AiStreamProgressPanel.kt` for bounded, separately rendered reasoning/content, model identity, inactivity age and per-request history. Observers never enter model messages or candidate/checkpoint data. Final/error/cancel flush pending previews.
- `streamText` has a meaningful-output inactivity watchdog in addition to socket read timeout, using the same per-request duration. Only nonblank reasoning/content or finish reason refreshes it; SSE heartbeats and usage-only events cannot keep a stalled request alive forever. Preserve whitespace-only content deltas in the actual output.

- Main `streamChat` exposes optional `onReplyCompletion` evidence before its terminal event, preserving finish reason, refusal/content-filter markers, and transport failure after a finish reason. Legacy `Done` semantics remain unchanged; automatic images require explicit `stop` without refusal or transport failure plus local content checks, with no separate AI judgment. `[DONE]` alone cannot authorize automatic images.

- Auxiliary `streamText` reports `finish_reason=length` as `StreamEvent.Error`, preserving preceding deltas and emitting no `Done`; callers must reject that error even when partial content exists.
- HTTP 200 proves stream establishment only.
- stream was reset: CANCEL after 200 is an HTTP/2 transport failure, not a 200 business error.
- A fixed read timeout measures silence between bytes/events; reasoning models can hit it after emitting a short reasoning prefix.
- Treat either `[DONE]` or a non-null `finish_reason` as terminal success evidence. Because `finish_reason` can precede a usage-only chunk and `[DONE]`, keep a short bounded grace before cancelling transport; still deliver one terminal event only. A peer close without either signal is an explicit protocol error.
- SSE callback flows use an unbounded handoff buffer because provider callbacks cannot suspend. Never ignore terminal delivery behind the default 64-slot callbackFlow capacity.
- Keep main-chat user stop distinct from transport failure. Persist raw assistant drafts with nonblank body or reasoning through the normal repository path; reasoning-only regeneration selects an empty-body version. Make the save non-cancellable and idempotent across completion races, refresh the durable timeline while the same-ID streaming item remains, then clear streaming UI state and release the responding gate. Never filter a normal persisted draft by the active streaming ID. Interrupted drafts skip full-reply-only post-processing.
- `streamText` has no retry; it logs the final serialized request and uses a 250 ms terminal grace to retain usage after finish_reason. Refusal/filter, truncation, unparseable chunks, server errors, empty/reasoning-only output, early EOF and cancellation remain distinct failures. Array-form content and nullable choice/delta/usage objects remain supported. completeText uses the same refusal policy and records non-stream usage.
- DebugLogManager mutations use request IDs (legacy session lookup remains compatible), immutable snapshots, nullable provider input/output/cache usage, and separately labeled estimates. Logs are process-local, limited to 100 entries and 4 Mi characters overall, with 65,536-character text previews and visible truncation. Known request keys and image bytes are redacted; logging never mutates the outbound body.
- Distinguish user cancellation, background-protection cancellation, client timeout, peer reset, proxy/VPN reset, and parser failure.
- Record timestamps for stream open, reasoning delta, content delta, terminal event, request ID, protocol, and exception class when improving diagnostics.
- Do not retry after partial visible output without a duplication and billing policy.

## Workflow

1. Identify actual selected model for the failing feature.
2. Capture final endpoint, headers presence, request keys, stream/non-stream mode, and parser contract.
3. Compare working chat and failing auxiliary request bodies field by field.
4. Reproduce with request-builder tests before changing transport.
5. Trace retry counters and exception wrapping across every layer before deciding retry owner.
6. Fix the shared lowest owner when all callers should inherit behavior.
7. Keep feature-specific parsing, retry budgets, and fallback decisions in feature owners.

## Regression Matrix

- Unset, valid explicit, and stale explicit model IDs.
- Preset and custom models with local/global API keys.
- Valid session model with its own key while the app default model or global key is unavailable.
- Allowed HTTP local model with and without its own key; HTTPS inheritance.
- Thinking enabled, disabled, reasoning effort, and custom conflicting fields.
- Chat streaming, auxiliary text streaming, and connection test.
- Cleartext HTTP enabled, HTTPS with cleartext enabled, and HTTP with cleartext disabled role serialization.
- HTTP error, empty content, reasoning-only content, malformed JSON, timeout, peer reset, user cancellation before content, cancellation after partial content, and cancellation during final persistence.
- Exact request counts for retryable and non-retryable failures; output failures must not consume transport budget. Verify `Retry-After`, cancellation passthrough, output-limit omission and final stage/attempt diagnostics.

## Stop Conditions

- Do not assume two UI features use the same model.
- Do not diagnose a parsing failure as an API failure without response evidence.
- Do not duplicate provider retries or fallback logic in multiple callers.
