package com.example.chatbar.ui.manage

import androidx.activity.compose.BackHandler

import com.example.chatbar.ui.kit.AppIcons

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.domain.appearance.ThemeColorHistoryPolicy
import com.example.chatbar.domain.appearance.ThemeColorHsv
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.moment.MomentPolicy
import com.example.chatbar.domain.moment.MomentReliabilityState
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.update.AppUpdateDownloadState
import com.example.chatbar.domain.update.DanbooruCatalogUpdateState
import com.example.chatbar.domain.update.UpdateCenterCheckResult
import com.example.chatbar.domain.voice.FishAudioTtsModels
import com.example.chatbar.domain.update.AppUpdateInstallResult
import com.example.chatbar.ui.components.UpdateCenterDialog
import com.example.chatbar.ui.components.CbAvatar
import com.example.chatbar.ui.components.CrashReportDeleteConfirmationDialog
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbNumberInput
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.utils.diagnostics.CrashReportManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch


import com.example.chatbar.ui.kit.*
import com.example.chatbar.ui.kit.FullscreenTextEditor
import androidx.compose.runtime.SideEffect
@Composable
internal fun GlobalSettingsScreen(
    saveRequest: Int,
    active: Boolean,
    onOpenModels: () -> Unit,
    onRegisterLeave: (((() -> Unit) -> Unit)) -> Unit,
    settings: AppSettings,
    player: PlayerSetting,
    characters: List<CharacterCard>,
    customModels: List<ModelConfig>,
    effectiveModels: List<ModelConfig>,
    auxiliaryTextModels: List<ModelConfig>,
    formats: List<FormatCard>,
    modelErrors: List<String>,
    apiTestStatus: String?,
    novelAiConfigured: Boolean,
    fishAudioConfigured: Boolean,
    momentsReliability: MomentReliabilityState,
    momentDebug: MomentDebugUiState,
    momentSchedulePreview: MomentSchedulePreviewUiState,
    onSave: suspend (AppSettings, AppSettings, PlayerSetting, PlayerSetting) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onThemeColor: (ThemeColorHsv) -> Unit,
    onBubbleFontScale: (Float) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onTestApiKey: (String, Boolean) -> Unit,
    onSaveNovelAiToken: (String) -> Unit,
    onClearNovelAiToken: () -> Unit,
    onSaveFishAudioApiKey: (String) -> Unit,
    onClearFishAudioApiKey: () -> Unit,
    onRefreshMomentsReliability: () -> Unit,
    onOpenMomentsAutoStartSettings: (android.content.Context) -> Unit,
    onOpenMomentsBatterySettings: (android.content.Context) -> Unit,
    onOpenMomentsNotificationSettings: (android.content.Context) -> Unit,
    onConfirmMomentsAutoStart: () -> Unit,
    onRefreshMomentSchedulePreview: () -> Unit,
    onGenerateDebugMoment: (String) -> Unit,
    onClearMomentDebug: () -> Unit,
    isApiTesting: Boolean,
    onCancelApiTest: () -> Unit
) {
    var playerName by rememberSettingDraft(player.playerName)
    var persona by rememberSettingDraft(player.globalPersona)
    var modelId by rememberSettingDraft(settings.defaultModelId)
    var imageModelId by rememberSettingDraft(settings.defaultImageModelId)
    var formatRepairModelId by rememberSettingDraft(settings.formatRepairModelId)
    var automaticFormatCheckEnabled by rememberSettingDraft(settings.automaticFormatCheckEnabled)
    var siliconFlowApiKey by rememberSettingDraft(settings.siliconFlowApiKey)
    var allowCleartextModelApi by rememberSettingDraft(settings.allowCleartextModelApi)
    var novelAiToken by rememberSettingDraft("")
    var fishAudioApiKey by rememberSettingDraft("")
    var fishAudioTtsModelId by rememberSettingDraft(settings.fishAudioTtsModelId)
    var voiceTagModelId by rememberSettingDraft(settings.voiceTagModelId)
    var audiobookModeEnabled by rememberSettingDraft(settings.audiobookModeEnabled)
    var novelAiImageModel by rememberSettingDraft(settings.novelAiImageModel)
    var novelAiImageAspectRatio by rememberSettingDraft(settings.novelAiImageAspectRatio)
    var novelAiPromptTranslationConsent by rememberSettingDraft(settings.novelAiPromptTranslationConsent)
    var formatId by rememberSettingDraft(settings.defaultFormatCardId)
    var themeMode by rememberSettingDraft(settings.themeMode)
    var themeColor by rememberSettingDraft(settings.themeColor)
    var themeColorHistory by rememberSettingDraft(settings.themeColorHistory)
    var themeColorPickerInitial by remember { mutableStateOf<ThemeColorHsv?>(null) }
    var momentsEnabled by rememberSettingDraft(settings.momentsEnabled)
    var momentsImagesEnabled by rememberSettingDraft(settings.momentsImagesEnabled)
    val initialMomentDelayRange = MomentPolicy.normalizedDelayHours(
        settings.momentsMinDelayHours,
        settings.momentsMaxDelayHours
    )
    var momentsMinDelayHours by rememberSettingDraft(initialMomentDelayRange.minHours.toFloat())
    var momentsMaxDelayHours by rememberSettingDraft(initialMomentDelayRange.maxHours.toFloat())
    var momentsBackgroundGuideDismissed by rememberSettingDraft(settings.momentsBackgroundGuideDismissed)
    var momentsAutoStartConfirmed by rememberSettingDraft(settings.momentsAutoStartConfirmed)
    var contextSize by rememberSettingDraft(settings.defaultContextWindowSize.coerceIn(0, 50).toFloat())
    var customContextSize by rememberSettingDraft(if (settings.defaultContextWindowSize > 50) settings.defaultContextWindowSize.toString() else "")
    var episodeMaxSourceTurns by rememberSettingDraft(settings.episodeMaxSourceTurns.coerceIn(1, 6).toFloat())
    var excludeAssistantStatusFromHistory by rememberSettingDraft(settings.excludeAssistantStatusFromHistory)
    var bubbleFontScale by rememberSettingDraft(settings.chatBubbleFontScale)
    var chatBackgroundImageOpacity by rememberSettingDraft(settings.chatBackgroundImageOpacity)
    var assistantSegmentedBubblesEnabled by rememberSettingDraft(settings.assistantSegmentedBubblesEnabled)
    var memoryTopK by rememberSettingDraft(settings.memoryRagTopK.coerceIn(0, 15).toFloat())
    var memoryThreshold by rememberSettingDraft(settings.memoryRagSimilarityThreshold)
    var docTopK by rememberSettingDraft(settings.docRagTopK.coerceIn(0, 15).toFloat())
    var docThreshold by rememberSettingDraft(settings.docRagSimilarityThreshold)
    var ragMode by rememberSettingDraft(settings.ragInjectionMode.toModeIndex().toFloat())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = ChatBarApp.instance.appUpdateManager
    val updateDownloadState by updateManager.downloadState.collectAsState()
    val catalogUpdateManager = ChatBarApp.instance.danbooruCatalogUpdateManager
    val catalogUpdateState by catalogUpdateManager.state.collectAsState()
    var checkingUpdate by rememberSettingDraft(false)
    var updateResult by remember { mutableStateOf<UpdateCenterCheckResult?>(null) }
    var confirmingCrashReportDelete by rememberSettingDraft(false)
    val pendingCrashReport by CrashReportManager.pendingReport.collectAsState()
    var momentDebugCardId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(characters) {
        if (momentDebugCardId == null || characters.none { it.id == momentDebugCardId }) {
            momentDebugCardId = characters.firstOrNull()?.id
        }
    }
    val selectedMomentDebugCard = characters.firstOrNull { it.id == momentDebugCardId }
    ?: characters.firstOrNull()
    val effectiveDefaultModelId = modelId ?: settings.presetDefaultModelKey?.let { "preset:$it" }
    ?: effectiveModels.firstOrNull()?.id
    val draftContextWindowSize = if (contextSize.toInt() >= 50 && customContextSize.isNotBlank()) {
        customContextSize.toIntOrNull() ?: 50
    } else {
        contextSize.toInt()
    }
    val novelAiImageSize = NovelAiImageSizePolicy.parseUserRatio(novelAiImageAspectRatio)
    val novelAiImageRatioError = NovelAiImageSizePolicy.validationError(novelAiImageAspectRatio)
    val draftMomentDelayRange = MomentPolicy.normalizedDelayHours(
        momentsMinDelayHours.roundToInt(),
        momentsMaxDelayHours.roundToInt()
    )
    val draftSettings = settings.copy(
        defaultModelId = modelId,
        defaultImageModelId = imageModelId,
        formatRepairModelId = formatRepairModelId,
        automaticFormatCheckEnabled = automaticFormatCheckEnabled,
        siliconFlowApiKey = siliconFlowApiKey.trim(),
        allowCleartextModelApi = allowCleartextModelApi,
        defaultFormatCardId = formatId,
        memoryRagTopK = memoryTopK.roundToInt().coerceIn(0, 15),
        memoryRagSimilarityThreshold = memoryThreshold,
        docRagTopK = docTopK.roundToInt().coerceIn(0, 15),
        docRagSimilarityThreshold = docThreshold,
        ragInjectionMode = ragMode.roundToInt().modeValue(),
        defaultContextWindowSize = draftContextWindowSize.coerceAtLeast(0),
        episodeMaxSourceTurns = episodeMaxSourceTurns.roundToInt().coerceIn(1, 6),
        excludeAssistantStatusFromHistory = excludeAssistantStatusFromHistory,
        novelAiImageModel = novelAiImageModel,
        novelAiImageAspectRatio = novelAiImageAspectRatio.trim(),
        novelAiPromptTranslationConsent = novelAiPromptTranslationConsent,
        fishAudioTtsModelId = fishAudioTtsModelId,
        voiceTagModelId = voiceTagModelId,
        audiobookModeEnabled = audiobookModeEnabled,
        momentsEnabled = momentsEnabled,
        momentsImagesEnabled = momentsImagesEnabled,
        momentsMinDelayHours = draftMomentDelayRange.minHours,
        momentsMaxDelayHours = draftMomentDelayRange.maxHours,
        momentsBackgroundGuideDismissed = momentsBackgroundGuideDismissed,
        momentsAutoStartConfirmed = momentsAutoStartConfirmed,
        chatBackgroundImageOpacity = chatBackgroundImageOpacity,
        assistantSegmentedBubblesEnabled = assistantSegmentedBubblesEnabled,
        themeColor = themeColor,
        themeColorHistory = themeColorHistory
    )
    val savedSettingsComparable = settings
    val settingsDirty = draftSettings != savedSettingsComparable ||
    playerName != player.playerName ||
    persona != player.globalPersona
    LaunchedEffect(settingsDirty) { onDirtyChange(settingsDirty) }
    val browser = remember { SettingsBrowserState() }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    fun save(after: (() -> Unit)? = null) {
        if (saving) return
        if (novelAiImageRatioError != null) {
            browser.open("images", "image-ratio")
            saveError = novelAiImageRatioError
            pendingLeave = null
            return
        }
        if (novelAiToken.isNotBlank() || fishAudioApiKey.isNotBlank()) {
            browser.open(if (novelAiToken.isNotBlank()) "images" else "voice",
                if (novelAiToken.isNotBlank()) "images-credential" else "voice-credential")
            saveError = "请先在密钥窗口保存密钥，或清空未提交的输入。"
            pendingLeave = null
            return
        }
        if (contextSize.toInt() >= 50 && customContextSize.isNotBlank() &&
            (customContextSize.toIntOrNull() == null || (customContextSize.toIntOrNull() ?: 0) < 0)) {
            browser.open("reply", "context-window")
            saveError = "上下文上限必须是非负整数"
            pendingLeave = null
            return
        }
        saving = true
        saveError = null
        scope.launch {
            runCatching { onSave(settings, draftSettings, player, player.copy(playerName = playerName, globalPersona = persona)) }
            .onSuccess { pendingLeave = null; after?.invoke() }
            .onFailure { saveError = "保存失败：${it.message}"; pendingLeave = null }
            saving = false
        }
    }
    val currentRequestLeave by androidx.compose.runtime.rememberUpdatedState<(() -> Unit) -> Unit> { action ->
        if (!saving) {
            if (settingsDirty || novelAiToken.isNotBlank() || fishAudioApiKey.isNotBlank()) pendingLeave = action else action()
        }
    }
    val requestLeave: (() -> Unit) -> Unit = remember { { action -> currentRequestLeave(action) } }
    SideEffect { onRegisterLeave(requestLeave) }
    var consumedSaveRequest by remember { mutableIntStateOf(saveRequest) }
    LaunchedEffect(saveRequest) {
        if (saveRequest != consumedSaveRequest) { consumedSaveRequest = saveRequest; save() }
    }
    pendingLeave?.let { action ->
        UnsavedSettingsDialog(
            onSave = { save(action) },
            onDiscard = { pendingLeave = null; action() },
            onContinue = { pendingLeave = null }, busy = saving
        )
    }
    themeColorPickerInitial?.let { initialColor ->
        ThemeColorPickerDialog(
            initialColor = initialColor,
            onDismissRequest = { themeColorPickerInitial = null },
            onApply = { selectedColor ->
                val normalizedColor = selectedColor.normalized()
                themeColorHistory = ThemeColorHistoryPolicy.update(
                    current = themeColor,
                    next = normalizedColor,
                    history = themeColorHistory
                )
                themeColor = normalizedColor
                onThemeColor(normalizedColor)
                themeColorPickerInitial = null
            }
        )
    }
    val modelOptions = effectiveModels.map { IdOption(it.id, it.displayName) }
    val formatRepairModelOptions = customModels
    .filter { it.baseUrl.isNotBlank() && it.modelName.isNotBlank() }.distinctBy(ModelConfig::id)
    .map { IdOption(it.id, it.displayName) }
    val categories = listOf(
        SettingsCategory("models", "模型与连接", effectiveModels.firstOrNull { it.id == effectiveDefaultModelId }?.displayName ?: "未选择模型"),
        SettingsCategory("reply", "回复与记忆", "上下文 ${draftContextWindowSize} 组 · 自动格式检查${if (automaticFormatCheckEnabled) "开" else "关"}"),
        SettingsCategory("appearance", "外观与显示", "${when(themeMode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" }} · 字号 ${bubbleFontScale}×"),
        SettingsCategory("player", "玩家身份", playerName.ifBlank { "未填写玩家名称" }),
        SettingsCategory("migration", "数据迁移", "完整导出与换机恢复"),
        SettingsCategory("images", "图片生成", "${novelAiImageModel.displayName} · ${if (novelAiConfigured) "已配置密钥" else "未配置密钥"}"),
        SettingsCategory("voice", "语音与朗读", "${if (fishAudioConfigured) "已配置密钥" else "未配置密钥"} · 听书${if (audiobookModeEnabled) "开" else "关"}"),
        SettingsCategory("moments", "朋友圈", if (momentsEnabled) formatMomentFrequency(draftMomentDelayRange.minHours, draftMomentDelayRange.maxHours) else "已关闭"),
        SettingsCategory("updates", "更新与诊断", "${BuildConfig.VERSION_NAME} · ${if (pendingCrashReport != null) "有诊断报告" else "暂无诊断报告"}")
    )
    val entries = listOf(
        SettingsEntry("app-backup", "migration", "全量存档", "导出 导入 全量存档 备份 换机 数据迁移 cbbackup") {
            SettingsLink("全量存档", "导出全部本地进度，或恢复其他设备的完整存档") {
                requestLeave { ChatBarApp.instance.appBackupService.open() }
            }
        },
        SettingsEntry("connection-help", "models", "连接说明", "连接说明", searchable = false) {
            CbText(
                "HTTPS 模型 API Key 留空时使用全局默认 API Key；允许明文 HTTP 后，HTTP 模型留空表示无需鉴权。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        },
        SettingsEntry("model-errors", "models", "模型与 API", "模型与 API", searchable = false) {
            modelErrors.forEach { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
        },
        SettingsEntry("global-api-key", "models", "全局默认 API Key", "API Key 密钥 凭据") {
            SettingsDetails("全局默认 API Key", if (siliconFlowApiKey.isBlank()) "未配置" else "已填写", browser.target == "global-api-key") {
                CbField("全局默认 API Key", description = "完成编辑后，点击页面顶部保存，与其他普通设置一起生效。") {
                    CbInput(siliconFlowApiKey, { siliconFlowApiKey = it }, placeholder = "sk-...", secure = true)
                }
            }
        },
        SettingsEntry("http", "models", "允许明文 HTTP 模型 API", "允许明文 HTTP 模型 API") {
            SettingsDetails("高级连接", "允许明文 HTTP 模型 API", browser.target == "http") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        CbText("允许明文 HTTP 模型 API", style = ChatBarTheme.typography.label)
                        CbText(
                            "仅用于可信本地部署。开启后 API Key、提示词和聊天内容可能被同一网络中的第三方窃取或篡改。",
                            color = ChatBarTheme.colors.destructive,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                    SettingsSwitch(
                        checked = allowCleartextModelApi,
                        onCheckedChange = { allowCleartextModelApi = it }, label = "允许明文 HTTP 模型 API")
                }
            }
        },
        SettingsEntry("test-connection", "models", "测试连接", "测试连接") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(
                    if (isApiTesting) "中断测试" else "测试连接",
                    {
                        CrashReportManager.recordBreadcrumb(
                            "action",
                            "test_model_connection cleartext=$allowCleartextModelApi"
                        )
                        if (isApiTesting) onCancelApiTest()
                        else onTestApiKey(siliconFlowApiKey, allowCleartextModelApi)
                    },
                    variant = ButtonVariant.Outline
                )
            }
        },
        SettingsEntry("connection-result", "models", "模型与 API", "模型与 API", searchable = false) {
            apiTestStatus?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption) }
        },
        SettingsEntry("default-model", "models", "默认对话模型", "默认对话模型") {
            RequiredSelect("默认对话模型", effectiveDefaultModelId, modelOptions, { modelId = it })
        },
        SettingsEntry("image-prompt-model", "images", "图片提示词设计模型", "默认生图模型 图片 Prompt") {
            OptionalSelect(
                "图片提示词设计模型",
                imageModelId,
                modelOptions,
                { imageModelId = it },
                noneLabel = "跟随默认对话模型"
            )
        },
        SettingsEntry("format-repair-model", "reply", "格式修复模型", "格式修复模型") {
            OptionalSelect(
                "格式修复模型",
                formatRepairModelId,
                formatRepairModelOptions,
                { formatRepairModelId = it },
                noneLabel = "跟随默认对话模型"
            )
        },
        SettingsEntry("default-format", "reply", "默认格式卡", "默认格式卡") {
            OptionalSelect("默认格式卡", formatId, formats.map { IdOption(it.id, it.name) }, { formatId = it })
        },
        SettingsEntry("automatic-format", "reply", "自动检查格式", "自动检查格式") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("自动检查格式", style = ChatBarTheme.typography.label)
                    CbText(
                        "此功能会提升大约1/10的token消耗，并放慢回复速度，谨慎开启",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(
                    checked = automaticFormatCheckEnabled,
                    onCheckedChange = { automaticFormatCheckEnabled = it }, label = "自动检查格式")
            }
        },
        SettingsEntry("context-window", "reply", "保留上下文消息", "上下文 自定义 上限 组数") {
            SliderField("保留上下文消息：${contextSize.toInt()} 组", contextSize, 0f..50f, 49) { contextSize = it }
            if (contextSize.toInt() >= 50) {
                CbField("自定义上下文上限") {
                    CbNumberInput(
                        customContextSize,
                        { customContextSize = it },
                        placeholder = "50"
                    )
                }
            }
        },
        SettingsEntry("episode-turns", "reply", "每条近期记忆目标", "每条近期记忆目标") {
            SliderField(
                "每条近期记忆目标：${episodeMaxSourceTurns.roundToInt()} 轮连续对话",
                episodeMaxSourceTurns,
                1f..6f,
                4
            ) { episodeMaxSourceTurns = it }
        },
        SettingsEntry("episode-help", "reply", "近期记忆说明", "近期记忆说明", searchable = false) {
            SettingsDetails("了解更多", "近期记忆按连续对话轮数生成") {
                CbText("固定按目标轮数生成；末尾不足时正常等待。仅修复旧BUG留下且两侧已有记忆的内部缺口时，允许单轮收尾。默认 2 轮。")
            }
        },
        SettingsEntry("history-status", "reply", "上下文剔除状态栏", "上下文剔除状态栏") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("上下文剔除状态栏", style = ChatBarTheme.typography.label)
                    CbText(
                        "默认开启；历史助手消息不发送状态栏和横线选项，上一条助手回复保留完整内容。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(
                    checked = excludeAssistantStatusFromHistory,
                    onCheckedChange = { excludeAssistantStatusFromHistory = it }, label = "上下文剔除状态栏")
            }
        },
        SettingsEntry("rag", "reply", "检索参数", "RAG", searchItems = listOf("注入强度", "文档召回数量", "文档相似度", "记忆召回数量", "记忆相似度")) {
            SettingsDetails("检索参数", "RAG 强度、召回数量与相似度", browser.target == "rag") {

                SliderField("注入强度：${ragMode.roundToInt().modeLabel()}", ragMode, 0f..3f, 2) { ragMode = it }
                SliderField("文档召回数量：${docTopK.toInt()}", docTopK, 0f..15f, 14) { docTopK = it }
                SliderField("文档相似度：${"%.2f".format(docThreshold)}", docThreshold, 0.3f..0.95f) { docThreshold = it }
                SliderField("记忆召回数量：${memoryTopK.toInt()}", memoryTopK, 0f..15f, 14) { memoryTopK = it }
                SliderField("记忆相似度：${"%.2f".format(memoryThreshold)}", memoryThreshold, 0.3f..0.95f) { memoryThreshold = it }

            }
        },
        SettingsEntry("theme-color", "appearance", "主题色", "主题色") {
            CbText("主题色 · 立即生效", style = ChatBarTheme.typography.label)
            ThemeColorSettingControls(
                current = themeColor,
                history = themeColorHistory,
                onSelectColor = { themeColorPickerInitial = it }
            )
        },
        SettingsEntry("segmented-bubbles", "appearance", "角色回复分段气泡", "角色回复分段气泡") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("角色回复分段气泡", style = ChatBarTheme.typography.label)
                    CbText(
                        "默认开启；关闭后，助手回复按整条消息显示为单个气泡。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(assistantSegmentedBubblesEnabled, { assistantSegmentedBubblesEnabled = it }, label = "角色回复分段气泡")
            }
        },
        SettingsEntry("font-scale", "appearance", "气泡字号", "气泡字号") {
            CbText("字号 · 立即生效", style = ChatBarTheme.typography.caption)
            SliderField("气泡字号：${"%.1f".format(bubbleFontScale)}x", bubbleFontScale, 0.5f..1.5f, 9) {
                bubbleFontScale = it
                onBubbleFontScale(it)
            }
        },
        SettingsEntry("background-opacity", "appearance", "聊天背景图透明度", "聊天背景图透明度") {
            SliderField(
                "聊天背景图透明度：${(chatBackgroundImageOpacity * 100).roundToInt()}%",
                chatBackgroundImageOpacity,
                0f..1f,
                19
            ) { chatBackgroundImageOpacity = it }
        },
        SettingsEntry("theme-mode", "appearance", "明暗模式", "明暗模式") {
            CbField("明暗模式", description = "立即生效") { CbSelect(themeMode, ThemeMode.entries, { when(it) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" } }, { themeMode = it; onThemeMode(it) }) }
        },
        SettingsEntry("player-name", "player", "玩家名称", "玩家名称") {
            CbField("玩家名称") { CbInput(playerName, { playerName = it }, placeholder = "旅行者") }
        },
        SettingsEntry("player-persona", "player", "玩家全局设定", "玩家全局设定") {
            SettingsLink("玩家全局设定", persona.ifBlank { "未填写 · 点击编辑" }.replace("\n", " ")) { fullscreen = true }
        },
        SettingsEntry("moments-enabled", "moments", "开启朋友圈功能", "开启朋友圈功能") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    CbText("开启朋友圈功能", style = ChatBarTheme.typography.label)
                    CbText(
                        "默认关闭；关闭时根 Tab 隐藏，后台不生成。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(momentsEnabled, { enabled ->
                        momentsEnabled = enabled
                    }, label = "开启朋友圈功能")
            }
        },
        SettingsEntry("moment-schedule", "moments", "自动配图与生成间隔", "朋友圈", searchItems = listOf("自动生成图片", "最短间隔", "最长间隔")) {
            if (momentsEnabled) {

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        CbText("自动生成图片", style = ChatBarTheme.typography.label)
                        CbText(
                            "默认开启。关闭后不再自动生图，朋友圈显示深色【点击查看私密图片】，可点击按需配图。",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                    SettingsSwitch(momentsImagesEnabled, { enabled ->
                            momentsImagesEnabled = enabled
                        }, label = "自动生成图片")
                }
                CbText(
                    "当前排程：${formatMomentFrequency(draftMomentDelayRange.minHours, draftMomentDelayRange.maxHours)}尝试一次。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                SliderField(
                    "最短间隔：${draftMomentDelayRange.minHours} 小时",
                    momentsMinDelayHours,
                    MomentPolicy.MIN_CONFIG_DELAY_HOURS.toFloat()..MomentPolicy.MAX_CONFIG_DELAY_HOURS.toFloat(),
                    MomentPolicy.MAX_CONFIG_DELAY_HOURS - MomentPolicy.MIN_CONFIG_DELAY_HOURS - 1
                ) { value ->
                    val next = value.roundToInt()
                    .coerceIn(MomentPolicy.MIN_CONFIG_DELAY_HOURS, MomentPolicy.MAX_CONFIG_DELAY_HOURS)
                    .toFloat()
                    momentsMinDelayHours = next
                    if (momentsMaxDelayHours < next) momentsMaxDelayHours = next
                }
                SliderField(
                    "最长间隔：${draftMomentDelayRange.maxHours} 小时",
                    momentsMaxDelayHours,
                    MomentPolicy.MIN_CONFIG_DELAY_HOURS.toFloat()..MomentPolicy.MAX_CONFIG_DELAY_HOURS.toFloat(),
                    MomentPolicy.MAX_CONFIG_DELAY_HOURS - MomentPolicy.MIN_CONFIG_DELAY_HOURS - 1
                ) { value ->
                    val next = value.roundToInt()
                    .coerceIn(MomentPolicy.MIN_CONFIG_DELAY_HOURS, MomentPolicy.MAX_CONFIG_DELAY_HOURS)
                    .toFloat()
                    momentsMaxDelayHours = next
                    if (momentsMinDelayHours > next) momentsMinDelayHours = next
                }
                CbText(
                    "应用运行时按排程补生成到期朋友圈；应用未运行时不会后台调用对话模型或 NovelAI。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                CbText(
                    "未配置 NovelAI Token 时只生成文字；生成失败会在朋友圈显示占位，可手动重试。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )

            } else CbText("开启朋友圈并保存后，可按排程生成内容。", color = ChatBarTheme.colors.mutedForeground)
        },
        SettingsEntry("moment-debug", "moments", "排程预览与调试", "排程预览与调试") {
            SettingsDetails("排程预览与调试", "查看排程、手动生成和请求记录", browser.target == "moment-debug", fullscreen = true) {
                if (!settings.momentsEnabled) CbText("请先开启朋友圈并保存，再运行排程预览或调试。")
                if (settings.momentsEnabled) {
                    SettingsSection("朋友圈调试") {
                        CbText(
                            "立即为指定角色卡生成新朋友圈。调试会记录判定结果，但不受排程、限额、48 小时门槛阻断。",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                        MomentSchedulePreviewBlock(
                            state = momentSchedulePreview,
                            onRefresh = onRefreshMomentSchedulePreview
                        )
                        CbField("角色卡") {
                            CbSelect(
                                selectedMomentDebugCard,
                                characters,
                                { it.name.ifBlank { "未命名角色" } },
                                { momentDebugCardId = it.id }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            CbButton(
                                text = if (momentDebug.isRunning) "生成中..." else "立即生成",
                                onClick = {
                                    selectedMomentDebugCard?.let { onGenerateDebugMoment(it.id) }
                                },
                                enabled = selectedMomentDebugCard != null && !momentDebug.isRunning
                            )
                            if (momentDebug.result != null) {
                                CbButton("清空日志", onClearMomentDebug, variant = ButtonVariant.Ghost)
                            }
                            if (momentDebug.isRunning) CbSpinner(Modifier.size(24.dp))
                        }
                        if (characters.isEmpty()) {
                            CbText("暂无角色卡。", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                        }
                        momentDebug.result?.let { result ->
                            result.errorMessage?.let { error ->
                                CbText("生成失败：$error", color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                            }
                            result.post?.let { post ->
                                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.surfaceSubtle) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CbAvatar(
                                                imagePath = post.senderAvatar,
                                                contentDescription = post.senderName,
                                                size = 36.dp,
                                                rounded = true,
                                                fallbackIcon = AppIcons.Face
                                            )
                                            Column(Modifier.weight(1f)) {
                                                CbText(post.senderName, style = ChatBarTheme.typography.label)
                                                CbText(
                                                    if (post.isPrivate) "仅你可见 · 赞数 ${post.displayLikeCount}" else "公开 · 赞数 ${post.displayLikeCount}",
                                                    color = ChatBarTheme.colors.mutedForeground,
                                                    style = ChatBarTheme.typography.caption
                                                )
                                            }
                                        }
                                        CbText(post.text, style = ChatBarTheme.typography.body)
                                        val imagePath = post.imagePath?.takeIf { it.isNotBlank() }
                                        if (imagePath != null) {
                                            AsyncImage(
                                                model = File(imagePath),
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).clip(RoundedCornerShape(6.dp)),
                                                contentScale = ContentScale.Crop
                                            )
                                            CbText("图片：$imagePath", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                                        } else {
                                            CbText("图片：未生成", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                                        }
                                    }
                                }
                            }
                            result.exchanges.forEach { exchange ->
                                MomentDebugExchangeBlock(exchange)
                            }
                        }
                    }
                }
            }
        },
        SettingsEntry("novel-status", "images", "Persistent API Token 已配置", "Persistent API Token 已配置", searchable = false) {
            CbText(
                if (novelAiConfigured) "Persistent API Token 已配置" else "未配置；聊天生图按钮不会显示",
                color = if (novelAiConfigured) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        },
        SettingsEntry("novel-model", "images", "NovelAI 模型", "NovelAI 模型") {
            CbField(
                "NovelAI 模型",
                description = "作为会话未单独选择时的默认值，并控制角色图片；生图工作室使用工作室内的独立设置。"
            ) {
                CbSelect(
                    value = novelAiImageModel,
                    options = NovelAiImageModel.entries,
                    optionLabel = { it.displayName },
                    onValueChange = { novelAiImageModel = it }
                )
            }
        },
        SettingsEntry("prompt-translation", "images", "Prompt 中文翻译注释", "Prompt 中文翻译注释") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("Prompt 中文翻译注释", style = ChatBarTheme.typography.label)
                    CbText(
                        "优先使用本地 Danbooru 词条库精确中文名，未命中时使用内置离线词典。注释不进入实际 Prompt。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(
                    checked = novelAiPromptTranslationConsent == NovelAiPromptTranslationConsent.ENABLED,
                    onCheckedChange = { enabled ->
                        novelAiPromptTranslationConsent = if (enabled) {
                            NovelAiPromptTranslationConsent.ENABLED
                        } else {
                            NovelAiPromptTranslationConsent.DISABLED
                        }
                    }, label = "Prompt 中文翻译注释")
            }
        },
        SettingsEntry("image-ratio", "images", "图片比例", "图片比例") {
            CbField(
                "图片比例",
                description = novelAiImageSize?.let {
                    "将按 ${it.width}x${it.height} 生成；留空时 AI 自动选择 Normal Portrait、Square 或 Horizontal。"
                } ?: "留空时 AI 自动选择 Normal Portrait、Square 或 Horizontal；支持 1:1、16:9、832x1216。",
                error = novelAiImageRatioError
            ) {
                CbInput(
                    novelAiImageAspectRatio,
                    { novelAiImageAspectRatio = it },
                    placeholder = "留空自动，或输入 1:1 / 16:9 / 9:16",
                    isError = novelAiImageRatioError != null
                )
            }
        },
        SettingsEntry("audiobook", "voice", "听书模式", "听书模式") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("听书模式", style = ChatBarTheme.typography.label)
                    CbText(
                        "作为会话默认值；分段模式可朗读旁白，非分段模式可朗读整条助手消息。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                SettingsSwitch(audiobookModeEnabled, { audiobookModeEnabled = it }, label = "听书模式")
            }
        },
        SettingsEntry("voice-status", "voice", "语音配置状态", "语音配置状态", searchable = false) {
            CbText(
                if (fishAudioConfigured) {
                    "API Key 已加密保存。角色编辑器和聊天语音入口已启用。"
                } else {
                    "未配置；隐藏音色选择和新语音生成入口，已有本地语音仍可播放。"
                },
                color = if (fishAudioConfigured) {
                    ChatBarTheme.colors.primary
                } else {
                    ChatBarTheme.colors.mutedForeground
                },
                style = ChatBarTheme.typography.caption
            )
        },
        SettingsEntry("tts-model", "voice", "TTS 模型", "TTS 模型") {
            CbField(
                "TTS 模型",
                description = "S2 系列使用 [tag]；S1 使用 (tag)。默认免费模型 s2.1-pro-free。"
            ) {
                CbSelect(
                    fishAudioTtsModelId,
                    FishAudioTtsModels.supported,
                    { it },
                    { fishAudioTtsModelId = it }
                )
            }
        },
        SettingsEntry("voice-tag-model", "voice", "语音翻译/标签模型", "语音翻译/标签模型") {
            OptionalSelect(
                label = "语音翻译/标签模型",
                selectedId = voiceTagModelId,
                options = auxiliaryTextModels.map { IdOption(it.id, it.displayName) },
                onSelected = { voiceTagModelId = it },
                noneLabel = "当前会话对话模型"
            )
        },
        SettingsEntry("voice-error", "voice", "语音配置状态", "语音配置状态", searchable = false) {
            if (voiceTagModelId != null && auxiliaryTextModels.none { it.id == voiceTagModelId }) {
                CbText(
                    "已选语音翻译/标签模型失效。需要 AI 标签或已设置语音使用语言时，语音生成会被禁用；未设置语言的听书模式不使用此模型。",
                    color = ChatBarTheme.colors.destructive,
                    style = ChatBarTheme.typography.caption
                )
            }
        },
        SettingsEntry("ai-request-logs", "updates", "AI 请求日志", "AI 请求日志 Token 提示词 模板 拒答") {
            SettingsDetails("AI 请求日志", "查看场景、模板版本、最终请求与用量", browser.target == "ai-request-logs", fullscreen = true) {
                AiRequestLogsContent()
            }
        },
        SettingsEntry("crash", "updates", "崩溃诊断", "崩溃诊断") {

            CbText(
                "异常退出时在本机生成一个脱敏文本文件；下次启动可直接发送。不会包含 API Key、Token、聊天正文或 Prompt。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            val report = pendingCrashReport
            if (report == null) {
                CbText(
                    "暂无待发送报告",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            } else {
                CbText(
                    "${report.trigger} · ${SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(report.createdAt))}",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        "发送报告",
                        {
                            CrashReportManager.sharePendingReport(context)
                            .onFailure { error ->
                                Toast.makeText(
                                    context,
                                    "发送报告失败：${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        variant = ButtonVariant.Outline
                    )
                    CbButton(
                        "删除报告",
                        { confirmingCrashReportDelete = true },
                        variant = ButtonVariant.Destructive
                    )
                }
            }

        },
        SettingsEntry("updates", "updates", "应用与词库更新", "应用与词库更新") {

            CbText(
                "当前版本：${BuildConfig.VERSION_NAME}",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            CbText(
                "检查更新会同时检查 ChatBar 应用和 Danbooru 词条库。可分别更新，也可同时下载。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CbButton(
                    text = if (checkingUpdate) "检查中..." else "检查更新",
                    onClick = {
                        if (checkingUpdate) return@CbButton
                        checkingUpdate = true
                        scope.launch {
                            runCatching {
                                ChatBarApp.instance.updateCenterChecker.check()
                            }.onSuccess { result ->
                                if (!result.hasVisibleResult) {
                                    Toast.makeText(
                                        context,
                                        "应用和 Danbooru 词条库均为最新",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    updateResult = result
                                }
                            }.onFailure { error ->
                                Toast.makeText(context, "检查更新失败：${error.message}", Toast.LENGTH_SHORT).show()
                            }
                            checkingUpdate = false
                        }
                    },
                    enabled = !checkingUpdate,
                    variant = ButtonVariant.Outline
                )
                if (checkingUpdate) CbSpinner(Modifier.size(24.dp))
            }

        },
        SettingsEntry("images-credential", "images", "NovelAI 密钥", "NovelAI API Token 凭据 密钥") {
            SettingsDetails("NovelAI 密钥", if (novelAiConfigured) "已配置" else "未配置", browser.target == "images-credential") {
                CbField(
                    "Persistent API Token",
                    description = "Token 使用 Android Keystore 加密保存，不写入应用设置 JSON。"
                ) {
                    CbInput(
                        novelAiToken,
                        { novelAiToken = it },
                        placeholder = if (novelAiConfigured) "输入新 Token 以替换" else "粘贴 NovelAI Persistent API Token",
                        secure = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("保存密钥", {
                            if (novelAiToken.isNotBlank()) {
                                onSaveNovelAiToken(novelAiToken)
                                novelAiToken = ""
                            }
                        }, enabled = novelAiToken.isNotBlank())
                    if (novelAiConfigured) {
                        CbButton("清除", onClearNovelAiToken, variant = ButtonVariant.Destructive)
                    }
                }
            }
        },
        SettingsEntry("voice-credential", "voice", "Fish Audio 密钥", "Fish Audio API Key 凭据 密钥") {
            SettingsDetails("Fish Audio 密钥", if (fishAudioConfigured) "已配置" else "未配置", browser.target == "voice-credential") {
                CbField(
                    "API Key",
                    description = "使用 Android Keystore 加密保存，不写入设置、导出文件或日志。保存时不发起连通性测试。"
                ) {
                    CbInput(
                        fishAudioApiKey,
                        { fishAudioApiKey = it },
                        placeholder = if (fishAudioConfigured) "输入新 Key 以替换" else "粘贴 Fish Audio API Key",
                        secure = true
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        "保存密钥",
                        {
                            onSaveFishAudioApiKey(fishAudioApiKey)
                            fishAudioApiKey = ""
                        },
                        enabled = fishAudioApiKey.isNotBlank()
                    )
                    if (fishAudioConfigured) {
                        CbButton(
                            "清除",
                            onClearFishAudioApiKey,
                            variant = ButtonVariant.Destructive
                        )
                    }
                }
            }
        },
        SettingsEntry("manage-models", "models", "管理模型", "管理模型") {
            SettingsLink("管理模型", "添加、编辑对话与向量模型") { requestLeave(onOpenModels) }
        }
    )
    Column(Modifier.fillMaxSize()) {
        if (saving) CbText("正在保存…", Modifier.padding(horizontal = 16.dp))
        saveError?.let { CbText(it, Modifier.padding(horizontal = 16.dp), color = ChatBarTheme.colors.destructive) }
        SettingsBrowser(categories, entries, browser, Modifier.weight(1f), active = active && !fullscreen)
    }
    BackHandler(fullscreen) { fullscreen = false }
    if (fullscreen) FullscreenTextEditor(title = "玩家全局设定", text = persona,
        onTextChange = { persona = it }, visible = true, onDismiss = { fullscreen = false })
    updateResult?.let { result ->
        val appInfo = result.appUpdate
        val catalogInfo = result.catalogUpdate
        val visibleAppState = remember(updateDownloadState, appInfo) {
            appInfo?.let(updateManager::stateFor) ?: AppUpdateDownloadState.Idle
        }
        val visibleCatalogState = remember(catalogUpdateState, catalogInfo) {
            catalogInfo?.let(catalogUpdateManager::stateFor) ?: DanbooruCatalogUpdateState.Idle
        }
        UpdateCenterDialog(
            result = result,
            appDownloadState = visibleAppState,
            catalogUpdateState = visibleCatalogState,
            onDismiss = { updateResult = null },
            onAppAction = {
                appInfo?.let { info ->
                    when {
                        info.apkAsset == null -> {
                            val releaseUrl = info.releaseUrl.ifBlank { AppUpdateChecker.DEFAULT_RELEASES_URL }
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
                            }.onFailure {
                                Toast.makeText(context, "无法打开 GitHub Release 页面", Toast.LENGTH_SHORT).show()
                            }
                        }
                        visibleAppState is AppUpdateDownloadState.Downloading -> updateManager.cancelDownload()
                        visibleAppState is AppUpdateDownloadState.Ready -> {
                            updateManager.requestInstall(context, info)
                            .onSuccess { installResult ->
                                if (installResult == AppUpdateInstallResult.PermissionRequired) {
                                    Toast.makeText(
                                        context,
                                        "请允许 ChatBar 安装未知应用，返回后再次点“安装应用”",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            .onFailure { error ->
                                Toast.makeText(
                                    context,
                                    "无法安装更新：${error.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        else -> updateManager.startDownload(info)
                    }
                }
            },
            onCatalogAction = {
                catalogInfo?.let { info ->
                    when (visibleCatalogState) {
                        is DanbooruCatalogUpdateState.Downloading -> catalogUpdateManager.cancelDownload()
                        is DanbooruCatalogUpdateState.Idle,
                        is DanbooruCatalogUpdateState.Failed -> catalogUpdateManager.startDownload(info)
                        else -> Unit
                    }
                }
            },
            onUpdateAll = {
                appInfo?.let { info ->
                    if (info.apkAsset != null &&
                        visibleAppState !is AppUpdateDownloadState.Downloading &&
                        visibleAppState !is AppUpdateDownloadState.Ready
                    ) {
                        updateManager.startDownload(info)
                    }
                }
                catalogInfo?.let { info ->
                    if (visibleCatalogState is DanbooruCatalogUpdateState.Idle ||
                        visibleCatalogState is DanbooruCatalogUpdateState.Failed
                    ) {
                        catalogUpdateManager.startDownload(info)
                    }
                }
            },
            onCancelAll = {
                updateManager.cancelDownload()
                catalogUpdateManager.cancelDownload()
            }
        )
    }
    if (confirmingCrashReportDelete) {
        CrashReportDeleteConfirmationDialog(
            onConfirm = {
                CrashReportManager.deletePendingReport()
                confirmingCrashReportDelete = false
            },
            onDismiss = { confirmingCrashReportDelete = false }
        )
    }
    if (saving) SettingsSavingDialog()

}
