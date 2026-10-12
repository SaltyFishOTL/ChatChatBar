package com.example.chatbar.domain.moment

import android.content.ContextWrapper
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.draft.EditorDraftAssetService
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MomentMediaRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cardCleanupKeepsHistoricalAvatarAndImageButRemovesUnreferencedAssets() = runTest {
        val root = temporary.newFolder()
        val avatar = File(root, "old-avatar.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val image = File(root, "old-image.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val discarded = File(root, "unused-background.png").apply { writeBytes(byteArrayOf(7)) }
        val post = MomentPost(id = "post", characterCardId = "card", sessionId = "session",
            senderName = "历史人物", senderAvatar = avatar.toURI().toString(), text = "原文",
            imagePath = image.path, scheduledAt = 1, generatedAt = 2)
        val context = object : ContextWrapper(null) { override fun getFilesDir() = root }
        val removable = MomentMediaRetention.unreferencedPaths(
            listOf(avatar.path, image.path, discarded.path, discarded.path), listOf(post)
        )
        EditorDraftAssetService(context).deleteFiles(removable)
        assertArrayEquals(byteArrayOf(1, 2, 3), avatar.readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), image.readBytes())
        assertFalse(discarded.exists())
        assertTrue(MomentMediaRetention.unreferencedPaths(listOf(avatar.path), listOf(post)).isEmpty())
    }

    @Test fun removingLastHistoricalReferenceMakesItsFileEligibleForCleanup() = runTest {
        val root = temporary.newFolder()
        val avatar = File(root, "avatar.png").apply { writeBytes(byteArrayOf(1)) }
        val context = object : ContextWrapper(null) { override fun getFilesDir() = root }
        EditorDraftAssetService(context).deleteFiles(
            MomentMediaRetention.unreferencedPaths(listOf(avatar.path), emptyList())
        )
        assertFalse(avatar.exists())
    }
}
