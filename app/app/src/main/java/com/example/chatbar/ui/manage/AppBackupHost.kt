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
    }
    LaunchedEffect(visible, shared?.path) {
        password = ""
        repeatPassword = ""
        error = null
        if (!visible || shared != null) importUri = null
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
    CbDialog(
        onDismissRequest = { if (!state.busy && !restarting) service.dismiss() },
        title = when {
            confirming -> "确认完整恢复"
            restarting -> "重新打开以恢复存档"
            state.busy -> "正在迁移数据"
            completed -> "导出完成"
            importing -> "导入全量存档"
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
            if (confirming) CbButton("替换全部数据", { attempt { service.prepareRestore() } }, variant = ButtonVariant.Destructive)
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
                CbText("迁移可离线进行。导出期间暂时锁定数据编辑。", color = ChatBarTheme.colors.mutedForeground)
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
                }
                if (confirming) CbText(
                    "恢复将替换当前 APP 的全部本地数据，包括设置、聊天、存档和密钥。需要保留当前数据时，请先取消并导出备份。",
                    color = ChatBarTheme.colors.destructive
                )
                if (restarting) CbText("关闭后从桌面重新打开 ChatBar。恢复初始化成功前，原数据备份会保留。")
                if (completed) CbText("将 .cbbackup 文件传到新机，在 ChatBar 的“数据迁移”中导入。")
            } else {
                CbText("完整迁移设置、聊天、模型、记忆、生图记录、原始图片、音频、草稿和会话存档。")
                CbText("存档包含 API 密钥和社区登录凭据，请妥善保管。", color = ChatBarTheme.colors.mutedForeground)
                if (!importing) {
                    SettingsSwitch(checked = encrypt, onCheckedChange = { encrypt = it }, label = "密码加密（可选）")
                }
                if (importing || encrypt) {
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
                    if (!encrypt) CbText("当前导出不加密。持有文件者可读取聊天和密钥。", color = ChatBarTheme.colors.destructive)
                    CbButton("导出全量存档", {
                        attempt {
                            if (encrypt) {
                                require(password.length in 8..1024) { "密码需为 8–1024 个字符" }
                                require(password == repeatPassword) { "两次密码不一致" }
                            }
                            service.configureExport(if (encrypt) password.toCharArray() else null)
                            password = ""; repeatPassword = ""
                            create.launch("ChatBar-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".cbbackup")
                        }
                    }, Modifier.fillMaxWidth())
                    CbButton("导入全量存档", { open.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
                }
            }
            (error ?: state.error)?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        }
    }
}

private fun size(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(
    com.example.chatbar.ChatBarApp.instance, bytes
)
