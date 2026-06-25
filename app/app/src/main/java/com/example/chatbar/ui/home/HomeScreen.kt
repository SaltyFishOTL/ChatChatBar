package com.example.chatbar.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.ChatBarApp
import com.example.chatbar.R
import com.example.chatbar.ChatRoute
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.ui.components.EmptyState
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbFab
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel()
) {
    val sessions by viewModel.sessions.collectAsState()
    val characters by viewModel.characters.collectAsState()
    val modelErrors by viewModel.modelConfigurationErrors.collectAsState()
    val modelUsable by viewModel.isModelConfigurationUsable.collectAsState()
    var showStartDialog by remember { mutableStateOf(false) }
    var actionSession by remember { mutableStateOf<ChatSession?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = "ChatChatBar",
                navigation = {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = "ChatBar",
                        modifier = Modifier.size(26.dp)
                    )
                }
            )
        },
        floatingActionButton = {
            CbFab(Icons.Default.Add, "新建对话", { showStartDialog = true })
        }
    ) {
        Box(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            if (sessions.isEmpty()) {
                EmptyState(title = "暂无最近会话", description = "点击右下角按钮，选择角色开始聊天。")
            } else {
                SessionList(
                    sessions = sessions,
                    onOpen = { onNavigate(ChatRoute(it.id)) },
                    onAction = { actionSession = it }
                )
            }
        }
    }

    if (showStartDialog) {
        CbDialog(
            onDismissRequest = { showStartDialog = false },
            title = "选择角色",
            dismiss = { CbButton("取消", { showStartDialog = false }, variant = ButtonVariant.Ghost) }
        ) {
            if (characters.isEmpty()) {
                CbText("当前没有角色卡，请先到管理页创建。", color = ChatBarTheme.colors.mutedForeground)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!modelUsable) {
                        CbText(
                            modelErrors.firstOrNull() ?: "模型配置不可用，无法开始聊天。",
                            color = ChatBarTheme.colors.destructive
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                    items(characters) { character ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = modelUsable) {
                                    viewModel.createSession(character) { sessionId ->
                                        showStartDialog = false
                                        onNavigate(ChatRoute(sessionId))
                                    }
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CharacterAvatar(character.avatar, Modifier.size(40.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                CbText(character.name, style = ChatBarTheme.typography.heading)
                                CbText(
                                    ragStatusText(character.ragIndexStatus, character.ragIndexDone, character.ragIndexTotal),
                                    color = if (character.ragIndexStatus == "FAILED") ChatBarTheme.colors.destructive else ChatBarTheme.colors.mutedForeground,
                                    style = ChatBarTheme.typography.caption
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    actionSession?.let { session ->
        CbDialog(
            onDismissRequest = { actionSession = null },
            title = session.title,
            dismiss = { CbButton("取消", { actionSession = null }, variant = ButtonVariant.Ghost) }
        ) {
            ActionRow(
                icon = Icons.Default.PushPin,
                title = if (session.isPinned) "取消置顶" else "置顶",
                onClick = {
                    viewModel.togglePinSession(session)
                    actionSession = null
                }
            )
            ActionRow(
                icon = Icons.Default.Delete,
                title = "删除聊天",
                destructive = true,
                onClick = {
                    deleteTarget = session
                    actionSession = null
                }
            )
        }
    }

    deleteTarget?.let { session ->
        CbDialog(
            onDismissRequest = { deleteTarget = null },
            title = "删除聊天",
            dismiss = { CbButton("取消", { deleteTarget = null }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton(
                    "删除",
                    {
                        viewModel.deleteSession(session)
                        deleteTarget = null
                    },
                    variant = ButtonVariant.Destructive
                )
            }
        ) {
            CbText("确定删除“${session.title}”？聊天记录和对应记忆索引会一并删除。", color = ChatBarTheme.colors.mutedForeground)
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<ChatSession>,
    onOpen: (ChatSession) -> Unit,
    onAction: (ChatSession) -> Unit
) {
    val pinned = sessions.filter { it.isPinned }
    val recent = sessions.filterNot { it.isPinned }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (pinned.isNotEmpty()) {
            item {
                SessionGroup("置顶", pinned, true, onOpen, onAction)
            }
        }
        if (recent.isNotEmpty()) {
            item {
                SessionGroup("最近对话", recent, false, onOpen, onAction)
            }
        }
    }
}

@Composable
private fun SessionGroup(
    title: String,
    sessions: List<ChatSession>,
    pinned: Boolean,
    onOpen: (ChatSession) -> Unit,
    onAction: (ChatSession) -> Unit
) {
    CbText(title, style = ChatBarTheme.typography.heading)
    Spacer(Modifier.height(8.dp))
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = if (pinned) ChatBarTheme.colors.accent else ChatBarTheme.colors.card
    ) {
        Column {
            sessions.forEachIndexed { index, session ->
                SessionItem(session, pinned, { onOpen(session) }, { onAction(session) })
                if (index != sessions.lastIndex) CbDivider(Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionItem(
    session: ChatSession,
    showPinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var avatar by remember { mutableStateOf<String?>(null) }
    var archived by remember { mutableStateOf(false) }
    LaunchedEffect(session.characterCardId) {
        val card = ChatBarApp.instance.characterRepository.getById(session.characterCardId)
        avatar = card?.avatar
        archived = card == null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CharacterAvatar(avatar, Modifier.size(42.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CbText(
                    session.title,
                    modifier = Modifier.weight(1f),
                    style = ChatBarTheme.typography.heading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (archived) {
                    Spacer(Modifier.width(8.dp))
                    CbText("已封存", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption, maxLines = 1)
                }
                session.lastMessageTime?.let {
                    Spacer(Modifier.width(8.dp))
                    CbText(
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it)),
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            CbText(
                session.lastMessagePreview ?: "开始全新对话…",
                color = ChatBarTheme.colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (showPinned) {
            Spacer(Modifier.width(8.dp))
            CbIcon(Icons.Default.PushPin, "置顶", Modifier.size(16.dp), ChatBarTheme.colors.mutedForeground)
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) ChatBarTheme.colors.destructive else ChatBarTheme.colors.foreground
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CbIcon(icon, null, Modifier.size(18.dp), color)
        Spacer(Modifier.width(12.dp))
        CbText(title, color = color)
    }
}

@Composable
fun CharacterAvatar(avatarPath: String?, modifier: Modifier = Modifier) {
    if (avatarPath != null && File(avatarPath).exists()) {
        AsyncImage(
            model = File(avatarPath),
            contentDescription = "头像",
            modifier = modifier.clip(CircleShape).background(ChatBarTheme.colors.border),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = modifier.clip(CircleShape).background(ChatBarTheme.colors.accent),
            contentAlignment = Alignment.Center
        ) {
            CbIcon(Icons.Default.Face, "头像", Modifier.fillMaxSize(0.55f), ChatBarTheme.colors.primary)
        }
    }
}

private fun ragStatusText(status: String, done: Int, total: Int): String = when (status) {
    "INDEXING" -> "RAG：索引中 $done/$total"
    "COMPLETE" -> "RAG：已完成 $done/$total"
    "FAILED" -> "RAG：失败 $done/$total"
    else -> "RAG：待重建 $done/$total"
}
