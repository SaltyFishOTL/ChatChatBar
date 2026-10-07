# 生图与角色卡回归记录

## 交付范围

1. 图片理解及辅助任务使用所选模型的 reasoning effort、thinking、采样和显式输出参数。
2. 朋友圈生成跳过重复性/进度判断，旧 checkpoint 的 shouldPost 不再阻止有效文案发布；频率、开关与每日限额保留。
3. 角色卡 PNG 导出支持独立文件图片；不更改原卡背景或嵌入的角色卡资源。
4. 恢复预制角色时合并包内及清单声明的世界书、默认格式依赖；已存在的预制资源保留本地编辑。未绑定默认格式的预制卡不擅自新增绑定。
5. 角色卡画风下的高级设置默认折叠；模型、Sampler、Steps、CFG、CFG Rescale、V+ 随卡保存、导入导出，并用于相关生图入口。
6. Sampler 选项及请求校验按模型适配，旧 DDIM 设置兼容为 Euler Ancestral。
7. V+ 按模型能力显示、序列化和元数据回填；V5 请求不携带此参数。
8. 草稿与历史流式读取，较大 Vibe 编码保存为磁盘引用、发请求时按需读取；保留角色、折叠、设置和历史分组。

网页版同步生图相关功能。回归发现的长截图生命周期绑定问题同时修复；旧 UI 测试更新了窗口/滚动/输入语义定位和独立样本。

## 自动验证

- Android JVM：1,234 项通过。
- Android API 36 模拟器：101 项通过，0 跳过。
- 网页离线 Chromium：91 项通过；生产构建通过。
- 低内存：256 MiB JVM 堆下，读取各 160 MiB 的合成草稿、历史，数据引用去重，折叠和文字不变；损坏数据保留原文件。
- 未发送付费模型或 NovelAI 请求；实际供应商服务可用性不在离线验证结论内。

可复跑（工作目录 app）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
.\gradlew.bat :app:testDebugUnitTest --tests '*NovelAiStudioLargeLoadTest' --tests '*NovelAiInlinePayloadStoreTest' --init-script low-memory-tests.init.gradle --no-configuration-cache
```

## 后续人工抽查

创建角色卡，在高级设置选择模型/参数，保存并应用到工作室；检查参数、负面词及折叠角色。切换模型检查 Sampler 和 V+；重新打开确认保存。导出时选择另一张图片，导入导出文件检查卡片原背景仍保留。删除预制卡声明的关联资源后恢复角色，确认依赖恢复且已有编辑不被覆盖。开启朋友圈，确认相同上下文仍可生成。
