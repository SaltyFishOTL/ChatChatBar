package com.example.chatbar.domain.memory

/** Per-session demand counter. Runner stop and concurrent request share one monitor boundary. */
internal class MemoryMaintenanceMailbox {
    private data class Slot(
        var requestedVersion: Long = 0,
        var completedVersion: Long = 0,
        var running: Boolean = false
    )

    private val lock = Any()
    private val slots = mutableMapOf<String, Slot>()

    /** Returns true only when caller must start a runner. */
    fun request(sessionId: String): Boolean = synchronized(lock) {
        val slot = slots.getOrPut(sessionId) { Slot() }
        slot.requestedVersion++
        if (slot.running) return@synchronized false
        slot.running = true
        true
    }

    fun versionToProcess(sessionId: String): Long = synchronized(lock) {
        slots[sessionId]?.requestedVersion ?: 0
    }

    /** Returns true when another pass is required; otherwise atomically stops runner. */
    fun completePass(sessionId: String, processedVersion: Long): Boolean = synchronized(lock) {
        val slot = slots[sessionId] ?: return@synchronized false
        slot.completedVersion = maxOf(slot.completedVersion, processedVersion)
        if (slot.requestedVersion > slot.completedVersion) return@synchronized true
        slot.running = false
        slots.remove(sessionId)
        false
    }

    fun cancel(sessionId: String) = synchronized(lock) {
        slots.remove(sessionId)
        Unit
    }
}
