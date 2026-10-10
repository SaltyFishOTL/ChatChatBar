package com.example.chatbar.domain.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.security.FishAudioCredentialStore
import com.example.chatbar.data.security.NovelAiCredentialStore
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preferences and key aliases: never touches installed user's credentials. */
@RunWith(AndroidJUnit4::class)
class BackupCredentialTransferTest {
    @Test fun portableSecretsAreReencryptedByDestinationKeysAndUndecryptableStateBlocksExport() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "chatbar-backup-test-" + UUID.randomUUID()
        val aliases = listOf(prefix + "-old-novel", prefix + "-new-novel", prefix + "-old-fish", prefix + "-new-fish")
        val usedPreferences = mutableSetOf<String>()
        fun isolated(device: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolatedName = prefix + "-" + device + "-" + name
                usedPreferences += isolatedName
                return base.getSharedPreferences(isolatedName, mode)
            }
        }
        val source = isolated("source")
        val target = isolated("target")
        try {
            val oldNovel = NovelAiCredentialStore(source, aliases[0])
            val newNovel = NovelAiCredentialStore(target, aliases[1])
            val oldFish = FishAudioCredentialStore(source, aliases[2])
            val newFish = FishAudioCredentialStore(target, aliases[3])
            assertNull(oldNovel.readForBackup())
            assertNull(oldFish.readForBackup())
            oldNovel.replaceFromBackup("synthetic-novel-secret")
            oldFish.replaceFromBackup("synthetic-fish-secret")
            newNovel.replaceFromBackup(oldNovel.readForBackup())
            newFish.replaceFromBackup(oldFish.readForBackup())
            assertEquals(oldNovel.load(), newNovel.load())
            assertEquals(oldFish.load(), newFish.load())
            for (name in listOf("novelai_credentials", "fish_audio_credentials")) {
                val original = source.getSharedPreferences(name, Context.MODE_PRIVATE)
                val destination = target.getSharedPreferences(name, Context.MODE_PRIVATE)
                assertNotEquals(original.all, destination.all)
                val edit = destination.edit().clear()
                original.all.forEach { (key, value) -> edit.putString(key, value as String) }
                assertTrue(edit.commit())
            }
            assertNull(newNovel.load())
            assertNull(newFish.load())
            assertThrows(IllegalStateException::class.java) { newNovel.readForBackup() }
            assertThrows(IllegalStateException::class.java) { newFish.readForBackup() }
            newNovel.replaceFromBackup(null)
            newFish.replaceFromBackup(null)
            assertNull(newNovel.readForBackup())
            assertNull(newFish.readForBackup())
        } finally {
            usedPreferences.forEach { base.deleteSharedPreferences(it) }
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { keys.deleteEntry(it) }
        }
    }
}
