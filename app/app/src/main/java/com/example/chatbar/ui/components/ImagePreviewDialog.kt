package com.example.chatbar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.ButtonVariant
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.CbDialog
import com.example.chatbar.ui.kit.CbIconButton
import java.io.File

@Composable
fun ImagePreviewDialog(
    path: String,
    onDismiss: () -> Unit,
    onSetCardAvatar: (() -> Unit)? = null,
    onSetCardBackground: (() -> Unit)? = null
) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var showImageActions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showImageActions) {
        CbDialog(
            onDismissRequest = { showImageActions = false },
            title = "图片操作",
            dismiss = { CbButton("取消", { showImageActions = false }, variant = ButtonVariant.Ghost) }
        ) {
            CbButton(
                "保存图片",
                {
                    showImageActions = false
                    saveImageToGallery(context, path)
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.size(8.dp))
            CbButton(
                "分享图片",
                {
                    showImageActions = false
                    shareImage(context, path)
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
                        onSetCardAvatar()
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
                        onSetCardBackground()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = ButtonVariant.Secondary
                )
            }
        }
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
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "查看大图",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(path) {
                        detectTapGestures(onLongPress = { showImageActions = true })
                    }
                    .pointerInput(path) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = newScale
                            offset = if (newScale == 1f) {
                                Offset.Zero
                            } else {
                                offset + pan
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit
            )
            CbIconButton(
                AppIcons.Close,
                "关闭大图",
                onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                tint = Color.White
            )
        }
    }
}
