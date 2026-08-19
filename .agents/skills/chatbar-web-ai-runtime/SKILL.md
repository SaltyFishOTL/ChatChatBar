---
name: chatbar-web-ai-runtime
description: Maintain ChatBar session-bound WebView AI transport, built-in DeepSeek/Kimi/Doubao adapters, login/browser lifecycle, DOM automation, full-context submission, webpage stream extraction, dynamic web model resolution, and standard chat rendering. Use when changing or diagnosing webpage AI binding, highlighted “网页版” chat/image model choices, hidden WebView generation, selectors broken by site updates, login/cookie behavior, single-task locking, app-background cancellation, or web output/stop detection.
---

# ChatBar Web AI Runtime

Preserve separation between ChatBar conversation truth and session-bound website transport conversations. Website supplies transport only; ChatBar keeps prompt assembly, bubbles, persistence, retry UI, memory, and NovelAI generation ownership.

## First Read

- Persisted binding and model transport: `data/local/entity/ChatSession.kt`, `ModelConfig.kt`
- Dynamic model identity/resolution: `domain/webai/WebAiModelPolicy.kt`, `domain/model/EffectiveModelResolver.kt`
- Browser, security, adapters, DOM streaming: `domain/webai/WebAiController.kt`
- Transport dispatch: `domain/webai/WebAiGateway.kt`, `domain/chat/StreamingChatService.kt`
- Browser host and chat entry: `ui/webai/WebAiBrowserHost.kt`, `ui/chat/ChatScreen.kt`
- Highlighted selectors and session refresh: `ui/chat/ChatSettingsDialog.kt`, `ui/kit/Controls.kt`, `ui/chat/ChatViewModel.kt`
- App lifetime/DI: `MainActivity.kt`, `ChatBarApp.kt`
- Full-message envelope: `domain/prompt/PromptTemplates.kt`

Use `chatbar-model-request-runtime` for shared stream consumers and model fallback behavior. Use `chatbar-prompt-pipeline` when message assembly or envelope prompt changes. Use `chatbar-image-generation-runtime` when NovelAI generation changes.

## Invariants

- Store only `WebAiBinding(site, boundAt)` in session JSON. Keep WebView cookies app-global and outside exports.
- Use `web-ai:<sessionId>` as dynamic model ID. Resolve it against that exact session binding; stale web IDs never fall back to API models.
- Expose web model only after browser verifies an adapter-origin composer. User selects chat and image-prompt model independently.
- “Image model” means text model used by `NovelAiPromptDesigner`; actual NovelAI HTTP/image pipeline remains unchanged.
- Reject chat image attachments for web chat transport. Web v1 is text-only.
- Send full assembled `ChatApiMessage` history through `webAiConversationEnvelope`. Website-history cleanup remains unresolved; do not reuse one website thread or change new-chat behavior without an approved design.
- Allow one WebView task globally. Contention returns visible error; no queue and no API fallback.
- Keep task alive while browser is hidden inside foreground app. App `onStop` cancels task; do not acquire foreground-service lease.
- Cancellation clicks website stop control when detectable. Existing flow cancellation owns standard interrupted-reply behavior.
- Emit monotonic `ReasoningDelta` and `Delta`, then one `Done`. Stop with explicit error when DOM text rewrites would corrupt accumulated bubble.

## WebView Security

- Never add `addJavascriptInterface`.
- Run automation only after `WebAiSite.allowsAutomationAt` accepts HTTPS adapter origin.
- Cancel SSL errors. Disable file/content access, file-URL privilege, mixed content, and WebView debugging.
- Permit HTTPS login redirects for visible user interaction, but do not evaluate automation on redirect origins.
- Keep cookies persistent and shared so login survives sessions; never serialize cookies into `ChatSession` or `SaveSlot`.
- Construct `WebView` inside `AndroidView.factory`; release it through `onRelease`. Do not remember a detached `WebView` outside the interop host.
- Keep WebView host dimensions stable across loading-state changes. Progress UI must retain a fixed slot; toggling it must not resize or recreate WebView.

## Adapter Maintenance

1. Reproduce in visible browser and identify whether failure is composer, new-chat, send, assistant content, reasoning, or stop detection.
2. Update only matching `WebAiAdapter` selector lists/scripts in `WebAiController.kt`.
3. Prefer semantic attributes and stable roles before hashed classes.
4. Keep generic selectors as last resort. Do not broaden allowed automation hosts to solve selector failure.
5. Verify baseline output is ignored before new response starts, stream text stays monotonic, and stable completion waits after stop control disappears.
6. Treat login/CAPTCHA/site redesign as visible failure. Do not hide primary failure with API fallback.

## Persistence and Archive Rules

- Added session fields remain nullable/defaulted so old JSON decodes without migration.
- SaveSlots may carry dynamic model IDs but never binding/cookies. On load, remap web ID to current target session only when it is bound; otherwise clear selection and notify user.
- Unbinding clears current session web chat/image selections while preserving all other session state and history.

## Verification Focus

- Old session JSON opens with no web binding.
- Bind each site, hide browser, then confirm highlighted web choices appear in both selectors.
- Select web chat model: each task starts from a clean website conversation; full context, reasoning/content, and stop still work.
- Select web image model: NovelAI prompt design uses website text output; NovelAI generation remains normal.
- Add-image action is disabled and send guard rejects stale attached images.
- A second web task fails visibly. Switching app stops active task. Relaunch retains login cookies, session binding, and distinct website conversation URL.
- Unbind and SaveSlot restore preserve ChatBar history and never silently select API fallback.

## Stop Conditions

- Do not claim arbitrary website support without a maintained adapter.
- Do not scrape network tokens or private website APIs.
- Do not copy code from AGPL browser-automation projects into ChatBar.
- Do not treat compile success as proof current website DOM selectors work; require device manual verification.

