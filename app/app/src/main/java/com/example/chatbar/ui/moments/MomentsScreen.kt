package com.example.chatbar.ui.moments

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.ui.components.CbAvatar
import com.example.chatbar.ui.components.EmptyState
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.kit.AppIcons
import com.example.chatbar.ui.kit.CbDivider
import com.example.chatbar.ui.kit.CbIcon
import com.example.chatbar.ui.kit.CbIconButton
import com.example.chatbar.ui.kit.CbScaffold
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.CbTopBar
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MomentsScreen(
    modifier: Modifier = Modifier,
    viewModel: MomentsViewModel = viewModel()
) {
    val posts by viewModel.posts.collectAsState()
    val context = LocalContext.current
    val expandedImage = remember { mutableStateOf<Pair<MomentPost, String>?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    CbScaffold(
        modifier = modifier,
        topBar = { CbTopBar("朋友圈") }
    ) { bottomInset ->
        Box(Modifier.fillMaxSize().background(Color.White)) {
            if (posts.isEmpty()) {
                EmptyState(
                    icon = AppIcons.Heart,
                    title = "暂无朋友圈",
                    description = "开启全局功能和角色朋友圈后，推进交流会自动出现动态。"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = bottomInset + 88.dp)
                ) {
                    items(posts, key = { it.id }) { post ->
                        MomentPostRow(
                            post = post,
                            onToggleLike = { viewModel.toggleLike(post.id) },
                            onDelete = { viewModel.deletePost(post.id) },
                            onOpenImage = { imagePost, path -> expandedImage.value = imagePost to path }
                        )
                        CbDivider(color = Color(0xFFE9E9E9))
                    }
                }
            }
        }
    }
    expandedImage.value?.let { (post, path) ->
        ImagePreviewDialog(
            path = path,
            onDismiss = { expandedImage.value = null },
            onSetCardAvatar = {
                viewModel.replaceCharacterCardAvatarFromImage(post.characterCardId, path) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            onSetCardBackground = {
                viewModel.replaceCharacterCardBackgroundFromImage(post.characterCardId, path) { _, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentPostRow(
    post: MomentPost,
    onToggleLike: () -> Unit,
    onDelete: () -> Unit,
    onOpenImage: (MomentPost, String) -> Unit
) {
    val textColor = Color(0xFF111111)
    val muted = Color(0xFF757575)
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        CbAvatar(
            imagePath = post.senderAvatar,
            contentDescription = post.senderName,
            size = 44.dp,
            rounded = true,
            fallbackIcon = AppIcons.Face
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CbText(
                post.senderName,
                color = Color(0xFF576B95),
                style = ChatBarTheme.typography.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (post.text.isNotBlank()) {
                CbText(
                    post.text,
                    color = textColor,
                    style = ChatBarTheme.typography.body
                )
            }
            post.imagePath?.takeIf(String::isNotBlank)?.let { path ->
                AsyncImage(
                    model = File(path),
                    contentDescription = post.imageBrief.ifBlank { post.text },
                    modifier = Modifier
                        .fillMaxWidth(0.74f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFF1F1F1))
                        .clickable { onOpenImage(post, path) },
                    contentScale = ContentScale.Crop
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CbText(
                    formatMomentTime(post.generatedAt),
                    color = muted,
                    style = ChatBarTheme.typography.caption
                )
                if (post.isPrivate) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5F5F5)).padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        CbIcon(AppIcons.Lock, null, Modifier.size(12.dp), muted)
                        Spacer(Modifier.width(3.dp))
                        CbText("仅你可见", color = muted, style = ChatBarTheme.typography.caption)
                    }
                }
                Spacer(Modifier.weight(1f))
                MomentDeleteButton(onDelete)
                Spacer(Modifier.width(2.dp))
                CbIconButton(
                    imageVector = AppIcons.Heart,
                    contentDescription = if (post.userLiked) "取消点赞" else "点赞",
                    onClick = onToggleLike,
                    modifier = Modifier.size(34.dp),
                    tint = if (post.userLiked) Color(0xFFE5484D) else Color(0xFF576B95)
                )
                CbText(
                    post.displayLikeCount.toString(),
                    color = muted,
                    style = ChatBarTheme.typography.caption,
                    modifier = Modifier.clickable(onClick = onToggleLike)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentDeleteButton(onDelete: () -> Unit) {
    val muted = Color(0xFF757575)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .combinedClickable(
                role = Role.Button,
                onClick = {},
                onLongClick = onDelete
            ),
        contentAlignment = Alignment.Center
    ) {
        CbIcon(
            imageVector = AppIcons.Delete,
            contentDescription = "长按删除",
            modifier = Modifier.size(15.dp),
            tint = muted.copy(alpha = 0.42f)
        )
    }
}

private fun formatMomentTime(timeMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timeMs).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 60L * 60L * 1000L -> "${diff / 60_000L}分钟前"
        diff < 24L * 60L * 60L * 1000L -> "${diff / (60L * 60L * 1000L)}小时前"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(timeMs))
    }
}
