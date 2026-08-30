package com.example.chatbar.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chatbar.domain.card.SharedImageDestination
import com.example.chatbar.domain.card.SharedImageImportRequest
import com.example.chatbar.domain.card.SharedImportConflict
import com.example.chatbar.domain.card.SharedImportCoordinator
import com.example.chatbar.domain.card.SharedImportFocus
import com.example.chatbar.domain.card.SharedImportInspection
import com.example.chatbar.domain.card.SharedImportKind
import com.example.chatbar.domain.card.SharedImportQueueItemState
import com.example.chatbar.domain.card.SharedImportSection
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarSpacing
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.manage.ManageViewModel
import kotlinx.coroutines.launch

@Composable
fun SharedImportHost(
    coordinator: SharedImportCoordinator,
    enabled: Boolean,
    onFocus: (SharedImportFocus) -> Unit,
    onOpenImage: (SharedImageImportRequest) -> Unit,
    viewModel: ManageViewModel = viewModel()
) {
    val queue by coordinator.queueState.collectAsState()
    val active = queue.active
    val scope = rememberCoroutineScope()
    if (!enabled || active == null) return

    LaunchedEffect(active.id, active.state) {
        val ready = active.state as? SharedImportQueueItemState.Ready ?: return@LaunchedEffect
        if (!coordinator.claimReady(active.id)) return@LaunchedEffect
        runCatching {
            when (val inspection = ready.inspection) {
                is SharedImportInspection.Character -> {
                    val conflict = viewModel.findCharacterImportConflict(inspection.request)
                    if (conflict == null) {
                        coordinator.markCompleted(
                            active.id,
                            persistImport(viewModel, active.id, inspection, overwriteId = null)
                        )
                    } else {
                        coordinator.awaitConflict(active.id, SharedImportConflict(conflict.id, conflict.name))
                    }
                }
                is SharedImportInspection.Format -> {
                    val conflict = viewModel.findFormatNameConflict(inspection.packageData.name)
                    if (conflict == null) {
                        coordinator.markCompleted(
                            active.id,
                            persistImport(viewModel, active.id, inspection, overwriteId = null)
                        )
                    } else {
                        coordinator.awaitConflict(active.id, SharedImportConflict(conflict.id, conflict.name))
                    }
                }
                is SharedImportInspection.WorldBook -> {
                    val conflict = viewModel.findWorldBookNameConflict(inspection.packageData.book.name)
                    if (conflict == null) {
                        coordinator.markCompleted(
                            active.id,
                            persistImport(viewModel, active.id, inspection, overwriteId = null)
                        )
                    } else {
                        coordinator.awaitConflict(active.id, SharedImportConflict(conflict.id, conflict.name))
                    }
                }
                is SharedImportInspection.ModelTemplate -> coordinator.markCompleted(
                    active.id,
                    persistImport(viewModel, active.id, inspection, overwriteId = null)
                )
                is SharedImportInspection.Image -> coordinator.awaitImageChoice(active.id)
                is SharedImportInspection.Unknown -> coordinator.awaitUnknown(active.id)
            }
        }.onFailure { error ->
            coordinator.fail(active.id, error.message ?: "导入失败")
        }
    }

    LaunchedEffect(active.id, active.state is SharedImportQueueItemState.Completed) {
        val completed = active.state as? SharedImportQueueItemState.Completed ?: return@LaunchedEffect
        onFocus(completed.focus)
    }

    LaunchedEffect(active.id, (active.state as? SharedImportQueueItemState.ImageHandoff)?.attempt) {
        if (active.state is SharedImportQueueItemState.ImageHandoff) {
            coordinator.currentImageRequest()?.let(onOpenImage)
        }
    }

    val queueHint = if (queue.pendingCount > 0) " · 后面还有 ${queue.pendingCount} 项" else ""
    when (val state = active.state) {
        SharedImportQueueItemState.Preparing -> CbDialog(
            onDismissRequest = {},
            title = "正在识别共享内容",
            dismiss = {
                CbButton("取消", { coordinator.cancel(active.id) }, variant = ButtonVariant.Ghost)
            }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbSpinner()
                CbText("${active.displayName}$queueHint", color = ChatBarTheme.colors.mutedForeground)
            }
        }

        is SharedImportQueueItemState.Processing -> CbDialog(
            onDismissRequest = {},
            title = "正在导入"
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbSpinner()
                CbText("${active.displayName}$queueHint", color = ChatBarTheme.colors.mutedForeground)
            }
        }

        is SharedImportQueueItemState.AwaitingConflict -> CbDialog(
            onDismissRequest = { coordinator.cancel(active.id) },
            title = "名称冲突",
            dismiss = {
                CbButton("取消", { coordinator.cancel(active.id) }, variant = ButtonVariant.Ghost)
            }
        ) {
            CbText("已存在“${state.conflict.existingName}”。选择覆盖或创建新项。$queueHint")
            Spacer(Modifier.height(ChatBarSpacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbButton(
                    "覆盖现有项",
                    {
                        if (coordinator.claimConflict(active.id)) scope.launch {
                            runCatching {
                                persistImport(viewModel, active.id, state.inspection, state.conflict.existingId)
                            }.onSuccess { focus ->
                                coordinator.markCompleted(active.id, focus)
                            }.onFailure { error ->
                                coordinator.fail(active.id, error.message ?: "覆盖失败")
                            }
                        }
                    },
                    Modifier.weight(1f),
                    variant = ButtonVariant.Destructive
                )
                CbButton(
                    "创建新项",
                    {
                        if (coordinator.claimConflict(active.id)) scope.launch {
                            runCatching {
                                persistImport(viewModel, active.id, state.inspection, overwriteId = null)
                            }.onSuccess { focus ->
                                coordinator.markCompleted(active.id, focus)
                            }.onFailure { error ->
                                coordinator.fail(active.id, error.message ?: "导入失败")
                            }
                        }
                    },
                    Modifier.weight(1f)
                )
            }
        }

        is SharedImportQueueItemState.AwaitingImageChoice -> CbDialog(
            onDismissRequest = { coordinator.cancel(active.id) },
            title = "选择图片用途",
            dismiss = {
                CbButton("取消", { coordinator.cancel(active.id) }, variant = ButtonVariant.Ghost)
            }
        ) {
            CbText(
                "${state.staged.displayName} · ${state.inspection.info.width}×${state.inspection.info.height}$queueHint",
                color = ChatBarTheme.colors.mutedForeground
            )
            if (state.inspection.info.animatedGif) {
                Spacer(Modifier.height(ChatBarSpacing.xs))
                CbText("动画 GIF 暂不能作为图像引导，可进入图片工具。", color = ChatBarTheme.colors.mutedForeground)
            }
            Spacer(Modifier.height(ChatBarSpacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.sm)) {
                CbButton(
                    "图像引导",
                    {
                        if (coordinator.chooseImageDestination(active.id, SharedImageDestination.GUIDANCE)) {
                            coordinator.currentImageRequest()?.let(onOpenImage)
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = !state.inspection.info.animatedGif
                )
                CbButton(
                    "图片工具",
                    {
                        if (coordinator.chooseImageDestination(active.id, SharedImageDestination.TOOLS)) {
                            coordinator.currentImageRequest()?.let(onOpenImage)
                        }
                    },
                    Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Outline
                )
            }
        }

        is SharedImportQueueItemState.AwaitingUnknown -> CbDialog(
            onDismissRequest = { coordinator.cancel(active.id) },
            title = "无法自动识别",
            dismiss = {
                CbButton("取消", { coordinator.cancel(active.id) }, variant = ButtonVariant.Ghost)
            }
        ) {
            CbText(
                if (state.inspection.textLike) {
                    "可手动选择目标类型，系统仍会严格校验内容。$queueHint"
                } else {
                    "文件不是受支持的卡片、JSON 或图片。$queueHint"
                },
                color = ChatBarTheme.colors.mutedForeground
            )
            state.manualError?.let { error ->
                Spacer(Modifier.height(ChatBarSpacing.xs))
                CbText(error, color = ChatBarTheme.colors.destructive)
            }
            if (state.inspection.textLike) {
                Spacer(Modifier.height(ChatBarSpacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(ChatBarSpacing.xs)) {
                    ManualTargetButton("按角色卡尝试", SharedImportKind.CHARACTER) {
                        coordinator.tryManual(active.id, it)
                    }
                    ManualTargetButton("按格式卡尝试", SharedImportKind.FORMAT) {
                        coordinator.tryManual(active.id, it)
                    }
                    ManualTargetButton("按模型模板尝试", SharedImportKind.MODEL_TEMPLATE) {
                        coordinator.tryManual(active.id, it)
                    }
                    ManualTargetButton("按世界书尝试", SharedImportKind.WORLD_BOOK) {
                        coordinator.tryManual(active.id, it)
                    }
                }
            }
        }

        is SharedImportQueueItemState.Error -> CbDialog(
            onDismissRequest = { coordinator.cancel(active.id) },
            title = "导入失败",
            dismiss = {
                CbButton("取消", { coordinator.cancel(active.id) }, variant = ButtonVariant.Ghost)
            },
            confirm = { CbButton("重试", { coordinator.retry(active.id) }) }
        ) {
            CbText(state.message, color = ChatBarTheme.colors.destructive)
            if (queue.pendingCount > 0) {
                Spacer(Modifier.height(ChatBarSpacing.xs))
                CbText("取消后继续处理后面 ${queue.pendingCount} 项。", color = ChatBarTheme.colors.mutedForeground)
            }
        }

        is SharedImportQueueItemState.Ready,
        is SharedImportQueueItemState.ImageHandoff,
        is SharedImportQueueItemState.Completed -> Unit
    }
}

@Composable
private fun ManualTargetButton(
    label: String,
    kind: SharedImportKind,
    onSelect: (SharedImportKind) -> Unit
) {
    CbButton(label, { onSelect(kind) }, Modifier.fillMaxWidth(), variant = ButtonVariant.Outline)
}

private suspend fun persistImport(
    viewModel: ManageViewModel,
    queueId: Long,
    inspection: SharedImportInspection,
    overwriteId: String?
): SharedImportFocus = when (inspection) {
    is SharedImportInspection.Character -> {
        val card = if (overwriteId == null) {
            viewModel.importCharacterAsNew(inspection.request)
        } else {
            viewModel.overwriteCharacter(overwriteId, inspection.request)
        }
        SharedImportFocus(
            queueId,
            SharedImportSection.CHARACTER,
            card.id,
            "角色卡“${card.name}”已导入，文档 RAG 待重建。"
        )
    }
    is SharedImportInspection.Format -> {
        val card = if (overwriteId == null) {
            viewModel.importFormatAsNew(inspection.packageData)
        } else {
            viewModel.overwriteFormat(overwriteId, inspection.packageData)
        }
        SharedImportFocus(queueId, SharedImportSection.FORMAT, card.id, "格式卡“${card.name}”已导入。")
    }
    is SharedImportInspection.WorldBook -> {
        val book = if (overwriteId == null) {
            viewModel.importWorldBookAsNew(inspection.packageData)
        } else {
            viewModel.overwriteWorldBook(overwriteId, inspection.packageData)
        }
        SharedImportFocus(queueId, SharedImportSection.WORLD_BOOK, book.id, "世界书“${book.name}”已导入。")
    }
    is SharedImportInspection.ModelTemplate -> {
        val id = viewModel.importModelTemplate(inspection.packageData)
        SharedImportFocus(queueId, SharedImportSection.MODEL, id, "模型模板已导入，请编辑并填写 API Key。")
    }
    is SharedImportInspection.Image,
    is SharedImportInspection.Unknown -> error("该共享内容不能作为资源导入")
}
