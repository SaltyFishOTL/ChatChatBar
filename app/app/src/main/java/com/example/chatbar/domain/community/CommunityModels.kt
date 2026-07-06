package com.example.chatbar.domain.community

import com.example.chatbar.domain.card.CharacterCardPackage
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.WorldBookPackage
import com.example.chatbar.domain.card.validateForImport
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CommunityItemType(val wireName: String, val label: String) {
    CHARACTER("character", "角色卡"),
    FORMAT("format", "格式卡"),
    WORLD_BOOK("world_book", "世界书");

    companion object {
        fun fromWireName(value: String): CommunityItemType =
            entries.firstOrNull { it.wireName == value } ?: CHARACTER
    }
}

data class CommunityItem(
    val id: String,
    val type: CommunityItemType,
    val title: String,
    val description: String,
    val tags: List<String>,
    val authorUserId: String,
    val authorName: String,
    val sourceLocalName: String,
    val filePath: String,
    val previewPath: String?,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val downloadCount: Int,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CommunityItemDto(
    val id: String = "",
    val type: String = "",
    val title: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("author_user_id")
    val authorUserId: String = "",
    @SerialName("author_name")
    val authorName: String = "",
    @SerialName("source_local_name")
    val sourceLocalName: String = "",
    @SerialName("file_path")
    val filePath: String = "",
    @SerialName("preview_path")
    val previewPath: String? = null,
    val sha256: String = "",
    @SerialName("size_bytes")
    val sizeBytes: Long = 0L,
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    @SerialName("download_count")
    val downloadCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("updated_at")
    val updatedAt: String = ""
) {
    fun toDomain(): CommunityItem =
        CommunityItem(
            id = id,
            type = CommunityItemType.fromWireName(type),
            title = title,
            description = description,
            tags = tags,
            authorUserId = authorUserId,
            authorName = authorName,
            sourceLocalName = sourceLocalName,
            filePath = filePath,
            previewPath = previewPath,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            schemaVersion = schemaVersion,
            downloadCount = downloadCount,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
}

@Serializable
data class CommunitySubmitRequest(
    val type: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    @SerialName("source_local_name")
    val sourceLocalName: String,
    @SerialName("file_path")
    val filePath: String,
    @SerialName("preview_path")
    val previewPath: String? = null,
    val sha256: String,
    @SerialName("size_bytes")
    val sizeBytes: Long,
    @SerialName("schema_version")
    val schemaVersion: Int
)

@Serializable
data class CommunitySession(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "bearer",
    val expiresAtMillis: Long = 0L,
    val userId: String,
    val displayName: String
) {
    val isExpired: Boolean
        get() = expiresAtMillis > 0L && System.currentTimeMillis() >= expiresAtMillis - SESSION_EXPIRY_SKEW_MS

    companion object {
        private const val SESSION_EXPIRY_SKEW_MS = 60_000L
    }
}

data class CommunityUploadCandidate(
    val id: String,
    val type: CommunityItemType,
    val title: String,
    val subtitle: String = ""
)

data class CommunityPreviewDraft(
    val fileName: String,
    val bytes: ByteArray,
    val contentType: String
)

data class CommunityPackageDraft(
    val type: CommunityItemType,
    val localId: String,
    val sourceLocalName: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val packageText: String,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val preview: CommunityPreviewDraft? = null
)

sealed interface CommunityImportPayload {
    data class Character(val data: CharacterCardPackage) : CommunityImportPayload
    data class Format(val data: FormatCardPackage) : CommunityImportPayload
    data class WorldBook(val data: WorldBookPackage) : CommunityImportPayload
}

data class CommunityPendingImport(
    val item: CommunityItem,
    val displayName: String,
    val conflictId: String?,
    val conflictName: String?,
    val payload: CommunityImportPayload
)

object CommunityPackagePolicy {
    const val MAX_PACKAGE_BYTES: Long = 20L * 1024L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun validate(type: CommunityItemType, rawJson: String) {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_PACKAGE_BYTES) { "社区包超过 20 MB" }
        when (type) {
            CommunityItemType.CHARACTER -> {
                val data = json.decodeFromString(CharacterCardPackage.serializer(), rawJson)
                data.validateForImport()
            }

            CommunityItemType.FORMAT -> {
                val data = json.decodeFromString(FormatCardPackage.serializer(), rawJson)
                require(data.schemaVersion == 1) { "不支持的格式卡 schemaVersion：${data.schemaVersion}" }
                require(data.name.isNotBlank()) { "格式卡名称不能为空" }
                require(data.content.isNotBlank()) { "格式卡内容不能为空" }
            }

            CommunityItemType.WORLD_BOOK -> {
                val data = json.decodeFromString(WorldBookPackage.serializer(), rawJson)
                require(data.schemaVersion == 1) { "不支持的世界书 schemaVersion：${data.schemaVersion}" }
                require(data.book.name.isNotBlank()) { "世界书名称不能为空" }
            }
        }
    }

    fun schemaVersion(rawJson: String): Int =
        json.parseToJsonElement(rawJson)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: error("缺少 schemaVersion")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
