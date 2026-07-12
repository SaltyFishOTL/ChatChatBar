package com.example.chatbar.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbChoiceChip
import com.example.chatbar.ui.kit.CbSlider
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.data.local.ImageMaskPreferences
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

private enum class MaskBrushType(val label: String) {
    Mosaic("马赛克"),
    Black("黑色"),
    White("白色")
}

@Composable
internal fun ImageMosaicEditor(sourcePath: String, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { ImageMaskPreferences(context) }
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
    val undoStack = remember(sourcePath) { ArrayDeque<Bitmap>() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(ChatBarTheme.colors.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbButton("取消", onDismiss, variant = ButtonVariant.Ghost)
                CbText("涂抹需要处理的位置", Modifier.weight(1f), style = ChatBarTheme.typography.heading)
                CbButton("完成", {
                    writeMosaicCopy(File(context.filesDir, "images"), bitmap)?.let(onComplete)
                })
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                Canvas(
                    Modifier.fillMaxSize().onSizeChanged { canvasSize = it }.pointerInput(sourcePath, canvasSize, brushSizeDp, brushType, bitmap.width, bitmap.height) {
                        var previousPoint = Offset.Unspecified
                        detectDragGestures(
                            onDragStart = {
                                undoStack.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                                if (undoStack.size > 10) undoStack.removeFirst().recycle()
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
                        }, Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton(
                        "应用全图 AI 贴片",
                        {
                            undoStack.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                            if (undoStack.size > 10) undoStack.removeFirst().recycle()
                            applyFullImageAdversarialPatch(bitmap)
                            revision++
                        },
                        Modifier.weight(1f),
                        variant = ButtonVariant.Secondary
                    )
                    CbButton(
                        "旋转 90°",
                        {
                            undoStack.addLast(bitmap.copy(Bitmap.Config.ARGB_8888, true))
                            if (undoStack.size > 10) undoStack.removeFirst().recycle()
                            bitmap = rotateBitmap90(bitmap)
                            revision++
                        },
                        Modifier.weight(1f),
                        variant = ButtonVariant.Outline
                    )
                }
                CbText(
                    "实验性中等强度色度扰动：兼顾人眼可读性与干扰强度，可能被压缩或缩放削弱，不保证对所有模型有效。",
                    color = ChatBarTheme.colors.mutedForeground,
                    style = ChatBarTheme.typography.label
                )
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
                        contentDescription = "笔刷尺寸"
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CbButton("撤销", {
                        if (undoStack.isNotEmpty()) bitmap = undoStack.removeLast().also { revision++ }
                    }, Modifier.weight(1f), variant = ButtonVariant.Secondary, enabled = undoStack.isNotEmpty())
                    CbButton("重置", {
                        undoStack.forEach(Bitmap::recycle)
                        undoStack.clear()
                        bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                        revision++
                    }, Modifier.weight(1f), variant = ButtonVariant.Outline)
                }
            }
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

private fun applyFullImageAdversarialPatch(bitmap: Bitmap) {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        val index = y * bitmap.width + x
        val color = pixels[index]
        val hash = (x / 3) * 73856093 xor (y / 3) * 19349663
        val delta = ADVERSARIAL_CHROMA_DELTAS[Math.floorMod(hash, ADVERSARIAL_CHROMA_DELTAS.size)]
        val red = ((color shr 16) and 0xff) + delta[0]
        val green = ((color shr 8) and 0xff) + delta[1]
        val blue = (color and 0xff) + delta[2]
        pixels[index] = (color and -0x1000000) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255)
    }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
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

private val ADVERSARIAL_CHROMA_DELTAS = arrayOf(
    intArrayOf(24, -12, -12),
    intArrayOf(-24, 12, 12),
    intArrayOf(-12, 24, -12),
    intArrayOf(12, -24, 12),
    intArrayOf(-12, -12, 24),
    intArrayOf(12, 12, -24)
)

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

private fun writeMosaicCopy(directory: File, bitmap: Bitmap): String? = runCatching {
    directory.mkdirs()
    val target = File(directory, "mosaic_${System.currentTimeMillis()}.png")
    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    target.absolutePath
}.getOrNull()
