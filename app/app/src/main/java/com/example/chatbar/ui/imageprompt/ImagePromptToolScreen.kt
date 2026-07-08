package com.example.chatbar.ui.imageprompt

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File

@Composable
fun ImagePromptToolScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImagePromptToolViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val selectedModel = state.models.firstOrNull { it.id == state.selectedModelId }
    val selectedCharacterCard = state.characterCards.firstOrNull { it.id == state.selectedCharacterCardId }

    Column(modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
        CbTopBar(
            title = "跑图工具",
            navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
            actions = {
                if (state.isBusy) {
                    CbButton(
                        "停止",
                        viewModel::cancelActiveTask,
                        variant = ButtonVariant.Outline
                    )
                }
            }
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PromptInputPanel(
                    state = state,
                    selectedModel = selectedModel,
                    onDescription = viewModel::updateImageDescription,
                    onStyle = viewModel::updateStylePrompt,
                    onCharacter = viewModel::updateCharacterPrompt,
                    selectedCharacterCard = selectedCharacterCard,
                    onImportCharacterCard = { viewModel.importCharacterCardPrompts(it.id) },
                    onModel = { viewModel.selectModel(it.id) },
                    onGeneratePrompt = viewModel::designPrompt
                )
            }
            if (state.reasoningStream.isNotBlank() || state.isDesigning) {
                item {
                    StreamPanel(
                        title = "思维流",
                        text = state.reasoningStream,
                        active = state.isDesigning
                    )
                }
            }
            if (state.resultStream.isNotBlank() || state.isDesigning) {
                item {
                    StreamPanel(
                        title = "结果流",
                        text = state.resultStream,
                        active = state.isDesigning
                    )
                }
            }
            if (state.finalPromptParts.isNotEmpty()) {
                item {
                    FinalPromptPanel(
                        parts = state.finalPromptParts,
                        canGenerateImage = novelAiConfigured && state.promptPlan != null && !state.isBusy,
                        imageGenerating = state.isGeneratingImage,
                        onCopy = {
                            clipboard.setText(AnnotatedString(state.finalPrompt))
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        },
                        onCopyPart = { part ->
                            clipboard.setText(AnnotatedString(part.text))
                            Toast.makeText(context, "已复制 ${part.title}", Toast.LENGTH_SHORT).show()
                        },
                        onGenerateImage = viewModel::generateImage
                    )
                }
            }
            if (state.imagePreview != null || state.imagePath != null || state.isGeneratingImage) {
                item {
                    ImagePreviewPanel(state)
                }
            }
            state.error?.let { error ->
                item {
                    ErrorPanel(error, viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun PromptInputPanel(
    state: ImagePromptToolUiState,
    selectedModel: ModelConfig?,
    onDescription: (String) -> Unit,
    onStyle: (String) -> Unit,
    onCharacter: (String) -> Unit,
    selectedCharacterCard: CharacterCard?,
    onImportCharacterCard: (CharacterCard) -> Unit,
    onModel: (ModelConfig) -> Unit,
    onGeneratePrompt: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("图片描述") {
                CbInput(
                    value = state.imageDescription,
                    onValueChange = onDescription,
                    placeholder = "想要生成的画面",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 3
                )
            }
            CbField("导入角色卡提示词") {
                CbSelect(
                    value = selectedCharacterCard,
                    options = state.characterCards,
                    optionLabel = { it.name },
                    onValueChange = onImportCharacterCard,
                    placeholder = if (state.characterCards.isEmpty()) "暂无角色卡" else "选择角色卡"
                )
            }
            CbField("画风") {
                CbInput(
                    value = state.stylePrompt,
                    onValueChange = onStyle,
                    placeholder = "风格、镜头、质感",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 2
                )
            }
            CbField("角色提示词") {
                CbInput(
                    value = state.characterPrompt,
                    onValueChange = onCharacter,
                    placeholder = "角色外貌、服装、Danbooru 标签",
                    enabled = !state.isBusy,
                    singleLine = false,
                    minLines = 3
                )
            }
            CbField("设计 AI") {
                CbSelect(
                    value = selectedModel,
                    options = state.models,
                    optionLabel = { it.displayName },
                    onValueChange = onModel,
                    placeholder = "选择对话模型"
                )
            }
            state.modelErrors.firstOrNull()?.let {
                CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CbButton(
                    "生成 NAI 提示词",
                    onGeneratePrompt,
                    modifier = Modifier.weight(1f),
                    enabled = state.canDesign
                )
                if (state.isDesigning) CbSpinner(Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun StreamPanel(
    title: String,
    text: String,
    active: Boolean
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbText(title, style = ChatBarTheme.typography.heading)
                if (active) CbSpinner(Modifier.size(18.dp))
            }
            StreamText(text.ifBlank { "等待流式输出" })
        }
    }
}

@Composable
private fun FinalPromptPanel(
    parts: List<ImagePromptToolPromptPart>,
    canGenerateImage: Boolean,
    imageGenerating: Boolean,
    onCopy: () -> Unit,
    onCopyPart: (ImagePromptToolPromptPart) -> Unit,
    onGenerateImage: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CbText("最终提示词", style = ChatBarTheme.typography.heading)
            parts.forEach { part ->
                PromptPartBlock(part = part, onCopy = { onCopyPart(part) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CbButton(
                    "复制全部",
                    onCopy,
                    modifier = Modifier.weight(1f),
                    variant = ButtonVariant.Secondary
                )
                if (canGenerateImage) {
                    CbButton(
                        "用此提示词生图",
                        onGenerateImage,
                        modifier = Modifier.weight(1f),
                        enabled = !imageGenerating
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptPartBlock(
    part: ImagePromptToolPromptPart,
    onCopy: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        shape = RoundedCornerShape(ChatBarShape.sm)
    ) {
        Column(Modifier.padding(ChatBarSpacing.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbText(
                    part.title,
                    modifier = Modifier.weight(1f),
                    color = ChatBarTheme.colors.foreground,
                    style = ChatBarTheme.typography.label
                )
                CbIconButton(
                    AppIcons.ContentCopy,
                    "复制${part.title}",
                    onCopy,
                    tint = ChatBarTheme.colors.mutedForeground
                )
            }
            SelectionContainer {
                CbText(
                    part.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
        }
    }
}

@Composable
private fun ImagePreviewPanel(state: ImagePromptToolUiState) {
    val label = when (state.phase) {
        ImagePromptToolPhase.GENERATING -> "NovelAI 正在生成"
        ImagePromptToolPhase.STREAMING -> "流式预览 ${(state.imageProgress * 100).toInt()}%"
        ImagePromptToolPhase.SAVING -> "正在保存图片"
        ImagePromptToolPhase.FINISHED -> "生图完成"
        ImagePromptToolPhase.FAILED -> "生图失败"
        ImagePromptToolPhase.CANCELLED -> "已停止"
        else -> "图片预览"
    }
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.card,
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.isGeneratingImage) CbSpinner(Modifier.size(18.dp))
                CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            when {
                state.imagePreview != null -> AsyncImage(
                    model = state.imagePreview,
                    contentDescription = "NovelAI 流式生图预览",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(ChatBarShape.sm)),
                    contentScale = ContentScale.Fit
                )
                state.imagePath != null -> AsyncImage(
                    model = File(state.imagePath),
                    contentDescription = "NovelAI 生图结果",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(ChatBarShape.sm)),
                    contentScale = ContentScale.Fit
                )
                else -> Box(
                    Modifier.fillMaxWidth().height(180.dp).background(ChatBarTheme.colors.muted, RoundedCornerShape(ChatBarShape.sm)),
                    contentAlignment = Alignment.Center
                ) {
                    CbText("等待图片流", color = ChatBarTheme.colors.mutedForeground)
                }
            }
            state.imagePath?.let {
                CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun ErrorPanel(
    error: String,
    onDismiss: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText(error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
            CbButton("关闭", onDismiss, variant = ButtonVariant.Ghost)
        }
    }
}

@Composable
private fun StreamText(text: String) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.muted,
        shape = RoundedCornerShape(ChatBarShape.sm)
    ) {
        SelectionContainer {
            CbText(
                text,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(ChatBarSpacing.sm),
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
    }
}
