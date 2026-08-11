package com.example.chatbar.ui.imageprompt

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.chatbar.domain.image.FullImagePatchOperation
import com.example.chatbar.ui.components.saveImageToGallery
import com.example.chatbar.ui.components.shareImage
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbProgress
import com.example.chatbar.ui.kit.CbSpinner
import com.example.chatbar.ui.kit.CbSurface
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarShape
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File

@Composable
internal fun ImageProcessingPage(
    state: ImageProcessingUiState,
    onSelectImage: (Uri) -> Unit,
    onProcess: (FullImagePatchOperation) -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onSelectImage)
    }

    LaunchedEffect(state.phase, state.result?.path, state.error) {
        if (state.source != null &&
            (state.phase == ImageProcessingPhase.PROCESSING || state.result != null || state.error != null)
        ) {
            listState.animateScrollToItem(2)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CbSurface(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, ChatBarTheme.colors.border)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CbText("上传待处理图片", style = ChatBarTheme.typography.heading)
                    CbText(
                        "支持 PNG、JPEG、WebP 和 GIF。文件只复制到应用处理区，不会自动保存到相册。",
                        color = ChatBarTheme.colors.mutedForeground,
                        style = ChatBarTheme.typography.caption
                    )
                    CbButton(
                        if (state.source == null) "选择图片" else "更换图片",
                        { picker.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isBusy,
                        variant = ButtonVariant.Outline
                    )
                }
            }
        }

        state.source?.let { source ->
            item {
                CbSurface(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ChatBarTheme.colors.border)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CbText("原图", style = ChatBarTheme.typography.heading)
                        AsyncImage(
                            model = File(source.path),
                            imageLoader = imageLoader,
                            contentDescription = "待处理图片",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(ChatBarShape.sm)),
                            contentScale = ContentScale.Fit
                        )
                        CbText(
                            source.description(),
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CbButton(
                                if (
                                    state.phase == ImageProcessingPhase.PROCESSING &&
                                    state.lastOperation == FullImagePatchOperation.Apply
                                ) {
                                    "正在应用…"
                                } else {
                                    "应用全图贴片"
                                },
                                { onProcess(FullImagePatchOperation.Apply) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy
                            )
                            CbButton(
                                if (
                                    state.phase == ImageProcessingPhase.PROCESSING &&
                                    state.lastOperation == FullImagePatchOperation.Restore
                                ) {
                                    "正在还原…"
                                } else {
                                    "还原全图贴片"
                                },
                                { onProcess(FullImagePatchOperation.Restore) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.isBusy,
                                variant = ButtonVariant.Secondary
                            )
                        }
                        if (state.phase == ImageProcessingPhase.FINISHED && state.result != null) {
                            CbText(
                                if (state.lastOperation == FullImagePatchOperation.Apply) {
                                    "贴片已应用，处理结果显示在下方。"
                                } else {
                                    "贴片已还原，处理结果显示在下方。"
                                },
                                color = ChatBarTheme.colors.primary,
                                style = ChatBarTheme.typography.label
                            )
                        }
                        CbText(
                            "还原会抵消同版贴片。图片若经过压缩、缩放或二次编辑，可能只能近似恢复。GIF 会逐帧处理。",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                    }
                }
            }
        }

        if (state.isBusy) {
            item {
                CbSurface(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ChatBarTheme.colors.border)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CbSpinner(Modifier.size(18.dp))
                            CbText(
                                if (state.phase == ImageProcessingPhase.IMPORTING) "正在读取图片" else "正在处理图片",
                                style = ChatBarTheme.typography.label
                            )
                        }
                        if (state.phase == ImageProcessingPhase.PROCESSING) {
                            CbProgress(state.progress)
                            CbText(
                                "${(state.progress * 100).toInt()}%",
                                color = ChatBarTheme.colors.mutedForeground,
                                style = ChatBarTheme.typography.caption
                            )
                        }
                    }
                }
            }
        }

        state.result?.let { result ->
            item {
                CbSurface(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, ChatBarTheme.colors.border)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CbText(
                            if (state.lastOperation == FullImagePatchOperation.Apply) "贴片结果" else "还原结果",
                            style = ChatBarTheme.typography.heading
                        )
                        AsyncImage(
                            model = File(result.path),
                            imageLoader = imageLoader,
                            contentDescription = "图像处理结果",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(ChatBarShape.sm)),
                            contentScale = ContentScale.Fit
                        )
                        CbText(
                            "${result.width} × ${result.height}" +
                                if (result.frameCount > 1) " · GIF ${result.frameCount} 帧" else " · PNG",
                            color = ChatBarTheme.colors.mutedForeground,
                            style = ChatBarTheme.typography.caption
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CbButton(
                                "直接分享",
                                { shareImage(context, result.path, "分享处理后的图片") },
                                modifier = Modifier.weight(1f)
                            )
                            CbButton(
                                "保存到相册",
                                { saveImageToGallery(context, result.path) },
                                modifier = Modifier.weight(1f),
                                variant = ButtonVariant.Secondary
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { error ->
            item {
                CbSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = ChatBarTheme.colors.muted,
                    border = BorderStroke(1.dp, ChatBarTheme.colors.destructive)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CbText(error, color = ChatBarTheme.colors.destructive, style = ChatBarTheme.typography.caption)
                        CbButton("关闭", onDismissError, variant = ButtonVariant.Ghost)
                    }
                }
            }
        }
    }
}

private fun com.example.chatbar.domain.image.ImportedProcessImage.description(): String =
    buildString {
        append(displayName)
        append(" · ")
        append(width)
        append(" × ")
        append(height)
        if (frameCount > 1) {
            append(" · GIF ")
            append(frameCount)
            append(" 帧")
        }
    }
