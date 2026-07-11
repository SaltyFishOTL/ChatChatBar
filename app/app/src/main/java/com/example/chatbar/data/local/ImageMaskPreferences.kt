package com.example.chatbar.data.local

import android.content.Context

class ImageMaskPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun loadBrushSize(): Float = preferences.getFloat(BRUSH_SIZE, DEFAULT_BRUSH_SIZE).coerceIn(16f, 72f)

    fun loadBrushType(): String = preferences.getString(BRUSH_TYPE, DEFAULT_BRUSH_TYPE) ?: DEFAULT_BRUSH_TYPE

    fun saveBrushSize(value: Float) {
        preferences.edit().putFloat(BRUSH_SIZE, value.coerceIn(16f, 72f)).apply()
    }

    fun saveBrushType(value: String) {
        preferences.edit().putString(BRUSH_TYPE, value).apply()
    }

    private companion object {
        const val NAME = "image_mask_preferences"
        const val BRUSH_SIZE = "brush_size"
        const val BRUSH_TYPE = "brush_type"
        const val DEFAULT_BRUSH_SIZE = 36f
        const val DEFAULT_BRUSH_TYPE = "Mosaic"
    }
}
