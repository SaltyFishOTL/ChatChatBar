package com.example.chatbar.data.repository

import com.example.chatbar.domain.moment.MomentImageVersions
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.domain.moment.MomentSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MomentRepository(private val storage: JsonFileStorage) {
    companion object {
        private const val POST_TYPE = "moment_posts"
        private const val TASK_TYPE = "moment_tasks"
    }

    private val _posts = MutableStateFlow<List<MomentPost>>(emptyList())
    val posts: Flow<List<MomentPost>> = _posts.asStateFlow()

    private var initialized = false
    private val postMutex = Mutex()

    suspend fun initialize() {
        if (initialized) return
        refreshPosts()
        initialized = true
    }

    private suspend fun refreshPosts() {
        _posts.value = storage.loadAll(POST_TYPE, MomentPost.serializer())
            .sortedByDescending { it.generatedAt }
    }

    suspend fun getAllPosts(): List<MomentPost> {
        initialize()
        return _posts.value
    }

    suspend fun getPostsForCard(cardId: String): List<MomentPost> =
        getAllPosts().filter { it.characterCardId == cardId }

    suspend fun latestPostForCard(cardId: String): MomentPost? =
        getPostsForCard(cardId)
            .filterNot { it.isPlaceholder }
            .maxByOrNull { it.generatedAt }

    suspend fun savePost(post: MomentPost) = postMutex.withLock { savePostLocked(post) }

    private suspend fun savePostLocked(post: MomentPost) {
        initialize()
        withContext(NonCancellable) {
            storage.saveEntity(POST_TYPE, post.id, post, MomentPost.serializer())
            _posts.value = (_posts.value.filterNot { it.id == post.id } + post).sortedByDescending { it.generatedAt }
        }
    }

    suspend fun appendImageVersion(postId: String, expectedPath: String?, path: String, prompt: String,
        metadata: GeneratedImageMetadata?, imageBrief: String? = null) = postMutex.withLock {
        val post = getPost(postId) ?: error("原朋友圈不存在")
        require(!post.isPlaceholder && MomentImageVersions.currentImagePath(post) == MomentImageVersions.imagePathOrNull(expectedPath)) { "原朋友圈图片已变化，请重新操作" }
        val updated = MomentImageVersions.append(post, path, prompt, metadata)
        savePostLocked(updated.copy(imageBrief = imageBrief ?: post.imageBrief, updatedAt = System.currentTimeMillis()))
    }

    suspend fun selectImageVersion(postId: String, versionId: String) = postMutex.withLock {
        val post = getPost(postId) ?: error("原朋友圈不存在")
        savePostLocked(MomentImageVersions.select(post, versionId).copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun getPost(id: String): MomentPost? =
        storage.loadEntity(POST_TYPE, id, MomentPost.serializer())

    suspend fun updatePost(post: MomentPost) {
        savePost(post.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleLike(postId: String) = postMutex.withLock {
        val post = getPost(postId) ?: return@withLock
        savePostLocked(post.copy(userLiked = !post.userLiked, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePostText(postId: String, text: String): MomentPost? =
        updatePostContent(postId, text)

    suspend fun updatePostContent(postId: String, text: String, sender: MomentSender? = null): MomentPost? = postMutex.withLock {
        val post = getPost(postId) ?: return@withLock null
        require(!post.isPlaceholder) { "生成失败占位朋友圈不可编辑" }
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "朋友圈文字不能为空" }
        require(sender == null || sender.name.isNotBlank()) { "发布人物名称不能为空" }
        if (normalizedText == post.text && (sender == null || sender == MomentSender(
                post.senderCharacterId, post.senderName, post.senderAvatar))) return@withLock post

        val updated = post.copy(
            text = normalizedText,
            senderCharacterId = if (sender != null) sender.characterId else post.senderCharacterId,
            senderName = sender?.name ?: post.senderName,
            senderAvatar = if (sender != null) sender.avatar else post.senderAvatar,
            updatedAt = System.currentTimeMillis()
        )
        savePostLocked(updated)
        return@withLock updated
    }

    suspend fun deletePost(postId: String): MomentPost? = postMutex.withLock {
        val post = getPost(postId) ?: return@withLock null
        storage.deleteEntity<MomentPost>(POST_TYPE, postId)
        refreshPosts()
        return@withLock post
    }

    suspend fun deletePendingTasksForCharacter(cardId: String): Int =
        storage.deleteWhere(TASK_TYPE, MomentTask.serializer()) {
            it.characterCardId == cardId && it.status == MomentTaskStatus.PENDING
        }

    suspend fun deletePendingFutureTasks(now: Long): Int =
        storage.deleteWhere(TASK_TYPE, MomentTask.serializer()) {
            it.status == MomentTaskStatus.PENDING && it.scheduledAt > now
        }

    suspend fun getAllTasks(): List<MomentTask> =
        storage.loadAll(TASK_TYPE, MomentTask.serializer())

    suspend fun getTasksForCard(cardId: String): List<MomentTask> =
        getAllTasks().filter { it.characterCardId == cardId }

    suspend fun pendingTasksDue(now: Long): List<MomentTask> =
        getAllTasks()
            .filter { it.status == MomentTaskStatus.PENDING && it.scheduledAt <= now }
            .sortedBy { it.scheduledAt }

    suspend fun nextPendingTask(): MomentTask? =
        getAllTasks()
            .filter { it.status == MomentTaskStatus.PENDING }
            .minByOrNull { it.scheduledAt }

    suspend fun taskForPost(postId: String): MomentTask? =
        getAllTasks().firstOrNull { it.postId == postId }

    suspend fun saveTask(task: MomentTask) {
        storage.saveEntity(TASK_TYPE, task.id, task, MomentTask.serializer())
    }

    suspend fun updateTask(task: MomentTask) {
        saveTask(task.copy(updatedAt = System.currentTimeMillis()))
    }
}
