package com.example.chatbar.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Matrix
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.data.local.ImageMaskPreferences
import com.example.chatbar.domain.image.ImageProcessingService
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.domain.image.ProcessImageKind
import com.example.chatbar.domain.image.ProcessedImage
import com.example.chatbar.domain.image.ProcessedImageOperation
import com.example.chatbar.domain.image.ImageMetadataStripper
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

private enum class MaskBrushType(val label: String) {
    Mosaic("马赛克"),
    Black("黑色"),
    White("白色")
}

private data class MosaicUndoSnapshot(
    val bitmap: Bitmap,
    val hasVisualChanges: Boolean
)

@Composable
internal fun ImageMosaicEditor(sourcePath: String, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { ImageMaskPreferences(context) }
    val processingService = remember { ImageProcessingService(context.applicationContext) }
    val source = remember(sourcePath) {
        BitmapFactory.decodeFile(sourcePath)?.copy(Bitmap.Config.ARGB_8888, true)
    }
    if (source == null) {
        onDismiss()
        return
    }
    var bitmap by remember(sourcePath) { mutableStateOf(source.copy(Bitmap.Config.ARGB_8888, true)) }
    var revision by remember { mutableIntStateOf(0) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var brushSizeDp by remember { mutableFloatStateOf(preferences.loadBrushSize()) }
    var brushType by remember {
        mutableStateOf(MaskBrushType.entries.firstOrNull { it.name == preferences.loadBrushType() } ?: MaskBrushType.Mosaic)
    }
    var hasVisualChanges by remember(sourcePath) { mutableStateOf(false) }
    var isStrippingMetadata by remember(sourcePath) { mutableStateOf(false) }
    var inspection by remember(sourcePath) { mutableStateOf<ImportedProcessImage?>(null) }
    var inspectionError by remember(sourcePath) { mutableStateOf<String?>(null) }
    var isProcessingApng by remember(sourcePath) { mutableStateOf(false) }
    var processingProgress by remember(sourcePath) { mutableFloatStateOf(0f) }
    var processingError by remember(sourcePath) { mutableStateOf<String?>(null) }
    var processingResult by remember(sourcePath) { mutableStateOf<ProcessedImage?>(null) }
    var processingJob by remember(sourcePath) { mutableStateOf<Job?>(null) }
    val undoStack = remember(sourcePath) { ArrayDeque<MosaicUndoSnapshot>() }
    val busy = isStrippingMetadata || isProcessingApng
    val editingAllowed = inspection?.kind == ProcessImageKind.STATIC

    LaunchedEffect(sourcePath) {
        inspection = null
        inspectionError = null
        runCatching {
            withContext(Dispatchers.IO) { processingService.inspectFile(sourcePath) }
        }.onSuccess {
            inspection = it
        }.onFailure {
            inspectionError = "读取图片类型失败：${it.message ?: "未知错误"}"
        }
    }

    fun pushUndoSnapshot() {
        undoStack.addLast(
            MosaicUndoSnapshot(
                bitmap.copy(Bitmap.Config.ARGB_8888, true),
                hasVisualChanges
            )
        )
        if (undoStack.size > 10) undoStack.removeFirst().bitmap.recycle()
    }

    fun startApngOperation() {
        val currentInspection = inspection ?: return
        if (busy || currentInspection.kind == ProcessImageKind.OTHER_APNG) return
        processingError = null
        processingProgress = 0f
        processingJob = scope.launch {
            isProcessingApng = true
            try {
                val result = when (currentInspection.kind) {
                    ProcessImageKind.STATIC -> {
                        val snapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        try {
                            withContext(Dispatchers.IO) {
                                processingService.createApngDisguise(snapshot) { processingProgress = it }
                            }
                        } finally {
                            snapshot.recycle()
                        }
                    }

                    ProcessImageKind.GIF -> withContext(Dispatchers.IO) {
                        processingService.createApngDisguise(sourcePath) { processingProgress = it }
                    }

                    ProcessImageKind.CHATBAR_DISGUISE_APNG -> withContext(Dispatchers.IO) {
                        processingService.restoreApngDisguise(sourcePath) { processingProgress = it }
                    }

                    ProcessImageKind.OTHER_APNG -> error("仅支持还原由 ChatBar 生成的 APNG 伪装图")
                }
                processingResult = result
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                processingError = error.message ?: "APNG 处理失败"
            } finally {
                isProcessingApng = false
                processingJob = null
            }
        }
    }

    processingResult?.let { result ->
        ApngProcessingResultDialog(
            result = result,
            sourcePath = sourcePath,
            truthBitmap = bitmap,
            sourceKind = inspection?.kind ?: ProcessImageKind.STATIC,
            onDismiss = { processingResult = null },
            onUseResult = {
                processingResult = null
                onComplete(result.path)
            }
        )
    }

    if (processingResult == null) Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = !busy,
            dismissOnClickOutside = !busy
        )
    ) {
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbButton("取消", onDismiss, enabled = !busy, variant = ButtonVariant.Ghost)
                CbText("涂抹需要处理的位置", Modifier.weight(1f), style = ChatBarTheme.typography.heading)
                CbButton("完成", {
                    writeMosaicCopy(File(context.filesDir, "images"), bitmap)?.let(onComplete)
                }, enabled = editingAllowed && !busy)
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(sourcePath, canvasSize, brushSizeDp, brushType, bitmap.width, bitmap.height, editingAllowed, busy) {
                        if (!editingAllowed || busy) return@pointerInput
                        var previousPoint = Offset.Unspecified
                        detectDragGestures(
                            onDragStart = {
                                pushUndoSnapshot()
                                hasVisualChanges = true
                                previousPoint = mapToBitmap(it, canvasSize, bitmap)
                                applyBrush(bitmap, previousPoint, brushRadius(bitmap, canvasSize, brushSizeDp), brushType)
                                revision++
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val currentPoint = mapToBitmap(change.position, canvasSize, bitmap)
                                applyBrushStroke(bitmap, previousPoint, currentPoint, brushRadius(bitmap, canvasSize, brushSizeDp), brushType)
                                previousPoint = currentPoint
                                revision++
                            },
                            onDragEnd = { previousPoint = Offset.Unspecified },
                            onDragCancel = { previousPoint = Offset.Unspecified }
                        )
                    }
                ) {
                    revision
                    drawFittedBitmap(bitmap)
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaskBrushType.entries.forEach { type ->
                        CbChoiceChip(type.label, brushType == type, {
                            brushType = type
                            preferences.saveBrushType(type.name)
                        }, Modifier.weight(1f), enabled = editingAllowed && !busy)
                    }
                }
                CbButton(
                    when {
                        isProcessingApng -> "停止处理"
                        inspection?.kind == ProcessImageKind.CHATBAR_DISGUISE_APNG -> "逆向还原"
                        else -> "APNG伪装"
                    },
                    {
                        if (isProcessingApng) {
                            processingJob?.cancel(CancellationException("用户停止 APNG 处理"))
                        } else {
                            startApngOperation()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = inspection != null && inspection?.kind != ProcessImageKind.OTHER_APNG && !isStrippingMetadata,
                    variant = ButtonVariant.Secondary
                )
                if (isProcessingApng) {
                    CbProgress(processingProgress)
                    CbText(
                        "正在${if (inspection?.canRestoreApng == true) "还原" else "生成"} APNG… ${(processingProgress * 100).toInt()}%",
                        color = ChatBarTheme.colors.primary,
                        style = ChatBarTheme.typography.caption
                    )
                }
                CbButton(
                    "旋转 90°",
                    {
                        pushUndoSnapshot()
                        bitmap = rotateBitmap90(bitmap)
                        hasVisualChanges = true
                        revision++
                    },
                    Modifier.fillMaxWidth(),
                    enabled = editingAllowed && !busy,
                    variant = ButtonVariant.Outline
                )
                CbButton(
                    if (isStrippingMetadata) "正在去除图片元数据…" else "去除图片元数据，但不更改图片",
                    {
                        val shouldPreserveCurrentBitmap = hasVisualChanges
                        val currentBitmap = bitmap
                        isStrippingMetadata = true
                        scope.launch {
                            try {
                                val outputPath = if (shouldPreserveCurrentBitmap) {
                                    val snapshot = currentBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    try {
                                        withContext(Dispatchers.IO) {
                                            writeMosaicCopy(
                                                directory = File(context.filesDir, "images"),
                                                bitmap = snapshot,
                                                filePrefix = "metadata_stripped"
                                            ) ?: error("无法保存无元数据图片")
                                        }
                                    } finally {
                                        snapshot.recycle()
                                    }
                                } else {
                                    withContext(Dispatchers.IO) {
                                        ImageMetadataStripper.stripToCopy(
                                            source = File(sourcePath),
                                            outputDirectory = File(context.filesDir, "images")
                                        ).absolutePath
                                    }
                                }
                                Toast.makeText(context, "已生成无元数据副本，图片画面未更改", Toast.LENGTH_SHORT).show()
                                onComplete(outputPath)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Toast.makeText(
                                    context,
                                    "去除图片元数据失败：${error.message ?: "未知错误"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isStrippingMetadata = false
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    enabled = editingAllowed && !busy,
                    variant = ButtonVariant.Outline
                )
                CbText(
                    when (inspection?.kind) {
                        ProcessImageKind.STATIC -> "APNG伪装会生成品牌 Logo 封面；支持动画的查看器点开后显示当前编辑画面。"
                        ProcessImageKind.GIF -> "GIF 会完整保留帧、时序和循环；为避免丢失动画，涂抹、旋转和去元数据已禁用。"
                        ProcessImageKind.CHATBAR_DISGUISE_APNG -> "已识别 ChatBar APNG伪装；逆向还原会移除 Logo，静态输出 PNG，动态输出无伪装 APNG。"
                        ProcessImageKind.OTHER_APNG -> "此 APNG 缺少有效 ChatBar 标记，不能伪装或逆向还原。"
                        null -> inspectionError ?: "正在检查图片类型…"
                    },
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.label
                )
                processingError?.let { error ->
                    CbText(error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CbText("笔刷 ${brushSizeDp.toInt()}dp", style = ChatBarTheme.typography.label)
                    CbSlider(
                        value = brushSizeDp,
                        onValueChange = {
                            brushSizeDp = it
                            preferences.saveBrushSize(it)
                        },
                        valueRange = 16f..72f,
                        modifier = Modifier.weight(1f),
                        contentDescription = "笔刷尺寸",
                        enabled = editingAllowed && !busy
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("撤销", {
                        if (undoStack.isNotEmpty()) {
                            val snapshot = undoStack.removeLast()
                            bitmap = snapshot.bitmap
                            hasVisualChanges = snapshot.hasVisualChanges
                            revision++
                        }
                    }, Modifier.weight(1f), variant = ButtonVariant.Secondary, enabled = editingAllowed && undoStack.isNotEmpty() && !busy)
                    CbButton("重置", {
                        undoStack.forEach { it.bitmap.recycle() }
                        undoStack.clear()
                        bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                        hasVisualChanges = false
                        revision++
                    }, Modifier.weight(1f), variant = ButtonVariant.Outline, enabled = editingAllowed && !busy)
                }
            }
        }
    }
}

@Composable
private fun ApngProcessingResultDialog(
    result: ProcessedImage,
    sourcePath: String,
    truthBitmap: Bitmap,
    sourceKind: ProcessImageKind,
    onDismiss: () -> Unit,
    onUseResult: () -> Unit
) {
    val context = LocalContext.current
    val isDisguise = result.operation == ProcessedImageOperation.APNG_DISGUISE
    CbDialog(
        onDismissRequest = onDismiss,
        title = if (isDisguise) "APNG伪装已生成" else "逆向还原完成",
        modifier = Modifier.heightIn(max = 760.dp),
        dismiss = { CbButton("继续编辑", onDismiss, variant = ButtonVariant.Ghost) },
        confirm = { CbButton("使用结果", onUseResult) }
    ) {
        Column(
            Modifier
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isDisguise) {
                ApngPreviewCard("聊天默认画面") {
                    AsyncImage(
                        model = File(result.path),
                        contentDescription = "APNG Logo 封面",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                ApngPreviewCard("点开后内容") {
                    if (sourceKind == ProcessImageKind.GIF) {
                        AsyncImage(
                            model = File(sourcePath),
                            contentDescription = "APNG 真图动画模拟",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Image(
                            bitmap = truthBitmap.asImageBitmap(),
                            contentDescription = "APNG 真图画面模拟",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            } else {
                ApngPreviewCard("还原结果") {
                    AsyncImage(
                        model = File(result.path),
                        contentDescription = "APNG 还原结果",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            val formatLabel = if (isDisguise || result.isAnimated) "APNG" else "PNG"
            CbText(
                "${result.width} × ${result.height} · ${result.frameCount} 个真图帧 · $formatLabel",
                color = ChatBarTheme.colors.mutedForeground,
                style = ChatBarTheme.typography.caption
            )
            if (result.isAnimated) {
                CbText(
                    "ChatBar 仅显示动画默认帧；实际播放效果由 QQ 等目标应用决定。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.caption
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CbButton(
                    "保存到相册",
                    { saveImageToGallery(context = context, path = result.path) },
                    Modifier.weight(1f)
                )
                CbButton(
                    "直接分享",
                    { shareFromImageLongPress(context, result.path) },
                    Modifier.weight(1f),
                    variant = ButtonVariant.Secondary
                )
            }
        }
    }
}

@Composable
private fun ApngPreviewCard(title: String, content: @Composable () -> Unit) {
    CbSurface(Modifier.fillMaxWidth(), color = ChatBarTheme.colors.muted) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CbText(title, style = ChatBarTheme.typography.label)
            content()
        }
    }
}

private fun mapToBitmap(point: Offset, size: IntSize, bitmap: Bitmap): Offset {
    if (size.width == 0 || size.height == 0) return Offset.Unspecified
    val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
    return Offset(
        (point.x - (size.width - bitmap.width * scale) / 2f) / scale,
        (point.y - (size.height - bitmap.height * scale) / 2f) / scale
    )
}

private fun brushRadius(bitmap: Bitmap, size: IntSize, brushSizeDp: Float): Float {
    if (size.width == 0 || size.height == 0) return 1f
    return brushSizeDp / 2f / min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
}

private fun applyBrush(bitmap: Bitmap, point: Offset, radius: Float, type: MaskBrushType) {
    if (point == Offset.Unspecified || point.x !in 0f..bitmap.width.toFloat() || point.y !in 0f..bitmap.height.toFloat()) return
    if (type != MaskBrushType.Mosaic) {
        AndroidCanvas(bitmap).drawCircle(
            point.x,
            point.y,
            radius,
            Paint().apply { color = if (type == MaskBrushType.Black) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        )
        return
    }
    val block = (radius / 3f).toInt().coerceAtLeast(6)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint()
    val left = floor((point.x - radius) / block).toInt() * block
    val top = floor((point.y - radius) / block).toInt() * block
    val right = ceil((point.x + radius) / block).toInt() * block
    val bottom = ceil((point.y + radius) / block).toInt() * block
    for (y in top until bottom step block) for (x in left until right step block) {
        val cx = x + block / 2f
        val cy = y + block / 2f
        if ((cx - point.x) * (cx - point.x) + (cy - point.y) * (cy - point.y) > radius * radius) continue
        paint.color = bitmap.getPixel(cx.toInt().coerceIn(0, bitmap.width - 1), cy.toInt().coerceIn(0, bitmap.height - 1))
        canvas.drawRect(x.coerceAtLeast(0).toFloat(), y.coerceAtLeast(0).toFloat(), (x + block).coerceAtMost(bitmap.width).toFloat(), (y + block).coerceAtMost(bitmap.height).toFloat(), paint)
    }
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap = Bitmap.createBitmap(
    bitmap,
    0,
    0,
    bitmap.width,
    bitmap.height,
    Matrix().apply { postRotate(90f) },
    true
).copy(Bitmap.Config.ARGB_8888, true)

private fun applyBrushStroke(bitmap: Bitmap, from: Offset, to: Offset, radius: Float, type: MaskBrushType) {
    if (from == Offset.Unspecified || to == Offset.Unspecified) return
    val distance = hypot(to.x - from.x, to.y - from.y)
    val steps = ceil(distance / (radius * 0.35f).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
    for (index in 1..steps) {
        val fraction = index.toFloat() / steps
        applyBrush(
            bitmap,
            Offset(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction),
            radius,
            type
        )
    }
}

private fun DrawScope.drawFittedBitmap(bitmap: Bitmap) {
    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
    val width = (bitmap.width * scale).toInt()
    val height = (bitmap.height * scale).toInt()
    drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(((size.width - width) / 2).toInt(), ((size.height - height) / 2).toInt()), dstSize = IntSize(width, height))
}

private fun writeMosaicCopy(
    directory: File,
    bitmap: Bitmap,
    filePrefix: String = "mosaic"
): String? = runCatching {
    directory.mkdirs()
    val target = File(directory, "${filePrefix}_${System.currentTimeMillis()}.png")
    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    target.absolutePath
}.getOrNull()
