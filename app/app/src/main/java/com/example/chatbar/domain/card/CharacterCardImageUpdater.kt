package com.example.chatbar.domain.card

import android.content.Context
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.repository.CharacterRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class CharacterCardImageTarget(val filePrefix: String) {
    AVATAR("avatar"),
    BACKGROUND("background")
}

object CharacterCardImageUpdater {
    suspend fun replace(
        context: Context,
        characterRepository: CharacterRepository,
        cardId: String,
        sourcePath: String,
        target: CharacterCardImageTarget
    ): CharacterCard {
        val currentCard = characterRepository.getById(cardId)
            ?: throw IllegalStateException("角色卡不存在")
        val localPath = copyImageIntoCharacterCardStorage(
            context = context,
            sourcePath = sourcePath,
            cardId = currentCard.id,
            target = target
        )
        val updated = when (target) {
            CharacterCardImageTarget.AVATAR -> currentCard.copy(avatar = localPath)
            CharacterCardImageTarget.BACKGROUND -> currentCard.copy(chatBackground = localPath)
        }
        characterRepository.update(updated)
        return characterRepository.getById(updated.id) ?: updated
    }

    private suspend fun copyImageIntoCharacterCardStorage(
        context: Context,
        sourcePath: String,
        cardId: String,
        target: CharacterCardImageTarget
    ): String = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.exists()) throw IllegalArgumentException("图片文件不存在")
        val extension = source.extension.takeIf { it.isNotBlank() } ?: "png"
        val directory = File(
            context.filesDir,
            "images/character_cards/${cardId.safeFileSegment()}"
        ).also(File::mkdirs)
        val targetFile = File(
            directory,
            "${target.filePrefix}_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension"
        )
        source.inputStream().use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        targetFile.absolutePath
    }
}

private fun String.safeFileSegment(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
