---
name: chatbar-feature-map
description: Locate ChatBar feature entry points before broad repository search. Use at the start of ChatBar tasks when the requested area is unclear, when reducing redundant rg/search work, or when mapping a user-visible feature to likely screens, ViewModels, domain services, repositories, tests, and existing project skills.
---

# ChatBar Feature Map

Use this as a first-hop map. Read specific files or skills, then search only if the map misses.

## Workflow

1. Match request to one or two rows below.
2. Read listed skill first when present, otherwise read listed files.
3. Use focused `rg -n "term" path` only after first-read files do not answer location.
4. On Windows, avoid regex alternation and shell pipes in search commands.
5. Update this map when a feature moves or a repeated search pattern appears.

## Feature Rows

- App wiring: `app/app/src/main/java/com/example/chatbar/ChatBarApp.kt`, `Navigation.kt`, `NavigationKeys.kt`.
- Character card edit UI: `ui/character/CharacterEditScreen.kt`, `ui/character/CharacterEditViewModel.kt`.
- Character-card AI auto-fill/rewrite/diff/cover: use `chatbar-character-card-ai`.
- Chat screen behavior: `ui/chat/ChatScreen.kt`, `ui/chat/ChatViewModel.kt`, `domain/chat/PromptAssembler.kt`.
- Assistant segmented bubble rendering: `ui/components/ChatBubble.kt`, speaker-marker parser/block IDs in `domain/chat/RoleplayContentSegments.kt`, per-turn marker prompt in `domain/prompt/PromptTemplates.kt` and `ui/chat/ChatViewModel.kt`, segment edit/delete in `ui/chat/ChatViewModel.kt`, segment selection/long screenshot in `ui/chat/ChatScreenshotSelection.kt` and `ui/chat/ChatLongScreenshot.kt`. Global default-on toggle is `AppSettings.assistantSegmentedBubblesEnabled`, edited in `ManageScreen` appearance settings and observed by `ChatViewModel`. Dialogue/thought avatars resolve `<n="人物名"/>` against `CharacterCard.characters[*].appearanceImage`; card-level `avatar` is cover/header plus legacy unmarked chat fallback. Invariants: missing marker shows card avatar and no speaker name; malformed/empty marker shows `?`/`未标注`; chat speaker avatar is 40dp circular crop with no border.
- Character speaker names/history: uniqueness and legacy normalization live in `domain/card/CharacterSpeakerNamePolicy.kt` and `CharacterSpeakerMigration.kt`; durable rename retries live on `CharacterCard.pendingSpeakerRenameTasks`, coordinated by `domain/chat/SpeakerTagHistoryService.kt` and `ChatRepository.rewriteSpeakerTagsForCharacterCard`.
- Chat settings/model selection: `ui/chat/ChatSettingsDialog.kt`, `ui/manage/ManageScreen.kt`, app settings repository/classes.
- RAG/search/indexing: `domain/rag/RagManager.kt`, `data/repository/RagRepository.kt`, `domain/search/CharacterResearchService.kt`.
- NovelAI prompt/image: use `chatbar-novelai-prompt`; then read `domain/image/NovelAiPromptDesigner.kt`, `domain/image/NovelAiImageService.kt`. Per-character avatar generation also uses `chatbar-character-card-ai`; do not bypass `NovelAiPromptDesigner`.
- Moments: use `chatbar-moments` before reading Moments UI, ViewModel, scheduler, prompts, or storage.
- Community: use `chatbar-community-platform` before reading `ui/community/CommunityScreen.kt` or Supabase/Edge Function code.
- UI kit and Compose styling: use `chatbar-shadcn-compose`; then read `ui/kit/*` and target screen.
- Emulator/device verification: use `chatbar-emulator-test`.
- Import/export/card packages: `domain/card/CardTransferModels.kt`, `CharacterCardTransferService.kt`, `SillyTavernCardParser.kt`, `SillyTavernCardMapper.kt`.
- Persistence/entities: `data/local/entity/*`, `data/local/JsonFileStorage.kt`, related repository under `data/repository/*`.
- World books: `data/local/entity/WorldBook*.kt`, `ui/character/CharacterEditScreen.kt`, `ui/character/CharacterEditViewModel.kt`.
- Tutorial/help: `ui/tutorial/TutorialScreen.kt`.

## Stop Conditions

- If a request matches a specific project skill, read that skill instead of continuing map expansion.
- If three focused searches miss, report uncertainty and widen search deliberately.
- Do not create a large architecture summary here; keep rows short and actionable.
