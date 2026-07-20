---
name: chatbar-model-request-runtime
description: Maintain and diagnose ChatBar model resolution, OpenAI-compatible request construction, provider-specific parameters, API authentication, cleartext local-model policy, thinking/reasoning controls, streaming transport, connection tests, and response parsing. Use for model fallback bugs, HTTP 400 compatibility errors, empty responses, SSE stalls, stuck stop controls, user-cancellation persistence, timeout or stream-reset failures, auth differences, and auxiliary-model request issues.
---

# ChatBar Model Request Runtime

Separate model selection, request construction, transport, and output parsing. A successful HTTP call can still fail at stream or protocol parsing.

## First Read

- Model selection and fallback: domain/model/EffectiveModelResolver.kt
- Chat model availability and send gating: ui/chat/ChatViewModel.kt
- Shared HTTP client, proxy, cleartext policy, Authorization helper: domain/ProxyAwareClient.kt
- Chat/text request body, SSE parsing, retries, thinking fields: domain/chat/StreamingChatService.kt
- Cleartext HTTP strict-template role adaptation: domain/chat/CleartextHttpChatTemplatePolicy.kt
- Connection-test caller: ui/manage/ManageViewModel.kt
- Embedding-specific transport: domain/rag/EmbeddingService.kt
- Callers with fixed auxiliary parameters: domain/card/CharacterAutoFillService.kt, CharacterRewriteService.kt, domain/image/NovelAiPromptDesigner.kt, and domain/memory/MemoryAiGateway.kt
- Tests: ModelConfigurationTest.kt, CleartextHttpPolicyTest.kt, StreamingChatServiceThinkingTest.kt, StreamingChatServiceTerminalTest.kt, InterruptedReplyPolicyTest.kt, and request-body tests near each caller

Use chatbar-message-format-repair for repair state behavior and chatbar-image-generation-runtime for NovelAI image HTTP generation.

## Resolution Rules

- Distinguish unset selection from explicitly stale selection.
- Apply fallback only where feature policy defines unset as follow-default.
- Keep selected custom, preset, retrieval, embedding, and auxiliary model sources distinct.
- Prefer each model's own API key. Use the global default key only when an HTTPS or otherwise authenticated model has a blank key.
- For an existing chat, compute usability with `status(session.modelId, appSettings)` and resolve the send model from the same ID. Use unscoped `status(appSettings)` only for flows that intentionally follow the app default, such as new-session gating.
- Gate every `ModelConfig` request, including image-prompt design, through `hasConfiguredAuthentication`; do not infer usability from raw `apiKey` blankness.
- Resolve effective API keys once. For allowed cleartext HTTP local models, a blank model key means no Authorization header and must not inherit the global key.
- Never emit an empty Bearer header.

## Request Rules

- Map model/provider capabilities before adding request fields.
- Do not blindly send max_tokens, max_completion_tokens, thinking_budget, reasoning_effort, and thinking controls together.
- `ModelConfig.outputTokenParameter` selects exactly one output-token key. Auxiliary isolated tasks strip sampling, stop, penalties, token overrides, and thinking parameters before adding task-owned limits.
- Long-term memory sends no thinking budget. Explicit thinking-off and JSON Mode are capability-gated; unknown custom providers default to `max_tokens` without JSON Mode or provider-specific off controls.
- When disableThinking is true, remove configured thinking/reasoning parameters and send the supported explicit off control.
- Keep connection-test requests small and deterministic, but ensure reasoning-only models can still produce a usable probe result. Current test flow disables thinking.
- For opted-in `http://` model requests, preserve the first system role and serialize later system roles as assistant without moving or merging messages. Do not apply this adaptation to HTTPS or when cleartext access is disabled.
- Treat strict JSON or protocol parsing as a separate failure layer from HTTP transport. Preserve raw diagnostic evidence within privacy limits.
- Verify current provider behavior against official provider documentation when compatibility may have changed.

## Streaming Diagnosis

- HTTP 200 proves stream establishment only.
- stream was reset: CANCEL after 200 is an HTTP/2 transport failure, not a 200 business error.
- A fixed read timeout measures silence between bytes/events; reasoning models can hit it after emitting a short reasoning prefix.
- Treat either `[DONE]` or a non-null `finish_reason` as terminal success evidence. Because `finish_reason` can precede a usage-only chunk and `[DONE]`, keep a short bounded grace before cancelling transport; still deliver one terminal event only. A peer close without either signal is an explicit protocol error.
- SSE callback flows use an unbounded handoff buffer because provider callbacks cannot suspend. Never ignore terminal delivery behind the default 64-slot callbackFlow capacity.
- Keep main-chat user stop distinct from transport failure. Persist only a nonblank raw assistant draft through the normal repository path, make the save non-cancellable and idempotent across completion races, then clear streaming UI state. Do not run full-reply-only post-processing for a genuinely interrupted draft.
- Distinguish user cancellation, background-protection cancellation, client timeout, peer reset, proxy/VPN reset, and parser failure.
- Record timestamps for stream open, reasoning delta, content delta, terminal event, request ID, protocol, and exception class when improving diagnostics.
- Do not retry after partial visible output without a duplication and billing policy.

## Workflow

1. Identify actual selected model for the failing feature.
2. Capture final endpoint, headers presence, request keys, stream/non-stream mode, and parser contract.
3. Compare working chat and failing auxiliary request bodies field by field.
4. Reproduce with request-builder tests before changing transport.
5. Fix the shared lowest owner when all callers should inherit behavior.
6. Keep feature-specific parsing and fallback decisions in feature owners.

## Regression Matrix

- Unset, valid explicit, and stale explicit model IDs.
- Preset and custom models with local/global API keys.
- Valid session model with its own key while the app default model or global key is unavailable.
- Allowed HTTP local model with and without its own key; HTTPS inheritance.
- Thinking enabled, disabled, reasoning effort, and custom conflicting fields.
- Chat streaming, auxiliary text streaming, and connection test.
- Cleartext HTTP enabled, HTTPS with cleartext enabled, and HTTP with cleartext disabled role serialization.
- HTTP error, empty content, reasoning-only content, malformed JSON, timeout, peer reset, user cancellation before content, cancellation after partial content, and cancellation during final persistence.

## Stop Conditions

- Do not assume two UI features use the same model.
- Do not diagnose a parsing failure as an API failure without response evidence.
- Do not duplicate provider retries or fallback logic in multiple callers.
