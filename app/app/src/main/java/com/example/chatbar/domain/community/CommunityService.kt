package com.example.chatbar.domain.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.card.CharacterCardTransferService
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.card.WorldBookTransferService
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class CommunityService(
    private val app: ChatBarApp,
    private val characterRepository: CharacterRepository,
    private val formatCardRepository: FormatCardRepository,
    private val worldBookRepository: WorldBookRepository,
    private val characterTransfers: CharacterCardTransferService,
    private val formatTransfers: FormatCardTransferService,
    private val worldBookTransfers: WorldBookTransferService,
    private val json: Json,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
) {
    private val baseUrl = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY.trim()
    private val redirectUri = BuildConfig.SUPABASE_REDIRECT_URI.trim()
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(loadSession())
    private val _enabled = MutableStateFlow(false)

    val configured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val session: StateFlow<CommunitySession?> = _session.asStateFlow()
    @Volatile
    private var warmedItemsPage: WarmedCommunityPage? = null
    @Volatile
    private var disabledMessage: String = "社区已关闭"
    @Volatile
    private var statusLoadedAt: Long = 0L

    fun discordLoginUrl(): String {
        check(configured) { "请先配置 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY" }
        check(enabled.value) { disabledMessage }
        return "$baseUrl/auth/v1/authorize" +
            "?provider=discord" +
            "&redirect_to=${urlEncode(redirectUri)}" +
            "&scopes=${urlEncode("identify")}"
    }

    suspend fun monitorEnabledStatus() {
        while (true) {
            runCatching { refreshEnabledStatus(force = true) }
                .onFailure { error -> Log.w(TAG, "Community status refresh failed", error) }
            delay(STATUS_POLL_INTERVAL_MS)
        }
    }

    suspend fun refreshEnabledStatus(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!configured) {
            _enabled.value = false
            disabledMessage = "请先配置 Supabase"
            return@withContext false
        }
        val now = System.currentTimeMillis()
        if (!force && now - statusLoadedAt <= STATUS_CACHE_TTL_MS) {
            return@withContext enabled.value
        }
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_runtime_config?id=eq.community&select=enabled,message,updated_at")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val rows = runCatching {
            val body = executeForBody(request, "读取社区状态")
            json.decodeFromString(ListSerializer(CommunityRuntimeConfigDto.serializer()), body)
        }.getOrElse { error ->
            if (error.isMissingRuntimeConfig()) {
                statusLoadedAt = now
                disabledMessage = ""
                _enabled.value = true
                Log.w(TAG, "Community runtime config missing; using legacy enabled mode", error)
                return@withContext true
            }
            throw error
        }
        val config = rows.firstOrNull()
        val nextEnabled = config?.enabled ?: true
        disabledMessage = config?.message?.takeIf(String::isNotBlank) ?: "社区已关闭"
        statusLoadedAt = now
        _enabled.value = nextEnabled
        if (!nextEnabled) clearWarmCache()
        nextEnabled
    }

    suspend fun handleAuthCallback(uri: Uri): CommunitySession = withContext(Dispatchers.IO) {
        checkAvailable()
        val params = uri.callbackParams()
        params["error_description"]?.let { error(it) }
        params["error"]?.let { error(it) }
        val accessToken = params["access_token"]?.takeIf(String::isNotBlank)
            ?: error("回调缺少 access_token")
        val refreshToken = params["refresh_token"]?.takeIf(String::isNotBlank)
        val tokenType = params["token_type"]?.takeIf(String::isNotBlank) ?: "bearer"
        val expiresInSeconds = params["expires_in"]?.toLongOrNull() ?: 0L
        val user = fetchUser(accessToken)
        val session = CommunitySession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType,
            expiresAtMillis = if (expiresInSeconds > 0L) {
                System.currentTimeMillis() + expiresInSeconds * 1000L
            } else {
                0L
            },
            userId = user.id,
            displayName = user.displayName()
        )
        saveSession(session)
        session
    }

    fun signOut() {
        prefs.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    suspend fun listItems(): List<CommunityItem> = withContext(Dispatchers.IO) {
        checkAvailable()
        queryItems(authorUserId = null, accessToken = null, offset = 0, limit = DEFAULT_LIST_LIMIT).items
    }

    suspend fun prefetchFirstItemsPage(limit: Int = DEFAULT_PAGE_SIZE): CommunityItemPage? = withContext(Dispatchers.IO) {
        if (!configured || !refreshEnabledStatus()) return@withContext null
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        warmedItemsPage
            ?.takeIf { it.limit == safeLimit && System.currentTimeMillis() - it.loadedAt <= WARM_CACHE_TTL_MS }
            ?.page
            ?.let { return@withContext it }
        runCatching {
            val page = queryItems(authorUserId = null, accessToken = null, offset = 0, limit = safeLimit)
            warmedItemsPage = WarmedCommunityPage(
                limit = safeLimit,
                page = page,
                loadedAt = System.currentTimeMillis()
            )
            page
        }.getOrNull()
    }

    suspend fun listItemsPage(
        offset: Int,
        limit: Int,
        preferWarmCache: Boolean = false
    ): CommunityItemPage = withContext(Dispatchers.IO) {
        checkAvailable()
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        if (preferWarmCache && offset == 0) {
            warmedItemsPage
                ?.takeIf { it.limit == safeLimit && System.currentTimeMillis() - it.loadedAt <= WARM_CACHE_TTL_MS }
                ?.page
                ?.let { return@withContext it }
        }
        val page = queryItems(authorUserId = null, accessToken = null, offset = offset, limit = safeLimit)
        if (offset == 0) {
            warmedItemsPage = WarmedCommunityPage(safeLimit, page, System.currentTimeMillis())
        }
        page
    }

    suspend fun listMyItems(): List<CommunityItem> = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        queryItems(authorUserId = session.userId, accessToken = session.accessToken, offset = 0, limit = DEFAULT_LIST_LIMIT).items
    }

    suspend fun listMyItemsPage(offset: Int, limit: Int): CommunityItemPage = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        queryItems(authorUserId = session.userId, accessToken = session.accessToken, offset = offset, limit = limit)
    }

    private fun queryItems(authorUserId: String?, accessToken: String?, offset: Int, limit: Int): CommunityItemPage {
        val authorFilter = authorUserId?.let { "&author_user_id=eq.${urlEncode(it)}" }.orEmpty()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_items?select=$COMMUNITY_ITEM_SELECT$authorFilter&order=created_at.desc&offset=$safeOffset&limit=${safeLimit + 1}")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${accessToken ?: anonKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeForBody(request, "读取社区列表")
        val decoded = json.decodeFromString(ListSerializer(CommunityItemDto.serializer()), body)
            .map(CommunityItemDto::toDomain)
        val pageItems = decoded.take(safeLimit)
        return CommunityItemPage(
            items = pageItems,
            nextOffset = safeOffset + pageItems.size,
            hasMore = decoded.size > safeLimit
        )
    }

    suspend fun localCandidates(type: CommunityItemType): List<CommunityUploadCandidate> = withContext(Dispatchers.IO) {
        when (type) {
            CommunityItemType.CHARACTER -> characterRepository.getAll()
                .filterNot { it.isCommunityDownload }
                .map { card ->
                    CommunityUploadCandidate(
                        id = card.id,
                        type = type,
                        title = card.name,
                        subtitle = "${card.characters.size} 人物 / ${card.customDocuments.size} 文档"
                    )
                }

            CommunityItemType.FORMAT -> formatCardRepository.getAll().map { card ->
                CommunityUploadCandidate(
                    id = card.id,
                    type = type,
                    title = card.name,
                    subtitle = if (card.isDefault) "默认格式卡" else ""
                )
            }

            CommunityItemType.WORLD_BOOK -> worldBookRepository.getAll().map { book ->
                CommunityUploadCandidate(
                    id = book.id,
                    type = type,
                    title = book.name,
                    subtitle = "${book.entries.size} 条目"
                )
            }
        }
    }

    suspend fun buildDraft(
        type: CommunityItemType,
        localId: String,
        title: String,
        description: String,
        tags: List<String>
    ): CommunityPackageDraft = withContext(Dispatchers.IO) {
        val sourceName: String
        val packageText: String
        val preview: CommunityPreviewDraft?
        when (type) {
            CommunityItemType.CHARACTER -> {
                val card = characterRepository.getById(localId) ?: error("角色卡不存在")
                require(!card.isCommunityDownload) { "下载角色卡不能上传，请先复制为本地角色卡" }
                sourceName = card.name
                packageText = characterTransfers.exportJson(localId)
                preview = card.avatar?.let(::previewFromPath)
            }

            CommunityItemType.FORMAT -> {
                val card = formatCardRepository.getById(localId) ?: error("格式卡不存在")
                sourceName = card.name
                packageText = formatTransfers.exportJson(localId)
                preview = null
            }

            CommunityItemType.WORLD_BOOK -> {
                val book = worldBookRepository.getById(localId) ?: error("世界书不存在")
                sourceName = book.name
                packageText = worldBookTransfers.exportJson(localId)
                preview = null
            }
        }
        CommunityPackagePolicy.validate(type, packageText)
        val bytes = packageText.toByteArray(Charsets.UTF_8)
        require(bytes.size <= CommunityPackagePolicy.MAX_PACKAGE_BYTES) { "社区包超过 20 MB" }
        CommunityPackageDraft(
            type = type,
            localId = localId,
            sourceLocalName = sourceName,
            title = title.ifBlank { sourceName },
            description = description.trim(),
            tags = tags.map(String::trim).filter(String::isNotBlank).distinct().take(MAX_TAGS),
            packageText = packageText,
            sha256 = CommunityPackagePolicy.sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            schemaVersion = CommunityPackagePolicy.schemaVersion(packageText),
            preview = preview
        )
    }

    suspend fun submitDraft(draft: CommunityPackageDraft): CommunityItem = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        publishDraft(draft = draft, session = session, itemId = null)
    }

    suspend fun updateDraft(item: CommunityItem, draft: CommunityPackageDraft): CommunityItem = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        require(item.authorUserId == session.userId) { "只能更新自己上传的条目" }
        require(item.type == draft.type) { "不能用不同类型覆盖社区条目" }
        publishDraft(draft = draft, session = session, itemId = item.id)
    }

    suspend fun deleteItem(item: CommunityItem) = withContext(Dispatchers.IO) {
        checkAvailable()
        val session = requireSession()
        require(item.authorUserId == session.userId) { "只能删除自己上传的条目" }
        val body = json.encodeToString(CommunityDeleteRequest(action = "delete", itemId = item.id))
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/submit-community-item")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeForBody(request, "删除社区条目")
    }

    private fun publishDraft(
        draft: CommunityPackageDraft,
        session: CommunitySession,
        itemId: String?
    ): CommunityItem {
        ensureNoCharacterNameConflict(draft, itemId, session.accessToken)
        val filePath = storagePath(session.userId, draft.type, draft.title, "json")
        uploadObject(
            bucket = PACKAGE_BUCKET,
            path = filePath,
            bytes = draft.packageText.toByteArray(Charsets.UTF_8),
            contentType = "application/json",
            accessToken = session.accessToken
        )
        val previewPath = draft.preview?.let { preview ->
            val extension = File(preview.fileName).extension.ifBlank { "jpg" }
            val path = storagePath(session.userId, draft.type, draft.title, extension, prefix = "previews")
            uploadObject(PREVIEW_BUCKET, path, preview.bytes, preview.contentType, session.accessToken)
            path
        }
        val payload = CommunitySubmitRequest(
            itemId = itemId,
            type = draft.type.wireName,
            title = draft.title,
            description = draft.description,
            tags = draft.tags,
            sourceLocalName = draft.sourceLocalName,
            filePath = filePath,
            previewPath = previewPath,
            sha256 = draft.sha256,
            sizeBytes = draft.sizeBytes,
            schemaVersion = draft.schemaVersion
        )
        val body = json.encodeToString(CommunitySubmitRequest.serializer(), payload)
        val request = Request.Builder()
            .url("$baseUrl/functions/v1/submit-community-item")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer ${session.accessToken}")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseBody = executeForBody(request, "发布社区条目")
        return json.decodeFromString(CommunityItemDto.serializer(), responseBody).toDomain()
    }

    private fun ensureNoCharacterNameConflict(
        draft: CommunityPackageDraft,
        itemId: String?,
        accessToken: String
    ) {
        if (draft.type != CommunityItemType.CHARACTER) return
        val existing = queryItems(authorUserId = null, accessToken = accessToken, offset = 0, limit = DEFAULT_LIST_LIMIT).items
            .firstOrNull { item ->
                item.id != itemId &&
                    item.type == CommunityItemType.CHARACTER &&
                    (
                        NamePolicy.isSame(item.title, draft.title) ||
                            NamePolicy.isSame(item.sourceLocalName, draft.sourceLocalName)
                        )
            }
        require(existing == null) { "社区已有同名角色卡：${existing?.title}" }
    }

    suspend fun readPackage(item: CommunityItem): String = fetchPackage(item, countDownload = false)

    suspend fun downloadPackage(item: CommunityItem): String = fetchPackage(item, countDownload = true)

    private suspend fun fetchPackage(item: CommunityItem, countDownload: Boolean): String = withContext(Dispatchers.IO) {
        checkAvailable()
        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/public/$PACKAGE_BUCKET/${encodePath(item.filePath)}")
            .header("apikey", anonKey)
            .get()
            .build()
        val body = executeForBody(request, "下载社区包")
        if (countDownload) runCatching { incrementDownloadCount(item.id) }
        body
    }

    fun previewUrl(item: CommunityItem): String? {
        val path = item.previewPath?.takeIf(String::isNotBlank) ?: return null
        if (!configured || !enabled.value) return null
        return "$baseUrl/storage/v1/object/public/$PREVIEW_BUCKET/${encodePath(path)}"
    }

    fun clearWarmCache() {
        warmedItemsPage = null
    }

    private suspend fun requireSession(): CommunitySession {
        val current = _session.value ?: error("请先用 Discord 登录")
        if (!current.isExpired) return current
        val refreshed = runCatching { refreshSession(current) }.getOrElse {
            signOut()
            error("登录已过期，请重新登录")
        }
        saveSession(refreshed)
        return refreshed
    }

    private fun refreshSession(current: CommunitySession): CommunitySession {
        val refreshToken = current.refreshToken?.takeIf(String::isNotBlank) ?: error("缺少 refresh_token")
        val body = json.encodeToString(RefreshTokenRequest.serializer(), RefreshTokenRequest(refreshToken))
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/token?grant_type=refresh_token")
            .header("apikey", anonKey)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseBody = executeForBody(request, "刷新社区登录")
        val token = json.decodeFromString(TokenResponse.serializer(), responseBody)
        val user = token.user ?: fetchUser(token.accessToken)
        return CommunitySession(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: refreshToken,
            tokenType = token.tokenType.ifBlank { current.tokenType },
            expiresAtMillis = if (token.expiresIn > 0L) {
                System.currentTimeMillis() + token.expiresIn * 1000L
            } else {
                0L
            },
            userId = user.id.ifBlank { current.userId },
            displayName = user.displayName().ifBlank { current.displayName }
        )
    }

    private fun fetchUser(accessToken: String): SupabaseUser {
        val request = Request.Builder()
            .url("$baseUrl/auth/v1/user")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeForBody(request, "读取 Discord 用户")
        return json.decodeFromString(SupabaseUser.serializer(), body)
    }

    private fun uploadObject(
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
        accessToken: String
    ) {
        val mediaType = contentType.toMediaType()
        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/$bucket/${encodePath(path)}")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", contentType)
            .header("x-upsert", "false")
            .post(bytes.toRequestBody(mediaType))
            .build()
        executeForBody(request, "上传社区文件")
    }

    private fun incrementDownloadCount(itemId: String) {
        val body = """{"item_id":"$itemId"}"""
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/rpc/increment_community_download_count")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeForBody(request, "更新下载次数")
    }

    private fun executeForBody(request: Request, action: String): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = body.take(MAX_ERROR_BODY)
                error("${action}失败：HTTP ${response.code}${if (detail.isBlank()) "" else " - $detail"}")
            }
            return body
        }
    }

    private fun Throwable.isMissingRuntimeConfig(): Boolean {
        val text = message.orEmpty()
        return text.contains("PGRST205") &&
            text.contains("community_runtime_config")
    }

    private fun loadSession(): CommunitySession? =
        prefs.getString(KEY_SESSION, null)?.let { raw ->
            runCatching { json.decodeFromString(CommunitySession.serializer(), raw) }.getOrNull()
        }

    private fun saveSession(session: CommunitySession) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(CommunitySession.serializer(), session)).apply()
        _session.value = session
    }

    private suspend fun checkAvailable() {
        checkConfigured()
        check(refreshEnabledStatus()) { disabledMessage }
    }

    private fun checkConfigured() {
        check(configured) { "请先配置 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY" }
    }

    private fun previewFromPath(path: String): CommunityPreviewDraft? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_PREVIEW_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = previewSampleSize(width, height)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        val preview = scalePreview(decoded)
        if (preview !== decoded) decoded.recycle()
        val bytes = compressPreview(preview)
        preview.recycle()
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_UPLOAD_BYTES) return null
        return CommunityPreviewDraft(
            fileName = "preview.jpg",
            bytes = bytes,
            contentType = "image/jpeg"
        )
    }

    private fun previewSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > PREVIEW_MAX_DIMENSION * 2 || height / sample > PREVIEW_MAX_DIMENSION * 2) {
            sample *= 2
        }
        return sample
    }

    private fun scalePreview(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= PREVIEW_MAX_DIMENSION) return bitmap
        val scale = PREVIEW_MAX_DIMENSION.toFloat() / maxSide.toFloat()
        val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun compressPreview(bitmap: Bitmap): ByteArray {
        var quality = PREVIEW_JPEG_QUALITY
        while (quality >= PREVIEW_MIN_JPEG_QUALITY) {
            val output = ByteArrayOutputStream()
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                val bytes = output.toByteArray()
                if (bytes.size <= MAX_PREVIEW_UPLOAD_BYTES || quality == PREVIEW_MIN_JPEG_QUALITY) {
                    return bytes
                }
            }
            quality -= 10
        }
        return ByteArray(0)
    }

    private fun storagePath(
        userId: String,
        type: CommunityItemType,
        title: String,
        extension: String,
        prefix: String = "packages"
    ): String {
        val safeTitle = safePathPart(title)
        val ext = safePathPart(extension).ifBlank { "json" }
        return "$userId/$prefix/${type.wireName}/${UUID.randomUUID()}_$safeTitle.$ext"
    }

    private fun safePathPart(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .take(MAX_PATH_PART)
            .ifBlank { "item" }

    private fun Uri.callbackParams(): Map<String, String> = buildMap {
        queryParameterNames.forEach { name ->
            getQueryParameter(name)?.let { put(name, it) }
        }
        fragment
            ?.split("&")
            ?.mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = pair.substring(0, index).urlDecode()
                val value = pair.substring(index + 1).urlDecode()
                key to value
            }
            ?.forEach { (key, value) -> put(key, value) }
    }

    private fun String.urlDecode(): String =
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { urlEncode(it).replace("+", "%20") }

    private fun SupabaseUser.displayName(): String {
        val metadataName = userMetadata.firstString("full_name")
            ?: userMetadata.firstString("name")
            ?: userMetadata.firstString("preferred_username")
            ?: userMetadata.firstString("user_name")
        return metadataName ?: email?.substringBefore('@') ?: "Discord 用户"
    }

    private fun JsonObject.firstString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    companion object {
        private const val PREFS_NAME = "community_session"
        private const val KEY_SESSION = "session"
        private const val TAG = "CommunityService"
        private const val PACKAGE_BUCKET = "community-packages"
        private const val PREVIEW_BUCKET = "community-previews"
        private const val COMMUNITY_ITEM_SELECT =
            "id,type,title,description,tags,author_user_id,author_name,source_local_name,file_path,preview_path,sha256,size_bytes,schema_version,download_count,created_at,updated_at"
        private const val DEFAULT_LIST_LIMIT = 100
        private const val DEFAULT_PAGE_SIZE = 20
        private const val MAX_PAGE_SIZE = 100
        private const val STATUS_CACHE_TTL_MS = 5_000L
        private const val STATUS_POLL_INTERVAL_MS = 5_000L
        private const val WARM_CACHE_TTL_MS = 60_000L
        private const val MAX_ERROR_BODY = 600
        private const val MAX_TAGS = 8
        private const val MAX_PATH_PART = 80
        private const val MAX_PREVIEW_SOURCE_BYTES = 20L * 1024L * 1024L
        private const val MAX_PREVIEW_UPLOAD_BYTES = 80 * 1024
        private const val PREVIEW_MAX_DIMENSION = 160
        private const val PREVIEW_JPEG_QUALITY = 56
        private const val PREVIEW_MIN_JPEG_QUALITY = 40
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class WarmedCommunityPage(
    val limit: Int,
    val page: CommunityItemPage,
    val loadedAt: Long
)

@Serializable
private data class CommunityRuntimeConfigDto(
    val enabled: Boolean = true,
    val message: String = ""
)

@Serializable
private data class SupabaseUser(
    val id: String = "",
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonObject = JsonObject(emptyMap())
)

@Serializable
private data class RefreshTokenRequest(
    @SerialName("refresh_token")
    val refreshToken: String
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String = "bearer",
    @SerialName("expires_in")
    val expiresIn: Long = 0L,
    val user: SupabaseUser? = null
)
