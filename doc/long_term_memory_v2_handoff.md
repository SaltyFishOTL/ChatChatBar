# 长期记忆 v2 Handoff

Last updated: 2026-08-24
Branch/worktree: `master`
Baseline before V1: `966cea7c 优化聊天图片生成与再生成`
Latest stable commit: `532e460 Release 1.3.22`
Status: 已修复聊天中的AI生成图片错误进入RAG消息身份、导致删除图片时等待远程Embedding的问题。RAG v7只索引规范化后的非空文本消息；图片仍保留聊天与长期记忆source-turn身份。release已保数据部署到设备`49075ec2`，未调用真实模型或Embedding。

## Completed

- 2026-08-24聊天AI图片与RAG解耦：空正文AI生成图片消息此前虽不进入向量正文，仍进入完整source turn的`messageIds`并成为末尾assistant anchor；删除历史图片会因此重建整轮向量并等待远程Embedding。现`ChatMemoryIndexPolicy`只把规范化后非空文本消息放入RAG正文、`messageIds`和anchor，纯图片轮不建索引；内容版本升至7使旧错误块安全失效。删除图片或整条图片消息时先持久化并即时刷新UI、文件清理走IO，且纯图片消息不再等待RAG互斥或触发Embedding。长期记忆语义指纹仍保留图片及其source-turn变化，RAG所有权保持独立。
- 2026-08-21长期记忆模型预检错误自愈：Archive/HEAD/backfill曾把“对话模型未配置或缺少鉴权”持久化；模型恢复可用后，若Gap、来源修复或压缩选择使维护阶段提前返回，旧错误不会经过正常成功路径清除，造成对话可用但维护页持续报错。现协调器每次成功解析当前会话模型并通过同一鉴权检查后，先清除仅限`PREFLIGHT + 精确模型配置错误`的旧Archive/HEAD/backfill状态；真实网络、请求、输出校验及其他预检错误完整保留。纯策略回归测试与本地CI已通过。
- 2026-08-16 NovelAI名称策略分流：聊天生图必须把有效玩家名归一回`${'$'}username`，并让画面规划与最终Prompt设计请求全程保留该玩家角色标记；`${'$'}botname`仍渲染为`CharacterCard.effectiveBotName`。角色卡封面、朋友圈及Prompt工具继续在请求前渲染真实玩家/角色名称；会话玩家名覆盖优先于全局玩家名。规划Debug展示实际发送的system prompt。模板常量保持占位符，未配置名称时沿用项目现有未解析语义。
- 2026-08-16流式消息此前作为独立LazyColumn尾项显示，落盘后换成不同key的持久消息；长回复完成时列表丢失条目内偏移并跳回气泡顶部。中断路径又先刷新仓库、再清除流式状态，而刷新会按活动流式ID过滤刚保存的草稿，导致草稿直到下一次聊天刷新才重现。现流式、持久及重新生成替换版本始终按同一消息ID合并为一个时间线条目；持久化后先刷新同ID durable数据，再清流式覆盖。仓库刷新只隐藏明确的重新生成旧目标，不再隐藏普通流稿ID。滚动锚点直接基于合并时间线保存。
- 2026-08-14聊天回复重新生成期间，仓库刷新会把被替换的持久消息重新插回列表；图片完成又按旧列表重写消息记录，导致瞬态回复、旧回复及图片锚点顺序互相覆盖。现重新生成期间以稳定消息ID持续隐藏旧气泡，图片插入只写新增或实际变更的记录，并通过仓库互斥与持久`orderKey`保护避免并发覆盖。调试控制台新增“修复消息顺序”：按稳定source turn、创建时间与有效图片锚点链生成预览，只修改`orderKey`；确认前拒绝过期快照，写入前持久备份，消息后续新增/删除/更新时拒绝撤销。修复或撤销后仅刷新长期记忆来源变更状态，不触发AI。
- 2026-08-13崩溃报告显示自动维护捕获上游“会话不存在”后又调用`setMaintenancePreflightError`，第二次`loadLocked`抛出未捕获`IllegalStateException`。根因是会话删除先移除记录，但应用级自动维护、补录、完整/HEAD重建或压缩续跑仍可能存活。现协调器按会话登记所有Job；显式删除原子阻止新Job、取消并join现有Job，等待共享`AiBackgroundWorkManager.run`的`finally`释放前台保护lease后才删除会话；取消不再被普通错误catch转写为预检失败。协调器完成回调独立于协程主体执行，覆盖lazy Job未进入主体即被取消的窗口。删除同时先停止语音生成，持久化pending deletion仍保留失败恢复语义。
- 已实现HEAD、Episode、Arc、Era结构，连续source turn覆盖、派生显示T、预算压缩、独立分页历史、SaveSlot v4、编辑/恢复和完整注入预览。
- 自动Episode、手动补录、当前锚点、Archive压缩与HEAD不再把整份`MemorySessionState.revision`当作AI任务冲突锁。Episode只关联其source hash、目标pending成员和同源覆盖；压缩只关联模型实际读取的全部候选节点；HEAD只关联自身版本、输入source，`BACKFILL`额外关联实际发送的Archive文本。提交前在状态锁内重载并基于当前状态重放，因此无关聊天新增、其他节点编辑/新增、HEAD或分页变更不会误杀任务，也不会被旧快照覆盖。
- Archive失败操作已改为“历史归档”中文说明卡：解释会扫描未归档旧对话、生成Episode并按需压缩且不修改聊天原文。点击重试会在等待Archive锁/模型前立即显示转圈和耗时说明、暂时隐藏旧错误并阻止重复点击；任务完成后再以持久化结果刷新页面。
- 禁用期Gap即使位于直接上下文也可补录，但用户刚发送、尚无助手回复的开放source turn不再进入补录；等AI回复加入同一source turn并稳定后才可选，避免补录中途证据真实变化。
- Episode/Arc/Era活跃分页ID现按派生T升序持久化。中间节点编辑/替换或任意重排会在增删delta无法复现位置时保存精确顺序快照；若旧revision父链已错误物化，后续Checkpoint/纯新增同步会自愈为快照。加载旧状态时只自动修复节点齐全、T可验证的分页，并写入隐藏修复revision；缺节点或缺T不强排。恢复旧历史也先按当前派生T规范化。
- Episode/Arc/Era编辑卡已增加“AI重新生成此节点”：Episode只读其原始聊天轮，Arc/Era只读其有序直接子节点，错误节点正文不进入AI输入。三层`summary`均完整流式写入编辑框；校验重试先清除上一轮流稿，最终失败恢复请求前草稿。结果仍是未保存候选；用户确认并点击Checkpoint后才替换活跃节点。不同目标节点可真正并发；保存一个候选不会让其他节点失效。完成时只校验该目标仍活跃、不可变节点与原始聊天/直接子节点依据未变；目标自身被替换或依据变化仍拒绝。
- Episode/Arc/Era编辑卡持续比较草稿与已持久化正文：未修改时显示“已保存到Checkpoint”；手工编辑或AI候选产生后显示红色“未保存，离开会丢失”，并把保存按钮切为高强调未保存状态。只有保存成功并刷新出新节点后才恢复已保存提示。
- 长期记忆注入只包含Archive + HEAD；RAG、世界书、直接上下文、pending和原始对话不进入长期记忆块。
- Archive注入和完整预览只输出按派生T排序的节点正文，不再输出`[Episode/Arc/Era Tx-Tx]`或Legacy类型/T标识；节点层级与T范围只留在程序元数据和UI。Legacy仅保留“时间未知｜不代表当前进展”语义警告。
- 非空活跃节点正文不会再因sourceTurnId缺失、断裂或旧T映射异常被静默丢弃。T证明完整的节点先按时间排序，异常节点按创建时间稳定后置；UI显示数据完整性警告，时间线约束明确说明范围待修复。空正文仍不注入。
- 主聊天请求把非空Archive作为独立`system`消息放在世界书/RAG之后、HEAD之前，不再与其他动态资料合并；固定顺序为世界书→RAG→Archive→HEAD。发送前重新读取失败会直接报错；若内存预算表明有Archive正文但编译结果为空，或最终消息列表缺少Archive标记，请求会在联网前被阻止。调试控制台直接显示最终Request JSON中Archive/HEAD各自是否已发送。
- 独立Archive/HEAD在创建最终`ChatApiMessage`时会按当前玩家名与角色卡`effectiveBotName`渲染会话占位符；Bot名称非空白时保留原始内容（含换行），否则回退角色卡名称。支持`$username`、`$botname`、`{{user}}`/`{{char}}`、`{user}`/`{char}`和`<USER>`/`<BOT>`；持久化记忆正文保持原样，System Prompt调试预览显示实际渲染结果。
- Episode全局分组支持1–6轮、默认2；滑块位于全局设置并与上下文保留组数相邻。
- Episode AI协议已改为把1–N轮原文直接压成一个`summary`，不再生成逐T `sourceCoverage`。新节点不含逐T摘要；程序使用有序sourceTurnId、来源哈希和单段正文计算结构覆盖哈希。旧coverage节点继续兼容，不重写。
- Episode summary的Prompt目标为1T 50字、每增加1T加20字、默认2T 70字、6T 150字；程序硬上限为Prompt目标的2倍，即1T 100字至6T 300字，给AI字数估算保留有界容差。每次Prompt仍明确写入较短目标，并含错误逐T复述和正确跨轮融合示例。长期记忆所有AI阶段的截断、空输出、解析和校验错误统一最多5次输出尝试，覆盖压缩规划、补录Episode、正式Archive压缩和最终HEAD重建；瞬时网络/408/425/429/5xx独立最多3次，鉴权、不可重试HTTP和取消立即停止。
- Arc/Era压缩协议已从逐child保留正文改为两阶段筛选：规划AI读取同一候选集，只输出一句50字内取舍指南，`maxTokens=128`，清除继承的思考配置并仅向支持的模型发送关闭思考参数，不做程序字数校验、不持久化、不作为事实证据；规划与正式压缩各自最多5次输出尝试。压缩AI读取指南与原child，Prompt目标60–300字，程序接受50–400字且要求短于被消费正文；JSON阶段截断后按当前Token上限倍增并跨尝试保留，最高受4096和模型配置约束。最终错误明确显示压缩规划/正式压缩/Episode/HEAD及失败次数。提示词要求朴素客观、围绕主因果线和关键状态变化，禁止逐child复述、机械时间连接和华丽场景描写。Episode→Arc与Arc→Era新建时消费最老连续3–10个child，输入最多15个，第11–15个只作为末尾边界参考；Era→Era消费2–5个。AI只返回`consumedChildIds + summary`；`coverageUnits`由程序按child coverage hash生成。旧4–20/3–10父节点仍可读取、编辑、修复和重建，但新压缩不再产生该规模。
- 实体机完整重建曾高频复现“正式压缩：输出连续5次失败；最后错误：只能消费候选最老连续前缀”。根因是压缩提示词把任务描述为“判断是否构成完整Arc/故事线”，模型为找语义完整窗口而跳过最老child换窗口、或把第11–15个末尾参考也写入`consumedChildIds`，与程序“只能消费候选最老连续前缀”的位置性校验冲突，且重试回传的错误文本未说明如何修正。2026-08-03强化提示词：消费规则改为“最少3条，最多10条”（消除“第3至第10条”歧义），明确consumedChildIds必须从第1条开始原序连续、第11–15条任何情况不得消费、不能在“最老前缀”固定窗口外换窗口（不足以成事件时返回`compressible=false`）、程序会逐条比对。规划提示词同步改为“最少3个，最多10个”，Era重压缩改为“最少2条，最多5条”。配套渲染改动：`renderChildren`新增`markReferenceFromIndex`，普通压缩路径把第10条之后的候选在child标题中标为“末尾参考，不可消费”（修复/重建强制消费路径不标记）。`PromptTemplatesTest`按新协议token更新断言；真实模型上的复发率仍需长聊验收。
- 长期记忆字数上限的“增加 2000 字”按钮原只在Archive已超限时显示；用户误选“保持上限并压缩”后用量低于上限，按钮消失且declined标志残留，之后超限只会静默压缩，无法再手动扩大。2026-08-03修复：维护弹窗改为只要`memoryLimitChars < 20000`就显示“增加 2000 字”（抽出内部`MemoryLimitAction`组件）；手动`increaseLimit`与决策弹窗选择扩容都会重置三层`*CompressionPromptDeclined`标志，用户后续再次超限可重新被询问。新增`MemoryCompressionDecisionPolicyTest`扩容重置decline测试与`LongTermMemoryUiTest`手动扩容按钮可用性/上限隐藏测试；`ci.ps1 -SkipAssemble`通过。已通过`redeploy.bat --no-pause`在实体机`49075ec2`完成release保数据安装并启动（PID 20836）。
- 上下文与RAG按完整`sourceTurnId`共享身份边界：同轮追加AI回复/图片不拆成多个上下文组或新T身份；RAG仅可在同一身份内对超长正文分片。
- RAG完整轮正文超过600字时改为多卡片索引：每片从第300字起优先寻找第400字内的换行分段，其次寻找中英文句号，均无则在第350字切分；尾片保留剩余正文。所有分片继续共享原`sourceTurnId`、消息集合与T边界，仅用稳定`chunkIndex`区分；自动索引内容版本升至6，旧自动块可通过现有重建入口升级，手动块不受影响。
- 全局上下文保留组数现支持0；0不保留普通历史组，但仍保留Prompt末尾的完整上一轮和当前开放轮，所有更早完整轮进入归档边界。文档/记忆RAG召回数也分别支持0；某类为0时在仓库查询前禁用该来源，两类均为0时跳过整段RAG检索。
- 用户手动中断聊天生成时，非空助手流稿会作为普通助手消息落盘，继承当前用户轮的`sourceTurnId/sourceTurnOrder`并触发`REPLY_PERSISTED`维护；空正文不保存，也不触发完整回复专属的自动格式修复或朋友圈生成。
- 主聊天请求不再给历史、上一轮、重试输入或当前输入追加`[Txx]`；模型只看到原始角色与正文顺序。T继续作为内部记忆排序/UI元数据。AI生成图片留下的空助手记录、被“排除状态栏”清空的助手记录和其他空助手记录直接省略；流式完成但无正文时明确失败，不再持久化空助手回复。
- RAG存储与长期记忆节点保持独立；仅共享稳定source-turn边界。最终召回中仅`CHAT_MEMORY`卡片前注入一次对话记忆使用说明，指导模型在相关时把过往细节自然融入台词、行为和关系细节，禁止复述或说明来源；知识库文档卡不带该说明。
- Gap已改为持久缺失事实：扩大上下文只隐藏当前不可补录部分，不删除Gap；缩小上下文后重新显示。旧版误删Gap会执行一次幂等修复，并排除已有节点、普通pending和永久清空前剧情。
- 长期记忆页面刷新会重新读取最新全局上下文组数、原始消息、source turn时间线、已有覆盖、普通pending、Gap和来源哈希；上下文缩小后新滑出的未覆盖稳定轮会立刻登记为可补录Gap。刷新不调用AI，重复刷新不重复建Gap。
- 一键补录已区分本进程活跃runner与重启遗留`RUNNING`：内部读取不再暂停自己的任务；孤儿任务在重启后转`PAUSED`。
- 补录失败会显示具体原因和“重试补录”；成功批次设计为立即持久化并只移除对应Gap来源。
- 手动补录由应用级协调器持有并按会话去重，页面仅订阅处理轮数、已生成Episode数、当前阶段/T范围和流式summary；离开页面不取消已开始的模型调用，流文本仍只存在运行时，不写入SaveSlot或会话JSON。
- 被后续活跃记忆包围的未覆盖source turn会在任何加载/刷新中自动提升为持久Gap，并从普通Episode pending移除。实时backfill内部重载继续保留该Gap来源，避免模型已生成summary后因pending被清空而静默`Aborted`。批次选择另有独立兜底：旧的不足N轮/不连续segment保留待补录，但会跳过并处理后面首个满足精确N轮的连续segment，不再形成全局队头阻塞。
- 自动Archive历史追赶改为每次全局runner lease最多提交一个Episode；后面仍有精确批次时释放锁后再排下一次。失败重试的15/60/300秒退避也在锁外等待，避免一个旧会话长期独占维护runner。
- 一键补录若撞上正在执行的Archive原子步骤，先显示运行时`WAITING_FOR_ARCHIVE`：“补录已排队，当前步骤完成后开始”；此时不伪造持久`RUNNING`、不显示暂停按钮、不锁聊天。取得runner锁后才进入`PREPARING/RUNNING`。
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
- 长期记忆固定区现只保留单行容量/Archive/HEAD状态、维护/完整重建/HEAD重建/刷新四个可访问图标和层级Tab。错误、警告、扩容、来源修复、一键补录、压缩选择及全部运行进度移入可滚动“长期记忆维护”弹窗；任一详情或编辑页不再被维护卡片挤掉高度，维护图标用状态点提示待处理事项。
- 完整重建确认后先校验模型/鉴权/后台网络保护，再删除Archive、HEAD、各层历史版本、旧Gap与手工记忆编辑；保留聊天原文、会话字数上限和其他设置。随后把所有当前可归档原文登记为新Gap，直接复用一键补录的Episode批次、流稿、轮数/阶段进度、暂停、错误续跑、压缩选择和最终HEAD流程。
- `fullRegenerationPending`随状态与SaveSlot持久化；页面销毁或进程重启后可通过普通补录runner从剩余Gap继续，成功批次不回滚。末尾不足全局Episode目标的来源转回普通pending等待未来凑批，不生成违规单轮Episode，也不永久卡住完整重建。
- 完整重建或补录报错后用户无法非破坏性停止：维护弹窗只有“继续完整重建/重试补录”，且协调器在`fullRegenerationPending`期间每次维护触发（进聊天、新回复、网络恢复）都会无视ERROR/PAUSED自动重新enqueue补录，形成无法停止的重试循环；“取消”只能走删除记忆等破坏性路径，把已录入部分一起抛弃。2026-08-03修复：`MemoryRegenerationPolicy.abortFullRegeneration`纯策略清除重建标志并置backfill IDLE，剩余来源继续留在Gap等待普通补录，已录入节点/历史/手动编辑原样保留；服务层`abortFullRegeneration`在RUNNING时拒绝（须先暂停）；协调器仅当backfill为IDLE才自动enqueue（ERROR/PAUSED不再自动重试，暂停真正生效）；维护弹窗在完整重建ERROR与暂停状态增加“停止完整重建并保留已完成部分”按钮。新增`MemoryRegenerationPolicyTest`两条纯策略测试与`LongTermMemoryUiTest`失败重建展示中止按钮的instrumented测试；`ci.ps1 -SkipAssemble`通过。无连接设备，未重新部署。
- HEAD单独重建保留Archive和旧HEAD，读取当前可注入Archive与最新稳定剧情基线。Gap、被安全排除的stale根、Archive扩容/压缩选择不再阻塞该显式动作；证据变化会拒绝提交，失败保留旧HEAD并显示HEAD错误。

## In Progress

- 无代码进行中。两个最终确认路径故意未在用户真实会话执行；等待用户按需验收。

## Tried And Failed

- 旧长期记忆页把说明、两个全宽重建按钮、错误、警告、扩容、Archive重试、来源修复、补录和压缩选择全部固定堆在层级Tab上方；状态一多就把下面的HEAD、节点正文和编辑器压到零高度。现固定区只保留一行状态、四个图标和Tab，所有条件维护内容进入带状态点的可滚动维护弹窗。
- 旧完整重建另建`runFullRegeneration`逐Episode Archive循环，只暴露“正在完全重新生成”任务类型，未复用补录的阶段、轮数、T范围、流稿、暂停和错误状态。现预检与清空后生成新Gap，直接进入`startBackfill`；压缩选择续跑会先等待完整重建runner退出，末尾不足N轮转普通pending后正常收尾。
- 旧实现把进入直接上下文的Gap来源永久删除，导致上下文恢复后待补录提示消失。已改为持久Gap + 动态eligibility。
- 旧实现每次读取状态都把`RUNNING`改成`PAUSED`，导致补录AI调用后、首个Episode提交前静默退出。已用进程内活跃runner登记修复。
- 修复活跃runner后，手动补录启动点仍在`ChatViewModel.viewModelScope`；用户看到完整流式summary后退出页面会取消协程，使代码到不了Episode提交，重进后同一Gap再次提示。现补录任务和进度源均迁到应用级协调器，ViewModel仅观察。
- 旧刷新仅重读已有状态，且Gap修复版本完成后会提前返回；上下文缩小时新滑出窗口的未覆盖轮次不会被扫描。已新增显式条件同步刷新并移除错误短路。
- 旧实现先要求AI生成逐T `sourceCoverage`，再生成整体summary；即使隐藏coverage，模型输出和持久化仍近似随T线性增长。已改为直接多T单摘要，覆盖证明由程序生成。
- 旧Arc/Era压缩要求`childCoverage`逐child非空且“不得抛弃任何被消费child”，程序还用这些逐项文本判断压缩量，模型因此只能把Episode/Arc依次改写并拼成流水账。删除`childCoverage`后，单阶段150–600字Prompt仍易逐条复述且文风华丽。现增加独立短规划请求先决定主线与删减项，再由压缩请求输出60–300字目标的朴素总结；程序仅按50–400字容错、连续来源和实际缩短校验。
- 新增压缩规划后最初误设`maxAttempts=1`，导致规划阶段Token截断、空输出或普通输出错误第一次即终止，正式压缩的5次重试根本不会开始；旧JSON截断重试还在每轮外层尝试中重置Token上限。现所有AI阶段统一5次输出尝试，JSON截断扩容跨尝试保留，并把瞬时传输3次与输出5次分开计数；最终错误携带阶段和次数。
- 旧状态可能出现“活跃记忆已覆盖T47以后、T46未覆盖但不在`state.gaps`”的隐式内部缺口。自动维护把T46混入普通pending首位，精确2轮批次因T46与后续来源不连续而永远停在T52；手动补录虽能用隐式覆盖检测选出T46，但AI生成后的`loadLocked`只按显式Gap保留backfill pending，于是把T46删除、状态改为`IDLE`，提交走静默`Aborted`。现加载时无条件把受后续活跃记忆包围的未覆盖来源提升为durable Gap，并从普通pending剥离。
- 修复T46队头阻塞后的首次真实旧会话追赶并非死锁，但自动Archive在一个全局runner lease内串行生成/压缩全部历史批次，再让一键补录排队；页面约10分钟只显示同一加载文案，ViewModel还把排队补录伪装成`RUNNING`并锁聊天。设备现场最终从1830/2000收敛到1286/2000、T46告警消失，证明任务能完成。现改为一Episode一lease、锁外退避及`WAITING_FOR_ARCHIVE`真实排队态。
- 旧RAG按相邻消息对分块，遗漏序章和同轮追加回复。已改为完整source turn分块。
- 旧revision用“删旧ID，再把新ID追加末尾”重建编辑后的中间节点，导致底层分页乱序；UI又在展示前排序，因此肉眼正常但完整性检查持续报警。已改为位置敏感快照 + 加载期隐藏修复revision，未通过关闭告警掩盖问题。
- 旧节点重新生成共用整会话Archive锁，并绑定全局`state.revision`；多个任务实际串行，保存任一候选都会误杀其他未变节点。已移除整会话锁和全局revision守卫，改为节点及其证据级校验。
- 旧自动Episode、手动补录、压缩和HEAD部分路径仍绑定整会话`state.revision`。聊天新增、无关HEAD滚动或其他节点提交都会提升revision，导致正确AI结果被当作竞态丢弃；两个回复后的Archive/HEAD后台任务也会互相误杀。现统一改为操作证据集合校验并重载rebase；全局revision仅保留为单调持久化版本号。
- 旧Archive失败按钮使用未解释的“重试 Archive 维护”，且ViewModel只在整个任务结束后刷新；等待同会话Archive锁或模型期间页面持续显示旧错误，看似点击无效。现以独立运行时状态即时反馈，不伪造不可计算的百分比。
- 旧扩容/压缩选择在页面`viewModelScope`内先解析模型，再直接执行完整Archive或补录模型流程；只有全部完成才刷新UI。因此完整重建弹出“近期流程准备压缩为事件总结”后，点击按钮仍保留菜单，看似完全无响应，离页还会取消后续付费任务。现选择先原子落盘并即时从UI移除，模型续跑交给应用级协调器；协调器等待产生选择的旧runner退出，并保证连续多层选择不会被旧续跑去重吞掉。
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

- 模型配置已恢复可用、同时存在Gap/来源修复/压缩选择等早退条件时，维护页旧鉴权错误应立即消失且真实维护状态保留；纯策略测试已通过，真实设备/真实模型待手动回归。
- 尚未在会真实调用模型的测试会话中，一边执行长期记忆自动维护/补录/重建一边删除会话；自动测试已覆盖活跃Job取消并等待、删除后拒绝新Job、其他会话Job不受影响。未为该破坏性复现额外消耗API额度或删除用户现有会话。
- 真实旧会话尚未手动执行RAG自动块重建来观察超长T轮次的多卡片结果；纯策略与身份测试已覆盖拆分优先级、边界、稳定分片ID和同轮旧块清理。本轮未调用真实向量API。
- 真实用户旧会话尚未手动抓取最终API请求确认Archive正文、Bot名称覆盖、占位符替换与历史消息；纯策略和最终JSON序列化测试已覆盖正文保留、独立消息、Archive/HEAD多行Bot名称及兼容别名替换、HEAD-only拒绝和空历史过滤。本轮无连接设备，未做交互验收。
- T46隐式内部缺口提升、应用级补录runner及后续Archive追赶已由用户真实旧会话完成：T46告警消失，Archive从1830/2000经生成/压缩收敛到1286/2000。新有界runner与排队UI尚待部署后复核。
- 真实长聊下新3–10/2–5消费窗口、低层级额外5个末尾参考、两阶段规划与60–300字筛选质量、三层扩容询问、`compressible=false`链、Era平级压缩和多批补录仍缺少完整端到端证据。
- 多节点AI重新生成及其真实并发流式表现尚待用户再次调用模型验证；自动测试已覆盖流稿、候选不自动保存、无关节点Checkpoint不失效、目标/依据变化仍拒绝。
- 旧SaveSlot、补录暂停后继续仍需真实数据手动回归。
- 自动Episode/HEAD与用户继续聊天并发、手动多批补录期间继续聊天、压缩期间编辑无关节点，纯策略测试已通过；真实服务instrumented测试已编译但因当前无设备且SDK缺`emulator.exe`未执行，仍待真实长聊和真实模型手动回归。
- Archive失败重试的用途说明、即时运行态和重复点击隐藏已由Compose测试覆盖并完成Android测试源码编译；尚未在连接设备上用真实慢模型手测等待过程。
- 来源修改/整轮删除后的警告、安全注入、分批暂停/继续、Arc/Era结构降级和HEAD重建已有纯策略、序列化与UI测试；真实长会话和真实模型输出尚未手动回归。

## Unconfirmed

- 用户T23测试卡在最新source-turn分组下应显示的精确待补录T范围，取决于当前已覆盖节点与稳定轮分组；不再用RAG块数推断。

## Blockers

- 无代码阻塞。真实NovelAI请求会消耗模型与图片额度，本轮未触发。

## Recommended Next Steps

1. 在曾显示“对话模型未配置或缺少鉴权”的会话确认当前模型仍可正常对话；打开长期记忆维护或点击重试，确认旧错误消失。若同时存在Gap、来源修复或压缩选择，确认这些真实状态仍保留。
2. 部署后打开长期记忆各层“当前/编辑/历史”，确认固定顶部仅一行状态与四个图标，正文和编辑区始终获得剩余高度；点击滑杆图标确认错误、补录、修复、选择和进度都在可滚动维护弹窗内。
3. 在可消耗额度的测试会话确认完整重建；核对清空后维护弹窗显示与一键补录相同的准备/生成T范围/检查空间/保存/HEAD阶段、轮数、Episode数与流稿，暂停和继续不再次清空已完成结果。
4. 在存在旧Episode/Arc/Era的真实会话打开完整预览并抓取一次API请求，确认Archive含全部非空正文，所有user/assistant聊天消息都没有程序追加的`[T数字]`前缀。
5. 选中一条明显错误的Episode/Arc/Era，点击“AI重新生成此节点”；先核对候选，确认错误正文没有影响结果，再决定是否保存Checkpoint。
6. 在有Episode/Arc/Era的测试会话修改一条历史消息；打开长期记忆页，确认出现修复警告，旧受影响根/HEAD不再进入完整预览。点击修复，观察阶段、流式摘要和逐根完成数。
7. 删除一个位于压缩节点中间的完整source turn；确认修复不会生成跨删除缺口的Episode/Arc/Era，且暂停/继续或失败重试不会丢失已完成根。
8. 把全局上下文保留组数从较大值降到最低，再打开长期记忆页或点击刷新；确认新滑出窗口的T范围立即显示“一键补录长期记忆”。
9. 再把上下文扩大并刷新，确认相应范围只暂时隐藏；缩小后刷新应重新出现，且不产生重复Gap。
10. 若失败，记录页面展示的完整失败原因；不要重建RAG或清数据。
11. 在专用测试会话触发慢速长期记忆维护后，从首页删除该会话；确认删除完成、后台通知消失、应用继续存活，且会话不会被后台错误回写恢复。

## Architecture Notes

- 维护此模块前使用`chatbar-long-term-memory`；稳定约束见其`references/invariants.md`，状态机见`references/state-machines.md`。
- `sourceTurnId`是持久身份；T是派生显示。上下文、Episode和RAG数据所有权不同，但必须共享完整轮边界。
- Gap表示历史缺失事实；当前上下文只控制是否可补录。不要再次把二者合并为同一可变列表。
- 活跃分页ID必须按派生T升序；UI排序不能替代持久层顺序。revision delta只有在从父revision能精确复现节点顺序时才可使用，否则保存快照。
- 重新生成是节点级只读候选任务，不持有Archive整会话锁，也不绑定全局revision。失效边界是目标活跃身份、不可变目标节点和该节点确切依据。
- 所有直接提交AI任务都必须绑定其实际输入证据并在提交锁内重载rebase：Episode=目标source/pending/覆盖，压缩=全部模型候选节点，HEAD=HEAD版本+输入source，回填HEAD再加Archive文本。全局revision不是冲突键。
- HEAD、Archive、RAG独立失败和持久化；主聊天不等待后台记忆任务。
- 自动Archive→HEAD及手动Gap补录由应用级协调器持有；聊天页面只能订阅运行时进度，不能拥有或取消付费模型任务。
- 页面销毁/切换会话不取消已开始任务；显式删除会话必须先阻止该会话新任务并cancel-and-join所有协调器Job，再删除主记录与派生数据，避免错误回写复活会话。
- 来源变更检测与修复分离：检测只刷新过期状态和安全注入；用户按钮才启动AI。修复必须沿不可变节点依赖向上重算，不能通过改哈希认可旧正文。

## Verification Baseline

- 2026-08-24聊天AI图片与RAG解耦：`redeploy.bat --no-pause`通过release Kotlin编译、Lint Vital、打包和签名检查，并在唯一授权设备`49075ec2`完成保数据安装与启动。新增`ChatMemoryIndexPolicyTest`覆盖同轮纯图片消息不进入RAG正文/`messageIds`/anchor、纯图片轮不建索引及v6块失效；按项目规则未运行自动测试。未触发真实Embedding或长期记忆模型调用。
- 2026-08-21长期记忆模型预检错误自愈：`redeploy.bat --build-only --no-pause` release构建通过，包含release Kotlin编译、lint vital、打包与签名检查。新增`MemoryModelPreflightPolicyTest`覆盖只清除精确模型配置预检错误，并保留同文案的非预检失败及其他backfill错误；按项目规则未运行测试。`adb devices -l`无连接设备，未部署、未调用模型。
- 2026-08-16中断草稿、流式完成滚动及NovelAI名称策略：按用户确认，聊天场景、规划system及最终设计system保留`${'$'}username`，真实玩家名归一为该标记，`${'$'}botname`仍渲染；其他生图路径继续渲染名称。`:app:compileDebugKotlin`、全量JVM测试、`ci.ps1 -SkipAssemble`与`redeploy.bat --build-only --no-pause`通过，Android测试源码编译成功。此前设备`49075ec2`已安装上一轮release；最终例外修正时ADB无设备，未部署最新APK。未调用真实模型或NovelAI。
- 2026-08-14并发消息排序与历史修复：定向`ChatMessageOrderingTest`、`ChatMessageRefreshPolicyTest`、`ChatMessageOrderRepairPolicyTest`、`ChatRepositoryTest`通过；全量`gradlew test --rerun-tasks`与`ci.ps1 -SkipAssemble`通过，Android测试源码编译成功。覆盖图片锚点插入不改并发回复键、重新生成刷新隐藏旧气泡、source-turn/链式图片修复、孤儿/循环锚点保守处理、修复前快照拒绝、持久备份、撤销及后续消息更新时拒绝撤销。首次CI因沙箱禁止下载固定Gradle 9.1失败，授权网络后同一命令通过。最终实体机`49075ec2`经`redeploy.bat --no-pause`完成release保数据安装与启动命令，进程PID `18805`持续存活，最近`AndroidRuntime`错误为空。未在用户旧聊天执行修复按钮，未调用模型。
- 2026-08-13删除会话与长期记忆维护竞态修复：定向`SessionScopedJobRegistryTest`、`:app:compileDebugKotlin`、全量`gradlew test`与`ci.ps1 -SkipAssemble`通过；覆盖删除时取消并join活跃Job、删除后拒绝新Job、其他会话Job继续运行。首次CI因沙箱禁止下载固定Gradle 9.1失败，授权网络后同一命令通过。实体机`49075ec2`通过`redeploy.bat --no-pause`完成release保数据安装并启动，PID `22590`，`MainActivity`为`topResumedActivity`，启动后`AndroidRuntime`无错误。未执行会消耗模型额度并删除会话的真实竞态复现。
- 2026-08-11 RAG超长完整轮分片：定向`ChatMemoryIndexPolicyTest`/`ChatMemoryIdentityTest`、全量`gradlew test`与`ci.ps1 -SkipAssemble`通过；覆盖600字不拆、300–400字窗口内分段优先、句号次选、300字边界、无标点350字硬切、分片ID及同轮多块替换。实体机`49075ec2`通过`redeploy.bat --no-pause`完成release保数据安装并启动，进程PID `4503`，`MainActivity`为resumed且持有窗口焦点。未执行真实RAG重建、未调用向量API。
- 2026-08-11零上下文/分来源零召回：`:app:compileDebugKotlin --rerun-tasks`、定向`ContextWindowManagerTest`/`RagSourcePlanTest`与`ci.ps1 -SkipAssemble`通过。测试覆盖0窗口仅保留上一轮并归档全部更早组，以及文档0、记忆0、两者0和0上下文召回边界。`adb devices -l`无连接设备，未部署。
- 2026-08-02 Arc/Era两阶段筛选式压缩与AI重试修复：定向`MemoryCompressionPolicyTest`、`PromptTemplatesTest`、`StreamingChatServiceThinkingTest`、`MemoryAiRetryTest`、`MemoryAiFailurePolicyTest`、全量`gradlew test`与`ci.ps1 -SkipAssemble`通过，Android测试源码编译成功。策略覆盖50/400程序边界及Era最低可压缩输入；Prompt覆盖60–300目标、禁止逐child/华丽描写、50字内无思维过程规划；请求体覆盖规划`maxTokens=128`、隔离角色扮演参数、不继承思考配置、关闭受支持模型思考且不请求JSON。重试覆盖规划/正式压缩/Episode/HEAD输出错误5次、可恢复传输错误3次、HTTP 400立即停止、截断Token上限1800→3600→4096跨尝试保留，以及失败阶段/尝试次数持久化。早先实体机`49075ec2`已通过`redeploy.bat --no-pause`完成release保数据安装并启动，进程PID `11693`；重试修复后`adb devices -l`无连接设备，未重新部署。未触发真实压缩或模型调用，两阶段摘要质量仍待真实长聊验收。
- 2026-08-02长期记忆紧凑UI与重建复用补录：强制`:app:compileDebugKotlin --rerun-tasks`、定向`MemoryRegenerationPolicyTest`、全量`gradlew test`及`ci.ps1 -SkipAssemble`通过，Android测试源码编译通过。纯策略覆盖重建Gap初始化、时间线断裂分组、末尾不足N轮转普通pending和暂停保留；instrumented源码覆盖清空后直接进入真实`startBackfill`并完成HEAD/flag；Compose源码覆盖图标二次确认及完整重建复用补录进度文案。`adb devices -l`无连接设备，未部署、未调用真实模型。
- 2026-08-02压缩选择无响应修复：`:app:compileDebugKotlin`、定向`MemoryCompressionDecisionPolicyTest`、全量`gradlew test`及`ci.ps1 -SkipAssemble`通过，Android测试源码编译通过。新增纯策略覆盖立即清空选择、拒绝标记、完整重建pending保留、补录续跑归属和最高上限错误；真实仓库服务测试确认选择提交与状态恢复发生在任何模型调用前；Compose测试覆盖“保持上限并压缩”回调及菜单立即关闭。`adb devices -l`无连接设备，未部署、未点击真实选择、未调用模型；真实长会话菜单交互待用户验收。
- 2026-08-01手动完整/HEAD重建：`:app:compileDebugKotlin`、全量`gradlew test`及`ci.ps1 -SkipAssemble`通过，Android测试源码包含两个确认弹窗、Token警告和确认回调场景并成功编译。物理设备`49075ec2`通过`redeploy.bat --no-pause`完成release保数据安装，进程PID `17561`。未打开真实长聊、未点击任一最终确认、未调用模型，避免自动维护或重建消耗API额度。
- 2026-08-01隐式内部Gap修复：定向`MemoryBackfillPolicyTest`、`MemoryEpisodeBatchPolicyTest`与全量`gradlew test`通过，`ci.ps1 -SkipAssemble`通过并编译Android测试源码。回归覆盖隐式单轮缺口转durable Gap、从普通pending剥离、实时backfill在内部重载后保持`RUNNING`与目标来源、后续普通2轮批次继续、旧不足1/2轮segment不阻塞后续精确2/3轮segment，以及重复加载幂等。补录提交若异常变`IDLE`会显示具体错误，不再静默停止。物理设备`49075ec2`以`redeploy.bat --no-pause`完成release保数据安装和启动；真实T46模型补录待用户验收。
- 2026-07-21补录页面生命周期修复：`:app:compileDebugKotlin`、`:app:compileDebugAndroidTestKotlin`、`ci.ps1 -SkipAssemble`及完整`ci.ps1`通过；新增instrumented场景在summary已可见后销毁页面观察者，应用级任务仍完成Episode、HEAD和持久状态。`adb devices -l`无设备，未执行连接测试或部署；Skill校验脚本因运行环境缺`PyYAML`未执行，frontmatter与结构人工检查通过。
- 2026-07-27 Bot名称拆分：定向角色卡兼容、schema 7传输、Prompt普通/缓存/World Book outlet及`ChatRequestMemoryPolicyTest`通过；全量`app/.\gradlew.bat test`、`:app:compileDebugKotlin --rerun-tasks`和`app/ci.ps1 -SkipAssemble`通过。最终请求JSON测试确认Archive/HEAD使用多行`effectiveBotName`替换兼容别名；`adb devices -l`无连接设备，未部署或调用真实模型。
- 2026-07-18 Archive重试UX切片：`:app:compileDebugKotlin --rerun-tasks`与`ci.ps1 -SkipAssemble`通过；新增Compose测试覆盖失败说明/按钮回调和点击后的即时运行反馈。`adb devices -l`无连接设备，未部署或调用真实模型。
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
