package com.localchat.android.network

import com.localchat.android.data.ApiFormat
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

/** OpenAI 兼容协议策略(/v1/chat/completions)。 */
object OpenAiHandler : ApiHandler {
    override val format = ApiFormat.OPENAI

    override fun chatEndpoint(provider: ProviderConfig) = ensureV1(trimBase(provider)) + "/chat/completions"

    override fun modelsEndpoint(provider: ProviderConfig) = ensureV1(trimBase(provider)) + "/models"

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
    ): JsonObject {
        val temp = model.temperature.coerceIn(0.0, 2.0)
        return buildJsonObject {
            put("model", model.id)
            put("stream", true)
            put("messages", buildJsonArray {
                if (!systemPrompt.isNullOrBlank()) {
                    add(buildJsonObject { put("role", "system"); put("content", systemPrompt) })
                }
                turns.forEach { t -> add(buildJsonObject { put("role", t.role); put("content", t.content) }) }
            })
            put("max_tokens", maxOf(16, model.maxOutputTokens))
            if (temp > 0) put("temperature", temp)
            // 显式传递思考开关(GLM/OpenRouter 等兼容网关的统一写法)
            if (includeThinkingField) {
                put("thinking", buildJsonObject { put("type", if (think) "enabled" else "disabled") })
            }
            if (think && includeReasoningField) put("reasoning_effort", "medium")
        }
    }

    override fun handleChunk(
        root: JsonObject,
        onContent: (String) -> Unit,
        onReasoning: (String) -> Unit
    ): Boolean {
        root["error"]?.let { err ->
            throw LlmException((err as? JsonObject)?.optString("message") ?: err.toString())
        }
        val choices = root.optArr("choices") ?: return false
        if (choices.isEmpty()) return false
        val c0 = choices[0] as? JsonObject ?: return false
        val delta = c0.optObj("delta")
        if (delta != null) {
            emitText(delta, onContent, onReasoning)
        } else {
            // 服务端忽略了 stream 时的整段返回
            val msg = c0.optObj("message")
            if (msg != null) {
                emitText(msg, onContent, onReasoning)
                return true
            }
        }
        val fr = c0["finish_reason"]
        return fr is kotlinx.serialization.json.JsonPrimitive && fr.isString
    }

    private fun emitText(e: JsonObject, onContent: (String) -> Unit, onReasoning: (String) -> Unit) {
        (e.optString("reasoning_content") ?: e.optString("reasoning"))?.let(onReasoning)
        e.optString("content")?.takeIf { it.isNotEmpty() }?.let(onContent)
    }
}
