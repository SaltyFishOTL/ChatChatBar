---
name: chatbar-fish-audio-voice
description: Maintain ChatBar Fish Audio voice generation across API credentials, voice-library search and preview, character voice bindings, AI tag generation and text confirmation, stable message anchors, concurrent TTS, local audio persistence, playback, SaveSlot/card transfer, cleanup, and chat UI. Use when changing or diagnosing Fish Audio settings, model listing, TTS requests, voice generation progress or cancellation, speaker matching, generated voice messages, autoplay order, ExoPlayer crashes, or voice migration and export behavior.
---

# ChatBar Fish Audio Voice

Keep voice generation independent from chat text, prompt context, and long-term memory. Read the smallest matching path first.

Use `chatbar-model-request-runtime` for shared auxiliary-model resolution or SSE transport, `chatbar-background-work-runtime` for foreground leases, `chatbar-character-card-ai` for auto-fill/rewrite merge behavior, `chatbar-shadcn-compose` for UI changes, and `chatbar-emulator-test` for device playback verification.

## First Read

- App wiring: `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt`.
- Settings and credentials: `data/local/entity/AppSettings.kt`, `data/security/FishAudioCredentialStore.kt`, `ui/manage/ManageScreen.kt`, `ManageViewModel.kt`.
- Voice bindings and transfer: `data/local/entity/CharacterCard.kt`, `domain/card/CardTransferModels.kt`, `CharacterCardTransferService.kt`.
- API and models: `domain/voice/FishAudioModels.kt`, `FishAudioService.kt`.
- Tagging and orchestration: `domain/voice/FishAudioTagService.kt`, `FishAudioGenerationCoordinator.kt`, `domain/prompt/PromptTemplates.kt`.
- Anchors and persistence: `domain/voice/VoiceAnchorPolicy.kt`, `data/local/entity/FishAudioEntities.kt`, `data/repository/VoiceMessageRepository.kt`, `domain/voice/FishAudioStorage.kt`.
- Playback: `domain/voice/VoicePlaybackController.kt`.
- Chat and character UI: `ui/chat/ChatScreen.kt`, `ChatViewModel.kt`, `ui/components/ChatBubble.kt`, `ui/character/CharacterEditScreen.kt`, `CharacterEditViewModel.kt`.
- Save/restore and deletion: `data/local/entity/SaveSlot.kt`, `domain/deletion/DeletionCoordinator.kt`.
- Tests: `domain/voice/FishAudioRequestFactoryTest.kt`, `FishAudioTagPolicyTest.kt`, `VoiceAnchorPolicyTest.kt`, `domain/card/CharacterFishAudioSerializationTest.kt`, and SaveSlot serialization tests.

All abbreviated source paths are under `app/app/src/main/java/com/example/chatbar/`; tests are under `app/app/src/test/java/com/example/chatbar/`.

## Ownership and Isolation

- Keep generated voice records outside `ChatMessage`. Chat prompt assembly, RAG, source fingerprints, and long-term memory must continue reading text and existing image fields only.
- Preserve generated voice history after character deletion, card replacement, speaker-tag edits, or later voice changes. Historical records use generation-time character, voice, and Fish-model snapshots.
- Delete voices only with their owning message/session, explicit voice deletion, or failed replacement cleanup.
- Keep Fish API keys in Android Keystore. Never serialize, export, log, or save blank credentials.
- Gate new voice selection and generation on a configured Fish key. Keep existing local voice playback and deletion available without the key.

## API and Voice Binding

- Keep community and personal libraries on `GET /model` with explicit `self`, pagination, title/tag/language filters, and sort mapping.
- Keep TTS on `POST /v1/tts` with Bearer auth, `model` header, `reference_id`, MP3, and 64 kbps.
- Do not synthesize paid previews when a model has no official sample. Previewing never binds a voice; only explicit selection mutates the character draft.
- Store a non-sensitive `FishAudioVoiceBinding` snapshot on each `CharacterInfo`.
- Preserve `fishAudioVoice` through character-card copy/import/export/community transfer and AI auto-fill/rewrite materialization. AI output must not create or overwrite it.
- Treat inaccessible private bindings as unavailable. Never substitute another voice silently.

## Tagging and Confirmation

- Generate tags only for assistant `DIALOGUE` and `THOUGHT` segments with one unique speaker match and a bound voice.
- Resolve `voiceTagModelId = null` to the current session model. Treat an explicit stale model as disabled; never fall back.
- Call the shared streaming text service with thinking disabled. Keep prompts in `PromptTemplates`.
- Validate strict JSON IDs, duplicates, omissions, unknown IDs, tag syntax, and unchanged spoken text separately.
- Use fixed parenthesized S1 tags and bracketed S2 cues.
- Never send original text directly to Fish after tag failure.
- When legal AI output changes spoken text, enter `AWAITING_TEXT_CONFIRMATION`; show original/proposed text and wait for explicit use/reject.
- Release the foreground-work lease while awaiting user confirmation. Resume synthesis under a fresh lease after acceptance.
- Treat long-press regeneration text as already user-confirmed, but still validate Fish tag syntax.

## Anchors, Files, and Lifecycle

- Reconcile stable anchors after content edits, alternative switches, or segment deletion with character-offset mapping plus monotonic segment matching.
- Preserve explicit target IDs for direct segment edits. Reattach a deleted target to the previous speakable segment, or make it a message-head orphan when none exists.
- Order voices by source segment order, then creation time. Allow multiple voices per anchor.
- Write downloads to `.part`, verify response length and parsable duration, then atomically replace the final MP3.
- On regeneration, persist the replacement before deleting the previous file. Preserve the old record/file on failure.
- Cancel and join message/session batches before repository or file deletion so completed network work cannot resurrect deleted voices.
- Clean stale `.part` and unreferenced owned audio without touching external files.
- Keep SaveSlot voice metadata and Base64 audio restoration transactional. Keep character-card package compatibility for older versions.

## Concurrency, Progress, and Playback

- Share one five-permit TTS semaphore across all batches. Whole-message generation calls the tag model once, then synthesizes missing targets concurrently.
- Persist successful targets during partial failure, but do not autoplay any partially failed or cancelled batch.
- Keep visible batch states for queued, tagging stream, text confirmation, synthesis completion count/bytes, failure, and cancellation.
- Use shared `AiBackgroundWorkManager` only while model/TTS network work is active. Do not hold a lease for user decisions.
- Preserve autoplay order by session timeline and visual voice order. Later messages must not steal playback when earlier batches are pending.
- Discard autoplay intent when the originating ChatScreen is no longer resumed. Do not replay it on return.
- Serialize every ExoPlayer call onto its application/main thread. Never call `stop`, media-item APIs, `prepare`, or `play` from the app IO scope.
- Manual voice taps stop current playback and automatic queues, then restart only the selected voice from the beginning.

## UI and Tutorial

- Keep voice progress cards visible near the composer rather than below a potentially long message.
- Put QQ-style voice bars below their anchor; when segmented bubbles are off, list all voices below the whole text bubble.
- Keep voice blocks independently selectable for long screenshots.
- Document every voice long-press action in the advanced tutorial. Keep basic setup, binding, generation, confirmation, and playback instructions in the basic tutorial.

## Workflow

1. Identify the owner: API, tag protocol, coordinator, anchor/repository, playback, transfer, or UI.
2. Reproduce from persisted state and exact batch phase before changing fallback or cleanup behavior.
3. Preserve isolation, historical snapshots, cancellation ordering, and foreground-lease boundaries.
4. Add focused JVM tests for request construction, tag parsing, anchor migration, or serialization.
5. Compile UI/shared changes, run CI-equivalent checks, then verify playback and background behavior on a release-signed device.

## Verification

Run from `app/`:

```powershell
.\gradlew.bat test
.\gradlew.bat :app:compileDebugKotlin --rerun-tasks
powershell -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble
```

With a connected physical device, use `chatbar-emulator-test` and the data-preserving release deployment. Reproduce playback with AndroidRuntime logs when investigating Media3 crashes.

## Stop Conditions

- Do not move voice metadata into `ChatMessage` or prompt/history inputs.
- Do not weaken strict tag parsing into hidden plain-text fallback.
- Do not access ExoPlayer from IO/background dispatchers.
- Do not hold foreground protection while waiting for user confirmation.
- Do not delete historical voice files because current character/card configuration changed.
