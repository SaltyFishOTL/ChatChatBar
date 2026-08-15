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
    fun `planner parses scene draft and deduplicated queries capped at six`() {
        val decision = planner().parseDecision(
            """
            ```json
            {"sceneDescription":"$DEFAULT_SCENE_DESCRIPTION","queries":["俯视","撑伞","俯视","双马尾","教室","夜景","初音未来","额外词"]}
            ```
            """.trimIndent()
        )

        assertEquals(
            listOf("俯视", "撑伞", "双马尾", "教室", "夜景", "初音未来"),
            decision?.queries
        )
        assertEquals("search", decision?.action)
        assertTrue(decision?.sceneDescription.orEmpty().contains("雨夜窄巷"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("禁止生成 Danbooru tag"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("sceneDescription"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("每名可见人物"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("完整姓名"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("动作发起方"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("服装细节"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("林知夏"))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("周景珩"))
        assertFalse(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("40-80"))
        assertFalse(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("120-200"))
        assertFalse(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("180-320"))
        assertFalse(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("\"queries\":[\"林知夏\""))
        assertTrue(PromptTemplates.NOVELAI_TAG_SEARCH_PLANNER_SYSTEM.contains("不得搜索已提供了Prompt的角色"))

        val request = PromptTemplates.novelAiTagSearchPlannerUser(
            taskInput = "任务",
            characterPrompts = listOf("初音未来" to "hatsune_miku, aqua_hair, twintails")
        )
        assertTrue(request.contains("不按字数判断"))
        assertTrue(request.contains("初音未来: hatsune_miku, aqua_hair, twintails"))
        assertTrue(request.contains("不要重复查询其中已有的角色名或 Tag"))
    }

    @Test
    fun `planner infers finish or search from scene draft and queries`() {
        val planner = planner()

        val finish = planner.parseDecision(
            """{"sceneDescription":"$SINGLE_SCENE_DESCRIPTION","queries":[]}"""
        )
        val flexible = planner.parseDecision(
            """{"scene_description":"$ROOFTOP_SCENE_DESCRIPTION","keywords":["低角度"]}"""
        )

        assertEquals("finish", finish?.action)
        assertTrue(finish?.queries.orEmpty().isEmpty())
        assertEquals("search", flexible?.action)
        assertEquals(listOf("低角度"), flexible?.queries)
    }

    @Test
    fun `planner accepts concise nonblank scene draft`() {
        val decision = planner().parseDecision(
            """{"sceneDescription":"两人在雨夜街道共撑伞。","queries":["撑伞"]}"""
        )

        assertEquals("两人在雨夜街道共撑伞。", decision?.sceneDescription)
        assertEquals(listOf("撑伞"), decision?.queries)
    }

    @Test
    fun `planner removes queries already covered by character prompts`() {
        val decision = planner().parseDecision(
            raw = """{"sceneDescription":"初音未来站在舞台中央挥手。","queries":["初音未来","hatsune miku","twintails","低角度"]}""",
            characterPrompts = listOf(
                "初音未来" to "hatsune_miku, aqua_hair, {twintails:1.2}"
            )
        )

        assertEquals(listOf("低角度"), decision?.queries)
        assertEquals("search", decision?.action)
    }

    @Test
    fun `planner streaming progress keeps reasoning and output visible`() {
        val updates = mutableListOf<String>()
        val progress = NovelAiTagPlannerStreamingProgress(updates::add)

        progress.appendReasoning("先分析人物关系。")
        progress.appendReasoning("再确定镜头。")
        progress.appendContent("{\"sceneDescription\":")
        progress.appendContent("\"雨夜街道\",\"queries\":[]}")

        val latest = updates.last()
        assertTrue(latest.contains("【思考】\n先分析人物关系。再确定镜头。"))
        assertTrue(
            latest.contains(
                "【输出】\n{\"sceneDescription\":\"雨夜街道\",\"queries\":[]}"
            )
        )
        assertTrue(latest.indexOf("【思考】") < latest.indexOf("【输出】"))
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
        assertEquals(DEFAULT_SCENE_DESCRIPTION, result.sceneDescription)
        assertTrue(result.transcript.contains("AI 图片画面设计"))
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
    fun `same planner queries drive local codex retrieval with diversity key`() = runTest {
        val planner = StaticPlanner(listOf("夜市", "礼服"))
        var received: Triple<List<String>, String, String>? = null
        val codexSearcher = NovelAiCodexSearcher { queries, taskInput, diversityKey ->
            received = Triple(queries, taskInput, diversityKey)
            NovelAiCodexSearchResult(
                matches = listOf(
                    NovelAiCodexMatch(
                        entry = NovelAiCodexEntry(
                            id = "scene",
                            kind = NovelAiCodexKind.COMPOSITION,
                            title = "灯笼夜市",
                            prompt = "night market, red lanterns"
                        ),
                        score = 1.0,
                        matchedQueries = listOf("夜市")
                    )
                )
            )
        }

        val result = NovelAiTagResearchService(
            planner = planner,
            searchClient = LambdaClient { outcome(it) },
            codexSearcher = codexSearcher
        ).research(
            taskInput = "角色在夜市穿礼服",
            characterPrompts = emptyList(),
            imageBase64s = emptyList(),
            model = model(),
            diversityKey = "moment:card"
        )

        assertEquals(Triple(listOf("夜市", "礼服"), DEFAULT_SCENE_DESCRIPTION, "moment:card"), received)
        assertEquals("scene", result.codexEvidence.single().id)
        assertTrue(result.transcript.contains("本地 NovelAI 法典召回"))
    }

    @Test
    fun `planner may run beyond old twenty second timeout`() = runTest {
        val progress = mutableListOf<String>()
        val planner = object : NovelAiTagSearchPlanner {
            override suspend fun decide(
                taskInput: String,
                characterPrompts: List<Pair<String, String>>,
                imageBase64s: List<String>,
                model: ModelConfig,
                playerName: String?,
                botName: String,
                onRawText: (String) -> Unit
            ): NovelAiTagSearchDecisionResult {
                onRawText("【思考】\n正在规划空间关系")
                delay(20_001L)
                onRawText("【思考】\n空间关系完成\n\n【输出】\n$DEFAULT_SCENE_DESCRIPTION")
                return NovelAiTagSearchDecisionResult(
                    decision = NovelAiTagSearchDecision(
                        action = "finish",
                        sceneDescription = DEFAULT_SCENE_DESCRIPTION
                    ),
                    requestText = taskInput,
                    reasoningResponse = "空间关系完成",
                    rawResponse = "{\"sceneDescription\":\"$DEFAULT_SCENE_DESCRIPTION\",\"queries\":[]}"
                )
            }
        }

        val result = NovelAiTagResearchService(
            planner = planner,
            searchClient = LambdaClient { outcome(it) }
        ).research(
            taskInput = "角色站在雨夜街道",
            characterPrompts = emptyList(),
            imageBase64s = emptyList(),
            model = model(),
            diversityKey = "chat:test",
            onProgress = progress::add
        )

        assertEquals(DEFAULT_SCENE_DESCRIPTION, result.sceneDescription)
        assertTrue(progress.any { it.contains("正在规划空间关系") })
        assertTrue(result.transcript.contains("【思考】"))
        assertFalse(result.transcript.contains("最长 20 秒"))
    }

    @Test
    fun `user cancellation stops active planner without fallback work`() = runTest {
        var plannerCancelled = false
        var searchCalls = 0
        var codexCalls = 0
        val planner = object : NovelAiTagSearchPlanner {
            override suspend fun decide(
                taskInput: String,
                characterPrompts: List<Pair<String, String>>,
                imageBase64s: List<String>,
                model: ModelConfig,
                playerName: String?,
                botName: String,
                onRawText: (String) -> Unit
            ): NovelAiTagSearchDecisionResult = try {
                onRawText("【思考】\n持续规划中")
                awaitCancellation()
            } finally {
                plannerCancelled = true
            }
        }
        val job = launch {
            NovelAiTagResearchService(
                planner = planner,
                searchClient = LambdaClient {
                    searchCalls += 1
                    outcome(it)
                },
                codexSearcher = NovelAiCodexSearcher { _, _, _ ->
                    codexCalls += 1
                    NovelAiCodexSearchResult()
                }
            ).research("scene", emptyList(), emptyList(), model())
        }

        runCurrent()
        job.cancelAndJoin()

        assertTrue(plannerCancelled)
        assertEquals(0, searchCalls)
        assertEquals(0, codexCalls)
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
    fun `empty query plan skips TagSuggest but still recalls codex from scene draft`() = runTest {
        val planner = StaticPlanner(emptyList(), finish = true)
        var searchCalls = 0
        var codexScene = ""
        val client = LambdaClient {
            searchCalls += 1
            outcome(it)
        }

        val result = NovelAiTagResearchService(
            planner = planner,
            searchClient = client,
            codexSearcher = NovelAiCodexSearcher { _, sceneDescription, _ ->
                codexScene = sceneDescription
                NovelAiCodexSearchResult()
            }
        ).research(
            "scene",
            emptyList(),
            emptyList(),
            model()
        )

        assertTrue(result.evidence.isEmpty())
        assertEquals(0, searchCalls)
        assertEquals(DEFAULT_SCENE_DESCRIPTION, codexScene)
        assertEquals(DEFAULT_SCENE_DESCRIPTION, result.sceneDescription)
        assertTrue(result.transcript.contains("无需 TagSuggest"))
    }

    @Test
    fun `prompt progress accumulates batch search design and repair in order`() {
        val updates = mutableListOf<String>()
        val progress = NovelAiPromptProgress(updates::add)

        progress.updatePrelude(
            "【AI 图片画面设计】\nscene + queries\n\n" +
                "【本地 NovelAI 法典召回】\nreferences\n\n" +
                "【TagSuggest 批量搜索】\nfound"
        )
        progress.updateStage("最终 Prompt 设计", "{bad")
        progress.updateStage("JSON 修复", "{good}")

        val final = updates.last()
        assertTrue(final.indexOf("AI 图片画面设计") < final.indexOf("本地 NovelAI 法典召回"))
        assertTrue(final.indexOf("本地 NovelAI 法典召回") < final.indexOf("TagSuggest 批量搜索"))
        assertTrue(final.indexOf("TagSuggest 批量搜索") < final.indexOf("最终 Prompt 设计"))
        assertTrue(final.indexOf("最终 Prompt 设计") < final.indexOf("JSON 修复"))
    }

    @Test
    fun `moment debug exchanges include batch planning and TagSuggest results`() {
        val research = NovelAiTagResearchResult(
            decisionResults = listOf(decisionResult(listOf("俯视", "撑伞"))),
            sceneDescription = DEFAULT_SCENE_DESCRIPTION,
            queryResults = listOf(
                NovelAiTagQueryResult(
                    query = "俯视",
                    candidates = listOf(candidate("from_above"))
                )
            )
        )

        val exchanges = NovelAiPromptDesigner.tagResearchDebugExchanges(research)

        assertEquals(
            listOf("自然语言画面设计与检索规划", "本地 NovelAI 法典模糊召回", "TagSuggest 批量搜索"),
            exchanges.map { it.title }
        )
        assertTrue(exchanges[0].output.contains("[画面设计 1]"))
        assertTrue(exchanges[2].output.contains("from_above"))
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
            playerName: String?,
            botName: String,
            onRawText: (String) -> Unit
        ): NovelAiTagSearchDecisionResult {
            calls += 1
            val result = if (finish) {
                NovelAiTagSearchDecisionResult(
                    decision = NovelAiTagSearchDecision(
                        action = "finish",
                        sceneDescription = DEFAULT_SCENE_DESCRIPTION
                    ),
                    requestText = taskInput,
                    rawResponse = "{\"sceneDescription\":\"$DEFAULT_SCENE_DESCRIPTION\",\"queries\":[]}"
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
                decision = NovelAiTagSearchDecision(
                    action = "search",
                    queries = queries,
                    sceneDescription = DEFAULT_SCENE_DESCRIPTION
                ),
                requestText = requestText,
                rawResponse = "{\"sceneDescription\":\"$DEFAULT_SCENE_DESCRIPTION\",\"queries\":[${queries.joinToString(",") { "\"$it\"" }}]}"
            )

        const val DEFAULT_SCENE_DESCRIPTION =
            "雨夜窄巷中，林知夏位于左前景，右手举黑色长柄伞，左手攥住周景珩的外套前襟，穿米白衬衫、深蓝百褶裙、黑色及膝袜和棕色短靴。周景珩位于右侧稍后方，身体前倾替林知夏挡风，左手扶住她的腰，穿敞开的深灰长外套、黑色高领毛衣、长裤和皮鞋。两人肩臂相贴且四肢无遮挡冲突；中景、略低机位、侧前方视角聚焦对视与手部接触，前景雨丝清晰，背景灯笼在湿石板路上形成倒影。"

        const val SINGLE_SCENE_DESCRIPTION =
            "沈月白独自坐在画面中央靠窗的木椅上，身体朝左，双腿并拢，左手托住摊开的书，右手捻起书页，低头阅读。沈月白穿浅蓝针织开衫、白色衬衫、深灰及膝裙、黑色短袜和棕色皮鞋，衣物完整平整。场景为午后书房，中近景、平视侧前方机位，窗帘过滤的阳光落在她的脸、书页和木桌上，背景书架轻微虚化。"

        const val ROOFTOP_SCENE_DESCRIPTION =
            "顾临川站在画面右侧高楼天台边缘，身体朝向左前方城市，左脚踏在矮墙内侧，右手按住被风扬起的黑色长外套下摆，左手扶着护栏，侧脸望向远处灯火。顾临川穿黑色高领毛衣、深灰长裤和系带短靴，外套敞开但衣物完整。场景为深夜天台，中远景、低机位仰拍，护栏形成前景引导线，人物轮廓被城市霓虹勾亮，背景楼群形成清晰纵深。"

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
