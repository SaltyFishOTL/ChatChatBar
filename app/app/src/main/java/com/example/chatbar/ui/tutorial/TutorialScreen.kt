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

@Composable
fun TutorialScreen(onExit: () -> Unit) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val page = tutorialPages[pageIndex]

    BackHandler {
        if (pageIndex > 0) pageIndex-- else onExit()
    }

    Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        CbTopBar(
            title = "基础教程",
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
                tutorialPages.indices.forEach { index ->
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
                    if (pageIndex == tutorialPages.lastIndex) "开始使用" else "下一步",
                    {
                        if (pageIndex == tutorialPages.lastIndex) onExit() else pageIndex++
                    },
                    Modifier.weight(1f)
                )
            }
        }
    }
}
