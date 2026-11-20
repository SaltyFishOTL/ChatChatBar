package com.example.chatbar.domain.draft

import android.content.Context
import android.net.Uri
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.domain.backup.LocalDataMaintenance
import java.io.File
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class EditorDraftAssetService(private val context: Context) {
    private val modalJson = Json { ignoreUnknownKeys = true }

    fun isDraftAsset(path: String?): Boolean =
        !path.isNullOrBlank() && runCatching {
            File(path).canonicalFile.toPath().startsWith(draftRoot().canonicalFile.toPath())
        }.getOrDefault(false)

    suspend fun copyImageToDraft(draftSessionId: String, uri: Uri, extension: String): String =
        copyUriToDraft(draftSessionId, uri, "img_${UUID.randomUUID()}.$extension")

    suspend fun writeDocumentToDraft(draftSessionId: String, fileName: String, content: String): String =
        withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            val file = uniqueDraftFile(draftSessionId, fileName)
            file.writeText(content)
            file.absolutePath
        }
    }

    suspend fun copyDocumentToDraft(draftSessionId: String, uri: Uri, fileName: String): String =
        copyUriToDraft(draftSessionId, uri, fileName)

    fun newDraftFile(draftSessionId: String, fileName: String): File =
        uniqueDraftFile(draftSessionId, fileName)

    suspend fun stageExistingFile(draftSessionId: String, path: String, fallbackName: String): String =
        withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            val source = File(path)
            val file = uniqueDraftFile(draftSessionId, source.name.takeIf { it.isNotBlank() } ?: fallbackName)
            if (source.exists()) source.copyTo(file, overwrite = true) else file.writeText("")
            file.absolutePath
        }
    }

    suspend fun materializeCharacterAssets(card: CharacterCard): CharacterCard =
        withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            val paths = mutableMapOf<String, String?>()
            fun image(path: String?): String? = path?.let {
                paths.getOrPut(it) { materializeFile(it, "images") }
            }
            card.copy(
                avatar = image(card.avatar),
                chatBackground = image(card.chatBackground),
                chatBackgroundImages = card.chatBackgroundImages.mapNotNull(::image).distinct(),
                characters = card.characters.map { character ->
                    character.copy(appearanceImage = image(character.appearanceImage))
                },
                customDocuments = card.customDocuments.map { doc ->
                    doc.copy(filePath = materializeFile(doc.filePath, "documents") ?: doc.filePath)
                }
            )
        }
    }

    suspend fun deleteDraft(draftSessionId: String) = withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            draftDir(draftSessionId).deleteRecursively()
        }
    }

    suspend fun deleteUnreferencedDraftAssets(
        remainingDrafts: List<EditorDraft>,
        sessions: List<String> = emptyList(),
        files: Set<String> = emptySet(),
    ): Set<String> = withContext(Dispatchers.IO) {
        LocalDataMaintenance.access {
            val root = draftRoot().canonicalFile.toPath()
            fun sessionDirectory(session: String): Path = draftDir(session).canonicalFile.toPath().also {
                check(it.parent == root) { "草稿资源目录无效" }
            }
            fun namespace(path: Path): Path? = path.takeIf { it != root && it.startsWith(root) }
                ?.let { root.resolve(root.relativize(it).getName(0)) }
            val protectedDirectories = buildSet {
                for (draft in remainingDrafts) {
                    add(sessionDirectory(draft.draftSessionId))
                    val modalImage = draft.openModalState?.takeIf { draft.entityType == EditorDraftType.CHARACTER_CARD }?.let {
                        modalJson.decodeFromString(CharacterOpenModalState.serializer(), it).character?.appearanceImage
                    }
                    val references = draft.draftAssetPaths + draft.pendingDeletedAssets +
                        draft.characterPayload?.let(::ownedAssetPaths).orEmpty() + listOfNotNull(modalImage)
                    references.mapNotNullTo(this) { namespace(File(it).canonicalFile.toPath()) }
                }
            }
            val removed = files.filterTo(mutableSetOf()) { file ->
                val path = File(file).canonicalFile.toPath()
                val directory = namespace(path) ?: return@filterTo false
                directory !in protectedDirectories && path.toFile().let { it.delete() || !it.exists() }
            }
            for (directory in sessions.distinct().map(::sessionDirectory)) {
                if (directory !in protectedDirectories) {
                    check(directory.toFile().deleteRecursively()) { "删除草稿资源目录失败" }
                }
            }
            removed
        }
    }

    suspend fun deleteFiles(paths: Iterable<String>) = withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            paths.distinct().forEach { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists()) file.delete()
                }
            }
        }
    }

    fun ownedAssetPaths(card: CharacterCard): List<String> = buildList {
        card.avatar?.let(::add)
        card.chatBackground?.let(::add)
        addAll(card.chatBackgroundImages)
        card.characters.mapNotNullTo(this) { it.appearanceImage }
        card.customDocuments.mapTo(this) { it.filePath }
    }.filter(String::isNotBlank).distinct()

    private suspend fun copyUriToDraft(draftSessionId: String, uri: Uri, fileName: String): String =
        withContext(Dispatchers.IO) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            val file = uniqueDraftFile(draftSessionId, fileName)
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            input.use { inputStream ->
                file.outputStream().use { output -> inputStream.copyTo(output) }
            }
            check(file.exists() && file.length() > 0L) { "草稿文件复制失败" }
            file.absolutePath
        }
    }

    private fun materializeFile(path: String?, dirName: String): String? {
        return com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            if (!isDraftAsset(path)) return path
            val source = File(path!!)
            check(source.isFile && source.length() > 0L) {
                "草稿资源文件不存在或为空：${source.name}"
            }
            val targetDir = File(context.filesDir, dirName).also { if (!it.exists()) it.mkdirs() }
            val target = uniqueFile(targetDir, source.name)
            source.copyTo(target, overwrite = true)
            return target.absolutePath
        }
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
