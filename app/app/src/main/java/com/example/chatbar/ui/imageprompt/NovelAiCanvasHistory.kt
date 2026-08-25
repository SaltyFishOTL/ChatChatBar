package com.example.chatbar.ui.imageprompt

internal fun <T> undoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? {
    val previous = undo.removeLastOrNull() ?: return null
    redo += current
    return previous
}

internal fun <T> redoCanvasState(current: T, undo: MutableList<T>, redo: MutableList<T>): T? {
    val next = redo.removeLastOrNull() ?: return null
    undo += current
    return next
}
