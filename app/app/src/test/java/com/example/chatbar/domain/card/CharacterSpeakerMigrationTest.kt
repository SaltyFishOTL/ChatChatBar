package com.example.chatbar.domain.card

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.repository.CharacterRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterSpeakerMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun migrationSuffixesLegacyDuplicatesOnceAndKeepsTimestamp() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val repository = CharacterRepository(storage)
        repository.save(
            CharacterCard(
                id = "card",
                name = "旧卡",
                characters = listOf(
                    CharacterInfo(id = "first", name = " 爱音 "),
                    CharacterInfo(id = "second", name = "爱音"),
                    CharacterInfo(id = "third", name = "爱音 (2)")
                ),
                createdAt = 1,
                updatedAt = 9
            )
        )
        val migration = CharacterSpeakerMigration(storage, repository)

        migration.run()
        val firstRun = requireNotNull(repository.getById("card"))
        migration.run()
        val secondRun = requireNotNull(repository.getById("card"))

        assertEquals(listOf("爱音", "爱音 (2)", "爱音 (2) (2)"), firstRun.characters.map { it.name })
        assertEquals(firstRun, secondRun)
        assertEquals(9, secondRun.updatedAt)
    }

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
