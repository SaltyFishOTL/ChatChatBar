# ChatBar FlutterReborn Visual Audit

更新时间：2026-06-21

目标：为 P0-05 逐屏视觉/交互 audit 建立设备证据。当前证据来自 Android emulator `emulator-5554`，分辨率 1080x2400，Flutter debug APK。

## 当前结论

- 已完成首轮主导航视觉 smoke：Tutorial、Home、Manage、Chat 空态、Settings、Help。
- 未发现黑屏、启动崩溃、顶部导航裁切、中文缺字、主要按钮横向溢出。
- 顶部导航在 portrait 下换行显示，`Help` 不再被右侧裁切。
- Manage/Settings 长内容依赖滚动，首屏底部自然截断；暂未发现固定底部遮挡主要操作。
- Chat 仅验证空态；高风险的有会话 Chat 页面、composer、键盘、消息、长按 action、Debug panel、图片预览仍未完成逐屏视觉验收。

## 设备证据

| Screen | Screenshot | 状态 | 观察 |
| --- | --- | --- | --- |
| Tutorial | `D:\Projects\ChatBar\flutter-visual-tutorial.png` | OK | SafeArea 正常；导航换行；教程标题、卡片、Next 可读；无底部按钮裁切。 |
| Home | `D:\Projects\ChatBar\flutter-visual-home.png` | OK | 统计卡、Recent sessions 空态可读；导航选中态清楚；无横向溢出。 |
| Manage | `D:\Projects\ChatBar\flutter-visual-manage.png` | PARTIAL | 中文角色名/描述可读；图片预览显示；Start chat/Edit/Export/Delete 按钮无裁切；长列表首屏底部为自然滚动截断。 |
| Chat empty | `D:\Projects\ChatBar\flutter-visual-chat.png` | PARTIAL | 空态 `No chat sessions` 可读；未覆盖会话详情、composer、键盘和消息列表。 |
| Settings | `D:\Projects\ChatBar\flutter-visual-settings.png` | PARTIAL | 模型模式、预设模型、表单输入框和模板按钮可读；长设置页首屏底部为自然滚动截断。 |
| Help | `D:\Projects\ChatBar\flutter-visual-help.png` | OK | Help tab 打开 Tutorial 内容；Skip/Next 可读；无裁切。 |

## 已尝试

- 重新构建 debug APK：`flutter build apk --debug`。
- 清理 app 数据并启动：`adb shell pm clear com.example.chatbar_reborn` + `am start`。
- 逐个点击顶部导航并截图。
- 拉取 UI hierarchy：`D:\Projects\ChatBar\flutter-visual-window.xml`。
- 检查 logcat：未见 `FATAL EXCEPTION`、`FileSystemException`。

## 未覆盖

- 有会话 Chat 页面：session list、header、composer、message list、message bubble、image attachment、debug panel。
- 键盘打开后的 composer/input inset。
- 长按消息 action panel。
- 图片预览全屏和图库保存 UI 入口。
- Settings 下半部分：NovelAI token、RAG settings、appearance/player settings。
- Manage 深层页面：角色编辑、格式卡编辑、导入冲突、删除确认。
- 真实 provider streaming 录屏。
- 物理设备尺寸/字体缩放。

## 推荐下一步

1. 造最小会话数据或通过 UI `Start chat` 进入 Chat 详情页，截图验证 composer/message/keyboard。
2. 覆盖 Manage 编辑页和删除确认。
3. 覆盖 Settings 下半部分和 NovelAI token UI。
4. 复跑 Android 原生版本对应页面截图，做 Android vs Flutter 并排核对。
5. 将每个截图对应的 UI hierarchy/关键节点写入审计表，避免只凭截图判断。

## 2026-06-21 Chat Detail 深层视觉补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Chat detail fixed | `D:\Projects\ChatBar\flutter-visual-chat-detail-fixed.png` | OK/PARTIAL | 有会话详情页可显示；session list 置顶；header 横向可读；`Session`/`Saves`/`Debug`、`Attach image`/`Pick image`、`Send`/`Send + AI` 全部入屏；未见黄黑 overflow 条。 |
| Chat detail hierarchy | `D:\Projects\ChatBar\flutter-visual-chat-detail-fixed.xml` | OK | UI hierarchy 未命中 `OVERFLOW`/`RIGHT OVERFLOWED`/`BOTTOM OVERFLOWED`；关键按钮 bounds 均在 `[0,0][1080,2400]` 内。 |

### 本轮发现并修复

- 初始有会话 Chat 详情页在 portrait 下溢出：
  - `RIGHT OVERFLOWED BY 186 PIXELS`
  - `BOTTOM OVERFLOWED BY 227 PIXELS`
  - composer 控件在 hierarchy 中出现 `[0,0][0,0]` bounds。
- 修复点：
  - `ChatView`：窄屏从横向 `Row` 改为上方 session list + 下方 detail。
  - `ChatComposer`：窄屏输入框满宽，按钮 `Wrap` 换行。
  - `ChatHeader`：竖屏身份区和操作按钮上下排，标题不再竖排。

### 仍未覆盖

- 软键盘弹出后的 composer inset：本轮 AVD 输入框 focus 成功，但软键盘未显示，疑似硬件键盘设置影响。
- 长按 message action panel。
- Debug panel 展开态。
- 图片预览/保存入口。
- Android 原生并排截图对照。

## 2026-06-21 Debug 与 Message Actions 视觉补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Debug panel | `D:\Projects\ChatBar\flutter-visual-debug-panel.png` | OK/PARTIAL | `Debug console`、`Rebuild RAG`、`Clear` 可见；按钮入屏；未见 overflow。 |
| Debug rebuild result | `D:\Projects\ChatBar\flutter-visual-debug-rebuild.png` | OK/PARTIAL | `RAG rebuild result` 和 `Copy` 可见；空日志状态仍显示；未见 overflow。 |
| Message actions fixed | `D:\Projects\ChatBar\flutter-visual-message-actions-fixed.png` | OK/PARTIAL | 长按 assistant message 后显示固定 action bar；`Edit`、`Delete`、`Regenerate`、`Close` 全可见；未见 overflow。 |
| Message actions hierarchy | `D:\Projects\ChatBar\flutter-visual-message-actions-fixed.xml` | OK | UI hierarchy 中 `Edit/Delete/Regenerate/Close` bounds 均在 `[0,0][1080,2400]` 内；未命中 `OVERFLOW`/`RIGHT OVERFLOWED`/`BOTTOM OVERFLOWED`。 |

### 本轮发现并修复

- 初始 Message actions 作为 inline panel 放在 message bubble 下方，长消息场景只露出标题，操作按钮被 composer 截掉。
- 尝试把 inline panel 挪到 bubble 上方后仍不稳定。
- 最终修复：`ChatMessageList` 在 `Bottom` 控件下方显示固定 `MessageActionPanel`，消息内容留在下方滚动区域。

### 仍未覆盖

- `Edit/Delete/Regenerate` 的设备端完整操作链路。
- archived session 下 action panel 禁用/隐藏验证。
- Debug panel 中真实 provider 日志展开、复制、清空。
- 图片预览/保存入口。
## 2026-06-21 图片消息/预览/保存视觉补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Image message fixed | `D:\Projects\ChatBar\flutter-visual-image-message-fixed.png` | OK/PARTIAL | 图片消息缩略图、`Delete image`、`Preview image`、`Generate image` 均在 1080x2400 屏内；无明显 overflow。 |
| Image message hierarchy | `D:\Projects\ChatBar\flutter-visual-image-message-fixed.xml` | OK | 关键控件 bounds 均在 `[0,0][1080,2400]` 内。 |
| Image preview fixed | `D:\Projects\ChatBar\flutter-visual-image-preview-fixed.png` | OK/PARTIAL | `Image preview`、`Save image`、`Close preview`、预览图均可见；按钮未被 composer 截断。 |
| Image preview hierarchy | `D:\Projects\ChatBar\flutter-visual-image-preview-fixed.xml` | OK | `Save image` / `Close preview` bounds 有效。 |
| Image save fixed | `D:\Projects\ChatBar\flutter-visual-image-save-fixed.png` | OK/PARTIAL | 保存后显示 `Saved to Pictures/ChatBar`；预览仍可见。 |
| Image save hierarchy | `D:\Projects\ChatBar\flutter-visual-image-save-fixed.xml` | OK | 保存成功文案进入 accessibility hierarchy。 |

### 本轮发现并修复

- 真实 PNG 缩略图在 portrait 消息视口中过高，操作按钮被挤出或贴近底边。
- 新增 `MessageImageSection`，把图片 UI 状态从 `MessageBubble` 拆出。
- 缩略图限高到 72dp，预览图限高到 96dp，优先保证操作可达。
- `MessageImagePreview` header 改用 `Wrap`，窄屏下按钮换行不溢出。

### 仍未覆盖

- 多图消息。
- 系统 picker 手动选择图片后的 UI 链路。
- 图片预览是否需要改为 Android 同款全屏/modal。
- 物理设备和不同字体缩放。
## 2026-06-21 Settings 下半段视觉补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Settings lower fixed | `D:\Projects\ChatBar\flutter-visual-settings-lower-fixed.png` | OK/PARTIAL | `RAG retrieval`、`Player`、`NovelAI image generation`、`Default context window` 均可见；主要按钮 bounds 在 1080x2400 内。 |
| Settings lower hierarchy | `D:\Projects\ChatBar\flutter-visual-settings-lower-fixed.xml` | OK | `Player name`、`Global persona`、`Persistent API token` 均有独立 `EditText` hint；`Save token` / `Clear token` 可见。 |
| Token save fixed | `D:\Projects\ChatBar\flutter-visual-settings-token-save-fixed.png` | OK/PARTIAL | 输入测试 token 后显示 `Token configured` 与 `NovelAI token saved`。 |
| Token clear fixed | `D:\Projects\ChatBar\flutter-visual-settings-token-clear-fixed.png` | OK/PARTIAL | 清除后显示 `Token not configured` 与 `NovelAI token cleared`；测试 token 已清理。 |

### 本轮发现并修复

- `CbInput` 原先仅内部 `EditableText` 可获得焦点，点击 padding/提示区域时 ADB 输入不稳定。
- UI hierarchy 中部分 Settings card 被聚合成大 `EditText`，输入框语义不够清楚。
- 修复：`CbInput` 增加 `Semantics(textField: true, label: hintText)` 与 wrapper `GestureDetector` 请求 focus。

### 仍未覆盖

- 软键盘弹出后的 Settings inset。
- 物理设备长 token 输入/复制粘贴。
- TalkBack 实际朗读。
- 自定义 chat/embedding model 表单的完整设备新增/编辑/删除。

## 2026-06-21 Manage 深层视觉补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Manage import fixed | `D:\Projects\ChatBar\flutter-visual-manage-import-panel-fixed.png` | OK/PARTIAL | `New`、`Hide import`、`Presets`、`Character package JSON`、`Import` 入屏；输入框 hint 独立。 |
| Character edit top | `D:\Projects\ChatBar\flutter-visual-manage-character-edit.png` | OK/PARTIAL | `Name`、`Avatar image path`、`Greeting`、`Clear avatar`、`Basic setting`、`Structured/Freeform` 入屏。 |
| Character edit middle | `D:\Projects\ChatBar\flutter-visual-manage-character-edit-step3.png` | OK/PARTIAL | 首卡 freeform 编辑可继续滚到 `Reference documents`、`Add document`、`Save`、`Cancel`。 |
| People/docs card | `D:\Projects\ChatBar\flutter-visual-manage-character-edit-people.png` | OK/PARTIAL | 第二卡编辑入口包含 `People`、`Person 1..10`、`Reference documents`、13 docs。 |
| Document form | `D:\Projects\ChatBar\flutter-visual-manage-character-edit-docs-form.png` | OK/PARTIAL | 文档区 `Delete`、`Rebuild RAG`、`File name`、`Document content` 可见。 |
| Document delete confirm | `D:\Projects\ChatBar\flutter-visual-manage-document-delete-confirm.png` | OK | 第一次点文档 `Delete` 后按钮变为 `Confirm delete document`；未执行第二次删除。 |

### 本轮发现并修复

- `CbButton` 缺少 button semantics，设备 hierarchy 中多个按钮语义弱。
- 修复：`CbButton` 加 `Semantics(button: true, selected: selected, label: label)`。
- `CbInput` 语义边界不够清楚，导入卡/设置卡中可能聚合 sibling。
- 修复：`CbInput` 加 `Semantics(container: true, textField: true, label: hintText)`。
- 文档删除原本单击即执行，风险高。
- 修复：`CharacterDocumentsEditor` 内部维护 `confirmingDeleteId`，第一次点击只显示 `Confirm delete document`。

### 仍未覆盖

- 导入 JSON 冲突 overwrite/copy/cancel 设备全流程。
- `Add document` + `Rebuild RAG` 真实设备写入链路。
- 第二次文档删除确认后的真实删除链路。
- 系统图片 picker 和物理设备触摸观感。

## 2026-06-21 Manage 导入冲突补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Import panel fixed | `D:\Projects\ChatBar\flutter-visual-manage-import-panel-fixed.png` | OK/PARTIAL | 导入面板和 `Import` 按钮可见，输入框语义独立。 |
| Import text probe | `D:\Projects\ChatBar\flutter-visual-manage-import-text-probe.xml` | BLOCKED/PARTIAL | `EditText focused=true`，但 ADB `input text abc` 未进入输入框；无法自动注入长 JSON。 |

### 本轮发现

- 设备端无 ADB Keyboard，系统 clipboard 命令也不可用。
- ADB `input text` 无法把文本写入当前导入框，即使 XML 显示输入框已 focus。
- 因此本轮不能用 ADB 自动完成真实 JSON 冲突流。

### 测试覆盖

- `CharacterPackageTransferPanel` widget test 已覆盖冲突按钮显示和 `Overwrite existing` / `Create copy` / `Cancel` callback。
- `CharacterTransferController` unit tests 已覆盖 import copy 唯一命名、overwrite 保留 identity、失败回滚。

### 仍未覆盖

- 人工粘贴 JSON 后的真实设备 conflict screen。
- 真实设备 copy/overwrite/cancel 完整链路。
- 长 JSON 输入体验是否需要文件 picker 或导入文件入口。

## 2026-06-21 Manage 文档添加/RAG 补充

### 设备证据

| Screen | Screenshot | Status | Observation |
| --- | --- | --- | --- |
| Add document input probe | `D:\Projects\ChatBar\flutter-visual-manage-add-document-input-probe.png` | BLOCKED/PARTIAL | `Document content`、`Add document` 可见；输入框 focus 成功但 ADB `input text hello` 未写入。 |
| Add document hierarchy | `D:\Projects\ChatBar\flutter-visual-manage-add-document-input-probe.xml` | BLOCKED/PARTIAL | XML 显示 document content `focused=true`；文本仍为空。 |

### 本轮发现

- 文档 form 本身在 portrait 下可达。
- `Rebuild RAG`、`File name`、`Document content`、`Add document` 按钮 bounds 在 1080x2400 内。
- 当前 ADB 无法注入文本，阻止自动化完成真实 Add document 端到端。

### 测试覆盖

- `CharacterDocumentController` tests 覆盖写 owned file、标 RAG dirty、删除 owned file/chunks、拒绝空内容。
- `CharacterRagController` tests 覆盖无文档跳过、缺 embedding config error、有 embedding 时 per-doc index/failure。
- `CharacterDocumentsEditor` widget test 覆盖 Add/Rebuild 按钮 callback 和 rebuild log 显示。

### 仍未覆盖

- 人工键盘输入文档内容后 Add document 真实设备流。
- rebuild 后设备 UI 状态刷新。
- 真实 embedding provider 下的 document RAG rebuild 成功路径。
