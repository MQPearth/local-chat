package com.localchat.android.network

import com.localchat.android.data.ApiFormat
import com.localchat.android.data.HttpExchange
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object LlmClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    /**
     * 流式对话。网络与解析在 IO 线程执行;collect 侧取消即中断底层请求。
     * log 不为 null 时记录原始 HTTP 请求/响应。
     */
    fun stream(
        provider: ProviderConfig,
        model: ModelConfig,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        think: Boolean,
        log: MutableList<HttpExchange>? = null
    ): Flow<StreamEvent> = flow {
        val handler = ApiHandlers.of(provider.format)
        var includeThinking = true
        var includeReasoning = true
        var body = handler.buildChatBody(model, turns, systemPrompt, think, includeThinking, includeReasoning)
        var ex = newExchange(handler, provider, body)
        log?.add(ex)
        var attempts = 0
        while (true) {
            val request = buildRequest(handler, provider, body, ex)
            val call = http.newCall(request)
            val resp: Response = try {
                // enqueue + 可取消续体:collect 侧取消时立即 cancel() 中断底层连接
                suspendCancellableCoroutine { cont ->
                    call.enqueue(object : Callback {
                        override fun onResponse(call: Call, response: Response) {
                            // resume 与取消竞态时回调 onCancellation,关闭已打开的响应避免泄漏
                            if (cont.isActive) cont.resume(response) { response.close() }
                        }
                        override fun onFailure(call: Call, e: IOException) {
                            if (!cont.isCancelled) cont.resumeWithException(e)
                        }
                    })
                    cont.invokeOnCancellation { call.cancel() }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // 请求被取消时让取消异常正常传播
                ex.error = e.message
                ex.markEnded()
                throw IOException(e.message ?: "网络请求失败", e)
            }
            var completed = false
            try {
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    recordResponseStart(ex, resp)
                    ex.appendBodyLine(err)
                    // OpenAI 兼容服务对参数支持差异大:400 时按报错逐步去掉 reasoning_effort / thinking 重试(最多两次)
                    var retry = false
                    if (provider.format == ApiFormat.OPENAI && resp.code == 400 && attempts < 2) {
                        if (think && includeReasoning && err.contains("reasoning", ignoreCase = true)) {
                            includeReasoning = false; retry = true
                        } else if (includeThinking && (err.contains("thinking", ignoreCase = true) ||
                                err.contains("unrecognized", ignoreCase = true))
                        ) {
                            includeThinking = false; retry = true
                        }
                    }
                    if (retry) {
                        attempts++
                        body = handler.buildChatBody(model, turns, systemPrompt, think, includeThinking, includeReasoning)
                        ex = newExchange(handler, provider, body)
                        log?.add(ex)
                    } else {
                        throw IOException("HTTP ${resp.code}: ${snippet(err)}")
                    }
                } else {
                    recordResponseStart(ex, resp)
                    val source = resp.body?.source() ?: throw IOException("空响应体")
                    var done = false
                    while (!done) {
                        val line = source.readUtf8Line() ?: break
                        currentCoroutineContext().ensureActive()
                        ex.appendBodyLine(line)
                        var payload: String? = null
                        if (line.startsWith("data:")) {
                            payload = line.substring(5).trim()
                            if (payload == "[DONE]") break
                            if (payload.isEmpty()) continue
                        } else if (line.startsWith("{")) {
                            payload = line // Ollama 原生接口为 NDJSON
                        } else continue
                        val root = try {
                            json.parseToJsonElement(payload) as? JsonObject
                        } catch (_: Exception) {
                            null
                        } ?: continue
                        val events = mutableListOf<StreamEvent>()
                        done = handler.handleChunk(
                            root,
                            { events.add(StreamEvent.Content(it)) },
                            { events.add(StreamEvent.Reasoning(it)) }
                        )
                        events.forEach { emit(it) }
                    }
                    completed = true
                    ex.markEnded()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                ex.error = e.message
                ex.markEnded()
                throw e
            } finally {
                resp.close()
            }
            if (completed) break
        }
    }.flowOn(Dispatchers.IO)

    /** 拉取服务端模型 ID 列表。 */
    suspend fun getModelIds(provider: ProviderConfig): List<String> {
        val handler = ApiHandlers.of(provider.format)
        val builder = Request.Builder().url(handler.modelsEndpoint(provider)).get()
        handler.applyHeaders(builder, provider)
        return withContext(Dispatchers.IO) {
            val call = http.newCall(builder.build())
            call.timeout().timeout(8, TimeUnit.SECONDS)
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${snippet(text)}")
                val root = json.parseToJsonElement(text) as? JsonObject
                val list = mutableListOf<String>()
                (root?.optArr("models") ?: root?.optArr("data"))?.forEach { e ->
                    val obj = e as? JsonObject ?: return@forEach
                    (obj.optString("name") ?: obj.optString("id"))?.let { list.add(it) }
                }
                list
            }
        }
    }

    /** Ollama:查询模型上下文长度。 */
    suspend fun fetchContextLength(provider: ProviderConfig, modelId: String): Long? {
        if (provider.format != ApiFormat.OLLAMA) return null
        val payload = buildJsonObject { put("model", modelId) }.toString()
        val builder = Request.Builder()
            .url(trimBase(provider) + "/api/show")
            .post(payload.toRequestBody(jsonMedia))
        OllamaHandler.applyHeaders(builder, provider)
        return withContext(Dispatchers.IO) {
            val call = http.newCall(builder.build())
            call.timeout().timeout(10, TimeUnit.SECONDS)
            call.execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${snippet(text)}")
                val info = (json.parseToJsonElement(text) as? JsonObject)?.optObj("model_info")
                    ?: return@use null
                val key = info.keys.firstOrNull { it.endsWith("context_length", ignoreCase = true) }
                    ?: return@use null
                val value = (info[key] as? JsonPrimitive)?.contentOrNull ?: return@use null
                value.toLongOrNull()
            }
        }
    }

    /** 测试连通性,返回结果描述。 */
    suspend fun test(provider: ProviderConfig, model: ModelConfig): String {
        val started = System.currentTimeMillis()
        return try {
            if (provider.format == ApiFormat.OLLAMA) {
                val ids = getModelIds(provider)
                val ms = System.currentTimeMillis() - started
                val has = ids.contains(model.id)
                "✓ 连接成功($ms ms),服务端共 ${ids.size} 个模型" + (if (has) "" else ";未找到 “${model.id}”")
            } else {
                val body = buildJsonObject {
                    put("model", model.id)
                    put("messages", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject { put("role", "user"); put("content", "hi") })
                    })
                    put("max_tokens", 1)
                }.toString()
                val url = if (provider.format == ApiFormat.OPENAI) {
                    ensureV1(trimBase(provider)) + "/chat/completions"
                } else {
                    ensureV1(trimBase(provider)) + "/messages"
                }
                val builder = Request.Builder().url(url).post(body.toRequestBody(jsonMedia))
                ApiHandlers.of(provider.format).applyHeaders(builder, provider)
                withContext(Dispatchers.IO) {
                    val call = http.newCall(builder.build())
                    call.timeout().timeout(30, TimeUnit.SECONDS)
                    call.execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        val ms = System.currentTimeMillis() - started
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${snippet(text)}")
                        "✓ 连接成功($ms ms)"
                    }
                }
            }
        } catch (e: Exception) {
            "✗ 连接失败:${e.message}"
        }
    }

    private fun newExchange(handler: ApiHandler, provider: ProviderConfig, body: JsonObject): HttpExchange {
        val ex = HttpExchange()
        ex.url = handler.chatEndpoint(provider)
        ex.requestBody = body.toString()
        return ex
    }

    private fun buildRequest(
        handler: ApiHandler,
        provider: ProviderConfig,
        body: JsonObject,
        ex: HttpExchange
    ): Request {
        val payload = body.toString()
        val builder = Request.Builder()
            .url(ex.url)
            .post(payload.toRequestBody(jsonMedia))
        handler.applyHeaders(builder, provider)
        ex.requestHeaders.clear()
        builder.build().headers.forEach { (k, v) ->
            ex.requestHeaders.merge(k, v) { a, b -> "$a, $b" }
        }
        ex.requestBody = payload
        return builder.build()
    }

    private fun recordResponseStart(ex: HttpExchange, r: Response) {
        if (ex.statusCode != null) return
        ex.statusCode = r.code
        ex.reasonPhrase = r.message
        r.headers.forEach { (k, v) ->
            ex.responseHeaders.merge(k, v) { a, b -> "$a, $b" }
        }
    }

    private fun snippet(s: String): String {
        val t = s.trim()
        return if (t.length > 300) t.take(300) + "…" else t
    }
}
