package com.example.chatbar.ui.imageprompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiCanvasHistoryTest {
    @Test
    fun redoCanBeUndoneAgain() {
        val undo = mutableListOf("A", "B")
        val redo = mutableListOf<String>()

        val afterUndo = undoCanvasState("C", undo, redo)
        val afterRedo = redoCanvasState(afterUndo!!, undo, redo)
        val afterSecondUndo = undoCanvasState(afterRedo!!, undo, redo)

        assertEquals("B", afterUndo)
        assertEquals("C", afterRedo)
        assertEquals("B", afterSecondUndo)
        assertEquals(listOf("C"), redo)
    }

    @Test
    fun newEditCanClearRedoWithoutLosingUndoOrder() {
        val undo = mutableListOf("A")
        val redo = mutableListOf<String>()
        val restored = undoCanvasState("B", undo, redo)

        undo += restored!!
        redo.clear()

        assertEquals(listOf("A"), undo)
        assertTrue(redo.isEmpty())
    }
}
