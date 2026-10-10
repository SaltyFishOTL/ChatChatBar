package com.example.chatbar.domain.backup

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString

/** Independent of device Keystore; only small keysets and headers enter memory. */
internal object AppBackupCodec {
    val magic = "CBBACKUP".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private val random = SecureRandom()

    fun hasMagic(file: File): Boolean = file.inputStream().use(::hasMagic)
    fun hasMagic(input: InputStream): Boolean =
        DataInputStream(input).run { ByteArray(magic.size).also { readFully(it) }.contentEquals(magic) }

    fun encrypting(output: OutputStream, password: CharArray?): OutputStream {
        val protected = password != null
        var handle: KeysetHandle? = null
        val envelope = if (protected) {
            require(password!!.size in 8..1024) { "存档密码需为 8–1024 个字符" }
            StreamingAeadConfig.register()
            handle = KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB)
            val salt = ByteArray(32).also(random::nextBytes)
            val key = derive(password, salt)
            try {
                val plain = ByteArrayOutputStream().also {
                    CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(it))
                }.toByteArray()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                cipher.updateAAD(magic + salt)
                val wrapped = try { cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
                AppBackupEnvelope(
                    encrypted = true,
                    salt = Base64.getEncoder().encodeToString(salt),
                    wrappedKey = Base64.getEncoder().encodeToString(wrapped)
                )
            } finally { key.fill(0) }
        } else AppBackupEnvelope()
        val header = backupJson.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        DataOutputStream(output).apply {
            write(magic)
            writeInt(header.size)
            write(header)
            flush()
        }
        return handle?.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            ?.newEncryptingStream(output, magic + header) ?: output
    }

    fun decrypting(input: InputStream, password: CharArray?): InputStream {
        val source = DataInputStream(input)
        require(ByteArray(magic.size).also(source::readFully).contentEquals(magic)) {
            "该文件不是 ChatBar 全量存档"
        }
        val length = source.readInt()
        require(length in 1..32768) { "存档头损坏" }
        val header = ByteArray(length).also(source::readFully)
        val envelope = backupJson.decodeFromString<AppBackupEnvelope>(header.toString(Charsets.UTF_8))
        require(envelope.version == 1) { "存档格式不支持，请升级 APP" }
        if (!envelope.encrypted) {
            require(envelope.salt == null && envelope.wrappedKey == null) { "存档头无效" }
            return source
        }
        require(password != null && password.size in 8..1024) { "请输入存档密码（8–1024 个字符）" }
        val salt = Base64.getDecoder().decode(requireNotNull(envelope.salt))
        val wrapped = Base64.getDecoder().decode(requireNotNull(envelope.wrappedKey))
        require(salt.size == 32 && wrapped.size in 29..16384) { "存档加密参数损坏" }
        val key = derive(password, salt)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
            cipher.updateAAD(magic + salt)
            cipher.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (_: java.security.GeneralSecurityException) {
            error("密码错误或存档已损坏")
        } finally { key.fill(0) }
        val handle = try {
            StreamingAeadConfig.register()
            CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(plain))
        } finally { plain.fill(0) }
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            .newDecryptingStream(source, magic + header)
    }

    private fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
