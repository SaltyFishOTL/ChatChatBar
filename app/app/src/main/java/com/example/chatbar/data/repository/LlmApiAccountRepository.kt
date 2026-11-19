package com.example.chatbar.data.repository

import com.example.chatbar.data.local.entity.LlmApiAccount
import com.example.chatbar.data.local.entity.LlmApiConnection
import com.example.chatbar.data.security.LlmApiAccountVault
import com.example.chatbar.domain.backup.LocalDataMaintenance
import com.example.chatbar.domain.model.canonicalLlmApiEndpoint
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlmApiAccountRepository internal constructor(private val vault: LlmApiAccountVault) {
    private val mutex = Mutex()
    private var loaded = false
    @Volatile private var connections = emptyMap<String, LlmApiConnection>()
    private val _accounts = MutableStateFlow<List<LlmApiAccount>>(emptyList())
    val accounts = _accounts.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    internal val revision = _revision.asStateFlow()
    private val _loadError = MutableStateFlow(false)
    val loadError = _loadError.asStateFlow()

    suspend fun initialize() = mutex.withLock {
        if (loaded) return@withLock
        try {
            val saved = withContext(Dispatchers.IO) { vault.read() }
            check(saved.map { it.account.id }.distinct().size == saved.size)
            publish(saved.associateBy { it.account.id })
            loaded = true
            _loadError.value = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unreadable vault must never be treated as empty by a later write.
            _loadError.value = true
        }
    }

    fun connection(id: String): LlmApiConnection? = connections[id]

    suspend fun create(displayName: String, baseUrl: String, apiKey: String): LlmApiAccount =
        mutate {
            val account = LlmApiAccount(UUID.randomUUID().toString(), name(displayName), endpoint(baseUrl))
            commit(connections + (account.id to LlmApiConnection(account, credential(apiKey))))
            account
        }

    suspend fun update(id: String, displayName: String, apiKey: String?) = mutate {
        val old = connection(id) ?: error("对话 API 账户不存在")
        val account = old.account.copy(displayName = name(displayName))
        commit(connections + (id to LlmApiConnection(account, apiKey?.let(::credential) ?: old.apiKey, old.legacySource)))
    }

    internal suspend fun remove(id: String) = mutate {
        if (id in connections) commit(connections - id)
    }

    /** Idempotent across app restarts, including a failure after the secret is written. */
    internal suspend fun adoptLegacy(
        source: String,
        displayName: String,
        baseUrl: String,
        apiKey: String
    ): LlmApiAccount = mutate {
        val endpoint = endpoint(baseUrl)
        val key = credential(apiKey)
        val prior = connections.values.firstOrNull {
            it.legacySource == source && it.account.baseUrl == endpoint && it.apiKey == key
        }
        prior?.account ?: LlmApiAccount(UUID.randomUUID().toString(), name(displayName), endpoint).also { account ->
            commit(connections + (account.id to LlmApiConnection(account, key, source)))
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T {
        initialize()
        return mutex.withLock {
            check(loaded) { "无法打开对话 API 密钥库，请重新打开应用后重试" }
            withContext(NonCancellable + Dispatchers.IO) {
                LocalDataMaintenance.enter().use { block() }
            }
        }
    }

    private fun commit(next: Map<String, LlmApiConnection>) {
        try {
            vault.write(next.values.toList())
            // Verify durable contents before clearing the legacy key.
            val saved = vault.read().associateBy { it.account.id }
            check(next.keys == saved.keys && next.all { (id, value) ->
                saved[id]?.let {
                    it.account == value.account && it.apiKey == value.apiKey && it.legacySource == value.legacySource
                } == true
            }) { "无法读取已保存的对话 API 账户" }
            publish(saved)
        } catch (error: Exception) {
            // The write may have reached disk. Reload before another mutation.
            loaded = false
            _loadError.value = true
            throw error
        }
    }

    private fun publish(next: Map<String, LlmApiConnection>) {
        connections = next
        _accounts.value = next.values.map { it.account }.sortedBy { it.displayName }
        _revision.value++
    }

    private fun name(value: String): String =
        value.trim().also { require(it.isNotEmpty()) { "请填写账户名称" } }

    private fun endpoint(value: String): String =
        canonicalLlmApiEndpoint(value) ?: throw IllegalArgumentException("请填写完整的 API 地址，地址不能包含凭据、查询或片段")

    private fun credential(value: String): String =
        value.trim().also { require(it.isNotEmpty() && it.all { char -> char in '!'..'~' }) { "请填写有效的 API Key" } }
}
