package com.localchat.android.network

import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

/** Anthropic Messages 协议策略(/v1/messages)。 */
object AnthropicHandler : ApiHandler {
    override val format = ApiFormat.ANTHROPIC

    override fun chatEndpoint(provider: ProviderConfig) = ensureV1(trimBase(provider)) + "/messages"

    override fun modelsEndpoint(provider: ProviderConfig) = ensureV1(trimBase(provider)) + "/models"

    override fun applyHeaders(builder: Request.Builder, provider: ProviderConfig) {
        if (provider.apiKey.isNotBlank()) {
            builder.header("x-api-key", provider.apiKey)
            builder.header("anthropic-version", "2023-06-01")
        }
    }

    override fun buildChatBody(
        model: ModelConfig,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        think: Boolean,
        includeThinkingField: Boolean,
        includeReasoningField: Boolean
    ): JsonObject {
        var maxTok = maxOf(256, model.maxOutputTokens)
        return buildJsonObject {
            if (!systemPrompt.isNullOrBlank()) put("system", systemPrompt)
            put("messages", buildJsonArray {
                turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.content) }) }
            })
            if (think) {
                val budget = (maxTok / 2).coerceIn(1024, 32768)
                if (maxTok <= budget) maxTok += budget
                put("max_tokens", maxTok)
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
            } else {
                put("max_tokens", maxTok)
                // 混合思考模型(如 qwen3.5)默认开启思考,须显式关闭;
                // 个别网关对该参数会返回空响应,由 LlmClient 的零输出重试兜底。
                put("thinking", buildJsonObject { put("type", "disabled") })
                val temp = model.temperature.coerceIn(0.0, 2.0)
                if (temp > 0) put("temperature", temp)
            }
        }
    }

    override fun handleChunk(
        root: JsonObject,
        onContent: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Boolean {
        val type = root.optString("type") ?: return false
        when (type) {
            "content_block_delta" -> root.optObj("delta")?.let { d ->
                when (d.optString("type")) {
                    "text_delta" -> d.optString("text")?.let(onContent)
                    "thinking_delta" -> d.optString("thinking")?.let(onReasoning)
                }
            }
            "message_stop" -> return true
            "message_delta" -> if (root.optObj("delta")?.optString("stop_reason") != null) return true
            "error" -> throw LlmException(root.optObj("error")?.optString("message") ?: "Anthropic API 错误")
        }
        return false
    }
}
