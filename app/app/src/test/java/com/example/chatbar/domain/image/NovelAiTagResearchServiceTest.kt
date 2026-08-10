package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NovelAiTagResearchServiceTest {
    @Test
    fun `planner parses deduplicated batch queries capped at six without purpose`() {
        val decision = planner().parseDecision(
            """
            ```json
            {"action":"search","queries":["俯视","撑伞","俯视","双马尾","教室","夜景","初音未来","额外词"],"purpose":"unused"}
            ```
            """.trimIndent()
        )

        assertEquals(
            listOf("俯视", "撑伞", "双马尾", "教室", "夜景", "初音未来"),
            decision?.queries
        )
        assertEquals("search", decision?.action)
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("不要输出 purpose"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("一次性规划"))
    }

    @Test
    fun `planner parses finish and legacy single query`() {
        val planner = planner()

        val finish = planner.parseDecision("""{"action":"finish"}""")
        val legacy = planner.parseDecision("""{"needSearch":true,"query":"俯视"}""")

        assertEquals(NovelAiTagSearchDecision("finish"), finish)
        assertEquals(NovelAiTagSearchDecision("search", listOf("俯视")), legacy)
    }

    @Test
    fun `planner request contains reference image in one user message`() {
        val messages = planner().requestMessages("scene", listOf("", "image-data"))

        assertEquals(listOf("system", "user"), messages.map { it.role })
        val imageParts = messages.last().content.jsonArray
        assertEquals("text", imageParts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image_url", imageParts[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `TagSuggest query normalizes English spaces to underscores and Chinese spaces away`() {
        assertEquals("from_above", "from above".normalizeTagSuggestQuery())
        assertEquals("from_above", "from_above".normalizeTagSuggestQuery())
        assertEquals("初音未来", "初音 未来".normalizeTagSuggestQuery())
        assertEquals("俯视", "  俯视  ".normalizeTagSuggestQuery())
    }

    @Test
    fun `TagSuggest parser accepts only general copyright and character tags`() {
        val result = TagSuggestClient().parseResponse(
            """
            {"results":[
              {"name":"from_above","cn_name":"俯视","count":100,"category":0},
              {"name":"vocaloid","cn_name":"VOCALOID","count":90,"category":3},
              {"name":"hatsune_miku","cn_name":"初音未来","count":80,"category":4},
              {"name":"some_artist","cn_name":"画师","count":70,"category":1},
              {"name":"highres","cn_name":"高分辨率","count":60,"category":5},
              {"name":"bad tag","cn_name":"非法","count":50,"category":0}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf("from_above", "vocaloid", "hatsune_miku"), result.map { it.name })
        assertEquals(
            listOf(NovelAiTagCategory.GENERAL, NovelAiTagCategory.COPYRIGHT, NovelAiTagCategory.CHARACTER),
            result.map { it.category }
        )
    }

    @Test
    fun `TagSuggest sends underscore query and caches for thirty minutes`() = runTest {
        val requests = AtomicInteger()
        val sentQueries = mutableListOf<String?>()
        var now = 0L
        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                sentQueries += chain.request().url.queryParameter("q")
                response(
                    chain.request(),
                    200,
                    """{"results":[{"name":"from_above","cn_name":"俯视","count":1,"category":0}]}"""
                )
            }
            .build()
        val client = TagSuggestClient(
            client = httpClient,
            baseUrl = "https://example.test/".toHttpUrl(),
            clockMillis = { now }
        )

        val first = client.search("from above")
        val cached = client.search("FROM ABOVE")
        now = 30 * 60 * 1000L + 1L
        val refreshed = client.search("from above")

        assertEquals("from_above", first.effectiveQuery)
        assertFalse(first.fromCache)
        assertTrue(cached.fromCache)
        assertFalse(refreshed.fromCache)
        assertEquals(listOf("from_above", "from_above"), sentQueries)
        assertEquals(2, requests.get())
    }

    @Test
    fun `TagSuggest exposes 429 and malformed responses as failures`() = runTest {
        val rateLimited = clientResponding(429, "too many")
        val malformed = clientResponding(200, "{\"unexpected\":[]}")

        val rateLimitError = runCatching { rateLimited.search("夜景") }.exceptionOrNull()
        val malformedError = runCatching { malformed.search("夜景") }.exceptionOrNull()

        assertTrue(rateLimitError is IOException)
        assertTrue(rateLimitError?.message.orEmpty().contains("HTTP 429"))
        assertTrue(malformedError?.message.orEmpty().contains("缺少 results"))
    }

    @Test
    fun `research calls planner once and searches all queries concurrently`() = runTest {
        val planner = StaticPlanner(listOf("俯视", "撑伞", "双马尾"))
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val client = LambdaClient { query ->
            val running = active.incrementAndGet()
            maxActive.updateAndGet { current -> maxOf(current, running) }
            delay(if (query == "俯视") 150L else 50L)
            active.decrementAndGet()
            outcome(query, candidate("tag_${query}"))
        }

        val result = NovelAiTagResearchService(planner, client).research(
            "scene",
            emptyList(),
            emptyList(),
            model()
        )

        assertEquals(1, planner.calls)
        assertEquals(3, maxActive.get())
        assertEquals(listOf("俯视", "撑伞", "双马尾"), result.queryResults.map { it.query })
        assertEquals(listOf("tag_俯视", "tag_撑伞", "tag_双马尾"), result.evidence.map { it.name })
        assertTrue(result.transcript.contains("AI 批量搜索规划"))
        assertTrue(result.transcript.contains("TagSuggest 批量搜索"))
    }

    @Test
    fun `batch search preserves partial successes when another query fails`() = runTest {
        val planner = StaticPlanner(listOf("构图", "俯视", "夜景"))
        val client = LambdaClient { query ->
            if (query == "构图") throw IOException("HTTP 429")
            outcome(query, candidate("tag_${query}"))
        }
        val progress = mutableListOf<String>()

        val result = NovelAiTagResearchService(planner, client).research(
            "scene",
            emptyList(),
            emptyList(),
            model(),
            progress::add
        )

        assertEquals("HTTP 429", result.queryResults[0].failureReason)
        assertEquals(listOf("tag_俯视", "tag_夜景"), result.evidence.map { it.name })
        assertTrue(result.transcript.contains("失败：HTTP 429"))
        assertEquals(result.transcript, progress.last())
    }

    @Test
    fun `batch keeps eight candidates per query and twenty four globally`() = runTest {
        val planner = StaticPlanner(listOf("查询一", "查询二", "查询三", "查询四"))
        val client = LambdaClient { query ->
            NovelAiTagSearchOutcome(
                effectiveQuery = query,
                candidates = (1..10).map { index -> candidate("${query}_$index") }
            )
        }

        val result = NovelAiTagResearchService(planner, client).research(
            "scene",
            emptyList(),
            emptyList(),
            model()
        )

        assertEquals(4, result.queryResults.size)
        assertEquals(listOf(8, 8, 8, 0), result.queryResults.map { it.candidates.size })
        assertEquals(24, result.evidence.size)
        assertTrue(result.transcript.contains("全局去重后保留 24 个候选"))
    }

    @Test
    fun `one timed out query does not cancel other concurrent searches`() = runTest {
        val planner = StaticPlanner(listOf("慢请求", "俯视"))
        val client = LambdaClient { query ->
            if (query == "慢请求") awaitCancellation()
            outcome(query, candidate("from_above"))
        }

        val result = NovelAiTagResearchService(
            planner = planner,
            searchClient = client,
            requestTimeoutMs = 10L,
            batchTimeoutMs = 100L
        ).research("scene", emptyList(), emptyList(), model())

        assertEquals("请求超时", result.queryResults[0].failureReason)
        assertEquals(listOf("from_above"), result.evidence.map { it.name })
    }

    @Test
    fun `user cancellation cancels every active batch request`() = runTest {
        val cancelled = AtomicInteger()
        val planner = StaticPlanner(listOf("慢请求一", "慢请求二"))
        val client = LambdaClient {
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        }
        val job = launch {
            NovelAiTagResearchService(planner, client).research(
                "scene",
                emptyList(),
                emptyList(),
                model()
            )
        }

        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(2, cancelled.get())
        assertEquals(1, planner.calls)
    }

    @Test
    fun `finish decision skips all searches`() = runTest {
        val planner = StaticPlanner(emptyList(), finish = true)
        var searchCalls = 0
        val client = LambdaClient {
            searchCalls += 1
            outcome(it)
        }

        val result = NovelAiTagResearchService(planner, client).research(
            "scene",
            emptyList(),
            emptyList(),
            model()
        )

        assertTrue(result.evidence.isEmpty())
        assertEquals(0, searchCalls)
        assertTrue(result.transcript.contains("无需搜索"))
    }

    @Test
    fun `prompt progress accumulates batch search design and repair in order`() {
        val updates = mutableListOf<String>()
        val progress = NovelAiPromptProgress(updates::add)

        progress.updatePrelude("【AI 批量搜索规划】\nqueries\n\n【TagSuggest 批量搜索】\nfound")
        progress.updateStage("最终 Prompt 设计", "{bad")
        progress.updateStage("JSON 修复", "{good}")

        val final = updates.last()
        assertTrue(final.indexOf("AI 批量搜索规划") < final.indexOf("TagSuggest 批量搜索"))
        assertTrue(final.indexOf("TagSuggest 批量搜索") < final.indexOf("最终 Prompt 设计"))
        assertTrue(final.indexOf("最终 Prompt 设计") < final.indexOf("JSON 修复"))
    }

    @Test
    fun `moment debug exchanges include batch planning and TagSuggest results`() {
        val research = NovelAiTagResearchResult(
            decisionResults = listOf(decisionResult(listOf("俯视", "撑伞"))),
            queryResults = listOf(
                NovelAiTagQueryResult(
                    query = "俯视",
                    candidates = listOf(candidate("from_above"))
                )
            )
        )

        val exchanges = NovelAiPromptDesigner.tagResearchDebugExchanges(research)

        assertEquals(listOf("Danbooru Tag 批量搜索规划", "TagSuggest 批量搜索"), exchanges.map { it.title })
        assertTrue(exchanges[0].output.contains("[批量规划 1]"))
        assertTrue(exchanges[1].output.contains("from_above"))
    }

    private fun planner() = LlmNovelAiTagSearchPlanner(StreamingChatService { false })

    private class StaticPlanner(
        private val queries: List<String>,
        private val finish: Boolean = false
    ) : NovelAiTagSearchPlanner {
        var calls = 0

        override suspend fun decide(
            taskInput: String,
            characterPrompts: List<Pair<String, String>>,
            imageBase64s: List<String>,
            model: ModelConfig,
            onRawText: (String) -> Unit
        ): NovelAiTagSearchDecisionResult {
            calls += 1
            val result = if (finish) {
                NovelAiTagSearchDecisionResult(
                    decision = NovelAiTagSearchDecision("finish"),
                    requestText = taskInput,
                    rawResponse = "{\"action\":\"finish\"}"
                )
            } else {
                decisionResult(queries, taskInput)
            }
            onRawText(result.rawResponse)
            return result
        }
    }

    private class LambdaClient(
        private val block: suspend (String) -> NovelAiTagSearchOutcome
    ) : NovelAiTagSearchClient {
        override suspend fun search(query: String): NovelAiTagSearchOutcome = block(query)
    }

    private companion object {
        fun decisionResult(queries: List<String>, requestText: String = "scene") =
            NovelAiTagSearchDecisionResult(
                decision = NovelAiTagSearchDecision("search", queries),
                requestText = requestText,
                rawResponse = "{\"action\":\"search\",\"queries\":[${queries.joinToString(",") { "\"$it\"" }}]}"
            )

        fun outcome(query: String, vararg candidates: NovelAiTagCandidate) =
            NovelAiTagSearchOutcome(query.normalizeTagSuggestQuery(), candidates.toList())

        fun candidate(name: String, count: Long = 1L) = NovelAiTagCandidate(
            name = name,
            translatedName = "",
            count = count,
            category = NovelAiTagCategory.GENERAL
        )

        fun clientResponding(code: Int, body: String): TagSuggestClient {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain -> response(chain.request(), code, body) }
                .build()
            return TagSuggestClient(client, "https://example.test/".toHttpUrl())
        }

        fun response(request: Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

        fun model() = ModelConfig(
            id = "model",
            displayName = "Model",
            baseUrl = "https://llm.example/v1",
            apiKey = "key",
            modelName = "model",
            createdAt = 1L
        )
    }
}
