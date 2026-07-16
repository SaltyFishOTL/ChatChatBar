package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MomentPostSerializationTest {
    @Test
    fun oldPayload_defaultsGeneratedImageMetadataToNull() {
        val decoded = Json.decodeFromString(
            MomentPost.serializer(),
            """{"id":"p","characterCardId":"c","sessionId":"s","senderName":"角色","text":"动态","imagePath":"old.png","imagePrompt":"1girl","scheduledAt":1,"generatedAt":2}"""
        )

        assertNull(decoded.generatedImageMetadata)
        assertEquals("1girl", decoded.imagePrompt)
    }

    @Test
    fun generatedImageMetadata_roundTripsAllEditablePrompts() {
        val metadata = GeneratedImageMetadata(
            imagePath = "moment.png",
            baseCaption = "rainy street",
            characterPrompts = listOf(
                GeneratedImageCharacterPrompt("1girl, silver hair", 0.25f, 0.6f)
            ),
            negativePrompt = "watermark, text",
            sizePreset = "HORIZONTAL",
            width = 1216,
            height = 832
        )
        val post = MomentPost(
            id = "p",
            characterCardId = "c",
            sessionId = "s",
            senderName = "角色",
            text = "动态",
            imagePath = metadata.imagePath,
            imagePrompt = metadata.baseCaption,
            generatedImageMetadata = metadata,
            scheduledAt = 1,
            generatedAt = 2
        )

        val decoded = Json.decodeFromString(
            MomentPost.serializer(),
            Json.encodeToString(MomentPost.serializer(), post)
        )

        assertEquals(metadata, decoded.generatedImageMetadata)
    }
}
