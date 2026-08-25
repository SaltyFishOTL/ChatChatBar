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
- Prompt tool: ui/imageprompt/ImagePromptToolViewModel.kt and ImagePromptToolScreen.kt; gallery-import parsing/merge lives in domain/image/NovelAiPngMetadataReader.kt and NovelAiStudioImageImport.kt
- Studio prompt token budgets: domain/image/NovelAiPromptTokenCounter.kt plus assets/tokenizers; reproducible compact `.binz` GZIP assets come from tools/build_novelai_tokenizer_assets.py (`.gz` is forbidden because Android packaging expands and renames it)
- Studio Prompt display translations: domain/image/NovelAiPromptTranslation.kt, data/repository/NovelAiPromptTranslationCacheRepository.kt, and annotation state/rendering in ImagePromptToolViewModel.kt plus ImagePromptToolScreen.kt
- Studio account quota and immediate Anlas estimate: domain/image/NovelAiAccountService.kt; UI ownership remains ImagePromptToolViewModel.kt and ImagePromptToolScreen.kt
- Studio contracts and persistence: domain/image/NovelAiStudioModels.kt, data/repository/NovelAiStudioRepository.kt, ui/imageprompt/NovelAiHistoryViewModel.kt, and NovelAiHistoryScreen.kt
- Studio image guidance: domain/image/NovelAiImageGuidance.kt, NovelAiStudioAssetStorage.kt, NovelAiVibeEncodingService.kt, and ui/imageprompt/NovelAiImageGuidanceEditor.kt
- Prompt-tool image processing: ui/imageprompt/ImageProcessingPage.kt, ImageProcessingViewModel.kt, domain/image/ImageProcessingService.kt, and FullImageAdversarialPatch.kt
- Character-card PNG export patch option: domain/card/CharacterCardPngRenderer.kt and ui/manage/ManageScreen.kt
- Chat orchestration: ui/chat/ChatViewModel.kt and ChatScreen.kt
- Moments orchestration: ui/moments/MomentsViewModel.kt and MomentsScreen.kt
- Shared foreground/background protection: use `chatbar-background-work-runtime`
- Tests: NovelAiImageRetryTest.kt, NovelAiImageRegenerationTest.kt, ChatImageActionPolicyTest.kt, NovelAiImageRegenerationDialogTest.kt, and image metadata serialization tests

Use chatbar-character-card-ai for card cover/avatar candidate policy and chatbar-moments for post identity, scheduling, and placeholder behavior.

## Service Invariants

- Keep NovelAiImageService.generate as shared NovelAI HTTP owner for chat, character images, prompt tool, and Moments.
- A session “网页版” image model changes only `NovelAiPromptDesigner` text transport through `StreamingChatService`; NovelAI byte generation remains here. Use `chatbar-web-ai-runtime` for WebView binding/DOM failures.
- `NovelAiImageService.generate` accepts explicit `NovelAiGenerationSettings`; serialize model, dimensions, 1–4 `n_samples`, steps, guidance, CFG Rescale, sampler, and base seed. Compatibility overloads default to V4.5 Full with CFG Rescale disabled. Non-studio app flows read `AppSettings.novelAiImageModel` and keep legacy 28 steps, guidance 8, CFG Rescale 0, Euler Ancestral, and existing batch count; studio keeps its independent per-model settings.
- Emit intermediate, final, and error events consistently; cancel active OkHttp call when Flow closes.
- Treat `retry` stream frames as transient server control signals: keep reading the same stream instead of failing or starting another HTTP attempt.
- Treat a batch as successful only after the stream returns exactly the requested number of final frames. Surface partial or extra results as failure instead of silently accepting them.
- Retry HTTP 429 at the shared service only. Current contract is three total attempts, Retry-After capped at 30 seconds, otherwise 1-second then 2-second delay.
- Do not stack caller retries on top of service retries.
- Preserve correlation IDs and concrete HTTP/stream parse errors.
- Keep V4/V5 coordinates disabled unless product behavior explicitly changes; stored centers remain compatibility metadata.
- For V5 only, request construction collects quoted positive text from base and character prompts and appends one base `Text:` block. Preserve quoted source text; any explicit case-insensitive `Text:` block disables automation for the whole request. V4.5 remains unchanged.
- Studio token budgets count the same effective outbound prompt: V4.5 uses NovelAI T5 rules with a 512-token linked-field limit; V5 Full uses NovelAI Qwen rules with a 1471-token linked-field limit. Keep tokenizer assets lazy, compact, local, and off the main thread. Qwen's regex already carries explicit Unicode properties; do not pass Java `UNICODE_CHARACTER_CLASS`, which Android rejects on supported devices. Positive and negative totals each link base plus ordered character fields; counting failure stays visible but never blocks generation.
- Prompt Chinese annotations use an independent, non-interactive Canvas overlay anchored to raw `TextLayoutResult`; never insert annotation characters through `OutputTransformation`. Prompt-only line spacing reserves a compact ruby row below each source Tag; a 1px rule extends short Chinese labels to their source Tag end, and overlay scroll follows the raw field. When one source Tag spans several layout lines, consume and draw its Chinese translation across the corresponding ruby slots instead of ellipsizing it on the first line. Tag wrapping may use one stable same-length `OutputTransformation`: visually replace internal ASCII spaces outside natural-language/`Text:` blocks with NBSP, and replace ASCII commas with horizontally compressed fullwidth commas so Android exposes a real comma break opportunity. Raw spaces/commas and identity cursor offsets remain unchanged. Keep source text as sole owner for editing, selection/IME, clipboard, tokens, draft/history, undo/redo, and NovelAI requests. Discard stale annotations whose saved source range no longer matches current text. Translation defaults off and is controlled by the persisted translate icon beside the Prompt section title; gray means off, primary tint means on, and off cancels work plus clears all annotations. `FullscreenTextEditor` remains raw-source-only. When enabled, show cached/local results immediately, then query shared `TagSuggestClient` for exact Tag matches with every whitespace run normalized to `_`; only an exact candidate name with a Chinese name replaces local display. Tag parsing treats `,` and `，` as equivalent delimiters outside quoted/Text blocks. Missing matches, natural-language segments, and request failures use bundled 12k-entry offline dictionary plus image-prompt overrides. Unknown proper names remain visibly separated in English. Annotation behavior does not depend on AI-design natural-language switch, and failures remain non-blocking.
- Studio reads current Anlas and V5 Opus allowance from `/user/subscription` on entry and after successful generation. Keep the V5 count explicitly approximate. Immediately subtract successful Anlas cost and allowance generations locally, then reconcile both against later server changes so a stale response cannot undo visible spending. Estimate button cost locally with the official pixel-plus-Steps formula and free-sample rules so setting changes stay immediate; Opus exemption requires one text-to-image sample with no active image guidance, while every batch or guided generation is charged. Account-fetch failure must not block generation or claim a V5-free request.
- Keep NovelAI quality-tag injection disabled and use `ucPreset=3` (None) so NovelAI does not combine its UC preset with app text. Send exactly one negative source—`NovelAiPromptPlan.effectiveNegativePrompt`—identically through legacy and V4 fields.
- Studio guidance is explicit request data: i2i uses `img2img`; Inpaint uses `infill`, the model-specific `-inpainting` ID, and `add_original_image=false`. Resize the editor mask to request width/8 and height/8 without filtering, threshold at 155, and send that opaque binary PNG (white redraw, black preserve); restoring it to full request size causes stable backend timeouts. Send shared i2i `strength`/`noise` plus `inpaintImg2ImgStrength`. Final display composites over the original with an inward-only feather: pixels outside the user's full-resolution mask and a 10px inner guard remain original, followed by a 32px quintic transition so the latent-cell boundary band cannot become a halo. Restore server text metadata after composition. Never send preview-blue pixels. V5 pauses Precise/Vibe without deleting their V4.5 draft state. Precise and Vibe remain mutually exclusive on the wire; Fidelity maps to secondary strength as `1 - fidelity`.
- Vibe encoding happens only after Generate through `/ai/encode-vibe`. Cache by source SHA-256, model, and Information Extracted; slider movement never calls the endpoint. Its OkHttp request is coroutine-cancellable and cancellation closes the active call. Prepared encodings, normalized strengths, and request arrays stay positionally aligned.
- Guidance editor keeps mode tabs above the canvas, exposes explicit enabled/disabled source state plus replace/clear actions, uses standard undo/redo turn-arrow icons plus a labeled Done action, and gives every numeric property its own full-width slider row. One gesture recognizer owns the canvas: one pointer draws with the selected tool, while two pointers exclusively pan/zoom and cancel the pending stroke. Each mode keeps one chronological undo timeline across strokes, bitmap transforms, source enable/clear, reference type, and numeric parameters; coalesce one continuous slider drag into one step, and keep redo undoable. Each mode retains its own in-memory canvas, undo/redo, brush, zoom, and pan while switching tabs; Done materializes every edited mode, while Back discards them. Inpaint preview uses one fixed-alpha blue overlay with a binary, non-antialiased edge; never multiply color alpha and paint alpha.

## Metadata and Regeneration

- Chat/Moments metadata persists path, base caption, per-character prompts and negatives, base negative, size preset, width, and height. Studio history persists one batch recipe plus every owned path and per-image seed.
- Convert metadata through NovelAiImageRegenerationDraft and NovelAiPromptPlan helpers instead of reconstructing fields in each screen.
- Studio uses one debounced `NovelAiStudioDraft`, including per-model steps/guidance/CFG Rescale and other settings, fold state, AI inputs, conversion undo, ordered character positives/negatives, and natural-language mode. Its transient 80-step undo/redo timeline covers Prompt text, roles, model/settings, folds, imported/history/AI replacements, and image guidance; coalesce one continuous text edit or slider drag by stable field key. Keep owned guidance assets referenced by either history stack during orphan cleanup. CFG Rescale persists in draft/history, defaults to 0 for old payloads, serializes as `cfg_rescale`, and imports from NovelAI PNG metadata. Do not reconstruct it from transient Compose fields.
- Studio character-card import replaces style only when nonblank. Card character image prompts persist only as an unlimited AI reference catalog and never overwrite handwritten ordered character prompts or enter the NovelAI generation plan. Import is disabled until the draft is loaded and while any busy task or history apply is active; the selected card ID changes only with a successful draft import. Model character limits apply only to the editable/generated ordered character prompts.
- Studio generation owns a unique history ID/directory. Save every image, then persist the batch history JSON, then publish result paths. Any failure deletes only that new directory. Random-mode draft remains random after the concrete seeds are recorded.
- Studio generation captures one immutable draft/Prompt/settings snapshot before launch. Starting generation reserves the generating phase synchronously so repeated taps cannot replace the only cancellable Job. Prompt/settings undo, redo, and editing remain available during generation; stream/progress updates must preserve the live draft, while history records only the launch snapshot. Vibe encodings prepared for that request may enter its history recipe but must not replace a concurrently edited live draft.
- Applying a history image snapshots the previous complete draft in the separate undo singleton. Full reproduce restores recipe plus fixed selected seed; new-seed restore uses random mode; seed-only changes only current seed. Natural-language mode configures AI design only: new recipes leave its compatibility field false, history detail hides it, and applying history preserves the current draft mode.
- Studio history displays a flattened newest-first image gallery while preserving each batch's image order. Date and positive-Prompt filters are transient; detail and full-screen pagers use the current filtered image sequence and synchronize their page. Delete remains batch-scoped because each history ID owns one image directory.
- Persist a chat batch as one message with parallel image and metadata lists. Keep a prompt-tool batch together for result display and pager navigation.
- Insert a completed chat image directly after its anchor by allocating a free `orderKey`; do not renumber existing messages because text regeneration may hold the anchor's key while streaming. Repository refreshes during regeneration must keep the replaced persisted bubble hidden until the replacement persists; merge transient and durable versions by stable message ID so completion or interruption swaps in place without resetting scroll. Historical Debug repair may rebuild only `orderKey` from stable source-turn order plus valid image-anchor chains; require snapshot confirmation, durable backup, and safe undo.
- Regeneration exposes editable main and negative prompts, plus zero to six addable/removable character prompts.
- Initialize regeneration with original pixel dimensions, allow explicit Portrait/Square/Horizontal override, and request a fresh seed.
- Legacy images may recover metadata from persisted fields or embedded PNG metadata where feature policy supports it.
- Studio top bar keeps image guidance and image tools as separate icon entries. Image guidance opens its editor directly; image tools uses Android's document picker, then exposes metadata, mosaic, patch removal, and reverse Prompt in one toolbar. Recognized NovelAI PNG Comment metadata may selectively replace base positive, base negative, ordered character positives/negatives, generation settings, and fixed seed; unchecked sections, style, and natural-language mode remain unchanged.
- Guidance metadata import may additionally restore action values, i2i/Inpaint parameters, embedded base/mask/Precise images, and Vibe encodings. Never activate i2i/Inpaint when metadata omits its source image or mask. History recipes retain NAI-expressible guidance parameters and encoded Vibes but never own i2i sources, masks, or editor layers; disable full reproduction when a required source is missing. Output/history “use as” deep-copies first, then opens the selected guidance editor; an empty Inpaint mask is an editor state, not an operation error.
- Keep shared dialog content scrollable and bottom actions visible.
- While FullscreenTextEditor is active, stop composing CbDialog; its separate Android window otherwise covers the activity-hosted editor. Restore the dialog when fullscreen editing closes. While IME is visible, visually collapse an expanded output panel without changing its persisted fold state; closing IME restores the prior expansion.
- Studio multiline editors host one FullscreenTextEditor at screen root; never compose it inside a LazyColumn item. While a Prompt field owns focus and IME is visible, visually collapse the output panel without changing its saved fold state and replace header actions with one compact horizontally scrollable Tag-completion strip; keep the current output thumbnail at its left. Closing IME clears suggestions and restores the prior fold state. Candidate insertion replaces only the typed fragment up to the cursor and preserves any text after the cursor as a following Tag. Expanded studio output stays within half the screen and scrolls internally; every collapsed state keeps the current thumbnail visible. Recent thumbnails retain their owning recipe; output-header shortcuts apply settings with a new seed or apply only the image seed, while full reproduce remains in history detail. Generation state updates must preserve the user's current output fold state. Durable output/history images open shared ImagePreviewDialog for zoom, mosaic, save, and share.

## File and State Safety

- Save the new image and persist its metadata/path before deleting an old app-owned file.
- On generation or save failure, retain the old image and metadata.
- If any image in a batch fails to save or the owning repository update fails, delete only newly saved files from that attempt; do not persist a partial batch.
- Never delete unrelated or user-owned files.
- History deletion may delete only its ID-owned cache directory. Gallery copies are external and never deleted. Do not migrate or sweep legacy unindexed prompt-tool directories.
- Keep prompt-tool reference images as owned draft assets. Copy a replacement before deleting the previous asset; removal and ViewModel cleanup may delete only that owned draft path.
- Guidance assets live under `filesDir/images/studio-guidance`. Normalize static inputs to oriented PNG, reject animated GIF, and deep-copy output/history sources before draft publication. Startup cleanup retains current-draft, history-undo, and editor-checkpoint references; history deletion never owns guidance copies. Vibe cache files are separate from image orphan cleanup.
- Guidance editor checkpoints persist guidance parameters without feeding rendered canvas pixels back into the active editor. Canvas pixels become owned assets only on Done; explicit cancel clears the checkpoint. Orphan cleanup must include current draft, history undo, and active checkpoint before deleting guidance PNGs.
- Keep image-processing imports and results in the owned `filesDir/images/image-processing` work area. Static output is PNG; GIF output preserves animation by processing every frame. Share/save through shared image actions, and clean only stale work files.
- Gallery-import editing reuses ImageMosaicEditor. Its inverse full-image action uses `FullImagePatchOperation.Restore`; completed copies reopen shared ImagePreviewDialog so save/share/mosaic behavior stays centralized.
- Full-image patch apply/restore must share one deterministic transform. Restore is exact only before channel clipping, compression, resizing, or later edits.
- Character-card export applies the optional full-image patch only to the final rendered PNG pixels before metadata insertion; packaged source images and local card-owned files remain unchanged.
- Character-card export dialog uses `CharacterCardPngRenderer` at 1024px for its debounced patched preview so preview and saved PNG share the same transform; the unpatched path keeps the lightweight Compose preview.
- The full-image patch is a deterministic single-scale chroma transform: 3×3-block chroma shifts (±24/±12 chosen by a position hash), applied identically to every GIF frame. Keep the transform position-deterministic so restore stays an exact inverse before lossy steps. Multi-scale layers, per-frame global casts, and higher amplitudes were tried and made censorship evasion worse in practice, so the single-layer texture is the shipped default.
- GIF output re-encodes each frame with `AnimatedGifEncoder` default quality 10. Do not add pixel-domain residual feedback loops (encode→decode→diffuse): nearest-color mapping is locally optimal, so residual feedback is absorbed at snap time and changes nothing.
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
- Studio first defaults, full draft round-trip, per-model settings, AI-plan merge/restore, unlimited card-reference import, generated-role limit validation, output collapse, fixed bottom action, and 22-role scrolling.
- Studio batch transaction, per-image base-seed increments, history three-way apply, one-shot undo, owned-directory delete, and legacy-unindexed retention.
- Guidance action/model serialization, V5 reference pause/restore, Precise Fidelity inversion, Vibe cache hit/miss cost, same-size Inpaint mask validation, direct output/history “use as”, owned-copy survival after history deletion, canvas stroke/structure undo limits, and missing-source history behavior.
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
