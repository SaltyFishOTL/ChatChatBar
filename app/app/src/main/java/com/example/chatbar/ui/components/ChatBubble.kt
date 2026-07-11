package com.example.chatbar.ui.components

import com.example.chatbar.ui.kit.AppIcons

import android.graphics.BitmapFactory
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.PlaceholderRenderer
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.RoleplayTextSegment
import com.example.chatbar.domain.chat.parseRoleplayTextSegments
import com.example.chatbar.domain.chat.roleplayImageBlockId
import com.example.chatbar.domain.chat.roleplayLegacyTextBlockId
import com.example.chatbar.domain.chat.roleplayTextBlockId
import com.example.chatbar.domain.chat.stripRoleplaySpeakerMarkers
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.html.HtmlPlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatBubbleSegmentAction(
    val messageId: String,
    val blockId: String,
    val start: Int,
    val endExclusive: Int,
    val rawText: String,
    val copyText: String
)

data class ChatBubbleCharacterAvatar(
    val name: String,
    val avatarPath: String?
)

internal data class RoleplaySpeakerPresentation(
    val displayName: String?,
    val avatarPath: String?,
    val avatarFallbackName: String
)

internal fun resolveRoleplaySpeaker(
    speakerName: String?,
    characterAvatars: List<ChatBubbleCharacterAvatar>,
    legacyAvatarPath: String? = null,
    legacyAvatarFallbackName: String = ""
): RoleplaySpeakerPresentation {
    if (speakerName == null) {
        return RoleplaySpeakerPresentation(
            displayName = null,
            avatarPath = legacyAvatarPath?.takeIf(String::isNotBlank),
            avatarFallbackName = legacyAvatarFallbackName
        )
    }
    val normalized = speakerName.trim()
    if (normalized.isEmpty()) {
        return RoleplaySpeakerPresentation(
            displayName = "未标注",
            avatarPath = null,
            avatarFallbackName = "?"
        )
    }
    val matches = characterAvatars.filter { candidate ->
        candidate.name.trim().equals(normalized, ignoreCase = true)
    }
    val matched = matches.singleOrNull()
    val displayName = matched?.name?.trim()?.takeIf(String::isNotEmpty) ?: normalized
    return RoleplaySpeakerPresentation(
        displayName = displayName,
        avatarPath = matched?.avatarPath?.takeIf(String::isNotBlank),
        avatarFallbackName = displayName
    )
}

internal fun roleplaySpeakerHeaderIndexes(
    segments: List<RoleplayTextSegment>,
    visibleIndexes: Set<Int>? = null
): Set<Int> {
    val headers = mutableSetOf<Int>()
    var groupId = 0
    var previousWasSpeakerSegment = false
    var previousSpeakerKey: String? = null
    var lastRenderedGroupId: Int? = null
    segments.forEachIndexed { index, segment ->
        val isSpeakerSegment = segment.kind == RoleplaySegmentKind.DIALOGUE ||
            segment.kind == RoleplaySegmentKind.THOUGHT
        val speakerKey = when (val name = segment.speakerName) {
            null -> "__legacy_unmarked__"
            else -> name.trim().takeIf(String::isNotEmpty)?.lowercase() ?: "__invalid_marker__"
        }
        val continuesGroup = isSpeakerSegment &&
            previousWasSpeakerSegment &&
            speakerKey == previousSpeakerKey
        if (!continuesGroup) groupId++
        val currentGroupId = groupId.takeIf { isSpeakerSegment }
        if (visibleIndexes == null || index in visibleIndexes) {
            if (currentGroupId != null && currentGroupId != lastRenderedGroupId) {
                headers += index
            }
            lastRenderedGroupId = currentGroupId
        }
        previousWasSpeakerSegment = isSpeakerSegment
        previousSpeakerKey = speakerKey
    }
    return headers
}

internal sealed interface RoleplayContentSegment {
    data class Markdown(val text: String) : RoleplayContentSegment
    data class Status(val text: String) : RoleplayContentSegment
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
private val roleplayLinkPattern = Regex("(?<!!)\\[([^\\]]+)]\\([^)]*\\)")
private val singleNewlinePattern = Regex("(?<!\n)\n(?!\n)")
private const val hiddenCommentOpen = "<!--"
private const val hiddenCommentClose = "-->"
private const val COLOR_MARKER = '\u200B'

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    fontScale: Float = 1.0f,
    onLongPress: (() -> Unit)? = null,
    onPreviousAlternative: (() -> Unit)? = null,
    onNextAlternative: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    onImageLongPress: ((String) -> Unit)? = null,
    onGenerateImage: (() -> Unit)? = null,
    onGenerateImageLongPress: (() -> Unit)? = null,
    imageGenerationEnabled: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    selectionEnabled: Boolean = true,
    onToggleSelected: (() -> Unit)? = null,
    showActions: Boolean = true,
    exportMode: Boolean = false,
    renderPlayerName: String? = null,
    renderBotName: String = "",
    botAvatarPath: String? = null,
    characterAvatars: List<ChatBubbleCharacterAvatar> = emptyList(),
    assistantSegmentedBubblesEnabled: Boolean = true,
    onSegmentLongPress: ((ChatBubbleSegmentAction) -> Unit)? = null,
    selectedBlockIds: Set<String> = emptySet(),
    onToggleBlockSelected: ((String) -> Unit)? = null,
    blockFilterIds: Set<String>? = null,
    showMessageMeta: Boolean = true,
    expandedStatusBlockIds: Set<String> = emptySet(),
    onStatusExpandedChange: ((String, Boolean) -> Unit)? = null
) {
    if (message.role == MessageRole.ASSISTANT && assistantSegmentedBubblesEnabled) {
        SegmentedAssistantBubble(
            message = message,
            modifier = modifier,
            fontScale = fontScale,
            renderPlayerName = renderPlayerName,
            renderBotName = renderBotName,
            botAvatarPath = botAvatarPath,
            characterAvatars = characterAvatars,
            onLongPress = onLongPress,
            onPreviousAlternative = onPreviousAlternative,
            onNextAlternative = onNextAlternative,
            onImageClick = onImageClick,
            onImageLongPress = onImageLongPress,
            onGenerateImage = onGenerateImage,
            onGenerateImageLongPress = onGenerateImageLongPress,
            imageGenerationEnabled = imageGenerationEnabled,
            selectionMode = selectionMode,
            selectionEnabled = selectionEnabled,
            showActions = showActions,
            exportMode = exportMode,
            onSegmentLongPress = onSegmentLongPress,
            selectedBlockIds = selectedBlockIds,
            onToggleBlockSelected = onToggleBlockSelected,
            blockFilterIds = blockFilterIds,
            showMessageMeta = showMessageMeta,
            expandedStatusBlockIds = expandedStatusBlockIds,
            onStatusExpandedChange = onStatusExpandedChange
        )
    } else {
        LegacyChatBubble(
            message = message,
            modifier = modifier,
            fontScale = fontScale,
            renderPlayerName = renderPlayerName,
            renderBotName = renderBotName,
            onLongPress = onLongPress,
            onPreviousAlternative = onPreviousAlternative,
            onNextAlternative = onNextAlternative,
            onImageClick = onImageClick,
            onImageLongPress = onImageLongPress,
            onGenerateImage = onGenerateImage,
            onGenerateImageLongPress = onGenerateImageLongPress,
            imageGenerationEnabled = imageGenerationEnabled,
            selectionMode = selectionMode,
            selected = selected,
            selectionEnabled = selectionEnabled,
            onToggleSelected = onToggleSelected,
            showActions = showActions,
            exportMode = exportMode,
            selectedBlockIds = selectedBlockIds,
            onToggleBlockSelected = onToggleBlockSelected,
            blockFilterIds = blockFilterIds,
            showMessageMeta = showMessageMeta
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentedAssistantBubble(
    message: ChatMessage,
    modifier: Modifier,
    fontScale: Float,
    renderPlayerName: String?,
    renderBotName: String,
    botAvatarPath: String?,
    characterAvatars: List<ChatBubbleCharacterAvatar>,
    onLongPress: (() -> Unit)?,
    onPreviousAlternative: (() -> Unit)?,
    onNextAlternative: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    onImageLongPress: ((String) -> Unit)?,
    onGenerateImage: (() -> Unit)?,
    onGenerateImageLongPress: (() -> Unit)?,
    imageGenerationEnabled: Boolean,
    selectionMode: Boolean,
    selectionEnabled: Boolean,
    showActions: Boolean,
    exportMode: Boolean,
    onSegmentLongPress: ((ChatBubbleSegmentAction) -> Unit)?,
    selectedBlockIds: Set<String>,
    onToggleBlockSelected: ((String) -> Unit)?,
    blockFilterIds: Set<String>?,
    showMessageMeta: Boolean,
    expandedStatusBlockIds: Set<String>,
    onStatusExpandedChange: ((String, Boolean) -> Unit)?
) {
    val rawContent = message.displayContent
    val textSegments = remember(message.id, message.currentAlternativeIndex, rawContent) {
        parseRoleplayTextSegments(rawContent)
    }
    val visibleTextBlockCount = textSegments.indices.count { index ->
        blockFilterIds == null || roleplayTextBlockId(message.id, index) in blockFilterIds
    }
    val visibleImageBlockCount = message.images.indices.count { index ->
        blockFilterIds == null || roleplayImageBlockId(message.id, index) in blockFilterIds
    }
    if (visibleTextBlockCount == 0 && visibleImageBlockCount == 0 && blockFilterIds != null) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .semantics { contentDescription = "助手消息" }
    ) {
        val dialogueMaxWidth = maxWidth * 0.86f
        val thoughtMaxWidth = maxWidth * 0.72f
        val narrationMaxWidth = maxWidth * 0.96f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (!exportMode && !message.reasoningContent.isNullOrBlank()) {
                ReasoningPanel(
                    messageId = message.id,
                    reasoningContent = PlaceholderRenderer.render(message.reasoningContent, renderPlayerName, renderBotName),
                    onLongPress = onLongPress,
                    interactive = !selectionMode
                )
            }
            message.images.forEachIndexed { index, imagePath ->
                val blockId = roleplayImageBlockId(message.id, index)
                if (blockFilterIds == null || blockId in blockFilterIds) {
                    MessageImageBlock(
                        imagePath = imagePath,
                        blockId = blockId,
                        maxWidth = dialogueMaxWidth,
                        selectionMode = selectionMode,
                        selected = blockId in selectedBlockIds,
                        selectionEnabled = selectionEnabled || blockId in selectedBlockIds,
                        onToggleSelected = onToggleBlockSelected,
                        exportMode = exportMode,
                        onImageClick = onImageClick,
                        onImageLongPress = onImageLongPress
                    )
                }
            }
            val visibleTextSegmentIndexes = textSegments.indices.filterTo(mutableSetOf()) { index ->
                blockFilterIds == null || roleplayTextBlockId(message.id, index) in blockFilterIds
            }
            val speakerHeaderIndexes = roleplaySpeakerHeaderIndexes(
                textSegments,
                visibleTextSegmentIndexes.takeIf { blockFilterIds != null }
            )
            textSegments.forEachIndexed { index, segment ->
                val blockId = roleplayTextBlockId(message.id, index)
                if (blockFilterIds == null || blockId in blockFilterIds) {
                    val speaker = if (
                        segment.kind == RoleplaySegmentKind.DIALOGUE ||
                        segment.kind == RoleplaySegmentKind.THOUGHT
                    ) {
                        resolveRoleplaySpeaker(
                            speakerName = segment.speakerName,
                            characterAvatars = characterAvatars,
                            legacyAvatarPath = botAvatarPath,
                            legacyAvatarFallbackName = renderBotName
                        )
                    } else {
                        null
                    }
                    val rawFragment = rawContent.substring(
                        segment.start.coerceIn(0, rawContent.length),
                        segment.endExclusive.coerceIn(segment.start.coerceIn(0, rawContent.length), rawContent.length)
                    )
                    val copyText = PlaceholderRenderer.render(rawFragment, renderPlayerName, renderBotName)
                    val displayText = PlaceholderRenderer.render(segment.displayText, renderPlayerName, renderBotName)
                    SegmentBubble(
                        messageId = message.id,
                        blockId = blockId,
                        segment = segment,
                        displayText = displayText,
                        rawText = rawFragment,
                        copyText = copyText,
                        speaker = speaker,
                        showSpeakerHeader = index in speakerHeaderIndexes,
                        fontScale = fontScale,
                        dialogueMaxWidth = dialogueMaxWidth,
                        thoughtMaxWidth = thoughtMaxWidth,
                        narrationMaxWidth = narrationMaxWidth,
                        selectionMode = selectionMode,
                        selected = blockId in selectedBlockIds,
                        selectionEnabled = selectionEnabled || blockId in selectedBlockIds,
                        onToggleSelected = onToggleBlockSelected,
                        exportMode = exportMode,
                        onLongPress = onLongPress,
                        onSegmentLongPress = onSegmentLongPress,
                        expanded = blockId in expandedStatusBlockIds,
                        onExpandedChange = onStatusExpandedChange?.let { callback ->
                            { expanded -> callback(blockId, expanded) }
                        }
                    )
                }
            }
            if (showMessageMeta) {
                MessageMetaRow(
                    message = message,
                    isUser = false,
                    maxWidth = dialogueMaxWidth,
                    showActions = showActions,
                    showCopy = false,
                    onPreviousAlternative = onPreviousAlternative,
                    onNextAlternative = onNextAlternative,
                    onGenerateImage = onGenerateImage,
                    onGenerateImageLongPress = onGenerateImageLongPress,
                    imageGenerationEnabled = imageGenerationEnabled
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentBubble(
    messageId: String,
    blockId: String,
    segment: RoleplayTextSegment,
    displayText: String,
    rawText: String,
    copyText: String,
    speaker: RoleplaySpeakerPresentation?,
    showSpeakerHeader: Boolean,
    fontScale: Float,
    dialogueMaxWidth: Dp,
    thoughtMaxWidth: Dp,
    narrationMaxWidth: Dp,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggleSelected: ((String) -> Unit)?,
    exportMode: Boolean,
    onLongPress: (() -> Unit)?,
    onSegmentLongPress: ((ChatBubbleSegmentAction) -> Unit)?,
    expanded: Boolean,
    onExpandedChange: ((Boolean) -> Unit)?
) {
    var localExpanded by remember(blockId) { mutableStateOf(false) }
    val effectiveExpanded = if (onExpandedChange != null) expanded else localExpanded
    val updateExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) {
            onExpandedChange(next)
        } else {
            localExpanded = next
        }
    }
    val shape = when (segment.kind) {
        RoleplaySegmentKind.NARRATION -> RoundedCornerShape(12.dp)
        RoleplaySegmentKind.DIALOGUE -> RoundedCornerShape(3.dp, 10.dp, 10.dp, 10.dp)
        RoleplaySegmentKind.THOUGHT -> RoundedCornerShape(4.dp, 10.dp, 10.dp, 10.dp)
        RoleplaySegmentKind.STATUS -> RoundedCornerShape(12.dp)
    }
    val maxWidth = when (segment.kind) {
        RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS -> narrationMaxWidth
        RoleplaySegmentKind.DIALOGUE -> dialogueMaxWidth
        RoleplaySegmentKind.THOUGHT -> thoughtMaxWidth
    }
    val alignment = when (segment.kind) {
        RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS -> Alignment.Center
        RoleplaySegmentKind.DIALOGUE, RoleplaySegmentKind.THOUGHT -> Alignment.CenterStart
    }
    val background = when (segment.kind) {
        RoleplaySegmentKind.NARRATION -> Color.Transparent
        RoleplaySegmentKind.STATUS -> ChatBarTheme.colors.muted.copy(alpha = 0.72f)
        RoleplaySegmentKind.DIALOGUE -> ChatBarTheme.colors.card.copy(alpha = 0.72f)
        RoleplaySegmentKind.THOUGHT -> ChatBarTheme.colors.muted.copy(alpha = 0.56f)
    }
    val textColor = when (segment.kind) {
        RoleplaySegmentKind.NARRATION -> ChatBarTheme.colors.foreground.copy(alpha = 0.78f)
        RoleplaySegmentKind.THOUGHT -> ChatBarTheme.colors.mutedForeground
        else -> ChatBarTheme.colors.foreground
    }
    val textSize = when (segment.kind) {
        RoleplaySegmentKind.NARRATION -> 13f
        RoleplaySegmentKind.THOUGHT -> 12f
        else -> 14f
    }
    val canToggleSelection = selectionMode && onToggleSelected != null && selectionEnabled
    val canLongPress = !selectionMode && !exportMode && (onSegmentLongPress != null || onLongPress != null)
    val surfaceModifier = if (segment.kind == RoleplaySegmentKind.NARRATION) {
        Modifier.widthIn(min = maxWidth, max = maxWidth)
    } else {
        Modifier.widthIn(max = maxWidth)
    }
    val handleLongPress = {
        if (canLongPress) {
            onSegmentLongPress?.invoke(
                ChatBubbleSegmentAction(
                    messageId = messageId,
                    blockId = blockId,
                    start = segment.start,
                    endExclusive = segment.endExclusive,
                    rawText = rawText,
                    copyText = copyText
                )
            ) ?: onLongPress?.invoke()
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        if (speaker != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                if (showSpeakerHeader) {
                    CharacterChatAvatar(
                        avatarPath = speaker.avatarPath,
                        name = speaker.avatarFallbackName,
                        exportMode = exportMode
                    )
                } else {
                    Spacer(Modifier.width(ChatAvatarSize))
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (showSpeakerHeader && speaker.displayName != null) {
                        CbText(
                            speaker.displayName,
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                    SegmentBubbleSurface(
                        modifier = surfaceModifier,
                        shape = shape,
                        background = background,
                        selected = selected,
                        blockId = blockId,
                        selectionMode = selectionMode,
                        canToggleSelection = canToggleSelection,
                        onToggleSelected = onToggleSelected,
                        canLongPress = canLongPress,
                        onLongPress = handleLongPress,
                        segmentKind = segment.kind,
                        displayText = displayText,
                        textColor = textColor,
                        textSize = textSize * fontScale,
                        lineSpacingExtra = if (segment.kind == RoleplaySegmentKind.THOUGHT) 1.5f else 2f,
                        expanded = effectiveExpanded,
                        onExpandedChange = updateExpanded,
                        exportMode = exportMode
                    )
                }
            }
        } else {
            SegmentBubbleSurface(
                modifier = surfaceModifier,
                shape = shape,
                background = background,
                selected = selected,
                blockId = blockId,
                selectionMode = selectionMode,
                canToggleSelection = canToggleSelection,
                onToggleSelected = onToggleSelected,
                canLongPress = canLongPress,
                onLongPress = handleLongPress,
                segmentKind = segment.kind,
                displayText = displayText,
                textColor = textColor,
                textSize = textSize * fontScale,
                lineSpacingExtra = if (segment.kind == RoleplaySegmentKind.THOUGHT) 1.5f else 2f,
                expanded = effectiveExpanded,
                onExpandedChange = updateExpanded,
                exportMode = exportMode
            )
        }
        if (selectionMode) {
            SelectionMark(
                selected = selected,
                enabled = selectionEnabled,
                modifier = Modifier
                    .align(if (segment.kind == RoleplaySegmentKind.NARRATION || segment.kind == RoleplaySegmentKind.STATUS) Alignment.TopCenter else Alignment.TopStart)
                    .padding(top = 2.dp, start = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SegmentBubbleSurface(
    modifier: Modifier,
    shape: RoundedCornerShape,
    background: Color,
    selected: Boolean,
    blockId: String,
    selectionMode: Boolean,
    canToggleSelection: Boolean,
    onToggleSelected: ((String) -> Unit)?,
    canLongPress: Boolean,
    onLongPress: () -> Unit,
    segmentKind: RoleplaySegmentKind,
    displayText: String,
    textColor: Color,
    textSize: Float,
    lineSpacingExtra: Float,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    exportMode: Boolean
) {
    Box(
        modifier = modifier
            .background(background, shape)
            .let { if (selected) it.border(1.5.dp, ChatBarTheme.colors.primary, shape) else it }
            .combinedClickable(
                enabled = canToggleSelection || canLongPress,
                onClick = { if (canToggleSelection) onToggleSelected?.invoke(blockId) },
                onLongClick = onLongPress
            )
            .semantics { contentDescription = "助手消息" }
            .padding(
                horizontal = when (segmentKind) {
                    RoleplaySegmentKind.NARRATION -> 4.dp
                    RoleplaySegmentKind.THOUGHT -> 10.dp
                    else -> 12.dp
                },
                vertical = when (segmentKind) {
                    RoleplaySegmentKind.NARRATION -> 4.dp
                    RoleplaySegmentKind.THOUGHT -> 8.dp
                    else -> 10.dp
                }
            )
    ) {
        if (segmentKind == RoleplaySegmentKind.STATUS) {
            RoleplayStatusPanel(
                text = displayText,
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                onLongPress = onLongPress,
                interactive = !selectionMode && !exportMode
            )
        } else {
            RoleplayMarkdownText(
                text = displayText,
                color = textColor,
                fontSize = textSize,
                lineSpacingExtra = lineSpacingExtra
            )
        }
    }
}

@Composable
private fun CharacterChatAvatar(
    avatarPath: String?,
    name: String,
    exportMode: Boolean
) {
    val shape = CircleShape
    val cleanPath = avatarPath?.takeIf { it.isNotBlank() }
    val avatarFile = cleanPath?.let(::File)?.takeIf { it.exists() }
    Box(
        modifier = Modifier
            .size(ChatAvatarSize)
            .clip(shape)
            .background(ChatBarTheme.colors.muted),
        contentAlignment = Alignment.Center
    ) {
        if (avatarFile != null) {
            if (exportMode) {
                val bitmap = remember(avatarFile.absolutePath) {
                    BitmapFactory.decodeFile(avatarFile.absolutePath)?.asImageBitmap()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "角色头像",
                        modifier = Modifier.size(ChatAvatarSize).clip(shape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    AvatarInitial(name)
                }
            } else {
                AsyncImage(
                    model = avatarFile,
                    contentDescription = "角色头像",
                    modifier = Modifier.size(ChatAvatarSize).clip(shape),
                    contentScale = ContentScale.Crop
                )
            }
        } else {
            AvatarInitial(name)
        }
    }
}

@Composable
private fun AvatarInitial(name: String) {
    CbText(
        name.trim().take(1).ifBlank { "?" },
        color = ChatBarTheme.colors.mutedForeground,
        style = ChatBarTheme.typography.label
    )
}

private val ChatAvatarSize = 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LegacyChatBubble(
    message: ChatMessage,
    modifier: Modifier,
    fontScale: Float,
    renderPlayerName: String?,
    renderBotName: String,
    onLongPress: (() -> Unit)?,
    onPreviousAlternative: (() -> Unit)?,
    onNextAlternative: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?,
    onImageLongPress: ((String) -> Unit)?,
    onGenerateImage: (() -> Unit)?,
    onGenerateImageLongPress: (() -> Unit)?,
    imageGenerationEnabled: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggleSelected: (() -> Unit)?,
    showActions: Boolean,
    exportMode: Boolean,
    selectedBlockIds: Set<String>,
    onToggleBlockSelected: ((String) -> Unit)?,
    blockFilterIds: Set<String>?,
    showMessageMeta: Boolean
) {
    val isUser = message.role == MessageRole.USER
    val renderedContent = PlaceholderRenderer.render(message.displayContent, renderPlayerName, renderBotName)
    val contentSegments = remember(message.id, message.currentAlternativeIndex, renderedContent) {
        parseRoleplayContent(renderedContent)
    }
    val shape = RoundedCornerShape(
        topStart = if (isUser) 10.dp else 3.dp,
        topEnd = 10.dp,
        bottomStart = 10.dp,
        bottomEnd = if (isUser) 3.dp else 10.dp
    )
    val legacyTextBlockId = roleplayLegacyTextBlockId(message.id)
    val showText = renderedContent.isNotBlank() && (blockFilterIds == null || legacyTextBlockId in blockFilterIds)
    val visibleImageCount = message.images.indices.count { index ->
        blockFilterIds == null || roleplayImageBlockId(message.id, index) in blockFilterIds
    }
    if (!showText && visibleImageCount == 0 && blockFilterIds != null) return

    val canToggleSelection = selectionMode && onToggleSelected != null && (selectionEnabled || selected)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .let {
                if (selectionMode && onToggleSelected != null && onToggleBlockSelected == null) {
                    it.combinedClickable(
                        enabled = canToggleSelection,
                        onClick = { onToggleSelected() },
                        onLongClick = {}
                    )
                } else {
                    it
                }
            }
    ) {
        BoxWithConstraints {
            val bubbleMaxWidth = maxWidth * 0.86f
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                Column(
                    Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .background(
                            (if (isUser) ChatBarTheme.colors.primary else ChatBarTheme.colors.card)
                                .copy(alpha = 0.6f),
                            shape
                        )
                        .let { if (selected) it.border(1.5.dp, ChatBarTheme.colors.primary, shape) else it }
                        .combinedClickable(
                            enabled = if (selectionMode && onToggleBlockSelected != null) {
                                showText && (selectionEnabled || legacyTextBlockId in selectedBlockIds)
                            } else if (selectionMode && onToggleSelected != null) {
                                canToggleSelection
                            } else {
                                onLongPress != null
                            },
                            onClick = {
                                if (selectionMode && onToggleBlockSelected != null && showText) {
                                    onToggleBlockSelected(legacyTextBlockId)
                                } else if (selectionMode) {
                                    onToggleSelected?.invoke()
                                }
                            },
                            onLongClick = { if (!selectionMode) onLongPress?.invoke() }
                        )
                        .semantics { contentDescription = if (isUser) "用户消息" else "助手消息" }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    message.images.forEachIndexed { index, imagePath ->
                        val blockId = roleplayImageBlockId(message.id, index)
                        if (blockFilterIds == null || blockId in blockFilterIds) {
                            MessageImage(
                                imagePath = imagePath,
                                selectionMode = selectionMode,
                                selected = blockId in selectedBlockIds,
                                selectionEnabled = selectionEnabled || blockId in selectedBlockIds,
                                blockId = blockId,
                                onToggleSelected = onToggleBlockSelected,
                                exportMode = exportMode,
                                onImageClick = onImageClick,
                                onImageLongPress = onImageLongPress
                            )
                        }
                    }
                    if (showText) {
                        contentSegments.forEachIndexed { index, segment ->
                            key(message.id, message.currentAlternativeIndex, index) {
                                when (segment) {
                                    is RoleplayContentSegment.Markdown -> RoleplayMarkdownText(
                                        text = segment.text,
                                        color = if (isUser) Color.White else ChatBarTheme.colors.foreground,
                                        fontSize = 14f * fontScale,
                                        lineSpacingExtra = 2f
                                    )

                                    is RoleplayContentSegment.Status -> {
                                        var expanded by remember(message.id, index) { mutableStateOf(false) }
                                        RoleplayStatusPanel(
                                            text = segment.text,
                                            expanded = expanded,
                                            onExpandedChange = { expanded = it },
                                            onLongPress = onLongPress,
                                            interactive = !selectionMode && !exportMode
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (showMessageMeta) {
                    MessageMetaRow(
                        message = message.copy(content = renderedContent),
                        isUser = isUser,
                        maxWidth = bubbleMaxWidth,
                        showActions = showActions,
                        showCopy = showActions,
                        onPreviousAlternative = onPreviousAlternative,
                        onNextAlternative = onNextAlternative,
                        onGenerateImage = onGenerateImage,
                        onGenerateImageLongPress = onGenerateImageLongPress,
                        imageGenerationEnabled = imageGenerationEnabled
                    )
                }
            }
        }
        if (selectionMode && onToggleBlockSelected == null) {
            SelectionMark(
                selected = selected,
                enabled = selectionEnabled || selected,
                modifier = Modifier
                    .align(if (isUser) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = 2.dp, start = if (isUser) 0.dp else 2.dp, end = if (isUser) 2.dp else 0.dp)
            )
        }
    }
}

@Composable
private fun ReasoningPanel(
    messageId: String,
    reasoningContent: String,
    onLongPress: (() -> Unit)?,
    interactive: Boolean
) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    Column(
        Modifier
            .widthIn(max = 320.dp)
            .padding(bottom = 3.dp)
            .background(ChatBarTheme.colors.accent, RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = interactive,
                onClick = { expanded = !expanded },
                onLongClick = { onLongPress?.invoke() }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CbText(
                if (expanded) "思考过程" else "思考过程（已折叠）",
                color = ChatBarTheme.colors.primary,
                style = ChatBarTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
            )
            CbIcon(
                if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                "切换思考过程",
                Modifier.size(14.dp),
                ChatBarTheme.colors.mutedForeground
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                CbText(
                    reasoningContent,
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption.copy(lineHeight = 15.sp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageImageBlock(
    imagePath: String,
    blockId: String,
    maxWidth: Dp,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    onToggleSelected: ((String) -> Unit)?,
    exportMode: Boolean,
    onImageClick: ((String) -> Unit)?,
    onImageLongPress: ((String) -> Unit)?
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.widthIn(max = maxWidth)) {
            MessageImage(
                imagePath = imagePath,
                selectionMode = selectionMode,
                selected = selected,
                selectionEnabled = selectionEnabled,
                blockId = blockId,
                onToggleSelected = onToggleSelected,
                exportMode = exportMode,
                onImageClick = onImageClick,
                onImageLongPress = onImageLongPress
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageImage(
    imagePath: String,
    selectionMode: Boolean,
    selected: Boolean,
    selectionEnabled: Boolean,
    blockId: String,
    onToggleSelected: ((String) -> Unit)?,
    exportMode: Boolean,
    onImageClick: ((String) -> Unit)?,
    onImageLongPress: ((String) -> Unit)?
) {
    val imageRatio = remember(imagePath) { imageAspectRatio(imagePath) }
    val imageModifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 8.dp)
        .aspectRatio(imageRatio)
        .clip(RoundedCornerShape(8.dp))
        .let {
            if (selected) it.border(1.5.dp, ChatBarTheme.colors.primary, RoundedCornerShape(8.dp)) else it
        }
    Box {
        if (exportMode) {
            val bitmap = remember(imagePath) { BitmapFactory.decodeFile(imagePath)?.asImageBitmap() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "消息图片",
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit
                )
            } else {
                CbSurface(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(bottom = 8.dp),
                    color = ChatBarTheme.colors.muted,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    CbText(
                        "图片文件不存在",
                        modifier = Modifier.padding(12.dp),
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
            }
        } else {
            AsyncImage(
                model = File(imagePath),
                contentDescription = "消息图片",
                modifier = imageModifier.combinedClickable(
                    enabled = selectionMode || onImageClick != null || onImageLongPress != null,
                    onClick = {
                        if (selectionMode && onToggleSelected != null && selectionEnabled) {
                            onToggleSelected(blockId)
                        } else {
                            onImageClick?.invoke(imagePath)
                        }
                    },
                    onLongClick = {
                        if (!selectionMode) onImageLongPress?.invoke(imagePath)
                    }
                ),
                contentScale = ContentScale.Fit
            )
        }
        if (selectionMode) {
            SelectionMark(
                selected = selected,
                enabled = selectionEnabled,
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
            )
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val borderColor = if (selected) ChatBarTheme.colors.primary else ChatBarTheme.colors.border
    val fillColor = when {
        selected -> ChatBarTheme.colors.primary
        enabled -> ChatBarTheme.colors.card
        else -> ChatBarTheme.colors.muted
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fillColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            CbIcon(AppIcons.Check, "已选中", Modifier.size(15.dp), ChatBarTheme.colors.primaryForeground)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoleplayStatusPanel(
    text: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLongPress: (() -> Unit)?,
    interactive: Boolean = true
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ChatBarTheme.colors.accent.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = interactive,
                onClick = { onExpandedChange(!expanded) },
                onLongClick = { onLongPress?.invoke() }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CbText(
                if (expanded) "状态栏" else "状态栏（已折叠）",
                color = ChatBarTheme.colors.primary,
                style = ChatBarTheme.typography.caption.copy(fontWeight = FontWeight.Bold)
            )
            CbIcon(
                if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                "切换状态栏",
                Modifier.size(14.dp),
                ChatBarTheme.colors.mutedForeground
            )
        }
        if (expanded) {
            Spacer(Modifier.height(5.dp))
            SelectionContainer {
                CbText(
                    text.trim(),
                    color = ChatBarTheme.colors.foreground,
                    style = ChatBarTheme.typography.caption.copy(lineHeight = 16.sp)
                )
            }
        }
    }
}

@Composable
private fun MessageMetaRow(
    message: ChatMessage,
    isUser: Boolean,
    maxWidth: Dp,
    showActions: Boolean,
    showCopy: Boolean,
    onPreviousAlternative: (() -> Unit)?,
    onNextAlternative: (() -> Unit)?,
    onGenerateImage: (() -> Unit)?,
    onGenerateImageLongPress: (() -> Unit)?,
    imageGenerationEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth).padding(top = 3.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CbText(
            timeFormatter.format(Date(message.createdAt)),
            color = ChatBarTheme.colors.mutedForeground,
            style = ChatBarTheme.typography.caption
        )
        if (showActions && !isUser && message.alternatives.size > 1 && onPreviousAlternative != null && onNextAlternative != null) {
            val canPrevious = message.currentAlternativeIndex > 0
            val canNext = message.currentAlternativeIndex < message.alternatives.lastIndex
            CbSurface(shape = RoundedCornerShape(8.dp), color = ChatBarTheme.colors.accent) {
                Row(Modifier.padding(horizontal = 7.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CbText(
                        "‹",
                        color = if (canPrevious) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground.copy(alpha = 0.35f),
                        modifier = Modifier.clickable(enabled = canPrevious) { onPreviousAlternative() }
                    )
                    CbText("${message.currentAlternativeIndex + 1}/${message.alternatives.size}", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    CbText(
                        "›",
                        color = if (canNext) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground.copy(alpha = 0.35f),
                        modifier = Modifier.clickable(enabled = canNext) { onNextAlternative() }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val clipboardManager = LocalClipboardManager.current
        val ctx = LocalContext.current
        if (showCopy) {
            CbIconButton(
                AppIcons.ContentCopy,
                "复制消息",
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.displayContent))
                    Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                },
                tint = ChatBarTheme.colors.mutedForeground
            )
        }
        if (showActions && !isUser && onGenerateImage != null) {
            GenerateImageActionButton(
                onClick = onGenerateImage,
                onLongClick = onGenerateImageLongPress ?: onGenerateImage,
                enabled = imageGenerationEnabled,
                tint = if (imageGenerationEnabled) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground
            )
        }
    }
}

@Composable
private fun GenerateImageActionButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    enabled: Boolean,
    tint: Color
) {
    val indicatorColor = if (enabled) {
        ChatBarTheme.colors.primary
    } else {
        ChatBarTheme.colors.mutedForeground.copy(alpha = 0.45f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        CbIcon(AppIcons.Image, "点击直生，长按设置", Modifier.size(20.dp), tint)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
                .size(width = 16.dp, height = 2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(indicatorColor.copy(alpha = 0.72f))
        )
    }
}

@Composable
private fun RoleplayMarkdownText(
    text: String,
    color: Color,
    fontSize: Float,
    lineSpacingExtra: Float
) {
    val context = LocalContext.current
    val primaryColor = ChatBarTheme.colors.primary
    val appContext = context.applicationContext
    val markwon = remember(appContext, primaryColor) {
        Markwon.builder(appContext)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(primaryColor.toArgb())
                    builder.isLinkUnderlined(false)
                }
            })
            .build()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                linksClickable = false
                isClickable = false
                isLongClickable = false
            }
        },
        update = { textView ->
            textView.textSize = fontSize
            textView.setLineSpacing(lineSpacingExtra, 1.08f)
            textView.setOnClickListener(null)
            textView.setOnLongClickListener(null)
            textView.isClickable = false
            textView.isLongClickable = false
            textView.setTextColor(color.toArgb())
            markwon.setMarkdown(textView, sanitizeRoleplayMarkdown(text, true))
            applyAccentColorToMarkedRanges(textView, primaryColor.toArgb())
            textView.linksClickable = false
            textView.movementMethod = null
        }
    )
}

internal fun parseRoleplayContent(content: String): List<RoleplayContentSegment> {
    val parsed = parseRoleplayTextSegments(content)
    if (parsed.isEmpty()) return emptyList()
    return parsed.map { segment ->
        when (segment.kind) {
            RoleplaySegmentKind.STATUS -> RoleplayContentSegment.Status(segment.displayText)
            RoleplaySegmentKind.NARRATION,
            RoleplaySegmentKind.DIALOGUE,
            RoleplaySegmentKind.THOUGHT -> RoleplayContentSegment.Markdown(segment.rawText)
        }
    }
}

internal fun sanitizeRoleplayMarkdown(content: String, forColoring: Boolean = false): String {
    val withoutHiddenComments = stripRoleplayHiddenComments(stripRoleplaySpeakerMarkers(content))
    val withLineBreaks = singleNewlinePattern.replace(withoutHiddenComments, "  \n")
    if (forColoring) {
        return roleplayLinkPattern.replace(withLineBreaks) { match ->
            val text = match.groupValues[1]
            val url = match.value.substringAfter("](").substringBefore(")")
            if (url.isEmpty()) "\u200B[$text]\u200B" else match.value
        }
    }
    return withLineBreaks.replace(roleplayLinkPattern, "[$1]")
}

private fun stripRoleplayHiddenComments(content: String): String {
    val result = StringBuilder(content.length)
    var cursor = 0
    while (cursor < content.length) {
        val open = content.indexOf(hiddenCommentOpen, cursor)
        if (open < 0) {
            result.append(content, cursor, content.length)
            break
        }

        var depth = 1
        var search = open + hiddenCommentOpen.length
        var closedAt = -1

        while (search < content.length) {
            val nextOpen = content.indexOf(hiddenCommentOpen, search)
            val nextClose = content.indexOf(hiddenCommentClose, search)
            if (nextClose < 0) break

            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++
                search = nextOpen + hiddenCommentOpen.length
            } else {
                depth--
                search = nextClose + hiddenCommentClose.length
                if (depth == 0) {
                    closedAt = search
                    break
                }
            }
        }

        if (closedAt < 0) {
            result.append(content, cursor, open)
            result.append(content, open, content.length)
            break
        }

        val lineStart = content.lastIndexOf('\n', open - 1).let { if (it < 0) 0 else it + 1 }
        val openerStartsLine = content.substring(lineStart, open).all(::isHorizontalWhitespace)
        var afterCloseLineEnd = closedAt
        while (afterCloseLineEnd < content.length && isHorizontalWhitespace(content[afterCloseLineEnd])) {
            afterCloseLineEnd++
        }
        val closesLine = afterCloseLineEnd >= content.length || content[afterCloseLineEnd] == '\n'
        val standaloneBlock = openerStartsLine && closesLine

        result.append(content, cursor, if (standaloneBlock) lineStart else open)
        cursor = if (standaloneBlock && afterCloseLineEnd < content.length) {
            afterCloseLineEnd + 1
        } else {
            closedAt
        }
    }
    return result.toString()
}

private fun isHorizontalWhitespace(char: Char): Boolean {
    return char == ' ' || char == '\t' || char == '\r'
}

private fun applyAccentColorToMarkedRanges(textView: TextView, accentColor: Int) {
    val spannable = textView.text as? Spannable ?: return
    val text = spannable.toString()
    var start = text.indexOf(COLOR_MARKER)
    while (start >= 0) {
        val end = text.indexOf(COLOR_MARKER, start + 1)
        if (end < 0) break
        spannable.setSpan(ForegroundColorSpan(accentColor), start + 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        start = text.indexOf(COLOR_MARKER, end + 1)
    }
}

internal fun imageAspectRatio(path: String): Float {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, options)
    return if (options.outWidth > 0 && options.outHeight > 0) {
        options.outWidth.toFloat() / options.outHeight.toFloat()
    } else {
        1f
    }
}
