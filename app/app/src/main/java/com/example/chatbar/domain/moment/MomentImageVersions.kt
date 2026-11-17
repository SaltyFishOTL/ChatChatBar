package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentImageVersion
import com.example.chatbar.data.local.entity.MomentPost
import java.util.UUID

/** Current fields remain the selected image; old posts are lifted without changing their originals. */
object MomentImageVersions {
    /** Only legacy no-image spellings are equivalent; real paths are never trimmed or resolved. */
    fun imagePathOrNull(path: String?): String? = path?.takeIf(String::isNotBlank)
    fun currentImagePath(post: MomentPost): String? = imagePathOrNull(post.imagePath)

    fun retained(post: MomentPost): List<MomentImageVersion> {
        val current = currentImagePath(post) ?: return post.imageVersions
        if (post.imageVersions.any { it.imagePath == current }) return post.imageVersions
        return post.imageVersions + MomentImageVersion("legacy:${post.id}", current, post.imagePrompt, post.generatedImageMetadata)
    }

    fun append(post: MomentPost, path: String, prompt: String, metadata: com.example.chatbar.data.local.entity.GeneratedImageMetadata?): MomentPost {
        require(path.isNotBlank())
        val version = MomentImageVersion(UUID.randomUUID().toString(), path, prompt, metadata)
        return select(post.copy(imageVersions = retained(post) + version), version.id)
    }

    fun select(post: MomentPost, versionId: String): MomentPost {
        val versions = retained(post)
        val selected = versions.firstOrNull { it.id == versionId } ?: error("朋友圈图片版本不存在")
        return post.copy(imagePath = selected.imagePath, imagePrompt = selected.imagePrompt,
            generatedImageMetadata = selected.generatedImageMetadata, imageVersions = versions)
    }
}
