package com.example.chatbar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbText
import java.io.File

data class ImagePreviewItem(
    val messageId: String,
    val path: String
)

private data class RemovedImagePreviewItemKey(val index: Int)

@Composable
fun ImagePreviewDialog(
    path: String,
    onDismiss: () -> Unit,
    onSetCardAvatar: (() -> Unit)? = null,
    onSetCardBackground: (() -> Unit)? = null
) {
    ImagePreviewDialog(
        items = listOf(ImagePreviewItem(messageId = "", path = path)),
        initialIndex = 0,
        onDismiss = onDismiss,
        onSetCardAvatar = onSetCardAvatar?.let { action -> { action() } },
        onSetCardBackground = onSetCardBackground?.let { action -> { action() } }
    )
}

@Composable
fun ImagePreviewDialog(
    items: List<ImagePreviewItem>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onLocateMessage: ((String) -> Unit)? = null,
    onSetCardAvatar: ((String) -> Unit)? = null,
    onSetCardBackground: ((String) -> Unit)? = null,
    onPageChanged: ((Int) -> Unit)? = null
) {
    if (items.isEmpty()) return
    val safeInitialIndex = initialIndex.coerceIn(items.indices)
    val pagerState = rememberPagerState(initialPage = safeInitialIndex) { items.size }
    val currentItem = items[pagerState.currentPage.coerceIn(items.indices)]
    var showImageActions by remember { mutableStateOf(false) }
    var mosaicSourcePath by remember { mutableStateOf<String?>(null) }
    val editedPaths = remember { mutableStateMapOf<String, String>() }
    val context = LocalContext.current
    val actionPath = editedPaths[currentItem.path] ?: currentItem.path

    LaunchedEffect(items.size) {
        if (pagerState.currentPage !in items.indices) pagerState.scrollToPage(items.lastIndex)
    }
    LaunchedEffect(pagerState.currentPage) {
        onPageChanged?.invoke(pagerState.currentPage)
    }

    if (showImageActions) {
        CbDialog(
            onDismissRequest = { showImageActions = false },
            title = "图片操作",
            dismiss = { CbButton("取消", { showImageActions = false }, variant = ButtonVariant.Ghost) }
        ) {
            CbButton(
                if (editedPaths.containsKey(currentItem.path)) "继续打码" else "打码",
                {
                    showImageActions = false
                    mosaicSourcePath = actionPath
                },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary
            )
            Spacer(Modifier.size(8.dp))
            CbButton(
                "保存图片",
                {
                    showImageActions = false
                    saveImageToGallery(context, actionPath)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(8.dp))
            CbButton(
                "分享图片",
                {
                    showImageActions = false
                    shareFromImageLongPress(context, actionPath)
                },
                modifier = Modifier.fillMaxWidth(),
                variant = ButtonVariant.Secondary
            )
            if (onSetCardAvatar != null) {
                Spacer(Modifier.size(8.dp))
                CbButton(
                    "替换为头像",
                    {
                        showImageActions = false
                        onSetCardAvatar(actionPath)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Secondary
                )
            }
            if (onSetCardBackground != null) {
                Spacer(Modifier.size(8.dp))
                CbButton(
                    "替换为背景",
                    {
                        showImageActions = false
                        onSetCardBackground(actionPath)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Secondary
                )
            }
            if (onLocateMessage != null) {
                Spacer(Modifier.size(8.dp))
                CbButton(
                    "定位消息",
                    {
                        showImageActions = false
                        onLocateMessage(currentItem.messageId)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Secondary
                )
            }
        }
    }

    mosaicSourcePath?.let { sourcePath ->
        ImageMosaicEditor(
            sourcePath = sourcePath,
            onDismiss = {
                mosaicSourcePath = null
                showImageActions = true
            },
            onComplete = { outputPath ->
                editedPaths[currentItem.path] = outputPath
                mosaicSourcePath = null
                showImageActions = true
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 0,
                key = { page ->
                    items.getOrNull(page)?.let { item ->
                        item.messageId + "\u0000" + item.path
                    } ?: RemovedImagePreviewItemKey(page)
                }
            ) { page ->
                items.getOrNull(page)?.takeIf { page == pagerState.currentPage }?.let { item ->
                    ZoomablePreviewImage(
                        path = editedPaths[item.path] ?: item.path,
                        onLongPress = { showImageActions = true }
                    )
                }
            }
            CbIconButton(
                AppIcons.Close,
                "关闭大图",
                onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                tint = Color.White
            )
            CbText(
                text = "${pagerState.currentPage + 1}/${items.size}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .background(Color.Black.copy(alpha = 0.45f), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = Color.White.copy(alpha = 0.82f)
            )
        }
    }
}

@Composable
private fun ZoomablePreviewImage(path: String, onLongPress: () -> Unit) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale == 1f) Offset.Zero else offset + panChange
    }
    val context = LocalContext.current
    val runtime = LocalChatImageRenderRuntime.current
    val imageLoader = runtime.imageLoader ?: context.imageLoader
    val cacheKey = remember(path) { "chat-preview:$path" }
    val request = remember(path, context) {
        val metrics = context.resources.displayMetrics
        ImageRequest.Builder(context)
            .data(File(path))
            .size(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
            .memoryCacheKey(cacheKey)
            .diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED)
            .crossfade(false)
            .build()
    }
    DisposableEffect(imageLoader, cacheKey) {
        onDispose { imageLoader.memoryCache?.remove(MemoryCache.Key(cacheKey)) }
    }
    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = "查看大图",
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .pointerInput(path) { detectTapGestures(onLongPress = { onLongPress() }) }
            .transformable(
                state = transformState,
                canPan = { scale > 1f }
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit
    )
}
