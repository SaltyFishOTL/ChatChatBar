package com.example.chatbar.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.chatbar.ui.kit.*
import java.io.File

@Composable
internal fun ChatBackgroundGallery(
    images: List<String>, selected: String?, onSelect: (String) -> Unit,
    onRemove: ((String) -> Unit)? = null, enabled: Boolean = true
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(images.filter(String::isNotBlank).distinct(), key = { _, path -> path }) { index, path ->
            CbSurface(Modifier.width(132.dp)) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AsyncImage(File(path), "聊天背景图 ${index + 1}", Modifier.fillMaxWidth().height(92.dp), contentScale = ContentScale.Crop)
                    CbButton(
                        if (path == selected) "已选背景 ${index + 1}" else "选择背景 ${index + 1}",
                        { onSelect(path) }, enabled = enabled,
                        variant = if (path == selected) ButtonVariant.Default else ButtonVariant.Outline,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (onRemove != null) CbButton("移除背景 ${index + 1}", { onRemove(path) },
                        enabled = enabled, variant = ButtonVariant.Ghost, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
