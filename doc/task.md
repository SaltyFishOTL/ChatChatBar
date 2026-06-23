# ChatBar Phase 1 (Core App) - Complete
- [x] Environment Setup (JDK 17 with jlink)
- [x] Foundation & Navigation
- [x] Data Layer & Repositories
- [x] RAG Engine & Embedding
- [x] Chat Service & Prompt Assembly
- [x] UI Components & Screens
- [x] Initial debug APK compilation

# ChatBar Phase 2 (Developer Debug Log Console) - Complete
- [x] Create `DebugConfig.kt`
- [x] Create `DebugLogManager.kt` utility
- [x] Modify `StreamingChatService.kt` to stream raw request/SSE payloads
- [x] Modify `ChatViewModel.kt` to feed the logs (RAG, prompt, estimation)
- [x] Create `DebugLogDialog.kt` UI screen
- [x] Modify `ChatScreen.kt` to support the debug action trigger
- [x] Write `doc/DebugLoggingDesign.md` documentation
- [x] Compile and verify Kotlin compilation
- [x] Package final APK (`assembleDebug`)

# ChatBar Phase 2 (Batch Document Upload) - Complete
- [x] Add `importDocumentsFromFolder` in `CharacterEditViewModel.kt` using SAF Uri parsing
- [x] Add "批量上传" button and `OpenDocumentTree` picker launcher in `CharacterEditScreen.kt`
- [x] Test batch upload directory parsing and local storage file creation
- [x] Verify background RAG indexing of imported files
- [x] Compile and package final APK

# ChatBar Phase 2 (Multimodal Picker Support) - Complete
- [x] Add `copyUriToLocalFile` in `CharacterEditViewModel.kt` to copy picked image Uris to private images directory
- [x] Replace avatar manual path with visual picker launcher in `CharacterEditScreen.kt`
- [x] Add sub-character image upload field with thumbnail preview inside character edit dialog
- [x] Add sub-character image thumbnail in character card list items
- [x] Test picking image for avatar and subcharacter, checking persistence and display

# ChatBar Phase 2 (Bug Fixes) - Complete
- [x] Explicitly specify keys for `viewModel()` in `ModelEditScreen.kt`, `CharacterEditScreen.kt`, `FormatCardEditScreen.kt`, and `ChatScreen.kt` to isolate caches
- [x] Modify `buildRequestBody` in `StreamingChatService.kt` to serialize Double with zero fractional parts as Long to avoid API 400 parameter validation errors
- [x] Compile and verify Kotlin compilation
- [x] Test LLM edit loading correctness and successful chat stream response
- [x] Package final APK (`assembleDebug`)

# ChatBar Phase 2.5 (Refinements & Bug Fixes) - Complete
- [x] Implement `TextFieldValue` in `ChatScreen.kt` for input box IME punctuation fix
- [x] Implement free scrolling during streaming in `ChatScreen.kt`
- [x] Add `reasoningContent` to `ChatMessage.kt` & parse it in `StreamingChatService.kt`
- [x] Update `ChatViewModel.kt` to capture reasoning streams and save them
- [x] Add expandable brain emoji reasoning block in `ChatBubble.kt`
- [x] Add RAG search detailed pipeline logging in `ChatViewModel.kt`
- [x] Add rebuild RAG indexes feature in `ChatViewModel.kt` and `DebugLogDialog.kt`
- [x] Add raw text and reasoning view in `DebugLogDialog.kt`
- [x] Support reactive session observe and fallback settings in `ChatViewModel.kt`
- [x] Compile and verify execution

# ChatBar Phase 2.6 (Dialogue Optimizations & Refinements) - In Progress
- [x] Implement free scrolling during streaming in `ChatScreen.kt` using `isScrollInProgress` and exact bottom checks
- [x] Fix RAG 0 chunks issue:
  - [x] Update `saveCharacterCard` to index `customDocuments` as well as characters
  - [x] Implement `deleteChunksByDocumentId` in `RagRepository.kt` to query `originalDocId` and use it
  - [x] Expose `indexingStatus` flow in `CharacterEditViewModel.kt` to bubble up indexing errors/progress to UI
- [x] Restructure system prompt in `PromptAssembler.kt` with `SYSTEM_INSTRUCTION` at bottom and format/RAG at top
- [x] Replace text path attachment with visual system photo picker in chat interface `ChatScreen.kt` using `GetContent()`
- [x] Add player name field in `PlayerSetting.kt`, Settings UI, and auto-replace `$username` variable in `PromptAssembler.kt`
- [x] Add confirmation `AlertDialog` for all deletes (character, format, model, embedding, subcharacter, document, save slot)
- [x] Add edit/modify support for format cards in the management tab
