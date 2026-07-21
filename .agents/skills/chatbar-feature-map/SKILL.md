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
- Character card edit UI: ui/character/CharacterEditScreen.kt, CharacterEditViewModel.kt.
- Character-card AI auto-fill, rewrite, diff, apply, cover, or per-character avatar: use chatbar-character-card-ai.
- Chat prompt composition, stable/dynamic/tail cache layers, history roles, previous-turn hot zone, World Book/RAG/Archive/HEAD order, or prompt-delivery diagnosis: use chatbar-prompt-pipeline.
- Chat screen behavior outside a specific skill: ui/chat/ChatScreen.kt and ChatViewModel.kt.
- Long-term memory, HEAD, Episode/Arc/Era, source-turn/T mapping, Gap/backfill, historical source repair, compression, tier history, Archive injection, or SaveSlot memory migration: use chatbar-long-term-memory.
- Message AI format repair, automatic checks, restore-original notices, repair model selection, or repair state: use chatbar-message-format-repair.
- Shared AI background protection, foreground-service lifecycle, network guard, notification stop action, wake/Wi-Fi locks, or `ForegroundServiceDidNotStartInTimeException`: use chatbar-background-work-runtime.
- Model fallback, provider request fields, HTTP/local auth, thinking controls, connection tests, SSE timeout/reset, or auxiliary text requests: use chatbar-model-request-runtime.
- Generated-image runtime, NovelAI HTTP generation, 429 retry, editable regeneration, metadata, dimensions, seeds, concurrency, or file replacement: use chatbar-image-generation-runtime.
- Assistant segmented bubble rendering: ui/components/ChatBubble.kt; marker parsing in domain/chat/RoleplayContentSegments.kt; per-turn protocol in PromptTemplates.kt and ChatViewModel.kt; edit/delete in ChatViewModel.kt; selection/screenshot in ChatScreenshotSelection.kt and ChatLongScreenshot.kt.
- Character speaker names/history: domain/card/CharacterSpeakerNamePolicy.kt, CharacterSpeakerMigration.kt, domain/chat/SpeakerTagHistoryService.kt, and ChatRepository.rewriteSpeakerTagsForCharacterCard.
- Chat settings and model selection UI: ui/chat/ChatSettingsDialog.kt, ui/manage/ManageScreen.kt, AppSettings.kt, and SettingsRepository.kt.
- Chat save slots/archive transfer: data/local/entity/SaveSlot.kt, data/repository/SaveSlotRepository.kt, domain/chat/SaveSlotJsonTransfer.kt, ChatViewModel.kt, ChatSettingsDialog.kt.
- RAG/search/indexing and vector persistence: domain/rag/RagManager.kt, domain/rag/ChatMemoryIndexPolicy.kt, domain/rag/RagRepository.kt, data/local/entity/VectorChunk.kt, data/local/JsonFileStorage.kt, domain/search/CharacterResearchService.kt. RAG stays independent from long-term memory; source-turn grouping changes require chatbar-long-term-memory.
- NovelAI prompt/tag design: use chatbar-novelai-prompt, then read domain/image/NovelAiPromptDesigner.kt.
- Home image prompt tool, reference-image reverse prompting, or manual NovelAI prompt editing: use chatbar-novelai-prompt and chatbar-image-generation-runtime, then read ui/imageprompt/ImagePromptToolScreen.kt and ImagePromptToolViewModel.kt.
- Moments: use chatbar-moments before Moments UI, ViewModel, scheduler, prompts, storage, or post image policy.
- Community: use chatbar-community-platform before community UI or Supabase/Edge Function code.
- UI kit and Compose styling: use chatbar-shadcn-compose, then read ui/kit/ and target screen.
- Emulator/device/build verification: use chatbar-emulator-test.
- Crash diagnostics and report sharing: domain/diagnostics/CrashDiagnosticReport.kt, utils/diagnostics/CrashReportManager.kt and SystemExitInfoReader.kt, ui/components/CrashReportDialog.kt, ChatBarApp.kt, MainActivity.kt, Navigation.kt, ManageScreen.kt.
- App update checks, release APK download, system installer handoff, or release publishing: use chatbar-app-update.
- Import/export/card packages: domain/card/CardTransferModels.kt, CharacterCardTransferService.kt, CharacterCardPngRenderer.kt, SillyTavernCardParser.kt, SillyTavernCardMapper.kt; PNG export UI in ManageScreen.kt.
- Persistence/entities: data/local/entity/, data/local/JsonFileStorage.kt, related repository under data/repository/.
- World books: data/local/entity/WorldBook*.kt, CharacterEditScreen.kt, CharacterEditViewModel.kt.
- Tutorial/help: ui/tutorial/TutorialScreen.kt.

All abbreviated source paths are relative to app/app/src/main/java/com/example/chatbar/.

## Stop Conditions

- If a request matches a specific project skill, stop expanding this map and read that skill.
- If three focused searches miss, report uncertainty and widen deliberately.
- Keep each row short, ownership-focused, and actionable.
