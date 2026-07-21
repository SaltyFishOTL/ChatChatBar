---
name: chatbar-image-generation-runtime
description: Maintain ChatBar image-generation runtime across NovelAI HTTP generation, streaming frames, retries, background-work coordination, prompt-tool generation, generated-image metadata, editable regeneration, new-seed behavior, safe file replacement, and shared chat/Moments UI. Use when changing image generation concurrency, retry or error handling, prompt-tool manual generation, regeneration dialogs, prompt metadata persistence, dimensions, seeds, or owned-image lifecycle.
---

# ChatBar Image Generation Runtime

Keep prompt design, HTTP generation, persistence, and feature UI as separate owners. Use chatbar-novelai-prompt whenever tag-design text or NovelAiPromptDesigner behavior changes.

## First Read

- Persisted generated-image metadata: data/local/entity/ChatMessage.kt and data/local/entity/MomentEntities.kt
- NovelAI HTTP and frame parsing: domain/image/NovelAiImageService.kt
- Prompt-plan/metadata conversion: domain/image/NovelAiImageRegeneration.kt
- Prompt design boundary: domain/image/NovelAiPromptDesigner.kt
- Shared editor: ui/components/NovelAiImageRegenerationDialog.kt
- Shared image viewer/actions: ui/components/ImagePreviewDialog.kt and ImageMosaicEditor.kt
- Prompt tool: ui/imageprompt/ImagePromptToolViewModel.kt and ImagePromptToolScreen.kt
- Chat orchestration: ui/chat/ChatViewModel.kt and ChatScreen.kt
- Moments orchestration: ui/moments/MomentsViewModel.kt and MomentsScreen.kt
- Background protection: domain/service/AiBackgroundWorkManager.kt
- Tests: NovelAiImageRetryTest.kt, NovelAiImageRegenerationTest.kt, ChatImageActionPolicyTest.kt, NovelAiImageRegenerationDialogTest.kt, and image metadata serialization tests

Use chatbar-character-card-ai for card cover/avatar candidate policy and chatbar-moments for post identity, scheduling, and placeholder behavior.

## Service Invariants

- Keep NovelAiImageService.generate as shared NovelAI HTTP owner for chat, character images, prompt tool, and Moments.
- Emit intermediate, final, and error events consistently; cancel active OkHttp call when Flow closes.
- Retry HTTP 429 at the shared service only. Current contract is three total attempts, Retry-After capped at 30 seconds, otherwise 1-second then 2-second delay.
- Do not stack caller retries on top of service retries.
- Preserve correlation IDs and concrete HTTP/stream parse errors.
- Keep V4 coordinates disabled unless product behavior explicitly changes; stored centers remain compatibility metadata.

## Metadata and Regeneration

- Persist image path, base caption, per-character prompts, negative prompt, size preset, width, and height with every generated image.
- Convert metadata through NovelAiImageRegenerationDraft and NovelAiPromptPlan helpers instead of reconstructing fields in each screen.
- Prompt tool starts with `emptyNovelAiImageRegenerationDraft()`, converts AI plans through `toRegenerationDraft()`, and converts edited/manual drafts back through `toPromptPlan()` before generation.
- Prompt-tool saved results open through ImagePreviewDialog, reusing chat/Moments zoom, mosaic, gallery-save, and share actions; streaming-only frames stay non-actionable until a durable path exists.
- Regeneration exposes editable main and negative prompts, plus zero to six addable/removable character prompts.
- Preserve original pixel dimensions and request a fresh seed for each regeneration.
- Legacy images may recover metadata from persisted fields or embedded PNG metadata where feature policy supports it.
- Keep shared dialog content scrollable and bottom actions visible.
- While FullscreenTextEditor is active, stop composing CbDialog; its separate Android window otherwise covers the activity-hosted editor. Restore the dialog when fullscreen editing closes.

## File and State Safety

- Save the new image and persist its metadata/path before deleting an old app-owned file.
- On generation or save failure, retain the old image and metadata.
- Never delete unrelated or user-owned files.
- Keep prompt-tool reference images as owned draft assets. Copy a replacement before deleting the previous asset; removal and ViewModel cleanup may delete only that owned draft path.
- Preserve owning entity identity and non-image state: message alternatives/timeline data or Moment text/likes/time.
- Keep text generation and independent image tasks from blocking each other unless they mutate the same owned image slot.
- Route long-running work through AiBackgroundWorkManager where the caller already uses foreground protection.

## Workflow

1. Classify change as prompt design, HTTP service, metadata, file lifecycle, concurrency, or UI.
2. Trace one generated image from prompt plan through bytes, saved path, metadata, display, and regeneration.
3. Put shared behavior in domain/image or shared UI; keep entity-specific replacement in its ViewModel/repository.
4. Define cancellation and failure ownership before changing concurrency.
5. Add service, serialization, and replacement-order tests.

## Regression Matrix

- Intermediate then final stream; server error frame; malformed frame; cancellation.
- 429 succeeds on third attempt and fails once after three total attempts.
- New image and legacy image metadata loading.
- Editable prompt round-trip, character add/remove limits, original dimensions, and new seed.
- Prompt-tool blank manual draft, AI-plan materialization, manual generation, reference-image replacement, and vision fallback.
- Fullscreen prompt editor hides the dialog window, then restores it on close without losing the draft.
- Save failure, repository failure, and old-file cleanup failure.
- Concurrent text generation and image generation; two unrelated image tasks.
- Chat and Moments reuse of shared regeneration dialog.

## Stop Conditions

- Do not embed feature policy in NovelAiImageService.
- Do not make UI state the only copy of regeneration metadata.
- Do not delete the old image before the replacement is durable.
