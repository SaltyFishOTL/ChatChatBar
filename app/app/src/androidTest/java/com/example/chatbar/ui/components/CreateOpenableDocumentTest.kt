package com.example.chatbar.ui.components

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateOpenableDocumentTest {

    @Test
    fun createIntent_requestsOpenableJsonDocument() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val intent = CreateOpenableDocument("application/json")
            .createIntent(context, "archive.json")

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals("application/json", intent.type)
        assertEquals("archive.json", intent.getStringExtra(Intent.EXTRA_TITLE))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
    }
}
