package com.example.chatbar.domain.card

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SharedImportEvent<out T>(
    val id: Long,
    val value: T,
    val claimed: Boolean = false
)

/**
 * Keeps one external share event until its consumer finishes handling it.
 * Claiming prevents a recomposed screen from handling the same event twice.
 */
class SharedImportEventFlow<T> {
    private val _events = MutableStateFlow<SharedImportEvent<T>?>(null)
    val events: StateFlow<SharedImportEvent<T>?> = _events.asStateFlow()

    private var nextId = 0L

    @Synchronized
    fun publish(value: T) {
        nextId += 1L
        _events.value = SharedImportEvent(id = nextId, value = value)
    }

    fun claim(id: Long): Boolean {
        while (true) {
            val current = _events.value ?: return false
            if (current.id != id || current.claimed) return false
            if (_events.compareAndSet(current, current.copy(claimed = true))) return true
        }
    }

    fun complete(id: Long): Boolean {
        while (true) {
            val current = _events.value ?: return false
            if (current.id != id) return false
            if (_events.compareAndSet(current, null)) return true
        }
    }
}
