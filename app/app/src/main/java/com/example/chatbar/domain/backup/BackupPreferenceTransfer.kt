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
    fun capture(context: Context, selection: AppBackupSelection? = null): BackupPreferences {
        fun include(id: String) = selection == null || id in selection.globalOptions
        val community = if (include("community")) context.getSharedPreferences("community_session", Context.MODE_PRIVATE)
            .getString("session", null) else null
        community?.let { Json { ignoreUnknownKeys = true }.decodeFromString<CommunitySession>(it) }
        val mask = context.getSharedPreferences("image_mask_preferences", Context.MODE_PRIVATE)
        return BackupPreferences(
            novelAiToken = if (include("novelAiCredentials")) NovelAiCredentialStore(context).readForBackup() else null,
            fishAudioKey = if (include("fishAudioCredentials")) FishAudioCredentialStore(context).readForBackup() else null,
            communitySession = community,
            brushSize = if (include("imagePreferences")) mask.getFloat("brush_size", 36f) else 36f,
            brushType = if (include("imagePreferences")) mask.getString("brush_type", "Mosaic") ?: "Mosaic" else "Mosaic"
        )
    }

    fun restore(context: Context, snapshot: BackupPreferences) {
        require(snapshot.retainedPreferences.all { it in BackupDataRegistry.preferences }) { "恢复偏好范围无效" }
        // Synchronous commits are required before the restore journal can be committed.
        if ("novelai_credentials" !in snapshot.retainedPreferences) NovelAiCredentialStore(context).replaceFromBackup(snapshot.novelAiToken)
        if ("fish_audio_credentials" !in snapshot.retainedPreferences) FishAudioCredentialStore(context).replaceFromBackup(snapshot.fishAudioKey)
        if ("community_session" !in snapshot.retainedPreferences) check(context.getSharedPreferences("community_session", Context.MODE_PRIVATE).edit()
            .clear().putString("session", snapshot.communitySession).commit()) { "社区登录状态恢复失败" }
        if ("image_mask_preferences" !in snapshot.retainedPreferences) check(context.getSharedPreferences("image_mask_preferences", Context.MODE_PRIVATE).edit()
            .clear().putFloat("brush_size", snapshot.brushSize)
            .putString("brush_type", snapshot.brushType).commit()) { "图片偏好恢复失败" }
    }
}
