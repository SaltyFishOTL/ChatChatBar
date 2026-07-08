package com.example.chatbar.ui.components

import com.example.chatbar.ui.kit.AppIcons

import android.graphics.BitmapFactory
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import android.text.Spannable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.html.HtmlPlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface RoleplayContentSegment {
    data class Markdown(val text: String) : RoleplayContentSegment
    data class Status(val text: String) : RoleplayContentSegment
}

private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
private val roleplayLinkPattern = Regex("(?<!!)\\[([^\\]]+)]\\([^)]*\\)")
private val singleNewlinePattern = Regex("(?<!\n)\n(?!\n)")
private const val hiddenCommentOpen = "<!--"
private const val hiddenCommentClose = "-->"

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
    imageGenerationEnabled: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    selectionEnabled: Boolean = true,
    onToggleSelected: (() -> Unit)? = null,
    showActions: Boolean = true,
    exportMode: Boolean = false
) {
    val isUser = message.role == MessageRole.USER
    val shape = RoundedCornerShape(
        topStart = 10.dp,
        topEnd = 10.dp,
        bottomStart = if (isUser) 10.dp else 3.dp,
        bottomEnd = if (isUser) 3.dp else 10.dp
    )
    val contentSegments = remember(message.id, message.currentAlternativeIndex, message.displayContent) {
        parseRoleplayContent(message.displayContent)
    }
    val canToggleSelection = selectionMode && onToggleSelected != null && (selectionEnabled || selected)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .let {
                if (selectionMode && onToggleSelected != null) {
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Column(
                Modifier
                .widthIn(max = 300.dp)
                .background(
                    (if (isUser) ChatBarTheme.colors.primary else ChatBarTheme.colors.card)
                        .copy(alpha = 0.6f),
                    shape
                )
                .let {
                    if (selected) {
                        it.border(1.5.dp, ChatBarTheme.colors.primary, shape)
                    } else {
                        it
                    }
                }
                .let {
                    if (selectionMode && onToggleSelected != null) {
                        it.combinedClickable(
                            enabled = canToggleSelection,
                            onClick = { onToggleSelected() },
                            onLongClick = {}
                        )
                    } else {
                        it.combinedClickable(
                            enabled = onLongPress != null,
                            onClick = {},
                            onLongClick = { onLongPress?.invoke() }
                        )
                    }
                }
                .semantics {
                    contentDescription = if (isUser) "用户消息" else "助手消息"
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                message.images.forEach { imagePath ->
                    MessageImage(
                        imagePath = imagePath,
                        selectionMode = selectionMode,
                        exportMode = exportMode,
                        onImageClick = onImageClick,
                        onImageLongPress = onImageLongPress
                    )
                }
                if (!isUser && !message.reasoningContent.isNullOrBlank()) {
                var expanded by remember(message.id) { mutableStateOf(false) }
                val reasoningInteractive = !selectionMode && !exportMode
                Column(
                    Modifier
                        .padding(bottom = 8.dp)
                        .background(ChatBarTheme.colors.accent, RoundedCornerShape(8.dp))
                        .combinedClickable(
                            enabled = reasoningInteractive,
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
                                message.reasoningContent,
                                color = ChatBarTheme.colors.mutedForeground,
                                style = ChatBarTheme.typography.caption.copy(lineHeight = 15.sp)
                            )
                        }
                    }
                }
                }
                val context = LocalContext.current
                val foregroundColor = ChatBarTheme.colors.foreground
                val primaryColor = ChatBarTheme.colors.primary
                val appContext = context.applicationContext
                val markwon = remember(appContext) {
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
                contentSegments.forEachIndexed { index, segment ->
                    key(message.id, message.currentAlternativeIndex, index) {
                        when (segment) {
                            is RoleplayContentSegment.Markdown -> AndroidView(
                                factory = { ctx ->
                                    TextView(ctx).apply {
                                        textSize = 14f * fontScale
                                        setLineSpacing(2f, 1.08f)
                                        linksClickable = false
                                        isClickable = false
                                        isLongClickable = false
                                    }
                                },
                                update = { textView ->
                                    textView.setOnClickListener(null)
                                    textView.setOnLongClickListener(null)
                                    textView.isClickable = false
                                    textView.isLongClickable = false
                                    textView.setTextColor(if (isUser) Color.White.toArgb() else foregroundColor.toArgb())
                                    markwon.setMarkdown(textView, sanitizeRoleplayMarkdown(segment.text, true))
                                    applyAccentColorToMarkedRanges(textView, primaryColor.toArgb())
                                    textView.linksClickable = false
                                    textView.movementMethod = null
                                }
                            )
                            is RoleplayContentSegment.Status -> RoleplayStatusPanel(
                                text = segment.text,
                                onLongPress = onLongPress,
                                interactive = !selectionMode && !exportMode
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 300.dp).padding(top = 3.dp, start = 8.dp, end = 8.dp),
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
                            CbText("‹", color = if (canPrevious) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground.copy(alpha = 0.35f), modifier = Modifier.clickable(enabled = canPrevious) { onPreviousAlternative() })
                            CbText("${message.currentAlternativeIndex + 1}/${message.alternatives.size}", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                            CbText("›", color = if (canNext) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground.copy(alpha = 0.35f), modifier = Modifier.clickable(enabled = canNext) { onNextAlternative() })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val clipboardManager = LocalClipboardManager.current
                val ctx = LocalContext.current
                if (showActions) {
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
                CbIconButton(
                    AppIcons.Image,
                    "根据此消息生成图片",
                    onGenerateImage,
                    enabled = imageGenerationEnabled,
                    tint = if (imageGenerationEnabled) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground
                )
                }
            }
        }
        if (selectionMode) {
            Box(
                Modifier
                    .matchParentSize()
                    .combinedClickable(
                        enabled = canToggleSelection,
                        onClick = { onToggleSelected?.invoke() },
                        onLongClick = {}
                    )
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageImage(
    imagePath: String,
    selectionMode: Boolean,
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
                enabled = !selectionMode,
                onClick = { onImageClick?.invoke(imagePath) },
                onLongClick = { onImageLongPress?.invoke(imagePath) }
            ),
            contentScale = ContentScale.Fit
        )
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
    onLongPress: (() -> Unit)?,
    interactive: Boolean = true
) {
    var expanded by remember(text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(ChatBarTheme.colors.accent.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = interactive,
                onClick = { expanded = !expanded },
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

internal fun parseRoleplayContent(content: String): List<RoleplayContentSegment> {
    val visibleContent = stripRoleplayHiddenComments(content)
    if (visibleContent.isEmpty()) return emptyList()
    val marker = "\u0060\u0060\u0060"
    val segments = mutableListOf<RoleplayContentSegment>()
    var cursor = 0
    while (cursor < visibleContent.length) {
        val open = visibleContent.indexOf(marker, cursor)
        if (open < 0) {
            visibleContent.substring(cursor).takeIf(String::isNotBlank)
                ?.let { segments += RoleplayContentSegment.Markdown(it) }
            break
        }
        if (open > cursor) {
            visibleContent.substring(cursor, open).takeIf(String::isNotBlank)
                ?.let { segments += RoleplayContentSegment.Markdown(it) }
        }
        val headerEnd = visibleContent.indexOf('\n', open + marker.length)
        val bodyStart = if (headerEnd >= 0) headerEnd + 1 else open + marker.length
        val close = visibleContent.indexOf(marker, bodyStart)
        if (close < 0) {
            visibleContent.substring(open).takeIf(String::isNotBlank)
                ?.let { segments += RoleplayContentSegment.Markdown(it) }
            break
        }
        segments += RoleplayContentSegment.Status(visibleContent.substring(bodyStart, close).trim())
        cursor = close + marker.length
    }
    val result = mutableListOf<RoleplayContentSegment>()
    for (segment in segments) {
        if (segment is RoleplayContentSegment.Markdown) {
            result.addAll(splitMarkdownByHrFences(segment.text))
        } else {
            result.add(segment)
        }
    }
    return result.ifEmpty { listOf(RoleplayContentSegment.Markdown(visibleContent)) }
}

private val hrFencePattern = Regex("(?m)^[ \t]*---[ \t]*$")

private fun splitMarkdownByHrFences(text: String): List<RoleplayContentSegment> {
    val matches = hrFencePattern.findAll(text).toList()
    if (matches.isEmpty()) return listOf(RoleplayContentSegment.Markdown(text))

    val segments = mutableListOf<RoleplayContentSegment>()
    var cursor = 0
    var i = 0
    while (i < matches.size) {
        val fence = matches[i]
        if (i % 2 == 0) {
            if (fence.range.first > cursor) {
                text.substring(cursor, fence.range.first).takeIf(String::isNotBlank)
                    ?.let { segments += RoleplayContentSegment.Markdown(it) }
            }
            cursor = fence.range.last + 1
        } else {
            val statusText = text.substring(cursor, fence.range.first).trim()
            if (statusText.isNotBlank()) {
                segments += RoleplayContentSegment.Status(statusText)
            }
            cursor = fence.range.last + 1
        }
        i++
    }
    if (cursor < text.length) {
        val remaining = text.substring(cursor).trim()
        if (remaining.isNotBlank()) {
            if (matches.size % 2 == 1) {
                segments += RoleplayContentSegment.Status(remaining)
            } else {
                segments += RoleplayContentSegment.Markdown(remaining)
            }
        }
    }
    return segments.ifEmpty { listOf(RoleplayContentSegment.Markdown(text)) }
}

internal fun sanitizeRoleplayMarkdown(content: String, forColoring: Boolean = false): String {
    val withoutHiddenComments = stripRoleplayHiddenComments(content)
    val withLineBreaks = singleNewlinePattern.replace(withoutHiddenComments, "  \n")
    if (forColoring) {
        return roleplayLinkPattern.replace(withLineBreaks) { match ->
            val text = match.groupValues[1]
            val url = match.value.substringAfter("](").substringBefore(")")
            if (url.isEmpty()) "\u200B[$text]\u200B"
            else match.value
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

private const val COLOR_MARKER = '\u200B'

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
