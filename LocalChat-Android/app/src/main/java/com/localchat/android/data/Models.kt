package com.localchat.android.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ApiFormat { OPENAI, ANTHROPIC, OLLAMA }

object ApiFormatNames {
    /** 顺序必须与 [ApiFormat] 枚举一致,设置页按下标选择。 */
    val all = listOf(
        "OpenAI 兼容 (/v1/chat/completions)",
        "Anthropic Messages (/v1/messages)",
        "Ollama 原生 (/api/chat)"
    )
}

@Serializable
data class ModelConfig(
    var id: String = "",
    var contextWindow: Int = 8192,
    var maxOutputTokens: Int = 2048,
    var temperature: Double = 0.7,
    var topP: Double = 0.9,
    var systemPrompt: String = "",
    var enabled: Boolean = true
)

@Serializable
data class ProviderConfig(
    var id: String = newId(),
    var name: String = "新服务商",
    var enabled: Boolean = true,
    var baseUrl: String = "http://localhost:11434",
    var format: ApiFormat = ApiFormat.OLLAMA,
    var apiKey: String = "",
    var models: MutableList<ModelConfig> = mutableListOf(),
    var autoImported: Boolean = false
)

/** role: user | assistant | compact(压缩标记) */
@Serializable
data class ChatMessageData(
    var role: String = "user",
    var content: String = "",
    var reasoning: String = "",
    var time: Long = System.currentTimeMillis(),
    var isError: Boolean = false,
    var compactedCount: Int = 0,
    var reasonSeconds: Double = 0.0
)

@Serializable
data class ConversationData(
    var id: String = newId(),
    var title: String = "新对话",
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var providerId: String? = null,
    var modelId: String? = null,
    var messages: MutableList<ChatMessageData> = mutableListOf()
)

@Serializable
data class AppSettings(
    var providers: MutableList<ProviderConfig> = mutableListOf(),
    var conversations: MutableList<ConversationData> = mutableListOf(),
    var currentProviderId: String? = null,
    var currentModelId: String? = null,
    var thinkingEnabled: Boolean = false,
    var uiFontSize: Double = Defaults.UiFontSize,
    var chatFontSize: Double = Defaults.ChatFontSize
)

private fun newId(): String = UUID.randomUUID().toString().replace("-", "")

object Defaults {
    /** 与桌面版一致的字号基准与默认值。 */
    const val UiFontSize = 13.0
    const val ChatFontSize = 13.5
    fun create() = AppSettings(
        providers = mutableListOf(
            ProviderConfig(
                name = "本地 Ollama",
                baseUrl = "http://localhost:11434",
                format = ApiFormat.OLLAMA,
                models = mutableListOf(
                    ModelConfig(id = "qwen3.5:4b", contextWindow = 8192, maxOutputTokens = 2000)
                )
            )
        )
    )
}

fun ModelConfig.copyTo(dst: ModelConfig) {
    dst.id = id
    dst.contextWindow = contextWindow
    dst.maxOutputTokens = maxOutputTokens
    dst.temperature = temperature
    dst.topP = topP
    dst.systemPrompt = systemPrompt
    dst.enabled = enabled
}
