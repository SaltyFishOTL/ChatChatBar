package com.example.chatbar.ui.manage

import com.example.chatbar.ui.kit.AppIcons

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.CharacterEditRoute
import com.example.chatbar.ChatRoute
import com.example.chatbar.FormatCardEditRoute
import com.example.chatbar.ModelEditRoute
import com.example.chatbar.TutorialRoute
import com.example.chatbar.WorldBookEditRoute
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EmbeddingConfig
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelConfigurationMode
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.PlayerSetting
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.ThemeMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.card.CharacterCardImportRequest
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.WorldBookPackage
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
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbTabs
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarElevation
import com.example.chatbar.ui.kit.ChatBarTheme
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private sealed interface DeleteTarget {
    data class Character(val id: String, val name: String) : DeleteTarget
    data class Format(val id: String, val name: String) : DeleteTarget
    data class World(val id: String, val name: String) : DeleteTarget
    data class Model(val id: String, val name: String) : DeleteTarget
    data class Embedding(val id: String, val name: String) : DeleteTarget
    data class Retrieval(val name: String) : DeleteTarget
}

@Composable
fun ManageScreen(
    onNavigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManageViewModel = viewModel(),
    sharedUri: android.net.Uri? = null
) {
    val characters by viewModel.characterCards.collectAsState()
    val formats by viewModel.formatCards.collectAsState()
    val worldBooks by viewModel.worldBooks.collectAsState()
    val models by viewModel.modelConfigs.collectAsState()
    val embeddings by viewModel.embeddingConfigs.collectAsState()
    val embeddingModel by viewModel.embeddingModelConfig.collectAsState()
    val retrievalModel by viewModel.retrievalModelConfig.collectAsState()
    val settings by viewModel.appSettings.collectAsState()
    val player by viewModel.playerSetting.collectAsState()
    val characterPresets by viewModel.characterPresets.collectAsState()
    val formatPresets by viewModel.formatPresets.collectAsState()
    val worldBookPresets by viewModel.worldBookPresets.collectAsState()
    val modelPresets by viewModel.modelPresets.collectAsState()
    val effectiveModels by viewModel.effectiveChatModels.collectAsState()
    val modelErrors by viewModel.modelConfigurationErrors.collectAsState()
    val modelUsable by viewModel.isModelConfigurationUsable.collectAsState()
    val apiTestStatus by viewModel.apiTestStatus.collectAsState()
    val novelAiConfigured by viewModel.novelAiConfigured.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
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
    var addFormat by remember { mutableStateOf(false) }
    var editEmbedding by remember { mutableStateOf<EmbeddingConfig?>(null) }
    var showEmbedding by remember { mutableStateOf(false) }
    var showRetrieval by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportCharacterId by remember { mutableStateOf<String?>(null) }
    var exportFormatId by remember { mutableStateOf<String?>(null) }
    var exportWorldBookId by remember { mutableStateOf<String?>(null) }
    var exportWorldBookAsStId by remember { mutableStateOf<String?>(null) }
    var exportModelId by remember { mutableStateOf<String?>(null) }
    var pendingCharacterImport by remember { mutableStateOf<Pair<CharacterCardImportRequest, CharacterCard?>?>(null) }
    var pendingFormatImport by remember { mutableStateOf<Pair<FormatCardPackage, FormatCard?>?>(null) }
    var pendingWorldBookImport by remember { mutableStateOf<Pair<WorldBookPackage, WorldBook?>?>(null) }

    LaunchedEffect(sharedUri) {
        sharedUri?.let { uri ->
            scope.launch {
                runCatching {
                    val data = viewModel.decodeCharacterImport(uri, context)
                    val conflict = viewModel.findCharacterImportConflict(data)
                    if (conflict == null) {
                        viewModel.importCharacterAsNew(data)
                        message = "角色卡已导入，文档 RAG 待重建。"
                    } else {
                        pendingCharacterImport = data to conflict
                        tab = 0
                    }
                }.onFailure { message = "导入失败：${it.message}" }
            }
        }
    }

    val exportCharacter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportCharacterId.also { exportCharacterId = null }
        if (uri != null && id != null) scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportCharacterCardJson(id).toByteArray()) }
            }.onSuccess { message = "角色卡已导出。" }.onFailure { message = "导出失败：${it.message}" }
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
    val exportFormat = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
    val exportWorldBook = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val id = exportWorldBookId.also { exportWorldBookId = null }
        if (uri != null && id != null) scope.launch {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(viewModel.exportWorldBookJson(id).toByteArray()) } }
                .onSuccess { message = "世界书已导出。" }
                .onFailure { message = "导出失败：${it.message}" }
        }
    }
    val exportWorldBookAsSt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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
    val exportModel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
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

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = "管理",
                actions = {
                    CbIconButton(AppIcons.HelpOutline, "基础教程", { onNavigate(TutorialRoute()) })
                    if (tab == 4) CbDirtySaveButton(settingsDirty, { settingsSaveRequest++ })
                    if (tab == 0) CbIconButton(AppIcons.UploadFile, "导入角色卡", { importCharacter.launch(arrayOf("application/json", "image/png", "text/*", "*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 1) CbIconButton(AppIcons.UploadFile, "导入格式卡", { importFormat.launch(arrayOf("application/json", "text/*", "*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 2) CbIconButton(AppIcons.UploadFile, "导入世界书", { importWorldBook.launch(arrayOf("application/json", "text/*", "*/*")) }, tint = ChatBarTheme.colors.primary)
                    if (tab == 3) CbIconButton(AppIcons.UploadFile, "导入模型模板", { importModel.launch(arrayOf("application/json", "text/*", "*/*")) }, tint = ChatBarTheme.colors.primary)
                }
            )
        },
        floatingActionButton = {
            if (tab < 4) CbFab(AppIcons.Add, "新建", {
                when (tab) {
                    0 -> onNavigate(CharacterEditRoute(null))
                    1 -> addFormat = true
                    2 -> onNavigate(WorldBookEditRoute(null))
                    3 -> onNavigate(ModelEditRoute(null))
                }
            })
        }
    ) {
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            CbTabs(visibleTabs.map { it.second }, visibleTabs.indexOfFirst { it.first == tab }.coerceAtLeast(0), { newIdx ->
                val newTabId = visibleTabs[newIdx].first
                tab = newTabId
            })
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> CharacterTab(characters, characterPresets, viewModel::characterHasUpdate, modelUsable, modelErrors.firstOrNull(), importProgress, { card ->
                        exportCharacterId = card.id
                        exportCharacter.launch("${safeName(card.name)}.chatbar-character.json")
                    }, { onNavigate(CharacterEditRoute(it)) }, { id ->
                        deleteTarget = DeleteTarget.Character(id, characters.firstOrNull { it.id == id }?.name ?: "未命名角色")
                    }, { id -> viewModel.duplicateCharacterCard(id) { onNavigate(CharacterEditRoute(it)) } }, { id ->
                        viewModel.createSessionForCharacter(id) { onNavigate(ChatRoute(it)) }
                    }, { entry -> scope.launch {
                        val data = viewModel.recoverCharacterPreset(entry)
                        val conflict = viewModel.findCharacterImportConflict(data)
                        if (conflict == null) viewModel.importCharacterAsNew(data) else pendingCharacterImport = data to conflict
                    } })
                    1 -> FormatTab(formats, settings.defaultFormatCardId, formatPresets, viewModel::formatHasUpdate, { onNavigate(FormatCardEditRoute(it)) }, { id ->
                        deleteTarget = DeleteTarget.Format(id, formats.firstOrNull { it.id == id }?.name ?: "未命名格式")
                    }, viewModel::setFormatCardDefault, viewModel::duplicateFormatCard, { card ->
                        exportFormatId = card.id
                        exportFormat.launch("${safeName(card.name)}.chatbar-format.json")
                    }, { entry -> scope.launch {
                        val data = viewModel.recoverFormatPreset(entry)
                        val conflict = viewModel.findFormatNameConflict(data.name)
                        if (conflict == null) viewModel.importFormatAsNew(data) else pendingFormatImport = data to conflict
                    } })
                    2 -> WorldBookTab(
                        worldBooks, worldBookPresets, viewModel::worldBookHasUpdate,
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
                        } }
                    )
                    3 -> ModelsTab(
                        models, settings.defaultModelId, modelPresets, embeddingModel, retrievalModel,
                        onEditModel = { onNavigate(ModelEditRoute(it)) },
                        onExportModel = { model ->
                            exportModelId = model.id
                            exportModel.launch("${safeName(model.displayName)}.chatbar-model-template.json")
                        },
                        onSetDefaultModel = viewModel::setDefaultModel,
                        onDeleteModel = { id -> deleteTarget = DeleteTarget.Model(id, models.firstOrNull { it.id == id }?.displayName ?: "未命名模型") },
                        onDuplicateModel = { id -> viewModel.duplicateModelConfig(id) { onNavigate(ModelEditRoute(it)) } },
                        onRecoverPresetModels = { viewModel.importPresetModelCatalog { error -> message = error ?: "内置模型已导入。" } },
                        onEditEmbedding = { editEmbedding = it; showEmbedding = true },
                        onDeleteEmbedding = { id -> deleteTarget = DeleteTarget.Embedding(id, embeddingModel?.displayName ?: "未命名向量模型") },
                        onAddEmbedding = { editEmbedding = null; showEmbedding = true },
                        onEditRetrieval = { showRetrieval = true },
                        onDeleteRetrieval = { deleteTarget = DeleteTarget.Retrieval(retrievalModel?.displayName ?: "检索规划模型") }
                    )
                    4 -> SettingsTab(
                        settingsSaveRequest, settings, player, models, effectiveModels, formats, modelErrors, apiTestStatus, novelAiConfigured,
                        viewModel::updateAppSettings,
                        viewModel::updatePlayerSetting,
                        viewModel::updateThemeMode,
                        viewModel::updateBubbleFontScale,
                        { settingsDirty = it },
                        viewModel::testSiliconFlowApi,
                        viewModel::saveNovelAiToken,
                        viewModel::clearNovelAiToken
                    )
                }
            }
        }
    }

    if (addFormat) FormatDialog({ addFormat = false }) { name, content ->
        viewModel.saveFormatCard(name, content) { error ->
            if (error == null) addFormat = false else message = error
        }
    }
    if (showEmbedding) EmbeddingDialog(editEmbedding, { showEmbedding = false }) {
        viewModel.saveEmbeddingConfig(it)
        showEmbedding = false
    }
    if (showRetrieval) RetrievalDialog(retrievalModel, { showRetrieval = false }) {
        viewModel.saveRetrievalModelConfig(it)
        showRetrieval = false
    }
    deleteTarget?.let { target ->
        val (title, body) = when (target) {
            is DeleteTarget.Character -> "删除角色卡" to "确定删除“${target.name}”？相关 RAG 数据也会清理。"
            is DeleteTarget.Format -> "删除格式卡" to "确定删除“${target.name}”？"
            is DeleteTarget.World -> "删除世界书" to "确定删除“${target.name}”？被角色或会话引用时会阻止删除。"
            is DeleteTarget.Model -> "删除模型" to "确定删除“${target.name}”？"
            is DeleteTarget.Embedding -> "删除向量模型" to "确定删除“${target.name}”？"
            is DeleteTarget.Retrieval -> "删除检索模型" to "确定删除“${target.name}”？"
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
                        is DeleteTarget.Retrieval -> viewModel.deleteRetrievalModelConfig()
                    }
                    deleteTarget = null
                }, variant = ButtonVariant.Destructive)
            }
        ) { CbText(body, color = ChatBarTheme.colors.mutedForeground) }
    }
    pendingCharacterImport?.let { (data, existing) ->
        CbDialog(
            onDismissRequest = { pendingCharacterImport = null },
            title = "角色卡名称冲突",
            dismiss = { CbButton("取消", { pendingCharacterImport = null }, variant = ButtonVariant.Ghost) }
        ) {
            CbText("已存在“${existing?.name}”。选择覆盖原卡并保留历史关联，或创建自动编号的新卡。", color = ChatBarTheme.colors.mutedForeground)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("覆盖现有卡", { scope.launch { viewModel.overwriteCharacter(existing!!.id, data); pendingCharacterImport = null } }, variant = ButtonVariant.Destructive)
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
    message?.let {
        CbDialog(onDismissRequest = { message = null }, title = "导入 / 导出", confirm = { CbButton("知道了", { message = null }) }) {
            CbText(it, color = ChatBarTheme.colors.mutedForeground)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CharacterTab(
    cards: List<CharacterCard>,
    presets: List<PresetEntry>,
    hasUpdate: (CharacterCard) -> Boolean,
    modelUsable: Boolean,
    modelError: String?,
    importProgress: String?,
    onExport: (CharacterCard) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onStart: (String) -> Unit,
    onRecover: (PresetEntry) -> Unit
) {
    var menuCard by remember { mutableStateOf<CharacterCard?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                EntityRow(
                    title = card.name,
                    subtitle = if (card.editMode.name == "FREEFORM") "自由人物设定 · ${card.customDocuments.size} 份文档" else "${card.characters.size} 个人物 · ${card.customDocuments.size} 份文档",
                    badge = if (hasUpdate(card)) "有更新" else null,
                leading = { CharacterAvatar(card.avatar, Modifier.size(42.dp)) },
                onClick = { onEdit(card.id) },
                onLongClick = { menuCard = card },
                actions = {
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
private fun FormatTab(
    cards: List<FormatCard>,
    defaultFormatId: String?,
    presets: List<PresetEntry>,
    hasUpdate: (FormatCard) -> Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDefault: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (FormatCard) -> Unit,
    onRecover: (PresetEntry) -> Unit
) {
    var menuCard by remember { mutableStateOf<FormatCard?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val effectiveDefaultFormatId = defaultFormatId
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            EntityRow(
                title = card.name,
                subtitle = card.content.take(80),
                badge = when { hasUpdate(card) -> "有更新"; isDefault -> "默认"; else -> null },
                onClick = { onEdit(card.id) },
                onLongClick = { menuCard = card },
                actions = { CbButton(if (isDefault) "当前默认" else "设为默认", { onDefault(card.id) }, variant = ButtonVariant.Secondary) }
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
    presets: List<PresetEntry>,
    hasUpdate: (WorldBook) -> Boolean,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onExport: (WorldBook) -> Unit,
    onExportSt: (WorldBook) -> Unit,
    onRecover: (PresetEntry) -> Unit
) {
    var menuBook by remember { mutableStateOf<WorldBook?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            EntityRow(
                title = book.name,
                subtitle = "${book.entries.size} 条目 · 启用 $enabledCount · 扫描 ${book.scanDepth} 条",
                badge = when {
                    hasUpdate(book) -> "有更新"
                    book.sourcePresetKey != null -> "内置"
                    else -> null
                },
                onClick = { onEdit(book.id) },
                onLongClick = { menuBook = book },
                actions = {
                    CbIconButton(AppIcons.Download, "导出", { onExport(book) })
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
    presets: List<PresetEntry>,
    embedding: EmbeddingConfig?,
    retrieval: ModelConfig?,
    onEditModel: (String) -> Unit,
    onExportModel: (ModelConfig) -> Unit,
    onSetDefaultModel: (String) -> Unit,
    onDeleteModel: (String) -> Unit,
    onDuplicateModel: (String) -> Unit,
    onRecoverPresetModels: () -> Unit,
    onEditEmbedding: (EmbeddingConfig) -> Unit,
    onDeleteEmbedding: (String) -> Unit,
    onAddEmbedding: () -> Unit,
    onEditRetrieval: () -> Unit,
    onDeleteRetrieval: () -> Unit
) {
    var menuModel by remember { mutableStateOf<ModelConfig?>(null) }
    var showPresets by remember { mutableStateOf(false) }
    val effectiveDefaultModelId = defaultModelId ?: models.firstOrNull()?.id
    LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            EntityRow(model.displayName, "${model.modelName} · ${model.baseUrl}", badge = when {
                isDefault -> "默认"
                model.sourcePresetKey != null -> "内置"
                else -> null
            }, onClick = { onEditModel(model.id) }, onLongClick = { menuModel = model }, actions = {
                CbIconButton(AppIcons.Star, if (isDefault) "当前默认" else "设为默认", { onSetDefaultModel(model.id) }, enabled = !isDefault, tint = if (isDefault) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground)
                CbIconButton(AppIcons.Download, "导出", { onExportModel(model) })
                CbIconButton(AppIcons.Edit, "编辑", { onEditModel(model.id) }, tint = ChatBarTheme.colors.primary)
                CbIconButton(AppIcons.Delete, "删除", { onDeleteModel(model.id) }, tint = ChatBarTheme.colors.destructive)
            })
        }
        item { CbDivider(); HeaderAction("检索规划模型", if (retrieval == null) "添加" else "编辑", onEditRetrieval) }
        item {
            if (retrieval == null) CbText("未配置时回退到当前对话模型。建议配置快速、便宜的小模型。", color = ChatBarTheme.colors.mutedForeground)
            else EntityRow(retrieval.displayName, "${retrieval.modelName} · max=${retrieval.maxOutputTokens ?: "默认"}", actions = {
                CbIconButton(AppIcons.Edit, "编辑", onEditRetrieval, tint = ChatBarTheme.colors.primary)
                CbIconButton(AppIcons.Delete, "删除", onDeleteRetrieval, tint = ChatBarTheme.colors.destructive)
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
private fun SettingsTab(
    saveRequest: Int,
    settings: AppSettings,
    player: PlayerSetting,
    customModels: List<ModelConfig>,
    effectiveModels: List<ModelConfig>,
    formats: List<FormatCard>,
    modelErrors: List<String>,
    apiTestStatus: String?,
    novelAiConfigured: Boolean,
    onSaveSettings: (AppSettings) -> Unit,
    onSavePlayer: (String, String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onBubbleFontScale: (Float) -> Unit,
    onDirtyChange: (Boolean) -> Unit,
    onTestApiKey: (String) -> Unit,
    onSaveNovelAiToken: (String) -> Unit,
    onClearNovelAiToken: () -> Unit
) {
    var playerName by remember { mutableStateOf(player.playerName) }
    var persona by remember { mutableStateOf(player.globalPersona) }
    var modelId by remember { mutableStateOf(settings.defaultModelId) }
    var siliconFlowApiKey by remember { mutableStateOf(settings.siliconFlowApiKey) }
    var novelAiToken by remember { mutableStateOf("") }
    var formatId by remember { mutableStateOf(settings.defaultFormatCardId) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }
    var contextSize by remember { mutableFloatStateOf(settings.defaultContextWindowSize.coerceIn(5, 50).toFloat()) }
    var customContextSize by remember { mutableStateOf(if (settings.defaultContextWindowSize > 50) settings.defaultContextWindowSize.toString() else "") }
    var bubbleFontScale by remember { mutableFloatStateOf(settings.chatBubbleFontScale) }
    var memoryTopK by remember { mutableFloatStateOf(settings.memoryRagTopK.toFloat()) }
    var memoryThreshold by remember { mutableFloatStateOf(settings.memoryRagSimilarityThreshold) }
    var docTopK by remember { mutableFloatStateOf(settings.docRagTopK.toFloat()) }
    var docThreshold by remember { mutableFloatStateOf(settings.docRagSimilarityThreshold) }
    var ragMode by remember { mutableFloatStateOf(settings.ragInjectionMode.toModeIndex().toFloat()) }
    LaunchedEffect(
        player.playerName,
        player.globalPersona,
        settings.defaultModelId,
        settings.presetDefaultModelKey,
        settings.siliconFlowApiKey,
        settings.defaultFormatCardId,
        settings.themeMode,
        settings.defaultContextWindowSize,
        settings.memoryRagTopK,
        settings.memoryRagSimilarityThreshold,
        settings.docRagTopK,
        settings.docRagSimilarityThreshold,
        settings.ragInjectionMode,
        settings.chatBubbleFontScale
    ) {
        playerName = player.playerName; persona = player.globalPersona
        modelId = settings.defaultModelId ?: settings.presetDefaultModelKey?.let { "preset:$it" }
        siliconFlowApiKey = settings.siliconFlowApiKey; formatId = settings.defaultFormatCardId
        themeMode = settings.themeMode
        contextSize = settings.defaultContextWindowSize.toFloat(); memoryTopK = settings.memoryRagTopK.toFloat()
        memoryThreshold = settings.memoryRagSimilarityThreshold; docTopK = settings.docRagTopK.toFloat()
        docThreshold = settings.docRagSimilarityThreshold; ragMode = settings.ragInjectionMode.toModeIndex().toFloat(); bubbleFontScale = settings.chatBubbleFontScale
    }
    val effectiveDefaultModelId = modelId ?: effectiveModels.firstOrNull()?.id ?: customModels.firstOrNull { it.selectableForChat }?.id
    val draftContextWindowSize = if (contextSize.toInt() >= 50 && customContextSize.isNotBlank()) {
        customContextSize.toIntOrNull() ?: 50
    } else {
        contextSize.toInt()
    }
    val draftSettings = settings.copy(
        defaultModelId = effectiveDefaultModelId,
        presetDefaultModelKey = null,
        siliconFlowApiKey = siliconFlowApiKey.trim(),
        defaultEmbeddingId = null,
        defaultFormatCardId = formatId,
        memoryRagTopK = memoryTopK.toInt(),
        memoryRagSimilarityThreshold = memoryThreshold,
        docRagTopK = docTopK.toInt(),
        docRagSimilarityThreshold = docThreshold,
        ragInjectionMode = ragMode.roundToInt().modeValue(),
        defaultContextWindowSize = draftContextWindowSize
    )
    val savedSettingsComparable = settings.copy(defaultEmbeddingId = null)
    val settingsDirty = draftSettings != savedSettingsComparable ||
        playerName != player.playerName ||
        persona != player.globalPersona
    LaunchedEffect(settingsDirty) { onDirtyChange(settingsDirty) }
    LaunchedEffect(saveRequest) {
        if (saveRequest > 0) {
            onSavePlayer(playerName, persona)
            onSaveSettings(
                draftSettings.copy(
                    modelConfigurationMode = ModelConfigurationMode.CUSTOM_API,
                    chatBubbleFontScale = bubbleFontScale,
                    themeMode = themeMode
                )
            )
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection("模型与 API") {
            CbText(
                "模型 API Key 留空时使用全局默认 API Key。",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            modelErrors.forEach { CbText(it, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption) }
            CbField("全局默认 API Key") {
                CbInput(siliconFlowApiKey, { siliconFlowApiKey = it }, placeholder = "sk-...", visualTransformation = PasswordVisualTransformation())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("测试连接", { onTestApiKey(siliconFlowApiKey) }, variant = ButtonVariant.Outline)
            }
            apiTestStatus?.let { CbText(it, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption) }
            CbDivider()
            val modelOptions = effectiveModels.map { IdOption(it.id, it.displayName) }
            RequiredSelect("默认对话模型", effectiveDefaultModelId, modelOptions, { modelId = it })
            OptionalSelect("默认格式卡", formatId, formats.map { IdOption(it.id, it.name) }, { formatId = it })
            SliderField("保留上下文消息：${contextSize.toInt()} 条", contextSize, 5f..50f, 45) { contextSize = it }
            if (contextSize.toInt() >= 50) {
                CbField("自定义上下文上限") {
                    CbInput(customContextSize, { newValue ->
                        customContextSize = newValue.filter { it.isDigit() }
                    }, placeholder = "50")
                }
            }
        }
        SettingsSection("RAG 检索") {
            SliderField("注入强度：${ragMode.roundToInt().modeLabel()}", ragMode, 0f..3f, 2) { ragMode = it }
            SliderField("文档召回数量：${docTopK.toInt()}", docTopK, 1f..15f, 13) { docTopK = it }
            SliderField("文档相似度：${"%.2f".format(docThreshold)}", docThreshold, 0.3f..0.95f) { docThreshold = it }
            SliderField("记忆召回数量：${memoryTopK.toInt()}", memoryTopK, 1f..15f, 13) { memoryTopK = it }
            SliderField("记忆相似度：${"%.2f".format(memoryThreshold)}", memoryThreshold, 0.3f..0.95f) { memoryThreshold = it }
        }
        SettingsSection("外观") {
            SliderField("气泡字号：${"%.1f".format(bubbleFontScale)}x", bubbleFontScale, 0.5f..1.5f, 9) {
                bubbleFontScale = it
                onBubbleFontScale(it)
            }
            ThemeMode.entries.forEach { appearanceMode ->
                CbButton(
                    text = when (appearanceMode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    onClick = { themeMode = appearanceMode; onThemeMode(appearanceMode) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (themeMode == appearanceMode) ButtonVariant.Default else ButtonVariant.Outline
                )
            }
        }
        SettingsSection("玩家") {
            CbField("玩家名称") { CbInput(playerName, { playerName = it }, placeholder = "旅行者") }
            CbField("玩家全局设定") { CbInput(persona, { persona = it }, singleLine = false, minLines = 3) }
        }
        SettingsSection("NovelAI 生图") {
            CbText(
                if (novelAiConfigured) "Persistent API Token 已配置" else "未配置；聊天生图按钮不会显示",
                color = if (novelAiConfigured) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            CbField(
                "Persistent API Token",
                description = "Token 使用 Android Keystore 加密保存，不写入应用设置 JSON。"
            ) {
                CbInput(
                    novelAiToken,
                    { novelAiToken = it },
                    placeholder = if (novelAiConfigured) "输入新 Token 以替换" else "粘贴 NovelAI Persistent API Token",
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton("保存", {
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
        Spacer(Modifier.height(80.dp))
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun SliderField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        CbText(label, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.label)
        CbSlider(value, onChange, range, steps = steps, contentDescription = label)
    }
}

@Composable
private fun RequiredSelect(label: String, selectedId: String?, options: List<IdOption>, onSelected: (String?) -> Unit) {
    val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull()
    CbField(label) {
        CbSelect(selected, options, { it.label }, { onSelected(it.id) })
    }
}


@Composable
private fun OptionalSelect(label: String, selectedId: String?, options: List<IdOption>, onSelected: (String?) -> Unit) {
    val all = listOf(IdOption(null, "不设置")) + options
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
            CbField("API Key") { CbInput(key, { key = it }, visualTransformation = PasswordVisualTransformation()) }
            CbField("模型名称") { CbInput(model, { model = it }) }; CbField("向量维度") { CbInput(dimensions, { dimensions = it }) }
        }
    }
}

@Composable
private fun RetrievalDialog(original: ModelConfig?, onDismiss: () -> Unit, onSave: (ModelConfig) -> Unit) {
    var name by remember { mutableStateOf(original?.displayName ?: "检索规划模型") }; var url by remember { mutableStateOf(original?.baseUrl ?: "") }
    var key by remember { mutableStateOf(original?.apiKey ?: "") }; var model by remember { mutableStateOf(original?.modelName ?: "") }
    var maxTokens by remember { mutableStateOf((original?.maxOutputTokens ?: 128).toString()) }
    CbDialog(onDismiss, "检索规划模型", modifier = Modifier.heightIn(max = 760.dp), dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) }, confirm = {
        CbButton("保存", { onSave(ModelConfig(original?.id ?: UUID.randomUUID().toString(), name, url, key, model, templateType = original?.templateType ?: ModelTemplate.OPENAI, customParams = original?.customParams ?: emptyMap(), reasoningEffort = original?.reasoningEffort, enableThinking = original?.enableThinking, maxOutputTokens = maxTokens.toIntOrNull(), createdAt = original?.createdAt ?: System.currentTimeMillis())) }, enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank())
    }) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("显示名称") { CbInput(name, { name = it }) }; CbField("Base URL") { CbInput(url, { url = it }) }
            CbField("API Key") { CbInput(key, { key = it }, visualTransformation = PasswordVisualTransformation()) }
            CbField("模型名称") { CbInput(model, { model = it }) }; CbField("最大输出 Token") { CbInput(maxTokens, { maxTokens = it }) }
        }
    }
}

private data class IdOption(val id: String?, val label: String)
private fun safeName(value: String) = value.replace(Regex("[\\\\/:*?\"<>|]"), "_")
private fun String.toModeIndex() = when (uppercase()) { "OFF" -> 0; "LIGHT" -> 1; "STRONG" -> 3; else -> 2 }
private fun Int.modeValue() = when (coerceIn(0, 3)) { 0 -> "OFF"; 1 -> "LIGHT"; 3 -> "STRONG"; else -> "STANDARD" }
private fun Int.modeLabel() = when (coerceIn(0, 3)) { 0 -> "关闭"; 1 -> "轻量"; 3 -> "强"; else -> "标准" }
