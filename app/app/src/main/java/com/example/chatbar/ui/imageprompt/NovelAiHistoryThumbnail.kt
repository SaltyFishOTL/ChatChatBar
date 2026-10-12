package com.example.chatbar.ui.imageprompt

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File

internal fun historyThumbnailRequest(context: Context, path: String, edgePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(File(path))
        .size(edgePx.coerceAtLeast(1))
        .scale(Scale.FILL)
        .precision(Precision.EXACT)
        .memoryCacheKey("studio-history-thumbnail:$edgePx:$path")
        .diskCachePolicy(CachePolicy.DISABLED)
        .networkCachePolicy(CachePolicy.DISABLED)
        .crossfade(false)
        .build()

@Composable
internal fun NovelAiHistoryThumbnail(
    path: String,
    contentDescription: String,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier.background(ChatBarTheme.colors.surfaceSubtle),
        contentAlignment = Alignment.Center
    ) {
        val edgePx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val request = remember(context, path, edgePx) { historyThumbnailRequest(context, path, edgePx) }
        var failed by remember(path, edgePx) { mutableStateOf(false) }
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onLoading = { failed = false },
            onSuccess = { failed = false },
            onError = {
                failed = true
                Log.w("NovelAiHistoryThumbnail", "History thumbnail decode failed", it.result.throwable)
            }
        )
        if (failed) {
            CbText("图片加载失败", color = ChatBarTheme.colors.warning, style = ChatBarTheme.typography.caption)
        }
    }
}
