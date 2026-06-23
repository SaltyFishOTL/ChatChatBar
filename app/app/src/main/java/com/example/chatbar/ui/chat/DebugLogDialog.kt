package com.example.chatbar.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.utils.DebugLogEntry
import com.example.chatbar.utils.DebugLogManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DebugLogDialog(
    sessionId: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val logs by DebugLogManager.logs.collectAsState()
    val sessionLogs = remember(logs, sessionId) { logs.filter { it.sessionId == sessionId }.sortedByDescending { it.timestamp } }
    val scope = rememberCoroutineScope()
    var rebuilding by remember { mutableStateOf(false) }
    var rebuildResult by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            CbTopBar(
                title = "调试控制台",
                statusBarInset = true,
                navigation = { CbIconButton(Icons.Default.Close, "关闭", onDismiss) },
                actions = {
                    if (rebuilding) CbSpinner(Modifier.size(24.dp))
                    else CbIconButton(Icons.Default.Refresh, "重建 RAG 索引", {
                        rebuilding = true
                        scope.launch {
                            rebuildResult = viewModel.rebuildRagIndex()
                            rebuilding = false
                        }
                    }, tint = ChatBarTheme.colors.primary)
                    CbIconButton(Icons.Default.DeleteSweep, "清空日志", { DebugLogManager.clearLogs(sessionId) }, tint = ChatBarTheme.colors.destructive)
                }
            )
            if (sessionLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CbText("当前会话暂无调试日志", color = ChatBarTheme.colors.mutedForeground)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sessionLogs, key = { it.id }) { DebugLogCard(it) }
                }
            }
        }
    }
    rebuildResult?.let { result ->
        CbDialog(onDismissRequest = { rebuildResult = null }, title = "RAG 重建结果", confirm = { CbButton("确定", { rebuildResult = null }) }) {
            CodeBlock(result, Modifier.heightIn(max = 360.dp))
        }
    }
}

@Composable
fun DebugLogCard(logEntry: DebugLogEntry) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    CbSurface(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, ChatBarTheme.colors.border)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CbText(logEntry.modelName, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.label)
                        Spacer(Modifier.width(8.dp))
                        CbText(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(logEntry.timestamp)), style = ChatBarTheme.typography.heading)
                    }
                    CbText(
                        "Token ≈ ${logEntry.totalTokens}（输入 ${logEntry.estimatedPromptTokens} / 输出 ${logEntry.estimatedCompletionTokens}）",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                if (logEntry.error != null) CbIcon(Icons.Default.Error, "错误", tint = ChatBarTheme.colors.destructive)
                else if (!logEntry.isCompleted) CbSpinner(Modifier.size(20.dp))
                CbIcon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "收起" else "展开", tint = ChatBarTheme.colors.mutedForeground)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbDivider()
                    CbText("API：${logEntry.apiUrl}", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption.copy(fontFamily = FontFamily.Monospace))
                    DebugSection("RAG 召回 (${logEntry.ragChunks.size})", logEntry.ragChunks.joinToString("\n\n"), { clipboard.setText(AnnotatedString(it)) }) {
                        if (logEntry.ragChunks.isEmpty()) CbText("无 RAG 召回", color = ChatBarTheme.colors.mutedForeground)
                        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            logEntry.ragChunks.forEach { CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) { CbText(it, Modifier.padding(8.dp)) } }
                        }
                    }
                    DebugTextSection("System Prompt", logEntry.systemPrompt, { clipboard.setText(AnnotatedString(it)) })
                    DebugTextSection("Request JSON", logEntry.requestBodyJson, { clipboard.setText(AnnotatedString(it)) })
                    DebugTextSection("Raw SSE", logEntry.rawSseOutputText.ifBlank { "等待流数据…" }, { clipboard.setText(AnnotatedString(it)) }, logEntry.error != null)
                    DebugTextSection("AI 原始文本", logEntry.rawAiOutputText.ifBlank { "无原始输出" }, { clipboard.setText(AnnotatedString(it)) })
                    if (logEntry.rawReasoningOutputText.isNotBlank()) {
                        DebugTextSection("AI 原始思维链", logEntry.rawReasoningOutputText, { clipboard.setText(AnnotatedString(it)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugTextSection(title: String, text: String, copy: (String) -> Unit, error: Boolean = false) {
    DebugSection(title, text, copy) { CodeBlock(text, error = error) }
}

@Composable
private fun DebugSection(title: String, text: String, copy: (String) -> Unit, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CbIcon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, Modifier.size(18.dp), ChatBarTheme.colors.primary)
            Spacer(Modifier.width(4.dp)); CbText(title, style = ChatBarTheme.typography.heading)
        }
        CbIconButton(Icons.Default.ContentCopy, "复制", { copy(text) }, modifier = Modifier.size(32.dp), tint = ChatBarTheme.colors.mutedForeground)
    }
    AnimatedVisibility(expanded) { content() }
}

@Composable
private fun CodeBlock(text: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Box(
        modifier.fillMaxWidth().heightIn(max = 260.dp).background(ChatBarTheme.colors.muted, RoundedCornerShape(8.dp)).padding(8.dp).verticalScroll(rememberScrollState())
    ) {
        CbText(
            text,
            color = if (error) ChatBarTheme.colors.destructive else ChatBarTheme.colors.foreground,
            style = ChatBarTheme.typography.caption.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
