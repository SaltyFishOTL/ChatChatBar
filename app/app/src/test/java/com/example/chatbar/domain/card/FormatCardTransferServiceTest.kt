package com.example.chatbar.domain.card

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.repository.FormatCardRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FormatCardTransferServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun versionOnePackageDecodesWithEmptyTools() {
        val service = newService()
        val decoded = service.decode(
            """
            {
              "schemaVersion": 1,
              "name": "旧格式",
              "content": "旧要求"
            }
            """.trimIndent()
        )

        assertEquals(1, decoded.schemaVersion)
        assertEquals(emptyList<FormatCardUserToolConfig>(), decoded.userTools)
    }

    @Test
    fun bothUserToolTypesDecodeThroughTransferPipeline() {
        val decoded = newService().decode(
            """
            {
              "schemaVersion": 2,
              "name": "工具示例",
              "content": "格式要求",
              "userTools": [
                {
                  "type": "RANDOM_NUMBER",
                  "minimum": "1",
                  "maximum": "100"
                },
                {
                  "type": "STRONG_PROMPT_SUFFIX",
                  "text": "请严格遵守当前格式卡的全部要求。"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(FORMAT_CARD_PACKAGE_SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(
            listOf(
                FormatCardUserToolType.RANDOM_NUMBER,
                FormatCardUserToolType.STRONG_PROMPT_SUFFIX
            ),
            decoded.userTools.map { it.type }
        )
        assertEquals("1", decoded.userTools.first().minimum)
        assertEquals("100", decoded.userTools.first().maximum)
        assertEquals("请严格遵守当前格式卡的全部要求。", decoded.userTools.last().text)
    }

    @Test
    fun exportImportOverwriteAndDuplicatePreserveOrderedTools() = runTest {
        val repository = newRepository()
        val service = FormatCardTransferService(repository, json)
        val tools = listOf(
            FormatCardUserToolConfig.strongPromptSuffix().copy(text = "第一项"),
            FormatCardUserToolConfig.randomNumber().copy(minimum = "-2", maximum = "2")
        )
        val source = FormatCard(
            id = "source",
            name = "工具格式",
            content = "格式要求",
            userTools = tools,
            isDefault = true,
            createdAt = 1L
        )
        repository.save(source)

        val exported = service.decode(service.exportJson(source.id))
        assertEquals(FORMAT_CARD_PACKAGE_SCHEMA_VERSION, exported.schemaVersion)
        assertEquals(tools, exported.userTools)

        val duplicate = service.duplicate(source.id)
        assertEquals(tools, duplicate.userTools)
        assertFalse(duplicate.isDefault)

        val imported = service.importNew(exported.copy(name = "导入格式"))
        assertEquals(tools, imported.userTools)

        val overwritten = service.overwrite(
            existingId = source.id,
            packageData = exported.copy(
                userTools = listOf(FormatCardUserToolConfig.strongPromptSuffix().copy(text = "替换"))
            )
        )
        assertEquals(source.id, overwritten.id)
        assertEquals("替换", overwritten.userTools.single().text)
        assertEquals(1L, overwritten.createdAt)
        assertEquals(true, overwritten.isDefault)
    }

    private fun newService(): FormatCardTransferService = FormatCardTransferService(newRepository(), json)

    @Test
    fun presetDependencyRestoresOnceAndKeepsExistingEdits() = runTest {
        val repository = newRepository()
        val service = FormatCardTransferService(repository, json)
        val dependency = FormatCardPackage(name = "preset", content = "original", sourcePresetKey = "test-format")
        val restored = service.importCharacterDefault(dependency)
        assertEquals("test-format", restored.sourcePresetKey)
        repository.save(restored.copy(content = "user edit"))
        assertEquals(restored.id, service.importCharacterDefault(dependency).id)
        assertEquals("user edit", repository.getById(restored.id)?.content)
        assertEquals(1, repository.getAll().size)
    }

    @Test
    fun characterBindingReusesExactContentAndPreservesLocalConflictsAndDefault() = runTest {
        val repository = newRepository()
        val service = FormatCardTransferService(repository, json)
        val local = FormatCard.create("格式", "本地内容", isDefault = true)
        repository.save(local)
        val same = service.importCharacterDefault(FormatCardPackage(name = "格式", content = "本地内容"))
        assertEquals(local.id, same.id)
        val incoming = FormatCardPackage(
            name = "格式", content = "传入内容",
            userTools = listOf(FormatCardUserToolConfig.randomNumber())
        )
        val imported = service.importCharacterDefault(incoming)
        assertFalse(local.id == imported.id)
        assertFalse(local.name == imported.name)
        assertFalse(imported.isDefault)
        assertEquals(incoming.userTools, imported.userTools)
        assertEquals(local, repository.getById(local.id))
        assertEquals(local.id, repository.getDefault()?.id)
        val toolConflict = service.importCharacterDefault(
            FormatCardPackage(name = local.name, content = local.content, userTools = incoming.userTools)
        )
        assertFalse(local.id == toolConflict.id)
    }

    private fun newRepository(): FormatCardRepository = FormatCardRepository(
        JsonFileStorage(TestContext(temp.newFolder("files-${System.nanoTime()}")))
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
