# Implementation Plan - In-App Developer Debug Console

Introduce a developer debug logging panel in the ChatBar app for Debug builds. This console allows inspection of RAG search results, assembled system prompts, JSON request bodies, raw SSE stream responses, and token counts.

## User Review Required

> [!NOTE]
> We will add a global configuration flag `DebugConfig.SHOW_DEBUG_UI` in the code. Setting it to `false` will completely hide the debug UI trigger icon in the TopAppBar. We will also document all debug logging locations in `doc/DebugLoggingDesign.md` so they can be easily cleaned up or stripped during release preparation.

## Proposed Changes

### 2026-05-31 Scroll Debug HUD

#### [MODIFY] [DebugConfig.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/DebugConfig.kt)
- Add `SHOW_SCROLL_DEBUG_HUD`.
- Set to `true` only while investigating chat scroll behavior.
- Set to `false` before production/release builds.

#### [MODIFY] [ChatScreen.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/ChatScreen.kt)
- Add visible in-chat overlay labeled `SCROLL DEBUG v2 2026-05-31 19:04`.
- Overlay appears at top-left of message list when `DebugConfig.SHOW_SCROLL_DEBUG_HUD == true`.
- Fields shown:
  - `items`: LazyColumn total item count.
  - `visible`: visible item count.
  - `first` / `last`: visible item index, offset, and size.
  - `viewport`: viewport start/end offsets.
  - `isAtBottom`: current bottom detection result.
  - `buttonVisible`: whether jump-to-bottom FAB should be visible.
  - `isResponding`: whether AI is streaming/responding.
  - `complete`: whether latest output is complete.
  - `streamChars`: current streaming content + reasoning length.
  - `anchor`: viewport anchor used to prevent implicit follow-scroll.
  - `restoring`: whether the viewport anchor restore operation is in progress.
- Diagnostic rule:
  - If user does not see this HUD in debug build, installed APK is not the latest generated APK or the running screen is not this `ChatScreen`.
  - If HUD shows `buttonVisible=true` but FAB is missing, UI layering/layout issue.
  - If HUD shows `isAtBottom=true` while visually not at bottom, bottom detection is wrong.
  - If `anchor` changes during streaming while user is not scrolling, viewport anchoring is wrong.

### Core Debug Utilities

#### [NEW] [DebugLogManager.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt)
Create a thread-safe singleton to manage active logging builders and completed log entries per session.
- `DebugLogEntry`: data class holding request timestamp, system prompt, RAG results, API URL, request JSON, raw SSE output, and token estimates.
- `DebugLogManager`: manages `StateFlow<List<DebugLogEntry>>` and provides methods like `startRequest()`, `appendResponseChunk()`, `completeRequest()`, and `clear()`.

#### [NEW] [DebugConfig.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/DebugConfig.kt)
Define global debug configuration parameters:
- `const val SHOW_DEBUG_UI = true`

---

### Service & ViewModel Logging Integration

#### [MODIFY] [StreamingChatService.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/domain/chat/StreamingChatService.kt)
- Update `streamChat` signature to accept `sessionId: String`.
- Inside `streamChat`, log the target URL and JSON request body to `DebugLogManager`.
- Inside the SSE event listener, append each raw `data: ...` payload chunk to the log.
- Log error messages or `[DONE]` state.

#### [MODIFY] [ChatViewModel.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt)
- In `sendMessage()`, initialize `DebugLogManager` with `systemPrompt` and formatted `ragResults` prior to invoking `streamChat`.
- Pass `sessionId` to `streamingChatService.streamChat()`.
- Estimate prompt token usage (based on Chinese characters/English word ratios) and record it when completing the request.

---

### UI Integration

#### [NEW] [DebugLogDialog.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/DebugLogDialog.kt)
Create a full-screen scrollable dialog displaying the debug logs:
- List logs for the current session in reverse chronological order.
- Each log entry is styled as a premium Dark Theme Card.
- Expandable/collapsible sections for RAG Chunks, System Prompt, API Request Body, and Raw SSE output.
- Features a "Copy to Clipboard" button for easy sharing of request/response data.

#### [MODIFY] [ChatScreen.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/ChatScreen.kt)
- Add a bug/debug icon to the TopAppBar actions if `DebugConfig.SHOW_DEBUG_UI` is enabled.
- Toggle `DebugLogDialog` visibility when clicked.

---

### Documentation

#### [NEW] [DebugLoggingDesign.md](file:///D:/Projects/ChatBar/doc/DebugLoggingDesign.md)
Document the debug console architecture and list all lines/files modified to implement this logging. Provide a clear, step-by-step instructions on how to disable the UI, clear the code, or strip imports when deploying a production release.

## Verification Plan

### Automated Tests
- Build and compile using `./gradlew compileDebugKotlin` to verify integration.
- Assemble APK with `./gradlew assembleDebug`.

### Manual Verification
- Launch app, start a chat session, click the Bug icon in the top bar to verify logs open.
- Send a message, open debug panel, and verify RAG chunks, system prompt, JSON body, and raw streaming response update in real-time.
