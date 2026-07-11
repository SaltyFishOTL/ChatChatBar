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
                    "申请并配置 NovelAI Token",
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
                    "在“默认对话”中选择默认对话模型和默认格式卡。",
                    "点击“保存全局设置”。"
                )
            ),
            TutorialSection(
                title = "玩家设定",
                steps = listOf(
                    "在同一页面找到“玩家”。",
                    "填写玩家名称和玩家全局设定。",
                    "点击“保存玩家设定”。这些内容会参与后续对话。"
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
                    "把模型配置模式切换为“自定义 API”。",
                    "在“硅基流动 API”中粘贴 API Key并保存。",
                    "点击“测试连接”；对话和向量接口均成功后即可使用。"
                )
            )
        )
    ),
    TutorialPage(
        title = "进阶单元：其他 API 与新模型",
        summary = "ChatBar 可接入非硅基流动的 OpenAI 兼容 API，也可单独新增对话、向量和检索规划模型。",
        sections = listOf(
            TutorialSection(
                title = "使用非硅基流动 API",
                steps = listOf(
                    "在服务商控制台复制 OpenAI 兼容 Base URL、API Key 和模型标识。",
                    "进入“管理 > 模型”，点击右下角“新建”。",
                    "在“接口模板类型”中选择服务商对应模板；通用 OpenAI 兼容接口通常选择 OPENAI。",
                    "填写“显示名称”“Base URL”“API Key”“模型标识”，点击右上角保存。"
                )
            ),
            TutorialSection(
                title = "配置新模型",
                steps = listOf(
                    "如多个模型共用同一 Key，可进入“管理 > 设置 > 模型与 API”填写“全局默认 API Key”，单个模型的 API Key 留空。",
                    "保存模型后，进入“管理 > 设置”，在“默认对话模型”中选择新模型。",
                    "如服务商提供向量接口，可在“管理 > 模型 > 向量模型”点击“添加”，填写 Base URL、API Key、模型名称和向量维度，让 RAG 可以建立索引。",
                    "如需更便宜的检索规划，可在“管理 > 模型 > 检索规划模型”点击“添加”；未配置时会回退到当前对话模型。"
                )
            )
        )
    ),
    TutorialPage(
        title = "配置 NovelAI Token",
        summary = "配置后，可在聊天中使用 NovelAI 生图。",
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
                    "进入“管理 > 设置 > NovelAI 生图”。",
                    "粘贴 Persistent API Token并点击“保存”。",
                    "保存成功后，聊天页会显示生图操作。"
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
                    "进入“管理 > 设置 > RAG 检索”。",
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
                title = "置顶与删除",
                steps = listOf(
                    "进入首页，长按任意会话卡片。",
                    "选择“置顶”，可把常用会话移到顶部；再次长按可取消置顶。",
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
                    "可编辑或删除该消息；最后一条可重新生成的回复还会显示“重新生成”。",
                    "选择“多选”进入长截图模式，继续点选消息或片段，再预览、保存或分享。"
                )
            ),
            TutorialSection(
                title = "分段回复",
                steps = listOf(
                    "开启“助手回复分段气泡”后，长按某个片段打开“片段操作”。",
                    "可只复制、编辑或删除当前片段，也可从当前片段开始选择长截图。",
                    "最后一条回复的片段菜单还可重新生成整条回复。"
                )
            )
        )
    ),
    TutorialPage(
        title = "图片与 NovelAI 隐藏操作",
        summary = "聊天图片和生图按钮都提供长按入口。",
        sections = listOf(
            TutorialSection(
                title = "聊天图片",
                steps = listOf(
                    "点击图片进入预览，可把图片设为当前角色卡头像或背景。",
                    "长按生成图片，可用相同提示词和参数重新生成，或删除图片。",
                    "普通聊天图片长按会直接进入删除确认。"
                )
            ),
            TutorialSection(
                title = "自定义本次生图",
                steps = listOf(
                    "点击助手消息旁的生图按钮，会直接使用当前会话保存的生图偏好。",
                    "长按生图按钮，可额外填写本次图片内容提示和生图偏好。",
                    "提交后，本次偏好也会保存到当前会话，供后续直接生图使用。"
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
        summary = "删除和作品管理使用低干扰的长按入口。",
        sections = listOf(
            TutorialSection(
                title = "朋友圈",
                steps = listOf(
                    "每条动态右下角有淡色删除图标；长按图标才会删除，避免误触。",
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
                    "可调整会话模型、格式卡、玩家设定、背景、字体与生图偏好等当前会话配置。",
                    "清空记录位于会话设置内，会同时清除聊天记录和 RAG 记忆。"
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
