package com.example.chatbar.domain.card

internal class SharedImportFifoQueue<T> {
    private val values = mutableListOf<T>()

    val size: Int get() = values.size

    fun add(value: T) {
        values += value
    }

    fun firstOrNull(): T? = values.firstOrNull()

    fun find(predicate: (T) -> Boolean): T? = values.firstOrNull(predicate)

    fun removeFirst(expected: T): Boolean {
        if (values.firstOrNull() !== expected) return false
        values.removeAt(0)
        return true
    }

    fun snapshot(): List<T> = values.toList()
}
