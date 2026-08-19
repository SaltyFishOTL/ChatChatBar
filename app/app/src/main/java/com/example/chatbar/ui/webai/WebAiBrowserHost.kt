package com.example.chatbar.ui.webai

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.chatbar.data.local.entity.WebAiSite
import com.example.chatbar.domain.webai.WebAiController
import com.example.chatbar.domain.webai.displayName
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun WebAiBrowserHost(
    controller: WebAiController,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsState()

    Column(
        modifier
            .fillMaxSize()
            .zIndex(if (state.visible) 5f else -5f)
            .background(ChatBarTheme.colors.background)
    ) {
        CbTopBar(
            title = "网页版 AI",
            navigation = {
                CbIconButton(AppIcons.ArrowBack, "返回", controller::goBackOrHide)
            },
            actions = {
                CbIconButton(
                    AppIcons.Refresh,
                    "刷新网页",
                    controller::reload,
                    enabled = !state.taskRunning
                )
                CbIconButton(AppIcons.Close, "隐藏浏览器", controller::hide)
            }
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WebAiSite.entries.forEach { site ->
                CbChoiceChip(
                    text = site.displayName,
                    selected = state.site == site,
                    onClick = { controller.selectSite(site) }
                )
            }
        }
        val showProgress = state.pageLoading || state.pageProgress in 1..99
        CbProgress(
            state.pageProgress.coerceIn(0, 100) / 100f,
            Modifier.alpha(if (showProgress) 1f else 0f)
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { context ->
                    WebView(context).also(controller::attach)
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { releasedWebView ->
                    controller.detach(releasedWebView)
                    releasedWebView.stopLoading()
                    releasedWebView.destroy()
                }
            )
            if (state.pageLoading && state.pageProgress == 0) {
                CbText(
                    "正在载入 ${state.site.displayName}…",
                    modifier = Modifier.align(Alignment.Center),
                    color = ChatBarTheme.colors.mutedForeground
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(ChatBarTheme.colors.card)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CbText(
                state.status ?: "登录完成后绑定当前会话",
                color = if (state.status?.contains("失败") == true) {
                    ChatBarTheme.colors.destructive
                } else {
                    ChatBarTheme.colors.mutedForeground
                },
                style = ChatBarTheme.typography.caption
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isBound) {
                    CbButton(
                        "解除绑定",
                        controller::unbind,
                        modifier = Modifier.weight(1f),
                        enabled = !state.taskRunning && !state.bindingInProgress,
                        variant = ButtonVariant.Outline
                    )
                }
                CbButton(
                    if (state.bindingInProgress) "检查中…" else "完成并隐藏",
                    controller::bindAndHide,
                    modifier = Modifier.weight(1f),
                    enabled = !state.taskRunning && !state.bindingInProgress
                )
            }
        }
    }
}
