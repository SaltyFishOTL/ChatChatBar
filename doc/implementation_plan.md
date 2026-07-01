# ChatBar - AI 角色扮演 Android App 实施计划

## 2026-06-03 Phase 2.7 首版完成与思考过程默认折叠

- 状态：Phase 2.7 `Memory-aware Agentic RAG` 标记为已完成（首版）。后续只保留调优、评估、迁移类尾项，不再阻塞进入下一任务。
- 修复：AI 消息中的 `reasoningContent` 默认折叠，点击“思考过程”区域再展开，避免长思考内容默认占满聊天界面。
- 实现：`ChatBubble` 的思考过程展开状态改为按 `message.id` 记忆，默认 `false`，避免列表复用导致不同消息共享展开状态。

## 2026-06-03 聊天窗口初始定位到最新消息

- 问题：进入已有聊天时，`LazyListState` 初始位置为 0，消息加载完成后没有一次性滚动到底部，导致默认显示最早消息。
- 修复：`ChatScreen` 增加 `didInitialScrollToBottom`，在当前会话消息首次加载完成且非空时执行一次 `scrollToItem(messages.lastIndex, Int.MAX_VALUE)`，滚到列表真实底部，而不是只把最新消息 item 顶到视口。
- 修复：右下角跳底按钮同步改为 `animateScrollToItem(lastIndex, Int.MAX_VALUE)`，避免最新 AI 消息很长时只跳到该消息开头。
- 约束：该逻辑只负责进入窗口时定位到最新消息，不恢复流式输出自动跟随；后续用户滚动仍由右下角跳底按钮控制。

## 2026-06-03 管理后台编辑返回分页保持

- 问题：首次打开 App 后，从管理后台格式卡/大模型等分页进入编辑页再返回，会回到角色卡分页。
- 原因：`ManageScreen` 的 `selectedTab` 使用普通 `remember`，编辑页压栈后管理页内容首次被销毁/重建时，本地状态回到默认 0。
- 修复：`selectedTab` 改为 `rememberSaveable`，让管理页分页状态在导航离开和返回时恢复。

## 2026-06-03 对话消息编辑首版

- 交互：长按任意已落库消息都会弹出“消息操作”按钮清单；旧消息只提供“编辑”，最新消息额外提供“删除”；最新 AI 回复保留“重新生成”入口。
- 编辑页：选择编辑后进入全屏编辑层，左下角退出，右下角保存；右下角另有插入图片按钮，已有图片可在缩略图右上角删除。
- 保存：`ChatRepository.updateMessage` 更新消息正文、图片、更新时间；若编辑的是最后一条消息，同步刷新首页会话预览。
- RAG 同步：保存前检查该消息是否已有 `CHAT_MEMORY` chunk；若没有，说明仍处于普通上下文窗口或未索引，只更新消息本身；若已有，先删除旧 chunk，再按旧 chunk 的 `messageIds` 重建对应消息组，失败时删除旧记忆块，避免旧内容继续被召回。
- 复测修正：编辑层打开时隐藏系统栏，并临时隐藏聊天页 `TopAppBar`，避免全屏编辑顶部内容被挡住；系统返回键优先关闭编辑层，不再直接从聊天页返回主页。
- 复测修正：长按菜单调整为“最新消息：编辑/删除/重新生成；其他消息：编辑/删除”。删除任意消息前先记录旧 `CHAT_MEMORY` chunk 的 `messageIds`，删除后清旧 chunk，并用剩余关联消息重建，确保被删消息在记忆中不再提供旧内容。
- 复测修正：聊天输入为空时也允许发送，用于让 AI 基于现有上下文继续生成；重新生成改为“删除最新 AI 回复并发送一条空用户消息”，避免重复发送上一条用户原文导致 RAG/上下文变量不一致。
- 性能修正：`CHAT_MEMORY` 改为“单消息独立 chunk + 最近上下文增强 embedding 文本”；删除/编辑只处理该消息对应 chunk，不再重建多消息组合 chunk。
- 性能修正：发送完成后不再立刻索引仍处于 normal context window 内的新消息；仅索引刚离开上下文窗口、尚未建记忆的少量旧消息，避免过早建无用 chunk 并拖慢删除。
- 反馈修正：删除消息后先立即刷新聊天 UI，再显示“正在删除长期记忆”加载框执行 RAG 清理，防止用户误以为删除没生效而反复操作。
- 复测修正：加载框延迟来自删除前仍在查询旧 `memoryChunks`；改为删除函数开头立即设置 loading、乐观移除 UI，并 `yield()` 让 Compose 先渲染反馈，再执行文件删除和 RAG chunk 清理。
- 复测修正：新消息仍会删除慢，是因为删除函数无条件调用 `deleteMemoryForMessage()` 扫 RAG 文件。现改为先判断目标消息是否仍在 normal context window 内；若在，删除消息后直接结束，不显示长期记忆加载框，也不扫 RAG。

## 2026-06-03 多模态图片链路修复

- 问题：Debug 面板展开“发送给 API 的 JSON 结构体”时，含图请求会直接展示超长 base64，导致 Compose 文本渲染压力过大并闪退。
- 修复：`DebugLogManager` 存储 request body 前对 `data:image/...;base64,...` 做脱敏占位，保留长度信息但不展示完整 base64。
- 问题：角色卡人物 `appearanceImage` 没进入最终 API 消息，模型只能看到文字设定。
- 修复：聊天请求组装时收集角色卡人物外观图；多模态聊天模型直接把这些图片作为角色外观参考消息发送；纯文本聊天模型若配置了多模态视觉辅助模型，则先用视觉模型生成外观描述并追加进 system prompt。
- 问题：纯文本模型 + 视觉辅助模型解析图片时报“图片请求失败：null”。
- 修复：`describeImage` 不再在主线程同步 `call.execute()`，改为后台线程执行 OkHttp 请求，并在异常信息为空时输出异常类型，避免 `null` 错误。

## 2026-05-31 RAG 召回隔离修复

- 需求：`chat_memory` 与 `document` 分别召回，不能互相挤占 Top-K；两类召回数量和相似度阈值分开配置。
- 已有状态：`AppSettings` 已有 `memoryRagTopK` / `memoryRagSimilarityThreshold` 与 `docRagTopK` / `docRagSimilarityThreshold`；管理页已有独立滑块；`ChatViewModel` 已分别排序过滤后再合并结果。
- 本次修复：`ChatViewModel` 在 RAG 前先计算当前会发送给 AI 的 `contextMsgs` 与 `activeContextMessageIds`。
- 本次修复：召回 `CHAT_MEMORY` 时，通过 chunk `metadata["messageIds"]` 和 `messageId` 过滤掉所有与当前上下文窗口消息重叠的记忆块，避免“普通上下文 + RAG 记忆”重复发送同一段近期聊天。
- 本次确认：document 召回只使用 `ChunkSourceType.DOCUMENT`；`CHARACTER_SETTING` 不参与 document RAG，因为角色设定已固定进入 system prompt。
- Debug：RAG debug log 新增英文诊断行，输出 document 候选数、chat_memory 候选数、当前上下文消息数、过滤后的 eligible chat_memory 数、被过滤数量。
- 防回归：`RagManager.search()` 旧合并式共享 Top-K API 已标记 `@Deprecated`，避免后续重新绕过分离召回逻辑。
- 验证：`compileDebugKotlin` 与 `ci.ps1` 通过。

## 2026-05-31 复测后修正：滚动仍跟随、按钮不出现

- 现象：用户安装新包后仍看到流式输出自动跟随，且离底部时没有“跳转到底部”按钮。
- 原因判断：先前 `ChatScreen.kt` 局部编辑后出现编码/字符串损坏风险，且单纯删除显式自动滚动不足以阻止 `LazyColumn` 在尾部流式 item 高度增长时维持贴底视觉效果。
- 修复：完整重写 `ChatScreen.kt` 为干净 UTF-8；保留相册图片选择和缩略图预览；移除所有“新增消息/流式 token -> 滚到底部”的逻辑。
- 新增：用 `viewportAnchor` 记录首个可见 item 的 index/offset；流式内容增长且用户未滚动时，恢复该锚点，抵消尾部 item 增高导致的隐式贴底。
- 交互：仅当 `isAtBottom == false` 时显示右下角悬浮按钮；点击按钮才 `animateScrollToItem(lastIndex)`；回复完成后在按钮右上角显示绿色小勾。
- 验证：`compileDebugKotlin`、`assembleDebug`、`ci.ps1` 全部通过。新 debug APK：`D:\Projects\ChatBar\app\app\build\outputs\apk\debug\app-debug.apk`，时间 `2026-05-31 19:04:25`，大小 `21,828,655` bytes。

## 最新决策记录

### 2026-05-31：流式输出滚动策略改为用户主动跳转

- 变更：彻底取消流式输出期间的自动滚动跟随。不再监听 `streamingMsg.content.length` 后主动滚到底部。
- 交互：当当前视口不在最底部时，在消息列表右下角、输入框上方显示“跳转到底部”悬浮按钮。
- 状态标记：当 `!isResponding && streamingMsg == null` 时，在按钮右上角显示小勾，表示当前最新消息已完整输出。
- 原因：自动跟随难以与用户手动滚动意图同步，按钮式跳转把滚动时机交还给用户。
- 验证：`.\gradlew.bat compileDebugKotlin` 通过。仍需真机确认按钮位置、显隐和小勾状态。

## 当前修复记录

### 2026-05-31：流式输出期间滚动锁死

- 问题：`ChatScreen.kt` 以 `LaunchedEffect(messages.size, streamingMsg?.content?.length)` 监听流式消息长度；每次 token 更新都会触发滚动到底部。用户手动上滑时，下一次 token 更新又把列表拉回正在输出的消息，表现为滚动被锁死。
- 修复：新增 `autoFollowStreaming` 状态和 `NestedScrollConnection`。AI 回复期间检测到用户手动滚动 (`NestedScrollSource.UserInput`) 后，暂停自动跟随；当用户重新滚到底部或回复结束后，再恢复自动跟随。
- 细节：底部判断从“最后一个 item 可见”收紧为“最后一个 item 底部接近 viewport 底部”，避免流式 item 变高时误判。自动跟随时用 `scrollToItem`，减少持续 `animateScrollToItem` 造成的抢滚动感。
- 验证：`.\gradlew.bat compileDebugKotlin` 通过。仍需真机手动验证：流式输出中上滑查看历史，不应被拉回；手动滚回底部后，应继续跟随新 token。

基于 RAG 技术的 AI 角色扮演工具，支持百万字级别设定与记忆。

## 技术决策总结

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 开发框架 | Kotlin + Jetpack Compose | Android 现代标准，声明式 UI |
| 架构模式 | MVVM + Repository | Android 官方推荐 |
| 向量存储 | ObjectBox + HNSW | 百万字级别需万级向量毫秒搜索 |
| Embedding | 远程 API (OpenAI 兼容) | 用户配置 BaseURL + ApiKey + 模型名 |
| 分块策略 | 自动语义分块 (300-600字/块，50字重叠) | 用户零操作，段落优先+句子边界二次切分 |
| 上下文策略 | 固定窗口 N 条 + RAG 检索 | N 用户可配，默认 20 |
| RAG 检索 | top-K + 相似度阈值交集 | 全局配置，提供最优默认值 |
| AI 回复 | 流式输出 (SSE) | 角色扮演场景必须 |
| 文本渲染 | Markdown + HTML 注释 | 双向渲染（AI + 用户消息） |
| 多模态 | 用户发图，AI 回文字 | 多模态模型直发 / 纯文本模型先转描述 |
| 角色卡格式 | 自定义 JSON | 后续再加社区兼容格式 |
| 存档系统 | 无限槽位 | 用户自由创建 |
| UI 主题 | 暗色 only (非纯黑) | 仿聊天软件风格 |
| UI 语言 | 中文 | 最简 |
| 数据安全 | 本地存储，不加密 | 用户选择 |

---

## 分阶段交付计划

### Phase 1：核心功能（MVP）- [已完成]

#### 包含功能：
- [x] **项目搭建**：Kotlin + Compose + ObjectBox + 网络层(OkHttp/Retrofit)
- [x] **角色卡系统**：创建/编辑/删除角色卡（单角色先行）
  - 角色简介、形象（支持图片）、语气、过往、起始台词
  - 自定义信息（支持上传 txt/md/json 文档）
- [x] **模型管理**：配置聊天模型 + Embedding 模型
  - BaseURL + ApiKey + 模型名
  - 预设模板（OpenAI/Claude/Gemini等）→ 自动生成可视化参数表单
  - 用户可自由添加自定义参数（key + 类型选择 + 值）
- [x] **聊天系统**：
  - 流式输出
  - Markdown + HTML 注释渲染
  - 固定窗口 N 条上下文
  - 气泡式聊天 UI（暗色主题）
- [x] **多模态图片**：
  - 用户发送文字 + 插图混排
  - 多模态模型：直接发送 base64 图片
  - 纯文本模型：先调视觉模型转描述文字，再发给聊天模型
  - 角色卡设定中支持图片（多模态识别）
- [x] **RAG 引擎**：
  - 自动语义分块
  - ObjectBox 向量存储 + HNSW 索引
  - 设定文档 RAG 检索
  - 聊天记忆自动 RAG 化（截断时存入）
- [x] **Prompt 组装**：system prompt 拼接（角色设定 + 玩家设定 + 格式 + RAG 结果）
- [x] **首页**：最近聊天列表

### Phase 2：高级功能 - [进行中]

- [x] **多角色卡**：同一角色卡内多角色
- [x] **格式卡系统**：创建/管理/应用格式卡
- [x] **存档系统**：无限槽位存档/读档
- [x] **对话编辑**：回溯修改历史消息 + 删除 + 记忆同步更新
- [x] **重新生成**：长按最新一条 AI 回复弹出次级菜单；点击“重新生成”会删除该 AI 回复和上一条用户消息，再复用上一条用户消息重新发送，重新执行当前信息下的 RAG 检索和生成，便于控制变量调试 RAG。
- [x] **聊天设置面板**：切换模型、回复长度、回复语言、聊天背景
- [x] **玩家设定**：全局 + 逐对话覆盖
- [x] **补充设定**：逐对话编辑
- [x] **角色卡导入导出**：自定义 JSON 格式
- [x] **模型高级配置**：更多预设模板 + 参数模板分享
- [x] **开发者调试控制台**：支持在 UI 界面一键显示每轮组装提示词、RAG 检索结果、请求 JSON 以及原始 SSE 流（开关：`DebugConfig.SHOW_DEBUG_UI`）
- [x] **知识库文档批量上传**：支持调用文件选择器选择文件夹，一次性导入其中所有的 `.txt`/`.md`/`.json` 文件并进行 RAG 索引
- [x] **多模态图片选取支持**：系统级图片选择器，用于选取角色卡头像及子角色形象图，并自动拷贝至应用私有目录，废弃手动输入绝对路径的交互设计
- [x] **修复大模型管理修改项错误 Bug**
- [x] **修复流式聊天 API 400 参数校验错误 Bug**
- [x] **思维链展示**
- [x] **RAG 诊断与调试强化**
- [x] **输入法标点输入修复**
- [x] **原生输出内容查看**
- [x] **会话设定实时生效**

### Phase 2.6：对话体验优化与系统打磨 - [已完成]

- [x] **流式输出自由滚动（更新版）**：改变自动滚动触发机制。在 `ChatScreen.kt` 中，增加 `isAtBottom` 判断和 `isScrollInProgress` 检测。在流式输出中，如果用户处于列表最底部且未在手动滑动，则跟随新字符自动滚动；若用户已经向上翻阅历史，则停止跟随滚动，允许用户自由翻阅历史。
- [x] **RAG 索引缺陷与诊断优化**：
  - 修复 `CharacterEditViewModel.kt` 中保存角色卡时只索引角色基本设定而忽略已关联 customDocuments 的问题，支持在保存角色卡时自动索引全部关联文档。
  - 修复 `RagRepository.kt` 中删除文档向量块的逻辑漏洞：添加 `deleteChunksByDocumentId` 处理文档级删除，解决旧文档向量块无法被清除并在 RAG 中堆积的问题。
  - 移除对 RAG 索引异常的吞没，通过 `indexingStatus` StateFlow 将索引状态 and 错误暴露至 UI。
- [x] **Prompt 结构化与 System Instruction 沉底**：
  - 重新编排 System Prompt 结构：角色卡基本描述/格式/RAG (顶部) -> 补充设定 -> 个人人设 -> 系统核心指令 (最底部，最接近对话消息，起强约束效果)。
  - 将核心 System Instruction 声明为 `PromptAssembler.kt` 顶部显眼的变量常量，极大地方便开发者后续直接修改。
- [x] **聊天中多模态图片相册选取**：
  - 替换原有在聊天框中手动填写图片绝对路径的交互，为聊天输入栏的图片附件按钮绑定系统照片选择器 (`ActivityResultContracts.GetContent`)。
  - 将选中的图片自动复制至 App 私有目录并生成本地文件，与待发送消息绑定。
- [x] **玩家名称 ($username) 自动替换**：
  - 全局玩家设定 (`PlayerSetting.kt`) 结构扩展玩家名称字段 `playerName`。
  - 全局设置 Tab 中提供玩家名称 of 输入和详细文案提及。
  - 在最终拼装 System Prompt 后，通过正则或 replace 将全局提示词中所有的 `"$username"` 替换为玩家配置的真实名称。
- [x] **删除功能二次确认**：
  - 统一为所有删除入口添加确认 AlertDialog 弹窗，防止误触。涉及：角色卡删除、格式卡删除、对话大模型删除、嵌入模型删除、子人物删除、自定义文档删除、存档快照删除。
- [x] **格式卡编辑修改**：
  - 在管理界面中为格式卡 Tab 列表项目补充“编辑/修改”按钮，点击正常导航进入 `FormatCardEditScreen` 进而修改已有格式卡，补全格式卡闭环 CRUD。
- [x] **RAG 独立检索与参数分离 (记忆 vs 文档)**：
  - 将 `AppSettings` 中的全局 `ragTopK` 与 `ragSimilarityThreshold` 拆分为 `memoryRagTopK`, `memoryRagSimilarityThreshold` 与 `docRagTopK`, `docRagSimilarityThreshold`。
  - 管理界面 SettingsTab 中新增多组滑块，支持分别配置“对话记忆”与“知识库文档”的检索参数。
  - 在 `ChatViewModel.kt` 的检索流水线中，隔离 `CHARACTER_SETTING` 向量块（不再参与 RAG，因已固定位于 System Prompt），并将文档与对话记忆的余弦相似度计算、过滤与 Top-K 提取独立进行后合并结果，防止不同类型知识相互挤占。
- [x] **优化聊天图片选取交互**：
  - 将 `ChatScreen` 的图片选择器从 `GetContent()` 替换为 `PickVisualMedia()` 以唤起系统原生相册（照片选择器）代替文件管理器。
  - 替换底部的文字路径预览为真实的 `AsyncImage` 缩略图预览，对齐主流社交软件体验。
- [x] **新增会话级别玩家名称覆盖**：
  - 在 `ChatSession` 及 `SaveSlot` 实体中新增 `playerName` 字段支持会话覆盖。
  - 在 `ChatSettingsDialog` 中增加“玩家名称覆盖”输入框，并明确提示其会替换最终 System Prompt 中的 `$username` 宏。
  - 组装提示词时优先应用会话专属名称覆盖，兜底使用全局玩家名称。

### Phase 2.7：Memory-aware Agentic RAG - [已完成（首版）]

目标：把当前“用户原话 embedding topK”升级为适合角色扮演的联想式记忆系统。系统在聊天流中识别话题、实体、关系和隐含意图，按需检索百万字级角色设定与长期记忆库，经过多路召回与重排后低打扰注入上下文。

#### 技术设计补充

##### 1. 对话状态追踪器：先理解“现在在聊什么”

每轮聊天先生成结构化 `ConversationState`，再决定是否检索。不能直接把用户原话送进向量库。

```json
{
  "current_topic": "RAG产品方案设计",
  "mentioned_entities": ["上次那个客户"],
  "implicit_intent": "寻找相似案例或历史经验",
  "possible_recall_targets": [
    "历史客户案例",
    "相似RAG项目",
    "之前讨论过的方案",
    "客户需求与痛点"
  ],
  "should_retrieve": true
}
```

- `ConversationState` 输入：当前用户消息、最近 N 轮用户/助手消息、当前角色卡、当前会话设定、最近已注入知识卡片摘要。
- `ConversationState` 输出：当前话题、实体、关系、代词指代、用户意图、隐含任务、潜在召回目标、是否需要检索。
- 设计原因：联想能力不等于检索能力，而是“把聊天上下文转成检索意图”的能力。代词、省略、上下文依赖句必须先被补全。

##### 2. 联想触发器：不要每句话都查库

新增 `RecallDecision`。首版不让小模型判断可信度或是否应该召回；小模型只提炼检索 query/entities，本地 gate 根据 `q/e` 是否为空、显式实体/文档名/触发词命中等规则决定是否检索，避免小模型置信度不准导致误跳过或误召回。

```json
{
  "should_recall": true,
  "recall_type": ["project_memory", "knowledge_base", "similar_cases"],
  "reason": "用户提到与过去客户案例相似，需要召回历史案例",
  "confidence": 0.82,
  "max_context_items": 8
}
```

触发器按以下信号打分：

| 信号 | 说明 |
| --- | --- |
| 实体命中 | 提到人名、项目、客户、产品、文档名 |
| 代词/省略 | “上次那个”“之前说的”“类似那个” |
| 决策场景 | “要不要”“怎么选”“有什么风险” |
| 历史依赖 | “我之前”“我们上次”“那个方案” |
| 知识依赖 | 涉及事实、政策、技术细节、文档内容 |
| 用户偏好 | 涉及用户长期偏好、禁忌、习惯 |
| 新旧冲突 | 当前说法可能和历史记忆冲突 |

- 设计约束：小模型输出不包含置信度；本地规则只做“是否有可检索线索”的保守门控。后续若要引入可信度，也必须由 reranker/命中分布/实体精确匹配等可解释信号计算，不交给小模型主观判断。
- 设计参考：Self-RAG 的核心启发是按需检索，并评估检索内容是否有用；固定塞入检索段落可能引入无关内容，降低生成质量。

##### 3. Query Planner：一次生成多条检索 query

新增 `QueryPlanner`。它从 `ConversationState` 和 `RecallDecision` 生成多条检索 query，而不是只用用户原话。

```json
{
  "queries": [
    {
      "type": "decontextualized",
      "query": "当前RAG产品方案与历史ToB客户需求的相似点"
    },
    {
      "type": "entity",
      "query": "ToB客户 RAG 需求 知识库 检索 聊天记忆"
    },
    {
      "type": "case_recall",
      "query": "过去讨论过的类似RAG客户案例"
    },
    {
      "type": "hyde",
      "query": "一个企业客户希望AI在对话中主动记起相关知识库内容和历史上下文，用于销售、客服或内部知识助手"
    },
    {
      "type": "graph",
      "entities": ["RAG产品", "ToB客户", "聊天记忆", "知识库"]
    }
  ]
}
```

- `decontextualized`：把代词、省略和前文依赖补全成独立查询。
- `entity`：保留人名、作品名、组织名、文档名等精确线索，用于词面/BM25/metadata 检索。
- `case_recall`：偏向历史案例和长期记忆库。
- `hyde`：生成“假设相关文档”再 embedding，用于增强 dense retrieval；允许包含不真实细节，但检索必须落回真实语料。
- `graph`：后续可接实体关系索引，用于“谁和谁是什么关系”“某组织下有哪些角色”这类查询。

##### 4. 文档入库：Contextual Chunking

普通 chunk 脱离上下文后，embedding 不知道它属于哪个产品、客户、章节或角色关系。文档入库前应为每个 chunk 生成上下文说明。

格式：

```text
上下文说明：
这段内容来自《A客户RAG方案v3》的“主动记忆与召回策略”章节，
讨论的是客服聊天中基于用户意图自动召回历史订单、知识库和工单记录的方案。

原文：
...
```

- Contextual text 同时进入 embedding 与词面索引；原文仍单独保留，用于最终注入和调试展示。
- metadata 至少保留：文档名、章节路径、角色卡 ID、原文档 ID、contentHash、embeddingKey、chunk 原文范围、contextual summary。
- 设计参考：Anthropic Contextual Retrieval 思路是在 embedding 和 BM25 索引前为 chunk 加简短上下文说明；这对减少孤立片段误召回非常关键。

##### 5. Reranker 必须上线

主动联想型 RAG 最怕无关内容和过量内容。初筛召回后必须重排。

流程：

```text
每路召回 top 30-100
  -> RRF 合并
  -> 去重
  -> cross-encoder reranker / LLM reranker
  -> 过滤低分结果
  -> 保留 top 3-12 条
```

- 初筛阶段：向量、词面、metadata、历史记忆分别召回，追求召回率。
- RRF 合并：避免某一路召回垄断候选池。
- 去重：同文档相邻 chunk 合并或惩罚，减少重复注入。
- Reranker：对 query-document pair 打相关性分数；可优先接 API reranker，后续支持本地/规则降级。
- 最终过滤：低分不注入；宁可少想起，也不要错想起。
- Debug：必须显示初筛来源、RRF 分数、rerank 分数、过滤原因和最终注入顺序。

##### 6. 模型配置复用与联想判断执行策略

- Embedding 与 reranker 共用现有“嵌入模型”配置入口；不新增独立 reranker 设置。若服务端支持 rerank endpoint，则同一模型配置负责 embedding/rerank；若不支持，则降级为本地混合评分与 LLM rerank。
- 联想判断默认调用 Retrieval 专用小模型，输出 `ConversationState` / `RecallDecision` / `QueryPlan` JSON；若未配置 Retrieval 模型，则临时回落到当前对话使用的聊天 LLM。原因：思维模型/大模型执行短 JSON 判断过慢，且成本高。
- LLM 调用约束：低温度、短 prompt、短输出、严格 JSON、短超时；失败不重试，避免阻塞正常聊天。Retrieval 小模型只负责关键词/实体提炼，不负责 `confidence`、`should_recall`、事实判断或关系推断。
- 本地规则/词表仅作为 fallback：当 LLM 超时、失败、JSON 解析失败或网络不可用时，使用实体词表、文档名、章节名、最近记忆标题和少量触发词做轻量判断。
- 速度策略：RAG 注入强度为 `OFF` 时直接跳过 Retrieval Planner、Embedding 与 RAG 检索；非 `OFF` 时先跑短 Retrieval Planner，只抽取 `topic/queries/entities`，失败或超时时 fallback 到本地混合检索 query。

#### Todolist

- [x] **检索门控 `RetrievalGate`（首版）**：RAG 注入强度 `OFF` 时直接跳过 planner/embedding/retrieval；planner 返回 `topic/queries/entities` 全空时跳过 RAG；不使用小模型主观置信度。
- [x] **检索意图抽取 `RetrievalIntent`（首版）**：Retrieval Planner 只抽取 `topic`、`queries`、`entities` 三字段，保持原文语言，不做事实判断和可信度判断。
- [x] **对话查询改写 `QueryReformulator`（首版）**：Planner 成功时 embedding query 只使用 `topic/queries/entities`；planner 失败、超时或解析失败时 fallback 到原始用户消息 + 最近用户上下文。
- [x] **多路召回 `MultiRouteRetriever`（首版）**：document/chat_memory 分别生成向量候选池和词面候选池，每路限制 `max(topK * 6, 30)`，用 RRF 合并排名。
- [x] **重排器 `Reranker`（本地首版）**：基于 RRF、向量分、词面分与来源多样性做本地重排；同一 document 来源最多占约 `topK / 2`，防止少数文档垄断最终注入。
- [x] **低打扰上下文注入 `ContextInjector`（首版）**：以“可参考设定/记忆卡片”注入，支持 `OFF` / `LIGHT` / `STANDARD` / `STRONG` 四档；卡片数由“最多注入知识库文档卡片数 / 最多注入历史记忆卡片数”控制。
- [~] **长期记忆写回 `MemoryWriter`（暂不做）**：当前最优策略是直接把聊天记录当成记忆库，不引入额外摘要/写回，避免错误抽取污染长期记忆。
- [x] **百万字级索引与性能优化（首版完成）**：已完成文档增量索引、embedding 批量化、文档级并发限制、进度分批保存、后台索引状态可见、候选池裁剪；后续只保留更细粒度缓存/耗时统计等优化项。
- [x] **速度预算与降级策略（首版完成）**：`OFF` 直接跳过 RAG；planner 失败/超时/解析失败 fallback；候选池限制 `max(topK*6,30)`；planner 不自动重试，避免拖慢回复。
- [~] **准确率评估与回归集（暂缓）**：当前由人工 Debug 验证，不建立固定测试集；后续性能验收阶段再补。
- [x] **Debug 面板升级（召回链路首版）**：展示 planner raw output、topic/queries/entities、RAG query、document/chat_memory 多路候选、RRF 分数、向量/词面排名、来源分布和最终召回块。
- [x] **数据结构迁移（运行期首版完成）**：新增正式 `RetrievalIntent` / `RetrievedKnowledgeCard` 结构；运行期 planner intent 与 prompt 注入已改用新结构；底层持久化索引继续保留 `VectorChunk`。
- [x] **Retrieval 专用模型配置（首版）**：在管理后台“大模型”页新增一个单例 Retrieval 联想判断模型连接，字段与对话模型连接一致；未配置时回落当前聊天模型。建议使用便宜快速的小模型，不使用思维模型。

#### 分阶段交付

- [x] **2.7-A：只读诊断阶段（首版）**：新增 `RetrievalPlanner`，默认调用当前聊天 LLM 输出 `ConversationState` / `RecallDecision` / query plan，并写入 Debug 日志；失败、超时或 JSON 解析失败时回退当前混合检索。
- [x] **2.7-B：轻量 query reformulation（首版）**：Planner 输出精简为 `topic/queries/entities` 三字段；成功时 RAG embedding query 只使用这三项，失败、超时或解析失败时 fallback 到原始用户消息 + 最近用户上下文。
- [x] **2.7-A 诊断增强**：`RetrievalPlanner` 超时从 8s 调整为 15s；失败时 Debug 输出失败原因和原始响应预览，区分超时、API 错误和 JSON 解析失败。
- [x] **2.7-A Prompt 压缩**：planner system prompt 改为短 JSON 指令，并在请求中设置 `max_tokens=512`，降低输出过长和思维模型超时概率。
- [x] **2.7-A Planner 幻觉抑制**：planner 请求尝试关闭/压低思维链（`reasoning_effort=minimal`、`enable_thinking=false`、`max_completion_tokens`）；prompt 明确禁止回答问题、禁止把角色/关系/职业猜成具体人名，只能抽取对话中显式出现的实体或原样保留含糊短语。
- [x] **2.7-A Retrieval 模型思维参数手动配置**：取消 reasoning 参数自动重试；Retrieval 单例模型连接新增 `reasoningEffort`、`enableThinking`、`maxOutputTokens`，由用户按 API 兼容性手动填写，避免自动 fallback 拉长超时。
- [x] **2.7-A Retrieval 请求字段开关**：Retrieval 模型配置改为先勾选是否发送 `reasoning_effort`、`enable_thinking`、`max_tokens/max_completion_tokens`，再填写参数；未勾选时请求体完全不包含对应字段，避免不存在的结构体导致 400。
- [x] **2.7-A Planner 极简本地语言 JSON**：`RETRIEVAL_PLANNER_SYSTEM_PROMPT` 改为中文短指令，要求 topic/queries/entities 使用原文语言；输出 schema 压缩为 `{"t":[],"q":[],"e":[]}`。小模型只提炼话题、检索词和显式实体，不再输出 `r/c`，不再判断可信度或是否召回。
- [x] **2.7-A Planner Raw Output 常显**：无论 planner JSON 解析成功、结果为空、失败或超时，Debug 日志都显示 `Raw planner response preview`；成功路径也保留原始输出，方便排查“小模型输出正常但解析/提炼为空”的问题。
- [x] **2.7-A Planner 新短 schema 解析修复**：修复 `ignoreUnknownKeys=true` 导致 `{"t":[],"q":[],"e":[]}` 被旧 `RetrievalPlan` 结构抢先解析为空对象的问题；现在短 schema 手动解析优先，`t` 进入当前话题/召回目标，`q` 进入 planned queries，`e` 进入实体和实体 query。
- [x] **2.7-A Planner 旧 schema 废弃**：Planner parser 不再兼容 `conversation_state/recall_decision/queries` 旧结构，只接受当前短 schema `{"t":[],"q":[],"e":[]}`；其他 JSON 直接视为解析失败并显示 Raw Output，减少误解析和调试歧义。
- [x] **2.7-A Planner 解析严格映射**：修正 Debug 中“解析结果与 Raw Output 对不上”的问题；`t` 只映射到 topic/targets，`q` 只映射到 planned queries，`e` 只映射到 entities，不再把 topic/entity 派生为额外 query，也不再把 query 合入 targets。
- [x] **2.7-A Planner 三字段重写**：`RetrievalPlan` 数据结构改为只包含 `topic`、`queries`、`entities` 三个参数；废弃 `ConversationState`、`RecallDecision`、`RetrievalQuery` 旧内部结构。Debug 和 RAG query 直接展示/使用这三项，避免解析层再次派生或改写小模型输出。

## 2026-06-03 重新生成时 ChatMemory 清理修复

- 问题：重新生成最新 AI 回复时，旧 AI 消息可见内容被删除，但对应 `CHAT_MEMORY` RAG chunk 由后台异步删除；新一轮 `sendMessageInternal` 可能先开始 RAG 检索，导致旧回复仍被检索进上下文。
- 问题：聊天记忆 chunk 可能包含多条消息，`messageId` 只保存第一条消息 ID，实际关联消息列表在 `metadata["messageIds"]`；旧 `deleteChunksByMessageId` 只匹配 `chunk.messageId`，无法删除“包含目标消息但目标消息不是第一条”的 chunk。
- 修复：`RagRepository.deleteChunksByMessageId` 同时匹配 `chunk.messageId` 与 `metadata["messageIds"]`。
- 修复：`regenerateLastResponse` 改为先删除可见 AI 消息并刷新 UI，再同步等待 `ragManager.deleteMemoryForMessage(lastMsg.id)` 完成，随后才复用上一条用户消息重新生成，避免旧 AI 回复/包含旧回复的多消息 chunk 进入本轮检索。

## 2026-06-03 Phase 2.7-C 多路召回首版

- 目标：把当前单一路径 `combinedScore = vector + lexical` 升级为更可解释的多路候选池，先提升召回覆盖率，不立即接外部 reranker。
- 实现：document 和 chat_memory 均分别生成“向量候选池”和“词面候选池”；每路候选数限制为 `max(topK * 6, 30)`，防止百万字级设定下候选池无界膨胀。
- 合并：使用 RRF (`1 / (60 + rank)`) 合并向量排名和词面排名；最终排序优先 RRF，其次 `vectorScore + lexicalScore`。
- 过滤：document 仍使用 `docRagSimilarityThreshold` 或 `DOCUMENT_LEXICAL_ACCEPT_THRESHOLD`；chat_memory 使用 `memoryRagSimilarityThreshold` 或较低的 `MEMORY_LEXICAL_ACCEPT_THRESHOLD`。
- Debug：document/chat_memory 均输出 multi-route top scores，包含 RRF、combined、vector score/rank、lexical score/rank；document 继续输出 top20 来源分布。

## 2026-06-03 Phase 2.7-D 本地重排首版

- 调整：Planner 成功返回 `topic/queries/entities` 时，RAG embedding query 不再包含 `Current user message`，只使用 planner 三字段；仅 planner 失败、超时、解析失败或三字段为空时，才 fallback 到原始用户消息 + 最近用户上下文。
- 目标：在不接外部 reranker 的前提下先降低“少数文档垄断最终注入”的风险。
- 实现：document 最终注入前增加来源多样性本地重排；同一来源（`sourceLabel` 文件名前缀 / `fileName` / `originalDocId`）最多占约 `topK / 2`，若名额不足再按原排序回填。
- Debug：新增 `Document final source distribution after local rerank`，用于对比 top20 候选来源分布和最终注入来源分布。

## 2026-06-03 Phase 2.7-E 低打扰注入首版

- 目标：降低 RAG 内容对角色回复的强迫感，避免模型生硬复述检索块或把弱相关内容当作必须回答的事实。
- 实现：`PromptAssembler` 的 RAG 注入从“文档参考/历史记忆参考”改为“可参考设定/记忆卡片”。
- 注入规则：在 RAG 区块顶部明确说明“只在与当前对话自然相关时使用；不要逐条复述，不要强行提及来源，不要把不确定信息当成当前事实”。
- 卡片字段：每个 chunk 注入为 `[卡片 N]`，包含 `类型`（知识库文档/历史对话记忆/角色固定设定）、`来源`、`内容`。
- 限制：单张卡片内容截断到 1800 字，避免超长 chunk 垄断 system prompt。

## 2026-06-03 Phase 2.7-E 注入强度设置

- 新增全局设置：`ragInjectionMode`，支持 `OFF` / `LIGHT` / `STANDARD` / `STRONG`。
- 管理后台设置页新增“RAG 注入强度”滑块。
- 移除重复的“最大注入卡片数”设置；卡片数量由原 document/chat_memory Top-K 控制，并将 UI 文案改为“最多注入知识库文档卡片数 / 最多注入历史记忆卡片数”，避免暴露 Top-K 专业术语。
- 修复：RAG 注入强度滑块使用 `roundToInt()` 读取状态，避免连续滑块小数被 `toInt()` 截断导致“关闭-关闭-轻量-强联想”。
- 调整：`OFF` 模式直接跳过 Retrieval Planner、Embedding 和 RAG 检索，不再浪费 token 或拖慢回复。
- `LIGHT` / `STANDARD` / `STRONG` 使用不同低打扰说明，控制模型使用 RAG 的主动程度。

## 2026-06-03 Phase 2.7 当前状态小结

- 已完成首版链路：Retrieval Planner 三字段抽取 -> planner query embedding -> document/chat_memory 多路召回 -> RRF 合并 -> 本地来源多样性重排 -> 低打扰卡片注入。
- 已完成控制项：RAG 注入强度 `OFF/LIGHT/STANDARD/STRONG`；OFF 直接跳过 RAG；document/chat_memory 最多注入卡片数分别由原 Top-K 控制。
- 已完成 Debug：Raw planner output、解析后三字段、RAG query text、多路候选分数/排名、来源分布、最终召回块。
- 下一阶段优先级：MemoryWriter 暂停；准确率回归集暂缓；性能/速度策略已完成首版。现在进入 2.7-H 正式数据结构迁移，先替换运行期检索结果结构，不动底层向量索引持久化。
- [x] **2.7-C：多路召回上线（首版）**：document/chat_memory 分别走向量候选池与词面候选池，每路候选规模受限，并用 RRF 合并排名。
- [x] **2.7-D：本地重排与来源多样性（首版）**：基于 RRF + combined score 排序，并加入 document 来源多样性限制，避免少数文档长期垄断最终注入。
- [x] **2.7-E：低打扰注入与 UI 设置（首版）**：RAG 注入改为“可参考设定/记忆卡片”；支持关闭、轻量、标准、强联想四档；Top-K 文案改为“最多注入卡片数”。
- [~] **2.7-F：长期记忆写回（暂停）**：不做独立 MemoryWriter；当前继续使用聊天记录向量化作为记忆库。
- [x] **2.7-G：性能与降级首版**：百万字级索引相关的增量索引、批量 embedding、并发限制、后台状态、候选裁剪、OFF 跳过和失败 fallback 已完成；固定回归集暂缓。
- [x] **2.7-H：正式数据结构迁移（运行期首版）**：引入 `RetrievalIntent` / `RetrievedKnowledgeCard`；`PromptAssembler` 不再直接依赖 `VectorChunk` 作为 RAG 注入结构；底层索引与存档快照仍保留 `VectorChunk`。

#### 验收口径

- 检索准确率：明确人名/组织/关系提及时，相关文档应进入最终注入卡片；无关设定不得长期垄断 topK。
- 检索速度：普通聊天轮次不因 RAG 明显阻塞；强检索轮次应有超时降级，不卡 UI。
- 可扩展性：百万字级角色设定和长期记忆库可增量索引、可中断恢复、可显示状态。
- 可解释性：Debug 能解释为什么检索、搜了什么、召回什么、为什么注入、为什么写回或不写回。
---

## 项目结构设计

```
ChatBar/
├── app/
│   └── src/main/
│       ├── java/com/example/chatbar/
│       │   ├── ChatBarApp.kt                 # Application 类
│       │   ├── MainActivity.kt
│       │   ├── Navigation.kt                 # Navigation 3 页面路由
│       │   ├── NavigationKeys.kt             # 路由 Key 声明
│       │   ├── DebugConfig.kt                # 全局 Debug 配置开关
│       │   ├── data/
│       │   │   ├── local/
│       │   │   │   └── entity/               # 实体定义 (CharacterCard, ChatSession, ChatMessage, VectorChunk 等)
│       │   │   └── repository/               # 数据仓库实现
│       │   ├── domain/
│       │   │   ├── prompt/
│       │   │   │   └── PromptTemplates.kt    # 集中维护所有硬编码提示词模板（角色扮演系统指令、Retrieval Planner、图片描述等）
│       │   │   ├── rag/
│       │   │   │   ├── ChunkingEngine.kt      # 自动语义分块
│       │   │   │   ├── EmbeddingService.kt    # Embedding 调用
│       │   │   │   ├── VectorSearchEngine.kt  # 向量搜索
│       │   │   │   └── RagManager.kt          # RAG 编排
│       │   │   └── chat/
│       │   │       ├── PromptAssembler.kt     # Prompt 组装与系统指令模板
│       │   │       ├── ContextWindowManager.kt # 上下文窗口管理
│       │   │       └── StreamingChatService.kt # 流式聊天
│       │   ├── ui/
│       │   │   ├── theme/                     # 主题色 (ChatBarTheme 暗色)
│       │   │   ├── chat/
│       │   │   │   ├── ChatScreen.kt
│       │   │   │   ├── ChatSettingsDialog.kt  # 会话设置与存档
│       │   │   │   ├── ChatViewModel.kt
│       │   │   │   └── DebugLogDialog.kt      # 开发者调试面板 UI
│       │   │   ├── character/                 # 角色卡编辑页面
│       │   │   ├── format/                    # [NEW] 格式卡片编辑器页面
│       │   │   └── home/                      # 首页聊天列表
│       │   └── utils/
│       │       └── DebugLogManager.kt         # 调试日志线程安全管理器
│       └── res/
├── doc/
│   ├── AppDesignDocument.md                   # 原始设计文档
│   ├── implementation_plan.md                 # 项目全局实施计划（本文件）
│   └── task.md                                # 任务清单
```

---

## 核心模块设计

### 提示词结构化组装（Phase 2.6 新增）

```
+-------------------------------------------------------+
|  角色描述 & 设定 (Character Profiles)                   |
+-------------------------------------------------------+
|  RAG 相关上下文检索 (Setting & Memory Context)         |
+-------------------------------------------------------+
|  格式卡模板约束 & 回复约束 (Format / Length / Lang)   |
+-------------------------------------------------------+
|  会话级补充设定 (Supplementary Settings)              |
+-------------------------------------------------------+
|  个人人设 (Personal Settings, 含 Player Name)        |
+-------------------------------------------------------+
|  系统核心指令 (System Instruction)                     |  <- 靠最下，强化Recency Bias
+-------------------------------------------------------+
                                  |
                                  v
                    [ 全局 $username 字符替换 ]
```

---

### Phase 3：剩余体验优化 - [规划中]

- [x] **扮演风格切换（普通 / 激进）**：
  - 入口：在设置页的“对话大模型选择”一栏新增“扮演风格”选项。
  - 选项：`普通`、`激进`。
  - Prompt 映射：两种风格分别对应 `PromptTemplates` 中两段不同的 `ROLEPLAY_SYSTEM_INSTRUCTION`。
  - 目标：允许用户在不切换模型、不修改角色卡和格式卡的情况下，快速切换角色扮演强度。
  - 实现约束：风格选择应进入当前对话/全局设置的有效配置链路，并在组装 System Prompt 时实时生效；避免把风格文本硬编码在 UI 层。

#### 2026-06-04 实现记录
- `AppSettings` 新增 `roleplayStyle`，默认 `NORMAL`，旧配置兼容。
- `PromptTemplates` 保留原 `ROLEPLAY_SYSTEM_INSTRUCTION` 作为普通风格，新增 `ROLEPLAY_SYSTEM_INSTRUCTION_AGGRESSIVE` 作为激进风格，并提供 `roleplaySystemInstruction(style)` 统一选择。
- 管理后台设置页在对话模型设置区域新增“扮演风格”选项，支持 `普通` / `激进` 切换，并随全局设置保存。
- `PromptAssembler.assembleSystemPrompt()` 新增 `roleplayStyle` 参数，组装 System Prompt 时从 `PromptTemplates` 获取对应系统核心行为指令。
- `ChatViewModel` 每次发送前读取最新 `AppSettings.roleplayStyle` 并传入 Prompt 组装器，因此修改后对当前对话实时生效。

- [x] **视觉辅助模型图片描述压缩**：
  - 问题：纯文本聊天模型 + 多模态辅助模型时，辅助模型会把图片转成文本描述；描述过长会浪费 token，并污染后续聊天上下文。
  - Prompt：图片描述提示词统一维护在 `PromptTemplates.IMAGE_DESCRIPTION_PROMPT`，要求中文、短描述、只保留影响角色扮演理解的关键信息，不扩展剧情、不猜测不可见信息。
  - MaxToken：新增 `PromptTemplates.IMAGE_DESCRIPTION_MAX_TOKENS = 120`，`StreamingChatService.describeImage()` 调用 `buildRequestBody(... maxTokens=...)`，同时写入 `max_tokens` / `max_completion_tokens`。
  - 兜底：即使模型忽略输出上限，也会通过 `compactImageDescription()` 压缩空白并截断到 220 字符以内，再传给聊天模型。

## Verification Plan

### 自动化测试
- 运行 `./gradlew compileDebugKotlin` 验证编译。
- 运行 `./gradlew assembleDebug` 进行打包测试。

### 手动验证
- **流式输出自由滚动**：进入聊天，发送消息给 AI，在 AI 流式响应过程中，确认界面不产生任何滚动跳转。当 AI 吐字完毕，完整消息写入数据库（历史列表数量增加）后，确认界面自动滚动到底部。
- **RAG 向量块删除与索引**：
  1. 在角色编辑添加文档，保存后删除文档，进入调试控制台查看该文档关联的向量块是否清空（通过 "originalDocId" 查询）。
  2. 保存带有多个自定义文档的角色卡，确保所有关联文档的 RAG 分块数不为 0。
- **System Prompt 拼装顺序与替换**：在全局设置设置玩家名称为 "旅行者"，并在补充设定中加入 "$username 正在做某事"，发起对话后在调试控制台检查拼装出的 Prompt：确认系统指令沉底，且 "$username" 成功替换为了 "旅行者"。
- **聊天相册选取图片**：在聊天框点击图片附件，调出系统相册，任意选定一张图片，确认输入框下方预览成功加载，且消息能正常发送与识别。
- **防误触弹窗**：依次点击删除角色卡、格式卡、模型、文档、子人物、存档快照，确认均有二次确认对话框，选择“取消”不执行删除，选择“删除”正常移除。
- **格式卡编辑**：在管理中对已有的格式卡点击“编辑”按钮，能正确进入 `FormatCardEditScreen` 展示原有内容，修改并保存后数据成功在列表更新。

## 2026-05-31 RAG 文档索引流程修复

- 问题：角色文档上传时立即为每个文档调用 Embedding 建索引，保存角色卡时又全量重建；保存弹窗与远程索引绑定，网络慢或失败时表现为“创建索引并永远加载”。
- 问题：改为 document/chat_memory 分离召回后，若文档索引未完成或未成功落库，document 候选块会是 0，最终召回块数也可能一直为 0。
- 修复方向：上传/批量导入只保存文档文件和角色卡文档列表；保存角色卡立即完成 UI 保存，再启动应用级后台 RAG 索引任务。
- 状态可见性：`CharacterCard` 新增 `ragIndexStatus` / `ragIndexDone` / `ragIndexTotal` / `ragIndexMessage` / `ragIndexedAt`，持久化每张角色卡索引状态与进度。
- UI：角色编辑页显示 RAG 进度条和状态；管理页角色卡列表、新建对话角色选择列表显示每张卡的 RAG 状态。
- 调试：聊天 RAG Debug 日志在角色卡索引未完成时输出当前状态，解释为什么文档候选块或召回块为 0。
- 测试辅助：角色编辑页文档区新增一键清空当前角色卡全部文档和对应 document RAG 块的按钮，带二次确认。
- 复测修正：清空文档改为先清空 UI 状态并立即保存空文档列表，再用应用级后台任务删除本地文件和 document RAG 块；避免大量文档/向量块清理过慢导致确认后界面长期无变化。
- 验证：`compileDebugKotlin` 已通过；仍需真机验证上传、保存后后台状态推进、提前开聊的 Debug 提示、清空按钮。

## 2026-06-01 RAG 索引性能与 chunk 语义优化

- 需求：文档 RAG 索引需要支持增量重建，只处理新增/修改/失败文档；已成功索引的文档在闪退或失败后不重复浪费。
- 增量策略：`DocumentInfo` 新增 `contentHash` / `indexedHash` / `ragStatus` / `ragChunkCount` / `ragIndexedAt` / `ragError`；索引前一次性读取当前角色卡 document chunks，按 `originalDocId` 建 map，再逐文档算 hash 对比。
- 并发策略：Embedding API 已支持数组批量输入；文档级索引新增并发限制，默认同时索引 2 个文档，避免 100+ 文档完全串行，同时避免手机和 API 被打满。
- 进度策略：UI 状态可按文档完成更新；角色卡 JSON 进度每 3 个文档或失败/完成时保存一次，减少频繁文件 IO。
- chunk 策略：文档 chunk 默认改为 700 字、overlap 70 字；避免原 500/50 切太碎造成角色关系、设定上下文断裂。
- 来源标签：文档 chunk embedding 文本和召回正文前加入 `【来源】文件名 > 标题路径`；Markdown 使用 `# / ## / ###` 章节路径，TXT 支持简单标题行推断；metadata 精简保留 `originalDocId` / `contentHash` / `sourceLabel` 等必要字段。
- 预期效果：未修改文档跳过索引；失败重试只补失败/变化部分；召回给 AI 时带来源，降低跨文档/跨章节关系误套风险。

## 2026-06-01 Document RAG 候选存在但召回为 0

- 现象：Debug 显示 `document candidates=714`，说明文档索引已成功；但 document 召回块数仍为 0。
- 判断：问题位于相似度阈值过滤阶段，不是索引缺失。默认/既有 `docRagSimilarityThreshold=0.7` 对当前 embedding 分布可能过高。
- 修复：新增 document top similarity 分布日志，显示前 5 个候选的来源和分数，方便调阈值。
- 修复：document 召回新增保底逻辑：若存在 document 候选但没有任何块达到阈值，则取相似度最高的 `docRagTopK` 个，并写入 `Document RAG threshold fallback` Debug 日志。
- 调整：新默认文档阈值从 `0.7` 降到 `0.35`；已有用户配置不会被强制覆盖，保底逻辑负责立即避免 0 召回。

### 2026-06-01 修正：RAG 相似度排序语义与 embedding 指纹

- 问题：保底召回会在所有分数低于阈值时仍取前 `docRagTopK`，容易掩盖“相似度全部异常/为 0”的真实问题。
- 修复：document RAG 改回严格语义：先从所有 document chunks 按相似度降序取 `docRagTopK`，再按 `docRagSimilarityThreshold` 剔除。
- 问题：文档索引可能由旧 embedding 模型建立，而查询使用当前 embedding 模型；维度不同会导致相似度全为 0，维度相同但模型不同也会严重失真。
- 修复：文档向量 metadata 新增 `embeddingKey = hash(baseUrl + modelName)`；查询时只使用与当前 embedding 配置一致的 document chunks。
- 修复：增量索引判断也纳入 `embeddingKey`，embedding 模型变化时即使文档内容 hash 未变，也会重建该文档索引。
- Debug：新增被忽略的 legacy/mismatch document chunks 数量、top10 相似度与 top20 来源分布，便于确认是否全库检索和是否集中在少数文档。

## 2026-06-01 对话使用最新设置

- 问题：新建会话会把当时的全局默认大模型和格式卡 ID 复制进 `ChatSession`，后续修改全局默认时，旧对话仍使用旧 ID。
- 问题：发送消息时使用 ViewModel 内存里的 `_session` / `_characterCard`，若管理页修改了角色卡、模型、格式卡或设置，当前聊天页不一定立刻重读最新数据。
- 修复：新建会话不再复制默认 `modelId` / `formatCardId`；会话字段为空表示跟随最新全局默认。
- 修复：每次发送消息前从仓库重读最新 `ChatSession`、`CharacterCard`、`AppSettings`，再解析模型、嵌入模型、格式卡、玩家设定与上下文窗口。
- 修复：格式卡为空时回落到最新 `AppSettings.defaultFormatCardId`；上下文窗口使用最新 `AppSettings.defaultContextWindowSize`。
- 修复：聊天设置弹窗打开时刷新模型和格式卡列表，确保新增/修改后的条目可见。
- 调整：新默认阈值改为 document `0.55`、chat_memory `0.35`。

## 2026-06-01 首页旧聊天长按操作

- 问题：旧聊天无法从首页删除，缺少常见社交媒体式长按操作。
- 修复：首页会话条目支持长按；长按弹出操作对话框，提供置顶/取消置顶、删除聊天。
- 删除：删除前二次确认；确认后删除会话、该会话全部消息，并清理对应 `CHAT_MEMORY` RAG 向量块。
- 复测修正：`CHAT_MEMORY` 向量清理可能扫描大量 JSON，不能阻塞会话删除；改为先删除会话并刷新列表，消息和 RAG 记忆清理随后后台执行。
- 置顶：调用已有 `pinSession` / `unpinSession`，列表继续按置顶优先和更新时间排序。

## 2026-06-01 RAG 召回上下文与来源一致性调查

- 现象：`ignored legacy document chunks=0` 后，document chunk 数量正常，但当前聊天讨论乐队时，召回仍会跑到无关文档。
- 判断：检索 query 只使用本轮用户输入 `content`。当用户发送“还有什么值得注意的对手吗？”这类追问时，query 不包含“乐队/角色/上下文关键词”，向量检索无法知道对话主题，容易召回语义泛化的无关块。
- 修复：RAG query 改为“当前用户消息 + 最近 6 条非 system 对话上下文”，每条上下文截断 300 字，总 query 截断 2400 字；既保留追问语义，也避免 embedding 请求过长。
- Debug：新增 `RAG query text` 日志，显示实际送去 embedding 的 query 摘要；新增 document chunk 来源一致性检查，统计正文 `【来源】...` 与 metadata `fileName/sourceLabel` 不一致数量。
- Debug：document top scores 改为逐行输出 `score | meta=... | content=...`，方便判断排序来自哪些文档，以及是否存在索引内容和元数据错配。

## 2026-06-02 RAG 召回准确率仍为 0：混合召回修正

- 现象：用户明确提到人名时，document 召回仍集中在少数固定文档，未命中对应人名文档。
- 判断：纯向量排序在当前 embedding/文档分布下不可靠；并且上一版 query 纳入 assistant 上下文，若 assistant 已被错误 RAG 污染，会把错误文档关键词继续带入下一轮检索，形成固定文档自我强化。
- 修复：RAG query 只保留当前用户消息 + 最近 6 条用户消息，不再纳入 assistant 消息，避免错误回复污染检索。
- 修复：document 排序改为混合评分 `combinedScore = vectorScore + lexicalScore`；lexicalScore 从 query 中提取英文/数字 token 与中文 2-6 字 ngram，对 chunk 正文和 metadata 做词面命中加分。
- 修复：document 通过条件改为 `vectorScore >= docThreshold || lexicalScore >= 0.24`，确保明确人名/专名命中时可以进入召回，即使向量相似度异常偏低。
- Debug：top scores 输出 `combined/vector/lexical` 三个分数，方便区分是向量命中还是词面命中。

## 2026-06-02 RAG 长期重构方向：Memory-aware Agentic RAG

- 结论：当前 RAG 更像普通问答检索器：用户原话 -> embedding -> topK -> 注入。它不匹配角色扮演的“联想设定/想起记忆”目标。
- 目标：构建“联想式记忆 RAG”。聊天流中持续识别话题、实体、用户意图、隐含任务、可能关联知识点；主动生成检索意图；并行召回文档知识库和长期记忆；重排后低打扰注入。
- 核心差异：不再每句话直接拿用户原文向量检索 topK，而是先做 retrieval gate 和 query reformulation。只有当前轮确实可能需要长期知识/记忆时才检索。
- 参考思路：对话检索里的真实搜索意图依赖前文，需要 query reformulation，而不是直接搜当前轮原话。ConvGQR 类工作使用生成式模型改写对话查询，并结合潜在答案生成更强检索 query。

### 设计草案

- `RetrievalGate`：输入最近对话、当前用户消息、角色卡状态，输出是否检索、检索类型、置信度、原因。避免低价值轮次污染召回。
- `RetrievalIntentExtractor`：抽取当前话题、实体、人名/组织/地点/作品、关系、时间、用户意图、隐含任务、可能关联知识点。
- `QueryReformulator`：基于抽取结果生成多条 query：实体精确 query、关系 query、话题 query、潜在答案 query、历史记忆 query。
- `MultiRouteRetriever`：并行跑 document 向量召回、document 词面召回、chat_memory 向量召回、chat_memory 词面召回；保留当前混合召回作为底层检索器。
- `Reranker`：融合向量分、词面分、实体命中、来源多样性、最近已注入惩罚、文档状态、chunk 质量，输出最终 3-12 条知识/记忆卡片。
- `ContextInjector`：低打扰注入 system prompt。格式偏“可参考记忆/设定”，避免强迫模型生硬复述。
- `MemoryWriter`：每轮对话后判断是否写入长期记忆，抽取稳定事实、用户偏好、剧情状态、关系变化；避免把临时闲聊和错误输出写入记忆。
- `DebugPanel`：展示 gate 判定、抽取实体、生成 queries、各路召回候选、rerank 分数、最终注入卡片、写回决策。

### 迁移计划

- Phase A：保留现有混合检索，新增 debug-only `RetrievalIntent` 结构和日志，不影响回复。
- Phase B：接入轻量 LLM query reformulation，只生成 queries，不做自动写回；失败时回退当前混合检索。
- Phase C：多路召回和 rerank 上线，替换单一路径 topK；加入来源多样性和重复注入惩罚。
- Phase D：低打扰注入格式上线，并提供 UI 开关/强度设置：关闭、轻量、标准、强联想。
- Phase E：长期记忆写回上线；写回需有 gate、置信度和可撤销 debug 记录。
- Phase F：正式清理旧 RAG debug 与临时阈值逻辑，保留混合检索作为底层 retriever。
## 2026-06-04 设计文档对照补项：聊天输入全屏编辑器
- 来源：`AppDesignDocument.md` 聊天页要求“文本编辑栏左侧扩展按钮，展开全屏文本编辑器；左下角退出，右下角发送；可在光标处插入图片，与文字一起发送”。
- 缺口：当前聊天底部输入栏只有添加图片、文本框、发送按钮，没有全屏输入入口；只有历史消息编辑全屏层。
- 实现：`ChatScreen` 输入栏新增全屏展开按钮；打开后进入 `InputComposeFullScreen`，复用当前未发送文本和图片列表，支持插入/删除图片、左下退出、右下发送。
- 交互：全屏发送后会调用同一条 `sendMessage()` 链路，保留“空消息也可发送，让 AI 继续生成”的既有逻辑；发送完成后清空输入并自动退出全屏。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照剩余高优先级缺口
（当前高优先级显性缺口已转入补项记录；后续继续做完成度审计。）

## 2026-06-04 设计文档对照补项：聊天背景
- 来源：`AppDesignDocument.md` 要求角色卡可设置默认聊天背景，会话设置可选择当前聊天背景。
- 实现：`CharacterEditScreen` 增加默认聊天背景图片选择/清除，保存到 `CharacterCard.chatBackground`；`ChatSession` 增加 `chatBackground` 覆盖字段；`ChatSettingsDialog` 增加当前会话背景图片选择/恢复默认；`ChatScreen` 渲染会话背景优先，否则使用角色卡默认背景。
- 交互：图片沿用现有 `copyUriToLocalFile()` 复制到应用私有目录；聊天背景上叠加暗色遮罩，保持气泡文本可读。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：角色卡导入导出
- 来源：Phase 2 与 `AppDesignDocument.md` 要求角色卡支持自定义 JSON 格式导入导出。
- 实现：管理后台角色卡页顶部增加导入按钮；每张角色卡操作区增加导出按钮。导出使用系统文件创建器生成 `.chatbar-character.json`；导入使用系统文件选择器读取 JSON。
- 格式：导出包包含 `schemaVersion`、`exportedAt`、完整 `CharacterCard`、自定义文档正文列表、角色头像/聊天背景/人物设定图的 base64 映射。
- 导入：导入时生成新的角色卡 ID，避免覆盖当前卡；图片写入本机 `filesDir/images`，文档写入 `filesDir/documents`，并重写文档路径。导入后的 RAG 状态标记为 `NOT_INDEXED`，提示后续重建索引。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：模型高级配置模板分享
- 来源：Phase 2 要求“模型高级配置：更多预设模板 + 参数模板分享”；当前已有 `ModelTemplate` 和自定义参数 UI，但缺少模板导入/导出。
- 实现：管理后台模型分页顶部增加导入模型模板按钮；每个对话模型条目增加导出模板按钮。导出使用系统文件创建器生成 `.chatbar-model-template.json`；导入使用系统文件选择器读取 JSON 并创建新模型配置。
- 格式：导出包包含 `schemaVersion`、`exportedAt`、显示名、BaseURL、模型名、多模态标记、模板类型、自定义参数、reasoning/thinking/max token 结构体设置。
- 安全策略：模型模板导出不包含 `apiKey`，避免分享时泄露密钥；导入的新模型 `apiKey` 留空，用户需打开编辑页填入后再使用。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：近期多回复切换
- 来源：`AppDesignDocument.md` 要求支持重新生成当前 AI 回复，并支持在当前 AI 多回复之间切换。
- 边界：仅支持仍在 normal context window 内、且尚未被索引成 `CHAT_MEMORY` RAG chunk 的近期 AI 回复。一旦消息进入长期记忆库，禁用继续生成候选与切换，避免快速切换与慢速 RAG 重建冲突。
- 实现：复用 `ChatMessage.alternatives/currentAlternativeIndex`。重新生成最新 AI 回复时，不再删除原消息，而是排除该回复作为上下文重新生成，并把新文本追加到同一消息的候选列表；最多保留最近 5 个候选。
- 速度：左右切换只更新 `currentAlternativeIndex` 并刷新消息列表，不执行检索、不重建 RAG、不删除向量 chunk。
- UI：AI 气泡在存在多个候选时显示 `< 2/3 >` 切换条；最新 AI 回复的长按菜单仍使用“重新生成”入口追加新候选。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：起始台词
- 来源：`AppDesignDocument.md` 要求玩家首次开始与角色聊天、或清除聊天记忆重新开始时，角色发送起始台词作为对话开始点。
- 缺口：`CharacterCard.greeting` 字段和角色卡编辑 UI 已存在，但 `HomeViewModel.createSession()` 只创建空会话；`ChatViewModel.clearHistoryAndMemory()` 清空后也不重新插入开场白。
- 实现：新建会话后，如果角色卡 `greeting` 非空，立即写入一条 `ASSISTANT` 消息；清空当前会话历史和 RAG 记忆后，同样重新写入该角色卡开场白。
- 说明：起始台词作为普通近期 assistant 消息进入 normal context window；只有之后离开上下文窗口才会按既有策略进入 `CHAT_MEMORY`。

## 2026-06-04 Phase 2 旧勾选状态覆盖
- 对话编辑：已完成首版，支持长按任意消息编辑/删除，保存后更新普通上下文或对应 RAG 单消息 chunk。
- 角色卡导入导出：已完成首版，见“角色卡导入导出”补项记录。
- 模型高级配置模板分享：已完成首版，见“模型高级配置模板分享”补项记录。
- 聊天设置背景自定义：已完成首版，见“聊天背景”补项记录。

## 2026-06-04 设计文档对照补项：角色卡保存校验
- 来源：`AppDesignDocument.md` 要求除自定义信息/文档外，角色卡项目必须填写；无法保存时显示具体原因。
- 缺口：此前角色卡保存按钮主要只检查卡片名称，可能保存缺少开场白、人物设定、语气、经历等核心设定的空卡。
- 实现：`CharacterEditViewModel.validateForSave()` 输出明确错误列表；保存按钮点击时先校验，失败弹出原因列表，成功才保存并触发既有后台 RAG 索引流程。
- 校验范围：卡片名、起始台词、至少一个人物；每个人物需有名称、简介、外观文字或外观图、说话语气、背景经历。自定义文档仍为可选。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：存档会话设置快照
- 来源：`AppDesignDocument.md` 要求存档可保存玩家人物设定、补充设定、当前聊天历史、角色记忆库，并作为聊天进度读档恢复。
- 缺口：此前 `SaveSlot` 保存了消息、RAG chunk、玩家名/人设/补充设定，但没有保存当前会话模型、格式卡、回复长度、回复语言、聊天背景、上下文窗口等会话设置；读档后这些状态会回到当前会话值，导致存档不是完整进度快照。
- 实现：`SaveSlot` 增加 `modelId`、`formatCardId`、`replyLength`、`replyLanguage`、`chatBackground`、`contextWindowSize`；创建存档时写入当前会话设置；读档时连同消息和记忆 chunk 一起恢复。
- 兼容：新增字段均可空或有默认值，旧存档仍可读取；旧存档读档时没有的字段保持当前会话默认。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过。

## 2026-06-04 设计文档对照补项：交付与打包说明
- 来源：`AppDesignDocument.md` 最终交付要求包含完整 Android 项目源码、可直接安装的 APK 文件、打包 APK 的步骤说明。
- 缺口：已有 `doc/walkthrough.md` 记录阶段性构建结果，但根目录缺少面向用户的稳定打包/安装说明。
- 实现：新增根目录 `README.md`，记录环境要求、`assembleDebug` 打包命令、APK 输出路径、ADB 安装和启动命令、验证命令，以及 Android 8.0 (`minSdk 26`) 说明。

## 2026-06-04 设计文档对照补项：聊天页底部导航
- 来源：`AppDesignDocument.md` 要求聊天页底部也有“聊天/管理”两个导航选项。
- 缺口：此前底部导航只在首页和管理页显示，进入具体聊天会隐藏底栏。
- 实现：`MainNavigation` 在 `ChatRoute` 下继续显示 `BottomNavBar`，并将当前选中项视作“聊天”；点击“管理”会清空当前导航栈并进入管理页，点击“聊天”回到首页。`ChatScreen` 接收外层 `innerPadding`，避免输入框被底栏遮挡。

## 2026-06-04 交互回归修正：全屏输入、多回复切换、聊天页底栏、中文校验
- 全屏输入编辑器：`showInputFullScreen` 纳入系统栏隐藏条件，和消息编辑全屏层一致，避免顶部系统栏遮挡编辑内容。
- 聊天页底部导航：实际体验不佳，已回退 `ChatRoute` 下的底栏显示；底栏仍仅保留首页/管理页。
- 多回复切换：切换控件从独立大按钮行改为贴近时间的小型 `< 1/2 >` 控件；切换时同步更新 `content` 与 `currentAlternativeIndex`，避免 UI 或后续上下文仍读取旧回复。
- 角色卡保存校验：校验失败原因改为中文，覆盖角色卡名称、开场白、人物数量、名称、人物设定、外貌、说话风格、背景设定。

## 2026-06-04 交互回归修正 2：顶栏、重生成响应、多回复切换
- 顶栏空白：Material3 `TopAppBar` 默认包含 status bar inset，导致所有顶栏上半部分出现过高空白；已统一设置 `windowInsets = WindowInsets(0,0,0,0)`，让顶栏高度回到正常应用栏尺寸。
- 全屏输入/消息编辑：聊天页 `TopAppBar` 显示条件改为 `!isFullScreenEditor`，输入全屏和消息编辑全屏都会隐藏应用顶栏；系统栏隐藏逻辑继续复用 `WindowInsetsControllerCompat`。
- 重新生成延迟：`regenerateLastResponse()` 改为先立即移除旧 AI 回复并显示空 streaming placeholder，再检查是否近期未入 RAG 并启动生成；失败时恢复旧消息并提示，避免点击后长时间无反馈。
- 多回复切换：切换时先乐观更新 `_messages`，再落库；`ChatBubble` 用 `key(message.id, currentAlternativeIndex, displayContent)` 强制 AndroidView/Markwon 按候选回复重建，避免按钮有反馈但文本和序号不刷新。

## 2026-06-04 交互回归修正 3：输入全屏复用、重新生成动画、多回复切换延迟
- 输入全屏乱码：废弃单独 `InputComposeFullScreen` 调用路径，输入栏全屏直接复用现有 `MessageEditFullScreen` 组件，避免两套 UI 文案/样式漂移。
- 重新生成生成态：点击重新生成后只设置 `_isResponding=true` 且 `streamingMessage=null`，让聊天页走既有 `TypingIndicator` 三点动画；不再显示空 assistant 气泡。
- 多回复切换慢：原流程为 `getMessage()` 文件读 -> `isRecentUnindexedMessage()` 读设置/全量消息/RAG chunk -> 更新 UI -> 写文件 -> `refreshMessages()` 全量读，前置 I/O 导致点击后久等。
- 多回复切换新流程：先从 `_messages` 内存取消息，最近性用内存列表 `takeLast(50)` 判断，立即乐观更新 `_messages`；随后只检查 RAG chunk，若已入长期记忆则回滚，否则后台写入仓库，不再立即全量刷新。

## 2026-06-04 RAG chunk 与上下文窗口绑定修正
- 设计原则：`CHAT_MEMORY` RAG chunk 必须严格对应“已离开截断上下文窗口”的消息；UI 与交互不能通过读取 RAG chunk 判断近期状态。
- 多回复切换显示：改为 `默认截断上下文窗口大小` 与当前消息序号比较；只有仍在窗口内且 `alternatives.size > 1` 的 AI 消息才显示切换控件，窗口外旧消息不显示。
- 多回复切换流程：点击后只走内存消息列表判断与更新，立即更新 UI，再写 `ChatMessage` 文件；不再查询 `getChunksByMessageId()`，避免 RAG I/O 导致卡顿。
- 上下文窗口来源：运行期统一使用管理后台 `AppSettings.defaultContextWindowSize`，通过 `ChatViewModel.contextWindowSize` 暴露给 UI；避免 `ChatSession.contextWindowSize` 默认 20 固定盖住全局设置。
- 近期消息编辑：若消息仍在上下文窗口内，只更新消息文件，不检查 RAG chunk；只有窗口外消息才查询/重建对应长期记忆。
- 删除消息：按上下文窗口判断是否需要删除长期记忆；窗口内消息删除不触碰 RAG，窗口外消息才调用 `deleteMemoryForMessage()`。

## 2026-06-04 Phase 3 体验优化：会话设定与全局系统设置整理
- 会话设定：按“对话模型、格式卡、回复长度/语言、扮演风格、补充设定、玩家名称覆盖、玩家设定覆盖、聊天背景覆盖、清空历史、存档/读档”顺序整理；新增会话级 `roleplayStyle` 覆盖。
- 扮演风格：`ChatSession` 与 `SaveSlot` 增加 `roleplayStyle`；组装 prompt 时优先使用会话覆盖，否则使用全局设置；存档/读档会保存并恢复该设置。
- 全局系统设置：按“玩家全局名称、玩家全局设定、默认对话模型、默认格式卡、扮演风格、保留上下文条数、联想专注强度、文档卡片联想数量/门槛、记忆卡片联想数量/门槛”重排。
- 设置页隐藏项：全局设置页不再显示默认 Retrieval/Embedding 选择；联想规划模型与向量联想模型只在模型管理分页创建/编辑。
- 文案：`Context Window` 改为“保留上下文条数”；`TopK` 改为“文档/记忆卡片联想数量”；`Similarity Threshold` 改为“联想匹配度门槛”；`RAG 注入强度` 改为“联想专注强度”；`Retrieval Model` 改为“联想规划模型”；`Embedding` 改为“向量联想模型”。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过；仅剩既有 Compose/Icon deprecation warnings。
- 复测补做：上次只完成顺序/文案，未落实 UI 整理方案。现补：全局设置按“玩家 / 默认对话 / 联想”分区；“联想”默认折叠，降低初学复杂度；会话设置增加分隔线，外观/危险操作独立呈现；聊天背景相关英文文案改中文；保存入口文案更明确。

## 2026-06-05 Phase 3 视觉美化：柔和社媒风格首版
- 目标：按用户选定的 C 风格重构外观，不改功能逻辑；方向为“柔和社媒”：暖浅底、薄荷主色、珊瑚强调、石墨文字、薄分割线、轻表面。
- 主题：`Color.kt` 从暗色紫蓝系改为浅色柔和社媒色板；`Theme.kt` 改用 `lightColorScheme`，让 Material3 控件默认适配浅色主题。
- 聊天：用户气泡薄荷色、AI 气泡暖白；AI 文本改石墨色；气泡半径收敛到 8dp；输入框改暖灰底与 8dp 圆角；背景图片遮罩改浅色风格。
- 首页：最近会话从黑底分割列表改为暖白 8dp 卡片；置顶会话使用薄荷浅色底；保持头像/长按/置顶/删除功能不变。
- 约束：不新增图片资产，不改数据结构/业务流程，主要通过 Compose theme、颜色、圆角、间距实现。

## 2026-06-05 视觉复盘修正：去 Scroll Debug 与细节收紧
- 清理：移除 `DebugConfig.SHOW_SCROLL_DEBUG_HUD` 与 `ChatScreen` 里的 Scroll Debug HUD/字符串组装，滚动调试阶段结束。
- 截图复盘：聊天页仍像“换色版旧 UI”：顶栏粗、背景图干扰阅读、思考块过大、输入栏像表单、图标偏大；系统设置页标题/Tab/表单控件过大，后台感强。
- 修正：顶栏标题字号收紧；底部导航去阴影并压低高度；首页卡片/FAB/头像/文本密度收紧；管理后台 Tab 字号收紧；聊天背景遮罩增强，思考条弱化为轻折叠条，输入栏图标和文字尺寸收紧。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过；仅剩既有 Material/Icon deprecation warnings。

## 2026-06-05 视觉复盘修正 2：按设计图结构还原
- 用户反馈：最新界面比改前更丑，原因是只换色，未还原设计图结构。
- 首页：改为“置顶区 + 最近对话列表”结构；列表项放入统一暖白容器，行内分割线；置顶用浅薄荷底和数量胶囊；会话卡不再一个个大浮动块。
- 聊天：AI 气泡最大宽度、内边距、文字字号和行距收紧；思考过程改为更小的折叠胶囊；多回复切换改为时间旁小胶囊；输入栏从 `OutlinedTextField` 改成无下划线 filled composer，发送按钮改为薄荷圆形按钮。
- 系统设置：分区从松散标题/分割线改为 8dp 圆角面板；下拉输入高度收紧；标题文案“管理后台”改为“管理”，贴近设计图。
- 角色卡编辑：标题改为“角色卡”，默认聊天背景文案中文化，背景预览高度收紧，基础输入框统一 8dp 圆角和正文文本尺寸。
- 验证：`.\gradlew.bat :app:compileDebugKotlin` 通过；仅剩既有 deprecation warnings。
## 2026-06-05 视觉复盘修正 3：按截图问题继续还原 C 风格
- 底栏：取消选中项胶囊背景，改为图标与文字变色；底栏高度略收窄，避免选中态贴边造成廉价感。
- 顶栏：首页 `ChatBar` 加聊天图标与衬线标题，字号从 22sp 收到 20sp；管理页标题同步收小。
- FAB：主页、管理页、聊天页跳底按钮统一无阴影，主页/管理页 `+` 尺寸统一为标准 FAB 与 26dp 图标。
- 首页：移除“最近对话”右侧无意义“管理”文字，列表标题只保留信息本身。
- 聊天页：输入栏横向边距与底部区域更接近，输入框最小高度收窄，跳底按钮去阴影。
- 编辑页：角色卡、格式卡、大模型编辑页恢复 `TopAppBarDefaults.windowInsets`，避免状态栏遮挡；表单间距、单行输入高度进一步压缩。
- 设置页：全局设置卡片内距与间距收紧；滑块视觉高度收窄；玩家名称等单行输入固定为 52dp，多行设定框保留多行。
- 验证：`.\gradlew.bat assembleDebug` 通过，仅剩既有 Compose/Icon deprecation warnings。
## 2026-06-05 视觉复盘修正 4：选择框裁切、底栏、近白配色
- 问题：上一轮把带 label 的 `OutlinedTextField` / 下拉选择框压到 52dp，Material3 label 与正文被裁切，表现为文字只显示下半截。
- 修复：清空所有 `.height(52.dp)`，统一改为 64dp，保留紧凑感但不裁切 label/正文。
- 底栏：恢复 72dp 高度，移除文字 label，只保留“聊天/管理”图标变色；继续保持无选中气泡、无阴影。
- 配色：页面背景从暖米色 `#F7F4EF` 改为近白 `#FAFAFA`；Surface 改为 `#FEFEFE`，输入/轻区域改为 `#F6F6F6`，分割线改为极淡 `#E7E7E7`，减少顶栏/页面深白分区感。
- 验证：`.\gradlew.bat assembleDebug` 通过。

## 2026-06-05 UI 架构更新：Design System 替换旧散点样式架构

> 本节替换旧的“暗色 only / 页面内散点写样式”设计。后续视觉开发以本节为准。

### 新原则
- 业务层、数据层、RAG、Prompt、ViewModel 不承担视觉职责。
- 页面不再直接散点写颜色、阴影、高度、输入框边框；优先调用统一 Design System。
- 视觉层使用“近白社媒风格”：`rgb(250,250,250)` 作为页面与顶栏主基底，区域区分依靠 `0.5dp` 极淡边线、留白、字重，而不是深色底框。
- 所有旧 `DarkBackground/DarkSurface/DarkSurfaceVariant/DarkSurfaceHigh` 只作为兼容别名保留，新代码必须使用 `AppBackground/AppSurface/AppSurfaceElevated/AppFieldBackground/AppBorder`。

### 新文件结构
```text
app/src/main/java/com/example/chatbar/
  theme/
    Color.kt              # App* design tokens + deprecated Dark* compatibility aliases
    Theme.kt              # Material3 lightColorScheme bound to App* tokens
    Type.kt               # typography tokens
  ui/components/
    AppDesign.kt          # AppTopBar, AppSection, AppScreen, AppDimens, field/card/FAB styles
    BottomNavBar.kt       # bottom navigation, token-driven
    ChatBubble.kt         # chat message rendering
    EmptyState.kt
    LoadingIndicator.kt
```

### 组件职责
- `AppTopBar`：统一顶栏高度、背景、标题字号、状态栏 inset 策略。
- `AppDimens`：统一半径、边框、字段高度、顶栏/底栏高度。
- `appTextFieldColors()`：统一输入框/选择框颜色，避免不同页面各写一套 Material 默认样式。
- `AppSection`：统一设置页和列表面板，用近白背景 + 极细边框，不使用阴影。
- `appFabElevation()`：统一 FAB 无阴影策略。

### 迁移策略
- 首轮已完成：主题 token、底栏、首页顶栏/FAB、管理页顶栏/FAB/设置字段、角色/格式/模型编辑页部分字段接入新组件。
- 后续页面新增或改 UI 时，只允许通过 Design System token/component 调整；不得继续新增散点 `Dark*` 或硬编码色值。
- 旧页面中的散点样式按风险逐步收敛，避免影响业务行为。

### 2026-06-05 第二轮迁移记录
- 聊天页：消息列表背景、背景图遮罩、图片预览条、全屏输入/消息编辑器、跳底 FAB、输入框背景全部从 `Dark*` 迁移到 `App*` token / `appTextFieldColors()` / `appFabElevation()`。
- 会话设置弹窗：根背景、顶栏、Tab 背景、表单输入框、存档卡片、下拉菜单、聊天背景预览改用 `App*` token 与统一 field/card colors。
- Debug 弹窗：根背景、顶栏、日志卡片、代码块背景、RAG 重建结果弹窗迁移到 `App*` token，去除残留暗色表面。
- 首页：最近会话容器、长按菜单、确认弹窗、头像 fallback 背景迁移到 `AppSurfaceElevated/AppBorder`。
- 管理/编辑页：部分列表卡片、下拉菜单和模型/角色编辑表单继续迁移到统一 card/field token；剩余 `Dark*` 多为对话框容器或低频编辑控件，后续按页面继续收敛。
- 验证：`.\gradlew.bat assembleDebug` 通过。

### 2026-06-05 第三轮迁移记录：按 `doc/ui_design_system.md` 规范化元素
- 新增 `doc/ui_design_system.md`，定义字体层级、颜色 token、间距尺寸、页面结构、组件规范和禁止事项。
- Typography：`bodyLarge/bodyMedium/bodySmall` 统一到 14sp/14sp/13sp，letterSpacing 归零，贴合“不要负字距/不要 viewport 缩放字号/紧凑 UI 正文 14sp”的规范。
- 顶栏/标题：聊天页、角色卡编辑页、Debug 页清理 22sp/18sp 异常标题，统一到 20sp PageTitle。
- 元信息：`ChatBubble` 时间、多回复切换、思考折叠标题统一到 11sp Meta。
- 容器：角色/管理页弹窗容器从旧 `DarkSurface` 迁移到 `AppSurfaceElevated`；角色背景预览/进度条等轻区域迁移到 `AppSubtleSurface`。
- 表单：管理页、角色页、模型页剩余 `OutlinedTextFieldDefaults.colors(...)` 迁移到 `appTextFieldColors()`，保持输入框/选择框风格一致。
- 验证：`.\gradlew.bat assembleDebug` 通过。

## 2026-06-05 模型参数体验优化：常用参数卡片
- 问题：大模型编辑页的“自定义参数”要求用户手动输入 key/type/value，学习成本高，且容易拼错参数名。
- 改动：在模型编辑页参数区新增“常用参数”卡片区，优先提供一键添加；手动添加保留为兜底入口。
- 预设参数：`max_tokens`、`temperature`、`enable_thinking`、`thinking_budget`、`stop`、`frequency_penalty`。
- 交互：每张卡片显示中文标题、参数 key、默认值、简短说明；点击添加到 `customParamsMap`；已添加的参数卡片置灰并显示“已添加”。
- 保留：已启用参数仍可在下方直接编辑值或删除；手动添加对话框不删除，用于非标准 API 参数。
- 验证：`.\gradlew.bat assembleDebug` 通过。
## 2026-06-06 Phase 3 视觉美化：App Icon
- 选择：采用 F 方案作为 App Icon 首版，核心意象为「抽象消息流 / 文字流条 / 联想流动感」。
- 落地：App Icon 改用透明 PNG 前景图，不再手绘 XML 图形；从 F 方案图片裁切竖向声波/消息流条，移除白色卡片背景，保留半透明薄荷图形。Adaptive icon 背景设为透明。
- 启动页：禁用旧启动预览；Android 12+ splash 图标与背景设为透明，避免显示独立启动页。
- 复测修正：PNG 图形缩小到原尺寸 70%，避免 launcher 图标过满；首页顶栏品牌图标改用同一 `ic_launcher_foreground.png`，保持品牌一致。
- 设计约束：与当前设计系统保持一致，近白、薄荷、珊瑚、无阴影，避免廉价渐变和复杂资产。
