package com.example.chatbar.data.security

import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.repository.LlmApiAccountRepository
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmApiAccountStoreTest {
    @Test fun keyIsEncryptedOnDiskAndReopensWithTheSameBinding() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedName = "llm-account-test-${UUID.randomUUID()}"
        val data = File(context.noBackupFilesDir, isolatedName)
        try {
            val store = LlmApiAccountStore(context, isolatedName, isolatedName)
            val repository = LlmApiAccountRepository(store)
            val account = repository.create("Fixture", "https://fixture.example/v1", "synthetic-keystore-key")
            repository.update(account.id, "Renamed", null)
            assertFalse(data.readBytes().decodeToString().contains("synthetic-keystore-key"))
            val fresh = LlmApiAccountRepository(LlmApiAccountStore(context, isolatedName, isolatedName))
            fresh.initialize()
            assertEquals("Renamed", fresh.connection(account.id)?.account?.displayName)
            assertEquals("https://fixture.example/v1", fresh.connection(account.id)?.account?.baseUrl)
            assertEquals("synthetic-keystore-key", fresh.connection(account.id)?.apiKey)
        } finally {
            AtomicFile(data).delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(isolatedName)
        }
    }
}
