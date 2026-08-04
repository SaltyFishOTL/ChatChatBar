package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedImportClassifierTest {

    private fun packageJson(extraFields: Map<String, String>): String {
        val fields = buildList {
            add(""" "schemaVersion": 1 """)
            add(""" "exportedAt": 1700000000000 """)
            extraFields.forEach { (key, value) -> add(""" "$key": $value """) }
        }
        return "{ ${fields.joinToString(", ")} }"
    }

    @Test
    fun classifiesCharacterPackage() {
        val json = packageJson(
            mapOf(
                "card" to """{"name":"林雾","characters":[{"name":"林雾"}]}""",
                "documents" to "[]",
                "images" to "{}",
                "worldBooks" to "[]"
            )
        )
        assertEquals(SharedImportType.Character, SharedImportClassifier.classify(json))
    }

    @Test
    fun classifiesFormatPackage() {
        val json = packageJson(mapOf("name" to """ "测试格式" """, "content" to """ "{{char}}..." """))
        assertEquals(SharedImportType.Format, SharedImportClassifier.classify(json))
    }

    @Test
    fun classifiesWorldBookPackage() {
        val json = packageJson(mapOf("book" to """{"id":"b1","name":"设定集"}"""))
        assertEquals(SharedImportType.WorldBook, SharedImportClassifier.classify(json))
    }

    @Test
    fun classifiesModelTemplate() {
        val json = packageJson(
            mapOf(
                "displayName" to """ "DeepSeek" """,
                "baseUrl" to """ "https://api.deepseek.com" """,
                "modelName" to """ "deepseek-chat" """,
                "isMultimodal" to "false",
                "templateType" to """ "openai" """,
                "customParams" to "{}"
            )
        )
        assertEquals(SharedImportType.ModelTemplate, SharedImportClassifier.classify(json))
    }

    @Test
    fun classifiesCharacterBeforeFormatKeys() {
        val json = packageJson(
            mapOf(
                "name" to """ "角色名" """,
                "content" to """ "内容" """,
                "card" to """{"name":"角色"}"""
            )
        )
        assertEquals(SharedImportType.Character, SharedImportClassifier.classify(json))
    }

    @Test
    fun classifiesWorldBookBeforeOtherKeys() {
        val json = packageJson(
            mapOf(
                "name" to """ "书名" """,
                "content" to """ "内容" """,
                "book" to """{"id":"b1"}"""
            )
        )
        assertEquals(SharedImportType.WorldBook, SharedImportClassifier.classify(json))
    }

    @Test
    fun unknownForNonJsonAndForeignText() {
        assertEquals(SharedImportType.Unknown, SharedImportClassifier.classify("这不是 JSON"))
        assertEquals(SharedImportType.Unknown, SharedImportClassifier.classify("{}"))
    }
}
