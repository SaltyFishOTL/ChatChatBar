package com.example.chatbar.ui.manage

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.chatbar.domain.backup.AppBackupPhase
import com.example.chatbar.domain.backup.AppBackupService
import com.example.chatbar.domain.backup.AppBackupSelection
import com.example.chatbar.domain.backup.AppBackupRestoreMode
import com.example.chatbar.ui.components.CreateOpenableDocument
import com.example.chatbar.ui.kit.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-owned operation survives page disposal; passwords never enter saved instance state. */
@Composable
fun AppBackupHost(service: AppBackupService, onCloseApp: () -> Unit) {
    val state by service.state.collectAsState()
    val visible by service.dialogOpen.collectAsState()
    val shared by service.importRequest.collectAsState()
    val notice by com.example.chatbar.ChatBarApp.instance.backupStartupNotice.collectAsState()
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val catalogState by service.catalog.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var exportContents by remember { mutableStateOf<AppBackupSelection?>(null) }
    var restoreMode by remember { mutableStateOf(state.restorePreview?.mode ?: AppBackupRestoreMode.REPLACE_ALL) }

    fun attempt(action: () -> Unit) {
        error = null
        runCatching(action).onFailure { error = it.message ?: "无法开始迁移" }
    }
    val create = rememberLauncherForActivityResult(CreateOpenableDocument("application/vnd.chatbar.backup")) { uri ->
        if (uri == null) service.cancelExportSelection()
        else attempt { service.exportSelected(uri) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        importUri = uri
        error = null
        password = ""
        repeatPassword = ""
        restoreMode = AppBackupRestoreMode.REPLACE_ALL
    }
    LaunchedEffect(visible, shared?.path) {
        password = ""
        repeatPassword = ""
        error = null
        restoreMode = state.restorePreview?.mode ?: AppBackupRestoreMode.REPLACE_ALL
        if (!visible || shared != null) importUri = null
        if (!visible || shared != null) { choosing = false; exportContents = null }
    }
    if (!visible) {
        if (notice != null) CbDialog(
            onDismissRequest = { com.example.chatbar.ChatBarApp.instance.backupStartupNotice.value = null },
            title = "数据迁移",
            confirm = { CbButton("知道了", { com.example.chatbar.ChatBarApp.instance.backupStartupNotice.value = null }) }
        ) { CbText(requireNotNull(notice)) }
        return
    }
    val importing = shared != null || importUri != null
    val confirming = state.phase == AppBackupPhase.AWAITING_CONFIRMATION
    val restarting = state.phase == AppBackupPhase.READY_TO_RESTART
    val completed = state.phase == AppBackupPhase.COMPLETE
    if (choosing && !state.busy && !importing && !confirming && !restarting && !completed) {
        AppBackupSelectionScreen(catalogState, exportContents,
            onRefresh = service::refreshCatalog,
            onDismiss = { choosing = false },
            onContinue = { exportContents = it; choosing = false })
        return
    }
    CbDialog(
        onDismissRequest = { if (!state.busy && !restarting) service.dismiss() },
        title = when {
            confirming -> "确认导入存档"
            restarting -> "重新打开以恢复存档"
            state.busy -> "正在迁移数据"
            completed -> "导出完成"
            exportContents != null && !importing -> "导出保护与保存"
            importing -> "导入迁移存档"
            else -> "数据迁移"
        },
        dismissOnClickOutside = !state.busy && !confirming && !restarting,
        dismissOnBackPress = !state.busy && !restarting,
        dismiss = {
            if (state.busy) CbButton("取消", { service.cancel() }, variant = ButtonVariant.Ghost)
            else if (!restarting) CbButton("关闭", {
                password = ""; repeatPassword = ""; importUri = null
                service.dismiss()
            }, variant = ButtonVariant.Ghost)
        },
        confirm = {
            if (confirming) {
                val previewReady = state.restorePreview?.mode == restoreMode
                val needsPreview = state.summary?.requiresRestorePreview(restoreMode) == true && !previewReady
                CbButton(if (needsPreview) "预览导入结果" else if (restoreMode == AppBackupRestoreMode.REPLACE_ALL)
                    if (state.summary?.scope != null) "确认覆盖所选内容" else "替换全部数据"
                    else "确认${restoreModeLabel(restoreMode)}", {
                    attempt { if (needsPreview) service.previewRestore(restoreMode) else service.prepareRestore(restoreMode) }
                }, variant = if (needsPreview || restoreMode == AppBackupRestoreMode.ADD_COPIES) ButtonVariant.Default else ButtonVariant.Destructive)
            }
            else if (restarting) CbButton("关闭 APP", onCloseApp)
        }
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.md)) {
            if (state.busy) {
                CbSpinner()
                CbText(state.label, color = ChatBarTheme.colors.mutedForeground)
                if (state.totalBytes > 0) {
                    CbProgress(state.doneBytes.toFloat() / state.totalBytes)
                    CbText(size(state.doneBytes) + " / " + size(state.totalBytes))
                } else if (state.doneBytes > 0) CbText("已处理 " + size(state.doneBytes))
                if (state.doneFiles > 0) CbText("已处理 " + state.doneFiles + " 个文件")
                CbText("迁移可离线进行。准备与导出期间暂时锁定数据编辑。", color = ChatBarTheme.colors.mutedForeground)
            } else if (confirming || completed || restarting) {
                state.summary?.let { summary ->
                    CbText("存档时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(summary.createdAt)))
                    CbText("来源版本：" + summary.appVersionName)
                    CbText("数据：" + summary.fileCount + " 个文件 · " + size(summary.totalBytes))
                    val labels = mapOf(
                        "chat_sessions" to "会话", "chat_messages" to "聊天消息", "character_cards" to "角色卡",
                        "model_configs" to "模型", "save_slots" to "会话存档", "novelai_generation_history" to "生图记录",
                        "moment_posts" to "朋友圈", "generated_voice_messages" to "语音"
                    )
                    labels.forEach { (key, label) ->
                        summary.counts[key]?.let { CbText(label + "：" + it) }
                    }
                    summary.scope?.let { scope ->
                        CbDivider()
                        CbText("所选范围：全局设置 ${scope.globalOptions.size} 项 · 角色 ${scope.characters} 张 · 会话 ${scope.sessions} 个")
                        if (scope.sessions > 0) CbText("含聊天图片 ${scope.sessionsWithImages} 个 · 语音 ${scope.sessionsWithVoice} 个 · 存档 ${scope.sessionsWithArchives} 个会话",
                            color = ChatBarTheme.colors.mutedForeground)
                        CbText(buildList {
                            if (scope.studioSettings) add("工作室设置")
                            if (scope.studioHistory) add(if (scope.studioHistoryImages) "工作室历史与原图" else "工作室历史（仅元数据）")
                            if (scope.moments) add(if (scope.momentsImages) "朋友圈（含配图）" else "朋友圈（不含配图）")
                        }.joinToString(" · "), color = ChatBarTheme.colors.mutedForeground)
                        if (confirming && restoreMode == AppBackupRestoreMode.REPLACE_ALL) CbText("仅覆盖存档勾选范围，未勾选的设置、数据和媒体保留本机内容。", color = ChatBarTheme.colors.mutedForeground)
                    }
                }
                if (confirming) {
                    CbDivider()
                    CbField("导入方式") {
                        CbSelect(restoreMode, AppBackupRestoreMode.entries, ::restoreModeLabel, { mode ->
                            if (mode != restoreMode) attempt { service.discardRestorePreview(); restoreMode = mode }
                        })
                    }
                    CbText(when (restoreMode) {
                        AppBackupRestoreMode.REPLACE_ALL -> if (state.summary?.scope != null)
                            "覆盖所选角色卡和会话，以及已选分组。未勾选内容保留；已勾选但为空的分组会清空本机对应内容。"
                            else "旧版存档没有选择范围，将替换全部本地数据。需要保留时先取消并导出备份。"
                        AppBackupRestoreMode.UPDATE_EXISTING -> "更新匹配的角色卡、会话、朋友圈和生图历史，其余追加。本机未匹配内容保留。"
                        AppBackupRestoreMode.ADD_COPIES -> "角色卡、会话、朋友圈和生图历史全部新增，重复项创建独立副本，不改动本机原记录。"
                    }, color = if (restoreMode == AppBackupRestoreMode.REPLACE_ALL) ChatBarTheme.colors.destructive else ChatBarTheme.colors.foreground)
                    if (restoreMode != AppBackupRestoreMode.REPLACE_ALL || state.summary?.scope != null) {
                        CbText("已导出的全局设置、凭据和工作室设置仍覆盖；未导出项保留本机内容。",
                            color = ChatBarTheme.colors.warning)
                        if (restoreMode == AppBackupRestoreMode.UPDATE_EXISTING) CbText(
                            "优先匹配 ID，再比较唯一名称或内容指纹。会话在所属角色卡内匹配；重名歧义保留副本并提示。",
                            color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    }
                    state.restorePreview?.let { preview ->
                        CbDivider()
                        CbText("导入预览", style = ChatBarTheme.typography.label)
                        mapOf("character_cards" to "角色卡", "chat_sessions" to "会话", "moment_posts" to "朋友圈",
                            "novelai_generation_history" to "生图历史", "model_configs" to "模型",
                            "embedding_configs" to "向量模型", "format_cards" to "格式卡", "world_books" to "世界书",
                            "edit_drafts" to "编辑草稿", "player_setting" to "玩家信息").forEach { (type, label) ->
                            preview.counts[type]?.let { count -> CbText("$label：新增 ${count.added} · 更新 ${count.updated} · 副本 ${count.copies}" +
                                if (count.removed > 0) " · 删除 ${count.removed}" else "") }
                        }
                        CbText("确认后关闭并重新打开 APP。预览期间暂停数据编辑，关闭此页可取消。",
                            color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                    }
                }
                if (restarting) CbText("关闭后从桌面重新打开 ChatBar。恢复初始化成功前，原数据备份会保留。")
                if (completed) CbText("将 .cbbackup 文件传到新机，在 ChatBar 的“数据迁移”中导入。")
            } else {
                CbText(if (exportContents != null && !importing) "所选内容已准备。可返回修改清单，或设置密码后选择保存位置。"
                    else "按需选择设置、角色卡、会话、工作室和朋友圈，也可一键全选迁移。")
                CbText("存档可能包含所选登录凭据及关联模型的 API 密钥，请妥善保管。", color = ChatBarTheme.colors.mutedForeground)
                if (!importing && exportContents != null) {
                    CbButton("修改导出清单", { choosing = true }, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                    SettingsSwitch(checked = encrypt, onCheckedChange = { encrypt = it }, label = "密码加密（可选）")
                }
                if (importing || encrypt && exportContents != null) {
                    CbField(if (importing) "存档密码（加密文件填写）" else "存档密码",
                        description = if (importing) "未加密文件可留空。" else "至少 8 个字符；密码不保存，丢失后无法解密。") {
                        CbInput(password, { password = it }, secure = true)
                    }
                    if (!importing) CbField("再次输入密码") {
                        CbInput(repeatPassword, { repeatPassword = it }, secure = true)
                    }
                }
                if (importing) {
                    CbButton("校验存档", {
                        attempt {
                            val secret = password.takeIf { it.isNotEmpty() }?.toCharArray()
                            password = ""; repeatPassword = ""
                            if (shared != null) service.inspectShared(secret)
                            else service.inspect(requireNotNull(importUri), secret)
                        }
                    }, Modifier.fillMaxWidth())
                } else {
                    if (exportContents != null && !encrypt) CbText("当前导出不加密。持有文件者可读取所选聊天和密钥。", color = ChatBarTheme.colors.destructive)
                    CbButton(if (exportContents == null) "选择导出内容" else "选择保存位置并导出", {
                        if (exportContents == null) { choosing = true; service.refreshCatalog() }
                        else {
                            attempt {
                                if (encrypt) {
                                    require(password.length in 8..1024) { "密码需为 8–1024 个字符" }
                                    require(password == repeatPassword) { "两次密码不一致" }
                                }
                                service.configureExport(if (encrypt) password.toCharArray() else null, requireNotNull(exportContents))
                                password = ""; repeatPassword = ""
                                create.launch("ChatBar-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".cbbackup")
                            }
                        }
                    }, Modifier.fillMaxWidth())
                    CbButton("导入迁移存档", { open.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                }
            }
            state.warnings.forEach { CbText(it, color = ChatBarTheme.colors.warning) }
            (error ?: state.error)?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        }
    }
}

private fun restoreModeLabel(mode: AppBackupRestoreMode) = when (mode) {
    AppBackupRestoreMode.REPLACE_ALL -> "全量覆盖"
    AppBackupRestoreMode.UPDATE_EXISTING -> "增量更新"
    AppBackupRestoreMode.ADD_COPIES -> "增量导入"
}

private fun size(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(
    com.example.chatbar.ChatBarApp.instance, bytes
)
