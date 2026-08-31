package com.example.chatbar.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.memory.MemoryCache
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import kotlinx.coroutines.Dispatchers

data class ChatImageRenderRuntime(
    val imageLoader: ImageLoader? = null,
    val activePaths: Set<String>? = null
) {
    fun shouldLoad(path: String): Boolean = activePaths?.contains(path) != false
}

val LocalChatImageRenderRuntime = staticCompositionLocalOf { ChatImageRenderRuntime() }

fun createChatImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .dispatcher(Dispatchers.IO.limitedParallelism(2))
    .memoryCache {
        MemoryCache.Builder(context)
            .maxSizeBytes(24 * 1024 * 1024)
            .strongReferencesEnabled(true)
            .weakReferencesEnabled(false)
            .build()
    }
    .components {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(ImageDecoderDecoder.Factory())
        } else {
            add(GifDecoder.Factory())
        }
    }
    .build()

fun chatImageMemoryCacheKey(path: String): String = "chat-image:$path"

fun isOmittedSaveSlotImage(path: String): Boolean =
    path.startsWith(OMITTED_SAVE_SLOT_IMAGE_PREFIX)
