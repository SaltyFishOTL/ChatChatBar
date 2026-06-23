package com.example.chatbar.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用JSON文件存储 - 将可序列化实体保存为JSON文件
 *
 * 目录结构: filesDir/entities/<entityType>/<id>.json
 * 单例文件: filesDir/entities/<entityType>.json
 */
class JsonFileStorage(private val context: Context) {

    companion object {
        private const val ENTITIES_DIR = "entities"
    }

    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val entityMutexes = ConcurrentHashMap<String, Mutex>()

    // 每种实体类型的内存缓存 + Flow通知
    private val cacheFlows = ConcurrentHashMap<String, MutableStateFlow<Map<String, Any>>>()

    private fun mutexFor(entityType: String): Mutex =
        entityMutexes.computeIfAbsent(entityType) { Mutex() }

    private fun entityDir(entityType: String): File {
        return File(context.filesDir, "$ENTITIES_DIR/$entityType").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private fun entityFile(entityType: String, id: String): File {
        return File(entityDir(entityType), "$id.json")
    }

    private fun singletonFile(entityType: String): File {
        return File(context.filesDir, "$ENTITIES_DIR/$entityType.json").also {
            it.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> getCacheFlow(entityType: String): MutableStateFlow<Map<String, T>> {
        return cacheFlows.computeIfAbsent(entityType) {
            MutableStateFlow(emptyMap())
        } as MutableStateFlow<Map<String, T>>
    }

    /**
     * 保存实体到JSON文件
     */
    suspend fun <T : Any> saveEntity(
        entityType: String,
        id: String,
        entity: T,
        serializer: KSerializer<T>
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val file = entityFile(entityType, id)
            val jsonStr = json.encodeToString(serializer, entity)
            file.writeText(jsonStr)

            // 更新缓存
            val flow = getCacheFlow<T>(entityType)
            flow.value = flow.value + (id to entity)
        }
    }

    /**
     * 加载单个实体
     */
    suspend fun <T : Any> loadEntity(
        entityType: String,
        id: String,
        serializer: KSerializer<T>
    ): T? = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val file = entityFile(entityType, id)
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 加载所有同类实体
     */
    suspend fun <T : Any> loadAll(
        entityType: String,
        serializer: KSerializer<T>
    ): List<T> = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val dir = entityDir(entityType)
            val entities = mutableMapOf<String, T>()
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val entity = json.decodeFromString(serializer, file.readText())
                    entities[file.nameWithoutExtension] = entity
                } catch (_: Exception) { /* skip corrupt files */ }
            }

            // 初始化缓存
            val flow = getCacheFlow<T>(entityType)
            flow.value = entities

            entities.values.toList()
        }
    }

    /**
     * 删除实体
     */
    suspend fun <T : Any> deleteEntity(
        entityType: String,
        id: String
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entityFile(entityType, id).delete()

            // 更新缓存
            val flow = getCacheFlow<T>(entityType)
            flow.value = flow.value - id
        }
    }

    /** 按存储 ID 前缀批量删除，不反序列化实体。 */
    suspend fun <T : Any> deleteByIdPrefix(entityType: String, prefix: String): Int =
        mutexFor(entityType).withLock {
            withContext(Dispatchers.IO) {
                val deletedIds = entityDir(entityType)
                    .listFiles { file ->
                        file.extension == "json" && file.nameWithoutExtension.startsWith(prefix)
                    }
                    .orEmpty()
                    .mapNotNull { file -> file.nameWithoutExtension.takeIf { file.delete() } }

                if (deletedIds.isNotEmpty()) {
                    val flow = getCacheFlow<T>(entityType)
                    flow.value = flow.value - deletedIds.toSet()
                }
                deletedIds.size
            }
        }

    /** 单次扫描、单次缓存更新地批量删除匹配实体。 */
    suspend fun <T : Any> deleteWhere(
        entityType: String,
        serializer: KSerializer<T>,
        predicate: (T) -> Boolean
    ): Int = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val deletedIds = entityDir(entityType)
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .mapNotNull { file ->
                    val matches = runCatching {
                        predicate(json.decodeFromString(serializer, file.readText()))
                    }.getOrDefault(false)
                    file.nameWithoutExtension.takeIf { matches && file.delete() }
                }

            if (deletedIds.isNotEmpty()) {
                val flow = getCacheFlow<T>(entityType)
                flow.value = flow.value - deletedIds.toSet()
            }
            deletedIds.size
        }
    }

    /**
     * 检查实体是否存在
     */
    suspend fun exists(entityType: String, id: String): Boolean =
        withContext(Dispatchers.IO) {
            entityFile(entityType, id).exists()
        }

    /**
     * 获取实体列表的响应式Flow
     * 首次调用时自动从磁盘加载
     */
    fun <T : Any> observeAll(
        entityType: String,
        serializer: KSerializer<T>
    ): Flow<List<T>> {
        val flow = getCacheFlow<T>(entityType)
        return flow.map { it.values.toList() }
    }

    /**
     * 保存单例实体（如AppSettings、PlayerSetting）
     */
    suspend fun <T : Any> saveSingleton(
        entityType: String,
        entity: T,
        serializer: KSerializer<T>
    ) = withContext(Dispatchers.IO) {
        val file = singletonFile(entityType)
        file.writeText(json.encodeToString(serializer, entity))
    }

    /**
     * 加载单例实体
     */
    suspend fun <T : Any> loadSingleton(
        entityType: String,
        serializer: KSerializer<T>
    ): T? = withContext(Dispatchers.IO) {
        val file = singletonFile(entityType)
        if (!file.exists()) return@withContext null
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 按条件查询实体
     */
    suspend fun <T : Any> query(
        entityType: String,
        serializer: KSerializer<T>,
        predicate: (T) -> Boolean
    ): List<T> {
        return loadAll(entityType, serializer).filter(predicate)
    }

    /**
     * 批量保存实体
     */
    suspend fun <T : Any> saveAll(
        entityType: String,
        entities: Map<String, T>,
        serializer: KSerializer<T>
    ) {
        entities.forEach { (id, entity) ->
            saveEntity(entityType, id, entity, serializer)
        }
    }

    /**
     * 删除某类型全部实体
     */
    suspend fun <T : Any> deleteAll(entityType: String) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entityDir(entityType).listFiles()?.forEach { it.delete() }
            val flow = getCacheFlow<T>(entityType)
            flow.value = emptyMap()
        }
    }
}
