package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentSender
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

    @Test
    fun `sender edit preserves latest image likes and post ownership`() = runTest {
        val repository = repository()
        val original = post()
        repository.savePost(original)
        repository.toggleLike(original.id)
        val latest = requireNotNull(repository.getPost(original.id)).copy(imagePath = "new.png")
        repository.updatePost(latest)
        val updated = requireNotNull(repository.updatePostContent(original.id, "新文案", MomentSender("other", "新人物", null)))
        assertEquals(latest.copy(text = "新文案", senderCharacterId = "other", senderName = "新人物",
            senderAvatar = null, updatedAt = updated.updatedAt), updated)
        assertEquals(updated, repository.getPost(original.id))
    }

    @Test
    fun `text only edit retains historical sender and card selection can clear character id`() = runTest {
        val repository = repository()
        repository.savePost(post())
        val textOnly = requireNotNull(repository.updatePostContent("post", "文案"))
        assertEquals("character", textOnly.senderCharacterId)
        assertEquals("avatar.png", textOnly.senderAvatar)
        val card = requireNotNull(repository.updatePostContent("post", "文案", MomentSender(null, "角色卡", "card.png")))
        assertNull(card.senderCharacterId)
        assertEquals("card.png", card.senderAvatar)
        assertNull(repository.updatePostContent("missing", "文案", MomentSender(null, "角色卡", null)))
        assertTrue(runCatching { repository.updatePostContent("post", "文案", MomentSender(null, " ", null)) }.isFailure)
    }

    @Test fun `retained images and metadata survive cold storage and selected switch preserves latest text and likes`() = runTest {
        val repository = repository()
        val original = post()
        repository.savePost(original)
        repository.appendImageVersion(original.id, original.imagePath, "second.png", "second prompt", null)
        repository.toggleLike(original.id)
        repository.updatePostText(original.id, "edited after generation")
        val latest = requireNotNull(repository.getPost(original.id))
        assertEquals(2, latest.imageVersions.size)
        repository.selectImageVersion(original.id, latest.imageVersions.first().id)
        val stored = requireNotNull(repository.getPost(original.id))
        assertEquals(original.imagePath, stored.imagePath)
        assertEquals(original.imagePrompt, stored.imagePrompt)
        assertEquals("edited after generation", stored.text)
        assertEquals(!original.userLiked, stored.userLiked)
        val decoded = kotlinx.serialization.json.Json.decodeFromString(MomentPost.serializer(),
            kotlinx.serialization.json.Json.encodeToString(MomentPost.serializer(), stored))
        assertEquals(stored, decoded)
    }

    @Test fun `deleted or reselected post rejects late image append without resurrection`() = runTest {
        val repository = repository()
        val original = post()
        repository.savePost(original)
        repository.appendImageVersion(original.id, original.imagePath, "second.png", "p2", null)
        assertTrue(runCatching { repository.appendImageVersion(original.id, original.imagePath, "stale.png", "p3", null) }.isFailure)
        repository.deletePost(original.id)
        assertTrue(runCatching { repository.appendImageVersion(original.id, "second.png", "late.png", "p4", null) }.isFailure)
        assertNull(repository.getPost(original.id))
    }

    @Test fun legacyNullImagePathAcceptsOnDemandCommit() = checkInitialImageCommit(null)
    @Test fun legacyEmptyImagePathAcceptsOnDemandCommit() = checkInitialImageCommit("")
    @Test fun legacyWhitespaceImagePathAcceptsOnDemandCommit() = checkInitialImageCommit("  ")

    private fun checkInitialImageCommit(path: String?) = runTest {
        val repository = repository()
        val original = post().copy(imagePath = path, imagePrompt = "draft", generatedImageMetadata = null)
        repository.savePost(original)
        repository.appendImageVersion(original.id, null, "generated.png", "generated prompt", null)
        val saved = requireNotNull(repository.getPost(original.id))
        assertEquals("generated.png", saved.imagePath)
        assertEquals("generated prompt", saved.imagePrompt)
        assertEquals(1, saved.imageVersions.size)
        assertEquals(original.text, saved.text)
        assertEquals(original.userLiked, saved.userLiked)
    }

    @Test fun realImagePathsRemainExactAndConcurrentFirstImageWins() = runTest {
        val repository = repository()
        val original = post().copy(imagePath = "  real.png", generatedImageMetadata = null)
        repository.savePost(original)
        assertTrue(runCatching { repository.appendImageVersion(original.id, "real.png", "late.png", "late", null) }.isFailure)
        assertTrue(runCatching { repository.appendImageVersion(original.id, null, "late.png", "late", null) }.isFailure)
        assertEquals(original, repository.getPost(original.id))
        repository.savePost(original.copy(imagePath = " "))
        repository.appendImageVersion(original.id, null, "first.png", "first", null)
        assertTrue(runCatching { repository.appendImageVersion(original.id, null, "late.png", "late", null) }.isFailure)
        assertEquals("first.png", repository.getPost(original.id)?.imagePath)
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
