package com.example.chatbar.domain.backup

import java.io.File
import java.io.Writer
import java.text.Normalizer
import java.util.Locale

internal data class BackupMergeRecord(
    val file: File, val relative: String, val type: String, val values: Map<String, List<String>>,
    val inlineMediaKeys: Map<String, Set<String>> = emptyMap(),
) {
    fun text(field: String) = values[field]?.firstOrNull()
    val id get() = text("id")
    val sessionId get() = text("sessionId")
    val name get() = text("displayTitleOverride")?.takeIf(String::isNotBlank)
        ?: text("title") ?: text("name") ?: text("displayName")
    val singleton get() = relative.count { it == '/' } == 1

    fun hasInlineMediaReference(path: String, fields: List<String>): Boolean {
        if (inlineMediaKeys.isEmpty() || text("packageRef.fileName") != null) return false
        val resourceMap = when (fields) {
            listOf("audioResources"), listOf("voiceMessages", "audioPath") -> "audioResources"
            listOf("imageResources"), listOf("chatBackground"), listOf("messages", "images"),
            listOf("messages", "generatedImageMetadata", "imagePath") -> "imageResources"
            else -> return false
        }
        return path in inlineMediaKeys[resourceMap].orEmpty()
    }
}

/** Holds only identifiers/labels, never message bodies, prompts or inline media. */
internal object BackupMergeCatalog {
    val fields = setOf("id", "sessionId", "characterCardId", "name", "title", "displayTitleOverride", "displayName",
        "messageId", "messageVersionId", "sourceId", "sourceType", "entityType", "targetId", "ownerId", "fileName", "depth", "images", "imagePath", "path")
    fun read(root: File, checkActive: () -> Unit): List<BackupMergeRecord> = BackupDataRegistry.entityFiles(root).map { file ->
        checkActive()
        val relative = file.relativeTo(root).invariantSeparatorsPath
        val type = relative.removePrefix("entities/").substringBefore('/').removeSuffix(".json")
        val values = linkedMapOf<String, MutableList<String>>()
        val inlineMediaKeys = mutableMapOf<String, MutableSet<String>>()
        file.strictUtf8Reader().use { reader ->
            BackupJsonStream(reader, discardWriter, capturedFields = fields + BackupMergeIdentity.fields, checkActive = checkActive,
                observeField = { path, value -> values.getOrPut(path.joinToString(".")) { mutableListOf() }.add(value) }
            ) { path, location ->
                val resourceMap = location.singleOrNull()
                if (type == "save_slots" && resourceMap in setOf("imageResources", "audioResources") &&
                    isInlineMediaKey(path)) {
                    inlineMediaKeys.getOrPut(requireNotNull(resourceMap)) { linkedSetOf() }.add(path)
                }
                path
            }.copy()
        }
        BackupMergeRecord(file, relative, type, values, inlineMediaKeys)
    }.toList()

    private fun isInlineMediaKey(path: String): Boolean =
        path.isNotBlank() && path != "." && path != ".." &&
            path.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() }

    val discardWriter = object : Writer() {
        override fun write(chars: CharArray, offset: Int, length: Int) {}
        override fun flush() {}
        override fun close() {}
    }
}

internal object BackupMergeNames {
    fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun copyName(value: String, used: MutableSet<String>): String {
        val base = value.trim().ifBlank { "未命名" }.take(80)
        var number = 1
        while (true) {
            val candidate = base + if (number == 1) "（副本）" else "（副本 $number）"
            if (used.add(normalize(candidate))) return candidate
            number++
        }
    }
}
