package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import java.io.File
import java.net.URI

/** Historical posts retain their author snapshots and images after a card edit or deletion. */
object MomentMediaRetention {
    fun unreferencedPaths(candidates: Iterable<String>, posts: List<MomentPost>): List<String> {
        val retained = posts.flatMap { listOfNotNull(it.senderAvatar, it.imagePath) }
            .filter(String::isNotBlank)
            .map(::referenceKey)
            .toSet()
        return candidates.filter { it.isNotBlank() && referenceKey(it) !in retained }.distinct()
    }

    private fun referenceKey(path: String): String = when {
        path.startsWith("http:") || path.startsWith("https:") || path.startsWith("content:") -> path
        path.startsWith("file:") -> File(URI(path)).canonicalPath
        else -> File(path).canonicalPath
    }
}
