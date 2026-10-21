package com.localchat.android.network

import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Request

/** Ollama 原生协议策略(/api/chat,NDJSON 流)。 */
object OllamaHandler : ApiHandler {
    override val format = ApiFormat.OLLAMA

    override fun chatEndpoint(provider: ProviderConfig): String {
        val base = trimBase(provider)
        return (if (base.endsWith("/v1", ignoreCase = true)) base.dropLast(3) else base) + "/api/chat"
    }

    override fun modelsEndpoint(provider: ProviderConfig) = trimBase(provider) + "/api/tags"

    override fun applyHeaders(builder: Request.Builder, provider: ProviderConfig) {
        if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${provider.apiKey}")
    }

    override fun buildChatBody(
        model: ModelConfig,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        think: Boolean,
        includeThinkingField: Boolean,
        includeReasoningField: Boolean
    ): JsonObject = buildJsonObject {
        put("model", model.id)
        put("stream", true)
        put("messages", buildJsonArray {
            if (!systemPrompt.isNullOrBlank()) {
                add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
            }
            turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.content) }) }
        })
        put("think", think)
        put("options", buildJsonObject {
            put("num_ctx", maxOf(512, model.contextWindow))
            put("num_predict", maxOf(16, model.maxOutputTokens))
            put("temperature", model.temperature.coerceIn(0.0, 2.0))
            put("top_p", model.topP.coerceIn(0.01, 1.0))
        })
    }

    override fun handleChunk(
        root: JsonObject,
        onContent: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Boolean {
        (root["error"] as? JsonPrimitive)?.let { err ->
            throw LlmException(err.contentOrNull ?: "Ollama 错误")
        }
        root.optObj("message")?.let { msg ->
            msg.optString("thinking")?.let(onReasoning)
            msg.optString("content")?.takeIf { it.isNotEmpty() }?.let(onContent)
        }
        return (root["done"] as? JsonPrimitive)?.booleanOrNull == true
    }
}
