# ChatBar UI Design System

> 2026-06-13 起，界面层使用 Compose Foundation 自建 UI Kit。API 与视觉原则参考 shadcn/ui；不依赖 Compose Material 3。

## 1. 设计方向

- 中性、克制、内容优先；接近 shadcn 的低饱和产品界面。
- 层级依靠留白、细边框、字重和弱背景，不依靠阴影或大色块。
- 组件由本地源码拥有，可按产品需求修改，不追求 Web 组件逐像素移植。
- 页面只组合 UI Kit；禁止页面重复手写按钮、输入框、弹窗基础样式。

## 2. 架构

UI Kit 位于 `ui/kit/`：

| 文件 | 职责 |
| --- | --- |
| `ChatBarTheme.kt` | 颜色、排版、圆角、间距、CompositionLocal、主题入口 |
| `Primitives.kt` | Button、IconButton、Badge、Separator、Card |
| `Fields.kt` | Input、Textarea、Select |
| `Controls.kt` | Checkbox、Switch、Slider、Tabs |
| `Layouts.kt` | TopBar、Section、BottomSheet/Dialog 基础布局 |
| `Feedback.kt` | AlertDialog、确认框、Toast 风格反馈、空状态 |

参考规范：`.agents/skills/chatbar-shadcn-compose/`。

## 3. Token

页面必须从 `ChatBarTheme` 读取语义 token：

- `background` / `foreground`
- `card` / `cardForeground`
- `popover` / `popoverForeground`
- `primary` / `primaryForeground`
- `secondary` / `secondaryForeground`
- `muted` / `mutedForeground`
- `accent` / `accentForeground`
- `destructive` / `destructiveForeground`
- `border` / `input` / `ring`

禁止页面新增旧 `Dark*`、`App*` 颜色体系。新增颜色先判断能否表达为语义 token。

## 4. 排版与尺寸

- UI 正文使用系统 sans-serif；Debug/JSON 使用 monospace。
- 标题、正文、标签、辅助文本从主题 typography 读取。
- 页面横向间距通常 16dp；紧凑表单可用 12dp。
- 默认圆角保持克制：输入框/按钮约 6dp，卡片约 8dp。
- 最小触控目标 40dp；关键操作优先 44–48dp。
- 系统状态栏由 `CbTopBar` 默认处理；只有嵌套场景才关闭 inset。

## 5. 组件规则

- 按钮使用 `CbButton` 及 variant：default、secondary、outline、ghost、destructive。
- 图标操作使用 `CbIconButton`，必须提供 content description。
- 表单使用 `CbInput`、`CbTextarea`、`CbSelect`；错误文案靠近字段。
- 内容分组使用 `CbCard` / `CbSection`；禁止卡片套卡片。
- 页签使用 `CbTabs`；开关、复选框、滑块使用对应 `Cb*` 控件。
- 弹窗使用 `CbDialog` / `CbAlertDialog`；危险操作必须二次确认。
- 空状态使用 `CbEmptyState`，同时给出恢复路径或主操作。

## 6. 页面规则

- 首页：低密度会话列表；置顶状态弱强调；FAB 仅承担新建会话。
- 聊天页：内容优先；用户/AI 气泡保持清晰归属；输入区固定底部。
- 管理页：Tabs 分角色、模型、格式卡、设置；列表操作保持一致。
- 编辑页：字段分区、错误就地显示；保存操作位置稳定。
- Debug：允许高密度，仍使用语义颜色与 monospace，不恢复黑底旧样式。

## 7. 禁止事项

- 禁止引入 `androidx.compose.material3` 组件或主题。
- 禁止页面直接实现基础控件视觉。
- 禁止无语义硬编码颜色、任意阴影、过大圆角。
- 禁止仅靠颜色表达状态。
- 禁止为仿 shadcn 破坏 Android 可访问性、系统 inset、触控尺寸。

## 8. 迁移状态

- [x] 建立 Foundation-only `ui/kit`。
- [x] 迁移主题、导航、底栏、空状态。
- [x] 迁移首页、聊天页、管理页。
- [x] 迁移角色、模型、格式卡编辑页。
- [x] 迁移聊天设置、Debug、消息气泡与确认弹窗。
- [x] 删除旧 `AppDesign.kt`、Material 主题与排版文件。
- [x] 删除 Material 3 Gradle 依赖。
- [x] 源码扫描无 `material3` / `MaterialTheme` 引用。
