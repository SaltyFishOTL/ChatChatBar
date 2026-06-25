package com.example.chatbar.domain.card

import android.util.Base64
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.domain.rag.RagRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class CharacterCardTransferService(
    private val app: ChatBarApp,
    private val repository: CharacterRepository,
    private val ragRepository: RagRepository,
    private val json: Json
) {
    suspend fun exportJson(id: String): String = withContext(Dispatchers.IO) {
        val card = repository.getById(id) ?: error("角色卡不存在")
        json.encodeToString(CharacterCardPackage.serializer(), packageCard(card))
    }

    fun decode(rawJson: String): CharacterCardPackage =
        json.decodeFromString(CharacterCardPackage.serializer(), rawJson).also(CharacterCardPackage::validateForImport)

    suspend fun duplicate(id: String): CharacterCard = withContext(Dispatchers.IO) {
        val source = repository.getById(id) ?: error("角色卡不存在")
        val name = NamePolicy.nextCopyName(source.name, repository.getAll().map { it.name })
        saveNew(materialize(packageCard(source), UUID.randomUUID().toString(), name))
    }

    suspend fun importNew(
        packageData: CharacterCardPackage,
        requestedName: String = packageData.card.name,
        presetKey: String? = null,
        presetVersion: Int? = null
    ): CharacterCard = withContext(Dispatchers.IO) {
        packageData.validateForImport()
        val uniqueName = if (repository.getAll().any { NamePolicy.isSame(it.name, requestedName) }) {
            NamePolicy.nextCopyName(requestedName, repository.getAll().map { it.name })
        } else NamePolicy.normalize(requestedName)
        saveNew(materialize(packageData, UUID.randomUUID().toString(), uniqueName, presetKey, presetVersion))
    }

    suspend fun overwrite(
        existingId: String,
        packageData: CharacterCardPackage,
        presetKey: String? = null,
        presetVersion: Int? = null
    ): CharacterCard = withContext(Dispatchers.IO) {
        packageData.validateForImport()
        val existing = repository.getById(existingId) ?: error("待覆盖角色卡不存在")
        val replacement = materialize(
            packageData = packageData,
            id = existing.id,
            name = existing.name,
            presetKey = presetKey,
            presetVersion = presetVersion,
            createdAt = existing.createdAt
        )
        try {
            repository.save(replacement)
        } catch (error: Throwable) {
            deleteMaterializedFiles(replacement)
            throw error
        }
        deleteOwnedResources(existing)
        replacement
    }

    suspend fun deleteCard(id: String) = withContext(Dispatchers.IO) {
        val previous = repository.getById(id)
        if (previous != null) deleteOwnedResources(previous)
        repository.delete(id)
    }

    private fun packageCard(card: CharacterCard): CharacterCardPackage {
        val documents = card.customDocuments.map { doc ->
            val file = requireResourceFile(doc.filePath, "参考文档 ${doc.fileName}")
            PackagedDocument(doc.fileName, doc.fileType, file.readText())
        }
        val images = linkedMapOf<String, PackagedImage>()
        val resourceIdsByPath = mutableMapOf<String, String>()
        fun packageImage(path: String?, resourceId: String, label: String): String? {
            path?.takeIf(String::isNotBlank) ?: return null
            resourceIdsByPath[path]?.let { return it }
            val file = requireResourceFile(path, label)
            images[resourceId] = PackagedImage(
                fileName = file.name.ifBlank { "$resourceId.jpg" },
                data = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            )
            resourceIdsByPath[path] = resourceId
            return resourceId
        }
        val packagedCharacters = card.characters.mapIndexed { index, character ->
            PackagedCharacter(
                name = character.name,
                profile = character.profile,
                appearance = character.appearance,
                appearanceImageResourceId = packageImage(
                    character.appearanceImage,
                    "character-$index-appearance",
                    "人物 ${character.name} 的形象图"
                ),
                clothing = character.clothing,
                abilities = character.abilities,
                habits = character.habits,
                background = character.background,
                relationships = character.relationships,
                speakingStyle = character.speakingStyle,
                imagePrompt = character.imagePrompt
            )
        }
        return CharacterCardPackage(
            card = PackagedCharacterCard(
                name = card.name,
                avatarResourceId = packageImage(card.avatar, "avatar", "角色卡头像"),
                characters = packagedCharacters,
                greeting = card.greeting,
                alternateGreetings = card.alternateGreetings,
                chatBackgroundResourceId = packageImage(card.chatBackground, "chat-background", "聊天背景"),
                editMode = card.editMode,
                basicSetting = card.basicSetting,
                freeformCharacterText = card.freeformCharacterText,
                defaultImagePrompt = card.defaultImagePrompt,
                systemPrompt = card.systemPrompt,
                postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample,
                creatorNotes = card.creatorNotes,
                tags = card.tags,
                creator = card.creator,
                characterVersion = card.characterVersion,
                extensions = card.extensions,
                characterBook = card.characterBook
            ),
            documents = documents,
            images = images
        )
    }

    private suspend fun materialize(
        packageData: CharacterCardPackage,
        id: String,
        name: String,
        presetKey: String? = null,
        presetVersion: Int? = null,
        createdAt: Long = System.currentTimeMillis()
    ): CharacterCard {
        val now = System.currentTimeMillis()
        val createdFiles = mutableListOf<File>()
        try {
            val imagePathMap = materializeImages(packageData.images, now, createdFiles)
            val docsDir = File(app.filesDir, "documents").also(File::mkdirs)
            val documents = packageData.documents.map { packaged ->
                val docId = UUID.randomUUID().toString()
                val safeName = safeFileName(packaged.fileName)
                val file = File(docsDir, "card_${now}_${docId}_$safeName")
                createdFiles += file
                file.writeText(packaged.content)
                DocumentInfo.create(packaged.fileName, file.absolutePath, packaged.fileType).copy(id = docId, addedAt = now)
            }
            val card = packageData.card
            return CharacterCard(
                id = id,
                name = NamePolicy.normalize(name),
                avatar = card.avatarResourceId?.let(imagePathMap::getValue),
                characters = card.characters.map { character ->
                    CharacterInfo(
                        id = UUID.randomUUID().toString(),
                        name = character.name,
                        profile = character.profile,
                        appearance = character.appearance,
                        appearanceImage = character.appearanceImageResourceId?.let(imagePathMap::getValue),
                        clothing = character.clothing,
                        abilities = character.abilities,
                        habits = character.habits,
                        background = character.background,
                        relationships = character.relationships,
                        speakingStyle = character.speakingStyle,
                        imagePrompt = character.imagePrompt
                    )
                },
                customDocuments = documents,
                greeting = card.greeting,
                chatBackground = card.chatBackgroundResourceId?.let(imagePathMap::getValue),
                editMode = card.editMode,
                basicSetting = card.basicSetting,
                freeformCharacterText = card.freeformCharacterText,
                defaultImagePrompt = card.defaultImagePrompt,
                systemPrompt = card.systemPrompt,
                postHistoryInstructions = card.postHistoryInstructions,
                mesExample = card.mesExample,
                creatorNotes = card.creatorNotes,
                tags = card.tags,
                creator = card.creator,
                characterVersion = card.characterVersion,
                extensions = card.extensions,
                characterBook = card.characterBook,
                sourcePresetKey = presetKey,
                sourcePresetVersion = presetVersion,
                ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
                ragIndexDone = 0,
                ragIndexTotal = documents.size,
                ragIndexMessage = if (documents.isEmpty()) "无参考文档" else "参考文档待建立索引",
                ragIndexedAt = null,
                createdAt = createdAt,
                updatedAt = now
            )
        } catch (error: Throwable) {
            createdFiles.forEach(File::delete)
            throw error
        }
    }

    private fun materializeImages(
        images: Map<String, PackagedImage>,
        now: Long,
        createdFiles: MutableList<File>
    ): Map<String, String> {
        val imageDir = File(app.filesDir, "images").also(File::mkdirs)
        return images.mapValues { (_, packaged) ->
            val extension = File(packaged.fileName).extension
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                ?: "jpg"
            val file = File(imageDir, "card_${now}_${UUID.randomUUID()}.$extension")
            createdFiles += file
            if (packaged.data.startsWith("asset:")) {
                app.assets.open(packaged.data.removePrefix("asset:")).use { input -> file.outputStream().use(input::copyTo) }
            } else {
                file.writeBytes(Base64.decode(packaged.data, Base64.DEFAULT))
            }
            file.absolutePath
        }
    }

    private suspend fun saveNew(card: CharacterCard): CharacterCard {
        try {
            repository.save(card)
        } catch (error: Throwable) {
            deleteMaterializedFiles(card)
            throw error
        }
        return card
    }

    private fun requireResourceFile(path: String, label: String): File =
        File(path).takeIf { it.isFile } ?: error("$label 文件不存在：$path")

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.txt" }

    private fun deleteMaterializedFiles(card: CharacterCard) {
        card.avatar?.let { File(it).delete() }
        card.chatBackground?.let { File(it).delete() }
        card.characters.mapNotNull { it.appearanceImage }.forEach { File(it).delete() }
        card.customDocuments.forEach { File(it.filePath).delete() }
    }

    private suspend fun deleteOwnedResources(card: CharacterCard) {
        card.avatar?.let { File(it).delete() }
        card.chatBackground?.let { File(it).delete() }
        card.characters.mapNotNull { it.appearanceImage }.forEach { File(it).delete() }
        card.customDocuments.forEach { File(it.filePath).delete() }
        ragRepository.deleteChunksBySource(com.example.chatbar.data.local.entity.ChunkSourceType.DOCUMENT, card.id)
    }
}
