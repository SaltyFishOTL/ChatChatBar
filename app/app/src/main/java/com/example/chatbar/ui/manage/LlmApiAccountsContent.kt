package com.example.chatbar.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.LlmApiAccount
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.canonicalLlmApiEndpoint
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LlmApiAccountsContent(models: List<ModelConfig>, defaultAccountId: String?) {
    val app = ChatBarApp.instance
    val repository = app.llmApiAccountRepository
    val accounts by repository.accounts.collectAsState()
    val unavailable by repository.loadError.collectAsState()
    val modelMigrationPending by app.modelRepository.llmMigrationPending.collectAsState()
    val defaultMigrationPending by app.settingsRepository.siliconFlowMigrationPending.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LlmApiAccount?>(null) }
    var removing by remember { mutableStateOf<LlmApiAccount?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) { repository.initialize() }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalArgumentException) {
                failure = error.message ?: "账户配置无效"
            } catch (_: Exception) {
                failure = "操作失败，请确认密钥库可用且账户未被其他模型使用后重试"
            } finally {
                busy = false
            }
        }
    }

    CbText(
        "密钥保存在当前设备。导入到其他设备后，请重新配置账户并关联模型。",
        style = ChatBarTheme.typography.caption, color = ChatBarTheme.colors.mutedForeground
    )
    if (unavailable || modelMigrationPending || defaultMigrationPending) {
        CbText(
            if (unavailable) "对话 API 密钥库暂时无法读取。" else "部分旧 Key 尚未迁移，原配置仍可用。",
            color = ChatBarTheme.colors.destructive
        )
        CbButton("重试加载和迁移", {
            perform {
                repository.initialize()
                app.modelRepository.retryLlmKeyMigration()
                app.settingsRepository.retrySiliconFlowKeyMigration()
            }
        }, enabled = !busy, variant = ButtonVariant.Outline)
    }
    if (!editing && removing == null) failure?.let { CbText(it, color = ChatBarTheme.colors.destructive) }

    CbButton("添加账户", {
        failure = null
        selected = null
        editing = true
    }, enabled = !busy && !unavailable)

    accounts.forEach { account ->
        val references = models.filter { it.llmAccountId == account.id }.map { it.displayName }
        val usedByGlobal = defaultAccountId == account.id
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CbText(account.displayName, style = ChatBarTheme.typography.heading)
            CbText(account.baseUrl, color = ChatBarTheme.colors.mutedForeground)
            if (usedByGlobal) CbText("硅基流动默认 Key", style = ChatBarTheme.typography.caption)
            if (references.isNotEmpty()) CbText("使用此账户的模型：" + references.joinToString("、"),
                style = ChatBarTheme.typography.caption)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CbButton("编辑", {
                    failure = null
                    selected = account
                    editing = true
                }, enabled = !busy, variant = ButtonVariant.Outline)
                CbButton("删除", {
                    failure = null
                    removing = account
                }, enabled = !busy && references.isEmpty() && !usedByGlobal, variant = ButtonVariant.Ghost)
            }
        }
    }

    if (editing) {
        LlmApiAccountDialog(
            account = selected, busy = busy, failure = failure,
            onDismiss = { if (!busy) { editing = false; failure = null } },
            onSave = { alias, baseUrl, apiKey ->
                val old = selected
                perform {
                    if (old == null) repository.create(alias, baseUrl, apiKey)
                    else repository.update(old.id, alias, apiKey.takeIf { it.isNotBlank() })
                    editing = false
                }
            }
        )
    }
    removing?.let { target ->
        CbDialog(
            title = "删除对话 API 账户",
            onDismissRequest = { if (!busy) { removing = null; failure = null } },
            confirm = {
                CbButton("删除", {
                    perform {
                        app.settingsRepository.removeLlmApiAccount(target.id, app.modelRepository)
                        removing = null
                    }
                }, enabled = !busy)
            },
            dismiss = { CbButton("取消", { removing = null; failure = null }, enabled = !busy, variant = ButtonVariant.Ghost) }
        ) {
            CbText("删除「${target.displayName}」？")
            failure?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        }
    }
}

@Composable
private fun LlmApiAccountDialog(
    account: LlmApiAccount?,
    busy: Boolean,
    failure: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var alias by remember(account?.id) { mutableStateOf(account?.displayName.orEmpty()) }
    var baseUrl by remember(account?.id) { mutableStateOf(account?.baseUrl.orEmpty()) }
    var apiKey by remember(account?.id) { mutableStateOf("") }
    CbDialog(
        title = if (account == null) "添加对话 API 账户" else "编辑对话 API 账户",
        onDismissRequest = onDismiss,
        dismiss = { CbButton("取消", onDismiss, enabled = !busy, variant = ButtonVariant.Ghost) },
        confirm = { CbButton(
            "保存", { onSave(alias, baseUrl, apiKey) },
            enabled = !busy && alias.isNotBlank() && canonicalLlmApiEndpoint(baseUrl) != null &&
                (account != null || apiKey.isNotBlank())
        ) }
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CbField("账户名称") { CbInput(alias, { alias = it }, placeholder = "例如 DeepSeek 官方·日常") }
            CbField("Base URL") {
                if (account != null) CbText(account.baseUrl)
                else CbInput(baseUrl, { baseUrl = it }, placeholder = "https://api.example.com/v1")
            }
            CbField("API Key", description = if (account != null) "留空保留原 Key。如需换接口，请添加另一个账户。" else null) {
                CbInput(apiKey, { apiKey = it }, secure = true)
            }
            failure?.let { CbText(it, color = ChatBarTheme.colors.destructive) }
        }
    }
}
