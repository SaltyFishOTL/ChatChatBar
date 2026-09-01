---
name: chatbar-shared-import
description: Maintain ChatBar external ACTION_SEND/ACTION_VIEW/text import routing, content inspection, FIFO staging, resource conflicts, management focus, and shared-image handoff. Use when changing or diagnosing files shared into ChatBar, incorrect card-type detection, queued imports, unknown-format recovery, or Studio routing for shared images.
---

# ChatBar Shared Import

External sharing is one global ingestion pipeline. Management-page import buttons remain independent, explicitly typed entry points.

## Entry Points

- Android intents and URI-over-text precedence: `MainActivity.kt` and `AndroidManifest.xml`.
- Process queue, staging ownership, and cleanup: `domain/card/SharedImportCoordinator.kt` and `SharedImportFifoQueue.kt`.
- Content-first classification and strict manual decoding: `domain/card/SharedImportClassifier.kt`.
- Global dialogs and automatic resource persistence: `ui/shared/SharedImportHost.kt`.
- App wiring, routing, and management focus: `ChatBarApp.kt`, `Navigation.kt`, and `ui/manage/ManageScreen.kt`.
- Model transfer ownership: `domain/card/ModelTemplateTransferService.kt`.
- Shared-image destination handoff: use `chatbar-image-generation-runtime`, then read `ui/imageprompt/ImagePromptToolScreen.kt`, `ImagePromptToolViewModel.kt`, and `domain/image/ImageProcessingService.kt`.

All Kotlin paths are relative to `app/app/src/main/java/com/example/chatbar/`.

## Contracts

- Accept one file from `ACTION_SEND`, one URI from `ACTION_VIEW`, or nonblank `EXTRA_TEXT` only when no URI exists. Do not silently add `ACTION_SEND_MULTIPLE` support.
- Copy URI content into `filesDir/shared-import` immediately. Detection must use staged bytes, not extension or declared MIME. Queue remains FIFO and process-local; startup removes stale staging files.
- Recognize only strictly decodable ChatBar character/format/model/world-book packages, SillyTavern V1/V2 character JSON or Chara PNG, SillyTavern World Info, and ChatBar character PNG. Invalid or ambiguous data stays Unknown and never falls back to character import.
- A decodable image without supported card metadata stays Image, including NovelAI metadata PNG. GIF may enter image tools but not guidance.
- High-confidence resources import automatically. Character, format, and world-book name conflicts retain overwrite/new/cancel; model templates always create a new model with empty API key.
- Unknown text/JSON exposes four explicit strict decoders. Decoder failure remains visible without consuming the queue item.
- Delete staged content only after resource persistence or destination-owned image copy succeeds. Cancellation/failure advances FIFO without affecting later items.
- Shared image guidance creates one generation-fitted base copy plus one natural-dimension reference copy, loads them into Image-to-Image/Focused Inpainting, Precise, and Vibe source slots, defaults active action to Image-to-Image, and opens guidance editing after durable draft save. The three source groups remain independently replaceable and clearable. Image tools copy into their owned processing area. Busy Studio waits; shared import must not cancel generation.
- Completed resource imports navigate to the matching management tab and scroll to the new or overwritten item before acknowledging the queue item.

## Verification Focus

- Classifier fixtures: ChatBar four types, SillyTavern character/world-book shapes, ChatBar/ST PNG, ordinary/NovelAI/GIF images, BOM, incorrect MIME, ambiguity, invalid payloads.
- Queue transitions: FIFO arrival, stale/duplicate completion, cancel/failure continuation, URI precedence, and staged-file cleanup after safe handoff.
- Persistence: three conflict actions, model API key clearing, character post-import processing, management focus, and Studio busy handoff.
