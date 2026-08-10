package com.example.chatbar.domain.image

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiCodexCatalogTest {
    @Test
    fun `parser rejects unsupported schema`() {
        val result = NovelAiCodexCatalogParser(Json { ignoreUnknownKeys = true }).parse(
            """{"schemaVersion":2,"entries":[],"rewriteRules":[]}"""
        )

        assertTrue(result.fatalError.orEmpty().contains("不支持"))
        assertTrue(result.catalog.entries.isEmpty())
    }

    @Test
    fun `parser removes invalid and duplicate entries`() {
        val result = NovelAiCodexCatalogParser(Json { ignoreUnknownKeys = true }).parse(
            """
            {
              "schemaVersion":1,
              "entries":[
                {"id":"one","kind":"COMPOSITION","title":"灯笼夜市","prompt":"lanterns, night market"},
                {"id":"one","kind":"WARDROBE","title":"重复","prompt":"dress"},
                {"id":"blank","kind":"CORE","title":"","prompt":"bad"}
              ],
              "rewriteRules":[]
            }
            """.trimIndent()
        )

        assertNull(result.fatalError)
        assertEquals(listOf("one"), result.catalog.entries.map { it.id })
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `fuzzy search recalls multiple reference kinds from vague concepts`() {
        val catalog = NovelAiCodexCatalog(
            schemaVersion = 1,
            entries = listOf(
                entry("scene", NovelAiCodexKind.COMPOSITION, "灯笼夜市", "night market, red lanterns"),
                entry("dress", NovelAiCodexKind.WARDROBE, "礼服", "evening dress, long skirt"),
                entry("irrelevant", NovelAiCodexKind.CORE, "海边", "beach, waves")
            )
        )
        val result = NovelAiCodexSearchEngine(catalog, nextDouble = { 0.0 }).search(
            queries = listOf("礼服长裙"),
            sceneDescription = "角色穿着长礼服走进灯笼夜市，红色灯火映在湿润石板路上。",
            diversityKey = "test"
        )

        assertTrue(result.matches.any { it.entry.id == "scene" })
        assertTrue(result.matches.any { it.entry.id == "dress" })
        assertTrue(result.matches.size <= 5)
    }

    @Test
    fun `search uses Chinese section prose without requiring title match`() {
        val catalog = NovelAiCodexCatalog(
            schemaVersion = 1,
            entries = listOf(
                entry(
                    id = "wardrobe",
                    kind = NovelAiCodexKind.WARDROBE,
                    title = "模板甲",
                    prompt = "### 模板甲\n这里是完整解释。\n```\nopen_shirt, strap_slip\n```",
                    searchText = "模板甲 衬衫被拉开 肩带滑落 露出锁骨"
                )
            )
        )

        val result = NovelAiCodexSearchEngine(catalog, nextDouble = { 0.0 })
            .search(listOf("衣服拉开，肩带从肩头滑落"), "", "test")

        assertEquals("wardrobe", result.matches.single().entry.id)
        assertTrue(result.matches.single().entry.prompt.contains("这里是完整解释。\n```"))
    }

    @Test
    fun `English prompt tags and rewrite aliases do not participate in retrieval`() {
        val catalog = NovelAiCodexCatalog(
            schemaVersion = 1,
            entries = listOf(
                entry(
                    id = "r18",
                    kind = NovelAiCodexKind.R18,
                    title = "无关标题",
                    prompt = "thighjob, close-up",
                    searchText = "无关标题 成人模板"
                )
            ),
            rewriteRules = listOf(
                NovelAiTagRewriteRule(
                    aliases = listOf("素股"),
                    replacements = listOf("thighjob")
                )
            )
        )

        val result = NovelAiCodexSearchEngine(catalog, nextDouble = { 0.0 })
            .search(listOf("素股"), "", "test")

        assertTrue(result.matches.isEmpty())
    }

    @Test
    fun `repeated searches keep same relevant set without recent-use penalty`() {
        val catalog = NovelAiCodexCatalog(
            schemaVersion = 1,
            entries = (1..7).map { index ->
                entry(
                    id = "item-$index",
                    kind = NovelAiCodexKind.COMPOSITION,
                    title = "构图 $index",
                    prompt = "### 构图 $index\n完整原文 $index",
                    searchText = "雨夜街道 灯笼倒影 构图层次 细节$index"
                )
            }
        )
        var random = 0.0
        val engine = NovelAiCodexSearchEngine(catalog, nextDouble = {
            random = (random + 0.17) % 1.0
            random
        })

        val first = engine.search(listOf("雨夜灯笼倒影"), "", "same").matches.map { it.entry.id }.toSet()
        val second = engine.search(listOf("雨夜灯笼倒影"), "", "same").matches.map { it.entry.id }.toSet()

        assertEquals(first, second)
        assertEquals(5, first.size)
    }

    @Test
    fun `fuzzy search keeps details near end of long scene draft`() {
        val catalog = NovelAiCodexCatalog(
            schemaVersion = 1,
            entries = listOf(
                entry("lantern", NovelAiCodexKind.COMPOSITION, "灯笼倒影", "red lantern reflection")
            )
        )
        val longScene = "人物服装动作与空间关系。".repeat(22) +
            "背景红灯笼在湿润石板路上形成清晰倒影。"

        val result = NovelAiCodexSearchEngine(catalog, nextDouble = { 0.0 })
            .search(emptyList(), longScene, "test")

        assertEquals("lantern", result.matches.single().entry.id)
    }

    private fun entry(
        id: String,
        kind: NovelAiCodexKind,
        title: String,
        prompt: String,
        searchText: String = title
    ) = NovelAiCodexEntry(
        id = id,
        kind = kind,
        title = title,
        prompt = prompt,
        searchText = searchText
    )
}
