package com.example.chatbar.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Creates a Storage Access Framework document that is guaranteed to be stream-openable.
 *
 * Android's ACTION_CREATE_DOCUMENT contract requires CATEGORY_OPENABLE. The AndroidX contract
 * does not add it, which lets some system download handlers return a Uri without a write grant.
 */
class CreateOpenableDocument(mimeType: String) :
    ActivityResultContracts.CreateDocument(mimeType) {

    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input)
            .addCategory(Intent.CATEGORY_OPENABLE)
}
