package com.example.chatbar

import android.app.Application
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.service.StreamingNotificationManager
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.rag.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.community.CommunityService
import com.example.chatbar.domain.deletion.DeletionCoordinator
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.search.*
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.data.security.NovelAiCredentialStore
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * App Application class - 初始化并持有所有的全局仓库和域服务实例
 */
class ChatBarApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val streamingStopRequested = MutableStateFlow(false)
    
    // 存储与数据仓库
    lateinit var jsonFileStorage: JsonFileStorage
        private set
    lateinit var characterRepository: CharacterRepository
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var modelRepository: ModelRepository
        private set
    lateinit var formatCardRepository: FormatCardRepository
        private set
    lateinit var saveSlotRepository: SaveSlotRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var ragRepository: RagRepository
        private set
    lateinit var worldBookRepository: WorldBookRepository
        private set

    // 领域层服务
    lateinit var chunkingEngine: ChunkingEngine
        private set
    lateinit var embeddingService: EmbeddingService
        private set
    lateinit var vectorSearchEngine: VectorSearchEngine
        private set
    lateinit var ragManager: RagManager
        private set
    lateinit var retrievalPlanner: RetrievalPlanner
        private set
    lateinit var promptAssembler: PromptAssembler
        private set
    lateinit var contextWindowManager: ContextWindowManager
        private set
    lateinit var worldBookEngine: WorldBookEngine
        private set
    lateinit var streamingChatService: StreamingChatService
        private set
    lateinit var imageUnderstandingService: ImageUnderstandingService
        private set
    lateinit var characterCardTransferService: CharacterCardTransferService
        private set
    lateinit var characterAutoFillService: CharacterAutoFillService
        private set
    lateinit var characterRewriteService: CharacterRewriteService
        private set
    lateinit var formatCardTransferService: FormatCardTransferService
        private set
    lateinit var worldBookTransferService: WorldBookTransferService
        private set
    lateinit var presetCatalogService: PresetCatalogService
        private set
    lateinit var characterSessionService: CharacterSessionService
        private set
    lateinit var deletionCoordinator: DeletionCoordinator
        private set
    lateinit var presetModelCatalogService: PresetModelCatalogService
        private set
    lateinit var effectiveModelResolver: EffectiveModelResolver
        private set
    lateinit var novelAiCredentialStore: NovelAiCredentialStore
        private set
    lateinit var novelAiPromptDesigner: NovelAiPromptDesigner
        private set
    lateinit var novelAiImageService: NovelAiImageService
        private set
    lateinit var novelAiImageStorage: NovelAiImageStorage
        private set
    lateinit var searchBackend: SearchBackend
        private set
    lateinit var characterResearchPlanner: CharacterResearchPlanner
        private set
    lateinit var researchBriefSummarizer: LlmResearchBriefSummarizer
        private set
    lateinit var characterResearchService: CharacterResearchService
        private set
    lateinit var appUpdateChecker: AppUpdateChecker
        private set
    lateinit var communityService: CommunityService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. 初始化文件存储
        jsonFileStorage = JsonFileStorage(this)
        
        // 2. 初始化各个仓库
        characterRepository = CharacterRepository(jsonFileStorage)
        chatRepository = ChatRepository(jsonFileStorage)
        modelRepository = ModelRepository(jsonFileStorage)
        formatCardRepository = FormatCardRepository(jsonFileStorage)
        saveSlotRepository = SaveSlotRepository(jsonFileStorage)
        settingsRepository = SettingsRepository(jsonFileStorage)
        novelAiCredentialStore = NovelAiCredentialStore(this)
        ragRepository = RagRepository(jsonFileStorage)
        worldBookRepository = WorldBookRepository(jsonFileStorage)

        // 3. 初始化 RAG 服务和其它引擎
        chunkingEngine = ChunkingEngine()
        embeddingService = EmbeddingService()
        vectorSearchEngine = VectorSearchEngine()
        
        streamingChatService = StreamingChatService()
        novelAiPromptDesigner = NovelAiPromptDesigner(streamingChatService)
        novelAiImageService = NovelAiImageService()
        novelAiImageStorage = NovelAiImageStorage(this)
        searchBackend = MediaWikiSearchBackend()
        characterResearchPlanner = CharacterResearchPlanner(streamingChatService)
        researchBriefSummarizer = LlmResearchBriefSummarizer(streamingChatService)
        appUpdateChecker = AppUpdateChecker()

        ragManager = RagManager(
            chunkingEngine = chunkingEngine,
            embeddingService = embeddingService,
            vectorSearch = vectorSearchEngine,
            ragRepository = ragRepository
        )
        retrievalPlanner = RetrievalPlanner(streamingChatService)
        
        promptAssembler = PromptAssembler()
        contextWindowManager = ContextWindowManager()
        worldBookEngine = WorldBookEngine()

        val transferJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        presetModelCatalogService = PresetModelCatalogService(this, transferJson)
        effectiveModelResolver = EffectiveModelResolver(modelRepository, settingsRepository, presetModelCatalogService)
        imageUnderstandingService = ImageUnderstandingService(effectiveModelResolver, streamingChatService)
        characterResearchService = CharacterResearchService(
            settingsProvider = { settingsRepository.getAppSettings() },
            planner = characterResearchPlanner,
            backend = searchBackend,
            summarizer = researchBriefSummarizer
        )
        characterAutoFillService = CharacterAutoFillService(
            effectiveModelResolver,
            streamingChatService,
            characterResearchService,
            imageUnderstandingService
        )
        characterRewriteService = CharacterRewriteService(effectiveModelResolver, streamingChatService, characterResearchService)
        worldBookTransferService = WorldBookTransferService(worldBookRepository, transferJson)
        characterCardTransferService = CharacterCardTransferService(this, characterRepository, worldBookRepository, ragRepository, transferJson)
        formatCardTransferService = FormatCardTransferService(formatCardRepository, transferJson)
        communityService = CommunityService(
            app = this,
            characterRepository = characterRepository,
            formatCardRepository = formatCardRepository,
            worldBookRepository = worldBookRepository,
            characterTransfers = characterCardTransferService,
            formatTransfers = formatCardTransferService,
            worldBookTransfers = worldBookTransferService,
            json = transferJson
        )
        presetCatalogService = PresetCatalogService(
            this,
            jsonFileStorage,
            characterCardTransferService,
            formatCardTransferService,
            worldBookTransferService,
            ragRepository,
            transferJson
        )
        characterSessionService = CharacterSessionService(characterRepository, chatRepository)
        deletionCoordinator = DeletionCoordinator(
            jsonFileStorage,
            characterRepository,
            chatRepository,
            saveSlotRepository,
            ragRepository,
            novelAiImageStorage
        )
        StreamingNotificationManager.init(this)
        applicationScope.launch {
            deletionCoordinator.resumePending()
            WorldBookMigration(
                jsonFileStorage,
                characterRepository,
                worldBookRepository
            ).run()
            presetCatalogService.initialize()
            ModelConfigurationMigration(
                jsonFileStorage,
                modelRepository,
                settingsRepository,
                presetModelCatalogService
            ).run()
        }
    }
    
    companion object {
        lateinit var instance: ChatBarApp
            private set
        var batteryOptimizationHintShown = false
    }
}
