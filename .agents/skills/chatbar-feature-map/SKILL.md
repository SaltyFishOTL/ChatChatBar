---
name: chatbar-feature-map
description: Locate ChatBar feature entry points before broad repository search. Use at the start of ChatBar tasks when the requested area is unclear, when reducing redundant search work, or when mapping a user-visible feature to likely screens, ViewModels, domain services, repositories, tests, and existing project skills.
---

# ChatBar Feature Map

Use this as a first-hop map. Read a specific skill or listed files, then search only if the map misses.

## Workflow

1. Match request to one or two rows.
2. Read the listed skill first when present.
3. Read listed files before broad search.
4. Use focused search only after first-read files do not answer location.
5. Replace stale rows when ownership moves; do not grow this file into an architecture guide.

## Feature Rows

- App wiring and navigation: app/app/src/main/java/com/example/chatbar/ChatBarApp.kt, Navigation.kt, NavigationKeys.kt.
- Character card edit UI: ui/character/CharacterEditScreen.kt, CharacterEditViewModel.kt. Renaming a card name also rewrites bound session titles via `ChatRepository.rewriteSessionTitlesForCharacterCard`.
- Character default format binding: `CharacterCard.defaultFormatCardId`, character editor draft/save, and `domain/chat/CharacterSessionService.kt` (initializes new sessions only). `CharacterCardPackage` v9 optionally embeds one `FormatCardPackage`; `CharacterCardTransferService` covers JSON/PNG/copy/overwrite, and `FormatCardTransferService.importCharacterDefault` reuses matching name/content/ordered tools or imports a non-default copy. Null binding omits the embedded package; versions 3–8 remain readable. Missing references are shown in the editor and rejected on export; new sessions log the missing reference and use the existing global default path.
- Structured character transfer into another character card or a World Book: domain/card/CharacterSectionImportPolicy.kt plus the character/world-book edit screens and ViewModels.
- Character-card AI auto-fill, rewrite, image-to-appearance fill, diff, apply, cover, or per-character avatar: use chatbar-character-card-ai.
- Chat prompt composition, stable/dynamic/tail cache layers, history roles, previous-turn hot zone, World Book/RAG/Archive/HEAD order, or prompt-delivery diagnosis: use chatbar-prompt-pipeline.
- Format-card editing, ordered user tools, request-only user-message suffixes, drafts, or transfer: data/local/entity/FormatCard.kt, domain/card/FormatCardUserToolPolicy.kt, ui/format/FormatCardEditScreen.kt, and FormatCardEditViewModel.kt; use chatbar-prompt-pipeline for final API placement.
- Character-specific format-card AI creation, creative guidance/example, candidate validation, and blank-new-card apply: use chatbar-format-card-ai.
- Any model-facing prompt text, template, or builder change in PromptTemplates.kt: use chatbar-prompt-pipeline and maintain its file-header AI prompt directory.
- Chat screen behavior outside a specific skill: ui/chat/ChatScreen.kt, ChatViewModel.kt, and DebugLogDialog.kt. Archived chats expose an explicit character-card picker; `ChatRepository.relinkArchivedSession` validates missing old/present new cards and changes only the current session's card binding under the settings mutex, preserving history, memory, and settings. Historical timeline-order repair also uses domain/chat/ChatMessageOrderRepairPolicy.kt and data/repository/ChatRepository.kt; use chatbar-image-generation-runtime and chatbar-long-term-memory for anchor/source-turn effects.
- Home session list, long-press actions, pin/delete, or display-title override: ui/home/HomeScreen.kt, HomeViewModel.kt, domain/chat/SessionDisplayTitlePolicy.kt, data/local/entity/ChatSession.kt, and data/repository/ChatRepository.kt. Session duplication: domain/chat/SessionCopyService.kt and SessionCopyPolicy.kt; uses SaveSlot streaming media transfer, current memory snapshot, fresh message IDs, and copied voice anchors.
- Long-term memory, HEAD, Episode/Arc/Era, source-turn/T mapping, Gap/backfill, historical source repair, compression, tier history, Archive injection, or SaveSlot memory migration: use chatbar-long-term-memory.
- Message AI format repair, automatic checks, restore-original notices, repair model selection, or repair state: use chatbar-message-format-repair.
- Shared AI background protection, foreground-service lifecycle, network guard, notification stop action, wake/Wi-Fi locks, or `ForegroundServiceDidNotStartInTimeException`: use chatbar-background-work-runtime.
- Model fallback, provider request fields, HTTP/local auth, thinking controls, connection tests, SSE timeout/reset, or auxiliary text requests: use chatbar-model-request-runtime.
- Generated-image runtime, NovelAI HTTP generation, 429 retry, editable regeneration, metadata, dimensions, seeds, concurrency, or file replacement: use chatbar-image-generation-runtime.
- Assistant segmented bubble rendering: ui/components/ChatBubble.kt; marker parsing in domain/chat/RoleplayContentSegments.kt; per-turn protocol in PromptTemplates.kt and ChatViewModel.kt; edit/delete in ChatViewModel.kt; selection/screenshot in ChatScreenshotSelection.kt and ChatLongScreenshot.kt.
- Fish Audio API, credentials, voice library/bindings, AI tags and confirmation, generation batches, anchors, persistence, playback, SaveSlot transfer, or voice UI: use chatbar-fish-audio-voice.
- Character speaker names/history: domain/card/CharacterSpeakerNamePolicy.kt, CharacterSpeakerMigration.kt, domain/chat/SpeakerTagHistoryService.kt, and ChatRepository.rewriteSpeakerTagsForCharacterCard.
- Settings navigation/search: ui/kit/SettingsBrowser.kt; global nine-category content in ui/manage/GlobalSettingsScreen.kt, session six-category content in ui/chat/SessionSettingsContent.kt. ChatSettingsDialog.kt retains tool tabs and session drafts; ManageScreen.kt owns the global save entry. SettingsRepository.kt and ChatRepository.kt merge edited settings fields through SettingsDraftMerge.kt. Navigation.kt carries the global unsaved-settings guard.
- Whole-app .cbbackup migration, passwords, credentials, offline transfer, or boot rollback: use chatbar-app-backup.
- Chat save slots/archive transfer: data/local/entity/SaveSlot.kt, data/repository/SaveSlotRepository.kt, domain/chat/SaveSlotJsonTransfer.kt, ChatViewModel.kt, ChatSettingsDialog.kt.
- RAG/search/indexing and vector persistence: domain/rag/RagManager.kt, domain/rag/ChatMemoryIndexPolicy.kt, domain/rag/RagRepository.kt, data/local/entity/VectorChunk.kt, data/local/JsonFileStorage.kt, domain/search/CharacterResearchService.kt. RAG stays independent from long-term memory; source-turn grouping changes require chatbar-long-term-memory.
- NovelAI prompt/tag design: use chatbar-novelai-prompt, then read domain/image/NovelAiPromptDesigner.kt.
- Home image prompt tool, reference-image reverse prompting, or manual NovelAI prompt editing: use chatbar-novelai-prompt and chatbar-image-generation-runtime, then read ui/imageprompt/ImagePromptToolScreen.kt and ImagePromptToolViewModel.kt. Independent studio AI-design conversations and their natural-language mode use NovelAiDesignScreen.kt, NovelAiDesignViewModel.kt, domain/image/NovelAiDesignConversationModels.kt, and data/repository/NovelAiDesignConversationRepository.kt.
- APNG disguise/restore, static/GIF/APNG inspection, mosaic-editor image processing, or direct share/save: use chatbar-image-generation-runtime, then read ui/components/ImageMosaicEditor.kt, domain/image/ImageProcessingService.kt, domain/image/ApngDisguiseCodec.kt, and ui/components/ImageActions.kt.
- Moments: use chatbar-moments before Moments UI, ViewModel, scheduler, prompts, storage, or post image policy.
- Community: use chatbar-community-platform before community UI or Supabase/Edge Function code.
- UI kit and Compose styling: use chatbar-shadcn-compose, then read ui/kit/ and target screen.
- Emulator/device/build verification: use chatbar-emulator-test.
- Crash diagnostics and report sharing: domain/diagnostics/CrashDiagnosticReport.kt, utils/diagnostics/CrashReportManager.kt and SystemExitInfoReader.kt, ui/components/CrashReportDialog.kt, ChatBarApp.kt, MainActivity.kt, Navigation.kt, ui/manage/GlobalSettingsScreen.kt.
- App update checks, release APK download, system installer handoff, or release publishing: use chatbar-app-update.
- Import/export/card packages: domain/card/CardTransferModels.kt, CharacterCardTransferService.kt, CharacterPlaceholderPolicy.kt, CharacterCardPngRenderer.kt, SillyTavernCardParser.kt, SillyTavernCardMapper.kt; SAF export contract in ui/components/CreateOpenableDocument.kt; export UI in ManageScreen.kt. Use chatbar-shared-import for external ACTION_SEND/ACTION_VIEW/text routing, content detection, FIFO staging, conflicts, unknown-type recovery, and shared-image handoff.
- Persistence/entities: data/local/entity/, data/local/JsonFileStorage.kt, related repository under data/repository/. Singleton reads return null only for missing files; corrupt/unreadable files throw `SingletonReadException` and block overwrites. `singletonReadFailures` feeds `MainActivity`/`StorageReadFailureScreen`; retry rereads failed files and resumes `ChatBarApp.initializePersistentState`. SettingsRepository serializes initialization/read-create/write. `saveAll` is per-file, with completed writes reflected in cache even on failure; MemoryRepository owns journal recovery. Regression entry: `JsonFileStorageSafetyTest`.
- World books: data/local/entity/WorldBook*.kt, ui/worldbook/WorldBookEditScreen.kt, WorldBookEditViewModel.kt. For AI creation, blank-content fill, research, candidates, apply, or resume, use chatbar-worldbook-ai.
- Tutorial/help: ui/tutorial/TutorialScreen.kt.

All abbreviated source paths are relative to app/app/src/main/java/com/example/chatbar/.

## Stop Conditions

- If a request matches a specific project skill, stop expanding this map and read that skill.
- If three focused searches miss, report uncertainty and widen deliberately.
- Keep each row short, ownership-focused, and actionable.
