package com.example.chatbar.domain.community

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest

object CommunityPreviewCache {
    const val REQUEST_SIZE_PX = 160
    private const val MAX_PREFETCH_PER_BATCH = 40

    fun request(context: Context, url: String): ImageRequest {
        val appContext = context.applicationContext
        return ImageRequest.Builder(appContext)
            .data(url)
            .size(REQUEST_SIZE_PX, REQUEST_SIZE_PX)
            .memoryCacheKey(url)
            .diskCacheKey(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    fun prefetchItems(
        context: Context,
        service: CommunityService,
        items: List<CommunityItem>,
        limit: Int = MAX_PREFETCH_PER_BATCH
    ) {
        prefetchUrls(
            context = context,
            urls = items.asSequence().mapNotNull(service::previewUrl),
            limit = limit
        )
    }

    fun prefetchUrls(
        context: Context,
        urls: Sequence<String>,
        limit: Int = MAX_PREFETCH_PER_BATCH
    ) {
        val appContext = context.applicationContext
        urls
            .filter(String::isNotBlank)
            .distinct()
            .take(limit.coerceAtLeast(0))
            .forEach { url ->
                appContext.imageLoader.enqueue(request(appContext, url))
            }
    }
}
