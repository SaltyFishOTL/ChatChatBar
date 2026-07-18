package com.example.chatbar.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

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

    @OptIn(ExperimentalSerializationApi::class)
    private fun <T : Any> writeJsonFile(file: File, entity: T, serializer: KSerializer<T>) {
        val tempFile = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            tempFile.outputStream().buffered().use { output ->
                json.encodeToStream(serializer, entity, output)
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            tempFile.delete()
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
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any> saveEntity(
        entityType: String,
        id: String,
        entity: T,
        serializer: KSerializer<T>
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val file = entityFile(entityType, id)
            writeJsonFile(file, entity, serializer)

            // 更新缓存
            val flow = getCacheFlow<T>(entityType)
            flow.value = flow.value + (id to entity)
        }
    }

    /**
     * 加载单个实体
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any> loadEntity(
        entityType: String,
        id: String,
        serializer: KSerializer<T>
    ): T? = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val file = entityFile(entityType, id)
            if (!file.exists()) return@withContext null
            try {
                file.inputStream().buffered().use { input ->
                    json.decodeFromStream(serializer, input)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 加载所有同类实体
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any> loadAll(
        entityType: String,
        serializer: KSerializer<T>
    ): List<T> = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val dir = entityDir(entityType)
            val entities = mutableMapOf<String, T>()
            dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
                try {
                    val entity = file.inputStream().buffered().use { input ->
                        json.decodeFromStream(serializer, input)
                    }
                    entities[file.nameWithoutExtension] = entity
                } catch (_: Exception) { /* skip corrupt files */ }
            }

            // 初始化缓存
            val flow = getCacheFlow<T>(entityType)
            flow.value = entities

            entities.values.toList()
        }
    }

    /** 逐文件映射，不把完整实体放入通用缓存。适合大型实体列表摘要。 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any, R> loadAllMapped(
        entityType: String,
        serializer: KSerializer<T>,
        transform: (T) -> R
    ): List<R> = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entityDir(entityType)
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .mapNotNull { file ->
                    runCatching {
                        file.inputStream().buffered().use { input ->
                            transform(json.decodeFromStream(serializer, input))
                        }
                    }.getOrNull()
                }
        }
    }

    /** 逐文件筛选，不把完整实体集合放入通用缓存。适合大型实体查询。 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any> queryUncached(
        entityType: String,
        serializer: KSerializer<T>,
        predicate: (T) -> Boolean
    ): List<T> = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            val matches = mutableListOf<T>()
            entityDir(entityType)
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .forEach { file ->
                    val entity = try {
                        file.inputStream().buffered().use { input ->
                            json.decodeFromStream(serializer, input)
                        }
                    } catch (_: Exception) {
                        return@forEach
                    }
                    if (predicate(entity)) matches.add(entity)
                }
            matches
        }
    }

    /** 保存大型实体，但不长期保留其完整对象。 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun <T : Any> saveEntityUncached(
        entityType: String,
        id: String,
        entity: T,
        serializer: KSerializer<T>
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            writeJsonFile(entityFile(entityType, id), entity, serializer)
        }
    }

    /** 批量保存大型实体，但不把完整对象放入通用缓存。 */
    suspend fun <T : Any> saveAllUncached(
        entityType: String,
        entities: Map<String, T>,
        serializer: KSerializer<T>
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entities.forEach { (id, entity) ->
                writeJsonFile(entityFile(entityType, id), entity, serializer)
            }
        }
    }

    /** 删除未进入通用缓存的大型实体。 */
    suspend fun deleteEntityUncached(entityType: String, id: String) =
        mutexFor(entityType).withLock {
            withContext(Dispatchers.IO) {
                entityFile(entityType, id).delete()
            }
        }

    /** 逐文件删除大型实体，不创建或更新通用缓存。 */
    suspend fun <T : Any> deleteWhereUncached(
        entityType: String,
        serializer: KSerializer<T>,
        predicate: (T) -> Boolean
    ): Int = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entityDir(entityType)
                .listFiles { file -> file.extension == "json" }
                .orEmpty()
                .count { file ->
                    val matches = runCatching {
                        file.inputStream().buffered().use { input ->
                            @OptIn(ExperimentalSerializationApi::class)
                            predicate(json.decodeFromStream(serializer, input))
                        }
                    }.getOrDefault(false)
                    matches && file.delete()
                }
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
                        file.inputStream().buffered().use { input ->
                            @OptIn(ExperimentalSerializationApi::class)
                            predicate(json.decodeFromStream(serializer, input))
                        }
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
    ) = mutexFor(entityType).withLock {
        withContext(Dispatchers.IO) {
            entities.forEach { (id, entity) ->
                writeJsonFile(entityFile(entityType, id), entity, serializer)
            }
            if (entities.isNotEmpty()) {
                val flow = getCacheFlow<T>(entityType)
                flow.value = flow.value + entities
            }
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
