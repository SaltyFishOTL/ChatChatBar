package com.example.chatbar.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NovelAiCredentialStore(context: Context, private val keyAlias: String = KEY_ALIAS) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val _configured = MutableStateFlow(load() != null)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    fun isConfigured(): Boolean = load() != null

    fun readForBackup(): String? {
        val present = preferences.contains(CIPHERTEXT) || preferences.contains(IV)
        return load().also { check(!present || it != null) { "NovelAI 密钥无法解密，完整导出已停止" } }
    }

    fun replaceFromBackup(token: String?) {
        if (token == null) {
            check(preferences.edit().clear().commit()) { "NovelAI 密钥恢复失败" }
            _configured.value = false
            return
        }
        require(token.isNotBlank()) { "存档 NovelAI 密钥无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        check(preferences.edit().clear()
            .putString(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "NovelAI 密钥恢复失败" }
        _configured.value = true
    }

    fun save(token: String) {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            val normalized = token.trim()
            require(normalized.isNotEmpty()) { "NovelAI API Token 不能为空" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(cipher.doFinal(normalized.toByteArray()), Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
            _configured.value = true
        }
    }

    fun load(): String? {
        val ciphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)))
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    fun clear() {
        com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            preferences.edit().remove(CIPHERTEXT).remove(IV).apply()
            _configured.value = false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "novelai_credentials"
        const val CIPHERTEXT = "token_ciphertext"
        const val IV = "token_iv"
        const val KEY_ALIAS = "chatbar_novelai_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
