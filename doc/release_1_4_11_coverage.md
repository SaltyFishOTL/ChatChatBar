# v1.4.11 发布覆盖

上一稳定版经远端 GitHub Releases 确认为 `v1.4.10`，标签指向 `954b61b87356fc676daa6590ac9e1ff519ab175b`。正式版本为 `1.4.11 / versionCode 90`；标签 `v1.4.11` 指向版本提交 `cf16559a41284dcc1b80212631948c8c58fb8ba2`，功能提交为 `b7dc5c670d8a827a7dd34c891685dd4add38d9fb`。

## 更新内容

- 新增、修改或删除模型后，视觉模型、工作室及其他 AI 功能的模型列表和可用状态会自动同步。
- 修复已开启多模态的模型仍提示未配置视觉模型、图片反推无法启动的问题。
- 工作室切换设计模型后立即生效，图片反推和 AI 设计会使用最新选择及连接配置。

## 相邻标签范围映射

已逐笔检查 `v1.4.10..v1.4.11` 的全部三个提交及差异，按时间顺序覆盖如下：

| 提交 | 覆盖或省略理由 |
| --- | --- |
| c84d25967e0873309f57e9b859436a285ee7d153 | 仅记录 v1.4.10 发布证据与交接，不改变用户行为，省略 |
| b7dc5c670d8a827a7dd34c891685dd4add38d9fb | 第一条：模型保存、复制、预设恢复与状态发布串行化，模型读取同源，模型列表、默认选择、连接凭据与可用状态同步；第二条：反推启动读取最新多模态能力；第三条：同步切换共享草稿中的设计模型，AI 设计执行时重新解析连接配置。技能及回归源码为开发维护，不另列用户条目 |
| cf16559a41284dcc1b80212631948c8c58fb8ba2 | 工作流只更新 Android 版本名和版本码，省略 |

## 发布证据与验证边界

- [发布工作流 42437946538](https://github.com/SaltyFishOTL/ChatChatBar/actions/runs/42437946538) 成功，应用、JVM 测试源码、Android 测试源码编译及签名 APK 构建通过；`runTests=false`，测试执行步骤为 skipped。
- [正式 Release](https://github.com/SaltyFishOTL/ChatChatBar/releases/tag/v1.4.11) 非草稿、非预发布，正文与审核的三条更新说明一致。
- `verify_release.py --repo SaltyFishOTL/ChatChatBar --version 1.4.11` 通过，返回 `api_body=ok`、`atom_body=ok`；应用的 API 与 Atom 更新日志来源均包含相同说明。
- [ChatBar-1.4.11.apk](https://github.com/SaltyFishOTL/ChatChatBar/releases/download/v1.4.11/ChatBar-1.4.11.apk) 已上传，95,730,457 字节，GitHub asset digest 为 `sha256:0c78dfd5d2aa6536a67f4675b490e1415a2133133b84c3d33c38c3472e85d859`；工作流在正式发布前核验上传大小及 SHA-256。
- 本地发布前应用与两类测试源码编译通过；修改过的模型运行时、NovelAI 提示词技能均通过轻量结构校验。
- 同步版本提交后执行 `redeploy.bat --build-only --no-pause`，本地 release/lintVital 构建通过；独立 APK 为 `app/app/build/outputs/apk/release/app-release.apk`，元数据确认 `1.4.11 / 90`，95,730,689 字节，SHA-256 为 `458305b2196fe7df76c8bac2a2669cef9e1f36afb86503dbfe518db1191154fc`，与云端正式资产为独立构建。
- ADB 无连接设备；未执行自动测试、未调用真实模型、未读取设备日志或截图。发布后的证据补充仅修改文档，不改变标签中的客户端源码。

## 人工验收

1. 安装新版本并保持应用运行，新增多模态模型并保存；在其他模型的“图片解析视觉模型”和工作室“Prompt 设计模型”中确认该模型立即可选。
2. 修改已选模型的多模态能力、名称、连接信息或默认设置，保存后返回原页面；确认列表与可用状态更新。删除工作室显式指定的设计模型后应显示失效错误。
3. 在工作室切换设计模型，导入图片并反推；确认出现可编辑的场景描述，确认后生成 Prompt。再检查文本模型关联视觉模型的原有路径，以及聊天、角色卡、世界书、格式卡的模型列表。
