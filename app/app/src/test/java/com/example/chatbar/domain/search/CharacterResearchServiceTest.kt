package com.example.chatbar.domain.search

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ModelConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterResearchServiceTest {
    @Test
    fun `research returns null without touching backend when search disabled`() = runTest {
        val backend = FakeSearchBackend()
        val service = service(
            settings = AppSettings(webSearchEnabled = false),
            backend = backend
        )

        val brief = service.research("request", card(), model())

        assertNull(brief)
        assertEquals(0, backend.searchCalls.size)
    }

    @Test
    fun `research touches backend without api key when search enabled`() = runTest {
        val backend = FakeSearchBackend()
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            backend = backend
        )

        val brief = service.research("request", card(), model())

        requireNotNull(brief)
        assertEquals(1, backend.searchCalls.size)
    }

    @Test
    fun `research returns null when backend search fails`() = runTest {
        val backend = FakeSearchBackend(failSearch = true)
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            backend = backend
        )

        val brief = service.research("request", card(), model())

        assertNull(brief)
        assertEquals(1, backend.searchCalls.size)
    }

    @Test
    fun `research falls back to cleaned sources when extract and summarizer fail`() = runTest {
        val backend = FakeSearchBackend(failExtract = true)
        val service = service(
            settings = AppSettings(
                webSearchEnabled = true,
                webSearchMaxResultsPerQuery = 5
            ),
            backend = backend,
            summarizer = FakeSummarizer(returnNull = true)
        )

        val brief = service.research("request", card(), model())

        requireNotNull(brief)
        assertEquals(listOf("canon query"), brief.queries)
        assertEquals(1, backend.searchCalls.size)
        assertEquals(1, backend.searchCalls.single().maxResults)
        assertEquals(1, backend.extractCalls.size)
        assertTrue(brief.facts.single().contains("stable fact from search"))
        assertTrue(brief.sources.single().excerpt.contains("stable fact from search"))
    }

    @Test
    fun `research uses summarizer result when available`() = runTest {
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            summarizer = FakeSummarizer(
                brief = ResearchBrief(facts = listOf("compressed fact"), sources = emptyList())
            )
        )

        val brief = service.research("request", card(), model())

        requireNotNull(brief)
        assertEquals(listOf("compressed fact"), brief.facts)
    }

    @Test
    fun `research emits debug snapshots for plan sources and brief`() = runTest {
        val service = service(settings = AppSettings(webSearchEnabled = true))
        val snapshots = mutableListOf<ResearchDebugSnapshot>()

        val brief = service.research(
            userInput = "request",
            currentCard = card(),
            modelConfig = model(),
            onDebug = { snapshots += it }
        )

        requireNotNull(brief)
        assertTrue(snapshots.any { it.plan?.queries?.singleOrNull()?.query == "canon query" })
        assertTrue(snapshots.any { it.sources.singleOrNull()?.excerpt?.contains("stable fact from extract") == true })
        assertTrue(snapshots.any { it.brief?.facts == listOf("compressed fact") })
    }

    @Test
    fun `research emits debug snapshot when summarizer fails`() = runTest {
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            summarizer = FakeSummarizer(returnNull = true, failureReason = "summary parse failed")
        )
        val snapshots = mutableListOf<ResearchDebugSnapshot>()

        service.research(
            userInput = "request",
            currentCard = card(),
            modelConfig = model(),
            onDebug = { snapshots += it }
        )

        assertTrue(snapshots.any { it.briefFailureReason == "summary parse failed" })
    }

    @Test
    fun `research relays visible planner and summary output`() = runTest {
        val service = service(settings = AppSettings(webSearchEnabled = true))
        val outputs = mutableListOf<String>()

        service.research(
            userInput = "request",
            currentCard = card(),
            modelConfig = model(),
            onVisibleOutput = { key, _, text -> outputs += "$key:$text" }
        )

        assertTrue(outputs.contains("research-plan:{\"needSearch\":true}"))
        assertTrue(outputs.contains("research-brief:{\"facts\":[\"compressed fact\"]}"))
    }

    @Test
    fun `research does not emit default successful setup statuses`() = runTest {
        val service = service(settings = AppSettings(webSearchEnabled = true))
        val statuses = mutableListOf<String>()

        service.research("request", card(), model()) { statuses += it }

        assertFalse(statuses.contains("检查搜索增强设置"))
        assertFalse(statuses.contains("AI 正在判断是否需要搜索"))
        assertFalse(statuses.contains("AI 判定无需搜索，直接生成"))
    }

    @Test
    fun `research falls back to heuristic queries when planner fails`() = runTest {
        val backend = FakeSearchBackend()
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            planner = FailingPlanner(),
            backend = backend
        )
        val statuses = mutableListOf<String>()

        val brief = service.research("request", card(), model()) { statuses += it }

        requireNotNull(brief)
        assertEquals(listOf("Card", "request"), backend.searchCalls.map { it.query })
        assertEquals(listOf(1, 1), backend.searchCalls.map { it.maxResults })
        assertTrue(statuses.any { it.contains("改用保底关键词继续搜索") })
    }

    @Test
    fun `research uses fixed ten item cap and one result per query`() = runTest {
        val backend = FakeSearchBackend()
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            planner = MultiQueryPlanner(queryCount = 25),
            backend = backend
        )

        val brief = service.research("request", card(), model())

        requireNotNull(brief)
        assertEquals((1..10).map { "q$it" }, backend.searchCalls.map { it.query })
        assertTrue(backend.searchCalls.all { it.maxResults == 1 })
    }

    @Test
    fun `research keeps up to ten final sources`() = runTest {
        val backend = DistinctSearchBackend()
        val service = service(
            settings = AppSettings(webSearchEnabled = true),
            planner = MultiQueryPlanner(queryCount = 12),
            backend = backend
        )

        val brief = service.research("request", card(), model())

        requireNotNull(brief)
        assertEquals(10, backend.searchCalls.size)
        assertEquals(10, backend.extractCalls.single().size)
        assertEquals(listOf(10), backend.extractMaxPagesCalls)
        assertEquals(10, brief.sources.size)
        assertTrue(brief.sources.all { it.excerpt.length > 900 })
        assertTrue(brief.sources.last().excerpt.contains("stable fact 10"))
    }

    private fun service(
        settings: AppSettings,
        planner: CharacterResearchPlanProvider = FakePlanner(),
        backend: SearchBackend = FakeSearchBackend(),
        summarizer: ResearchBriefSummarizer = FakeSummarizer()
    ): CharacterResearchService = CharacterResearchService(
        settingsProvider = { settings },
        planner = planner,
        backend = backend,
        summarizer = summarizer
    )

    private fun card() = CharacterCard(
        id = "card",
        name = "Card",
        greeting = "",
        basicSetting = "",
        defaultImagePrompt = "",
        editMode = CharacterEditMode.STRUCTURED,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun model() = ModelConfig(
        id = "model",
        displayName = "Model",
        baseUrl = "https://llm.example/v1",
        apiKey = "llm-key",
        modelName = "provider/model",
        createdAt = 1L
    )

    private class FakePlanner : CharacterResearchPlanProvider {
        override suspend fun plan(
            userInput: String,
            currentCard: CharacterCard,
            modelConfig: ModelConfig,
            maxQueries: Int,
            onStatus: (String) -> Unit,
            onRawText: (String) -> Unit
        ): CharacterResearchPlanResult {
            onRawText("{\"needSearch\":true}")
            return CharacterResearchPlanResult(
                plan = CharacterResearchPlan(
                    needSearch = true,
                    reason = "Need facts",
                    queries = listOf(
                        CharacterResearchQuery(
                            query = "canon query",
                            priority = 1
                        )
                    )
                )
            )
        }
    }

    private class FailingPlanner : CharacterResearchPlanProvider {
        override suspend fun plan(
            userInput: String,
            currentCard: CharacterCard,
            modelConfig: ModelConfig,
            maxQueries: Int,
            onStatus: (String) -> Unit,
            onRawText: (String) -> Unit
        ): CharacterResearchPlanResult = CharacterResearchPlanResult(failureReason = "bad planner")
    }

    private class MultiQueryPlanner(private val queryCount: Int) : CharacterResearchPlanProvider {
        override suspend fun plan(
            userInput: String,
            currentCard: CharacterCard,
            modelConfig: ModelConfig,
            maxQueries: Int,
            onStatus: (String) -> Unit,
            onRawText: (String) -> Unit
        ): CharacterResearchPlanResult = CharacterResearchPlanResult(
            plan = CharacterResearchPlan(
                needSearch = true,
                reason = "Need many facts",
                queries = (1..queryCount).map { index ->
                    CharacterResearchQuery(query = "q$index", priority = index)
                }
            )
        )
    }

    private class FakeSearchBackend(
        private val failSearch: Boolean = false,
        private val failExtract: Boolean = false
    ) : SearchBackend {
        val searchCalls = mutableListOf<SearchBackendQuery>()
        val extractCalls = mutableListOf<List<String>>()
        val extractMaxPagesCalls = mutableListOf<Int>()

        override suspend fun search(query: SearchBackendQuery): List<SearchHit> {
            searchCalls += query
            if (failSearch) error("search failed")
            return listOf(
                SearchHit(
                    title = "Source",
                    url = "https://example.com/source",
                    content = "stable fact from search",
                    score = 0.7
                )
            )
        }

        override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> {
            extractCalls += urls
            extractMaxPagesCalls += maxPages
            if (failExtract) error("extract failed")
            return urls.take(maxPages).map { url ->
                SearchExtract(url = url, rawContent = "stable fact from extract")
            }
        }
    }

    private class DistinctSearchBackend : SearchBackend {
        val searchCalls = mutableListOf<SearchBackendQuery>()
        val extractCalls = mutableListOf<List<String>>()
        val extractMaxPagesCalls = mutableListOf<Int>()

        override suspend fun search(query: SearchBackendQuery): List<SearchHit> {
            searchCalls += query
            val index = query.query.removePrefix("q").toInt()
            return listOf(
                SearchHit(
                    title = "Source $index",
                    url = "https://example.com/source-$index",
                    content = "stable fact $index from search",
                    score = 100.0 - index
                )
            )
        }

        override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> {
            extractCalls += urls
            extractMaxPagesCalls += maxPages
            return urls.take(maxPages).mapIndexed { index, url ->
                SearchExtract(
                    url = url,
                    rawContent = "stable fact ${index + 1} from extract " + "detail ".repeat(300)
                )
            }
        }
    }

    private class FakeSummarizer(
        private val returnNull: Boolean = false,
        private val failureReason: String = "",
        private val brief: ResearchBrief = ResearchBrief(
            facts = listOf("compressed fact"),
            sources = emptyList()
        )
    ) : ResearchBriefSummarizer {
        override suspend fun summarize(
            request: String,
            currentCard: CharacterCard,
            plan: CharacterResearchPlan,
            sources: List<ResearchSource>,
            modelConfig: ModelConfig,
            onStatus: (String) -> Unit,
            onRawText: (String) -> Unit
        ): ResearchBriefResult {
            onRawText("{\"facts\":[\"compressed fact\"]}")
            return if (returnNull) {
                ResearchBriefResult(failureReason = failureReason)
            } else {
                ResearchBriefResult(brief = brief.copy(sources = sources))
            }
        }
    }
}
