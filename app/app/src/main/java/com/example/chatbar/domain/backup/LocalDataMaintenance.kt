package com.example.chatbar.domain.backup

import java.io.Closeable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A process-wide admission barrier. Leases may cross coroutine/thread boundaries. */
object LocalDataMaintenance {
    private val monitor = Any()
    private var accesses = 0
    private var exclusive = false
    private val _blocked = MutableStateFlow(false)
    val blocked = _blocked.asStateFlow()

    fun enter(): Closeable {
        synchronized(monitor) {
            check(!exclusive) { "正在迁移本地数据，请完成后重试" }
            accesses++
        }
        var closed = false
        return Closeable {
            synchronized(monitor) {
                if (!closed) {
                    closed = true
                    accesses--
                }
            }
        }
    }

    inline fun <T> access(block: () -> T): T = enter().use { block() }

    suspend fun acquire(): Closeable {
        // Do not cancel existing work. Short writes can drain; long jobs require retry.
        var quietSamples = 0
        repeat(100) {
            synchronized(monitor) {
                if (!exclusive && accesses == 0) {
                    // Allow already-scheduled draft/pref writes to enter before taking a snapshot.
                    if (++quietSamples >= 8) {
                        exclusive = true
                        _blocked.value = true
                        return Closeable {
                            synchronized(monitor) {
                                exclusive = false
                                _blocked.value = false
                            }
                        }
                    }
                } else quietSamples = 0
                check(!exclusive) { "已有数据迁移正在进行" }
            }
            delay(20)
        }
        error("生成、索引或数据写入尚未完成，请稍后重试")
    }
}
