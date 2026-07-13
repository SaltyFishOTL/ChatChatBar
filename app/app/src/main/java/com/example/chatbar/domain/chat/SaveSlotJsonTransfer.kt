package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.SaveSlot
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream

object SaveSlotJsonTransfer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun write(slot: SaveSlot, output: OutputStream) {
        json.encodeToStream(SaveSlot.serializer(), slot, output)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun read(input: InputStream): SaveSlot =
        json.decodeFromStream(SaveSlot.serializer(), input)
}
