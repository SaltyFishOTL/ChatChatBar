package com.example.chatbar.domain.backup

import android.content.Context
import com.example.chatbar.data.security.FishAudioCredentialStore
import com.example.chatbar.data.security.NovelAiCredentialStore
import com.example.chatbar.domain.community.CommunitySession
import kotlinx.serialization.json.Json

internal object BackupPreferenceTransfer {
    fun flush(context: Context) {
        for (name in BackupDataRegistry.preferences) {
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()) { "本地偏好尚未保存，请重试" }
        }
    }
    fun capture(context: Context): BackupPreferences {
        val community = context.getSharedPreferences("community_session", Context.MODE_PRIVATE)
            .getString("session", null)
        community?.let { Json { ignoreUnknownKeys = true }.decodeFromString<CommunitySession>(it) }
        val mask = context.getSharedPreferences("image_mask_preferences", Context.MODE_PRIVATE)
        return BackupPreferences(
            novelAiToken = NovelAiCredentialStore(context).readForBackup(),
            fishAudioKey = FishAudioCredentialStore(context).readForBackup(),
            communitySession = community,
            brushSize = mask.getFloat("brush_size", 36f),
            brushType = mask.getString("brush_type", "Mosaic") ?: "Mosaic"
        )
    }

    fun restore(context: Context, snapshot: BackupPreferences) {
        // Synchronous commits are required before the restore journal can be committed.
        NovelAiCredentialStore(context).replaceFromBackup(snapshot.novelAiToken)
        FishAudioCredentialStore(context).replaceFromBackup(snapshot.fishAudioKey)
        check(context.getSharedPreferences("community_session", Context.MODE_PRIVATE).edit()
            .clear().putString("session", snapshot.communitySession).commit()) { "社区登录状态恢复失败" }
        check(context.getSharedPreferences("image_mask_preferences", Context.MODE_PRIVATE).edit()
            .clear().putFloat("brush_size", snapshot.brushSize)
            .putString("brush_type", snapshot.brushType).commit()) { "图片偏好恢复失败" }
    }
}
