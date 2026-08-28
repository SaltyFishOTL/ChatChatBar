package com.example.chatbar.ui.imageprompt

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.image.NovelAiDesignConversation
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignTurn
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonSize
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NovelAiDesignScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: NovelAiDesignViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showSettings by remember { mutableStateOf(false) }
    var editingExtraRequirement by remember { mutableStateOf(false) }
    val closeScreen = {
        viewModel.persistSettingsNow()
        viewModel.leaveScreen()
        onBack()
    }
    val conversationId = state.conversation?.id

    BackHandler(enabled = editingExtraRequirement) {
        viewModel.persistSettingsNow()
        editingExtraRequirement = false
    }
    BackHandler(enabled = !editingExtraRequirement, onBack = closeScreen)

    DisposableEffect(conversationId) {
        onDispose {
            if (conversationId != null) {
                viewModel.rememberScrollPosition(
                    conversationId,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset
                )
            }
        }
    }
    LaunchedEffect(conversationId, state.composingNew) {
        val conversation = state.conversation
        if (conversation != null && !state.composingNew && conversation.turns.isNotEmpty()) {
            val (index, offset) = viewModel.consumeInitialScrollPosition(
                conversation.id,
                conversation.turns.size
            )
            listState.scrollToItem(index, offset)
        }
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.consumeNotice()
        }
    }
    LaunchedEffect(state.generatingTurnId) {
        val itemCount = state.conversation?.turns?.size.orZero()
        if (state.generatingTurnId != null && itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        CbTopBar(
            title = if (state.composingNew) "AI 设计 · 新对话" else "AI 设计",
            navigation = {
                CbIconButton(
                    AppIcons.ArrowBack,
                    "返回生图工作室",
                    closeScreen
                )
            },
            actions = {
                CbIconButton(
                    AppIcons.History,
                    "对话历史",
                    {
                        conversationId?.let {
                            viewModel.rememberScrollPosition(
                                it,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset
                            )
                        }
                        onOpenHistory()
                    },
                    enabled = !state.isGenerating
                )
                CbIconButton(
                    AppIcons.NewChat,
                    "新对话",
                    {
                        conversationId?.let {
                            viewModel.rememberScrollPosition(
                                it,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset
                            )
                        }
                        viewModel.startNewConversation()
                    },
                    enabled = !state.isGenerating
                )
                CbIconButton(
                    AppIcons.Settings,
                    "AI 设计设置",
                    { showSettings = true },
                    enabled = !state.isGenerating
                )
            }
        )

        if (state.modelError != null) {
            CbSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ChatBarSpacing.md, vertical = ChatBarSpacing.sm),
                color = ChatBarTheme.colors.destructive.copy(alpha = 0.1f),
                border = BorderStroke(1.dp, ChatBarTheme.colors.destructive.copy(alpha = 0.4f))
            ) {
                CbText(
                    state.modelError.orEmpty(),
                    modifier = Modifier.padding(ChatBarSpacing.md),
                    color = ChatBarTheme.colors.destructive
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(ChatBarSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)
        ) {
            val conversation = state.conversation
            if (state.composingNew || conversation == null) {
                item {
                    EmptyDesignConversation(
                        legacyPrefilled = state.input.isNotBlank(),
                        modelName = state.models.firstOrNull { it.id == state.selectedDesignModelId }?.displayName,
                        naturalLanguageMode = state.draft.aiDesignNaturalLanguageMode
                    )
                }
            } else {
                items(conversation.turns, key = NovelAiDesignTurn::id) { turn ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                    ) {
                        UserDesignBubble(turn.userText)
                        turn.reply?.let { reply ->
                            val key = "${conversation.id}:${turn.id}"
                            val regenerating = state.generatingTurnId == turn.id
                            PromptReplyBubble(
                                reply = reply,
                                modelName = reply.targetImageModel.displayName,
                                applying = state.applyingReplyKey == key,
                                applied = state.appliedReplyKey == key,
                                regenerating = regenerating,
                                canRegenerate = conversation.latestRegeneratableTurnId == turn.id &&
                                    !state.isGenerating && state.modelError == null,
                                onApply = { viewModel.applyReply(turn.id) },
                                onRegenerate = { viewModel.regenerateTurn(turn.id) }
                            )
                        }
                        if (state.generatingTurnId == turn.id) {
                            DesignProgressBubble(state.progressText, state.reasoningText)
                        } else if (turn.status == NovelAiDesignTurnStatus.FAILED ||
                            turn.status == NovelAiDesignTurnStatus.CANCELLED
                        ) {
                            FailedDesignBubble(
                                message = turn.error,
                                onRetry = { viewModel.retryTurn(turn.id) },
                                enabled = !state.isGenerating && state.modelError == null
                            )
                        }
                    }
                }
            }
            state.error?.let { message ->
                item {
                    CbSurface(
                        Modifier.fillMaxWidth(),
                        color = ChatBarTheme.colors.destructive.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, ChatBarTheme.colors.destructive.copy(alpha = 0.4f))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CbText(
                                message,
                                Modifier.weight(1f),
                                color = ChatBarTheme.colors.destructive
                            )
                            CbButton(
                                "关闭",
                                viewModel::dismissError,
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm
                            )
                        }
                    }
                }
            }
        }

        DesignComposer(
            input = state.input,
            onInput = viewModel::updateInput,
            generating = state.isGenerating,
            enabled = state.initialized && state.selectedDesignModelId != null &&
                state.modelError == null && state.conversation?.hasBlockingTurn != true,
            onSend = viewModel::sendMessage,
            onCancel = viewModel::cancelGeneration
        )
    }

    if (showSettings && !editingExtraRequirement) {
        NovelAiDesignSettingsDialog(
            models = state.models,
            selectedModelId = state.selectedDesignModelId,
            modelError = state.modelError,
            naturalLanguageMode = state.draft.aiDesignNaturalLanguageMode,
            extraRequirement = state.draft.extraRequirement,
            onSelectModel = viewModel::selectDesignModel,
            onNaturalLanguageMode = viewModel::setNaturalLanguageMode,
            onExtraRequirement = viewModel::updateExtraRequirement,
            onFullscreenExtra = { editingExtraRequirement = true },
            onDismiss = {
                viewModel.persistSettingsNow()
                showSettings = false
            }
        )
    }
    FullscreenTextEditor(
        title = "AI 设计额外要求",
        text = state.draft.extraRequirement,
        onTextChange = viewModel::updateExtraRequirement,
        visible = editingExtraRequirement,
        onDismiss = {
            viewModel.persistSettingsNow()
            editingExtraRequirement = false
        },
        onConfirm = {
            viewModel.updateExtraRequirement(it)
            viewModel.persistSettingsNow()
            editingExtraRequirement = false
        },
        placeholder = "例如：优先使用动态构图、避免俯视镜头"
    )
}

@Composable
private fun EmptyDesignConversation(
    legacyPrefilled: Boolean,
    modelName: String?,
    naturalLanguageMode: Boolean
) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceSubtle,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(ChatBarSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            com.example.chatbar.ui.kit.CbIcon(
                AppIcons.Bot,
                "AI 设计",
                Modifier.size(28.dp),
                ChatBarTheme.colors.primary
            )
            CbText("描述想要的画面", style = ChatBarTheme.typography.heading)
            CbText(
                when {
                    legacyPrefilled -> "已载入旧版 AI 设计中尚未发送的画面内容。"
                    naturalLanguageMode -> "首轮仍会检索资料，并生成 V5 中文自然语言基础/角色 Prompt；之后可继续提出局部修改。"
                    else -> "首条消息会设计完整 Prompt；之后可继续提出局部修改。"
                },
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            modelName?.let {
                CbText("设计模型 · $it", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun UserDesignBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        CbSurface(
            Modifier.widthIn(max = 320.dp),
            color = ChatBarTheme.colors.primaryAlpha,
            shape = RoundedCornerShape(ChatBarShape.lg, ChatBarShape.lg, ChatBarShape.xs, ChatBarShape.lg)
        ) {
            CbText(text, Modifier.padding(ChatBarSpacing.md))
        }
    }
}

@Composable
private fun PromptReplyBubble(
    reply: NovelAiDesignReply,
    modelName: String,
    applying: Boolean,
    applied: Boolean,
    regenerating: Boolean,
    canRegenerate: Boolean,
    onApply: () -> Unit,
    onRegenerate: () -> Unit
) {
    val plan = reply.plan
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        CbSurface(
            Modifier.widthIn(max = 340.dp),
            border = BorderStroke(1.dp, ChatBarTheme.colors.border),
            shape = RoundedCornerShape(ChatBarShape.lg, ChatBarShape.lg, ChatBarShape.lg, ChatBarShape.xs)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CbText(
                        if (reply.naturalLanguageMode) "基础 Prompt · 中文自然语言" else "基础 Prompt",
                        Modifier.weight(1f),
                        style = ChatBarTheme.typography.label
                    )
                    CbText(modelName, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                }
                PromptCodeText(plan.baseCaption)
                plan.characterCaptions.forEachIndexed { index, caption ->
                    CbText(
                        if (reply.naturalLanguageMode) {
                            "角色 Prompt ${index + 1} · 中文描述 + 英文 Tag"
                        } else {
                            "角色 Prompt ${index + 1}"
                        },
                        style = ChatBarTheme.typography.label
                    )
                    PromptCodeText(caption.prompt)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
                ) {
                    CbButton(
                        text = when {
                            applying -> "正在应用"
                            applied -> "已应用"
                            else -> "应用到工作室"
                        },
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                        enabled = !applying && !regenerating,
                        variant = if (applied) ButtonVariant.Secondary else ButtonVariant.Default,
                        size = ButtonSize.Sm
                    )
                    if (canRegenerate || regenerating) {
                        CbButton(
                            text = if (regenerating) "重新生成中" else "重新生成",
                            onClick = onRegenerate,
                            modifier = Modifier.weight(1f),
                            enabled = canRegenerate,
                            variant = ButtonVariant.Outline,
                            size = ButtonSize.Sm
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptCodeText(text: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    CbSurface(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, "已复制该 Prompt 模块", Toast.LENGTH_SHORT).show()
                }
            ),
        color = ChatBarTheme.colors.surfaceSubtle,
        shape = RoundedCornerShape(ChatBarShape.sm)
    ) {
        CbText(
            text,
            Modifier.padding(ChatBarSpacing.sm),
            style = ChatBarTheme.typography.caption.copy(fontFamily = FontFamily.Monospace)
        )
    }
}

@Composable
private fun DesignProgressBubble(progress: String, reasoning: String) {
    CbSurface(
        Modifier.widthIn(max = 340.dp),
        color = ChatBarTheme.colors.surfaceSubtle,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            CbText("AI 正在设计…", style = ChatBarTheme.typography.label, color = ChatBarTheme.colors.primary)
            if (progress.isNotBlank()) CbText(progress, color = ChatBarTheme.colors.mutedForeground)
            if (reasoning.isNotBlank()) {
                CbText("思考\n$reasoning", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun FailedDesignBubble(message: String, onRetry: () -> Unit, enabled: Boolean) {
    CbSurface(
        Modifier.widthIn(max = 340.dp),
        color = ChatBarTheme.colors.destructive.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, ChatBarTheme.colors.destructive.copy(alpha = 0.4f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
        ) {
            CbText(
                message.ifBlank { "AI 设计失败，可重试" },
                Modifier.weight(1f),
                color = ChatBarTheme.colors.destructive
            )
            CbButton("重试", onRetry, enabled = enabled, variant = ButtonVariant.Outline, size = ButtonSize.Sm)
        }
    }
}

@Composable
private fun DesignComposer(
    input: String,
    onInput: (String) -> Unit,
    generating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(ChatBarTheme.colors.card)
            .navigationBarsPadding()
            .padding(ChatBarSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
    ) {
        CbInput(
            value = input,
            onValueChange = onInput,
            modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 112.dp),
            placeholder = if (enabled) "描述画面或提出修改需求…" else "请先重试上一条需求",
            enabled = enabled && !generating,
            singleLine = false,
            minLines = 1
        )
        CbIconButton(
            imageVector = if (generating) AppIcons.Close else AppIcons.Send,
            contentDescription = if (generating) "停止 AI 设计" else "发送",
            onClick = if (generating) onCancel else onSend,
            enabled = generating || enabled && input.isNotBlank(),
            tint = if (generating) ChatBarTheme.colors.destructive else ChatBarTheme.colors.primary
        )
    }
}

@Composable
private fun NovelAiDesignSettingsDialog(
    models: List<ModelConfig>,
    selectedModelId: String?,
    modelError: String?,
    naturalLanguageMode: Boolean,
    extraRequirement: String,
    onSelectModel: (ModelConfig) -> Unit,
    onNaturalLanguageMode: (Boolean) -> Unit,
    onExtraRequirement: (String) -> Unit,
    onFullscreenExtra: () -> Unit,
    onDismiss: () -> Unit
) {
    CbDialog(
        onDismissRequest = onDismiss,
        title = "AI 设计设置",
        confirm = { CbButton("完成", onDismiss) }
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.lg)
        ) {
            CbField(
                label = "Prompt 设计模型",
                description = if (naturalLanguageMode) {
                    "控制负责设计 Prompt 的文本模型；自然语言模式固定面向 NovelAI V5 Full。"
                } else {
                    "只控制负责设计 Prompt 的文本模型；NovelAI V4.5/V5 跟随工作室模型。"
                },
                error = modelError
            ) {
                CbSelect(
                    value = models.firstOrNull { it.id == selectedModelId },
                    options = models,
                    optionLabel = ModelConfig::displayName,
                    onValueChange = onSelectModel,
                    placeholder = "选择 Prompt 设计模型"
                )
            }
            CbField(
                label = "自然语言模式",
                description = "仅适用于 V5。保留画面规划、Danbooru 词条库、法典和最终 AI 设计；基础区使用中文自然语言，角色区保留英文 Tag、互动语法与独立分区。应用时自动切换 V5。"
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    CbText(
                        if (naturalLanguageMode) "已开启" else "已关闭",
                        color = ChatBarTheme.colors.mutedForeground
                    )
                    CbSwitch(naturalLanguageMode, onNaturalLanguageMode)
                }
            }
            CbField(
                label = "额外要求",
                description = "自动保存，并应用于工作室 AI 对话与图片反推。",
                onFullscreenEdit = onFullscreenExtra
            ) {
                CbInput(
                    value = extraRequirement,
                    onValueChange = onExtraRequirement,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "填写每次设计都要遵循的要求",
                    singleLine = false,
                    minLines = 4
                )
            }
        }
    }
}

@Composable
fun NovelAiDesignHistoryScreen(
    onBack: () -> Unit,
    viewModel: NovelAiDesignHistoryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.selected) {
        if (state.selected) {
            viewModel.consumeSelected()
            onBack()
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        CbTopBar(
            title = "AI 设计历史",
            navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) }
        )
        if (state.conversations.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CbText(
                    if (state.initialized) "暂无历史会话" else "正在载入…",
                    color = ChatBarTheme.colors.mutedForeground
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(ChatBarSpacing.md),
                verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)
            ) {
                items(state.conversations, key = NovelAiDesignConversation::id) { conversation ->
                    DesignHistoryRow(
                        conversation = conversation,
                        busy = state.selectingId != null,
                        onClick = { viewModel.selectConversation(conversation.id) }
                    )
                }
                state.error?.let { error ->
                    item { CbText(error, color = ChatBarTheme.colors.destructive) }
                }
            }
        }
    }
}

@Composable
private fun DesignHistoryRow(
    conversation: NovelAiDesignConversation,
    busy: Boolean,
    onClick: () -> Unit
) {
    val summary = conversation.lastReply?.displayText
        ?.replace('\n', ' ')
        ?.take(90)
        .orEmpty()
    CbSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(ChatBarSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CbText(conversation.title, Modifier.weight(1f), style = ChatBarTheme.typography.label, maxLines = 1)
                CbText(
                    historyTimeFormatter.format(Date(conversation.updatedAt)),
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbText(
                summary.ifBlank {
                    conversation.turns.lastOrNull()?.error?.ifBlank { "尚未生成有效回复" }
                        ?: "尚未生成有效回复"
                },
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption,
                maxLines = 2
            )
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0

private val historyTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
