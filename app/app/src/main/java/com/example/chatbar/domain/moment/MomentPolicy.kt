package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import kotlin.random.Random

object MomentPolicy {
    const val ACTIVE_WINDOW_MS: Long = 48L * 60L * 60L * 1000L
    const val MIN_DELAY_MS: Long = 2L * 60L * 60L * 1000L
    const val MAX_DELAY_MS: Long = 13L * 60L * 60L * 1000L
    const val SCHEDULE_HORIZON_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MAX_CARD_POSTS_PER_DAY = 4
    const val MAX_GLOBAL_POSTS_PER_DAY = 18

    fun isRecentlyActive(lastMessageTime: Long?, now: Long): Boolean =
        lastMessageTime != null && now - lastMessageTime <= ACTIVE_WINDOW_MS

    fun nextDelayMs(seed: String, cursorMs: Long): Long {
        val random = Random(seed.hashCode() * 31 + cursorMs.hashCode())
        return random.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1)
    }

    fun likeCount(tier: String, seed: String, isPrivate: Boolean): Int {
        if (isPrivate) return 0
        val random = Random(seed.hashCode())
        val range = when (tier.trim().lowercase()) {
            "none", "private", "hidden" -> 0..0
            "tiny", "low" -> 0..12
            "normal", "ordinary" -> 2..80
            "popular", "influencer" -> 50..3000
            "celebrity", "star" -> 10_000..500_000
            "alt", "small_account" -> 0..30
            else -> 2..120
        }
        if (range.last == 0) return 0
        val biased = random.nextDouble().let { it * it }
        return range.first + (biased * (range.last - range.first)).toInt()
    }

    fun countForDay(times: Iterable<Long>, targetMs: Long): Int =
        times.count { sameUtcDay(it, targetMs) }

    fun sameUtcDay(leftMs: Long, rightMs: Long): Boolean =
        leftMs.floorUtcDay() == rightMs.floorUtcDay()

    fun Long.floorUtcDay(): Long = this / DAY_MS

    fun shouldCountTask(task: MomentTask): Boolean =
        task.status == MomentTaskStatus.PENDING ||
            task.status == MomentTaskStatus.RUNNING ||
            task.status == MomentTaskStatus.COMPLETED ||
            task.status == MomentTaskStatus.FAILED

    fun scheduledTimesForLimit(posts: List<MomentPost>, tasks: List<MomentTask>): List<Long> =
        posts.map { it.scheduledAt } + tasks.filter(::shouldCountTask).map { it.scheduledAt }

    private const val DAY_MS = 24L * 60L * 60L * 1000L
}
