# ChatBar 项目交接文档

> 审计日期：2026-06-18
> 当前状态：视觉系统、角色卡与传输 schema、模型分层配置、可恢复删除、空来源 RAG 短路、NovelAI 聊天生图（含一键 Prompt 设计、连接重试、负向词去重、全屏预览长按保存）、输入框字数显示、聊天气泡字号自动保存均已完成；最新 Debug APK 已安装并验证
> 基线提交：`acd0b5a`（2026-06-08，优化提示词）

---

## 1. 项目目标概述

ChatBar 是 Android AI 角色扮演 App。用户配置 OpenAI 兼容模型、创建角色卡并进行流式对话。核心能力包括 RAG 长期记忆、多模态图片、格式卡、存档、消息编辑/重生成、多回复和 Debug 控制台。

---

## 2. 项目结构与整体状况

- 单模块 Android App：Kotlin、Jetpack Compose、Navigation3。
- 持久化：`JsonFileStorage`，路径 `filesDir/entities/<type>/<id>.json`；无实际数据库。
- 全局依赖：`ChatBarApp.instance`。
- UI：Compose Foundation 自建 `ui/kit`；不依赖 Material 3。
- Gradle 根：`app/`；JDK 17；minSdk 26；targetSdk 36。
- 关键文档：`doc/implementation_plan.md`、`doc/ui_design_system.md`。

---

## 3. 已完成的工作

### 3.1 核心产品能力

- 角色卡、模型、格式卡 CRUD 与导入导出；角色卡使用不含本地路径和派生状态的可移植 schema v3。
- OkHttp SSE 流式聊天、Markdown、思维折叠、多回复切换。
- 图片直发或视觉辅助模型转文字描述。
- RAG：分块、embedding、向量/词面多路召回、RRF、重排、卡片注入。
- Retrieval Planner：抽取 topic、queries、entities；失败回退。
- 会话设置、存档读档、消息编辑/删除/重生成、Debug 面板。
- 角色卡/会话删除先移除主记录，再由持久化任务后台批量清理资源、消息、存档和 RAG；进程中断后下次启动自动续删。
- RAG 按实际来源短路：无已索引文档且无消息滑出上下文窗口时完全跳过 Planner、查询 Embedding 和向量检索；仅有长期记忆时只检索记忆。
- 全局设置的“保留上下文消息”Slider 实时显示具体条数。
- NovelAI 聊天生图：助手文本消息可作为锚点生成图片；上下文以该消息为终点，读取最近 3 条有效文本消息。
- 设计 AI 一步到位生成最终 Prompt：角色卡默认风格和人物预设提示词直接通过 system prompt 传给 AI，AI 返回完整 `baseCaption` + `characterCaptions`，客户端不做任何手动拼接或名字筛选。
- 设计 AI 开启 `enable_thinking=true` 但限制 `max_thinking_tokens=128`；草稿在状态卡实时显示，JSON 解析失败时执行一次流式修复。
- JSON 解析兼容新旧字段名（`baseCaption`/`scenePrompt`、`caption`/`adjustment`），旧字段自动 fallback。
- 设计 AI 交互完整记录进 Debug 日志：包含发送的 system/user 消息体和 AI 返回的原始 Prompt JSON。
- NovelAI Persistent API Token 使用 Android Keystore AES/GCM 加密，不写入 `AppSettings` 明文 JSON。
- NovelAI V4.5 Full 使用 MessagePack 长度前缀流，支持中间预览、进度和最终图片持久化。
- 负向提示词逗号分割后去重，避免重复标签发送。
- 连接错误自动重试（最多 3 次），每次换新 seed；网络恢复后不再报错。
- 生成图片支持原比例气泡显示、点击全屏、双指 `1x–5x` 缩放、拖动及长按删除。
- 全屏预览长按图片弹出保存确认，通过 MediaStore 保存到 `Pictures/ChatBar/`。
- 生成图片保存到 `files/images/generated/<sessionId>/`；单图、消息、清空记录及会话删除均执行受控目录清理。
- 聊天气泡字号设置：外观栏滑动条拖动后立即自动保存；`ChatViewModel` 监听 `appSettings` Flow，气泡实时更新渲染字号。

### 3.2 shadcn 风格 UI 重构

- 调研 shadcn 官方设计原则、组件源码组织和 variant 模型。
- 新增项目 skill：`.agents/skills/chatbar-shadcn-compose/`。
- 新增 Foundation-only UI Kit：`ui/kit/ChatBarTheme.kt`、`Primitives.kt`、`Fields.kt`、`Controls.kt`、`Layouts.kt`、`Feedback.kt`。
- 完成 MainActivity、Navigation、首页、聊天、管理、角色编辑、模型编辑、格式卡编辑、聊天设置、Debug、消息气泡、底栏和空状态迁移。
- 删除 `AppDesign.kt` 与整个旧 `theme` token/主题文件。
- 删除 `androidx.compose.material3` Gradle 依赖及 version catalog alias；源码扫描无 `material3` / `MaterialTheme` / 旧 theme 引用。
- Button、IconButton、FAB、Switch、Tabs、ChoiceChip、Slider 已补 Android semantics；Slider 在 UI 树中识别为 `android.widget.SeekBar`，支持 `SetProgress`。
- 修复 `StreamingChatService.describeImage()` 不可达旧代码。

### 3.3 验证

- `clean test :app:compileDebugAndroidTestKotlin assembleDebug`：通过。
- 2026-06-15 `ci.ps1`（`test + compileDebugAndroidTestKotlin + assembleDebug`）：通过，包含删除优化与 RAG 来源短路改动。
- `dependencyInsight --dependency androidx.compose.material3 --configuration debugRuntimeClasspath`：无匹配依赖。
- 2026-06-15 最新 Debug APK 已通过 `adb install -r` 覆盖安装到 PJZ110；`am start -W` 冷启动成功，耗时 577 ms。
- 真机验证：首页、管理页、全局设置、RAG Slider、角色编辑、聊天页、会话设置均正常渲染。
- `CbTopBar` 状态栏 inset 正常；测试会话已通过应用删除，设备数据恢复原状。
- `AndroidRuntime` 无崩溃日志；`MainActivity` 为 `topResumedActivity`，应用进程保持运行。
- CbInput 组件增加实时字数显示：在输入框内右下角叠加显示，带半透明背景；支持 String 和 TextFieldValue 重载。
- 全屏编辑器 Column padding 适配，防止底部按钮遮挡字数。
- NovelAI 相关 `test + compileDebugAndroidTestKotlin + assembleDebug` 和完整 `ci.ps1`：通过。
- `ChatBubbleImageTest` 已在 PJZ110 真机单独执行通过，覆盖图片宽高比、点击和长按回调。
- 2026-06-17 `ci.ps1`（test + compileDebugAndroidTestKotlin + assembleDebug）：通过，包含 CbInput 字数显示和全屏编辑器 padding 改动。
- 2026-06-18 `ci.ps1`：通过，包含 NovelAI 生图一键 Prompt 设计、名字免筛选、连接重试、负向词去重、全屏预览保存改动。
- 最新 NovelAI Debug APK 已通过 `adb install -r` 覆盖安装。

---

## 4. 进行中的工作

无代码开发进行中。2026-06-18 已完成 NovelAI 生图流程简化（AI 一步到位设计最终 Prompt）、名字筛选移除、连接重试、负向词去重、全屏预览长按保存；编译/打包/安装验证通过。

---

## 5. 已尝试但失败 / 已尝试但未采用

- 聊天页底部导航：体验差，已回退；底栏只在首页/管理页显示。
- 流式自动跟随：会抢用户滚动，已改为 viewportAnchor + 跳底按钮。
- 独立全屏输入组件：文案漂移，已改为复用消息全屏编辑器。
- Material 3 主题微调：无法形成稳定统一视觉，已整体替换为本地 Foundation UI Kit。
- 视觉稿逐像素截图拟合：不作为主流程；改用成熟组件语义和统一 token 约束。

---

## 6. 已知问题和风险

- `ChatViewModel.kt` 体积大，混合聊天状态、RAG 编排和消息操作。
- Retrieval Planner 未显式限制输出 token。
- 自动化测试少；核心 UI 与 RAG 缺少系统回归集。
- 角色卡传输 schema v3 不兼容旧 v2 文件；当前为早期开发阶段，按产品决定不提供 v2 导入兼容。
- 真机已覆盖 Slider 视觉、触控入口和可访问性语义；尚未完整覆盖真实模型流式滚动、存档读档、导入导出、多回复、图片辅助模型链路。
- API 兼容基于 OpenAI 格式假设；不同提供商对 token 参数可能要求不同。
- 自定义 API key 当前明文保存在应用私有 JSON；默认共享 Key 会进入 APK，可被提取，不应视为秘密。
- NovelAI Token 已单独使用 Android Keystore 加密；该结论不适用于现有自定义聊天模型 API key。
- 预制模型模板仍含 `TODO_*` 型号；填入实际型号并注入共享 Key 前，默认层会明确禁用聊天。
- 可恢复删除和 RAG 短路已通过编译与单元测试，但尚未在真机大量文档、消息和向量数据下量化耗时。
- NovelAI 流协议依据当前官方网页客户端的 MessagePack 行为实现；官方公开文档仍主要描述 SSE，协议变更可能需要同步适配。
- 全量 `connectedDebugAndroidTest` 曾因 UTP 日志句柄卡住；停止 Gradle daemon 后，目标 `ChatBubbleImageTest` 单独真机运行通过。

---

## 7. 关键文件说明

| 文件/目录 | 作用 |
| --- | --- |
| `ChatBarApp.kt` | Repository 与 domain service 初始化 |
| `Navigation.kt` / `NavigationKeys.kt` | Navigation3 路由与底栏 |
| `ui/kit/` | Foundation-only shadcn 风格组件系统 |
| `ChatViewModel.kt` | 核心聊天、消息与 RAG 编排 |
| `StreamingChatService.kt` | OpenAI 兼容聊天/图片请求 |
| `PromptAssembler.kt` / `PromptTemplates.kt` | Prompt 结构与模板 |
| `data/security/NovelAiCredentialStore.kt` | NovelAI Persistent API Token 的 Keystore 加密存储 |
| `domain/image/NovelAiPromptDesigner.kt` | 锚点上下文、流式 Prompt 设计（一步到位，AI 直接返回完整 baseCaption + captions）、JSON 修复、新旧字段 fallback、角色 6 人上限、center 坐标归一化 |
| `domain/image/NovelAiImageService.kt` | NovelAI V4.5 请求体构建（含负向词去重）、MessagePack 流请求与事件解析 |
| `domain/image/NovelAiStreamFrameDecoder.kt` | 4 字节大端长度前缀帧拆包 |
| `domain/image/NovelAiImageStorage.kt` | 生成图片私有存储与受控路径删除 |
| `ui/chat/ChatScreen.kt` | 聊天页、全屏预览（缩放/拖动/长按保存）、生图状态卡、Debug 对话框 |
| `domain/rag/` | 分块、embedding、planner、检索、重排 |
| `domain/rag/RagSourcePlan.kt` | 根据已索引文档和上下文外消息决定本轮 RAG 来源与完全短路 |
| `domain/deletion/DeletionCoordinator.kt` | 持久化删除任务、即时移除主实体、后台清理与启动续跑 |
| `domain/model/` | 预制模型目录、三层有效配置解析、模型配置迁移 |
| `domain/card/CardTransferModels.kt` | 角色卡 schema v3、格式卡传输 DTO 与角色卡导入请求 |
| `domain/card/CharacterCardTransferService.kt` | 角色卡导出、导入、复制、覆盖、资源物化与清理 |
| `assets/presets/models/default-models.json` | 默认模型型号模板 |
| `assets/presets/manifest.json` | 角色、格式、模型预制目录清单 |
| `JsonFileStorage.kt` | JSON 文件持久化 |
| `.agents/skills/chatbar-shadcn-compose/` | UI 实现约束与 shadcn 调研摘要 |
| `doc/ui_design_system.md` | 当前 UI 规范 |

---

## 8. 环境与运行

所有 Gradle 命令从 `D:\Projects\ChatBar\app` 执行：

```powershell
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat test
.\gradlew.bat :app:compileDebugAndroidTestKotlin
.\gradlew.bat assembleDebug
.\ci.ps1
```

APK：`app/app/build/outputs/apk/debug/app-debug.apk`

```powershell
adb install -r app\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.chatbar/.MainActivity
```

---

## 9. API / 配置说明

- 聊天与 Planner：`POST {baseUrl}/chat/completions`。
- Embedding：`POST {baseUrl}/embeddings`。
- 模型配置分三层：`DEFAULT`、`CUSTOM_API`、`FULL_CUSTOM`。
- 默认层使用 APK 预制模型目录和构建时注入的共享硅基流动 Key。
- 自定义 API 层使用同一预制目录，用户 Key 覆盖全部预制模型。
- 完全自定义层显示模型分页，使用用户模型、单例检索模型和单例向量模型。
- 共享 Key 来源：Gradle property 或环境变量 `CHATBAR_SILICONFLOW_API_KEY`。
- 默认模型模板：`app/app/src/main/assets/presets/models/default-models.json`；`TODO_*` 会被视为未配置。
- 会话保存的模型在当前层不可用时，运行时回退全局默认，不改写会话原值。
- Debug 面板可查看 system prompt、RAG 候选、请求 JSON 和 SSE。
- NovelAI Prompt 模板位于 `PromptTemplates.kt`；设计请求使用当前会话有效模型，SSE 流式返回，开启 `enable_thinking=true` 且 `max_thinking_tokens=128`。
- NovelAI 生图端点：`POST https://image.novelai.net/ai/generate-image-stream`。
- NovelAI 鉴权：`Authorization: Bearer <Persistent API Token>`；Token 不写日志，请求 JSON Debug 记录不包含鉴权头。
- NovelAI 主流程使用 `stream=msgpack`，解析 `intermediate`、`final`、`error`；普通 ZIP 端点未接入。

---

## 10. 阻塞项

当前无代码、构建或设备安装阻塞。

---

## 11. 推荐的下一步

### P0

1. 填写预制模型实际型号并注入共享 Key，验证默认层完整对话、Planner、Embedding 和视觉链路。
2. 验证三层切换、会话模型回退/恢复、硅基流动 Key 测试按钮和旧向量模型迁移。

### P1

3. 给 UI Kit 增加 Compose instrumented tests：按钮 variant、输入、Dialog、Tabs、Slider。
4. 给 PromptAssembler、ChunkingEngine、RetrievalPlanner、VectorSearchEngine 增加单元测试。
5. 拆分 ChatViewModel 中 RAG orchestration。
6. 真机量化大量文档角色删除、长会话删除、零来源 RAG 与仅长期记忆 RAG 的耗时。

### P2

7. API key 接入 Android Keystore。
8. 建立 RAG 固定回归集与多提供商 API 兼容测试。
9. 若共享 Key 存在滥用风险，改用内部代理签发短期凭证；APK 内静态 Key 无法真正保密。
10. 增加角色卡 schema v3 的端到端设备测试：文档、头像、聊天背景、人物形象图导出后跨安装导入。
11. 使用有效 Persistent API Token 验证 NovelAI 中间帧刷新、最终图片持久化、重启读取、失败重试和删除清理。
12. 若 NovelAI 仍返回 internal error，记录 UI 显示的 request ID，并对照官方当前网页客户端 payload 调整参数。

---

## 12. 给后续 AI 的直接指令

- 先读本文件、`doc/ui_design_system.md` 和 `.agents/skills/chatbar-shadcn-compose/SKILL.md`。
- 新页面只使用 `ui/kit`；不要重新引入 Material 3。
- 新 UI 必须使用语义 token 和现有 variant。
- 不恢复流式自动跟随，不在聊天页恢复底栏。
- 修改实体字段时提供默认值或 nullable，保证旧 JSON 可读取。
- 修改 `ChatViewModel` 前确认 RAG planner、检索、过滤和 prompt 组装顺序。
- 保持 RAG 空来源短路：无已索引文档且没有上下文外消息时不得调用 Planner、查询 Embedding 或向量检索；仅有一种来源时不得加载另一种来源。
- 角色卡/会话删除必须经 `DeletionCoordinator`，不得恢复 ViewModel 作用域内的逐项同步删除。
- 修改预制模型只编辑 `default-models.json`；不得把真实共享 Key 提交进 Git。
- NovelAI Token 只能通过 `NovelAiCredentialStore` 读写；不得加入 `AppSettings`、Debug 日志、异常文本或导出文件。
- NovelAI Prompt 设计模板只在 `PromptTemplates.kt` 修改；设计 AI 使用 `enable_thinking=true`、`max_thinking_tokens=128`。
- 删除生成图片前必须经 `NovelAiImageStorage` 验证路径位于应用私有 `images/generated` 根目录。
- 新增预制角色/格式 JSON 后必须同步登记 `presets/manifest.json`，并使用永久唯一 `presetKey`。
- 禁止在承载用户真实数据的设备上运行 `connected*AndroidTest`；instrumentation 部署可能重装目标 APK 并清空应用数据。默认只执行 AndroidTest 编译。确需真机测试时，必须先获得用户明确授权并完成应用数据备份，优先使用专用测试设备或模拟器。
- 修改已发布预制时递增 `version`；新版只提示更新，不自动覆盖用户实例。
- 角色卡 JSON 只允许 schema v3；不得重新写入实体 `id`、`customDocuments`、`filePath`、`createdAt`、`updatedAt` 或 RAG 派生字段。
- 预制图片使用逻辑 resource ID；图片值为 `PackagedImage(fileName, data)`，其中 `data` 可为 Base64 或 `asset:<assets 相对路径>`。

---

## 13. 未确认信息清单

- 聊天页流式滚动和跳底按钮体验。
- 角色卡 schema v3 文档/图片跨设备导入导出、存档读档、图片辅助模型、多回复边界链路。
- RAG 召回准确率与最新 prompt 的对话效果。
- 大量文档/消息/向量下的删除耗时，以及零来源和仅长期记忆 RAG 的真实端到端延迟。
- 多种 OpenAI 兼容服务的参数兼容性。
- 有效 Persistent API Token 下 NovelAI V4.5 Full 是否可稳定生成并持续返回 MessagePack 中间帧。
- NovelAI 当前 `832x1216`（竖屏）、28 steps、scale 8 参数是否作为最终产品默认值。

---

## 超短接手摘要（5 行）

ChatBar 是 Kotlin/Compose Android AI 角色扮演 App，数据保存为本地 JSON。
核心能力为流式聊天、多模态、NovelAI 生图和 Memory-aware Agentic RAG。<br>
2026-06-15 已完成 Foundation UI、角色卡传输 schema v3、可恢复批量删除、空来源 RAG 短路与 NovelAI 生图主体功能。
2026-06-14 已完成默认、自定义 API、完全自定义三层模型配置。
单元测试、AndroidTest 编译、Debug APK 构建通过；6 月 17 日最新 APK 已覆盖安装并冷启动成功。

---

## 14. 角色卡编辑与预制目录升级（2026-06-13）

已完成：

- `CharacterCard` 新增结构化/自由模式、基本设定、自由人物文本、预制来源字段；旧 JSON 默认结构化。
- 保存最低条件改为角色卡名称 + 起始台词；名称 trim、忽略大小写唯一。
- Prompt 按“基本设定 → 人物设定”组装，空段跳过；自由模式替换整个人物设定。
- RAG 业务只索引/检索 `DOCUMENT`、`CHAT_MEMORY`；启动清理旧 `CHARACTER_SETTING` 块。
- 角色/格式卡长按菜单支持编辑、复制、导出、删除；角色列表直接创建新会话。
- 复制、导入、覆盖使用 transfer service 深复制资源；同名导入支持覆盖或自动编号新建。
- APK 内置 `assets/presets/manifest.json` 与角色/格式示例；新 key 自动导入一次，更新只显示角标。
- 删除角色保留历史会话；缺失角色聊天显示封存状态并禁用发送/重新生成。
- 完成单元测试、AndroidTest 编译、Debug APK 构建。

2026-06-14 真机验证：APK 安装和冷启动成功；预制角色/格式自动导入成功；角色长按菜单显示编辑、复制、导出、删除；角色编辑页显示基本设定和人物编辑模式；“开始聊天”会创建新会话并写入预制起始台词；Logcat 无 FATAL/序列化异常。

仍待手工验证：模式切换确认/取消与清空范围、导入同名三路径、复制资源独立性、删除后封存、覆盖同 ID 后恢复可聊、格式卡长按菜单。

### 角色卡传输 schema v3（2026-06-15）

#### 已完成

- 角色卡传输模型不再直接序列化持久化实体 `CharacterCard`；改用 `PackagedCharacterCard`、`PackagedCharacter`、`PackagedDocument`、`PackagedImage`。
- 文档只在外层 `documents` 保存一次，字段为 `fileName`、`fileType`、`content`；不再导出 `customDocuments`、`filePath`、文档 ID、时间、Hash 或 RAG 状态。
- 图片引用改为逻辑 resource ID：角色卡保存 `avatarResourceId`、`chatBackgroundResourceId`，人物保存 `appearanceImageResourceId`。
- 外层 `images` 为 `Map<String, PackagedImage>`；图片数据支持纯 Base64 和预制专用 `asset:<assets 相对路径>`。
- 导入时重新生成角色卡 ID、人物 ID、文档 ID、本地文件路径、创建/更新时间，并将 RAG 状态重置为待索引。
- 普通导出遇到文档或图片源文件缺失时直接报错，不再静默生成缺资源包。
- 导入前校验 schema 版本、名称、文档元数据、图片载荷和全部图片引用；当前只接受 `schemaVersion: 3`。
- 覆盖流程改为先物化并保存新资源，成功后再删除旧资源；物化或保存失败时清理已创建的新文件。
- 预制来源信息不写入角色 JSON，由 `presets/manifest.json` 的 `presetKey`、`version` 在导入时注入。
- 删除 `ManageViewModel` 中不可达的旧 v2 导入导出实现，管理页统一使用 `CharacterCardTransferService`。

#### 当前 JSON 结构

```json
{
  "schemaVersion": 3,
  "exportedAt": 0,
  "card": {
    "name": "角色卡名称",
    "avatarResourceId": "avatar",
    "characters": [
      {
        "name": "人物名称",
        "appearanceImageResourceId": "character-0-appearance"
      }
    ],
    "greeting": "起始台词",
    "editMode": "STRUCTURED",
    "basicSetting": "基本设定"
  },
  "documents": [
    {
      "fileName": "世界设定.md",
      "fileType": "md",
      "content": "文档正文"
    }
  ],
  "images": {
    "avatar": {
      "fileName": "avatar.png",
      "data": "<Base64 或 asset:presets/images/avatar.png>"
    },
    "character-0-appearance": {
      "fileName": "character.png",
      "data": "<Base64 或 asset:presets/images/character.png>"
    }
  }
}
```

#### 预制迁移

- `MujicaMyGO.json` 已迁移到 schema v3，manifest 版本递增到 `2`。
- 原自由模式示例已由 `Rupa.json` 替代；`presetKey: Rupa`、版本 `1`。
- `Rupa.json` 的自由人物设定保存在 `freeformCharacterText`，`characters` 保持空列表。
- 单元测试会读取 manifest 中全部角色预置，验证文件存在、可按 v3 解码，且不含本地路径、持久化时间或 `customDocuments`。

#### 验证状态

- `test`：通过，包含 schema v3 round-trip、缺失图片引用、拒绝 v2、全部角色预置解码测试。
- `:app:compileDebugAndroidTestKotlin`：通过。
- `assembleDebug`：通过。
- `ci.ps1` 完整流程：通过。

#### 待手工验证

1. 带多份文档的角色卡导出后，在另一安装环境导入并完成 RAG 重建。
2. 同时带头像、聊天背景、人物形象图的角色卡导出/导入，确认所有 resource ID 正确映射。
3. 同名导入的覆盖、创建新卡、取消三路径；覆盖失败时旧卡及旧资源保持可用。
4. 预制图片使用 `asset:` 数据源时可正确复制到应用私有目录。

---

## 15. 模型分层配置升级（2026-06-14）

### 已完成

- `AppSettings` 新增 `modelConfigurationMode`、`presetDefaultModelKey`、`siliconFlowApiKey`；旧 JSON 可兼容读取。
- 三种模式：
  - `DEFAULT`：隐藏模型分页，只展示可选预制对话模型，使用包内共享 Key。
  - `CUSTOM_API`：隐藏模型分页，使用用户填写的硅基流动 Key 覆盖全部预制模型。
  - `FULL_CUSTOM`：显示原模型分页，只使用用户自定义模型。
- 新增 `PresetModelCatalogService`、`EffectiveModelResolver`、`ModelConfigurationMigration`、`PresetModelPolicy`。
- 预制模型引用使用 `preset:<modelKey>`，与用户模型 ID 隔离。
- 会话指定模型在当前模式不可用时，运行时回退当前全局默认；原 ID 保留，切回原模式可恢复。
- 向量模型从多实例 + 默认选择改为单例；首次迁移优先保留旧 `defaultEmbeddingId`，否则保留首项并清理其余旧记录。
- 检索规划模型继续保持单例；设置页删除默认向量模型选择器。
- 缺少共享 Key、用户 Key或必要预制型号时：应用可启动，但创建会话和发送消息禁用，并显示具体原因。
- 自定义 API Key 保存不阻塞联网；独立测试按钮同时测试最小对话与 embedding 请求。
- 角色管理页和首页新建会话入口均检查模型配置可用性。
- 会话设置会提示旧模型已在运行时回退。

### 构建配置

预制型号文件：

```text
app/app/src/main/assets/presets/models/default-models.json
```

必须把以下 `TODO_*` 替换为硅基流动实际模型 ID：

- 对话模型，至少一个可选项。
- 检索规划模型。
- 向量模型及正确 `dimensions`。
- 视觉、备用对话模型可按产品需要配置。

共享 Key 不写入仓库。配置方式：

```properties
# C:\Users\Administrator\.gradle\gradle.properties
CHATBAR_SILICONFLOW_API_KEY=sk-xxxxxxxx
```

也支持同名环境变量。Gradle 将其写入 `BuildConfig.SILICONFLOW_API_KEY`。

### 预制格式修复

已将以下包内 JSON 登记到 `presets/manifest.json`：

- `erowriting-format.json` → `presetKey: erowriting-format`
- `thinkchain-format.json` → `presetKey: thinkchain-format`

此前未生效原因：文件虽在 assets 中，但 manifest 未登记，目录服务不会发现或导入。

规则：新 `presetKey` 首次启动自动导入一次；已见 key 不重复导入；同 key 增加版本只显示更新，需从“恢复预制格式”手动导入或覆盖。

### 验证状态

- `test`：通过。
- `:app:compileDebugAndroidTestKotlin`：通过。
- `assembleDebug`：通过。
- 最新 APK 已通过 `adb install -r` 安装并启动。
- 按约定未执行启动后的自动截图或交互验证。

### 待手工验证

1. 默认层：填写实际型号和共享 Key 后完成对话、检索规划、文档 RAG、长期记忆 RAG。
2. 自定义 API 层：保存 Key、重启保留、测试按钮同时验证聊天与 embedding。
3. 完全自定义层：模型分页显示；自定义对话、检索、向量模型正常工作。
4. 三层来回切换：高级配置保留但停用；会话模型回退且切回后恢复。
5. 旧安装升级：默认进入默认层；旧默认向量模型迁移为单例，其余旧向量配置删除。
6. 两张新预制格式卡首次自动导入；删除后不自动复活；恢复入口可再次导入。

---

## 16. NovelAI 聊天生图（2026-06-18 更新）

### Prompt 设计流程（2026-06-18 重构）

设计 AI 一步到位生成最终提示词，不再在客户端手动拼接角色卡预设：

1. **上下文提取**：以锚点消息为终点，取最近 3 条非 system 消息。
2. **System prompt 组装**：`novelAiImagePromptSystem()` 将角色卡 `defaultImagePrompt` 和各人物 `imagePrompt` 直接写入 system prompt；角色名自动提取基础名（截去 `/`、`;`、`；` 后的别名/注释）。
3. **设计请求**：`enable_thinking=true`、`max_thinking_tokens=128`、`max_tokens=700`。
4. **AI 返回**：完整 JSON，`baseCaption` 已含卡预设风格，`caption` 已含人物预设 + 场景调整；客户端不做任何拼接。
5. **JSON 解析**：兼容新旧字段名（`baseCaption`/`scenePrompt`、`caption`/`adjustment`），旧字段自动 fallback。
6. **convert()**：仅做 relation tag 清理和 center 坐标 clamp/fallback；不做任何角色名筛选，AI 返回的每个角色直接采纳（上限 6 人）。
7. **Debug 日志**：设计 AI 的 system/user 消息和原始返回 JSON 均写入 `DebugLogManager`，可在 Debug 对话框查看。

### 生图请求

- 端点：`POST https://image.novelai.net/ai/generate-image-stream`
- 模型：`nai-diffusion-4-5-full`
- 当前固定参数：`832x1216`（竖屏）、28 steps、scale 8、`k_euler_ancestral`、Karras、单图、随机 seed
- V4 payload：`base_caption` + `char_captions`（含独立 center 坐标）
- 负向提示词：逗号分割后 `.distinct()` 去重
- MessagePack 流：4 字节大端长度前缀，处理 `intermediate`、`final`、`error`
- 连接错误自动重试：最多 3 次，每次换新 seed；仅对网络层错误（connection/timeout/closed/reset 等）重试

### UI 交互

- 生成图片支持原比例气泡显示、点击全屏、双指 `1x–5x` 缩放和拖动
- 全屏预览**长按**弹出保存确认，通过 MediaStore 保存到 `Pictures/ChatBar/`
- 气泡长按单张图片显示删除确认
- 中间预览帧在会话末尾状态卡实时刷新
- 同会话只允许一个生图任务；失败状态保留错误信息和重试按钮

### 存储与清理

- 生成图片目录：`files/images/generated/<sessionId>/`
- 单图、消息、清空记录和会话删除均经 `NovelAiImageStorage` 受控路径清理

### 关键实现

```text
data/security/NovelAiCredentialStore.kt   — Keystore 加密存储 Token
domain/image/NovelAiPromptDesigner.kt     — 一步 Prompt 设计、JSON 修复、convert()
domain/image/NovelAiImageService.kt       — 请求体构建（含负向词去重）、流解析
domain/image/NovelAiStreamFrameDecoder.kt — MessagePack 帧拆包
domain/image/NovelAiImageStorage.kt       — 图片存储与清理
domain/prompt/PromptTemplates.kt          — 系统提示词模板
domain/chat/StreamingChatService.kt       — streamText() 支持 maxThinkingTokens
ui/chat/ChatViewModel.kt                 — 生图编排、连接重试、Debug 日志
ui/chat/ChatScreen.kt                    — 全屏预览（缩放/拖动/长按保存）
ui/components/ChatBubble.kt              — 图片气泡与生图按钮
```

### 验证状态

- `test`：通过，覆盖 baseCharacterName、convert（免筛选、center clamp）、relation tag 清理、MessagePack 分片、事件解析、请求 fixture、Prompt 流增量、负向词去重。
- `assembleDebug`：通过。
- 最新 APK 已通过 `adb install -r` 覆盖安装。

### 待手工验证

1. 使用有效 Persistent API Token 完成一次真实 V4.5 Full 生图，确认中间帧、最终帧及 request ID。
2. 重启应用后确认最终图片消息和本地 PNG 正常读取。
3. 分别验证单图长按删除、整条消息删除、清空记录、删除会话后的文件清理。
4. 验证横图、竖图和超长图在气泡及全屏预览中的比例、缩放、拖动和长按保存体验。
5. 若服务端仍返回 `Error generating image, an internal error occurred`，保留 request ID，并重新抓取官方当前网页客户端请求体逐字段对照。
