# ChatBar 开发者调试日志系统设计文档

为方便开发调试与测试，ChatBar 引入了应用内调试日志系统。该系统允许在 Debug 版本中，直接在聊天界面查看每轮 AI 对话最终组装的 System Prompt、发送给接口的完整 JSON 结构体、RAG 召回设定以及模型的原生 SSE 流式输出等。

---

## 1. 系统架构与关键组件

调试日志系统遵循极其轻量、非侵入、易清理的设计原则：

1. **`DebugConfig.kt` (新增)**:
   - 位置: [DebugConfig.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/DebugConfig.kt)
   - 作用: 提供全局的调试开关 `SHOW_DEBUG_UI`。当设为 `false` 时，整个调试入口会在 UI 上完全隐藏，作为正式版发布的第一道物理屏蔽。

2. **`DebugLogManager.kt` (新增)**:
   - 位置: [DebugLogManager.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt)
   - 作用: 内存日志管理器。使用 `StateFlow` 实现响应式日志分发，通过 `ConcurrentHashMap` 支持会话隔离和实时的流式追加。

3. **`DebugLogDialog.kt` (新增)**:
   - 位置: [DebugLogDialog.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/DebugLogDialog.kt)
   - 作用: 调试控制台的 UI 面板。包含 RAG 召回块、System Prompt、JSON 请求体、SSE 流的原生文本。每个版块皆可一键复制，方便提取排查。

---

## 2. 修改点与日志插入位置 (代码锚点)

为方便正式发布前“一键剥离”，以下是代码中所有被改动并插入日志 hooks 的锚点：

### 1. 聊天接口层 `StreamingChatService.kt`
- **文件**: [StreamingChatService.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/domain/chat/StreamingChatService.kt)
- **改动**: 
  - `streamChat` 签名调整，接受 `sessionId: String`、`systemPrompt: String` 和 `ragChunks: List<String>`。
  - 在生成 `requestBody` 后，调用 `DebugLogManager.startRequest` 初始化日志。
  - 在 SSE 事件 `onEvent` 收到增量时，调用 `DebugLogManager.appendResponseChunk` 记录原生流。
  - 在 `onFailure` 时记录错误信息，在 `onClosed` / `[DONE]` 时调用 `DebugLogManager.completeRequest` 关闭通道。

### 2. 对话逻辑层 `ChatViewModel.kt`
- **文件**: [ChatViewModel.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/ChatViewModel.kt)
- **改动**:
  - 在 `sendMessage` 方法中，在调用 `streamingChatService.streamChat` 时，计算 `ragChunkStrings`（召回块文本）并传入。

### 3. 用户界面层 `ChatScreen.kt`
- **文件**: [ChatScreen.kt](file:///D:/Projects/ChatBar/app/app/src/main/java/com/example/chatbar/ui/chat/ChatScreen.kt)
- **改动**:
  - `Scaffold` 的 `TopAppBar` `actions` 列表中，当 `DebugConfig.SHOW_DEBUG_UI` 为真时，追加 `Icons.Default.BugReport` 按钮。
  - 聊天主界面声明并渲染 `DebugLogDialog`。

---

## 3. 正式版物理移除与清理指南

当需要交付正式发布版（Release APK）时，可以通过以下步骤，安全地对调试日志进行物理屏蔽和源码级移除：

### 步骤一：全局关闭 UI 入口（最快屏蔽方式）
将 `DebugConfig.kt` 中修改为：
```kotlin
object DebugConfig {
    const val SHOW_DEBUG_UI = false // 设为 false，直接隐藏调试图标与入口
}
```

### 步骤二：删除日志源码文件
直接删除以下 3 个新增文件：
- `app/src/main/java/com/example/chatbar/DebugConfig.kt`
- `app/src/main/java/com/example/chatbar/utils/DebugLogManager.kt`
- `app/src/main/java/com/example/chatbar/ui/chat/DebugLogDialog.kt`

### 步骤三：还原三处代码 Hook
1. **还原 `ChatScreen.kt`**:
   - 还原 `showDebugPanel` 局部状态声明。
   - 删除 `TopAppBar` 的 `actions` 里的 `BugReport` `IconButton`。
   - 删除底部的 `DebugLogDialog` 弹窗渲染代码。

2. **还原 `ChatViewModel.kt`**:
   - 还原调用 `streamingChatService.streamChat` 的传参，不再传入 `systemPrompt` 和 `ragChunks` 等参数。

3. **还原 `StreamingChatService.kt`**:
   - 恢复 `streamChat` 签名为不含 `systemPrompt`, `ragChunks`, `sessionId` 的原生格式。
   - 删掉内部所有的 `com.example.chatbar.utils.DebugLogManager` 方法调用。
