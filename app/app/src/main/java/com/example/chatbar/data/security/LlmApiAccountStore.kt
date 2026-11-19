package com.example.chatbar.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.example.chatbar.data.local.entity.LlmApiAccount
import com.example.chatbar.data.local.entity.LlmApiConnection
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface LlmApiAccountVault {
    fun read(): List<LlmApiConnection>
    fun write(connections: List<LlmApiConnection>)
}

/** Device-local credentials are excluded from both Android backup and portable ChatBar export. */
internal class LlmApiAccountStore(
    context: Context,
    private val keyAlias: String = "chatbar_llm_api_accounts_v1",
    filename: String = "llm_api_accounts_v1"
) : LlmApiAccountVault {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))

    @Serializable
    private data class Entry(val account: LlmApiAccount, val apiKey: String)

    override fun read(): List<LlmApiConnection> {
        val encrypted = try {
            file.openRead().use { it.readBytes() }
        } catch (error: FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            return emptyList()
        }
        require(encrypted.size > IV_BYTES) { "对话 API 密钥库格式无效" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyStore().getKey(keyAlias, null) as? SecretKey ?: error("对话 API 密钥无法解密"),
            GCMParameterSpec(128, encrypted.copyOfRange(0, IV_BYTES))
        )
        val plaintext = cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
        return try {
            Json.decodeFromString<List<Entry>>(plaintext.decodeToString()).map { entry ->
                LlmApiConnection(entry.account, entry.apiKey)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override fun write(connections: List<LlmApiConnection>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        check(cipher.iv.size == IV_BYTES)
        val plaintext = Json.encodeToString(connections.map { Entry(it.account, it.apiKey) }).encodeToByteArray()
        val encrypted = try {
            cipher.iv + cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val output = file.startWrite()
        try {
            output.write(encrypted)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
