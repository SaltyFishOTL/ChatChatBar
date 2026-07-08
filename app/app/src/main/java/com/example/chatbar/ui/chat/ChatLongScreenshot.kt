package com.example.chatbar.ui.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.ui.components.ChatBubble
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class ChatLongScreenshotRequest(
    val title: String,
    val messages: List<ChatMessage>,
    val backgroundPath: String?,
    val widthPx: Int,
    val fontScale: Float,
    val fileName: String
)

const val CHAT_LONG_SCREENSHOT_SELECTION_HEIGHT_LIMIT_PX = 32_000

suspend fun measureChatLongScreenshotHeight(
    context: Context,
    request: ChatLongScreenshotRequest
): Int = withContext(Dispatchers.Main) {
    if (request.messages.isEmpty()) return@withContext 0
    val widthPx = request.widthPx.coerceAtLeast(320)
    val activity = context.findActivity() ?: error("无法获取当前窗口")
    val root = activity.window.decorView as? ViewGroup ?: error("无法获取当前窗口")
    val composeView = createLongScreenshotComposeView(activity, request.copy(widthPx = widthPx))
    root.addView(
        composeView,
        ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    try {
        delay(120)
        measureLongScreenshotView(composeView, widthPx)
        delay(80)
        measureLongScreenshotView(composeView, widthPx)
        composeView.measuredHeight
    } finally {
        root.removeView(composeView)
    }
}

suspend fun renderChatLongScreenshot(
    context: Context,
    request: ChatLongScreenshotRequest
): File = withContext(Dispatchers.Main) {
    require(request.messages.isNotEmpty()) { "请选择至少一条消息" }
    val widthPx = request.widthPx.coerceAtLeast(320)
    val activity = context.findActivity() ?: error("无法获取当前窗口")
    val root = activity.window.decorView as? ViewGroup ?: error("无法获取当前窗口")
    val composeView = createLongScreenshotComposeView(activity, request.copy(widthPx = widthPx))
    root.addView(
        composeView,
        ViewGroup.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    try {
        delay(120)
        measureLongScreenshotView(composeView, widthPx)
        delay(80)
        measureLongScreenshotView(composeView, widthPx)
        val height = composeView.measuredHeight
        val renderPlan = createLongScreenshotRenderPlan(widthPx, height)
        val bitmap = drawLongScreenshotBitmap(composeView, renderPlan)
        try {
            writeLongScreenshotBitmap(context, request.fileName, bitmap)
        } finally {
            bitmap.recycle()
        }
    } finally {
        root.removeView(composeView)
    }
}

private fun createLongScreenshotComposeView(
    activity: Activity,
    request: ChatLongScreenshotRequest
): ComposeView =
    ComposeView(activity).apply {
        translationX = -request.widthPx * 2f
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent {
            ChatBarTheme {
                ChatLongScreenshotContent(request)
            }
        }
    }

@Composable
private fun ChatLongScreenshotContent(request: ChatLongScreenshotRequest) {
    val widthDp = with(LocalDensity.current) { request.widthPx.toDp() }
    val backgroundBitmap = remember(request.backgroundPath) {
        request.backgroundPath
            ?.takeIf { it.isNotBlank() }
            ?.let(BitmapFactory::decodeFile)
            ?.asImageBitmap()
    }
    val backgroundColor = ChatBarTheme.colors.background
    Column(
        modifier = Modifier
            .width(widthDp)
            .background(backgroundColor)
            .drawBehind {
                drawRect(backgroundColor)
                if (backgroundBitmap != null) {
                    drawRepeatedWidthFitBackground(backgroundBitmap)
                    drawRect(backgroundColor.copy(alpha = 0.84f))
                }
            }
    ) {
        CbTopBar(
            title = request.title,
            statusBarInset = false,
            navigation = {},
            actions = {}
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            request.messages.forEach { message ->
                ChatBubble(
                    message = message,
                    fontScale = request.fontScale,
                    showActions = false,
                    exportMode = true
                )
            }
        }
    }
}

private fun DrawScope.drawRepeatedWidthFitBackground(
    image: androidx.compose.ui.graphics.ImageBitmap
) {
    if (image.width <= 0 || image.height <= 0) return
    val tileWidth = size.width.roundToInt().coerceAtLeast(1)
    val tileHeight = (image.height * (size.width / image.width)).roundToInt().coerceAtLeast(1)
    var y = 0
    while (y < size.height) {
        drawImage(
            image = image,
            dstOffset = IntOffset(0, y),
            dstSize = IntSize(tileWidth, tileHeight)
        )
        y += tileHeight
    }
}

private fun measureLongScreenshotView(view: ComposeView, widthPx: Int) {
    val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    view.measure(widthSpec, heightSpec)
    view.layout(0, 0, widthPx, view.measuredHeight)
}

private data class LongScreenshotRenderPlan(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
    val config: Bitmap.Config
)

private fun createLongScreenshotRenderPlan(widthPx: Int, heightPx: Int): LongScreenshotRenderPlan {
    require(heightPx > 0) { "长截图生成失败" }
    val heightScale = (MAX_LONG_SCREENSHOT_OUTPUT_HEIGHT_PX.toFloat() / heightPx.toFloat()).coerceAtMost(1f)
    val argbScale = scaleForMaxBytes(widthPx, heightPx, ARGB_8888_BYTES_PER_PIXEL, MAX_LONG_SCREENSHOT_ARGB_8888_BYTES)
    buildRenderPlan(widthPx, heightPx, minOf(heightScale, argbScale), Bitmap.Config.ARGB_8888)?.let {
        return it
    }

    val rgb565Scale = scaleForMaxBytes(widthPx, heightPx, RGB_565_BYTES_PER_PIXEL, MAX_LONG_SCREENSHOT_RGB_565_BYTES)
    buildRenderPlan(widthPx, heightPx, minOf(heightScale, rgb565Scale), Bitmap.Config.RGB_565)?.let {
        return it
    }

    error(
        "长截图原始高度 ${heightPx}px 超过自动压缩上限 " +
            "${(MAX_LONG_SCREENSHOT_OUTPUT_HEIGHT_PX / MIN_LONG_SCREENSHOT_SCALE).roundToInt()}px，请减少选择消息数量"
    )
}

private fun buildRenderPlan(
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    scale: Float,
    config: Bitmap.Config
): LongScreenshotRenderPlan? {
    if (scale < MIN_LONG_SCREENSHOT_SCALE) return null
    val outputHeight = (sourceHeightPx * scale).roundToInt()
        .coerceIn(1, MAX_LONG_SCREENSHOT_OUTPUT_HEIGHT_PX)
    val outputScale = outputHeight.toFloat() / sourceHeightPx.toFloat()
    if (outputScale < MIN_LONG_SCREENSHOT_SCALE) return null
    val outputWidth = (sourceWidthPx * outputScale).roundToInt().coerceAtLeast(1)
    val bytesPerPixel = if (config == Bitmap.Config.RGB_565) RGB_565_BYTES_PER_PIXEL else ARGB_8888_BYTES_PER_PIXEL
    val maxBytes = if (config == Bitmap.Config.RGB_565) MAX_LONG_SCREENSHOT_RGB_565_BYTES else MAX_LONG_SCREENSHOT_ARGB_8888_BYTES
    val byteCount = estimatedLongScreenshotBytes(outputWidth, outputHeight, bytesPerPixel)
    if (byteCount > maxBytes) return null
    return LongScreenshotRenderPlan(outputWidth, outputHeight, outputScale, config)
}

private fun scaleForMaxBytes(widthPx: Int, heightPx: Int, bytesPerPixel: Long, maxBytes: Long): Float {
    val sourceBytes = estimatedLongScreenshotBytes(widthPx, heightPx, bytesPerPixel)
    if (sourceBytes <= maxBytes) return 1f
    return sqrt(maxBytes.toDouble() / sourceBytes.toDouble()).toFloat().coerceAtMost(1f)
}

private fun drawLongScreenshotBitmap(view: View, plan: LongScreenshotRenderPlan): Bitmap {
    val bitmap = Bitmap.createBitmap(plan.widthPx, plan.heightPx, plan.config)
    Canvas(bitmap).apply {
        scale(plan.scale, plan.scale)
        view.draw(this)
    }
    return bitmap
}

private fun estimatedLongScreenshotBytes(widthPx: Int, heightPx: Int, bytesPerPixel: Long): Long =
    widthPx.toLong() * heightPx.toLong() * bytesPerPixel

private suspend fun writeLongScreenshotBitmap(
    context: Context,
    fileName: String,
    bitmap: Bitmap
): File = withContext(Dispatchers.IO) {
    val directory = File(context.filesDir, "images/screenshots").also { it.mkdirs() }
    val file = File(directory, fileName)
    file.outputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "长截图保存失败" }
    }
    file
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val MAX_LONG_SCREENSHOT_OUTPUT_HEIGHT_PX = 32_000
private const val MAX_LONG_SCREENSHOT_ARGB_8888_BYTES = 96L * 1024L * 1024L
private const val MAX_LONG_SCREENSHOT_RGB_565_BYTES = 128L * 1024L * 1024L
private const val ARGB_8888_BYTES_PER_PIXEL = 4L
private const val RGB_565_BYTES_PER_PIXEL = 2L
private const val MIN_LONG_SCREENSHOT_SCALE = 0.4f
