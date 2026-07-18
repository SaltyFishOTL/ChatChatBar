# 长期记忆 v2 Handoff

Last updated: 2026-07-18
Branch/worktree: `master`
Baseline before V1: `966cea7c 优化聊天图片生成与再生成`
Status: 长期记忆重构版V1主体完成；自动Episode、补录、压缩与HEAD已改为目标证据级并发提交；历史消息修改/删除现会停用受影响旧记忆，并由用户在长期记忆页手动执行最小依赖修复；真实模型结果尚待用户验证。

## Completed

- 已实现HEAD、Episode、Arc、Era结构，连续source turn覆盖、派生显示T、预算压缩、独立分页历史、SaveSlot v4、编辑/恢复和完整注入预览。
- 自动Episode、手动补录、当前锚点、Archive压缩与HEAD不再把整份`MemorySessionState.revision`当作AI任务冲突锁。Episode只关联其source hash、目标pending成员和同源覆盖；压缩只关联模型实际读取的全部候选节点；HEAD只关联自身版本、输入source，`BACKFILL`额外关联实际发送的Archive文本。提交前在状态锁内重载并基于当前状态重放，因此无关聊天新增、其他节点编辑/新增、HEAD或分页变更不会误杀任务，也不会被旧快照覆盖。
- 禁用期Gap即使位于直接上下文也可补录，但用户刚发送、尚无助手回复的开放source turn不再进入补录；等AI回复加入同一source turn并稳定后才可选，避免补录中途证据真实变化。
- Episode/Arc/Era活跃分页ID现按派生T升序持久化。中间节点编辑/替换或任意重排会在增删delta无法复现位置时保存精确顺序快照；若旧revision父链已错误物化，后续Checkpoint/纯新增同步会自愈为快照。加载旧状态时只自动修复节点齐全、T可验证的分页，并写入隐藏修复revision；缺节点或缺T不强排。恢复旧历史也先按当前派生T规范化。
- Episode/Arc/Era编辑卡已增加“AI重新生成此节点”：Episode只读其原始聊天轮，Arc/Era只读其有序直接子节点，错误节点正文不进入AI输入。三层`summary`均完整流式写入编辑框；校验重试先清除上一轮流稿，最终失败恢复请求前草稿。结果仍是未保存候选；用户确认并点击Checkpoint后才替换活跃节点。不同目标节点可真正并发；保存一个候选不会让其他节点失效。完成时只校验该目标仍活跃、不可变节点与原始聊天/直接子节点依据未变；目标自身被替换或依据变化仍拒绝。
- Episode/Arc/Era编辑卡持续比较草稿与已持久化正文：未修改时显示“已保存到Checkpoint”；手工编辑或AI候选产生后显示红色“未保存，离开会丢失”，并把保存按钮切为高强调未保存状态。只有保存成功并刷新出新节点后才恢复已保存提示。
- 长期记忆注入只包含Archive + HEAD；RAG、世界书、直接上下文、pending和原始对话不进入长期记忆块。
- Archive注入和完整预览只输出按派生T排序的节点正文，不再输出`[Episode/Arc/Era Tx-Tx]`或Legacy类型/T标识；节点层级与T范围只留在程序元数据和UI。Legacy仅保留“时间未知｜不代表当前进展”语义警告。
- 非空活跃节点正文不会再因sourceTurnId缺失、断裂或旧T映射异常被静默丢弃。T证明完整的节点先按时间排序，异常节点按创建时间稳定后置；UI显示数据完整性警告，时间线约束明确说明范围待修复。空正文仍不注入。
- 主聊天请求把非空Archive作为独立`system`消息放在世界书/RAG之后、HEAD之前，不再与其他动态资料合并；固定顺序为世界书→RAG→Archive→HEAD。发送前重新读取失败会直接报错；若内存预算表明有Archive正文但编译结果为空，或最终消息列表缺少Archive标记，请求会在联网前被阻止。调试控制台直接显示最终Request JSON中Archive/HEAD各自是否已发送。
- Episode全局分组支持1–6轮、默认2；滑块位于全局设置并与上下文保留组数相邻。
- Episode AI协议已改为把1–N轮原文直接压成一个`summary`，不再生成逐T `sourceCoverage`。新节点不含逐T摘要；程序使用有序sourceTurnId、来源哈希和单段正文计算结构覆盖哈希。旧coverage节点继续兼容，不重写。
- Episode summary的Prompt目标为1T 50字、每增加1T加20字、默认2T 70字、6T 150字；程序硬上限为Prompt目标的2倍，即1T 100字至6T 300字，给AI字数估算保留有界容差。每次Prompt仍明确写入较短目标，并含错误逐T复述和正确跨轮融合示例。长期记忆AI输出校验统一最多5次，覆盖补录Episode、Archive压缩和最终HEAD重建。
- 上下文与RAG已改为按完整`sourceTurnId`分组：同轮追加AI回复/图片不拆成多个上下文组或RAG块。
- 主聊天请求不再给历史、上一轮、重试输入或当前输入追加`[Txx]`；模型只看到原始角色与正文顺序。T继续作为内部记忆排序/UI元数据。AI生成图片留下的空助手记录、被“排除状态栏”清空的助手记录和其他空助手记录直接省略；流式完成但无正文时明确失败，不再持久化空助手回复。
- RAG存储与长期记忆节点保持独立；仅共享稳定source-turn边界。最终召回中仅`CHAT_MEMORY`卡片前注入一次对话记忆使用说明，指导模型在相关时把过往细节自然融入台词、行为和关系细节，禁止复述或说明来源；知识库文档卡不带该说明。
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
- 历史消息修改/删除通过现有`sourceTurnId`与来源哈希自动检测；检测本身不调用AI。受影响Archive根节点和过期HEAD立即停止注入，未变后代仅在完整安全前沿不超预算时继续注入，避免旧摘要污染后续聊天。
- 长期记忆页新增“修复变更后的长期记忆”流程：警告、确认、阶段/根节点进度、流式摘要、暂停、继续和错误重试。修复与补录互斥；修复运行时锁定聊天发送。
- 修复按受影响活跃根节点分批提交。Episode从当前原始来源重生成；整轮删除会切开连续区间，不生成跨缺口节点。Arc/Era仅在原子节点一一对应且连续时重建，否则提升安全子节点前沿。全部Archive根修复后才重建或清空HEAD。
- 修复状态写入会话和SaveSlot：固定待修复根、已完成数、HEAD标记、暂停/错误均可恢复；流式文本只存运行时。进程重启后的孤儿`RUNNING`转`PAUSED`，已提交根不回滚。
- 仅AI生成节点参与自动修复。来源过期的用户手工节点要求用户在编辑器显式复核并保存，避免覆盖人工语义。

## In Progress

- 历史来源手动修复代码与自动测试已完成；待完整APK构建、设备数据保留部署及用户真实旧会话模型验证。

## Tried And Failed

- 旧实现把进入直接上下文的Gap来源永久删除，导致上下文恢复后待补录提示消失。已改为持久Gap + 动态eligibility。
- 旧实现每次读取状态都把`RUNNING`改成`PAUSED`，导致补录AI调用后、首个Episode提交前静默退出。已用进程内活跃runner登记修复。
- 旧刷新仅重读已有状态，且Gap修复版本完成后会提前返回；上下文缩小时新滑出窗口的未覆盖轮次不会被扫描。已新增显式条件同步刷新并移除错误短路。
- 旧实现先要求AI生成逐T `sourceCoverage`，再生成整体summary；即使隐藏coverage，模型输出和持久化仍近似随T线性增长。已改为直接多T单摘要，覆盖证明由程序生成。
- 旧RAG按相邻消息对分块，遗漏序章和同轮追加回复。已改为完整source turn分块。
- 旧revision用“删旧ID，再把新ID追加末尾”重建编辑后的中间节点，导致底层分页乱序；UI又在展示前排序，因此肉眼正常但完整性检查持续报警。已改为位置敏感快照 + 加载期隐藏修复revision，未通过关闭告警掩盖问题。
- 旧节点重新生成共用整会话Archive锁，并绑定全局`state.revision`；多个任务实际串行，保存任一候选都会误杀其他未变节点。已移除整会话锁和全局revision守卫，改为节点及其证据级校验。
- 旧自动Episode、手动补录、压缩和HEAD部分路径仍绑定整会话`state.revision`。聊天新增、无关HEAD滚动或其他节点提交都会提升revision，导致正确AI结果被当作竞态丢弃；两个回复后的Archive/HEAD后台任务也会互相误杀。现统一改为操作证据集合校验并重载rebase；全局revision仅保留为单调持久化版本号。
- 旧Archive注入先要求每个活跃节点都能从当前timeline推导完整T范围；旧迁移数据只要有一个sourceTurnId缺失，整段非空正文就被静默过滤。已把正文与T证明解耦：正文始终保留，异常范围单独告警并使用稳定排序。
- 旧发送链把Archive、RAG、世界书、HEAD拼成同一动态字符串，且发送前重新读取记忆时会吞掉异常；最终消息列表没有Archive存在性断言，预览正确不能证明实际请求正确。已改为Archive独立消息、读取异常显式失败、正文预算与编译结果交叉校验、最终列表硬断言和Request JSON实际发送指示。
- v1.2.6紧急修复先把独立Archive放在世界书/RAG之前，虽保证正文实际发送，但不符合动态资料语义顺序。现已统一主聊天与通用PromptAssembler为世界书→RAG→Archive→HEAD，并用最终序列化JSON验证位置。
- 旧聊天历史给每条消息拼接`[Txx]`；早期还会让AI图片记录、纯状态栏和空回复变成大量prefix-only助手消息。v1.2.6先消除了空占位；随后确认所有合成T标识都会误导模型格式理解，现已从历史、上一轮、重试与当前输入全部移除。
- 旧“已按新来源校正”只把节点来源哈希改成当前值，却保留基于旧消息生成的正文，导致错误摘要重新被视为有效。现已删除该伪修复入口，改为显式依赖链重生成；旧版已被用户校正过的节点因没有可靠标记，无法自动追溯识别。

## Tried And Not Adopted

- 不把RAG索引或召回范围与Episode/Arc/Era节点绑定。
- 不在长期记忆预览或注入中携带原始对话、pending或压缩来源。
- 不使用模型上下文10%预算、全局MemoryCommit或Episode语义闭合。

## Untested

- 真实用户旧会话尚未手动抓取最终API请求确认Archive正文与历史消息；纯策略和最终JSON序列化测试已覆盖正文保留、独立消息、HEAD-only拒绝及空历史过滤，模拟器为无旧数据冷启动烟测。
- 最新直接多T单摘要、补录runner和流式进度尚未经过用户真实模型手动验证。
- 真实长聊下三层扩容询问、`compressible=false`链、Era平级压缩和多批补录仍缺少完整端到端证据。
- 多节点AI重新生成及其真实并发流式表现尚待用户再次调用模型验证；自动测试已覆盖流稿、候选不自动保存、无关节点Checkpoint不失效、目标/依据变化仍拒绝。
- 旧SaveSlot、补录暂停后继续仍需真实数据手动回归。
- 自动Episode/HEAD与用户继续聊天并发、手动多批补录期间继续聊天、压缩期间编辑无关节点，纯策略测试已通过；真实服务instrumented测试已编译但因当前无设备且SDK缺`emulator.exe`未执行，仍待真实长聊和真实模型手动回归。
- 来源修改/整轮删除后的警告、安全注入、分批暂停/继续、Arc/Era结构降级和HEAD重建已有纯策略、序列化与UI测试；真实长会话和真实模型输出尚未手动回归。

## Unconfirmed

- 用户T23测试卡在最新source-turn分组下应显示的精确待补录T范围，取决于当前已覆盖节点与稳定轮分组；不再用RAG块数推断。

## Blockers

- 无代码阻塞。用户已允许Codex操作设备；真实模型补录或节点重新生成会消耗API额度，尚未触发。

## Recommended Next Steps

1. 在存在旧Episode/Arc/Era的真实会话打开完整预览并抓取一次API请求，确认Archive含全部非空正文，所有user/assistant聊天消息都没有程序追加的`[T数字]`前缀。
2. 选中一条明显错误的Episode/Arc/Era，点击“AI重新生成此节点”；先核对候选，确认错误正文没有影响结果，再决定是否保存Checkpoint。
3. 在有Episode/Arc/Era的测试会话修改一条历史消息；打开长期记忆页，确认出现修复警告，旧受影响根/HEAD不再进入完整预览。点击修复，观察阶段、流式摘要和逐根完成数。
4. 删除一个位于压缩节点中间的完整source turn；确认修复不会生成跨删除缺口的Episode/Arc/Era，且暂停/继续或失败重试不会丢失已完成根。
5. 把全局上下文保留组数从较大值降到最低，再打开长期记忆页或点击刷新；确认新滑出窗口的T范围立即显示“一键补录长期记忆”。
6. 再把上下文扩大并刷新，确认相应范围只暂时隐藏；缩小后刷新应重新出现，且不产生重复Gap。
7. 点击“一键补录长期记忆”，确认进度条、当前T范围和summary文字持续更新，Episode逐批增加。
8. 若失败，记录页面展示的完整失败原因；不要重建RAG或清数据。

## Architecture Notes

- 维护此模块前使用`chatbar-long-term-memory`；稳定约束见其`references/invariants.md`，状态机见`references/state-machines.md`。
- `sourceTurnId`是持久身份；T是派生显示。上下文、Episode和RAG数据所有权不同，但必须共享完整轮边界。
- Gap表示历史缺失事实；当前上下文只控制是否可补录。不要再次把二者合并为同一可变列表。
- 活跃分页ID必须按派生T升序；UI排序不能替代持久层顺序。revision delta只有在从父revision能精确复现节点顺序时才可使用，否则保存快照。
- 重新生成是节点级只读候选任务，不持有Archive整会话锁，也不绑定全局revision。失效边界是目标活跃身份、不可变目标节点和该节点确切依据。
- 所有直接提交AI任务都必须绑定其实际输入证据并在提交锁内重载rebase：Episode=目标source/pending/覆盖，压缩=全部模型候选节点，HEAD=HEAD版本+输入source，回填HEAD再加Archive文本。全局revision不是冲突键。
- HEAD、Archive、RAG独立失败和持久化；主聊天不等待后台记忆任务。
- 来源变更检测与修复分离：检测只刷新过期状态和安全注入；用户按钮才启动AI。修复必须沿不可变节点依赖向上重算，不能通过改哈希认可旧正文。

## Verification Baseline

- 2026-07-18目标证据并发切片：`app/gradlew.bat test`、`app/ci.ps1 -SkipAssemble`、完整`app/ci.ps1`均通过；Debug APK成功生成。新增策略测试覆盖无关revision/节点/pending变更放行及目标source/节点/HEAD/Archive变更拒绝；真实仓库+服务instrumented测试源码编译通过。
- 本切片`adb devices -l`无连接设备；尝试`emu.cmd`时本机Android SDK缺少`C:\Users\Administrator\AppData\Local\Android\Sdk\emulator\emulator.exe`，因此未运行instrumented测试、未部署APK、未调用真实模型。
- 当前来源修复切片：`ci.ps1 -SkipAssemble`与完整`ci.ps1`均通过；JVM测试、Android测试源码编译和Debug APK打包成功。`adb devices -l`无连接设备，因此本切片未部署；未调用真实模型。
- 定向回归通过：Episode Prompt目标与2倍程序硬上限边界、全部长期记忆策略、聊天消息无合成`[Txx]`前缀、空消息过滤、世界书→RAG→Archive→HEAD最终JSON顺序、RAG记忆卡专用说明且文档卡无说明、Archive独立请求与HEAD-only拒绝、最终JSON标记、状态栏排除和时间线提示。
- `app/.\gradlew.bat test`：全量JVM测试通过。
- `app/powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\ci.ps1 -SkipAssemble`：通过JVM测试与Android测试源码编译。
- 来源修复新增纯策略测试覆盖连续区间拆分、父节点合法重建、安全前沿预算和孤儿任务暂停；序列化测试覆盖旧JSON默认值及错误/待处理状态往返；UI测试覆盖手动修复入口、补录互斥、运行进度/流稿和暂停按钮。
- `app/powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\ci.ps1`：通过完整本地CI并生成Debug APK。
- 模拟器`chatbar_avd`冷启动完成；Debug APK数据保留安装成功；`MainActivity`冷启动`Status: ok`，应用进程启动后持续存活。
- 实体机已数据保留覆盖安装并启动当前release；包含完整流式节点重新生成、无合成T前缀、动态资料新顺序和RAG记忆卡专用说明。未为本轮提示词改动额外调用真实模型。
- 实体机已验证Episode编辑双态：初始显示“当前内容已保存到Checkpoint”且保存按钮禁用；临时改动后显示“有未保存修改，离开此页面会丢失”和高强调保存按钮。切页丢弃测试草稿后重新进入恢复已保存状态，测试字符未持久化。
- 安装后`com.example.chatbar/.MainActivity`为`RESUMED`、可见、首帧已绘制；真实模型多批补录尚未触发。
- 现有非阻塞编译警告未由本模块引入。
