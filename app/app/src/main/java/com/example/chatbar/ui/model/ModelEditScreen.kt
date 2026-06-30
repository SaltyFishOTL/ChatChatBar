package com.example.chatbar.ui.model

import com.example.chatbar.ui.kit.AppIcons

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.data.local.entity.ModelTemplate
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSelect
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme

@Composable
fun ModelEditScreen(
    modelId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelEditViewModel = viewModel(key = modelId, factory = ModelEditViewModelFactory(modelId))
) {
    val multimodalModels by viewModel.availableMultimodalModels.collectAsState()
    var showAddParam by remember { mutableStateOf(false) }
    val canSave = viewModel.displayName.isNotBlank() && viewModel.baseUrl.isNotBlank() &&
        viewModel.modelName.isNotBlank()
    val context = LocalContext.current
    var lastBackPressAt by remember { mutableStateOf(0L) }

    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt <= 2000L) {
            onBack()
        } else {
            lastBackPressAt = now
            Toast.makeText(context, "再按一次退出编辑（不会保存修改）", Toast.LENGTH_SHORT).show()
        }
    }

    CbScaffold(
        modifier = modifier,
        topBar = {
            CbTopBar(
                title = if (modelId == null) "添加模型" else "编辑模型",
                statusBarInset = true,
                navigation = { CbIconButton(AppIcons.ArrowBack, "返回", onBack) },
                actions = {
                    CbIconButton(
                        AppIcons.Save,
                        "保存",
                        { viewModel.saveModelConfig(onBack) },
                        enabled = canSave,
                        tint = ChatBarTheme.colors.primary
                    )
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("核心配置")
            CbField("显示名称") {
                CbInput(viewModel.displayName, { viewModel.displayName = it }, placeholder = "例如：ChatGPT-4o")
            }
            CbField("接口模板类型") {
                CbSelect(
                    value = viewModel.templateType,
                    options = ModelTemplate.entries.toList(),
                    optionLabel = { it.name },
                    onValueChange = viewModel::applyTemplateDefaults,
                    placeholder = "选择接口模板"
                )
            }
            CbField("Base URL") {
                CbInput(viewModel.baseUrl, { viewModel.baseUrl = it }, placeholder = "https://api.openai.com/v1")
            }
            CbField("API Key", description = "留空时使用设置里的全局默认 API Key。") {
                CbInput(
                    viewModel.apiKey,
                    { viewModel.apiKey = it },
                    placeholder = "可留空，默认使用全局 API Key",
                    visualTransformation = PasswordVisualTransformation()
                )
            }
            CbField("模型标识") {
                CbInput(viewModel.modelName, { viewModel.modelName = it }, placeholder = "gpt-4o-mini")
            }

            CbDivider()
            SectionTitle("多模态")
            SettingRow(
                title = "多模态模型",
                description = "开启后，模型可以直接接收并识别用户图片。",
                checked = viewModel.isMultimodal,
                onCheckedChange = { viewModel.isMultimodal = it }
            )
            if (!viewModel.isMultimodal) {
                val options = listOf(VisionOption(null, "不关联视觉模型")) +
                    multimodalModels.map { VisionOption(it.id, it.displayName) }
                CbField("图片解析视觉模型", description = "文本模型收到图片时，先由此模型生成描述。") {
                    CbSelect(
                        value = options.firstOrNull { it.id == viewModel.visionModelId },
                        options = options,
                        optionLabel = { it.label },
                        onValueChange = { viewModel.visionModelId = it.id },
                        placeholder = "选择视觉模型"
                    )
                }
            }

            CbDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("常用参数")
                CbButton("手动添加", { showAddParam = true }, variant = ButtonVariant.Ghost)
            }
            PresetGrid(viewModel.customParamsMap.keys) { preset ->
                viewModel.customParamsMap[preset.key] = preset.defaultValue
            }
            CbText("已启用参数", color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.label)
            viewModel.customParamsMap.toList().forEach { (key, value) ->
                ParameterRow(key, value, { viewModel.customParamsMap[key] = it }) {
                    viewModel.customParamsMap.remove(key)
                }
            }
        }
    }

    if (showAddParam) {
        AddParameterDialog(
            onDismiss = { showAddParam = false },
            onAdd = { key, value ->
                viewModel.customParamsMap[key] = value
                showAddParam = false
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    CbText(text, color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)
}

@Composable
private fun SettingRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            CbText(title)
            CbText(description, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
        }
        CbSwitch(checked, onCheckedChange)
    }
}

@Composable
private fun ParameterRow(
    key: String,
    value: ParamValue,
    onValueChange: (ParamValue) -> Unit,
    onDelete: () -> Unit
) {
    CbSurface(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CbText(key, style = ChatBarTheme.typography.heading)
                CbText(paramTypeLabel(value), color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
            }
            when (value) {
                is ParamValue.BooleanValue -> CbSwitch(value.value, { onValueChange(ParamValue.BooleanValue(it)) })
                is ParamValue.NumberValue -> {
                    var text by remember(value.value) { mutableStateOf(value.value.toString()) }
                    CbInput(
                        value = text,
                        onValueChange = {
                            text = it
                            it.toDoubleOrNull()?.let { number -> onValueChange(ParamValue.NumberValue(number)) }
                        },
                        modifier = Modifier.width(104.dp)
                    )
                }
                is ParamValue.StringValue -> {
                    CbInput(
                        value = value.value,
                        onValueChange = { onValueChange(ParamValue.StringValue(it)) },
                        modifier = Modifier.width(124.dp)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            CbIconButton(AppIcons.Delete, "删除", onDelete, tint = ChatBarTheme.colors.destructive)
        }
    }
}

@Composable
private fun AddParameterDialog(onDismiss: () -> Unit, onAdd: (String, ParamValue) -> Unit) {
    var key by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ParamType.Number) }
    var rawValue by remember { mutableStateOf("") }
    CbDialog(
        onDismissRequest = onDismiss,
        title = "手动添加参数",
        dismiss = { CbButton("取消", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = {
            CbButton("添加", {
                val value = when (type) {
                    ParamType.Number -> ParamValue.NumberValue(rawValue.toDoubleOrNull() ?: 0.0)
                    ParamType.Boolean -> ParamValue.BooleanValue(rawValue.lowercase().toBooleanStrictOrNull() ?: false)
                    ParamType.String -> ParamValue.StringValue(rawValue)
                }
                onAdd(key, value)
            }, enabled = key.isNotBlank())
        }
    ) {
        CbField("参数 Key") { CbInput(key, { key = it }, placeholder = "temperature") }
        Spacer(Modifier.size(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParamType.entries.forEach { option ->
                CbChoiceChip(option.label, type == option, { type = option })
            }
        }
        Spacer(Modifier.size(12.dp))
        CbField("参数初始值") { CbInput(rawValue, { rawValue = it }, placeholder = "0.8 / true / text") }
    }
}

@Composable
private fun PresetGrid(existingKeys: Set<String>, onAdd: (CommonParamPreset) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        commonParamPresets.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset ->
                    val enabled = preset.key !in existingKeys
                    CbSurface(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(enabled = enabled) { onAdd(preset) },
                        color = if (enabled) ChatBarTheme.colors.card else ChatBarTheme.colors.muted,
                        border = BorderStroke(1.dp, ChatBarTheme.colors.border)
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CbText(preset.title, style = ChatBarTheme.typography.heading)
                            CbText(preset.key, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                            CbText(preset.description, color = ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                            CbText(if (enabled) "点按添加 · ${preset.preview}" else "已添加", color = if (enabled) ChatBarTheme.colors.primary else ChatBarTheme.colors.mutedForeground, style = ChatBarTheme.typography.caption)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class VisionOption(val id: String?, val label: String)
private enum class ParamType(val label: String) { Number("数字"), Boolean("布尔"), String("文本") }
private data class CommonParamPreset(
    val key: String,
    val title: String,
    val description: String,
    val defaultValue: ParamValue,
    val preview: String
)

private val commonParamPresets = listOf(
    CommonParamPreset("max_tokens", "最大输出", "限制单次回复长度。", ParamValue.NumberValue(1500.0), "1500"),
    CommonParamPreset("temperature", "随机性", "越高越发散，越低越稳定。", ParamValue.NumberValue(0.7), "0.7"),
    CommonParamPreset("enable_thinking", "启用思考", "为支持的模型开启思考模式。", ParamValue.BooleanValue(true), "true"),
    CommonParamPreset("thinking_budget", "思考预算", "限制思考 token 预算。", ParamValue.NumberValue(1024.0), "1024"),
    CommonParamPreset("stop", "停止词", "遇到指定文本时停止输出。", ParamValue.StringValue(""), "空"),
    CommonParamPreset("frequency_penalty", "减少重复", "降低重复用词和复述。", ParamValue.NumberValue(0.2), "0.2")
)

private fun paramTypeLabel(value: ParamValue): String = when (value) {
    is ParamValue.NumberValue -> "数字"
    is ParamValue.BooleanValue -> "布尔"
    is ParamValue.StringValue -> "文本"
}
