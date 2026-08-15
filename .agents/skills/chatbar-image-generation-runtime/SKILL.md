---
name: chatbar-image-generation-runtime
description: Maintain ChatBar image-generation runtime across NovelAI HTTP generation, streaming frames, retries, background-work coordination, prompt-tool generation, generated-image metadata, editable regeneration, new-seed behavior, safe file replacement, and shared chat/Moments UI. Use when changing image generation concurrency, retry or error handling, prompt-tool manual generation, regeneration dialogs, prompt metadata persistence, dimensions, seeds, or owned-image lifecycle.
---

# ChatBar Image Generation Runtime

Keep prompt design, HTTP generation, persistence, and feature UI as separate owners. Use chatbar-novelai-prompt whenever tag-design text or NovelAiPromptDesigner behavior changes.

## First Read

- Persisted generated-image metadata: data/local/entity/ChatMessage.kt and data/local/entity/MomentEntities.kt
- NovelAI HTTP, batch policy, and frame parsing: domain/image/NovelAiImageService.kt and NovelAiBatchPolicy.kt
- Prompt-plan/metadata conversion: domain/image/NovelAiImageRegeneration.kt
- Prompt design boundary: domain/image/NovelAiPromptDesigner.kt
- Shared editor: ui/components/NovelAiImageRegenerationDialog.kt
- Shared image viewer/actions: ui/components/ImagePreviewDialog.kt and ImageMosaicEditor.kt
- Prompt tool: ui/imageprompt/ImagePromptToolViewModel.kt and ImagePromptToolScreen.kt
- Prompt-tool image processing: ui/imageprompt/ImageProcessingPage.kt, ImageProcessingViewModel.kt, domain/image/ImageProcessingService.kt, and FullImageAdversarialPatch.kt
- Character-card PNG export patch option: domain/card/CharacterCardPngRenderer.kt and ui/manage/ManageScreen.kt
- Chat orchestration: ui/chat/ChatViewModel.kt and ChatScreen.kt
- Moments orchestration: ui/moments/MomentsViewModel.kt and MomentsScreen.kt
- Shared foreground/background protection: use `chatbar-background-work-runtime`
- Tests: NovelAiImageRetryTest.kt, NovelAiImageRegenerationTest.kt, ChatImageActionPolicyTest.kt, NovelAiImageRegenerationDialogTest.kt, and image metadata serialization tests

Use chatbar-character-card-ai for card cover/avatar candidate policy and chatbar-moments for post identity, scheduling, and placeholder behavior.

## Service Invariants

- Keep NovelAiImageService.generate as shared NovelAI HTTP owner for chat, character images, prompt tool, and Moments.
- Pass batch size through `n_samples`; current Normal-area presets allow 1–4 images per request. Keep single-image callers on the default value of 1.
- Emit intermediate, final, and error events consistently; cancel active OkHttp call when Flow closes.
- Treat `retry` stream frames as transient server control signals: keep reading the same stream instead of failing or starting another HTTP attempt.
- Treat a batch as successful only after the stream returns exactly the requested number of final frames. Surface partial or extra results as failure instead of silently accepting them.
- Retry HTTP 429 at the shared service only. Current contract is three total attempts, Retry-After capped at 30 seconds, otherwise 1-second then 2-second delay.
- Do not stack caller retries on top of service retries.
- Preserve correlation IDs and concrete HTTP/stream parse errors.
- Keep V4 coordinates disabled unless product behavior explicitly changes; stored centers remain compatibility metadata.
- Keep NovelAI quality-tag injection disabled and use `ucPreset=3` (None) so NovelAI does not combine its UC preset with app text. Send exactly one negative source—`NovelAiPromptPlan.effectiveNegativePrompt`—identically through legacy and V4 fields.

## Metadata and Regeneration

- Persist image path, base caption, per-character prompts, negative prompt, size preset, width, and height with every generated image.
- Convert metadata through NovelAiImageRegenerationDraft and NovelAiPromptPlan helpers instead of reconstructing fields in each screen.
- Prompt tool starts with `emptyNovelAiImageRegenerationDraft()`, converts AI plans through `toRegenerationDraft()`, and converts edited/manual drafts back through `toPromptPlan()` before generation.
- Prompt-tool saved results open through ImagePreviewDialog, reusing chat/Moments zoom, mosaic, gallery-save, and share actions; streaming-only frames stay non-actionable until a durable path exists.
- Persist a chat batch as one message with parallel image and metadata lists. Keep a prompt-tool batch together for result display and pager navigation.
- Insert a completed chat image directly after its anchor by allocating a free `orderKey`; do not renumber existing messages because text regeneration may hold the anchor's key while streaming. Repository refreshes during regeneration must keep the replaced persisted bubble hidden until the replacement persists; merge transient and durable versions by stable message ID so completion or interruption swaps in place without resetting scroll. Historical Debug repair may rebuild only `orderKey` from stable source-turn order plus valid image-anchor chains; require snapshot confirmation, durable backup, and safe undo.
- Regeneration exposes editable main and negative prompts, plus zero to six addable/removable character prompts.
- Initialize regeneration with original pixel dimensions, allow explicit Portrait/Square/Horizontal override, and request a fresh seed.
- Legacy images may recover metadata from persisted fields or embedded PNG metadata where feature policy supports it.
- Keep shared dialog content scrollable and bottom actions visible.
- While FullscreenTextEditor is active, stop composing CbDialog; its separate Android window otherwise covers the activity-hosted editor. Restore the dialog when fullscreen editing closes.

## File and State Safety

- Save the new image and persist its metadata/path before deleting an old app-owned file.
- On generation or save failure, retain the old image and metadata.
- If any image in a batch fails to save or the owning repository update fails, delete only newly saved files from that attempt; do not persist a partial batch.
- Never delete unrelated or user-owned files.
- Keep prompt-tool reference images as owned draft assets. Copy a replacement before deleting the previous asset; removal and ViewModel cleanup may delete only that owned draft path.
- Keep image-processing imports and results in the owned `filesDir/images/image-processing` work area. Static output is PNG; GIF output preserves animation by processing every frame. Share/save through shared image actions, and clean only stale work files.
- Full-image patch apply/restore must share one deterministic transform. Restore is exact only before channel clipping, compression, resizing, or later edits.
- Character-card export applies the optional full-image patch only to the final rendered PNG pixels before metadata insertion; packaged source images and local card-owned files remain unchanged.
- Character-card export dialog uses `CharacterCardPngRenderer` at 1024px for its debounced patched preview so preview and saved PNG share the same transform; the unpatched path keeps the lightweight Compose preview.
- The full-image patch is a five-layer multi-scale chroma transform (per-frame global color cast ±6, 3×3 block shift ±28, 8×8 DCT-aligned DC bias ±14, three low-frequency sine waves at 64/96/128px periods whose phases shift per frame index, Bayer ordered-dither jitter). Keep every layer deterministic from position and frame index so restore stays an exact inverse before lossy steps.
- GIF output re-encodes each frame with `AnimatedGifEncoder` quality 1 (lower value = denser NeuQuant sampling = finer palette), keeping per-channel perturbation amplitude (~±30 RMS) well above palette quantization noise; this SNR margin is what preserves the patch through 256-color quantization. Do not add pixel-domain residual feedback loops (encode→decode→diffuse): nearest-color mapping is locally optimal, so residual feedback is absorbed at snap time and changes nothing.
- Preserve owning entity identity and non-image state: message alternatives/timeline data or Moment text/likes/time.
- Moments on-demand generation (text-only post → generate image) reuses `NovelAiPromptDesigner.designForMoment` for the prompt plan and `NovelAiImageService.generate` for bytes; both stream live (design text via `onDelta`, image progress via `Intermediate`). Persist the new path+metadata and only then remove any replaced file; never reach image state if the post becomes a placeholder. Run inside `AiBackgroundWorkManager` + `GlobalImageGenerationConcurrencyGate`.
- Keep text generation and independent image tasks from blocking each other unless they mutate the same owned image slot.
- Use `chatbar-background-work-runtime` when changing shared protection, notification, network-loss, or foreground-service lifecycle behavior.

## Workflow

1. Classify change as prompt design, HTTP service, metadata, file lifecycle, concurrency, or UI.
2. Trace one generated image from prompt plan through bytes, saved path, metadata, display, and regeneration.
3. Put shared behavior in domain/image or shared UI; keep entity-specific replacement in its ViewModel/repository.
4. Define cancellation and failure ownership before changing concurrency.
5. Add service, serialization, and replacement-order tests.

## Regression Matrix

- Intermediate then final stream; server error frame; malformed frame; cancellation.
- Batch size validation, `n_samples` serialization, exact final-frame count, grouped metadata, and partial-save cleanup.
- 429 succeeds on third attempt and fails once after three total attempts.
- New image and legacy image metadata loading.
- Editable prompt round-trip, character add/remove limits, original dimensions, and new seed.
- Prompt-tool blank manual draft, AI-plan materialization, manual generation, reference-image replacement, and vision fallback.
- Image-processing apply/restore status visibility, automatic result reveal, static-image round trip, layered patch inverse exactness and per-frame variation, GIF frame count/timing/loop/transparency, patch amplitude vs palette quantization noise margin, direct share, and gallery save.
- Character-card PNG export defaults the patch off, preserves the option through normalization, and keeps embedded card metadata importable when the patch is enabled.
- Fullscreen prompt editor hides the dialog window, then restores it on close without losing the draft.
- Save failure, repository failure, and old-file cleanup failure.
- Concurrent text generation and image generation; two unrelated image tasks.
- Chat and Moments reuse of shared regeneration dialog.

## Stop Conditions

- Do not embed feature policy in NovelAiImageService.
- Do not make UI state the only copy of regeneration metadata.
- Do not delete the old image before the replacement is durable.
