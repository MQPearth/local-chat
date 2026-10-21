package com.localchat.android.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次 LLM API 调用的原始 HTTP 记录;含失败重试时一次回复会有多条。 */
class HttpExchange {
    val startedAt = System.currentTimeMillis()
    var endedAt: Long? = null
    var method = "POST"
    var url = ""
    val requestHeaders = LinkedHashMap<String, String>()
    var requestBody = ""
    var statusCode: Int? = null
    var reasonPhrase: String? = null
    val responseHeaders = LinkedHashMap<String, String>()
    val responseBody = StringBuilder()
    var error: String? = null

    fun appendBodyLine(line: String) {
        responseBody.append(line).append('\n')
    }

    fun markEnded() {
        if (endedAt == null) endedAt = System.currentTimeMillis()
    }
}

/** 把 HTTP 记录格式化为可读文本,供「导出 HTTP 数据」使用。 */
object HttpLogFormatter {
    fun build(title: String, model: String, list: List<HttpExchange>): String {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("对话: $title")
        sb.appendLine("模型: $model")
        sb.appendLine("导出时间: ${ts.format(Date())}")
        sb.appendLine("HTTP 请求: ${list.size} 次(响应体为原始流式数据;请求头可能包含 API Key,分享前请注意脱敏)")
        list.forEachIndexed { i, e ->
            sb.appendLine()
            sb.appendLine("═".repeat(56))
            val dur = e.endedAt?.let { "  ·  ${it - e.startedAt} ms" } ?: ""
            sb.appendLine("#${i + 1}  ${ts.format(Date(e.startedAt))}$dur")
            sb.appendLine("═".repeat(56))
            sb.appendLine()
            sb.appendLine("► ${e.method} ${e.url}")
            e.requestHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine()
            sb.appendLine(e.requestBody)
            sb.appendLine()
            val code = e.statusCode
            if (code != null) {
                sb.appendLine("◄ $code ${e.reasonPhrase ?: ""}")
                e.responseHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
                sb.appendLine()
                val body = e.responseBody.toString()
                sb.appendLine(body)
                if (body.isNotEmpty() && !body.endsWith("\n")) sb.appendLine()
            }
            e.error?.let { sb.appendLine("!! 请求失败: $it") }
        }
        return sb.toString()
    }
}
