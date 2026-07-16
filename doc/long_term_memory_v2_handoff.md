# 长期记忆 v2 Handoff

Last updated: 2026-07-16
Branch/worktree: `master`，大量未提交长期记忆v2改动
Baseline before V1: `966cea7c 优化聊天图片生成与再生成`
Status: 长期记忆重构版V1主体完成，处于真实模型与旧数据持续验收期；直接多T单摘要协议和补录流式进度尚待用户安装后验证。

## Completed

- 已实现HEAD、Episode、Arc、Era结构，连续source turn覆盖、派生显示T、预算压缩、独立分页历史、SaveSlot v4、编辑/恢复和完整注入预览。
- 长期记忆注入只包含Archive + HEAD；RAG、世界书、直接上下文、pending和原始对话不进入长期记忆块。
- Episode全局分组支持1–6轮、默认2；滑块位于全局设置并与上下文保留组数相邻。
- Episode AI协议已改为把1–N轮原文直接压成一个`summary`，不再生成逐T `sourceCoverage`。新节点不含逐T摘要；程序使用有序sourceTurnId、来源哈希和单段正文计算结构覆盖哈希。旧coverage节点继续兼容，不重写。
- Episode summary采用动态硬上限：1T 50字，每增加1T加20字，默认2T 70字，6T最高150字；每次Prompt明确写入本批准确上限，并含错误逐T复述和正确跨轮融合示例，超限最多重试一次。
- 上下文与RAG已改为按完整`sourceTurnId`分组：同轮追加AI回复/图片不拆成多个上下文组或RAG块。
- RAG存储与长期记忆节点保持独立；仅共享稳定source-turn边界。
- Gap已改为持久缺失事实：扩大上下文只隐藏当前不可补录部分，不删除Gap；缩小上下文后重新显示。旧版误删Gap会执行一次幂等修复，并排除已有节点、普通pending和永久清空前剧情。
- 长期记忆页面刷新会重新读取最新全局上下文组数、原始消息、source turn时间线、已有覆盖、普通pending、Gap和来源哈希；上下文缩小后新滑出的未覆盖稳定轮会立刻登记为可补录Gap。刷新不调用AI，重复刷新不重复建Gap。
- 一键补录已区分本进程活跃runner与重启遗留`RUNNING`：内部读取不再暂停自己的任务；孤儿任务在重启后转`PAUSED`。
- 补录失败会显示具体原因和“重试补录”；成功批次设计为立即持久化并只移除对应Gap来源。
- 补录页显示处理轮数、已生成Episode数、当前阶段/T范围和流式summary；部分流文本只存在运行时，不写入SaveSlot或会话JSON。
- HEAD改为三模式：第三轮开始前以开场白+第一轮初始化；普通更新只读上一HEAD+下一基线组；补录以Archive+倒数第二个稳定组重建。最新完整轮始终留在Prompt底部热区。
- 发送前HEAD准备与RAG并行等待；发送后仍后台滚动。空HEAD不注入；历史空HEAD、落后HEAD或跨Gap更新会提示一键补录。
- 补录状态在进入聊天时即从持久层加载；补录与HEAD重建完成前输入框、全屏编辑和发送均锁定。
- V1已移除“重新补录长期记忆（Debug）”按钮、ViewModel入口和重建服务；旧数据中的Debug枚举值继续保留反序列化兼容。
- 已新增项目Skill：`.agents/skills/chatbar-long-term-memory/`，长期架构约束和状态机不再堆入handoff。

## In Progress

- 等待用户安装最新构建，验证普通补录的实时进度、流式summary及单段Episode提交。

## Tried And Failed

- 旧实现把进入直接上下文的Gap来源永久删除，导致上下文恢复后待补录提示消失。已改为持久Gap + 动态eligibility。
- 旧实现每次读取状态都把`RUNNING`改成`PAUSED`，导致补录AI调用后、首个Episode提交前静默退出。已用进程内活跃runner登记修复。
- 旧刷新仅重读已有状态，且Gap修复版本完成后会提前返回；上下文缩小时新滑出窗口的未覆盖轮次不会被扫描。已新增显式条件同步刷新并移除错误短路。
- 旧实现先要求AI生成逐T `sourceCoverage`，再生成整体summary；即使隐藏coverage，模型输出和持久化仍近似随T线性增长。已改为直接多T单摘要，覆盖证明由程序生成。
- 旧RAG按相邻消息对分块，遗漏序章和同轮追加回复。已改为完整source turn分块。

## Tried And Not Adopted

- 不把RAG索引或召回范围与Episode/Arc/Era节点绑定。
- 不在长期记忆预览或注入中携带原始对话、pending或压缩来源。
- 不使用模型上下文10%预算、全局MemoryCommit或Episode语义闭合。

## Untested

- 最新直接多T单摘要、补录runner和流式进度尚未经过用户真实模型手动验证。
- 真实长聊下三层扩容询问、`compressible=false`链、Era平级压缩和多批补录仍缺少完整端到端证据。
- 旧SaveSlot、来源编辑/删除、补录暂停后继续仍需真实数据手动回归。

## Unconfirmed

- 用户T23测试卡在最新source-turn分组下应显示的精确待补录T范围，取决于当前已覆盖节点与稳定轮分组；不再用RAG块数推断。

## Blockers

- 无代码阻塞。当前需要用户侧保留数据安装和真实模型验证；按用户要求，Codex不操作设备。

## Recommended Next Steps

1. 用户安装最新构建，把全局上下文保留组数从较大值降到最低，再打开长期记忆页或点击刷新；确认新滑出窗口的T范围立即显示“一键补录长期记忆”。
2. 再把上下文扩大并刷新，确认相应范围只暂时隐藏；缩小后刷新应重新出现，且不产生重复Gap。
3. 点击“一键补录长期记忆”，确认进度条、当前T范围和summary文字持续更新，Episode逐批增加。
4. 确认设置为2轮时，AI直接把2T原文压成不超过70字的一段Episode；页面不出现逐T摘要或`legacy-turn`覆盖证明。
5. 若失败，记录页面展示的完整“补录失败”原因；不要重建RAG或清数据。

## Architecture Notes

- 维护此模块前使用`chatbar-long-term-memory`；稳定约束见其`references/invariants.md`，状态机见`references/state-machines.md`。
- `sourceTurnId`是持久身份；T是派生显示。上下文、Episode和RAG数据所有权不同，但必须共享完整轮边界。
- Gap表示历史缺失事实；当前上下文只控制是否可补录。不要再次把二者合并为同一可变列表。
- HEAD、Archive、RAG独立失败和持久化；主聊天不等待后台记忆任务。

## Verification Baseline

- `app/.\gradlew.bat test`：通过。
- `app/.\gradlew.bat :app:compileDebugKotlin`：通过。
- `powershell -NoProfile -ExecutionPolicy Bypass -File app/ci.ps1 -SkipAssemble`：通过，含Android测试源码编译。
- 当前直接多T单摘要和补录流式进度未执行设备安装或运行验证；旧设备部署结果不是本轮证据。
- 现有非阻塞编译警告未由本模块引入。
