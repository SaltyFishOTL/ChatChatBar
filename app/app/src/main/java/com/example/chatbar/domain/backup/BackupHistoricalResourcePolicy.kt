package com.example.chatbar.domain.backup

/** An already missing optional author snapshot is different from a lost user-owned original. */
internal object BackupHistoricalResourcePolicy {
    fun allowsMissingAvatar(entity: String, fields: List<String>): Boolean =
        entity.removePrefix("entities/").substringBefore('/').removeSuffix(".json") == "moment_posts" &&
            fields == listOf("senderAvatar")
}

internal data class BackupValidationReport(val missingHistoricalAvatars: Int) {
    val warnings: List<String> get() = if (missingHistoricalAvatars == 0) emptyList() else listOf(
        "已有 $missingHistoricalAvatars 条朋友圈记录的历史头像文件缺失。记录和缺失状态已保留，恢复后这些头像仍缺失。"
    )
}
