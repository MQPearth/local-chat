package com.localchat.android.network

import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

class LlmException(message: String) : Exception(message)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
}

data class ChatTurn(val role: String, val content: String)

/**
 * 策略接口:三种 API 协议各自实现端点、鉴权头、请求体构造与流式分片解析。
 * 具体策略由 [ApiHandlers.of] 工厂按 [ApiFormat] 创建。
 */
interface ApiHandler {
    val format: ApiFormat
    fun chatEndpoint(provider: ProviderConfig): String
    fun modelsEndpoint(provider: ProviderConfig): String
    fun applyHeaders(builder: Request.Builder, provider: ProviderConfig)

    fun buildChatBody(
        model: ModelConfig,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        think: Boolean,
        includeThinkingField: Boolean = true,
        includeReasoningField: Boolean = true
    ): JsonObject

    /** 解析一条流式分片;返回 true 表示本回复结束。协议层错误抛 [LlmException]。 */
    fun handleChunk(
        root: JsonObject,
        onContent: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Boolean
}

object ApiHandlers {
    fun of(format: ApiFormat): ApiHandler = when (format) {
        ApiFormat.OPENAI -> OpenAiHandler
        ApiFormat.ANTHROPIC -> AnthropicHandler
        ApiFormat.OLLAMA -> OllamaHandler
    }
}

fun trimBase(provider: ProviderConfig): String = provider.baseUrl.trim().trimEnd('/')

fun ensureV1(base: String): String = if (base.endsWith("/v1", ignoreCase = true)) base else "$base/v1"

fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.optObj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.optArr(key: String): JsonArray? = this[key] as? JsonArray
