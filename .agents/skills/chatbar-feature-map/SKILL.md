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
- Chat settings/model selection: `ui/chat/ChatSettingsDialog.kt`, `ui/manage/ManageScreen.kt`, app settings repository/classes.
- RAG/search/indexing: `domain/rag/RagManager.kt`, `data/repository/RagRepository.kt`, `domain/search/CharacterResearchService.kt`.
- NovelAI prompt/image: use `chatbar-novelai-prompt`; then read `domain/image/NovelAiPromptDesigner.kt`, `domain/image/NovelAiImageService.kt`.
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
