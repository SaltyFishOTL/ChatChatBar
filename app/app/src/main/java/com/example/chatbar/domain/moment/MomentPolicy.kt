package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import kotlin.random.Random

object MomentPolicy {
    const val ACTIVE_WINDOW_MS: Long = 48L * 60L * 60L * 1000L
    const val DEFAULT_MIN_DELAY_HOURS = 2
    const val DEFAULT_MAX_DELAY_HOURS = 13
    const val MIN_DELAY_MS: Long = DEFAULT_MIN_DELAY_HOURS * 60L * 60L * 1000L
    const val MAX_DELAY_MS: Long = DEFAULT_MAX_DELAY_HOURS * 60L * 60L * 1000L
    const val MIN_CONFIG_DELAY_HOURS = 1
    const val MAX_CONFIG_DELAY_HOURS = 24
    const val SCHEDULE_HORIZON_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MAX_CARD_POSTS_PER_DAY = 4
    const val MAX_GLOBAL_POSTS_PER_DAY = 18

    data class DelayRange(
        val minHours: Int,
        val maxHours: Int
    ) {
        val minMs: Long get() = minHours.hoursToMs()
        val maxMs: Long get() = maxHours.hoursToMs()
    }

    fun isRecentlyActive(lastMessageTime: Long?, now: Long): Boolean =
        lastMessageTime != null && now - lastMessageTime <= ACTIVE_WINDOW_MS

    fun nextDelayMs(seed: String, cursorMs: Long): Long {
        return nextDelayMs(seed, cursorMs, DEFAULT_MIN_DELAY_HOURS, DEFAULT_MAX_DELAY_HOURS)
    }

    fun nextDelayMs(seed: String, cursorMs: Long, minDelayHours: Int, maxDelayHours: Int): Long {
        val range = normalizedDelayHours(minDelayHours, maxDelayHours)
        val random = Random(seed.hashCode() * 31 + cursorMs.hashCode())
        return random.nextLong(range.minMs, range.maxMs + 1L)
    }

    fun normalizedDelayHours(minDelayHours: Int, maxDelayHours: Int): DelayRange {
        val minHours = minDelayHours.coerceIn(MIN_CONFIG_DELAY_HOURS, MAX_CONFIG_DELAY_HOURS)
        val maxHours = maxDelayHours.coerceIn(minHours, MAX_CONFIG_DELAY_HOURS)
        return DelayRange(minHours, maxHours)
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

    fun scheduledTimesForLimit(posts: List<MomentPost>, tasks: List<MomentTask>): List<Long> {
        val countedTasks = tasks.filter(::shouldCountTask)
        val taskPostIds = countedTasks.mapNotNull { it.postId }.toSet()
        val orphanPosts = posts.filterNot { post ->
            post.id in taskPostIds ||
                countedTasks.any { task ->
                    task.characterCardId == post.characterCardId &&
                        task.scheduledAt == post.scheduledAt
                }
        }
        return countedTasks.map { it.scheduledAt } + orphanPosts.map { it.scheduledAt }
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun Int.hoursToMs(): Long = this * 60L * 60L * 1000L
}
