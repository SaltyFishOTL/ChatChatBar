package com.example.chatbar.domain.community

import android.content.Context
import android.net.Uri
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.FormatCardRepository
import com.example.chatbar.data.repository.WorldBookRepository
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.card.CharacterCardTransferService
import com.example.chatbar.domain.card.FormatCardTransferService
import com.example.chatbar.domain.card.WorldBookTransferService
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

    val configured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()
    val session: StateFlow<CommunitySession?> = _session.asStateFlow()

    fun discordLoginUrl(): String {
        checkConfigured()
        return "$baseUrl/auth/v1/authorize" +
            "?provider=discord" +
            "&redirect_to=${urlEncode(redirectUri)}" +
            "&scopes=${urlEncode("identify")}"
    }

    suspend fun handleAuthCallback(uri: Uri): CommunitySession = withContext(Dispatchers.IO) {
        checkConfigured()
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
        checkConfigured()
        val request = Request.Builder()
            .url("$baseUrl/rest/v1/community_items?select=*&order=created_at.desc&limit=100")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = executeForBody(request, "读取社区列表")
        json.decodeFromString(ListSerializer(CommunityItemDto.serializer()), body)
            .map(CommunityItemDto::toDomain)
    }

    suspend fun localCandidates(type: CommunityItemType): List<CommunityUploadCandidate> = withContext(Dispatchers.IO) {
        when (type) {
            CommunityItemType.CHARACTER -> characterRepository.getAll().map { card ->
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
        checkConfigured()
        val session = requireSession()
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
        json.decodeFromString(CommunityItemDto.serializer(), responseBody).toDomain()
    }

    suspend fun downloadPackage(item: CommunityItem): String = withContext(Dispatchers.IO) {
        checkConfigured()
        val request = Request.Builder()
            .url("$baseUrl/storage/v1/object/public/$PACKAGE_BUCKET/${encodePath(item.filePath)}")
            .header("apikey", anonKey)
            .get()
            .build()
        val body = executeForBody(request, "下载社区包")
        runCatching { incrementDownloadCount(item.id) }
        body
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

    private fun loadSession(): CommunitySession? =
        prefs.getString(KEY_SESSION, null)?.let { raw ->
            runCatching { json.decodeFromString(CommunitySession.serializer(), raw) }.getOrNull()
        }

    private fun saveSession(session: CommunitySession) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(CommunitySession.serializer(), session)).apply()
        _session.value = session
    }

    private fun checkConfigured() {
        check(configured) { "请先配置 CHATBAR_SUPABASE_URL 和 CHATBAR_SUPABASE_ANON_KEY" }
    }

    private fun previewFromPath(path: String): CommunityPreviewDraft? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_PREVIEW_BYTES) return null
        return CommunityPreviewDraft(
            fileName = file.name.ifBlank { "avatar.jpg" },
            bytes = file.readBytes(),
            contentType = contentTypeFor(file)
        )
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

    private fun contentTypeFor(file: File): String =
        when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }

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
        private const val PACKAGE_BUCKET = "community-packages"
        private const val PREVIEW_BUCKET = "community-previews"
        private const val MAX_ERROR_BODY = 600
        private const val MAX_TAGS = 8
        private const val MAX_PATH_PART = 80
        private const val MAX_PREVIEW_BYTES = 3L * 1024L * 1024L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

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
