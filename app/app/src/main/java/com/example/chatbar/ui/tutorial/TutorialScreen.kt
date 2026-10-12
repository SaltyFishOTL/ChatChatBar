package com.example.chatbar.ui.tutorial

import com.example.chatbar.ui.kit.AppIcons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme

private data class TutorialPage(
    val title: String,
    val summary: String,
    val sections: List<TutorialSection>
)

private data class TutorialSection(
    val title: String,
    val steps: List<String>
)

private val tutorialPages = listOf(
    TutorialPage(
        title = "欢迎使用 ChatBar",
        summary = "用几分钟了解开始聊天前最常用的设置。",
        sections = listOf(
            TutorialSection(
                title = "本教程包含",
                steps = listOf(
                    "设置默认模型、格式卡和玩家设定",
                    "申请并配置硅基流动 API",
                    "进阶单元：使用非硅基流动 API，并配置新模型",
                    "配置 NovelAI，并在聊天中生成图片",
                    "配置 Fish Audio、绑定角色音色并生成语音",
                    "添加 RAG 文档、刷新索引和关闭 RAG"
                )
            )
        )
    ),
    TutorialPage(
        title = "开始对话前的设置",
        summary = "先完成默认对话设置和玩家设定，再创建角色开始聊天。",
        sections = listOf(
            TutorialSection(
                title = "默认对话",
                steps = listOf(
                    "进入“管理 > 设置”。",
                    "进入“模型与连接”选择默认对话模型；进入“回复与记忆”选择默认格式卡。",
                    "点击“保存全局设置”。"
                )
            ),
            TutorialSection(
                title = "玩家设定",
                steps = listOf(
                    "返回设置分类，进入“玩家身份”。",
                    "填写玩家名称和玩家全局设定。",
                    "点击顶部保存按钮。玩家名称和设定会参与后续对话。"
                )
            )
        )
    ),
    TutorialPage(
        title = "配置硅基流动 API",
        summary = "使用自己的硅基流动 API Key 调用内置模型。",
        sections = listOf(
            TutorialSection(
                title = "申请 API Key",
                steps = listOf(
                    "访问 cloud.siliconflow.cn，并登录硅基流动账号。",
                    "进入控制台的“API 密钥”页面。",
                    "点击“新建 API 密钥”，创建并复制 API Key。"
                )
            ),
            TutorialSection(
                title = "在 ChatBar 配置",
                steps = listOf(
                    "进入“管理 > 设置”。",
                    "进入“模型与连接”。",
                    "在“全局默认 API Key”中粘贴密钥，点击顶部保存。",
                    "点击“测试连接”；对话和向量接口均成功后即可使用。"
                )
            )
        )
    ),
    TutorialPage(
        title = "进阶单元：其他 API 与新模型",
        summary = "ChatBar 可接入非硅基流动的 OpenAI 兼容 API，可新增对话与向量模型；检索规划使用当前场景所选对话模型。",
        sections = listOf(
            TutorialSection(
                title = "使用非硅基流动 API",
                steps = listOf(
                    "在服务商控制台复制 OpenAI 兼容 Base URL、API Key 和模型标识。",
                    "进入“管理 > 模型”，点击右下角“新建”。",
                    "在“接口模板类型”中选择服务商对应模板；通用 OpenAI 兼容接口通常选择 OPENAI。",
                    "填写 Base URL 和 API Key 后，点击模型标识旁的搜索图标，首次会自动获取列表。窗口支持多关键词搜索、模型系列筛选和名称排序；获取后可点刷新图标更新列表。选择模型后，空白显示名称会自动填入，也可手动填写标识。完成后保存并测试连接。"
                )
            ),
            TutorialSection(
                title = "配置新模型",
                steps = listOf(
                    "如多个模型共用同一 Key，可进入“管理 > 设置 > 模型与连接”填写“全局默认 API Key”，单个模型的 API Key 留空。",
                    "保存模型后，进入“管理 > 设置 > 模型与连接”，选择默认对话模型。",
                    "如服务商提供向量接口，可在“管理 > 模型 > 向量模型”点击“添加”，填写 Base URL、API Key、模型名称和向量维度，让 RAG 可以建立索引。",
                    "聊天中的 RAG 检索规划使用当前会话的对话模型；角色卡与世界书的资料研究使用本次生成所选模型。"
                )
            )
        )
    ),
    TutorialPage(
        title = "配置并使用 NovelAI 生图",
        summary = "保存 NovelAI Token 后，可根据助手回复直接生成图片。",
        sections = listOf(
            TutorialSection(
                title = "获取 Token",
                steps = listOf(
                    "访问 novelai.net，并登录 NovelAI 账号。",
                    "打开“User Settings > Account”。",
                    "点击“Get Persistent API Token”。",
                    "生成 Token 后点击复制。"
                )
            ),
            TutorialSection(
                title = "在 ChatBar 配置",
                steps = listOf(
                    "进入“管理 > 设置 > 图片生成”。",
                    "粘贴 Persistent API Token并点击“保存”。",
                    "保存成功后，聊天页会显示生图操作。"
                )
            ),
            TutorialSection(
                title = "在聊天中生成图片",
                steps = listOf(
                    "进入任意可用对话，等待助手生成一条非空回复。",
                    "点击助手回复下方的图片按钮；ChatBar 会先设计 NovelAI Prompt，再开始生成图片。",
                    "生成卡片会显示当前阶段和流式预览；完成后图片会附在对应回复中。",
                    "点击生成图片可全屏查看。失败时可在生成卡片中重试或关闭提示。"
                )
            )
        )
    ),
    TutorialPage(
        title = "配置并使用 Fish Audio 语音",
        summary = "配置 API Key、为角色绑定音色，再把对白、内心、旁白或整条消息生成为语音。",
        sections = listOf(
            TutorialSection(
                title = "配置 Fish Audio",
                steps = listOf(
                    "从 Fish Audio 官方控制台获取 API Key。",
                    "进入“管理 > 设置 > 语音与朗读 > Fish Audio 密钥”，粘贴 API Key 并点击“保存密钥”。",
                    "选择 TTS 模型和语音翻译/标签模型，再保存全局设置；留空时使用当前会话对话模型，听书模式未设置语音语言时不会调用此模型。",
                    "需要朗读旁白或整条消息时，可在这里开启“听书模式”；会话设置还能选择跟随全局、单独开启或单独关闭。",
                    "会话设置中的“语音使用语言”默认留空并朗读原文；填写语言后，每次生成会先翻译对应气泡，普通模式再添加标签，听书模式直接合成。"
                )
            ),
            TutorialSection(
                title = "为角色绑定音色",
                steps = listOf(
                    "进入“管理 > 角色”，创建或编辑角色卡。",
                    "分段模式编辑人物设定；自由模式在“人物头像册”添加或编辑人物，人物名称需与聊天中的说话人物名一致。两种模式都支持独立绑定音色。",
                    "在人物的“Fish Audio 音色”中点击“选择音色”，可切换“社区音色”和“我的音色”进行检索。",
                    "试听满意后点击“使用此音色”，再保存角色卡。每个需要说话的人物都要分别绑定。"
                )
            ),
            TutorialSection(
                title = "生成和播放语音",
                steps = listOf(
                    "进入该角色卡的聊天，长按有明确人物名的对白或内心气泡。",
                    "选择“为本段生成语音”或“为整条生成语音”。听书模式下，分段气泡可朗读旁白；关闭分段气泡后会把整条消息合成为一个语音。",
                    "单角色卡自动使用唯一角色音色；多角色卡朗读旁白或整条消息时，先选择角色音色。",
                    "设置语音语言后，进度卡依次显示 AI 翻译、AI 标签设计和 Fish Audio 合成；听书模式跳过标签设计。",
                    "若 AI 标签文本改变了输入口播，先查看差异并决定是否使用；确认后才会继续合成。",
                    "生成成功后会自动播放。点击气泡下方的语音条会从头播放，并自动续播同条消息内后续语音；语音内容不会进入对话上下文或长期记忆。"
                )
            )
        )
    ),
    TutorialPage(
        title = "使用 RAG 文档",
        summary = "RAG 会从参考文档中找出相关内容，帮助模型记住世界观和角色资料。",
        sections = listOf(
            TutorialSection(
                title = "添加与刷新索引",
                steps = listOf(
                    "进入“管理 > 角色”，打开需要编辑的角色卡。",
                    "在“参考文档”中新建文档，或批量导入文档。",
                    "编辑完成后点击角色卡顶部“保存”。",
                    "保存会检查文档变化，并自动刷新 RAG 索引；未变化文档会跳过。"
                )
            ),
            TutorialSection(
                title = "关闭 RAG",
                steps = listOf(
                    "进入“管理 > 设置 > 回复与记忆 > 检索参数”。",
                    "把“注入强度”调为“关闭”。",
                    "点击“保存全局设置”。"
                )
            )
        )
    )
)

private val advancedTutorialPages = listOf(
    TutorialPage(
        title = "欢迎来到进阶教程",
        summary = "这里集中说明不容易被发现的长按操作、二级菜单和快捷手势。",
        sections = listOf(
            TutorialSection(
                title = "操作提示",
                steps = listOf(
                    "看到列表项、消息气泡、图片或小图标时，可尝试长按打开更多操作。",
                    "需要编辑、导出、删除或覆盖内容的操作，通常位于长按菜单或预览页的二级操作中。",
                    "首页、朋友圈、社区和管理四个根页面支持左右滑动切换；未开启的模块不会出现。"
                )
            )
        )
    ),
    TutorialPage(
        title = "会话列表的长按操作",
        summary = "首页的会话卡片不只有点击进入聊天。",
        sections = listOf(
            TutorialSection(
                title = "改名、置顶与删除",
                steps = listOf(
                    "进入首页，长按任意会话卡片。",
                    "选择“修改显示名称”，可为当前会话设置独立名称；清空后保存会恢复跟随默认名称。",
                    "选择“置顶”，可把常用会话移到顶部；再次长按可取消置顶。",
                    "选择“复制会话”，会创建独立副本，保留过往消息、当前长期记忆、检索记忆、会话设置、图片和语音；完成后可直接打开副本。修改或删除副本不会影响原会话。",
                    "选择“删除聊天”，会同时删除聊天记录和对应记忆索引。"
                )
            )
        )
    ),
    TutorialPage(
        title = "消息与片段操作",
        summary = "长按聊天内容，可编辑、删除、重新生成或制作长截图。",
        sections = listOf(
            TutorialSection(
                title = "整条消息",
                steps = listOf(
                    "长按一条消息的空白区域，打开“消息操作”。",
                    "可编辑或删除该消息；最后一条可重新生成的回复还会显示“重新生成”。重新生成失败时，长按尾部错误消息也可直接重试。",
                    "助手消息可选择“AI 修复格式”，按当前格式卡与分段气泡规则检查并流式覆盖整条回复；修复完成后可恢复原文。",
                    "配置 Fish Audio 后，助手消息菜单可选择“为整条生成语音”；普通模式只补齐尚无语音且能匹配人物音色的对白和内心段。听书模式关闭分段气泡后会朗读整条消息。若 AI 标签文本改变了原文，会展示差异，确认使用后才合成。",
                    "选择“多选”进入长截图模式，继续点选消息或片段，再预览、保存或分享。"
                )
            ),
            TutorialSection(
                title = "分段回复",
                steps = listOf(
                    "开启“助手回复分段气泡”后，长按片段在附近打开浮动操作菜单，小尖角指向片段；点击外部或按返回关闭。空间不足时菜单自动调整位置，内容可滚动。",
                    "状态栏点击可展开或折叠；长按标题或正文打开片段操作，关闭分段气泡时打开整条消息操作。复制状态栏请使用操作菜单中的复制。",
                    "片段菜单顶部为“重新生成”和“修复格式”，作用于整条回复；下面本段行用单页图标和中性浅底，整条行用叠层图标和主题色浅底。图标下有复制、编辑、截图、删除等小字；点击“截图”加入长截图。窄屏可横向滑动操作行。多选时点消息左上角小圆可勾选或取消整条，状态栏也可直接点选。",
                    "长按有明确说话人物且已绑定音色的对白或内心气泡，可点击本段行或整条行的“语音”；听书模式下也可长按旁白块生成语音。",
                    "点击语音条会立即抢占当前播放、从头播放并自动续播同条消息内后续语音；长按语音条可编辑标签后文本、重新生成、下载、删除，或实验性发送为标准手机 QQ 的原生语音气泡。下载文件保存在手机 Download 目录，文件名包含聊天名、角色名和下载时间。语音条可作为独立块加入长截图。",
                    "QQ 实验发送使用扬声器外放、QQ 麦克风重录：首次进入先阅读权限与隐私说明并启用“ChatBar QQ 语音实验助手”；随后自行在 QQ 选择聊天并切到“按住说话”，再下拉通知栏点“开始”。ChatBar 倒计时后自动按住、播放、松开；最多发送前 50 秒，超出部分舍弃。开始前须断开耳机、蓝牙及 USB 音频并调高媒体音量。可从通知栏取消；离开 QQ、找不到固定录音按钮或手势失败时任务会停止，松开后仍须自行在 QQ 确认结果。TIM、QQ 轻聊版及 QQ HD 不支持。",
                    "片段菜单中的“AI 修复格式”仍会修复所属整条助手消息。",
                    "最后一条回复的片段菜单还可重新生成整条回复。"
                )
            )
        )
    ),
    TutorialPage(
        title = "图片与 NovelAI 隐藏操作",
        summary = "聊天图片、全屏预览和生图按钮都提供长按入口。",
        sections = listOf(
            TutorialSection(
                title = "聊天图片",
                steps = listOf(
                    "点击图片进入全屏预览；左右滑动可查看上一张或下一张，底部会显示当前张数和总张数。",
                    "长按生成图片，可分别修改画风 Prompt、基础 Prompt、角色提示词、负面提示词和图片比例。普通与全屏输入均支持中英文 Tag 预测和实时翻译，优先匹配 Danbooru，词典补充未命中项；底部固定预测栏与生图工作室一致，输入后候选实时出现并排序，点击候选插入当前输入位置；点击翻译图标切换实时翻译，全屏确认保存、取消放弃。重新生成使用新 seed；不修改比例时保留原图尺寸，也可删除图片。",
                    "文本正在生成时也可长按图片并重新生图。",
                    "普通聊天图片长按会直接进入删除确认。"
                )
            ),
            TutorialSection(
                title = "全屏图片",
                steps = listOf(
                    "在全屏预览中长按当前图片，打开“图片操作”。",
                    "角色卡 AI 封面候选也可点击查看完整图片；全屏长按可打码、保存、分享。候选下方“裁剪头像”可单独调整头像，应用时背景保留完整原图。",
                    "选择“打码”进入工具后，点击“去除元数据与像素隐写”或“完成”均会清除文件元数据及像素最低位中的隐藏参数，导出 PNG 副本；颜色与透明度可能有极轻微变化，原图保留。随后可保存或分享新副本；旧版处理的图片需重新清理。APNG伪装不属于去元数据操作。",
                    "普通图片可选择马赛克、黑色或白色笔刷并调整尺寸后涂抹，也可旋转 90 度；点击“APNG伪装”会生成聊天中默认显示 ChatBar Logo、点开后显示真实画面的 APNG。再次导入 ChatBar 伪装图时，按钮自动变为“逆向还原”。GIF 与 APNG 为避免丢失动画，不提供涂抹、旋转、去元数据或普通完成。",
                    "选择“定位消息”，会关闭全屏图片并跳到聊天记录中这张图片所在的消息。"
                )
            ),
            TutorialSection(
                title = "生图工作室",
                steps = listOf(
                    "点击工作室输出大图或历史详情大图进入全屏预览；在全屏中长按可打码、保存、分享。历史删除或清空只删除应用缓存，不会删除图库副本。",
                    "在生图历史网格中长按图片进入多选；单图角标表示选择顺序。再次长按已选图片会显示额外的“范围起点”标记，下一次点击选中起点与终点之间的所有图片（含两端），完成后起点标记消失；按返回或左上角关闭取消全部选择。折叠相册也支持范围选择，范围内各相册的全部图片都会选中。右上角图片折叠支持完整、基础、内容、画风提示词及日/月/年分组，点击相册展开后仍可继续折叠、搜索和日期筛选，返回恢复上一层。长按折叠相册选择内部全部图片，多选时相册角标表示已选张数；未标记范围起点时，点击已全选相册取消其选择。底部可统一命名并批量保存，也可进入删除确认，持续按住删除键 3 秒后删除所选历史图片。右上角删除只选择当前层筛选结果。",
                    "迁移时可只导出生图历史元数据。恢复后“仅元数据”记录仍可查看参数、复用 Seed、长按多选删除；没有原图，不能全屏预览、保存或用作图像引导。批量保存前需取消选择这些记录。",
                    "AI 设计回复中，长按某个基础 Prompt 或角色 Prompt 模块，只复制该模块内容。",
                    "进入 AI 设计右上角的对话历史，长按历史会话可选择删除；确认后删除整段对话，无法恢复。取消则保留记录；当前会话、工作室 Prompt 和生图历史不受影响。",
                    "AI 设计中，点击任意历史用户发言下的“编辑并重新生成”，修改后发送即可从该轮继续；原会话及后续回复保留在对话历史中，取消编辑不会改变记录。"
                )
            ),
            TutorialSection(
                title = "自定义本次生图",
                steps = listOf(
                    "点击助手消息旁的生图按钮，会直接使用当前会话保存的生图偏好。",
                    "长按生图按钮，可额外填写本次图片内容提示和生图偏好。",
                    "提交后，本次偏好也会保存到当前会话，供后续直接生图使用。",
                    "文本生成不占生图额度；全 App 可同时运行 2 个生图任务，更多任务会自动排队。"
                )
            )
        )
    ),
    TutorialPage(
        title = "管理页的长按菜单",
        summary = "角色卡、格式卡、世界书和模型都可长按管理。",
        sections = listOf(
            TutorialSection(
                title = "复制、导出与删除",
                steps = listOf(
                    "进入“管理”，切换到角色、格式、世界书或模型标签。",
                    "长按列表项，打开包含编辑、复制、导出和删除的操作菜单。",
                    "世界书菜单还可导出为 SillyTavern JSON；社区下载的角色卡可先复制，再编辑本地副本。"
                )
            ),
            TutorialSection(
                title = "恢复内置内容",
                steps = listOf(
                    "各管理标签顶部可展开“恢复预制角色”“恢复预制格式”“恢复预制世界书”或“恢复内置模型”。",
                    "展开后可重新导入缺失的内置内容，也可获取有更新的预制版本。"
                )
            )
        )
    ),
    TutorialPage(
        title = "朋友圈与社区",
        summary = "朋友圈删除带二次确认，社区作品管理使用长按入口。",
        sections = listOf(
            TutorialSection(
                title = "朋友圈",
                steps = listOf(
                    "点击动态右上角编辑按钮，或长按文字选择“编辑朋友圈”，可修改文案、切换所属角色卡的人物与头像；选择“复制文字”可复制完整正文。",
                    "点击每条动态右下角的淡色删除图标，再在确认窗口中选择“删除”。",
                    "点击动态图片进入预览，可将图片设为对应角色卡头像或背景。"
                )
            ),
            TutorialSection(
                title = "社区作品",
                steps = listOf(
                    "点击社区作品卡片查看详情。",
                    "长按自己发布的作品卡片，打开作品管理操作。",
                    "下载的角色卡在管理页保持只读；需要修改时长按并选择“复制”。"
                )
            )
        )
    ),
    TutorialPage(
        title = "聊天页二级功能",
        summary = "会话设置和顶部快捷按钮包含更多控制。",
        sections = listOf(
            TutorialSection(
                title = "会话设置",
                steps = listOf(
                    "点击聊天页右上角的调节图标，打开“会话设置”。",
                    "“参数与设定”按对话、补充设定与世界书、玩家与背景、图片、语音、记忆六类整理；可搜索设置名称直接定位。",
                    "跨分类修改后点击顶部保存；退出前可选择保存、放弃修改或继续编辑。长期记忆、RAG 检索库和存档仍在顶部标签。",
                    "清空操作位于“参数与设定 > 记忆与清理”，执行前需再次确认。"
                )
            ),
            TutorialSection(
                title = "跳转与全屏编辑",
                steps = listOf(
                    "聊天区右上角的两个小箭头可跳到上一条消息或第一条消息。",
                    "输入栏旁的展开图标可进入全屏编辑，适合编写长消息。",
                    "输入框上方出现向下按钮时，可快速回到最新消息；绿色勾表示最新回复已完成。"
                )
            )
        )
    )
)

@Composable
fun TutorialScreen(
    onExit: () -> Unit,
    advanced: Boolean = false
) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val pages = if (advanced) advancedTutorialPages else tutorialPages
    val page = pages[pageIndex]

    BackHandler {
        if (pageIndex > 0) pageIndex-- else onExit()
    }

    Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        CbTopBar(
            title = if (advanced) "进阶教程" else "基础教程",
            navigation = {
                CbIconButton(AppIcons.ArrowBack, "返回", {
                    if (pageIndex > 0) pageIndex-- else onExit()
                })
            },
            actions = {
                CbButton("跳过", onExit, variant = ButtonVariant.Ghost)
            }
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CbText(page.title, style = ChatBarTheme.typography.title)
            CbText(page.summary, color = ChatBarTheme.colors.mutedForeground)

            page.sections.forEach { section ->
                CbSurface(
                    Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ChatBarTheme.colors.border)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CbText(section.title, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
                        section.steps.forEachIndexed { index, step ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                CbText("${index + 1}.", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.label)
                                CbText(step, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { index ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (index == pageIndex) 8.dp else 6.dp)
                            .background(
                                if (index == pageIndex) ChatBarTheme.colors.primary else ChatBarTheme.colors.border,
                                CircleShape
                            )
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pageIndex > 0) {
                    CbButton("上一步", { pageIndex-- }, Modifier.weight(1f), variant = ButtonVariant.Outline)
                }
                CbButton(
                    if (pageIndex == pages.lastIndex) {
                        if (advanced) "完成" else "开始使用"
                    } else {
                        "下一步"
                    },
                    {
                        if (pageIndex == pages.lastIndex) onExit() else pageIndex++
                    },
                    Modifier.weight(1f)
                )
            }
        }
    }
}
