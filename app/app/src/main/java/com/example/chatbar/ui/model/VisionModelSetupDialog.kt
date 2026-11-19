package com.example.chatbar.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.DiscoveredModel
import com.example.chatbar.domain.model.ModelDiscoveryService
import com.example.chatbar.domain.model.ModelImageInput
import com.example.chatbar.domain.model.officialVisionModelSuggestion
import com.example.chatbar.domain.model.visionApiBase
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlinx.coroutines.CancellationException

@Composable
internal fun VisionModelSetupDialog(
    currentBaseUrl: String?,
    currentApiKey: String,
    onDismiss: () -> Unit,
    onSelect: (ModelConfig) -> Unit
) {
    val service = remember { ModelDiscoveryService() }
    var baseUrl by remember { mutableStateOf(currentBaseUrl.orEmpty()) }
    // Credentials stay in this dialog's transient state and are never copied to another base URL.
    var apiKey by remember { mutableStateOf(currentApiKey) }
    var modelId by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var confirmedImageInput by remember { mutableStateOf(false) }
    var showInChat by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<DiscoveredModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var allowCleartext by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allowCleartext = ChatBarApp.instance.settingsRepository.getAppSettings().allowCleartextModelApi
    }

    val base = visionApiBase(baseUrl)
    val explicitKey = apiKey.trim()
    val canUseConnection = base != null && (base.isHttps || allowCleartext) &&
        explicitKey.isNotEmpty() && explicitKey.all { it in '!'..'~' }
    val suggestion = officialVisionModelSuggestion(baseUrl)
    val imageInput = models.firstOrNull { it.id == modelId.trim() }?.imageInput ?: ModelImageInput.UNKNOWN
    val documentedImageInput = imageInput == ModelImageInput.UNKNOWN && suggestion != null && modelId.trim() == suggestion

    fun updateModelId(value: String) {
        if (modelId.trim() != value.trim()) confirmedImageInput = false
        modelId = value
    }

    LaunchedEffect(picker, revision) {
        if (!picker) return@LaunchedEffect
        discoveryError = null
        loading = true
        try {
            models = service.fetchModels(baseUrl, explicitKey, allowCleartext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discoveryError = e.message ?: "获取模型列表失败，可手动填写标识"
        } finally {
            loading = false
        }
    }

    if (picker) {
        val candidates = models.filter { it.imageInput != ModelImageInput.NOT_SUPPORTED }.associateBy(DiscoveredModel::id)
        ModelPickerDialog(
            modelIds = candidates.keys.toList(),
            selectedId = modelId.trim(),
            loading = loading,
            error = discoveryError ?: if (!loading && models.isNotEmpty() && candidates.isEmpty()) {
                "列表中没有支持图片的模型，可返回手动填写。"
            } else null,
            onRefresh = { revision++ },
            onSelect = { id ->
                updateModelId(id)
                picker = false
            },
            onDismiss = { picker = false },
            title = "从接口选择视觉模型",
            modelDetail = { id ->
                if (candidates[id]?.imageInput == ModelImageInput.SUPPORTED) "接口声明支持图片" else "图片能力未声明"
            }
        )
    } else {
        CbDialog(
            title = if (currentBaseUrl != null) "从当前接口添加视觉模型" else "新增其他接口的视觉模型",
            onDismissRequest = onDismiss,
            dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
            confirm = {
                CbButton(
                    "关联",
                    {
                        val id = modelId.trim()
                        val model = ModelConfig.create(
                            displayName = alias.trim().ifBlank { id }, baseUrl = baseUrl.trim(),
                            apiKey = explicitKey, modelName = id, isMultimodal = true
                        ).copy(selectableForChat = showInChat)
                        onSelect(model)
                    },
                    enabled = canUseConnection && modelId.isNotBlank() && when (imageInput) {
                        ModelImageInput.SUPPORTED -> true
                        ModelImageInput.NOT_SUPPORTED -> false
                        ModelImageInput.UNKNOWN -> documentedImageInput || confirmedImageInput
                    }
                )
            }
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CbField("Base URL") {
                    if (currentBaseUrl != null) {
                        CbText(base?.toString() ?: "请先在当前模型中填写有效的 OpenAI 兼容地址")
                    } else {
                        CbInput(baseUrl, { value ->
                            if (visionApiBase(value) != visionApiBase(baseUrl)) {
                                apiKey = ""
                                models = emptyList()
                                updateModelId("")
                            }
                            baseUrl = value
                        }, placeholder = "https://api.example.com/v1")
                    }
                }
                CbField(
                    "API Key",
                    description = if (currentApiKey.isNotBlank()) "已填入当前接口的 Key，可为这一模型另填。"
                    else "填写此接口自己的 Key。"
                ) {
                    CbInput(apiKey, { value ->
                        if (apiKey != value) {
                            models = emptyList()
                            confirmedImageInput = false
                        }
                        apiKey = value
                    }, secure = true)
                }
                if (base?.isHttps == false && !allowCleartext) {
                    CbText("明文 HTTP 模型 API 已禁用。", color = ChatBarTheme.colors.destructive)
                }
                CbButton("从接口查找模型", { picker = true }, enabled = canUseConnection, variant = ButtonVariant.Outline)
                if (suggestion != null && modelId.trim() != suggestion &&
                    models.none { it.id == suggestion && it.imageInput == ModelImageInput.NOT_SUPPORTED }
                ) {
                    CbButton("选用 DeepSeek Flash", { updateModelId(suggestion) }, variant = ButtonVariant.Ghost)
                }
                CbField("视觉模型标识") {
                    CbInput(modelId, ::updateModelId, placeholder = "从接口选择或手动填写")
                }
                when {
                    imageInput == ModelImageInput.NOT_SUPPORTED -> CbText(
                        "接口声明此模型不接收图片，请换一个视觉模型。", color = ChatBarTheme.colors.destructive
                    )
                    imageInput == ModelImageInput.SUPPORTED -> CbText("接口声明支持图片输入。")
                    documentedImageInput -> CbText("DeepSeek 官方文档确认 Flash 支持图片输入。")
                    modelId.isNotBlank() -> CbField("确认此模型支持图片输入") {
                        CbSwitch(confirmedImageInput, { confirmedImageInput = it })
                    }
                }
                CbField("显示名称") {
                    CbInput(alias, { alias = it }, placeholder = "留空使用模型标识")
                }
                CbField("也显示在对话模型列表") {
                    CbSwitch(showInChat, { showInChat = it })
                }
                CbText("保存当前模型后，关联及新模型才会生效。", style = ChatBarTheme.typography.caption)
            }
        }
    }
}
