package com.example.chatbar.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
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
import androidx.core.content.FileProvider
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

private fun shareImage(context: Context, path: String) {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = imageMimeType(sourceFile)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, "ChatBar image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享图片"))
    } catch (error: Exception) {
        Toast.makeText(context, "分享失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun imageMimeType(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "image/png"
}

private fun saveImageToGallery(context: Context, path: String) {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = sourceFile.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, imageMimeType(sourceFile))
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChatBar")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChatBar")
            dir.mkdirs()
            val target = File(dir, fileName)
            sourceFile.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
        }
    } catch (error: Exception) {
        Toast.makeText(context, "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}
