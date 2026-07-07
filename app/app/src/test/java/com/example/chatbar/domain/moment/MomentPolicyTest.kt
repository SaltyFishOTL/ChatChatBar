package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
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
    fun newSettingsAndCharacterFields_defaultOffForOldJson() {
        val json = Json { ignoreUnknownKeys = true }

        val settings = json.decodeFromString<AppSettings>("{}")
        val card = json.decodeFromString<CharacterCard>(
            """{"id":"card","name":"角色","createdAt":1,"updatedAt":1}"""
        )

        assertFalse(settings.momentsEnabled)
        assertFalse(settings.momentsAutoStartConfirmed)
        assertFalse(card.momentsEnabled)
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
