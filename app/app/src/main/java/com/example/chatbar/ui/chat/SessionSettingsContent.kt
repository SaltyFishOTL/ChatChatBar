package com.example.chatbar.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.ui.kit.*
import java.io.File

@Composable
internal fun SessionSettingsContent(
    browser: SettingsBrowserState, active: Boolean,
    modelId: String?, onModel: (String?) -> Unit, defaultModelId: String?, models: List<ModelConfig>,
    imageModelId: String?, onImageModel: (String?) -> Unit, defaultImageModelId: String?,
    novelAiImageModel: NovelAiImageModel?, onNovelAiImageModel: (NovelAiImageModel?) -> Unit,
    inheritedNovelAiImageModel: NovelAiImageModel, novelAiFromCharacter: Boolean,
    novelAiNaturalLanguageMode: Boolean, onNovelAiNaturalLanguageMode: (Boolean) -> Unit,
    automaticImageGenerationEnabled: Boolean, onAutomaticImageGenerationEnabled: (Boolean) -> Unit,
    formatId: String?, onFormat: (String?) -> Unit, defaultFormatId: String?, formats: List<FormatCard>,
    worldBooks: List<WorldBook>, inheritedWorldBookIds: List<String>, extraWorldBookIds: List<String>, onExtraWorldBookIds: (List<String>) -> Unit,
    length: String, onLength: (String) -> Unit, lengthError: String?,
    language: String, onLanguage: (String) -> Unit,
    supplementary: String, onSupplementary: (String) -> Unit,
    playerName: String, onPlayerName: (String) -> Unit, playerSetting: String, onPlayerSetting: (String) -> Unit,
    inheritedPlayerName: String, inheritedPlayerSetting: String,
    background: String, onPickBackground: () -> Unit, onClearBackground: () -> Unit,
    audiobookModeEnabled: Boolean?, onAudiobookModeEnabled: (Boolean?) -> Unit,
    globalAudiobookModeEnabled: Boolean,
    voiceLanguage: String, onVoiceLanguage: (String) -> Unit,
    longTermMemoryEnabled: Boolean, onLongTermMemoryEnabled: (Boolean) -> Unit,
    onOpenMemory: () -> Unit, onOpenRag: () -> Unit, onClearHistory: () -> Unit,
    openFullscreen: (String, String, (String) -> Unit) -> Unit,
    backgroundImages: List<String> = emptyList(),
    onSelectBackground: (String) -> Unit = {},
    peopleCard: CharacterCard? = null,
    participation: List<SessionCharacterParticipation> = emptyList(),
    onPersonEnabled: (String, Boolean) -> Unit = { _, _ -> },
    autoPauseAbsentPeople: Boolean = false,
    onRebindCharacter: () -> Unit = {}
) {
    var showWorldBooks by remember { mutableStateOf(false) }
    val modelOptions = models.map { SessionOption(it.id, it.displayName) }
    val modelName = models.firstOrNull { it.id == (modelId ?: defaultModelId) }?.displayName ?: "未配置或不可用"
    val categories = listOf(
        SettingsCategory("reply", "对话与回复", "$modelName · $length 字"),
        SettingsCategory("world", "补充设定与世界书", "继承 ${inheritedWorldBookIds.size} 本 · 额外 ${extraWorldBookIds.size} 本世界书"),
        SettingsCategory("player", "玩家与背景", playerName.ifBlank { "跟随全局 · $inheritedPlayerName" }),
        SettingsCategory("images", "图片生成", "${(novelAiImageModel ?: inheritedNovelAiImageModel).displayName} · 自动生图${if (automaticImageGenerationEnabled) "开" else "关"}"),
        SettingsCategory("voice", "语音与朗读", "听书${if (audiobookModeEnabled ?: globalAudiobookModeEnabled) "开" else "关"} · ${voiceLanguage.ifBlank { "原文语言" }}"),
        SettingsCategory("memory", "记忆与清理", if (longTermMemoryEnabled) "长期记忆已开启" else "长期记忆已关闭")
    )
    val entries = listOfNotNull(
        SettingsEntry("rebind", "world", "更换对话角色卡", "关联 换绑 保留历史") {
            CbField("更换对话角色卡", description = "保留当前聊天、记忆和会话设置；更换前会创建恢复存档。") {
                CbButton("更换角色卡", onRebindCharacter)
            }
        },
        if (peopleCard?.editMode == CharacterEditMode.STRUCTURED && peopleCard.characters.isNotEmpty()) {
            SettingsEntry("people", "world", "会话人物启停", "角色 参与 开启 关闭") {
                CbField("会话人物启停", description = "只影响后续请求中的人物资料，已保存的设定和历史消息会保留。") {
                    val state = participation.singleOrNull { it.cardId == peopleCard.id }
                    peopleCard.characters.forEach { person ->
                        val checked = person.enabled && person.id !in state?.disabledPersonIds.orEmpty() &&
                            (!autoPauseAbsentPeople || person.id !in state?.automaticallyPausedPersonIds.orEmpty())
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                CbText(person.name)
                                if (!person.enabled) CbText("已在角色卡中关闭，请先到角色卡编辑器启用。", style = ChatBarTheme.typography.caption)
                            }
                            SettingsSwitch(checked, { onPersonEnabled(person.id, it) }, label = person.name, enabled = person.enabled)
                        }
                    }
                }
            }
        } else null,
        SettingsEntry("model", "reply", "会话对话模型", "默认对话模型 API") {
            InheritedSelect("会话对话模型", modelId, defaultModelId, modelOptions, onModel)
        },
        SettingsEntry("format", "reply", "格式卡") {
            InheritedSelect("格式卡", formatId, defaultFormatId, formats.map { SessionOption(it.id, it.name) }, onFormat)
        },
        SettingsEntry("length", "reply", "正文目标字数", "回复长度") {
            CbField("正文目标字数", description = "范围 $MIN_REPLY_LENGTH_CHARS–$MAX_REPLY_LENGTH_CHARS", error = lengthError) {
                CbNumberInput(length, onLength, isError = lengthError != null)
            }
        },
        SettingsEntry("language", "reply", "回复语言") {
            CbField("回复语言") { CbInput(language, onLanguage, placeholder = "中文") }
        },
        SettingsEntry("supplementary", "world", "临时补充设定", "角色 Prompt") {
            SettingsTextSummary("临时补充设定", supplementary) { openFullscreen("临时补充设定", supplementary, onSupplementary) }
        },
        SettingsEntry("world-books", "world", "世界书", "额外 继承") {
            SettingsLink("世界书", "继承 ${inheritedWorldBookIds.size} 本 · 额外 ${extraWorldBookIds.size} 本") { showWorldBooks = true }
        },
        SettingsEntry("player-name", "player", "玩家名称", "玩家名称覆盖 username") {
            CbField("玩家名称", description = if (playerName.isBlank()) "跟随全局 · $inheritedPlayerName" else "本会话已指定") {
                CbInput(playerName, onPlayerName, placeholder = "留空跟随全局")
            }
        },
        SettingsEntry("persona", "player", "玩家设定", "玩家设定覆盖") {
            SettingsTextSummary(if (playerSetting.isBlank()) "玩家设定 · 跟随全局" else "玩家设定 · 本会话已指定",
                playerSetting.ifBlank { inheritedPlayerSetting }) { openFullscreen("玩家设定", playerSetting, onPlayerSetting) }
        },
        SettingsEntry("background", "player", "聊天背景", "背景覆盖 恢复默认") {
            SettingsDetails("聊天背景", if (background.isBlank()) "跟随角色卡／默认背景" else "本会话已指定", browser.target == "background") {
                if (background.isNotBlank()) AsyncImage(File(background), "聊天背景预览", Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Crop)
                if (backgroundImages.isNotEmpty()) {
                    com.example.chatbar.ui.components.ChatBackgroundGallery(backgroundImages, background, onSelectBackground)
                }
                CbButton("选择图片", onPickBackground)
                if (background.isNotBlank()) CbButton("恢复角色默认背景", onClearBackground, variant = ButtonVariant.Ghost)
            }
        },
        SettingsEntry("automatic-image", "images", "自动生图") {
            CbField("自动生图", description = "回复后按现有自动生图规则判断并生成图片。") {
                SettingsSwitch(automaticImageGenerationEnabled, onAutomaticImageGenerationEnabled, label = "自动生图")
            }
        },
        SettingsEntry("image-prompt-model", "images", "图片提示词设计模型", "默认生图模型 图片 Prompt") {
            InheritedSelect("图片提示词设计模型", imageModelId, defaultImageModelId, modelOptions, onImageModel)
        },
        SettingsEntry("novel-model", "images", "NovelAI 生图模型") {
            val options = listOf<NovelAiImageModel?>(null) + NovelAiImageModel.entries
            CbField("NovelAI 生图模型", description = "用于本会话对话生图及该会话产生的朋友圈图片。") {
                CbSelect(novelAiImageModel, options, { it?.displayName ?: "跟随${if (novelAiFromCharacter) "角色卡" else "全局"} · ${inheritedNovelAiImageModel.displayName}" }, onNovelAiImageModel)
            }
        },
        SettingsEntry("natural-language-image", "images", "自然语言模式", "V5 中文 Prompt Tag") {
            val supportsNaturalLanguage = (novelAiImageModel ?: inheritedNovelAiImageModel) == NovelAiImageModel.V5_FULL
            CbField("自然语言模式", description = if (supportsNaturalLanguage) {
                "聊天生图使用工作室同款中文自然语言设计，保留角色英文 Tag 与独立分区；关闭则使用 Tag 模式。"
            } else {
                "仅适用于 V5；当前暂停生效，切回 V5 后恢复已保存的选择。"
            }) {
                SettingsSwitch(
                    novelAiNaturalLanguageMode,
                    onNovelAiNaturalLanguageMode,
                    label = "自然语言模式",
                    enabled = supportsNaturalLanguage
                )
            }
        },
        SettingsEntry("audiobook", "voice", "听书模式", "朗读 旁白") {
            CbField("听书模式", description = "分段气泡可朗读旁白；单气泡可合成整条回复。") {
                CbSelect(audiobookModeEnabled, listOf<Boolean?>(null, true, false), {
                    when (it) { null -> "跟随全局 · ${if (globalAudiobookModeEnabled) "开启" else "关闭"}"; true -> "开启"; false -> "关闭" }
                }, onAudiobookModeEnabled)
            }
        },
        SettingsEntry("voice-language", "voice", "语音使用语言", "翻译") {
            CbField("语音使用语言", description = "留空使用原文；填写后由 AI 翻译，再合成语音。") {
                CbInput(voiceLanguage, onVoiceLanguage, placeholder = "例如：日语、英语")
            }
        },
        SettingsEntry("memory-enabled", "memory", "长期记忆开关") {
            CbField("长期记忆", description = "回复后自动总结，后续对话带入记忆。") { SettingsSwitch(longTermMemoryEnabled, onLongTermMemoryEnabled, label = "长期记忆") }
        },
        SettingsEntry("memory-tools", "memory", "查看长期记忆", "编辑 历史 恢复") {
            SettingsLink("查看长期记忆", "当前状态、归档与历史版本", onOpenMemory)
        },
        SettingsEntry("rag-tools", "memory", "RAG 检索库", "检索块") {
            SettingsLink("RAG 检索库", "查看和编辑当前会话检索块", onOpenRag)
        },
        SettingsEntry("clear-history", "memory", "清空历史和长期记忆", "危险操作 删除") {
            CbText("危险操作", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.label)
            CbButton("清空历史和长期记忆", onClearHistory, modifier = Modifier.fillMaxWidth(), variant = ButtonVariant.Destructive)
        }
    )
    SettingsBrowser(categories, entries, browser, active = active)
    if (showWorldBooks) SessionWorldBookPicker(worldBooks, inheritedWorldBookIds, extraWorldBookIds, onExtraWorldBookIds) { showWorldBooks = false }
}

@Composable
private fun SettingsTextSummary(title: String, value: String, onEdit: () -> Unit) {
    SettingsLink(title, value.ifBlank { "未填写 · 点击编辑" }.replace("\n", " "), onEdit)
}

private data class SessionOption(val id: String?, val title: String)

@Composable
private fun InheritedSelect(title: String, selectedId: String?, defaultId: String?, options: List<SessionOption>, onSelect: (String?) -> Unit) {
    val defaultName = options.firstOrNull { it.id == defaultId }?.title ?: if (defaultId == null) "未设置" else "默认项不可用"
    val missing = selectedId != null && options.none { it.id == selectedId }
    val all = listOf(SessionOption(null, "跟随全局 · $defaultName")) +
        (if (missing) listOf(SessionOption(selectedId, "原选择不可用")) else emptyList()) + options
    CbField(title, description = if (missing) "原选择不可用；实际使用由当前模型配置规则决定，原选择仍保留。" else if (selectedId == null) "跟随全局" else "本会话已指定") {
        CbSelect(all.first { it.id == selectedId }, all, { it.title }, { onSelect(it.id) })
    }
}

@Composable
private fun SessionWorldBookPicker(books: List<WorldBook>, inherited: List<String>, selected: List<String>, onSelected: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    CbDialog(onDismissRequest = onDismiss, title = "会话世界书", confirm = { CbButton("完成", onDismiss) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbInput(query, { query = it }, placeholder = "搜索世界书名称")
            CbText("角色继承的世界书只读；额外选择在保存会话设置后生效。", style = ChatBarTheme.typography.caption)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                val visible = books.filter { it.name.contains(query, true) }
                if (visible.isEmpty()) item { CbText("没有匹配的世界书") }
                items(visible, key = { it.id }) { book ->
                    val fromCharacter = book.id in inherited
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            CbText(book.name)
                            if (fromCharacter) CbText("角色继承", style = ChatBarTheme.typography.caption)
                        }
                        SettingsSwitch(fromCharacter || book.id in selected, { enabled ->
                            onSelected(if (enabled) (selected + book.id).distinct() else selected - book.id)
                        }, enabled = !fromCharacter, label = book.name)
                    }
                }
            }
        }
    }
}
