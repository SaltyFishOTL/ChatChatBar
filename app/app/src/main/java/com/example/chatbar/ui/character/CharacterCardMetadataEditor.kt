package com.example.chatbar.ui.character

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.chatbar.ui.kit.*

/** Card metadata stays separate from NovelAI tags and prompt fields. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterCardMetadataEditor(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    creator: String,
    onCreatorChange: (String) -> Unit,
    version: String,
    onVersionChange: (String) -> Unit,
    extensions: String
) {
    var newTag by remember { mutableStateOf("") }
    var showExtensions by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    CbField("角色卡标签", description = "用于卡片分类的元信息，不是生图标签，也不会加入人物提示词。") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEachIndexed { index, tag ->
                    CbButton(tag.ifBlank { "（空标签）" }, { onTagsChange(tags.filterIndexed { i, _ -> i != index }) },
                        modifier = Modifier.semantics { contentDescription = "移除标签 $tag" },
                        variant = ButtonVariant.Outline, icon = AppIcons.Close, supportingText = tag.ifBlank { "（空标签）" })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbInput(newTag, { newTag = it }, Modifier.weight(1f), placeholder = "新标签")
                CbButton("添加", {
                    val tag = newTag.trim()
                    if (tag.isNotEmpty() && tags.none { it.trim() == tag }) onTagsChange(tags + tag)
                    newTag = ""
                }, enabled = newTag.isNotBlank())
            }
        }
    }
    CbField("作者", description = "角色卡作者署名，与下方作者备注分开保存。") {
        CbInput(creator, onCreatorChange)
    }
    CbField("内容版本", description = "角色卡内容的版本标记，不会改变文件格式版本。") {
        CbInput(version, onVersionChange)
    }
    CbField("扩展数据（只读）", description = "保留其他工具写入的原始扩展；查看和复制不会改写或注入聊天。") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(if (showExtensions) "收起扩展数据" else "查看扩展数据", { showExtensions = !showExtensions },
                    enabled = extensions.isNotEmpty(), variant = ButtonVariant.Outline)
                CbButton("复制扩展", { clipboard.setText(AnnotatedString(extensions)) },
                    enabled = extensions.isNotEmpty(), variant = ButtonVariant.Ghost, icon = AppIcons.ContentCopy,
                    supportingText = "复制扩展")
            }
            if (extensions.isEmpty()) CbText("无扩展数据", color = ChatBarTheme.colors.mutedForeground)
            if (showExtensions) SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    CbText(extensions)
                }
            }
        }
    }
}

@Composable
internal fun CharacterAlternateGreetingsEditor(
    greetings: List<String>,
    onChange: (List<String>) -> Unit,
    onFullscreenEdit: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CbText("备用开场", style = ChatBarTheme.typography.heading)
        CbText("新建会话时可预览选择；不改变已有会话，重置会话仍使用主开场。空条目保留，但不在选择器中显示。",
            color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        greetings.forEachIndexed { index, greeting ->
            CbField("备用开场 ${index + 1}", onFullscreenEdit = { onFullscreenEdit(index, greeting) }) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CbIconButton(AppIcons.ExpandLess, "上移备用开场 ${index + 1}", {
                            onChange(greetings.toMutableList().also { it[index] = greetings[index - 1]; it[index - 1] = greeting })
                        }, enabled = index > 0)
                        CbIconButton(AppIcons.ExpandMore, "下移备用开场 ${index + 1}", {
                            onChange(greetings.toMutableList().also { it[index] = greetings[index + 1]; it[index + 1] = greeting })
                        }, enabled = index < greetings.lastIndex)
                        CbIconButton(AppIcons.Delete, "删除备用开场 ${index + 1}", {
                            onChange(greetings.filterIndexed { i, _ -> i != index })
                        }, tint = ChatBarTheme.colors.destructive)
                    }
                    CbInput(greeting, { next -> onChange(greetings.toMutableList().also { it[index] = next }) },
                        singleLine = false, minLines = 3)
                }
            }
        }
        CbButton("添加备用开场", { onChange(greetings + "") }, variant = ButtonVariant.Outline, icon = AppIcons.Add,
            supportingText = "添加备用开场")
    }
}
