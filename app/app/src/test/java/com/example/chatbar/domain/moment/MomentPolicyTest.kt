package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class MomentPolicyTest {
    @Test
    fun activeWindow_requiresCommunicationWithin48Hours() {
        val now = 100L * 60L * 60L * 1000L

        assertTrue(MomentPolicy.isRecentlyActive(now - MomentPolicy.ACTIVE_WINDOW_MS, now))
        assertFalse(MomentPolicy.isRecentlyActive(now - MomentPolicy.ACTIVE_WINDOW_MS - 1L, now))
        assertFalse(MomentPolicy.isRecentlyActive(null, now))
    }

    @Test
    fun nextDelay_staysWithinTwoToThirteenHours() {
        val delay = MomentPolicy.nextDelayMs("card-a", 12345L)

        assertTrue(delay >= MomentPolicy.MIN_DELAY_MS)
        assertTrue(delay <= MomentPolicy.MAX_DELAY_MS)
    }

    @Test
    fun privateMomentLikeCount_isAlwaysZero() {
        assertEquals(0, MomentPolicy.likeCount("celebrity", "seed", isPrivate = true))
    }

    @Test
    fun displayLikeCount_updatesWhenUserLikesPublicOrPrivateMoment() {
        val publicPost = post(isPrivate = false, baseLikeCount = 12, userLiked = false)
        val privatePost = post(isPrivate = true, baseLikeCount = 0, userLiked = false)

        assertEquals(12, publicPost.displayLikeCount)
        assertEquals(13, publicPost.copy(userLiked = true).displayLikeCount)
        assertEquals(0, privatePost.displayLikeCount)
        assertEquals(1, privatePost.copy(userLiked = true).displayLikeCount)
    }

    @Test
    fun failedTasksStillCountAsScheduledCandidates() {
        val failed = task(MomentTaskStatus.FAILED, scheduledAt = 1_000L)
        val skipped = task(MomentTaskStatus.SKIPPED, scheduledAt = 2_000L)

        val scheduled = MomentPolicy.scheduledTimesForLimit(posts = emptyList(), tasks = listOf(failed, skipped))

        assertEquals(listOf(1_000L), scheduled)
    }

    @Test
    fun linkedPostAndTask_countOnlyOnceForScheduleLimits() {
        val post = post(isPrivate = false, baseLikeCount = 0, userLiked = false).copy(
            id = "post-linked",
            scheduledAt = 1_000L
        )
        val task = task(MomentTaskStatus.COMPLETED, scheduledAt = 1_000L).copy(postId = post.id)

        val scheduled = MomentPolicy.scheduledTimesForLimit(posts = listOf(post), tasks = listOf(task))

        assertEquals(listOf(1_000L), scheduled)
    }

    @Test
    fun orphanPost_stillCountsForScheduleLimits() {
        val post = post(isPrivate = false, baseLikeCount = 0, userLiked = false).copy(scheduledAt = 1_000L)
        val task = task(MomentTaskStatus.PENDING, scheduledAt = 2_000L)

        val scheduled = MomentPolicy.scheduledTimesForLimit(posts = listOf(post), tasks = listOf(task))

        assertEquals(listOf(2_000L, 1_000L), scheduled)
    }

    @Test
    fun newSettingsDefaultOffAndCharacterDefaultOnForOldJson() {
        val json = Json { ignoreUnknownKeys = true }

        val settings = json.decodeFromString<AppSettings>("{}")
        val card = json.decodeFromString<CharacterCard>(
            """{"id":"card","name":"角色","createdAt":1,"updatedAt":1}"""
        )
        val disabledCard = json.decodeFromString<CharacterCard>(
            """{"id":"card-disabled","name":"角色","momentsEnabled":false,"createdAt":1,"updatedAt":1}"""
        )

        assertFalse(settings.momentsEnabled)
        assertFalse(settings.momentsAutoStartConfirmed)
        assertTrue(card.momentsEnabled)
        assertFalse(disabledCard.momentsEnabled)
    }

    @Test
    fun oldMomentPostJson_defaultsToNonPlaceholder() {
        val json = Json { ignoreUnknownKeys = true }

        val post = json.decodeFromString<MomentPost>(
            """{"id":"post","characterCardId":"card","sessionId":"session","senderName":"角色","text":"动态","scheduledAt":1,"generatedAt":1}"""
        )

        assertFalse(post.isPlaceholder)
        assertNull(post.failureReason)
    }

    @Test
    fun placeholderPost_hasFailureReasonAndNoContent() {
        val post = MomentPost.createPlaceholder(
            characterCardId = "card",
            sessionId = "session",
            senderCharacterId = "sender",
            senderName = "角色",
            senderAvatar = "avatar.png",
            failureReason = "生图失败",
            scheduledAt = 123L,
            generatedAt = 456L
        )

        assertTrue(post.isPlaceholder)
        assertEquals("生图失败", post.failureReason)
        assertEquals("", post.text)
        assertNull(post.imagePath)
        assertEquals(123L, post.scheduledAt)
        assertEquals(456L, post.generatedAt)
    }

    private fun task(status: MomentTaskStatus, scheduledAt: Long) = MomentTask(
        id = "task-$status",
        characterCardId = "card",
        sessionId = "session",
        scheduledAt = scheduledAt,
        status = status
    )

    private fun post(
        isPrivate: Boolean,
        baseLikeCount: Int,
        userLiked: Boolean
    ) = MomentPost(
        id = "post-$isPrivate-$userLiked",
        characterCardId = "card",
        sessionId = "session",
        senderName = "角色",
        text = "动态",
        isPrivate = isPrivate,
        baseLikeCount = baseLikeCount,
        userLiked = userLiked,
        scheduledAt = 1L,
        generatedAt = 1L
    )
}
