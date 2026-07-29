package com.example.chatbar.ui.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CharacterReferenceDocumentLimitsTest {

    @Test
    fun `documents over warning threshold remain supported`() {
        requireSupportedCharacterReferenceDocumentLength(
            CHARACTER_REFERENCE_DOCUMENT_WARNING_CHARS + 1
        )
    }

    @Test
    fun `maximum document length remains supported`() {
        requireSupportedCharacterReferenceDocumentLength(
            MAX_CHARACTER_REFERENCE_DOCUMENT_CHARS
        )
    }

    @Test
    fun `documents over maximum length are rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            requireSupportedCharacterReferenceDocumentLength(
                MAX_CHARACTER_REFERENCE_DOCUMENT_CHARS + 1
            )
        }

        assertEquals("参考文档超过 500 万字符限制", error.message)
    }
}
