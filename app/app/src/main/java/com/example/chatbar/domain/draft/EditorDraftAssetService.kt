package com.example.chatbar.domain.draft

import android.content.Context
import android.net.Uri
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EditorDraftAssetService(private val context: Context) {
    fun isDraftAsset(path: String?): Boolean =
        !path.isNullOrBlank() && runCatching {
            File(path).canonicalFile.toPath().startsWith(draftRoot().canonicalFile.toPath())
        }.getOrDefault(false)

    suspend fun copyImageToDraft(draftSessionId: String, uri: Uri, extension: String): String =
        copyUriToDraft(draftSessionId, uri, "img_${UUID.randomUUID()}.$extension")

    suspend fun writeDocumentToDraft(draftSessionId: String, fileName: String, content: String): String =
        withContext(Dispatchers.IO) {
            val file = uniqueDraftFile(draftSessionId, fileName)
            file.writeText(content)
            file.absolutePath
        }

    suspend fun copyDocumentToDraft(draftSessionId: String, uri: Uri, fileName: String): String =
        copyUriToDraft(draftSessionId, uri, fileName)

    fun newDraftFile(draftSessionId: String, fileName: String): File =
        uniqueDraftFile(draftSessionId, fileName)

    suspend fun stageExistingFile(draftSessionId: String, path: String, fallbackName: String): String =
        withContext(Dispatchers.IO) {
            val source = File(path)
            val file = uniqueDraftFile(draftSessionId, source.name.takeIf { it.isNotBlank() } ?: fallbackName)
            if (source.exists()) source.copyTo(file, overwrite = true) else file.writeText("")
            file.absolutePath
        }

    suspend fun materializeCharacterAssets(card: CharacterCard): CharacterCard =
        withContext(Dispatchers.IO) {
            card.copy(
                avatar = materializeFile(card.avatar, "images"),
                chatBackground = materializeFile(card.chatBackground, "images"),
                characters = card.characters.map { character ->
                    character.copy(appearanceImage = materializeFile(character.appearanceImage, "images"))
                },
                customDocuments = card.customDocuments.map { doc ->
                    doc.copy(filePath = materializeFile(doc.filePath, "documents") ?: doc.filePath)
                }
            )
        }

    suspend fun deleteDraft(draftSessionId: String) = withContext(Dispatchers.IO) {
        draftDir(draftSessionId).deleteRecursively()
    }

    suspend fun deleteFiles(paths: Iterable<String>) = withContext(Dispatchers.IO) {
        paths.distinct().forEach { path ->
            runCatching {
                val file = File(path)
                if (file.exists()) file.delete()
            }
        }
    }

    fun ownedAssetPaths(card: CharacterCard): List<String> = buildList {
        card.avatar?.let(::add)
        card.chatBackground?.let(::add)
        card.characters.mapNotNullTo(this) { it.appearanceImage }
        card.customDocuments.mapTo(this) { it.filePath }
    }.filter(String::isNotBlank).distinct()

    private suspend fun copyUriToDraft(draftSessionId: String, uri: Uri, fileName: String): String =
        withContext(Dispatchers.IO) {
            val file = uniqueDraftFile(draftSessionId, fileName)
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            input.use { inputStream ->
                file.outputStream().use { output -> inputStream.copyTo(output) }
            }
            check(file.exists() && file.length() > 0L) { "草稿文件复制失败" }
            file.absolutePath
        }

    private fun materializeFile(path: String?, dirName: String): String? {
        if (!isDraftAsset(path)) return path
        val source = File(path!!)
        if (!source.exists()) return path
        val targetDir = File(context.filesDir, dirName).also { if (!it.exists()) it.mkdirs() }
        val target = uniqueFile(targetDir, source.name)
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    private fun uniqueDraftFile(draftSessionId: String, fileName: String): File =
        uniqueFile(draftDir(draftSessionId), safeName(fileName))

    private fun uniqueFile(dir: File, fileName: String): File {
        if (!dir.exists()) dir.mkdirs()
        val name = safeName(fileName).ifBlank { "draft.txt" }
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = File(dir, name)
        var index = 2
        while (candidate.exists()) {
            val suffix = if (ext.isBlank()) "" else ".$ext"
            candidate = File(dir, "${base}_$index$suffix")
            index += 1
        }
        return candidate
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "draft.txt" }

    private fun draftRoot(): File = File(context.filesDir, "draft_assets").also {
        if (!it.exists()) it.mkdirs()
    }

    private fun draftDir(draftSessionId: String): File = File(draftRoot(), safeName(draftSessionId))
}
