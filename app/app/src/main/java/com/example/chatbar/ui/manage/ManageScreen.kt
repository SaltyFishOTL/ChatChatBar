package com.example.chatbar.ui.manage

import com.example.chatbar.ui.kit.AppIcons

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.BuildConfig
import com.example.chatbar.CharacterEditRoute
import com.example.chatbar.ChatBarApp
import com.example.chatbar.ChatRoute
import com.example.chatbar.FormatCardEditRoute
import com.example.chatbar.ModelEditRoute
import com.example.chatbar.R
import com.example.chatbar.TutorialRoute
import com.example.chatbar.WorldBookEditRoute
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.NovelAiPromptTranslationConsent
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.appearance.ThemeColorHistoryPolicy
import com.example.chatbar.domain.appearance.ThemeColorHsv
import com.example.chatbar.domain.card.CharacterCardImportRequest
import com.example.chatbar.domain.card.CharacterCardPngExportOptions
import com.example.chatbar.domain.card.CharacterCardPngRenderer
import com.example.chatbar.domain.card.SharedImportFocus
import com.example.chatbar.domain.card.SharedImportSection
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.community.CommunityItem
import com.example.chatbar.domain.image.ImageCropOffset
import com.example.chatbar.domain.image.ImageCropSize
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.clampCropOffset
import com.example.chatbar.domain.image.coverDisplaySize
import com.example.chatbar.domain.image.imageCropFractionRect
import com.example.chatbar.domain.moment.MomentDebugExchange
import com.example.chatbar.domain.moment.MomentPolicy
import com.example.chatbar.domain.moment.MomentReliabilityLevel
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
import com.example.chatbar.ui.components.CreateOpenableDocument
import com.example.chatbar.ui.components.RagConfigurationNoticeDialog
import com.example.chatbar.ui.home.CharacterAvatar
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDirtySaveButton
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbFab
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbNumberInput
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbTabs
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.swipeToAdjacentTab
import com.example.chatbar.utils.diagnostics.CrashReportManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DeleteTarget {
    data class Character(val id: String, val name: String) : DeleteTarget
    data class Format(val id: String, val name: String) : DeleteTarget
    data class World(val id: String, val name: String) : DeleteTarget
    data class Model(val id: String, val name: String) : DeleteTarget
    data class Embedding(val id: String, val name: String) : DeleteTarget
}

@Composable
fun ManageScreen(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageViewModel = viewModel(),
    sharedImportFocus: SharedImportFocus? = null,
    onSharedImportFocusConsumed: (Long) -> Unit = {},
    onSettingsGuard: (((() -> Unit) -> Unit)?) -> Unit = {},
    onSettingsActive: (Boolean) -> Unit = {},
    onSwipePastFirstTab: () -> Unit = {}
) {
    val characters by viewModel.characterCards.collectAsState()
    val formats by viewModel.formatCards.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val editorDrafts by viewModel.editorDrafts.collectAsState()
    val models by viewModel.modelConfigs.collectAsState()
    val embeddings by viewModel.embeddingConfigs.collectAsState()
    val embeddingModel by viewModel.embeddingModelConfig.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val player by viewModel.playerSetting.collectAsState()
    val characterPresets by viewModel.characterPresets.collectAsState()
    val formatPresets by viewModel.formatPresets.collectAsState()
    val worldBookPresets by viewModel.worldBookPresets.collectAsState()
    val modelPresets by viewModel.modelPresets.collectAsState()
    val effectiveModels by viewModel.effectiveChatModels.collectAsState()
    val modelErrors by viewModel.modelConfigurationErrors.collectAsState()
    val modelWarnings by viewModel.modelConfigurationWarnings.collectAsState()
    val modelUsable by viewModel.isModelConfigurationUsable.collectAsState()
    val apiTestStatus by viewModel.apiTestStatus.collectAsState()
    val isApiTesting by viewModel.isApiTesting.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val fishAudioConfigured by viewModel.fishAudioConfigured.collectAsState()
    val auxiliaryTextModels by viewModel.auxiliaryTextModels.collectAsState()
    val momentsReliability by viewModel.momentsReliability.collectAsState()
    val momentDebug by viewModel.momentDebug.collectAsState()
    val momentSchedulePreview by viewModel.momentSchedulePreview.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val communityCharacterUpdates by viewModel.communityCharacterUpdates.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settingsSaveRequest by remember { mutableIntStateOf(0) }
    var settingsDirty by remember { mutableStateOf(false) }
    val visibleTabs = listOf(
        0 to "\u89d2\u8272",
        1 to "\u683c\u5f0f",
        2 to "世界书",
        3 to "\u6a21\u578b",
        4 to "\u8bbe\u7f6e"
    )
    val selectedTabIndex = visibleTabs.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    var settingsLeave by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    fun selectTabIndex(index: Int) {
        val target = visibleTabs.getOrNull(index)?.first ?: return
        val action = { tab = target }
        if (tab == 4 && target != 4) settingsLeave?.invoke(action) ?: action() else action()
    }
    androidx.compose.runtime.SideEffect {
        onSettingsGuard(if (tab == 4) settingsLeave else null)
        onSettingsActive(tab == 4)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onSettingsGuard(null); onSettingsActive(false) }
    }
    var editEmbedding by remember { mutableStateOf<EmbeddingConfig?>(null) }
    var showEmbedding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var pendingDraftClear by remember { mutableStateOf<EditorDraft?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showTutorialMenu by remember { mutableStateOf(false) }
    var exportCharacterId by remember { mutableStateOf<String?>(null) }
    var pendingCharacterExport by remember { mutableStateOf<CharacterCard?>(null) }
    var characterExportOptions by remember { mutableStateOf(CharacterCardPngExportOptions()) }
    var exportFormatId by remember { mutableStateOf<String?>(null) }
    var exportWorldBookId by remember { mutableStateOf<String?>(null) }
    var exportWorldBookAsStId by remember { mutableStateOf<String?>(null) }
    var exportModelId by remember { mutableStateOf<String?>(null) }
    var pendingCharacterImport by remember { mutableStateOf<Pair<CharacterCardImportRequest, CharacterCard?>?>(null) }
    var pendingFormatImport by remember { mutableStateOf<Pair<FormatCardPackage, FormatCard?>?>(null) }
    var pendingWorldBookImport by remember { mutableStateOf<Pair<WorldBookPackage, WorldBook?>?>(null) }
    var pendingCharacterChatId by remember { mutableStateOf<String?>(null) }
    var localSharedImportFocus by remember { mutableStateOf<SharedImportFocus?>(null) }
    fun consumeSharedImportFocus() {
        val focus = localSharedImportFocus ?: return
        localSharedImportFocus = null
        onSharedImportFocusConsumed(focus.queueId)
    }

    LaunchedEffect(sharedImportFocus?.queueId) {
        val focus = sharedImportFocus ?: return@LaunchedEffect
        localSharedImportFocus = focus
        tab = when (focus.section) {
            SharedImportSection.CHARACTER -> 0
            SharedImportSection.FORMAT -> 1
            SharedImportSection.WORLD_BOOK -> 2
            SharedImportSection.MODEL -> 3
        }
        message = focus.message
    }

    val exportCharacter = rememberLauncherForActivityResult(CreateOpenableDocument("image/png")) { uri ->
        val id = exportCharacterId.also { exportCharacterId = null }
        if (uri != null && id != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(viewModel.exportCharacterCardPng(id, characterExportOptions))
                }
            }.onSuccess { message = "角色卡 PNG 已导出。" }.onFailure { message = "导出失败：${it.message}" }
        }
    }
    val importCharacter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val data = viewModel.decodeCharacterImport(uri, context)
                val conflict = viewModel.findCharacterImportConflict(data)
                if (conflict == null) {
                    viewModel.importCharacterAsNew(data)
                    message = "角色卡已导入，文档 RAG 等待重建。"
                } else pendingCharacterImport = data to conflict
            }.onFailure { message = "导入失败：${it.message}" }
        }
    }
    val exportFormat = rememberLauncherForActivityResult(CreateOpenableDocument("application/json")) { uri ->
        val id = exportFormatId.also { exportFormatId = null }
        if (uri != null && id != null) scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportFormatCardJson(id).toByteArray()) } }
                .onSuccess { message = "格式卡已导出。" }.onFailure { message = "导出失败：${it.message}" }
        }
    }
    val importFormat = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("文件为空")
                val data = viewModel.decodeFormatImport(raw)
                val conflict = viewModel.findFormatNameConflict(data.name)
                if (conflict == null) viewModel.importFormatAsNew(data) else pendingFormatImport = data to conflict
            }.onFailure { message = "导入失败：${it.message}" }
        }
    }
    val exportWorldBook = rememberLauncherForActivityResult(CreateOpenableDocument("application/json")) { uri ->
        val id = exportWorldBookId.also { exportWorldBookId = null }
        if (uri != null && id != null) scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportWorldBookJson(id).toByteArray()) } }
                .onSuccess { message = "世界书已导出。" }
                .onFailure { message = "导出失败：${it.message}" }
        }
    }
    val exportWorldBookAsSt = rememberLauncherForActivityResult(CreateOpenableDocument("application/json")) { uri ->
        val id = exportWorldBookAsStId.also { exportWorldBookAsStId = null }
        if (uri != null && id != null) scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportWorldBookSillyTavernJson(id).toByteArray()) } }
                .onSuccess { message = "SillyTavern 世界书 JSON 已导出。" }
                .onFailure { message = "导出失败：${it.message}" }
        }
    }
    val importWorldBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("文件为空")
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "导入世界书"
                val data = viewModel.decodeWorldBookImport(raw, name)
                val conflict = viewModel.findWorldBookNameConflict(data.book.name)
                if (conflict == null) viewModel.importWorldBookAsNew(data) else pendingWorldBookImport = data to conflict
            }.onFailure { message = "导入失败：${it.message}" }
        }
    }
    val exportModel = rememberLauncherForActivityResult(CreateOpenableDocument("application/json")) { uri ->
        val id = exportModelId.also { exportModelId = null }
        if (uri != null && id != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportModelTemplateJson(id).toByteArray()) }
            }.onSuccess { message = "模型模板已导出，不包含 API Key。" }.onFailure { message = "导出失败：${it.message}" }
        }
    }
    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("文件为空")
                viewModel.importModelTemplateJson(json)
                viewModel.refreshAll()
            }.onSuccess { message = "模型模板已导入，请编辑并填写 API Key。" }.onFailure { message = "导入失败：${it.message}" }
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshAll() }
    LaunchedEffect(tab) { viewModel.refreshDrafts() }
    LaunchedEffect(tab, settings.momentsEnabled) {
        if (tab == 4) viewModel.refreshMomentSchedulePreview()
    }

    fun latestNewDraftId(type: EditorDraftType): String =
        editorDrafts
            .filter { it.entityType == type && it.isNew }
            .maxByOrNull { it.updatedAt }
            ?.draftSessionId
            ?: UUID.randomUUID().toString()

    fun openDraft(draft: EditorDraft) {
        when (draft.entityType) {
            EditorDraftType.CHARACTER_CARD -> onNavigate(CharacterEditRoute(draft.targetId, draft.draftSessionId))
            EditorDraftType.FORMAT_CARD -> onNavigate(FormatCardEditRoute(draft.targetId, draft.draftSessionId))
            EditorDraftType.WORLD_BOOK -> onNavigate(WorldBookEditRoute(draft.targetId, draft.draftSessionId))
        }
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = "管理",
                actions = {
                    CbIconButton(AppIcons.HelpOutline, "教程", { showTutorialMenu = true })
                    if (tab == 4) CbDirtySaveButton(settingsDirty, { settingsSaveRequest++ })
                    if (tab == 0) CbIconButton(AppIcons.Import, "导入角色卡", { importCharacter.launch(arrayOf("*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 1) CbIconButton(AppIcons.Import, "导入格式卡", { importFormat.launch(arrayOf("*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 2) CbIconButton(AppIcons.Import, "导入世界书", { importWorldBook.launch(arrayOf("*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 3) CbIconButton(AppIcons.Import, "导入模型模板", { importModel.launch(arrayOf("*/*")) }, tint = ChatBarTheme.colors.primary)
                }
            )
        },
        floatingActionButton = {
            if (tab < 4) CbFab(AppIcons.Add, "新建", {
                when (tab) {
                    0 -> onNavigate(CharacterEditRoute(draftId = latestNewDraftId(EditorDraftType.CHARACTER_CARD)))
                    1 -> onNavigate(FormatCardEditRoute(draftId = latestNewDraftId(EditorDraftType.FORMAT_CARD)))
                    2 -> onNavigate(WorldBookEditRoute(draftId = latestNewDraftId(EditorDraftType.WORLD_BOOK)))
                    3 -> onNavigate(ModelEditRoute(null))
                }
            })
        }
    ) {
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            CbTabs(
                visibleTabs.map { it.second },
                selectedTabIndex,
                ::selectTabIndex,
                onSwipePastStart = onSwipePastFirstTab
            )
            Box(
                Modifier
                    .weight(1f)
                    .swipeToAdjacentTab(
                        selectedIndex = selectedTabIndex,
                        enabled = tab != 4,
                        itemCount = visibleTabs.size,
                        onSelected = ::selectTabIndex,
                        onSwipePastStart = onSwipePastFirstTab
                    )
            ) {
                when (tab) {
                    0 -> CharacterTab(characters, editorDrafts.filter { it.entityType == EditorDraftType.CHARACTER_CARD }, characterPresets, viewModel::characterHasUpdate, viewModel::characterCommunityUpdate, modelUsable, modelErrors.firstOrNull(), importProgress, { card ->
                        characterExportOptions = CharacterCardPngExportOptions()
                        pendingCharacterExport = card
                    }, { id ->
                        val card = characters.firstOrNull { it.id == id }
                        if (card?.isCommunityDownload == true) {
                            message = "下载角色卡只读。复制后可编辑本地副本。"
                        } else {
                            onNavigate(CharacterEditRoute(id))
                        }
                    }, { id ->
                        deleteTarget = DeleteTarget.Character(id, characters.firstOrNull { it.id == id }?.name ?: "未命名角色")
                    }, { id -> viewModel.duplicateCharacterCard(id) { onNavigate(CharacterEditRoute(it)) } }, { id ->
                        viewModel.updateCommunityCharacter(id) { result -> message = result }
                    }, { id ->
                        if (modelWarnings.isNotEmpty()) {
                            pendingCharacterChatId = id
                        } else {
                            viewModel.createSessionForCharacter(id) { onNavigate(ChatRoute(it)) }
                        }
                    }, { entry -> scope.launch {
                        val data = viewModel.recoverCharacterPreset(entry)
                        val conflict = viewModel.findCharacterImportConflict(data)
                        if (conflict == null) viewModel.importCharacterAsNew(data) else pendingCharacterImport = data to conflict
                    } }, ::openDraft, { pendingDraftClear = it },
                        focusedId = localSharedImportFocus
                            ?.takeIf { it.section == SharedImportSection.CHARACTER }
                            ?.itemId,
                        onFocusApplied = ::consumeSharedImportFocus
                    )
                    1 -> FormatTab(formats, editorDrafts.filter { it.entityType == EditorDraftType.FORMAT_CARD }, settings.defaultFormatCardId, formatPresets, viewModel::formatHasUpdate, { onNavigate(FormatCardEditRoute(it)) }, { id ->
                        deleteTarget = DeleteTarget.Format(id, formats.firstOrNull { it.id == id }?.name ?: "未命名格式")
                    }, viewModel::setFormatCardDefault, viewModel::duplicateFormatCard, { card ->
                        exportFormatId = card.id
                        exportFormat.launch("${safeName(card.name)}.chatbar-format.json")
                    }, { entry -> scope.launch {
                        val data = viewModel.recoverFormatPreset(entry)
                        val conflict = viewModel.findFormatNameConflict(data.name)
                        if (conflict == null) viewModel.importFormatAsNew(data) else pendingFormatImport = data to conflict
                    } }, ::openDraft, { pendingDraftClear = it },
                        focusedId = localSharedImportFocus
                            ?.takeIf { it.section == SharedImportSection.FORMAT }
                            ?.itemId,
                        onFocusApplied = ::consumeSharedImportFocus
                    )
                    2 -> WorldBookTab(
                        worldBooks, editorDrafts.filter { it.entityType == EditorDraftType.WORLD_BOOK }, worldBookPresets, viewModel::worldBookHasUpdate,
                        onEdit = { onNavigate(WorldBookEditRoute(it)) },
                        onDelete = { id -> deleteTarget = DeleteTarget.World(id, worldBooks.firstOrNull { it.id == id }?.name ?: "未命名世界书") },
                        onDuplicate = viewModel::duplicateWorldBook,
                        onExport = { book ->
                            exportWorldBookId = book.id
                            exportWorldBook.launch("${safeName(book.name)}.chatbar-world-book.json")
                        },
                        onExportSt = { book ->
                            exportWorldBookAsStId = book.id
                            exportWorldBookAsSt.launch("${safeName(book.name)}.sillytavern-world.json")
                        },
                        onRecover = { entry -> scope.launch {
                            val data = viewModel.recoverWorldBookPreset(entry)
                            val conflict = viewModel.findWorldBookNameConflict(data.book.name)
                            if (conflict == null) viewModel.importWorldBookAsNew(data) else pendingWorldBookImport = data to conflict
                        } },
                        onOpenDraft = ::openDraft,
                        onDiscardDraft = { pendingDraftClear = it },
                        focusedId = localSharedImportFocus
                            ?.takeIf { it.section == SharedImportSection.WORLD_BOOK }
                            ?.itemId,
                        onFocusApplied = ::consumeSharedImportFocus
                    )
                    3 -> ModelsTab(
                        models, settings.defaultModelId, settings.defaultImageModelId, modelPresets, embeddingModel,
                        onEditModel = { onNavigate(ModelEditRoute(it)) },
                        onExportModel = { model ->
                            exportModelId = model.id
                            exportModel.launch("${safeName(model.displayName)}.chatbar-model-template.json")
                        },
                        onSetDefaultModel = viewModel::setDefaultModel,
                        onSetDefaultImageModel = viewModel::setDefaultImageModel,
                        onDeleteModel = { id -> deleteTarget = DeleteTarget.Model(id, models.firstOrNull { it.id == id }?.displayName ?: "未命名模型") },
                        onDuplicateModel = { id -> viewModel.duplicateModelConfig(id) { onNavigate(ModelEditRoute(it)) } },
                        onRecoverPresetModels = { viewModel.importPresetModelCatalog { error -> message = error ?: "内置模型已导入。" } },
                        onEditEmbedding = { editEmbedding = it; showEmbedding = true },
                        onDeleteEmbedding = { id -> deleteTarget = DeleteTarget.Embedding(id, embeddingModel?.displayName ?: "未命名向量模型") },
                        onAddEmbedding = { editEmbedding = null; showEmbedding = true },
                        focusedId = localSharedImportFocus
                            ?.takeIf { it.section == SharedImportSection.MODEL }
                            ?.itemId,
                        onFocusApplied = ::consumeSharedImportFocus
                    )
                    4 -> GlobalSettingsScreen(
                        settingsSaveRequest, true, { tab = 3 }, { settingsLeave = it }, settings, player, characters, models, effectiveModels, auxiliaryTextModels, formats, modelErrors, apiTestStatus, novelAiConfigured, fishAudioConfigured, momentsReliability, momentDebug, momentSchedulePreview,
                        viewModel::saveSettingsDraft,
                        viewModel::updateThemeMode,
                        viewModel::updateThemeColor,
                        viewModel::updateBubbleFontScale,
                        { settingsDirty = it },
                        viewModel::testSiliconFlowApi,
                        viewModel::saveNovelAiToken,
                        viewModel::clearNovelAiToken,
                        viewModel::saveFishAudioApiKey,
                        viewModel::clearFishAudioApiKey,
                        viewModel::refreshMomentsReliability,
                        viewModel::openMomentsAutoStartSettings,
                        viewModel::openMomentsBatterySettings,
                        viewModel::openMomentsNotificationSettings,
                        viewModel::confirmMomentsAutoStart,
                        viewModel::refreshMomentSchedulePreview,
                        viewModel::generateDebugMoment,
                        viewModel::clearMomentDebug,
                        isApiTesting,
                        viewModel::cancelApiTest
                    )
                }
            }
        }
    }

    pendingCharacterChatId?.let { characterId ->
        RagConfigurationNoticeDialog(
            onDismissRequest = { pendingCharacterChatId = null },
            onContinue = {
                pendingCharacterChatId = null
                viewModel.createSessionForCharacter(characterId) { onNavigate(ChatRoute(it)) }
            }
        )
    }

    if (showEmbedding) EmbeddingDialog(editEmbedding, { showEmbedding = false }) {
        viewModel.saveEmbeddingConfig(it)
        showEmbedding = false
    }
    deleteTarget?.let { target ->
        val (title, body) = when (target) {
            is DeleteTarget.Character -> "删除角色卡" to "确定删除“${target.name}”？相关 RAG 数据也会清理。"
            is DeleteTarget.Format -> "删除格式卡" to "确定删除“${target.name}”？"
            is DeleteTarget.World -> "删除世界书" to "确定删除“${target.name}”？被角色或会话引用时会阻止删除。"
            is DeleteTarget.Model -> "删除模型" to "确定删除“${target.name}”？"
            is DeleteTarget.Embedding -> "删除向量模型" to "确定删除“${target.name}”？"
        }
        CbDialog(
            onDismissRequest = { deleteTarget = null },
            title = title,
            dismiss = { CbButton("取消", { deleteTarget = null }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("删除", {
                    when (target) {
                        is DeleteTarget.Character -> viewModel.deleteCharacterCard(target.id)
                        is DeleteTarget.Format -> viewModel.deleteFormatCard(target.id)
                        is DeleteTarget.World -> viewModel.deleteWorldBook(target.id) { error ->
                            if (error != null) message = error
                        }
                        is DeleteTarget.Model -> viewModel.deleteModelConfig(target.id)
                        is DeleteTarget.Embedding -> viewModel.deleteEmbeddingConfig(target.id)
                    }
                    deleteTarget = null
                }, variant = ButtonVariant.Destructive)
            }
        ) { CbText(body, color = ChatBarTheme.colors.mutedForeground) }
    }
    pendingDraftClear?.let { draft ->
        CbDialog(
            onDismissRequest = { pendingDraftClear = null },
            title = "清除草稿",
            dismiss = { CbButton("取消", { pendingDraftClear = null }, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton("清除", {
                    viewModel.discardEditorDraft(draft)
                    pendingDraftClear = null
                }, variant = ButtonVariant.Destructive)
            }
        ) {
            CbText(
                "确定清除“${draft.title}”的草稿？正式内容不会受影响。",
                color = ChatBarTheme.colors.mutedForeground
            )
        }
    }
    pendingCharacterImport?.let { (data, existing) ->
        val readOnlyConflict = existing?.isCommunityDownload == true
        CbDialog(
            onDismissRequest = { pendingCharacterImport = null },
            title = "角色卡名称冲突",
            dismiss = { CbButton("取消", { pendingCharacterImport = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText(
                if (readOnlyConflict) {
                    "已存在社区下载角色卡“${existing.name}”。下载卡只读，不能被本地导入覆盖；可创建自动编号的新卡。"
                } else {
                    "已存在“${existing?.name}”。选择覆盖原卡并保留历史关联，或创建自动编号的新卡。"
                },
                color = ChatBarTheme.colors.mutedForeground
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!readOnlyConflict) {
                    CbButton("覆盖现有卡", { scope.launch { viewModel.overwriteCharacter(existing!!.id, data); pendingCharacterImport = null } }, variant = ButtonVariant.Destructive)
                }
                CbButton("创建新卡", { scope.launch { viewModel.importCharacterAsNew(data); pendingCharacterImport = null } })
            }
        }
    }
    pendingFormatImport?.let { (data, existing) ->
        CbDialog(
            onDismissRequest = { pendingFormatImport = null },
            title = "格式卡名称冲突",
            dismiss = { CbButton("取消", { pendingFormatImport = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("已存在“${existing?.name}”。覆盖会保留当前默认状态；也可创建自动编号的新卡。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖现有卡", { scope.launch { viewModel.overwriteFormat(existing!!.id, data); pendingFormatImport = null } }, variant = ButtonVariant.Destructive)
                CbButton("创建新卡", { scope.launch { viewModel.importFormatAsNew(data); pendingFormatImport = null } })
            }
        }
    }
    pendingWorldBookImport?.let { (data, existing) ->
        CbDialog(
            onDismissRequest = { pendingWorldBookImport = null },
            title = "世界书名称冲突",
            dismiss = { CbButton("取消", { pendingWorldBookImport = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("已存在“${existing?.name}”。覆盖会保留世界书 ID 与现有绑定；也可创建自动编号的新书。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖现有书", { scope.launch { viewModel.overwriteWorldBook(existing!!.id, data); pendingWorldBookImport = null } }, variant = ButtonVariant.Destructive)
                CbButton("创建新书", { scope.launch { viewModel.importWorldBookAsNew(data); pendingWorldBookImport = null } })
            }
        }
    }
    pendingCharacterExport?.let { card ->
        CharacterPngExportDialog(
            card = card,
            options = characterExportOptions,
            onOptionsChange = { characterExportOptions = it },
            onDismiss = { pendingCharacterExport = null },
            onExport = {
                exportCharacterId = card.id
                pendingCharacterExport = null
                exportCharacter.launch("${safeName(card.name)}.chatbar-character.png")
            }
        )
    }
    if (showTutorialMenu) {
        CbDialog(
            onDismissRequest = { showTutorialMenu = false },
            title = "选择教程",
            dismiss = { CbButton("取消", { showTutorialMenu = false }, variant = ButtonVariant.Ghost) }
        ) {
            CbButton(
                "基础教程",
                { showTutorialMenu = false; onNavigate(TutorialRoute()) },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary
            )
            Spacer(Modifier.height(8.dp))
            CbButton(
                "进阶教程",
                { showTutorialMenu = false; onNavigate(TutorialRoute(advanced = true)) },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Outline
            )
        }
    }
    message?.let {
        CbDialog(onDismissRequest = { message = null }, title = "导入 / 导出", confirm = { CbButton("知道了", { message = null }) }) {
            CbText(it, color = ChatBarTheme.colors.mutedForeground)
        }
    }
}

@Composable
private fun CharacterPngExportDialog(
    card: CharacterCard,
    options: CharacterCardPngExportOptions,
    onOptionsChange: (CharacterCardPngExportOptions) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit
) {
    val normalized = options.normalized()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importingCover by remember { mutableStateOf(false) }
    val chooseCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importingCover = true
            var file: File? = null
            try {
                file = withContext(Dispatchers.IO) {
                    File.createTempFile("character-export-", ".image", context.cacheDir).also { target ->
                        try {
                            context.contentResolver.openInputStream(uri).use { input ->
                                requireNotNull(input) { "无法读取所选图片" }
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(target.absolutePath, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是有效图片" }
                        } catch (error: Throwable) {
                            target.delete()
                            throw error
                        }
                    }
                }
                onOptionsChange(normalized.copy(coverImagePath = file.absolutePath, cropCenterX = 0.5f, cropCenterY = 0.5f, cropZoom = 1f))
            } catch (error: Exception) {
                file?.delete()
                Toast.makeText(context, error.message ?: "读取图片失败", Toast.LENGTH_LONG).show()
            } finally {
                importingCover = false
            }
        }
    }
    val backgroundFile = (normalized.coverImagePath ?: card.chatBackground)?.let(::File)?.takeIf(File::isFile)
    var showBackgroundCrop by remember(card.id) { mutableStateOf(false) }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "导出角色卡 PNG",
        modifier = Modifier.heightIn(max = 780.dp),
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("导出 PNG", onExport, enabled = !importingCover) }
    ) {
        Column(
            Modifier
                .heightIn(max = 590.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CharacterPngPreview(card, normalized)
            CbButton("从文件选择导出图片", { chooseCover.launch(arrayOf("image/*")) }, enabled = !importingCover, variant = ButtonVariant.Outline)
            if (normalized.coverImagePath != null) {
                CbButton("使用角色卡背景图", { onOptionsChange(normalized.copy(coverImagePath = null, cropCenterX = 0.5f, cropCenterY = 0.5f, cropZoom = 1f)) }, variant = ButtonVariant.Ghost)
            }
            if (backgroundFile == null) {
                CbText("未设置默认聊天背景，当前预览使用品牌底色。", color = ChatBarTheme.colors.warning, style = ChatBarTheme.typography.caption)
            } else {
                CbButton("调整背景裁剪", { showBackgroundCrop = true }, variant = ButtonVariant.Outline)
            }
            CbField("导出尺寸") {
                val sizes = listOf(1024, 1536, 2048)
                CbSelect(normalized.sizePx, sizes, { "${it} x ${it}" }, { onOptionsChange(normalized.copy(sizePx = it)) })
            }
            ExportSliderField(
                label = "渐变高度",
                valueText = "${(normalized.gradientHeight * 100).roundToInt()}%",
                value = normalized.gradientHeight,
                range = 0.25f..0.68f,
                onChange = { onOptionsChange(normalized.copy(gradientHeight = it)) }
            )
            ExportSliderField(
                label = "渐变强度",
                valueText = "${(normalized.gradientStrength * 100).roundToInt()}%",
                value = normalized.gradientStrength,
                range = 0.45f..0.9f,
                onChange = { onOptionsChange(normalized.copy(gradientStrength = it)) }
            )
            ExportSliderField(
                label = "图标大小",
                valueText = "${(normalized.logoScale * 100).roundToInt()}%",
                value = normalized.logoScale,
                range = 0.07f..0.14f,
                onChange = { onOptionsChange(normalized.copy(logoScale = it)) }
            )
            ExportSliderField(
                label = "标题大小",
                valueText = "${(normalized.titleScale * 100).roundToInt()}%",
                value = normalized.titleScale,
                range = 0.04f..0.08f,
                onChange = { onOptionsChange(normalized.copy(titleScale = it)) }
            )
        }
    }

    if (showBackgroundCrop && backgroundFile != null) {
        CharacterPngBackgroundCropDialog(
            imageFile = backgroundFile,
            options = normalized,
            onDismiss = { showBackgroundCrop = false },
            onConfirm = { cropCenterX, cropCenterY, cropZoom ->
                onOptionsChange(
                    normalized.copy(
                        cropCenterX = cropCenterX,
                        cropCenterY = cropCenterY,
                        cropZoom = cropZoom
                    )
                )
                showBackgroundCrop = false
            }
        )
    }
}

@Composable
private fun CharacterPngPreview(card: CharacterCard, options: CharacterCardPngExportOptions) {
    val backgroundFile = remember(card.chatBackground, options.coverImagePath) {
        (options.coverImagePath ?: card.chatBackground)?.let(::File)?.takeIf(File::isFile)
    }
    val density = LocalDensity.current
    var sourceSize by remember(backgroundFile) { mutableStateOf<ImageCropSize?>(null) }
    var frameSize by remember(backgroundFile) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(backgroundFile) {
        sourceSize = backgroundFile?.let { loadExportImageSize(it) }
    }
    val gradientStart = (1f - options.gradientHeight).coerceIn(0f, 1f)
    val titleFontFamily = remember { FontFamily(Font(R.font.xiaolang_tianqiong)) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(ChatBarTheme.colors.surfaceSubtle)
    ) {
        val previewSize = maxWidth
        val logoSize = previewSize * options.logoScale
        val titleSize = with(density) { (previewSize.toPx() * options.titleScale).toSp() }
        val margin = previewSize * 0.055f
        val gap = previewSize * 0.024f
        Box(Modifier.fillMaxSize().onSizeChanged { frameSize = it }) {
            val size = sourceSize
            if (backgroundFile != null && size != null && frameSize.width > 0 && frameSize.height > 0) {
                val display = coverDisplaySize(
                    sourceWidth = size.width,
                    sourceHeight = size.height,
                    frameWidth = frameSize.width.toFloat(),
                    frameHeight = frameSize.height.toFloat()
                )
                val drawnWidth = display.width * options.cropZoom
                val drawnHeight = display.height * options.cropZoom
                AsyncImage(
                    model = backgroundFile,
                    contentDescription = "默认聊天背景",
                    modifier = Modifier
                        .requiredSize(
                            width = with(density) { drawnWidth.toDp() },
                            height = with(density) { drawnHeight.toDp() }
                        )
                        .graphicsLayer {
                            translationX = drawnWidth * (0.5f - options.cropCenterX)
                            translationY = drawnHeight * (0.5f - options.cropCenterY)
                        },
                    contentScale = ContentScale.FillBounds
                )
            } else if (backgroundFile != null) {
                AsyncImage(
                    model = backgroundFile,
                    contentDescription = "默认聊天背景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF12181D), Color(0xFF2D6058), Color(0xFF0A0E12))
                            )
                        )
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            gradientStart to Color.Transparent,
                            1f to Color.Black.copy(alpha = options.gradientStrength)
                        )
                    )
                )
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(margin),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(logoSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = "ChatChatBar",
                    modifier = Modifier.fillMaxSize(0.86f)
                )
            }
            Spacer(Modifier.width(gap))
            CbText(
                card.name.ifBlank { "未命名角色" },
                modifier = Modifier.weight(1f),
                color = Color.White,
                style = ChatBarTheme.typography.title.copy(
                    fontSize = titleSize,
                    lineHeight = (titleSize.value * 1.3f).sp,
                    fontFamily = titleFontFamily
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CharacterPngBackgroundCropDialog(
    imageFile: File,
    options: CharacterCardPngExportOptions,
    onDismiss: () -> Unit,
    onConfirm: (cropCenterX: Float, cropCenterY: Float, cropZoom: Float) -> Unit
) {
    val density = LocalDensity.current
    var sourceSize by remember(imageFile) { mutableStateOf<ImageCropSize?>(null) }
    var frameSize by remember(imageFile) { mutableStateOf(IntSize.Zero) }
    var scale by remember(imageFile) { mutableFloatStateOf(options.cropZoom) }
    var offset by remember(imageFile) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(imageFile) {
        sourceSize = loadExportImageSize(imageFile)
    }
    LaunchedEffect(sourceSize, frameSize.width, frameSize.height, options.cropCenterX, options.cropCenterY, options.cropZoom) {
        val size = sourceSize ?: return@LaunchedEffect
        if (frameSize.width <= 0 || frameSize.height <= 0) return@LaunchedEffect
        val display = coverDisplaySize(
            sourceWidth = size.width,
            sourceHeight = size.height,
            frameWidth = frameSize.width.toFloat(),
            frameHeight = frameSize.height.toFloat()
        )
        scale = options.cropZoom
        offset = Offset(
            x = display.width * scale * (0.5f - options.cropCenterX),
            y = display.height * scale * (0.5f - options.cropCenterY)
        )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val size = sourceSize ?: return@rememberTransformableState
        if (frameSize.width <= 0 || frameSize.height <= 0) return@rememberTransformableState
        val nextScale = (scale * zoomChange).coerceIn(1f, 6f)
        val clamped = clampCropOffset(
            offset = ImageCropOffset(offset.x + panChange.x, offset.y + panChange.y),
            sourceWidth = size.width,
            sourceHeight = size.height,
            frameWidth = frameSize.width.toFloat(),
            frameHeight = frameSize.height.toFloat(),
            userScale = nextScale
        )
        scale = nextScale
        offset = Offset(clamped.x, clamped.y)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbIconButton(AppIcons.Close, "取消裁剪", onDismiss, tint = ChatBarTheme.colors.foreground)
                CbText("调整导出背景", style = ChatBarTheme.typography.heading)
                CbIconButton(
                    AppIcons.Check,
                    "确认裁剪",
                    {
                        val size = sourceSize ?: return@CbIconButton
                        if (frameSize.width <= 0 || frameSize.height <= 0) return@CbIconButton
                        val crop = imageCropFractionRect(
                            sourceWidth = size.width,
                            sourceHeight = size.height,
                            frameWidth = frameSize.width.toFloat(),
                            frameHeight = frameSize.height.toFloat(),
                            userScale = scale,
                            offset = ImageCropOffset(offset.x, offset.y)
                        )
                        onConfirm((crop.left + crop.right) / 2f, (crop.top + crop.bottom) / 2f, scale)
                    },
                    enabled = sourceSize != null && frameSize.width > 0 && frameSize.height > 0,
                    tint = ChatBarTheme.colors.primary
                )
            }
            CbText("拖动调整位置，双指缩放。", color = ChatBarTheme.colors.mutedForeground)
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val frameSide = minOf(maxWidth, maxHeight)
                Box(
                    Modifier
                        .size(frameSide)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .transformable(transformState)
                        .onSizeChanged { frameSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val size = sourceSize
                    if (size != null && frameSize.width > 0 && frameSize.height > 0) {
                        val display = coverDisplaySize(
                            sourceWidth = size.width,
                            sourceHeight = size.height,
                            frameWidth = frameSize.width.toFloat(),
                            frameHeight = frameSize.height.toFloat()
                        )
                        AsyncImage(
                            model = imageFile,
                            contentDescription = "导出背景裁剪预览",
                            modifier = Modifier
                                .requiredSize(
                                    width = with(density) { (display.width * scale).toDp() },
                                    height = with(density) { (display.height * scale).toDp() }
                                )
                                .graphicsLayer {
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        CbSpinner()
                    }
                }
            }
        }
    }
}

private suspend fun loadExportImageSize(file: File): ImageCropSize? = withContext(Dispatchers.IO) {
    runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "图片无法读取" }
        ImageCropSize(options.outWidth.toFloat(), options.outHeight.toFloat())
    }.getOrNull()
}

@Composable
private fun ExportSliderField(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.label)
            CbText(valueText, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        CbSlider(value, onChange, range, contentDescription = label)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterTab(
    cards: List<CharacterCard>,
    drafts: List<EditorDraft>,
    presets: List<PresetEntry>,
    hasUpdate: (CharacterCard) -> Boolean,
    communityUpdate: (CharacterCard) -> CommunityItem?,
    modelUsable: Boolean,
    modelError: String?,
    importProgress: String?,
    onExport: (CharacterCard) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onCommunityUpdate: (String) -> Unit,
    onStart: (String) -> Unit,
    onRecover: (PresetEntry) -> Unit,
    onOpenDraft: (EditorDraft) -> Unit,
    onDiscardDraft: (EditorDraft) -> Unit,
    focusedId: String? = null,
    onFocusApplied: () -> Unit = {}
) {
    var menuCard by remember { mutableStateOf<CharacterCard?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val newDrafts = drafts.filter { it.isNew }
    LaunchedEffect(focusedId, cards.map { it.id }, importProgress, modelUsable, showPresets, presets.size, newDrafts.size) {
        val cardIndex = cards.indexOfFirst { it.id == focusedId }
        if (focusedId != null && cardIndex >= 0) {
            val prefix = (if (importProgress != null) 1 else 0) +
                (if (!modelUsable) 1 else 0) + newDrafts.size + 1 +
                (if (showPresets) presets.size else 0)
            listState.animateScrollToItem(prefix + cardIndex)
            onFocusApplied()
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        importProgress?.let { msg ->
            item {
                CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.primary.copy(alpha = 0.08f)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CbSpinner()
                        Spacer(Modifier.width(8.dp))
                        CbText(msg, color = ChatBarTheme.colors.primary)
                    }
                }
            }
        }
        if (!modelUsable) item {
            CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.destructive.copy(alpha = 0.08f)) {
                CbText(
                    modelError ?: "模型配置不可用，无法开始聊天。",
                    Modifier.padding(12.dp),
                    color = ChatBarTheme.colors.destructive
                )
            }
        }
        items(newDrafts, key = { "draft-${it.id}" }) { draft ->
            EntityRow(
                title = draft.title,
                subtitle = "未完成新建草稿 · ${draftTimeLabel(draft.updatedAt)}",
                badge = "草稿",
                onClick = { onOpenDraft(draft) },
                actions = {
                    CbIconButton(AppIcons.DeleteSweep, "清除草稿", { onDiscardDraft(draft) }, tint = ChatBarTheme.colors.destructive)
                }
            )
        }
        item {
            CbButton(if (showPresets) "收起预制角色" else "恢复预制角色", { showPresets = !showPresets }, variant = ButtonVariant.Outline)
        }
            if (showPresets) items(presets, key = { it.presetKey }) { preset ->
                val hasCard = cards.any { it.sourcePresetKey == preset.presetKey }
                val update = hasCard && cards.any { it.sourcePresetKey == preset.presetKey && (it.sourcePresetVersion ?: 0) < preset.version }
                EntityRow(preset.displayName, "预制版本 ${preset.version}", badge = when { update -> "有更新"; !hasCard -> "可恢复"; else -> null }, actions = {
                    CbButton("导入", { onRecover(preset) }, variant = ButtonVariant.Secondary)
                })
            }
            items(cards, key = { it.id }) { card ->
                val remoteUpdate = communityUpdate(card)
                val draft = drafts.firstOrNull { it.targetId == card.id }
                val isDownloaded = card.isCommunityDownload
                val editClick: (() -> Unit)? = if (isDownloaded) null else ({ onEdit(card.id) })
                EntityRow(
                    title = card.name,
                    subtitle = if (card.editMode.name == "FREEFORM") "自由人物设定 · ${card.customDocuments.size} 份文档" else "${card.characters.size} 个人物 · ${card.customDocuments.size} 份文档",
                    badge = when {
                        draft != null -> "有草稿"
                        remoteUpdate != null -> "可更新"
                        hasUpdate(card) -> "有更新"
                        isDownloaded -> "社区"
                        else -> null
                    },
                leading = { CharacterAvatar(card.avatar, Modifier.size(42.dp)) },
                onClick = editClick,
                onLongClick = { menuCard = card },
                actions = {
                    if (draft != null) {
                        CbIconButton(
                            AppIcons.DeleteSweep,
                            "清除草稿",
                            { onDiscardDraft(draft) },
                            tint = ChatBarTheme.colors.destructive
                        )
                    }
                    if (remoteUpdate != null) {
                        CbIconButton(
                            AppIcons.Refresh,
                            "更新",
                            { onCommunityUpdate(card.id) },
                            tint = ChatBarTheme.colors.primary
                        )
                    }
                    CbIconButton(
                        AppIcons.PlayArrow,
                        "开始聊天",
                        { onStart(card.id) },
                        enabled = modelUsable,
                        tint = ChatBarTheme.colors.primary
                    )
                }
            )
        }
    }
    menuCard?.let { card ->
        val actions = buildList {
            if (!card.isCommunityDownload) add("编辑" to { onEdit(card.id) })
            communityUpdate(card)?.let { add("更新" to { onCommunityUpdate(card.id) }) }
            add("复制" to { onDuplicate(card.id) })
            add("导出" to { onExport(card) })
            add("删除" to { onDelete(card.id) })
        }
        CardActionDialog(card.name, { menuCard = null }, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormatTab(
    cards: List<FormatCard>,
    drafts: List<EditorDraft>,
    defaultFormatId: String?,
    presets: List<PresetEntry>,
    hasUpdate: (FormatCard) -> Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDefault: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (FormatCard) -> Unit,
    onRecover: (PresetEntry) -> Unit,
    onOpenDraft: (EditorDraft) -> Unit,
    onDiscardDraft: (EditorDraft) -> Unit,
    focusedId: String? = null,
    onFocusApplied: () -> Unit = {}
) {
    var menuCard by remember { mutableStateOf<FormatCard?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val effectiveDefaultFormatId = defaultFormatId
    val listState = rememberLazyListState()
    val newDrafts = drafts.filter { it.isNew }
    LaunchedEffect(focusedId, cards.map { it.id }, showPresets, presets.size, newDrafts.size) {
        val cardIndex = cards.indexOfFirst { it.id == focusedId }
        if (focusedId != null && cardIndex >= 0) {
            val prefix = newDrafts.size + 1 + if (showPresets) presets.size else 0
            listState.animateScrollToItem(prefix + cardIndex)
            onFocusApplied()
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(newDrafts, key = { "draft-${it.id}" }) { draft ->
            EntityRow(
                title = draft.title,
                subtitle = "未完成新建草稿 · ${draftTimeLabel(draft.updatedAt)}",
                badge = "草稿",
                onClick = { onOpenDraft(draft) },
                actions = {
                    CbIconButton(AppIcons.DeleteSweep, "清除草稿", { onDiscardDraft(draft) }, tint = ChatBarTheme.colors.destructive)
                }
            )
        }
        item { CbButton(if (showPresets) "收起预制格式" else "恢复预制格式", { showPresets = !showPresets }, variant = ButtonVariant.Outline) }
        if (showPresets) items(presets, key = { it.presetKey }) { preset ->
            val hasCard = cards.any { it.sourcePresetKey == preset.presetKey }
            val update = hasCard && cards.any { it.sourcePresetKey == preset.presetKey && (it.sourcePresetVersion ?: 0) < preset.version }
                EntityRow(preset.displayName, "预制版本 ${preset.version}", badge = when { update -> "有更新"; !hasCard -> "可恢复"; else -> null }, actions = {
                CbButton("导入", { onRecover(preset) }, variant = ButtonVariant.Secondary)
            })
        }
        items(cards, key = { it.id }) { card ->
            val isDefault = card.id == effectiveDefaultFormatId
            val draft = drafts.firstOrNull { it.targetId == card.id }
            EntityRow(
                title = card.name,
                subtitle = card.content.take(80),
                badge = when { draft != null -> "有草稿"; hasUpdate(card) -> "有更新"; isDefault -> "默认"; else -> null },
                onClick = { onEdit(card.id) },
                onLongClick = { menuCard = card },
                actions = {
                    if (draft != null) {
                        CbIconButton(
                            AppIcons.DeleteSweep,
                            "清除草稿",
                            { onDiscardDraft(draft) },
                            tint = ChatBarTheme.colors.destructive
                        )
                    }
                    CbButton(if (isDefault) "当前默认" else "设为默认", { onDefault(card.id) }, variant = ButtonVariant.Secondary)
                }
            )
        }
    }
    menuCard?.let { card ->
        CardActionDialog(card.name, { menuCard = null }, listOf(
            "编辑" to { onEdit(card.id) },
            "复制" to { onDuplicate(card.id) },
            "导出" to { onExport(card) },
            "删除" to { onDelete(card.id) }
        ))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorldBookTab(
    books: List<WorldBook>,
    drafts: List<EditorDraft>,
    presets: List<PresetEntry>,
    hasUpdate: (WorldBook) -> Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (WorldBook) -> Unit,
    onExportSt: (WorldBook) -> Unit,
    onRecover: (PresetEntry) -> Unit,
    onOpenDraft: (EditorDraft) -> Unit,
    onDiscardDraft: (EditorDraft) -> Unit,
    focusedId: String? = null,
    onFocusApplied: () -> Unit = {}
) {
    var menuBook by remember { mutableStateOf<WorldBook?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val newDrafts = drafts.filter { it.isNew }
    LaunchedEffect(focusedId, books.map { it.id }, showPresets, presets.size, newDrafts.size) {
        val bookIndex = books.indexOfFirst { it.id == focusedId }
        if (focusedId != null && bookIndex >= 0) {
            val prefix = newDrafts.size + 1 + if (showPresets) presets.size else 0
            listState.animateScrollToItem(prefix + bookIndex)
            onFocusApplied()
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(newDrafts, key = { "draft-${it.id}" }) { draft ->
            EntityRow(
                title = draft.title,
                subtitle = "未完成新建草稿 · ${draftTimeLabel(draft.updatedAt)}",
                badge = "草稿",
                onClick = { onOpenDraft(draft) },
                actions = {
                    CbIconButton(AppIcons.DeleteSweep, "清除草稿", { onDiscardDraft(draft) }, tint = ChatBarTheme.colors.destructive)
                }
            )
        }
        item { CbButton(if (showPresets) "收起预制世界书" else "恢复预制世界书", { showPresets = !showPresets }, variant = ButtonVariant.Outline) }
        if (showPresets) items(presets, key = { it.presetKey }) { preset ->
            val hasBook = books.any { it.sourcePresetKey == preset.presetKey }
            val update = hasBook && books.any { it.sourcePresetKey == preset.presetKey && (it.sourcePresetVersion ?: 0) < preset.version }
            EntityRow(
                preset.displayName,
                "预制版本 ${preset.version}",
                badge = when {
                    update -> "有更新"
                    !hasBook -> "可恢复"
                    else -> null
                },
                actions = { CbButton("导入", { onRecover(preset) }, variant = ButtonVariant.Secondary) }
            )
        }
        items(books, key = { it.id }) { book ->
            val enabledCount = book.entries.count { it.enabled }
            val draft = drafts.firstOrNull { it.targetId == book.id }
            EntityRow(
                title = book.name,
                subtitle = "${book.entries.size} 条目 · 启用 $enabledCount · 扫描 ${book.scanDepth} 条",
                badge = when {
                    draft != null -> "有草稿"
                    hasUpdate(book) -> "有更新"
                    book.sourcePresetKey != null -> "内置"
                    else -> null
                },
                onClick = { onEdit(book.id) },
                onLongClick = { menuBook = book },
                actions = {
                    if (draft != null) {
                        CbIconButton(
                            AppIcons.DeleteSweep,
                            "清除草稿",
                            { onDiscardDraft(draft) },
                            tint = ChatBarTheme.colors.destructive
                        )
                    }
                    CbIconButton(AppIcons.Edit, "编辑", { onEdit(book.id) }, tint = ChatBarTheme.colors.primary)
                    CbIconButton(AppIcons.Delete, "删除", { onDelete(book.id) }, tint = ChatBarTheme.colors.destructive)
                }
            )
        }
    }
    menuBook?.let { book ->
        CardActionDialog(book.name, { menuBook = null }, listOf(
            "编辑" to { onEdit(book.id) },
            "复制" to { onDuplicate(book.id) },
            "导出" to { onExport(book) },
            "导出为 SillyTavern JSON" to { onExportSt(book) },
            "删除" to { onDelete(book.id) }
        ))
    }
}

@Composable
private fun CardActionDialog(title: String, onDismiss: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    CbDialog(onDismissRequest = onDismiss, title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { (label, action) ->
                CbButton(label, { onDismiss(); action() }, modifier = Modifier.fillMaxWidth(), variant = if (label == "删除") ButtonVariant.Destructive else ButtonVariant.Outline)
            }
        }
    }
}

@Composable
private fun ModelsTab(
    models: List<ModelConfig>,
    defaultModelId: String?,
    defaultImageModelId: String?,
    presets: List<PresetEntry>,
    embedding: EmbeddingConfig?,
    onEditModel: (String) -> Unit,
    onExportModel: (ModelConfig) -> Unit,
    onSetDefaultModel: (String) -> Unit,
    onSetDefaultImageModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDuplicateModel: (String) -> Unit,
    onRecoverPresetModels: () -> Unit,
    onEditEmbedding: (EmbeddingConfig) -> Unit,
    onDeleteEmbedding: (String) -> Unit,
    onAddEmbedding: () -> Unit,
    focusedId: String? = null,
    onFocusApplied: () -> Unit = {}
) {
    var menuModel by remember { mutableStateOf<ModelConfig?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val effectiveDefaultModelId = defaultModelId ?: models.firstOrNull()?.id
    val effectiveDefaultImageModelId = defaultImageModelId ?: effectiveDefaultModelId
    val listState = rememberLazyListState()
    LaunchedEffect(focusedId, models.map { it.id }, showPresets, presets.size) {
        val modelIndex = models.indexOfFirst { it.id == focusedId }
        if (focusedId != null && modelIndex >= 0) {
            val prefix = 2 + if (showPresets) presets.size else 0
            listState.animateScrollToItem(prefix + modelIndex)
            onFocusApplied()
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { CbButton(if (showPresets) "收起内置模型" else "恢复内置模型", { showPresets = !showPresets }, variant = ButtonVariant.Outline) }
        if (showPresets) items(presets, key = { it.presetKey }) { preset ->
            val importedCount = models.count { it.sourcePresetKey != null }
            val hasUpdate = models.any { it.sourcePresetKey != null && (it.sourcePresetVersion ?: 0) < preset.version }
            EntityRow(
                preset.displayName,
                "预置版本 ${preset.version} · 已导入 $importedCount 个",
                badge = when {
                    importedCount == 0 -> "可恢复"
                    hasUpdate -> "有更新"
                    else -> null
                },
                actions = { CbButton("导入", onRecoverPresetModels, variant = ButtonVariant.Secondary) }
            )
        }
        item { SectionTitle("对话模型") }
        items(models, key = { it.id }) { model ->
            val isDefault = model.id == effectiveDefaultModelId
            val isDefaultImage = model.id == effectiveDefaultImageModelId
            val badge = buildList {
                if (isDefault) add("默认对话")
                if (isDefaultImage) add("默认生图")
                if (model.sourcePresetKey != null) add("内置")
            }.joinToString(" / ").takeIf(String::isNotBlank)
            EntityRow(model.displayName, "${model.modelName} · ${model.baseUrl}", badge = badge, onClick = { onEditModel(model.id) }, onLongClick = { menuModel = model }, actions = {
                CbIconButton(AppIcons.Star, if (isDefault) "当前默认对话" else "设为默认对话", { onSetDefaultModel(model.id) }, enabled = !isDefault, tint = if (isDefault) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground)
                CbIconButton(AppIcons.Image, if (isDefaultImage) "当前默认生图" else "设为默认生图", { onSetDefaultImageModel(model.id) }, enabled = !isDefaultImage, tint = if (isDefaultImage) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground)
                CbIconButton(AppIcons.Edit, "编辑", { onEditModel(model.id) }, tint = ChatBarTheme.colors.primary)
                CbIconButton(AppIcons.Delete, "删除", { onDeleteModel(model.id) }, tint = ChatBarTheme.colors.destructive)
            })
        }
        item { CbDivider(); HeaderAction("向量模型", if (embedding == null) "添加" else "编辑", { if (embedding == null) onAddEmbedding() else onEditEmbedding(embedding) }) }
        item {
            if (embedding == null) CbText("尚未配置向量模型，RAG 无法建立索引。", color = ChatBarTheme.colors.mutedForeground)
            else EntityRow(embedding.displayName, "${embedding.modelName} · ${embedding.dimensions} 维", actions = {
                CbIconButton(AppIcons.Edit, "编辑", { onEditEmbedding(embedding) }, tint = ChatBarTheme.colors.primary)
                CbIconButton(AppIcons.Delete, "删除", { onDeleteEmbedding(embedding.id) }, tint = ChatBarTheme.colors.destructive)
            })
        }
    }
    menuModel?.let { model ->
        CardActionDialog(model.displayName, { menuModel = null }, listOf(
            "编辑" to { onEditModel(model.id) },
            "复制" to { onDuplicateModel(model.id) },
            "导出" to { onExportModel(model) },
            "删除" to { onDeleteModel(model.id) }
        ))
    }
}

@Composable
internal fun MomentSchedulePreviewBlock(
    state: MomentSchedulePreviewUiState,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(ChatBarTheme.colors.surfaceSubtle)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                CbText("未来 12 小时安排", style = ChatBarTheme.typography.label)
                CbText(
                    "按已保存设置和当前 pending 任务生成预览。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            CbButton(
                text = if (state.isLoading) "刷新中" else "刷新安排",
                onClick = onRefresh,
                enabled = !state.isLoading,
                variant = ButtonVariant.Ghost
            )
        }
        if (state.isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbSpinner(Modifier.size(18.dp))
                CbText("正在读取排程", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
        }
        state.message?.let { message ->
            CbText(message, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        state.items.forEach { item ->
            MomentSchedulePreviewRow(item, state.generatedAt)
        }
        if (state.generatedAt > 0L) {
            CbText(
                "读取于 ${formatMomentScheduleTime(state.generatedAt)}",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
    }
}

@Composable
internal fun MomentSchedulePreviewRow(
    item: MomentSchedulePreviewItem,
    nowMs: Long
) {
    val statusColor = if (item.statusLabel == "待生成") {
        ChatBarTheme.colors.primary
    } else {
        ChatBarTheme.colors.warning
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.width(72.dp)) {
            CbText(formatMomentScheduleTime(item.scheduledAt), style = ChatBarTheme.typography.caption)
            CbText(
                formatMomentScheduleDistance(item.scheduledAt, nowMs),
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
        }
        Column(Modifier.weight(1f)) {
            CbText(item.cardName, style = ChatBarTheme.typography.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CbText(item.sessionTitle, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        CbText(item.statusLabel, color = statusColor, style = ChatBarTheme.typography.caption)
    }
}

internal fun formatMomentScheduleTime(timeMs: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timeMs))

internal fun formatMomentScheduleDistance(timeMs: Long, nowMs: Long): String {
    val diff = (timeMs - nowMs).coerceAtLeast(0L)
    val hours = diff / (60L * 60L * 1000L)
    val minutes = (diff % (60L * 60L * 1000L)) / (60L * 1000L)
    return when {
        hours > 0L -> "${hours}小时${minutes}分后"
        minutes > 0L -> "${minutes}分钟后"
        else -> "即将到达"
    }
}

internal fun formatMomentFrequency(minHours: Int, maxHours: Int): String =
    if (minHours == maxHours) "每 ${minHours} 小时" else "每 ${minHours}-${maxHours} 小时"

@Composable
internal fun MomentDebugExchangeBlock(exchange: MomentDebugExchange) {
    CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.surfaceElevated, elevation = ChatBarElevation.low) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CbText(exchange.title, style = ChatBarTheme.typography.label)
            MomentDebugTextBlock("输入", exchange.input)
            MomentDebugTextBlock("输出", exchange.output)
        }
    }
}

@Composable
internal fun MomentDebugTextBlock(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        SelectionContainer {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(ChatBarTheme.colors.background)
                    .padding(10.dp)
            ) {
                CbText(
                    text.ifBlank { "(空)" },
                    color = ChatBarTheme.colors.foreground,
                    style = ChatBarTheme.typography.caption.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun <T> EntityList(data: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(data, key = key) { row(it) }
    }
}

@Composable
private fun EntityRow(
    title: String,
    subtitle: String,
    badge: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    actions: @Composable () -> Unit
) {
    CbSurface(
        Modifier.fillMaxWidth().then(
            if (onClick != null || onLongClick != null) {
                Modifier.combinedClickable(
                    onClick = onClick ?: {},
                    onLongClick = onLongClick
                )
            } else Modifier
        ),
        color = ChatBarTheme.colors.surfaceElevated,
        elevation = ChatBarElevation.low
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke(); if (leading != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CbText(title, modifier = Modifier.weight(1f, fill = false), style = ChatBarTheme.typography.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    badge?.let { Spacer(Modifier.width(8.dp)); CbText(it, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.caption) }
                }
                CbText(subtitle, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption, maxLines = 2)
            }
            Row { actions() }
        }
    }
}

@Composable
private fun HeaderAction(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title); CbButton(action, onClick, variant = ButtonVariant.Ghost)
    }
}

@Composable
private fun SectionTitle(title: String) = CbText(title, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    CbSurface(
        Modifier.fillMaxWidth(),
        color = ChatBarTheme.colors.surfaceElevated,
        elevation = ChatBarElevation.low
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
internal fun SliderField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    com.example.chatbar.ui.kit.SettingsDetails(label.substringBefore("："), label.substringAfter("：", "点击调整")) {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.label)
        CbSlider(value, onChange, range, steps = steps, contentDescription = label)
    }
}

@Composable
internal fun RequiredSelect(label: String, selectedId: String?, options: List<IdOption>, onSelected: (String?) -> Unit) {
    val selected = options.firstOrNull { it.id == selectedId }
        ?: IdOption(selectedId, if (selectedId == null) "未指定 · 按现有默认规则选择" else "原选择不可用")
    val available = if (selected in options) options else listOf(selected) + options
    CbField(label) {
        CbSelect(selected, available, { it.label }, { onSelected(it.id) })
    }
}


@Composable
internal fun OptionalSelect(
    label: String,
    selectedId: String?,
    options: List<IdOption>,
    onSelected: (String?) -> Unit,
    noneLabel: String = "不设置"
) {
    val all = listOf(IdOption(null, noneLabel)) +
        (if (selectedId != null && options.none { it.id == selectedId }) listOf(IdOption(selectedId, "原选择不可用")) else emptyList()) + options
    CbField(label) {
        CbSelect(all.firstOrNull { it.id == selectedId }, all, { it.label }, { onSelected(it.id) })
    }
}

@Composable
private fun FormatDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var content by remember { mutableStateOf("") }
    CbDialog(onDismiss, "新建格式卡", dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }, confirm = {
        CbButton("保存", { onSave(name, content) }, enabled = name.isNotBlank() && content.isNotBlank())
    }) {
        CbField("格式名称") { CbInput(name, { name = it }) }; Spacer(Modifier.height(12.dp))
        CbField("Prompt 格式要求") { CbInput(content, { content = it }, singleLine = false, minLines = 4) }
    }
}

@Composable
private fun EmbeddingDialog(original: EmbeddingConfig?, onDismiss: () -> Unit, onSave: (EmbeddingConfig) -> Unit) {
    var name by remember { mutableStateOf(original?.displayName ?: "") }; var url by remember { mutableStateOf(original?.baseUrl ?: "") }
    var key by remember { mutableStateOf(original?.apiKey ?: "") }; var model by remember { mutableStateOf(original?.modelName ?: "") }
    var dimensions by remember { mutableStateOf((original?.dimensions ?: 1536).toString()) }
    CbDialog(onDismiss, if (original == null) "添加向量模型" else "编辑向量模型", modifier = Modifier.heightIn(max = 760.dp), dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }, confirm = {
        CbButton("保存", { onSave(EmbeddingConfig(original?.id ?: UUID.randomUUID().toString(), name, url, key, model, dimensions.toIntOrNull() ?: 1536)) }, enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank())
    }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("显示名称") { CbInput(name, { name = it }) }; CbField("Base URL") { CbInput(url, { url = it }) }
            CbField("API Key") { CbInput(key, { key = it }, secure = true) }
            CbField("模型名称") { CbInput(model, { model = it }) }; CbField("向量维度") { CbNumberInput(dimensions, { dimensions = it }) }
        }
    }
}

internal data class IdOption(val id: String?, val label: String)
private fun safeName(value: String) = value.replace(Regex("[\\\\/:*?\"<>|]"), "_")
internal fun String.toModeIndex() = when (uppercase()) { "OFF" -> 0; "LIGHT" -> 1; "STRONG" -> 3; else -> 2 }
internal fun Int.modeValue() = when (coerceIn(0, 3)) { 0 -> "OFF"; 1 -> "LIGHT"; 3 -> "STRONG"; else -> "STANDARD" }
internal fun Int.modeLabel() = when (coerceIn(0, 3)) { 0 -> "关闭"; 1 -> "轻量"; 3 -> "强"; else -> "标准" }
private fun draftTimeLabel(timeMs: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMs))
