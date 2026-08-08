package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MomentPost
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MomentRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `updatePostText changes only text and updated timestamp`() = runTest {
        val repository = repository()
        val original = post()
        repository.savePost(original)

        val updated = requireNotNull(repository.updatePostText(original.id, "  修改后的动态  "))

        assertEquals("修改后的动态", updated.text)
        assertTrue(updated.updatedAt > original.updatedAt)
        assertEquals(
            original.copy(text = updated.text, updatedAt = updated.updatedAt),
            updated
        )
        assertEquals(updated, repository.getPost(original.id))
    }

    @Test
    fun `updatePostText rejects blank and placeholder content`() = runTest {
        val repository = repository()
        val original = post()
        val placeholder = post().copy(id = "placeholder", text = "", isPlaceholder = true)
        repository.savePost(original)
        repository.savePost(placeholder)

        val blankError = runCatching {
            repository.updatePostText(original.id, "  ")
        }.exceptionOrNull()
        val placeholderError = runCatching {
            repository.updatePostText(placeholder.id, "修改")
        }.exceptionOrNull()

        assertTrue(blankError is IllegalArgumentException)
        assertTrue(placeholderError is IllegalArgumentException)
        assertNull(repository.updatePostText("missing", "修改"))
    }

    private fun repository(): MomentRepository =
        MomentRepository(JsonFileStorage(TestContext(temp.newFolder("files"))))

    private fun post(): MomentPost = MomentPost(
        id = "post",
        characterCardId = "card",
        sessionId = "session",
        senderCharacterId = "character",
        senderName = "角色",
        senderAvatar = "avatar.png",
        text = "原动态",
        imagePath = "moment.png",
        imagePrompt = "prompt",
        generatedImageMetadata = GeneratedImageMetadata(
            imagePath = "moment.png",
            baseCaption = "prompt",
            negativePrompt = "",
            sizePreset = "SQUARE",
            width = 1024,
            height = 1024
        ),
        imageBrief = "brief",
        isPrivate = true,
        baseLikeCount = 0,
        userLiked = true,
        generationReason = "reason",
        scheduledAt = 1,
        generatedAt = 2,
        createdAt = 2,
        updatedAt = 2
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
