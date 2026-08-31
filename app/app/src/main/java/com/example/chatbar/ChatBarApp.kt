package com.example.chatbar

import android.app.Application
import android.util.Log
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.service.StreamingNotificationManager
import com.example.chatbar.data.repository.*
import com.example.chatbar.domain.rag.*
import com.example.chatbar.domain.chat.*
import com.example.chatbar.domain.card.*
import com.example.chatbar.domain.community.CommunityService
import com.example.chatbar.domain.deletion.DeletionCoordinator
import com.example.chatbar.domain.draft.EditorDraftAssetService
import com.example.chatbar.domain.model.*
import com.example.chatbar.domain.image.*
import com.example.chatbar.domain.moment.*
import com.example.chatbar.domain.memory.LongTermMemoryService
import com.example.chatbar.domain.memory.LongTermMemoryAutoMaintenanceCoordinator
import com.example.chatbar.domain.search.*
import com.example.chatbar.domain.update.AppUpdateChecker
import com.example.chatbar.domain.update.AppUpdateManager
import com.example.chatbar.domain.update.DanbooruCatalogUpdateChecker
import com.example.chatbar.domain.update.DanbooruCatalogUpdateManager
import com.example.chatbar.domain.update.UpdateCenterChecker
import com.example.chatbar.domain.community.CommunityPreviewCache
import com.example.chatbar.domain.voice.*
import com.example.chatbar.domain.voice.qq.QqVoiceGestureGatewayRegistry
import com.example.chatbar.domain.voice.qq.QqVoiceTransferCoordinator
import com.example.chatbar.domain.voice.qq.QqVoiceTransferNotificationManager
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.utils.diagnostics.CrashReportManager
import com.example.chatbar.data.security.FishAudioCredentialStore
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
    lateinit var saveSlotPackageStorage: SaveSlotPackageStorage
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var ragRepository: RagRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var worldBookRepository: WorldBookRepository
        private set
    lateinit var editorDraftRepository: EditorDraftRepository
        private set
    lateinit var momentRepository: MomentRepository
        private set
    lateinit var voiceMessageRepository: VoiceMessageRepository
        private set
    lateinit var novelAiStudioRepository: NovelAiStudioRepository
        private set
    lateinit var novelAiDesignConversationRepository: NovelAiDesignConversationRepository
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
    lateinit var longTermMemoryService: LongTermMemoryService
        private set
    lateinit var longTermMemoryAutoMaintenanceCoordinator: LongTermMemoryAutoMaintenanceCoordinator
        private set
    lateinit var speakerTagHistoryService: SpeakerTagHistoryService
        private set
    lateinit var worldBookEngine: WorldBookEngine
        private set
    lateinit var streamingChatService: StreamingChatService
        private set
    lateinit var messageFormatRepairService: MessageFormatRepairService
        private set
    lateinit var imageUnderstandingService: ImageUnderstandingService
        private set
    lateinit var characterCardTransferService: CharacterCardTransferService
        private set
    lateinit var characterAutoFillService: CharacterAutoFillService
        private set
    lateinit var characterAppearanceImageService: CharacterAppearanceImageService
        private set
    lateinit var characterRewriteService: CharacterRewriteService
        private set
    lateinit var formatCardTransferService: FormatCardTransferService
        private set
    lateinit var worldBookTransferService: WorldBookTransferService
        private set
    lateinit var modelTemplateTransferService: ModelTemplateTransferService
        private set
    lateinit var sharedImportCoordinator: SharedImportCoordinator
        private set
    lateinit var presetCatalogService: PresetCatalogService
        private set
    lateinit var characterSessionService: CharacterSessionService
        private set
    lateinit var deletionCoordinator: DeletionCoordinator
        private set
    lateinit var editorDraftAssetService: EditorDraftAssetService
        private set
    lateinit var presetModelCatalogService: PresetModelCatalogService
        private set
    lateinit var novelAiStyleCatalogService: NovelAiStyleCatalogService
        private set
    lateinit var effectiveModelResolver: EffectiveModelResolver
        private set
    lateinit var novelAiCredentialStore: NovelAiCredentialStore
        private set
    lateinit var novelAiPromptDesigner: NovelAiPromptDesigner
        private set
    lateinit var novelAiImageService: NovelAiImageService
        private set
    lateinit var novelAiAccountService: NovelAiAccountService
        private set
    lateinit var novelAiPromptTokenCounter: NovelAiPromptTokenCounter
        private set
    lateinit var novelAiImageStorage: NovelAiImageStorage
        private set
    lateinit var novelAiStudioAssetStorage: NovelAiStudioAssetStorage
        private set
    lateinit var novelAiVibeEncodingService: NovelAiVibeEncodingService
        private set
    lateinit var novelAiDanbooruTagCatalog: DanbooruTagCatalog
        private set
    lateinit var novelAiPromptTranslationService: NovelAiPromptTranslationService
        private set
    lateinit var fishAudioCredentialStore: FishAudioCredentialStore
        private set
    lateinit var fishAudioStorage: FishAudioStorage
        private set
    lateinit var fishAudioService: FishAudioService
        private set
    lateinit var fishAudioTagService: FishAudioTagService
        private set
    lateinit var voicePlaybackController: VoicePlaybackController
        private set
    lateinit var qqVoiceTransferCoordinator: QqVoiceTransferCoordinator
        private set
    lateinit var qqVoiceGestureGateway: QqVoiceGestureGatewayRegistry
        private set
    lateinit var fishAudioGenerationCoordinator: FishAudioGenerationCoordinator
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
    lateinit var appUpdateManager: AppUpdateManager
        private set
    lateinit var danbooruCatalogUpdateChecker: DanbooruCatalogUpdateChecker
        private set
    lateinit var danbooruCatalogUpdateManager: DanbooruCatalogUpdateManager
        private set
    lateinit var updateCenterChecker: UpdateCenterChecker
        private set
    lateinit var communityService: CommunityService
        private set
    lateinit var momentGenerationService: MomentGenerationService
        private set
    lateinit var momentScheduler: MomentScheduler
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReportManager.initialize(this)

        // 1. 初始化文件存储
        jsonFileStorage = JsonFileStorage(this)
        
        // 2. 初始化各个仓库
        characterRepository = CharacterRepository(jsonFileStorage)
        chatRepository = ChatRepository(jsonFileStorage)
        modelRepository = ModelRepository(jsonFileStorage)
        formatCardRepository = FormatCardRepository(jsonFileStorage)
        saveSlotPackageStorage = SaveSlotPackageStorage(this)
        saveSlotRepository = SaveSlotRepository(jsonFileStorage, saveSlotPackageStorage)
        settingsRepository = SettingsRepository(jsonFileStorage)
        momentRepository = MomentRepository(jsonFileStorage)
        voiceMessageRepository = VoiceMessageRepository(jsonFileStorage)
        novelAiCredentialStore = NovelAiCredentialStore(this)
        fishAudioCredentialStore = FishAudioCredentialStore(this)
        ragRepository = RagRepository(jsonFileStorage)
        memoryRepository = MemoryRepository(jsonFileStorage)
        worldBookRepository = WorldBookRepository(jsonFileStorage)
        editorDraftRepository = EditorDraftRepository(jsonFileStorage)
        novelAiImageStorage = NovelAiImageStorage(this)
        novelAiStudioRepository = NovelAiStudioRepository(jsonFileStorage, novelAiImageStorage)
        novelAiDesignConversationRepository = NovelAiDesignConversationRepository(jsonFileStorage)
        editorDraftAssetService = EditorDraftAssetService(this)
        applicationScope.launch {
            jsonFileStorage.deleteSingleton("novelai_prompt_translation_cache")
        }

        // 3. 初始化 RAG 服务和其它引擎
        chunkingEngine = ChunkingEngine()
        embeddingService = EmbeddingService {
            settingsRepository.currentAppSettings.allowCleartextModelApi
        }
        vectorSearchEngine = VectorSearchEngine()
        
        streamingChatService = StreamingChatService {
            settingsRepository.currentAppSettings.allowCleartextModelApi
        }
        fishAudioStorage = FishAudioStorage(this)
        fishAudioService = FishAudioService(fishAudioStorage)
        fishAudioTagService = FishAudioTagService(streamingChatService)
        voicePlaybackController = VoicePlaybackController(this)
        qqVoiceTransferCoordinator = QqVoiceTransferCoordinator()
        qqVoiceGestureGateway = QqVoiceGestureGatewayRegistry()
        val novelAiCodexJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val novelAiCodexLoad = NovelAiCodexCatalogService(this, novelAiCodexJson).load()
        novelAiCodexLoad.fatalError?.let { Log.e("ChatBarApp", it) }
        novelAiCodexLoad.errors.forEach { Log.w("ChatBarApp", it) }
        val novelAiCodexSearchEngine = NovelAiCodexSearchEngine(
            catalog = novelAiCodexLoad.catalog,
            unavailableReason = novelAiCodexLoad.fatalError.orEmpty()
        )
        novelAiDanbooruTagCatalog = DanbooruTagCatalog(this)
        val novelAiPromptWordDictionary = assets.open("novelai_prompt_words.tsv").use(
            NovelAiPromptWordDictionary::fromTsv
        )
        novelAiPromptTranslationService = NovelAiPromptTranslationService(
            wordDictionary = novelAiPromptWordDictionary,
            tagLookup = novelAiDanbooruTagCatalog
        )
        novelAiPromptDesigner = NovelAiPromptDesigner(
            chatService = streamingChatService,
            tagResearchService = NovelAiTagResearchService(
                planner = LlmNovelAiTagSearchPlanner(streamingChatService),
                searchClient = novelAiDanbooruTagCatalog,
                codexSearcher = novelAiCodexSearchEngine
            ),
            promptPostProcessor = NovelAiPromptPostProcessor(novelAiCodexLoad.catalog.rewriteRules),
            imageUnderstandingServiceProvider = {
                if (::imageUnderstandingService.isInitialized) imageUnderstandingService else null
            }
        )
        novelAiImageService = NovelAiImageService()
        novelAiAccountService = NovelAiAccountService()
        novelAiPromptTokenCounter = NovelAiPromptTokenCounter(this)
        novelAiStudioAssetStorage = NovelAiStudioAssetStorage(this)
        novelAiVibeEncodingService = NovelAiVibeEncodingService(this)
        searchBackend = MediaWikiSearchBackend()
        characterResearchPlanner = CharacterResearchPlanner(streamingChatService)
        researchBriefSummarizer = LlmResearchBriefSummarizer(streamingChatService)
        appUpdateChecker = AppUpdateChecker()
        appUpdateManager = AppUpdateManager(this, applicationScope)
        danbooruCatalogUpdateChecker = DanbooruCatalogUpdateChecker(novelAiDanbooruTagCatalog)
        danbooruCatalogUpdateManager = DanbooruCatalogUpdateManager(
            scope = applicationScope,
            catalog = novelAiDanbooruTagCatalog
        )
        updateCenterChecker = UpdateCenterChecker(appUpdateChecker, danbooruCatalogUpdateChecker)

        ragManager = RagManager(
            chunkingEngine = chunkingEngine,
            embeddingService = embeddingService,
            vectorSearch = vectorSearchEngine,
            ragRepository = ragRepository
        )
        retrievalPlanner = RetrievalPlanner(streamingChatService)
        
        promptAssembler = PromptAssembler()
        contextWindowManager = ContextWindowManager()
        longTermMemoryService = LongTermMemoryService(
            chatRepository = chatRepository,
            memoryRepository = memoryRepository,
            settingsRepository = settingsRepository,
            streamingChatService = streamingChatService,
            contextWindowManager = contextWindowManager
        )
        momentGenerationService = MomentGenerationService(
            chatService = streamingChatService,
            promptDesigner = novelAiPromptDesigner,
            imageService = novelAiImageService,
            imageStorage = novelAiImageStorage,
            novelAiCredentials = novelAiCredentialStore,
            compiledMemoryProvider = { session ->
                if (session.longTermMemoryEnabled) {
                    longTermMemoryService.promptView(session.id).fullText
                } else {
                    ""
                }
            }
        )
        speakerTagHistoryService = SpeakerTagHistoryService(
            characterRepository,
            chatRepository
        )
        worldBookEngine = WorldBookEngine()

        val transferJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
        presetModelCatalogService = PresetModelCatalogService(this, transferJson)
        novelAiStyleCatalogService = NovelAiStyleCatalogService(this, transferJson)
        effectiveModelResolver = EffectiveModelResolver(modelRepository, settingsRepository, presetModelCatalogService)
        fishAudioGenerationCoordinator = FishAudioGenerationCoordinator(
            scope = applicationScope,
            chatRepository = chatRepository,
            characterRepository = characterRepository,
            settingsRepository = settingsRepository,
            voiceRepository = voiceMessageRepository,
            credentials = fishAudioCredentialStore,
            fishAudioService = fishAudioService,
            tagService = fishAudioTagService,
            modelResolver = effectiveModelResolver,
            storage = fishAudioStorage,
            playback = voicePlaybackController
        )
        longTermMemoryAutoMaintenanceCoordinator = LongTermMemoryAutoMaintenanceCoordinator(
            context = this,
            scope = applicationScope,
            chatRepository = chatRepository,
            settingsRepository = settingsRepository,
            modelResolver = effectiveModelResolver,
            memoryService = longTermMemoryService
        )
        messageFormatRepairService = MessageFormatRepairService(streamingChatService)
        momentScheduler = MomentScheduler(
            context = this,
            scope = applicationScope,
            settingsRepository = settingsRepository,
            characterRepository = characterRepository,
            chatRepository = chatRepository,
            momentRepository = momentRepository,
            modelResolver = effectiveModelResolver,
            generationService = momentGenerationService
        )
        imageUnderstandingService = ImageUnderstandingService(effectiveModelResolver, streamingChatService)
        val characterReferenceDocumentRetriever = RagCharacterReferenceDocumentRetriever(
            chunkingEngine = chunkingEngine,
            embeddingService = embeddingService,
            vectorSearch = vectorSearchEngine,
            embeddingConfigProvider = { effectiveModelResolver.embeddingModel() }
        )
        characterResearchService = CharacterResearchService(
            settingsProvider = { settingsRepository.getAppSettings() },
            planner = characterResearchPlanner,
            backend = searchBackend,
            summarizer = researchBriefSummarizer,
            referenceDocumentRetriever = characterReferenceDocumentRetriever,
            manualWebPageRetriever = HttpManualWebPageRetriever()
        )
        characterAutoFillService = CharacterAutoFillService(
            effectiveModelResolver,
            streamingChatService,
            characterResearchService,
            imageUnderstandingService
        )
        characterAppearanceImageService = CharacterAppearanceImageService(
            effectiveModelResolver,
            streamingChatService,
            settingsProvider = { settingsRepository.getAppSettings() }
        )
        characterRewriteService = CharacterRewriteService(effectiveModelResolver, streamingChatService, characterResearchService)
        worldBookTransferService = WorldBookTransferService(worldBookRepository, transferJson)
        characterCardTransferService = CharacterCardTransferService(this, characterRepository, worldBookRepository, ragRepository, transferJson)
        formatCardTransferService = FormatCardTransferService(formatCardRepository, transferJson)
        modelTemplateTransferService = ModelTemplateTransferService(modelRepository, transferJson)
        sharedImportCoordinator = SharedImportCoordinator(this, applicationScope)
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
        applicationScope.launch {
            communityService.monitorEnabledStatus()
        }
        applicationScope.launch {
            communityService.enabled.collect { enabled ->
                if (enabled) {
                    runCatching {
                        communityService.prefetchFirstItemsPage()?.let { page ->
                            CommunityPreviewCache.prefetchItems(this@ChatBarApp, communityService, page.items)
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Community warm prefetch failed", error)
                        communityService.clearWarmCache()
                    }
                } else {
                    communityService.clearWarmCache()
                }
            }
        }
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
            memoryRepository,
            momentRepository,
            novelAiImageStorage,
            voiceMessageRepository,
            fishAudioStorage,
            voicePlaybackController,
            fishAudioGenerationCoordinator,
            longTermMemoryAutoMaintenanceCoordinator
        )
        StreamingNotificationManager.init(this)
        QqVoiceTransferNotificationManager.init(this)
        applicationScope.launch {
            deletionCoordinator.resumePending()
            CharacterSpeakerMigration(jsonFileStorage, characterRepository).run()
            speakerTagHistoryService.resumePending()
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
            settingsRepository.initialize()
            voiceMessageRepository.initialize()
            fishAudioStorage.cleanupPartialFiles()
            saveSlotPackageStorage.cleanupPartialFiles()
            fishAudioStorage.cleanupOrphanFiles(
                voiceMessageRepository.voices.value.mapTo(mutableSetOf()) { it.audioPath }
            )
            momentRepository.initialize()
            novelAiStudioRepository.initialize()
            novelAiDesignConversationRepository.initialize()
            val studioDraft = novelAiStudioRepository.loadDraft()
            val studioUndo = novelAiStudioRepository.loadUndoDraft()
            val studioGuidanceCheckpoint = novelAiStudioRepository.loadGuidanceCheckpoint()
            novelAiStudioAssetStorage.cleanupOrphans(
                studioDraft.imageGuidance.ownedAssetPaths() +
                    studioUndo?.imageGuidance?.ownedAssetPaths().orEmpty() +
                    studioGuidanceCheckpoint?.ownedAssetPaths().orEmpty()
            )
            momentScheduler.kick("startup")
        }
    }
    
    companion object {
        private const val TAG = "ChatBarApp"
        lateinit var instance: ChatBarApp
            private set
        var batteryOptimizationHintShown = false
    }
}
