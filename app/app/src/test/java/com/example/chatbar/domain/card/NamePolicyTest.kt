package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NamePolicyTest {
    @Test fun normalizeAndCompareIgnoreCase() {
        assertEquals("Alice", NamePolicy.normalize("  Alice  "))
        assertTrue(NamePolicy.isSame(" Alice ", "alice"))
    }

    @Test fun copyNameUsesFirstAvailableSuffix() {
        assertEquals("Alice (3)", NamePolicy.nextCopyName("Alice", listOf("Alice", "alice (2)", "Alice (4)")))
    }
}
