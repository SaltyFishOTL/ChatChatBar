package com.example.chatbar.domain.backup

import java.io.StringReader
import java.io.StringWriter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class BackupJsonStreamTest {
    @Test fun streamedMediaReplacementSkipsMappingAndAddsMissingFieldsWithoutChangingText() {
        val output = StringWriter()
        val images = { writer: java.io.Writer -> writer.write("[\"/local/original.png\"]") }
        val observed = StringWriter()
        val text = "正文🙂".repeat(20000)
        val source = "{\"images\":[\"/incoming/new.png\"],\"content\":" + Json.encodeToString(text) + "}"
        BackupJsonStream(StringReader(source), output,
            replaceValueStream = { path, writer -> if (path == listOf("images")) { images(writer); true } else false },
            extraRootStreams = mapOf("images" to images, "generatedImageMetadata" to { it.write("[]") }),
            observeValue = { path -> observed.takeIf { path == listOf("content") } }
        ) { _, _ -> error("Unselected incoming image must not be mapped") }.copy()
        val result = Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals("[\"/local/original.png\"]", result.getValue("images").toString())
        assertEquals("[]", result.getValue("generatedImageMetadata").toString())
        assertEquals(text, result.getValue("content").jsonPrimitive.content)
        assertEquals(text, Json.parseToJsonElement(observed.toString()).jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) {
            BackupJsonStream(StringReader("""{"images":[{"id":"one","id":"two"}]}"""), StringWriter(),
                replaceValueStream = { _, writer -> writer.write("[]"); true }
            ) { value, _ -> value }.copy()
        }
    }

    @Test fun mapsOnlyResourceFieldsAndRetainsPromptAndMessageText() {
        val source = """{"avatar":"/old/a.png","content":"/old/a.png","systemPrompt":"/old/a.png","extensions":{"path":"/old/a.png"},"characterPayload":{"characters":[{"appearanceImage":"/old/a.png"}]}}"""
        val output = StringWriter()
        BackupJsonStream(StringReader(source), output) { path, _ -> path.replace("/old/", "/new/") }.copy()
        val result = Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals("/new/a.png", result.getValue("avatar").jsonPrimitive.content)
        assertEquals("/old/a.png", result.getValue("content").jsonPrimitive.content)
        assertEquals("/old/a.png", result.getValue("systemPrompt").jsonPrimitive.content)
        assertEquals("/old/a.png", result.getValue("extensions").jsonObject.getValue("path").jsonPrimitive.content)
        assertTrue(output.toString().contains("/new/a.png"))
    }

    @Test fun mapsLegacyInlineResourceKeysAndImageReferencesTogether() {
        val source = """{"messages":[{"images":["/old/image.png"]}],"imageResources":{"/old/image.png":{"fileName":"image.png","data":"QUJD"}}}"""
        val output = StringWriter()
        BackupJsonStream(StringReader(source), output) { path, _ -> path.replace("/old/", "/new/") }.copy()
        assertFalse(output.toString().contains("/old/"))
        assertEquals(2, Regex("/new/image.png").findAll(output.toString()).count())
    }

    @Test fun skeletonValidatesRecordsAndStreamsInlinePayloadWithoutRetainingIt() {
        val source = """{"id":"slot","messages":[{"id":"m1"},{"id":"m2"}],"vectorChunks":[],"imageResources":{"x":{"data":"QUJD","fileName":"x.png"}}}"""
        val records = mutableListOf<String>()
        val output = StringWriter()
        BackupJsonStream(StringReader(source), output, validationSkeleton = true,
            validateRecord = { collection, record -> if (collection == "messages") records += record }
        ) { value, _ -> value }.copy()
        assertEquals(2, records.size)
        val skeleton = Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals("slot", skeleton.getValue("id").jsonPrimitive.content)
        assertEquals("[]", skeleton.getValue("messages").toString())
        assertEquals("{}", skeleton.getValue("imageResources").toString())
    }

    @Test fun rejectsMalformedJsonAndDuplicateFields() {
        listOf("""{"id":1,"id":2}""", """{"data":"unterminated}""", """{"id":01}""", """{"id":true} trailing""").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                BackupJsonStream(StringReader(it), StringWriter()) { value, _ -> value }.copy()
            }
        }
    }

    @Test fun rejectsCorruptInlineMediaAndBoundsLargeTextDuringTypedValidation() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupJsonStream(StringReader("""{"imageResources":{"x":{"fileName":"x","data":"@@@@"}}}"""),
                StringWriter(), validationSkeleton = true) { value, _ -> value }.copy()
        }
        val output = StringWriter()
        BackupJsonStream(StringReader("""{"content":"""" + "正文".repeat(40000) + "\"}"),
            output, validationSkeleton = true) { value, _ -> value }.copy()
        assertTrue(output.toString().length < 100)
        assertEquals("validation-long-text", Json.parseToJsonElement(output.toString()).jsonObject
            .getValue("content").jsonPrimitive.content)
    }
}
