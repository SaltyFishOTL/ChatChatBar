---
name: chatbar-save-slot
description: Maintain ChatBar conversation SaveSlot creation, listing, loading, import/export, v8 streaming packages, media inclusion policies, legacy schema compatibility, and transactional restore. Use when changing or diagnosing chat archives, `.cbsave` files, archive memory use, missing archived images/audio, or SaveSlot progress and cancellation.
---

# ChatBar SaveSlot

Keep archive size independent from peak app memory. Treat SaveSlot as a cross-store restore transaction, not one large serialized object.

## Entry Points

- Models and legacy inline fields: `app/app/src/main/java/com/example/chatbar/data/local/entity/SaveSlot.kt`.
- Summary persistence and legacy raw export: `data/repository/SaveSlotRepository.kt`.
- v8 package creation, validation, import/export, and media materialization: `domain/chat/SaveSlotPackageStorage.kt`.
- Chat orchestration and rollback: `ui/chat/ChatViewModel.kt`.
- Image/audio choices, progress, cancellation, and document pickers: `ui/chat/ChatSettingsDialog.kt`.
- Streaming entity replacement primitives: `data/local/JsonFileStorage.kt`, `data/repository/ChatRepository.kt`, and `domain/rag/RagRepository.kt`.
- Voice file restore: `domain/voice/FishAudioStorage.kt`; use `chatbar-fish-audio-voice` when voice ownership changes.
- Image metadata/display behavior: use `chatbar-image-generation-runtime`.
- Long-term-memory snapshot semantics: use `chatbar-long-term-memory`.

All abbreviated source paths are under `app/app/src/main/java/com/example/chatbar/`.

## Package Contract

- Schema 8 uses a ZIP `.cbsave`: `manifest.json`, line-delimited `messages.jsonl`, `rag.jsonl`, optional `voices.jsonl`, and media beneath `media/images/` or `media/audio/`.
- Keep manifest lists empty for v8; `SaveSlotPackageRef` owns counts and package metadata. Old schema 1–7 remains inline JSON and decode-compatible.
- Summary listing must parse only lightweight top-level fields. Do not deserialize old Base64 media to show the SaveSlot list or export an unchanged legacy archive.
- Detect import format from stream bytes, not filename or MIME type. Export v8 as `.cbsave`; retain legacy JSON export.
- Validate manifest version, duplicate/blank IDs, declared counts, and every media reference before replacing target-session data.

## Bounded-Memory Invariants

- Stream messages and RAG one record at a time; keep only a small repository batch in memory.
- Stream original images/audio with a fixed buffer. For compressed images, decode and recycle one sampled bitmap at a time; animated GIF stays original.
- Never rebuild package media as Base64, a whole ZIP byte array, or one in-memory message list.
- `SaveSlotImagePolicy.NONE` must not read image bytes. Preserve one omitted-image token per attachment so message layout and attachment count survive restore.
- Transform `ChatMessage.images` and matching `generatedImageMetadata.imagePath` together for every image policy.

## Restore and Ownership

- Stage and validate the package plus materialize resources before committing session data. Replace message and RAG entity sets through their streaming transactional APIs.
- Remap restored message session IDs, RAG IDs, voice IDs, image paths, metadata paths, and background path to the target session.
- Persist replacement records before deleting old owned media. On pre-commit failure or cancellation, delete only files created by that attempt.
- Never delete external files. Package deletion may target only the deterministic app-private SaveSlot package path.
- Restore only the current long-term-memory snapshot. Do not archive histories, running jobs, coordinator state, or stale runtime progress.

## UI and Lifecycle

- Default new SaveSlot to no images. Offer compressed and original policies explicitly; audio inclusion is independent.
- Keep creation, loading, import, and export progress visible and cancellable. Do not dismiss the dialog before a long operation reports completion or failure.
- A restored omitted image is a durable notice, not a loading state. A missing included resource is an archive validation failure.
- Startup may clean stale partial package files, but must not sweep complete packages without matching SaveSlot ownership checks.

## Review Checklist

1. Trace one message, generated-image metadata record, background, voice, RAG chunk, and memory snapshot through create → export → import → restore.
2. Check cancellation before materialization, during media copy, and during transactional replacement.
3. Check schema 1–7 listing/export/load separately from v8.
4. Verify default no-image, compressed-image, original-image, and audio-off paths without increasing peak memory with archive size.
