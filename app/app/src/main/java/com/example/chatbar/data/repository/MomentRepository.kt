package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTask
import com.example.chatbar.data.local.entity.MomentTaskStatus
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
        getPostsForCard(cardId).maxByOrNull { it.generatedAt }

    suspend fun savePost(post: MomentPost) {
        storage.saveEntity(POST_TYPE, post.id, post, MomentPost.serializer())
        refreshPosts()
    }

    suspend fun getPost(id: String): MomentPost? =
        storage.loadEntity(POST_TYPE, id, MomentPost.serializer())

    suspend fun updatePost(post: MomentPost) {
        savePost(post.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun toggleLike(postId: String) {
        val post = getPost(postId) ?: return
        updatePost(post.copy(userLiked = !post.userLiked))
    }

    suspend fun deletePost(postId: String): MomentPost? {
        val post = getPost(postId) ?: return null
        storage.deleteEntity<MomentPost>(POST_TYPE, postId)
        refreshPosts()
        return post
    }

    suspend fun deleteForCharacter(cardId: String): List<MomentPost> {
        val posts = getPostsForCard(cardId)
        storage.deleteWhere(POST_TYPE, MomentPost.serializer()) { it.characterCardId == cardId }
        storage.deleteWhere(TASK_TYPE, MomentTask.serializer()) { it.characterCardId == cardId }
        refreshPosts()
        return posts
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

    suspend fun saveTask(task: MomentTask) {
        storage.saveEntity(TASK_TYPE, task.id, task, MomentTask.serializer())
    }

    suspend fun updateTask(task: MomentTask) {
        saveTask(task.copy(updatedAt = System.currentTimeMillis()))
    }
}
