package com.example.chatbar.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class DebugLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String,
    val modelName: String = "",
    val apiUrl: String = "",
    val requestBodyJson: String = "",
    val systemPrompt: String = "",
    val ragChunks: List<String> = emptyList(),
    val rawSseOutput: StringBuilder = StringBuilder(),
    val rawAiOutput: StringBuilder = StringBuilder(),          // 原生AI输出文本
    val rawReasoningOutput: StringBuilder = StringBuilder(),   // 原生思维链输出文本
    var estimatedPromptTokens: Int = 0,
    var estimatedCompletionTokens: Int = 0,
    var error: String? = null,
    var isCompleted: Boolean = false
) {
    val rawSseOutputText: String
        get() = rawSseOutput.toString()

    val rawAiOutputText: String
        get() = rawAiOutput.toString()

    val rawReasoningOutputText: String
        get() = rawReasoningOutput.toString()

    val totalTokens: Int
        get() = estimatedPromptTokens + estimatedCompletionTokens
}

object DebugLogManager {
    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val activeLogs = ConcurrentHashMap<String, String>()

    fun startRequest(
        sessionId: String,
        modelName: String,
        apiUrl: String,
        requestBodyJson: String,
        systemPrompt: String,
        ragChunks: List<String>
    ): String {
        val entry = DebugLogEntry(
            sessionId = sessionId,
            modelName = modelName,
            apiUrl = apiUrl,
            requestBodyJson = sanitizeRequestBodyForDisplay(requestBodyJson),
            systemPrompt = systemPrompt,
            ragChunks = ragChunks,
            estimatedPromptTokens = estimateTokens(sanitizeRequestBodyForDisplay(requestBodyJson))
        )
        activeLogs[sessionId] = entry.id
        synchronized(this) {
            _logs.value = _logs.value + entry
        }
        return entry.id
    }

    fun appendResponseChunk(
        sessionId: String,
        chunkData: String,
        deltaText: String? = null,
        reasoningText: String? = null
    ) {
        val logId = activeLogs[sessionId] ?: return
        synchronized(this) {
            _logs.value = _logs.value.map { entry ->
                if (entry.id == logId) {
                    entry.rawSseOutput.append(chunkData).append("\n")
                    if (deltaText != null) entry.rawAiOutput.append(deltaText)
                    if (reasoningText != null) entry.rawReasoningOutput.append(reasoningText)
                    entry.estimatedCompletionTokens = estimateTokens(entry.rawAiOutput.toString())
                    entry
                } else {
                    entry
                }
            }
        }
    }

    fun completeRequest(sessionId: String) {
        val logId = activeLogs[sessionId] ?: return
        synchronized(this) {
            _logs.value = _logs.value.map { entry ->
                if (entry.id == logId) {
                    // Force a copy to notify StateFlow of change
                    val newBuilder = StringBuilder(entry.rawSseOutput.toString())
                    val newAi = StringBuilder(entry.rawAiOutput.toString())
                    val newReasoning = StringBuilder(entry.rawReasoningOutput.toString())
                    entry.copy(
                        rawSseOutput = newBuilder,
                        rawAiOutput = newAi,
                        rawReasoningOutput = newReasoning,
                        isCompleted = true
                    )
                } else {
                    entry
                }
            }
        }
        activeLogs.remove(sessionId)
    }

    fun logError(sessionId: String, errorMsg: String) {
        val logId = activeLogs[sessionId] ?: return
        synchronized(this) {
            _logs.value = _logs.value.map { entry ->
                if (entry.id == logId) {
                    entry.rawSseOutput.append("[ERROR]: ").append(errorMsg).append("\n")
                    val newBuilder = StringBuilder(entry.rawSseOutput.toString())
                    val newAi = StringBuilder(entry.rawAiOutput.toString())
                    val newReasoning = StringBuilder(entry.rawReasoningOutput.toString())
                    entry.copy(
                        rawSseOutput = newBuilder,
                        rawAiOutput = newAi,
                        rawReasoningOutput = newReasoning,
                        error = errorMsg,
                        isCompleted = true
                    )
                } else {
                    entry
                }
            }
        }
        activeLogs.remove(sessionId)
    }

    fun clearLogs(sessionId: String) {
        synchronized(this) {
            _logs.value = _logs.value.filter { it.sessionId != sessionId }
        }
        activeLogs.remove(sessionId)
    }

    fun recordCompleted(
        sessionId: String,
        modelName: String,
        apiUrl: String,
        requestBodyJson: String,
        rawAiOutput: String = ""
    ) {
        val entry = DebugLogEntry(
            sessionId = sessionId,
            modelName = modelName,
            apiUrl = apiUrl,
            requestBodyJson = sanitizeRequestBodyForDisplay(requestBodyJson),
            rawAiOutput = StringBuilder(rawAiOutput),
            estimatedPromptTokens = estimateTokens(requestBodyJson),
            estimatedCompletionTokens = estimateTokens(rawAiOutput),
            isCompleted = true
        )
        synchronized(this) { _logs.value = _logs.value + entry }
    }

    private fun estimateTokens(text: String): Int {
        var count = 0
        for (char in text) {
            if (char.code in 0x4E00..0x9FA5) {
                count += 2
            } else {
                count += 1
            }
        }
        return (count * 0.4).toInt().coerceAtLeast(1)
    }

    private fun sanitizeRequestBodyForDisplay(json: String): String {
        return Regex("data:image/[^;]+;base64,[A-Za-z0-9+/=]+").replace(json) { match ->
            val length = match.value.length
            "data:image/*;base64,<omitted ${length} chars>"
        }
    }
}
