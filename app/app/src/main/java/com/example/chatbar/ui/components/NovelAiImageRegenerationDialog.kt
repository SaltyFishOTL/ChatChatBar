package com.example.chatbar.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbField
import com.example.chatbar.ui.kit.CbInput
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.FullscreenTextEditor

@Composable
fun NovelAiImageRegenerationDialog(
    draft: NovelAiImageRegenerationDraft?,
    loading: Boolean,
    submitting: Boolean = false,
    errorMessage: String? = null,
    onDraftChange: (NovelAiImageRegenerationDraft) -> Unit,
    onDismiss: () -> Unit,
    onRegenerate: (NovelAiImageRegenerationDraft) -> Unit,
    onDeleteImage: (() -> Unit)? = null
) {
    var fullscreenTarget by remember { mutableStateOf<Int?>(null) }
    CbDialog(
        onDismissRequest = {
            if (!submitting) {
                fullscreenTarget = null
                onDismiss()
            }
        },
        title = "图片操作",
        dismiss = {
            CbButton(
                "取消",
                {
                    fullscreenTarget = null
                    onDismiss()
                },
                enabled = !submitting,
                variant = ButtonVariant.Ghost
            )
        },
        confirm = {
            CbButton(
                if (submitting) "生成中" else "重新生成",
                { draft?.let(onRegenerate) },
                enabled = draft?.baseCaption?.isNotBlank() == true && !loading && !submitting
            )
        }
    ) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CbSpinner()
                }
            } else {
                errorMessage?.let {
                    CbText(it, color = ChatBarTheme.colors.destructive)
                }
                draft?.let { current ->
                    CbText(
                        "编辑本次使用的 NovelAI 提示词。尺寸保持 ${current.width}×${current.height}；每次使用新 seed。",
                        color = ChatBarTheme.colors.mutedForeground
                    )
                    CbField(
                        label = "主提示词",
                        description = "场景、构图、画质和全局风格标签",
                        error = if (current.baseCaption.isBlank()) "主提示词不能为空" else null,
                        onFullscreenEdit = { fullscreenTarget = BASE_CAPTION_TARGET }
                    ) {
                        CbInput(
                            value = current.baseCaption,
                            onValueChange = { onDraftChange(current.copy(baseCaption = it)) },
                            singleLine = false,
                            minLines = 3,
                            isError = current.baseCaption.isBlank()
                        )
                    }
                    current.characterPrompts.forEachIndexed { index, characterPrompt ->
                        CbField(
                            label = "角色提示词 ${index + 1}",
                            description = "该角色的外观、服装和动作标签",
                            onFullscreenEdit = { fullscreenTarget = index }
                        ) {
                            CbInput(
                                value = characterPrompt.prompt,
                                onValueChange = { value ->
                                    onDraftChange(
                                        current.copy(
                                            characterPrompts = current.characterPrompts.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) item.copy(prompt = value) else item
                                            }
                                        )
                                    )
                                },
                                singleLine = false,
                                minLines = 3
                            )
                        }
                    }
                    CbField(
                        label = "负面提示词",
                        description = "不希望图片出现的内容或质量问题",
                        onFullscreenEdit = { fullscreenTarget = NEGATIVE_PROMPT_TARGET }
                    ) {
                        CbInput(
                            value = current.negativePrompt,
                            onValueChange = { onDraftChange(current.copy(negativePrompt = it)) },
                            singleLine = false,
                            minLines = 3
                        )
                    }
                }
            }
            if (onDeleteImage != null) {
                CbButton(
                    "删除图片",
                    onDeleteImage,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Destructive
                )
            }
        }
    }

    fullscreenTarget?.let { target ->
        val current = draft ?: return@let
        val text = when (target) {
            BASE_CAPTION_TARGET -> current.baseCaption
            NEGATIVE_PROMPT_TARGET -> current.negativePrompt
            else -> current.characterPrompts.getOrNull(target)?.prompt ?: return@let
        }
        val title = when (target) {
            BASE_CAPTION_TARGET -> "编辑主提示词"
            NEGATIVE_PROMPT_TARGET -> "编辑负面提示词"
            else -> "编辑角色提示词 ${target + 1}"
        }
        FullscreenTextEditor(
            title = title,
            text = text,
            onTextChange = { value ->
                onDraftChange(
                    when (target) {
                        BASE_CAPTION_TARGET -> current.copy(baseCaption = value)
                        NEGATIVE_PROMPT_TARGET -> current.copy(negativePrompt = value)
                        else -> current.copy(
                            characterPrompts = current.characterPrompts.mapIndexed { index, item ->
                                if (index == target) item.copy(prompt = value) else item
                            }
                        )
                    }
                )
            },
            visible = true,
            onDismiss = { fullscreenTarget = null }
        )
    }
}

private const val BASE_CAPTION_TARGET = -1
private const val NEGATIVE_PROMPT_TARGET = -2
