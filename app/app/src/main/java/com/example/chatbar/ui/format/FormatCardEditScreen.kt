package com.example.chatbar.ui.format

import com.example.chatbar.ui.kit.AppIcons

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbSwitch
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor

@Composable
fun FormatCardEditScreen(
    formatCardId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FormatCardEditViewModel = viewModel(
        key = formatCardId,
        factory = FormatCardEditViewModelFactory(formatCardId)
    )
) {
    var fullscreenField by remember { mutableStateOf<Pair<String, String>?>(null) }
    var fullscreenOnChange by remember { mutableStateOf<((String) -> Unit)?>(null) }
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
                title = if (formatCardId == null) "新建格式卡" else "编辑格式卡",
                statusBarInset = true,
                navigation = {
                    CbIconButton(AppIcons.ArrowBack, "返回", onBack)
                },
                actions = {
                    CbIconButton(
                        imageVector = AppIcons.Save,
                        contentDescription = "保存",
                        onClick = { viewModel.saveFormatCard(onBack) },
                        enabled = viewModel.name.isNotBlank() && viewModel.content.isNotBlank(),
                        tint = ChatBarTheme.colors.primary
                    )
                }
            )
        }
    ) { bottomInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ChatBarTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CbText("格式基本信息", color = ChatBarTheme.colors.primary, style = ChatBarTheme.typography.heading)

            CbField(label = "格式名称", error = viewModel.saveError) {
                CbInput(
                    value = viewModel.name,
                    onValueChange = { viewModel.name = it; viewModel.saveError = null },
                    placeholder = "例如：剧本对话格式"
                )
            }

            CbField(
                label = "Prompt 格式要求",
                description = "用于约束 AI 回复的结构和表达方式。",
                onFullscreenEdit = {
                    fullscreenField = "Prompt 格式要求" to viewModel.content; fullscreenOnChange = { viewModel.content = it }
                }
            ) {
                CbInput(
                    value = viewModel.content,
                    onValueChange = { viewModel.content = it },
                    placeholder = "例如：动作使用 *动作描写*，台词使用中文引号。",
                    singleLine = false,
                    minLines = 6
                )
            }

            CbDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    CbText("设为全局默认格式")
                    Spacer(Modifier.height(4.dp))
                    CbText(
                        "开启后，新建对话默认采用此格式。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbSwitch(viewModel.isDefault, { viewModel.isDefault = it })
            }
            Spacer(Modifier.height(bottomInset))
        }
    }

    fullscreenField?.let { (title, text) ->
        FullscreenTextEditor(
            title = title,
            text = text,
            onTextChange = { newValue ->
                fullscreenOnChange?.invoke(newValue)
                fullscreenField = title to newValue
            },
            visible = true,
            onDismiss = { fullscreenField = null; fullscreenOnChange = null }
        )
    }
}
