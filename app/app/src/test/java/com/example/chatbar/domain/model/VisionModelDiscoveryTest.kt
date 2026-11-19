package com.example.chatbar.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionModelDiscoveryTest {
    @Test
    fun preservesLegacyModelIdsWhileReadingExplicitImageCapabilities() {
        val response = """{"data":[
            {"id":"Flash","input_modalities":["text","image"]},
            {"id":"pro","input_modalities":["text"]},
            {"id":"unknown"},
            {"id":"partial","input_modalities":["text",12]},
            {"id":"empty","input_modalities":[]},
            {"id":"Flash"},
            {"id":""}
        ]}"""
        assertEquals(listOf("empty", "Flash", "partial", "pro", "unknown"), parseModelIds(response))
        assertEquals(
            mapOf(
                "empty" to ModelImageInput.UNKNOWN,
                "Flash" to ModelImageInput.SUPPORTED,
                "partial" to ModelImageInput.UNKNOWN,
                "pro" to ModelImageInput.NOT_SUPPORTED,
                "unknown" to ModelImageInput.UNKNOWN
            ),
            parseDiscoveredModels(response).associate { it.id to it.imageInput }
        )
    }

    @Test
    fun onlySuggestsTheOfficialOpenAiCompatibleDeepSeekModel() {
        for (url in listOf("https://api.deepseek.com", "https://API.DEEPSEEK.COM:443/v1/")) {
            assertEquals("deepseek-flash", officialVisionModelSuggestion(url))
        }
        for (url in listOf(
            "http://api.deepseek.com", "https://api.deepseek.com:8443",
            "https://api.deepseek.com.example.test", "https://api.deepseek.com/anthropic",
            "https://api.deepseek.com/?key=no", "https://secret@api.deepseek.com"
        )) assertNull(url, officialVisionModelSuggestion(url))
    }
}
