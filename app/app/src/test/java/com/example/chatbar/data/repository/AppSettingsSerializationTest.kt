package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsSerializationTest {
    @Test
    fun oldPayload_defaultsAssistantSegmentedBubblesToEnabled() {
        val decoded = Json.decodeFromString(AppSettings.serializer(), """{"themeMode":"DARK"}""")

        assertTrue(decoded.assistantSegmentedBubblesEnabled)
        assertTrue(decoded.excludeAssistantStatusFromHistory)
    }

    @Test
    fun disabledAssistantSegmentedBubbles_roundTrips() {
        val encoded = Json.encodeToString(
            AppSettings.serializer(),
            AppSettings(assistantSegmentedBubblesEnabled = false)
        )

        val decoded = Json.decodeFromString(AppSettings.serializer(), encoded)

        assertFalse(decoded.assistantSegmentedBubblesEnabled)
    }

    @Test
    fun disabledAssistantStatusHistoryExclusion_roundTrips() {
        val encoded = Json.encodeToString(
            AppSettings.serializer(),
            AppSettings(excludeAssistantStatusFromHistory = false)
        )

        val decoded = Json.decodeFromString(AppSettings.serializer(), encoded)

        assertFalse(decoded.excludeAssistantStatusFromHistory)
    }
}
