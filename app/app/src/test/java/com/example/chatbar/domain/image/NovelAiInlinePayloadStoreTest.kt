package com.example.chatbar.domain.image

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelAiInlinePayloadStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun largeLegacyPayloadIsExternalizedWithoutChangingFoldsPromptsOrSource() {
        val root = temp.newFolder()
        val store = NovelAiInlinePayloadStore(root)
        val file = File(root, "draft.json")
        file.bufferedWriter().use { out ->
            out.write("""{"styleExpanded":false,"characters":[{"enabled":false,"prompt":"测试\\\""}],"vibes":[{"encodedVibe":"""" )
            repeat(4096) { out.write("A".repeat(8192)) }
            out.write(""""}],"negative":"encodedVibe"}""")
        }
        val originalSize = file.length()
        val compact = store.openCompactJson(file).bufferedReader().use { it.readText() }
        assertTrue(compact.length < 1024)
        assertEquals(originalSize, file.length())
        val value = Json.parseToJsonElement(compact).jsonObject
        assertEquals("false", value.getValue("styleExpanded").jsonPrimitive.content)
        assertEquals("false", value.getValue("characters").jsonArray[0].jsonObject.getValue("enabled").jsonPrimitive.content)
        val reference = value.getValue("vibes").jsonArray[0].jsonObject.getValue("encodedVibe").jsonPrimitive.content
        assertEquals(32 * 1024 * 1024, store.resolve(reference).length)
        val second = File(root, "second.json").apply { writeText(compact) }
        assertEquals(compact, store.openCompactJson(second).bufferedReader().use { it.readText() })
        assertEquals(1, File(root, "novelai-vibe-payloads").listFiles()!!.size)
    }

    @Test fun escapesNullAndMalformedInputDoNotLoseOriginalData() {
        val root = temp.newFolder()
        val store = NovelAiInlinePayloadStore(root)
        val file = File(root, "draft.json")
        val text = """{"encodedVibe":"ab\/cd\u002b","other":{"encodedVibe":null},"prompt":"encodedVibe"}"""
        file.writeText(text)
        assertEquals(Json.parseToJsonElement(text), Json.parseToJsonElement(store.openCompactJson(file).bufferedReader().use { it.readText() }))
        file.writeText("""{"encodedVibe":"unterminated""")
        val before = file.readText()
        assertTrue(runCatching { store.openCompactJson(file).close() }.isFailure)
        assertEquals(before, file.readText())
        assertFalse(File(root, "novelai-vibe-payloads").listFiles()!!.any { it.extension == "tmp" })
    }
}
