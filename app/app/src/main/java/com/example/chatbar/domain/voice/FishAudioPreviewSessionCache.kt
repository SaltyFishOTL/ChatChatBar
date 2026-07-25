package com.example.chatbar.domain.voice

import java.io.File

internal class FishAudioPreviewSessionCache(
    private val fileExists: (String) -> Boolean = { File(it).isFile }
) {
    private val generatedPaths = mutableMapOf<String, String>()

    fun get(referenceId: String): String? {
        val path = generatedPaths[referenceId] ?: return null
        if (fileExists(path)) return path
        generatedPaths.remove(referenceId)
        return null
    }

    fun remember(referenceId: String, path: String): String {
        val cached = get(referenceId)
        if (cached != null) return cached
        generatedPaths[referenceId] = path
        return path
    }

    fun clear() {
        generatedPaths.clear()
    }
}
