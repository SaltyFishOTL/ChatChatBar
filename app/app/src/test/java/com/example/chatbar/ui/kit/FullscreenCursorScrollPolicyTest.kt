package com.example.chatbar.ui.kit

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FullscreenCursorScrollPolicyTest {
    @Test
    fun activeCursorLineAlignsToTopWithinScrollRange() {
        assertEquals(
            240,
            fullscreenCursorScrollTarget(
                cursorTopPx = 240.4f,
                maxScrollPx = 900,
                imeVisible = true,
                selection = TextRange(12)
            )
        )
    }

    @Test
    fun firstLineAndEndOfDocumentAreClamped() {
        assertEquals(0, fullscreenCursorScrollTarget(-4f, 900, true, TextRange(0)))
        assertEquals(900, fullscreenCursorScrollTarget(1_400f, 900, true, TextRange(80)))
    }

    @Test
    fun hiddenImeDoesNotMoveViewport() {
        assertNull(fullscreenCursorScrollTarget(240f, 900, false, TextRange(12)))
    }

    @Test
    fun expandedSelectionLeavesScrollingToTextField() {
        assertNull(fullscreenCursorScrollTarget(240f, 900, true, TextRange(4, 12)))
    }
}
