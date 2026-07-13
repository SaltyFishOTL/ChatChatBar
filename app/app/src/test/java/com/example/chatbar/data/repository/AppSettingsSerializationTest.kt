package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY
import com.example.chatbar.data.local.entity.withNormalizedAppearance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsSerializationTest {
    @Test
    fun oldPayload_defaultsAssistantSegmentedBubblesToEnabled() {
        val decoded = Json.decodeFromString(AppSettings.serializer(), """{"themeMode":"DARK"}""")

        assertTrue(decoded.assistantSegmentedBubblesEnabled)
        assertTrue(decoded.excludeAssistantStatusFromHistory)
        assertFalse(decoded.allowCleartextModelApi)
        assertFalse(decoded.automaticFormatCheckEnabled)
        assertEquals(null, decoded.formatRepairModelId)
        assertEquals(DEFAULT_CHAT_BACKGROUND_IMAGE_OPACITY, decoded.chatBackgroundImageOpacity)
    }

    @Test
    fun formatRepairSettings_roundTrip() {
        val encoded = Json.encodeToString(
            AppSettings.serializer(),
            AppSettings(
                automaticFormatCheckEnabled = true,
                formatRepairModelId = "repair-model"
            )
        )

        val decoded = Json.decodeFromString(AppSettings.serializer(), encoded)

        assertTrue(decoded.automaticFormatCheckEnabled)
        assertEquals("repair-model", decoded.formatRepairModelId)
    }

    @Test
    fun enabledCleartextModelApi_roundTrips() {
        val encoded = Json.encodeToString(
            AppSettings.serializer(),
            AppSettings(allowCleartextModelApi = true)
        )

        val decoded = Json.decodeFromString(AppSettings.serializer(), encoded)

        assertTrue(decoded.allowCleartextModelApi)
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

    @Test
    fun backgroundImageOpacity_isNormalizedToSupportedRange() {
        assertEquals(1f, AppSettings(chatBackgroundImageOpacity = 2f).withNormalizedAppearance().chatBackgroundImageOpacity)
        assertEquals(0f, AppSettings(chatBackgroundImageOpacity = -1f).withNormalizedAppearance().chatBackgroundImageOpacity)
    }
}
