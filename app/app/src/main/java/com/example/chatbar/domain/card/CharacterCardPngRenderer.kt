package com.example.chatbar.domain.card

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.example.chatbar.R
import com.example.chatbar.data.local.entity.CharacterCard
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

data class CharacterCardPngExportOptions(
    val sizePx: Int = 1536,
    val gradientHeight: Float = 0.42f,
    val gradientStrength: Float = 0.72f,
    val logoScale: Float = 0.095f,
    val titleScale: Float = 0.052f,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f,
    val cropZoom: Float = 1f
) {
    fun normalized(): CharacterCardPngExportOptions = copy(
        sizePx = sizePx.coerceIn(1024, 2048),
        gradientHeight = gradientHeight.coerceIn(0.25f, 0.68f),
        gradientStrength = gradientStrength.coerceIn(0.45f, 0.9f),
        logoScale = logoScale.coerceIn(0.07f, 0.14f),
        titleScale = titleScale.coerceIn(0.04f, 0.08f),
        cropCenterX = cropCenterX.coerceIn(0f, 1f),
        cropCenterY = cropCenterY.coerceIn(0f, 1f),
        cropZoom = cropZoom.coerceIn(1f, 6f)
    )
}

object CharacterCardPngRenderer {
    fun render(context: Context, card: CharacterCard, options: CharacterCardPngExportOptions): ByteArray {
        val normalized = options.normalized()
        val size = normalized.sizePx
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = card.chatBackground
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }

        if (background != null) {
            drawCover(canvas, background, size, normalized)
            background.recycle()
        } else {
            drawFallbackBackground(canvas, size)
        }
        drawBottomGradient(canvas, size, normalized)
        drawBrandRow(context, canvas, size, card.name.ifBlank { "未命名角色" }, normalized)

        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun drawCover(
        canvas: Canvas,
        source: Bitmap,
        size: Int,
        options: CharacterCardPngExportOptions
    ) {
        val scale = max(size.toFloat() / source.width, size.toFloat() / source.height) * options.cropZoom
        val cropWidth = (size / scale).roundToInt().coerceIn(1, source.width)
        val cropHeight = (size / scale).roundToInt().coerceIn(1, source.height)
        val left = (source.width * options.cropCenterX - cropWidth / 2f)
            .roundToInt()
            .coerceIn(0, source.width - cropWidth)
        val top = (source.height * options.cropCenterY - cropHeight / 2f)
            .roundToInt()
            .coerceIn(0, source.height - cropHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            source,
            Rect(left, top, left + cropWidth, top + cropHeight),
            RectF(0f, 0f, size.toFloat(), size.toFloat()),
            paint
        )
    }

    private fun drawFallbackBackground(canvas: Canvas, size: Int) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                intArrayOf(
                    Color.rgb(18, 24, 29),
                    Color.rgb(45, 96, 88),
                    Color.rgb(10, 14, 18)
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), fill)
        val veil = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
        val stripeHeight = size * 0.12f
        canvas.save()
        canvas.rotate(-18f, size / 2f, size / 2f)
        canvas.drawRect(-size * 0.2f, size * 0.22f, size * 1.2f, size * 0.22f + stripeHeight, veil)
        canvas.restore()
    }

    private fun drawBottomGradient(canvas: Canvas, size: Int, options: CharacterCardPngExportOptions) {
        val startY = size * (1f - options.gradientHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                startY,
                0f,
                size.toFloat(),
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((255 * options.gradientStrength).roundToInt(), 0, 0, 0)
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, startY, size.toFloat(), size.toFloat(), paint)
    }

    private fun drawBrandRow(
        context: Context,
        canvas: Canvas,
        size: Int,
        title: String,
        options: CharacterCardPngExportOptions
    ) {
        val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        val margin = (size * 0.055f).roundToInt()
        val gap = (size * 0.024f).roundToInt()
        val logoSize = (size * options.logoScale).roundToInt()
        val textWidth = (size - margin * 2 - logoSize - gap).coerceAtLeast(size / 3)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * options.titleScale
            typeface = titleTypeface(context)
            setShadowLayer(size * 0.008f, 0f, size * 0.003f, Color.argb(210, 0, 0, 0))
        }
        val layout = StaticLayout.Builder
            .obtain(title, 0, title.length, textPaint, textWidth)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        val rowHeight = max(logoSize, layout.height)
        val rowTop = size - margin - rowHeight
        val logoTop = rowTop + (rowHeight - logoSize) / 2f
        val logoLeft = margin.toFloat()
        val plateInset = logoSize * 0.13f
        val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(82, 0, 0, 0) }
        canvas.drawRoundRect(
            RectF(
                logoLeft - plateInset,
                logoTop - plateInset,
                logoLeft + logoSize + plateInset,
                logoTop + logoSize + plateInset
            ),
            logoSize * 0.18f,
            logoSize * 0.18f,
            platePaint
        )
        if (logo != null) {
            canvas.drawBitmap(
                logo,
                Rect(0, 0, logo.width, logo.height),
                RectF(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
            logo.recycle()
        }
        canvas.save()
        canvas.translate((margin + logoSize + gap).toFloat(), rowTop + (rowHeight - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun titleTypeface(context: Context): Typeface =
        runCatching { context.resources.getFont(R.font.xiaolang_tianqiong) }
            .getOrElse { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
}
