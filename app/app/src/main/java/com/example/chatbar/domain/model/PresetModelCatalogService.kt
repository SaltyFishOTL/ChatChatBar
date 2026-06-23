package com.example.chatbar.domain.model

import android.content.Context
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetModelCatalog
import com.example.chatbar.data.local.entity.PresetType
import kotlinx.serialization.json.Json

class PresetModelCatalogService(
    private val context: Context,
    private val json: Json
) {
    companion object {
        private const val MANIFEST_PATH = "presets/manifest.json"
        const val PRESET_REF_PREFIX = "preset:"
    }

    val catalog: PresetModelCatalog by lazy {
        val manifest = decodeAsset(MANIFEST_PATH, PresetManifest.serializer())
        val entry = manifest.entries.singleOrNull { it.type == PresetType.MODEL_CATALOG }
            ?: return@lazy PresetModelCatalog()
        decodeAsset(entry.file, PresetModelCatalog.serializer())
    }

    fun entries(): List<PresetEntry> {
        val manifest = decodeAsset(MANIFEST_PATH, PresetManifest.serializer())
        return manifest.entries.filter { it.type == PresetType.MODEL_CATALOG }
    }

    private fun <T> decodeAsset(path: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        context.assets.open(path).bufferedReader().use { json.decodeFromString(serializer, it.readText()) }
}
