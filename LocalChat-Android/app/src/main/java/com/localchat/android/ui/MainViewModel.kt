package com.localchat.android.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localchat.android.data.ApiFormat
import com.localchat.android.data.AppSettings
import com.localchat.android.data.ChatMessageData
import com.localchat.android.data.ConversationData
import com.localchat.android.data.HttpExchange
import com.localchat.android.data.HttpLogFormatter
import com.localchat.android.data.ModelConfig
import com.localchat.android.data.ProviderConfig
import com.localchat.android.data.SettingsStore
import com.localchat.android.data.TokenEstimator
import com.localchat.android.network.ChatTurn
import com.localchat.android.network.LlmClient
import com.localchat.android.network.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 消息 UI 状态:revision 为快照状态,内容变化时递增触发组合刷新;
 * 真实数据仍保存在持久化的 [data] 中。
 */
class MessageUi(val data: ChatMessageData, initialStreaming: Boolean = false) {
    var revision by mutableIntStateOf(0)
    var streaming by mutableStateOf(initialStreaming)
    var thinkingOpen by mutableStateOf(false)
    var thinkingTouched by mutableStateOf(false) // 用户手动操作过思考块后,不再自动展开
    internal var reasonStart: Long? = null
    internal var reasonEnd: Long? = null
    internal var contentStarted = false

    /** 本条回复产生的原始 HTTP 记录(含失败重试,会有多条)。 */
    val httpLog = mutableListOf<HttpExchange>()

    val role: String get() = data.role
    val content: String get() { revision; return data.content }
    val reasoning: String get() { revision; return data.reasoning }
    val isError: Boolean get() = data.isError
    val showWaiting: Boolean get() = streaming && data.content.isEmpty() && data.reasoning.isEmpty()
    val compactHeaderText: String
        get() = if (data.role == "compact") "已压缩 ${data.compactedCount} 条历史消息,点击查看摘要" else ""

    val thinkingLabel: String
        get() {
            revision
            if (data.reasoning.isEmpty()) return ""
            if (!contentStarted) {
                // 重启后的历史消息:展示持久化的思考耗时
                if (!streaming) {
                    return if (data.reasonSeconds > 0) "已深度思考 (用时 ${fmtSeconds(data.reasonSeconds)} 秒)" else "已深度思考"
                }
                val sec = reasonStart?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0
                return "思考中… ${sec.toInt()} 秒"
            }
            val dur = if (reasonStart != null && reasonEnd != null) {
                (reasonEnd!! - reasonStart!!) / 1000.0
            } else {
                data.reasonSeconds // 中断兜底:用已持久化的耗时
            }
            return if (dur > 0) "已深度思考 (用时 ${fmtSeconds(dur)} 秒)" else "已深度思考"
        }

    fun appendReasoning(piece: String) {
        if (piece.isEmpty()) return
        if (reasonStart == null) {
            reasonStart = System.currentTimeMillis()
            if (!thinkingTouched) thinkingOpen = true // 思考中自动展开,直播推理过程
        }
        data.reasoning += piece
        revision++
    }

    fun appendContent(piece: String) {
        if (piece.isEmpty()) return
        if (!contentStarted) {
            contentStarted = true
            reasonEnd = System.currentTimeMillis()
            reasonStart?.let { data.reasonSeconds = (System.currentTimeMillis() - it) / 1000.0 } // 持久化思考耗时
            thinkingOpen = false // 思考结束,自动折叠思考块
        }
        data.content += piece
        revision++
    }

    fun finish(cancelled: Boolean = false) {
        if (cancelled) {
            appendRaw(if (data.content.isEmpty() && data.reasoning.isEmpty()) "(已停止)" else "\n\n(已停止)")
        }
        endStream()
    }

    fun fail(msg: String) {
        if (data.content.isEmpty() && data.reasoning.isEmpty()) {
            data.isError = true
            appendRaw("[请求失败] $msg")
        } else {
            appendRaw("\n\n⚠ 生成中断:$msg")
        }
        endStream()
    }

    private fun endStream() {
        // 持久化思考耗时,重启后仍可显示
        if (data.reasonSeconds <= 0) {
            reasonStart?.let { data.reasonSeconds = ((reasonEnd ?: System.currentTimeMillis()) - it) / 1000.0 }
        }
        streaming = false
        revision++
    }

    private fun appendRaw(s: String) {
        data.content += s
        revision++
    }

    private fun fmtSeconds(d: Double): String =
        "%.1f".format(d).trimEnd('0').trimEnd('.')
}

class ConversationUi(val data: ConversationData) {
    var revision by mutableIntStateOf(0)
    val messages = mutableStateListOf<MessageUi>()

    init {
        // 跳过中断留下的空助手消息,避免渲染空气泡
        data.messages
            .filter { !(it.role == "assistant" && it.content.isEmpty() && it.reasoning.isEmpty()) }
            .forEach { messages.add(MessageUi(it)) }
    }

    val title: String get() { revision; return data.title }
    val updatedAt: Long get() { revision; return data.updatedAt }

    fun setTitle(v: String) {
        data.title = v
        revision++
    }

    val messageCountText: String
        get() {
            revision
            return if (messages.isEmpty()) "" else "${messages.size} 条消息"
        }

    /** 按实际发送范围(最近 30 条非错误消息)粗略估算的上下文 token 用量。 */
    val estimatedTokens: Int
        get() {
            revision
            messages.forEach { it.revision }
            val texts = data.messages
                .filter { !it.isError && it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
                .map { it.content }
            val window = if (texts.size > 30) texts.takeLast(30) else texts
            return TokenEstimator.estimate(window)
        }

    fun addUser(text: String): MessageUi {
        val d = ChatMessageData(role = "user", content = text)
        data.messages.add(d)
        val vm = MessageUi(d)
        messages.add(vm)
        revision++
        return vm
    }

    fun addAssistant(): MessageUi {
        val d = ChatMessageData(role = "assistant")
        data.messages.add(d)
        val vm = MessageUi(d, initialStreaming = true)
        messages.add(vm)
        revision++
        return vm
    }

    /** 插入压缩标记:旧消息保留,标记只作为后续请求的上下文边界。 */
    fun addCompact(summary: String, count: Int): MessageUi {
        val d = ChatMessageData(role = "compact", content = summary, compactedCount = count)
        data.messages.add(d)
        val vm = MessageUi(d)
        messages.add(vm)
        revision++
        return vm
    }

    /** 最后一个压缩标记之后的消息条数,作为下次压缩的覆盖计数。 */
    fun countAfterLastCompact(): Int {
        val msgs = data.messages
        var start = 0
        for (i in msgs.indices.reversed()) {
            if (msgs[i].role == "compact") { start = i + 1; break }
        }
        return msgs.size - start
    }

    /**
     * 按实际发送范围构建对话轮次:只取最后一个压缩标记之后的消息;
     * 若存在标记,把摘要内容作为首条 user 消息插入。
     */
    fun buildTurns(maxTurns: Int = 30): List<ChatTurn> {
        val msgs = data.messages
        var start = 0
        for (i in msgs.indices.reversed()) {
            if (msgs[i].role == "compact") { start = i + 1; break }
        }
        var turns: List<ChatTurn> = msgs.drop(start)
            .filter { !it.isError && it.content.isNotBlank() && (it.role == "user" || it.role == "assistant") }
            .map { ChatTurn(it.role, it.content) }
        if (turns.size > maxTurns) turns = turns.takeLast(maxTurns)
        for (i in start - 1 downTo 0) {
            if (msgs[i].role == "compact") {
                turns = listOf(ChatTurn("user", "[上下文摘要]\n" + msgs[i].content)) + turns
                break
            }
        }
        return turns
    }

    /** 汇总本对话全部 HTTP 记录(按消息顺序)。 */
    fun collectHttp(): List<HttpExchange> = messages.flatMap { it.httpLog }

    fun refresh() {
        revision++
    }
}

/** 一次可选用的模型 = 服务商 + 模型配置。 */
class ModelOption(val provider: ProviderConfig, val model: ModelConfig) {
    override fun toString() = "${provider.name} · ${model.id}"
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    val settings: AppSettings = store.load()

    val conversations = mutableStateListOf<ConversationUi>()
    var selected by mutableStateOf<ConversationUi?>(null)
        private set
    val modelOptions = mutableStateListOf<ModelOption>()
    var selectedModel by mutableStateOf<ModelOption?>(null)
    var input by mutableStateOf("")
    var thinkingEnabled by mutableStateOf(false)
        private set
    var isStreaming by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var streamJob: Job? = null

    init {
        thinkingEnabled = settings.thinkingEnabled
        settings.conversations.sortedByDescending { it.updatedAt }.forEach { conversations.add(ConversationUi(it)) }
        selected = conversations.firstOrNull()
        rebuildModelOptions()
    }

    fun clearNotice() { notice = null }
    private fun notify(msg: String) { notice = msg }
    fun save() { store.save(settings) }

    fun setThinking(v: Boolean) {
        thinkingEnabled = v
        settings.thinkingEnabled = v
        save()
    }

    /** 启动后尝试从本地 Ollama 自动导入模型列表(仅第一次)。 */
    fun initialize() {
        viewModelScope.launch {
            try {
                var changed = false
                for (p in settings.providers
                    .filter { it.enabled && it.format == ApiFormat.OLLAMA && !it.autoImported }) {
                    val ids = try { LlmClient.getModelIds(p) } catch (_: Exception) { continue }
                    if (ids.isEmpty()) continue
                    val existing = p.models.map { it.id }.toHashSet()
                    val news = mutableListOf<ModelConfig>()
                    for (id in ids) {
                        val keep = p.models.firstOrNull { it.id == id }
                        if (keep != null) { news.add(keep); continue }
                        val mc = ModelConfig(id = id)
                        try {
                            val ctx = LlmClient.fetchContextLength(p, id)
                            if (ctx != null && ctx > 0) {
                                mc.contextWindow = ctx.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                mc.maxOutputTokens = (mc.contextWindow / 4).coerceIn(256, 8192)
                            }
                        } catch (_: Exception) { }
                        news.add(mc)
                    }
                    p.models = news
                    p.autoImported = true
                    changed = true
                }
                if (changed) {
                    save()
                    rebuildModelOptions()
                }
            } catch (_: Exception) { }
        }
    }

    fun newChat() {
        val c = ConversationData()
        settings.conversations.add(c)
        val vm = ConversationUi(c)
        conversations.add(0, vm)
        selected = vm // 直接赋值避免触发模型恢复
        save()
    }

    /** 切换会话,并恢复该会话上次使用的模型。 */
    fun select(conv: ConversationUi?) {
        selected = conv
        conv?.let { restoreModelFor(it) }
    }

    fun selectModel(option: ModelOption?) {
        selectedModel = option
        settings.currentProviderId = option?.provider?.id
        settings.currentModelId = option?.model?.id
        selected?.let {
            it.data.providerId = option?.provider?.id
            it.data.modelId = option?.model?.id
        }
        save()
    }

    fun send() {
        if (isStreaming) return
        val text = input.trim()
        if (text.isEmpty()) return
        val opt = selectedModel
        if (opt == null) {
            notify("请先点击右上角齿轮打开「模型服务设置」,添加并启用至少一个模型。")
            return
        }
        if (selected == null) newChat()
        val conv = selected ?: return
        if (conv.messages.isEmpty()) {
            conv.setTitle(if (text.length > 18) text.take(18) + "…" else text)
        }
        conv.addUser(text)
        input = ""
        val reply = conv.addAssistant()
        isStreaming = true
        streamJob = viewModelScope.launch {
            try {
                LlmClient.stream(
                    opt.provider, opt.model, conv.buildTurns(),
                    opt.model.systemPrompt, thinkingEnabled, reply.httpLog
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> reply.appendContent(event.text)
                        is StreamEvent.Reasoning -> reply.appendReasoning(event.text)
                    }
                }
                reply.finish()
            } catch (e: CancellationException) {
                reply.finish(cancelled = true)
            } catch (e: Exception) {
                reply.fail(e.message ?: e.javaClass.simpleName)
            } finally {
                isStreaming = false
                conv.data.updatedAt = System.currentTimeMillis()
                conv.data.providerId = opt.provider.id
                conv.data.modelId = opt.model.id
                conv.refresh()
                save()
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
    }

    /** 用当前模型把对话历史压缩成一条摘要,原消息保留,标记之后的消息作为新上下文。 */
    fun compact() {
        if (isStreaming) return
        val conv = selected ?: return
        val opt = selectedModel ?: return
        if (conv.messages.isEmpty()) return
        val count = conv.countAfterLastCompact()
        val turns = conv.buildTurns(maxTurns = Int.MAX_VALUE).toMutableList()
        if (turns.isEmpty()) return
        // 摘要指令作为最后的用户消息发送,不走顶层 system 字段
        // (部分 Anthropic 协议网关带 system 时会返回空响应)
        turns.add(ChatTurn("user", CompactPrompt))
        isStreaming = true
        streamJob = viewModelScope.launch {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val httpLog = mutableListOf<HttpExchange>()
            try {
                // 先按关闭思考请求;拿不到内容再开启思考重试(部分思考型模型在禁用状态下不输出正文)
                requestSummary(opt, turns, content, reasoning, httpLog, think = false)
                var summary = extractSummary(content, reasoning)
                if (summary.isEmpty()) {
                    content.clear()
                    reasoning.clear()
                    requestSummary(opt, turns, content, reasoning, httpLog, think = true)
                    summary = extractSummary(content, reasoning)
                }
                if (summary.isEmpty()) {
                    var raw = httpLog.lastOrNull()?.responseBody?.toString()?.trim() ?: ""
                    if (raw.length > 600) raw = raw.take(600) + "…"
                    throw IllegalStateException(
                        if (raw.isBlank()) "模型未返回任何内容" else "模型未返回摘要,服务端原始响应:\n$raw"
                    )
                }
                conv.addCompact(summary, count)
                conv.data.updatedAt = System.currentTimeMillis()
                conv.data.providerId = opt.provider.id
                conv.data.modelId = opt.model.id
                conv.refresh()
                save()
            } catch (_: CancellationException) {
                // 用户取消:保留原历史不动
            } catch (e: Exception) {
                notify("压缩失败:" + e.message)
            } finally {
                isStreaming = false
            }
        }
    }

    private suspend fun requestSummary(
        opt: ModelOption,
        turns: List<ChatTurn>,
        content: StringBuilder,
        reasoning: StringBuilder,
        httpLog: MutableList<HttpExchange>,
        think: Boolean
    ) {
        val log = mutableListOf<HttpExchange>()
        LlmClient.stream(opt.provider, opt.model, turns, null, think, log).collect { e ->
            when (e) {
                is StreamEvent.Content -> content.append(e.text)
                is StreamEvent.Reasoning -> reasoning.append(e.text)
            }
        }
        httpLog.addAll(log)
    }

    private fun extractSummary(content: StringBuilder, reasoning: StringBuilder): String {
        var summary = content.toString().trim()
        if (summary.isEmpty()) summary = reasoning.toString().trim()
        if (summary.contains("<think>")) {
            summary = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL).replace(summary, "").trim()
        }
        return summary
    }

    /** 设置页关闭时调用:保存并按最新配置重建模型下拉项。 */
    fun onSettingsClosed() {
        save()
        rebuildModelOptions()
    }

    fun deleteConversation(c: ConversationUi) {
        if (isStreaming && selected == c) stop()
        settings.conversations.remove(c.data)
        conversations.remove(c)
        if (selected == c) selected = conversations.firstOrNull()
        save()
    }

    fun renameConversation(c: ConversationUi, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == c.title) return
        c.setTitle(trimmed)
        save()
    }

    /** 返回 (导出文件名, 文本内容);还没有 HTTP 记录时弹提示并返回 null。 */
    fun exportHttp(c: ConversationUi): Pair<String, String>? {
        val list = c.collectHttp()
        if (list.isEmpty()) {
            notify("该对话还没有 HTTP 记录,先完成一次对话再导出。")
            return null
        }
        val pid = c.data.providerId
        val mid = c.data.modelId
        val model = if (pid != null && mid != null) {
            "${settings.providers.firstOrNull { it.id == pid }?.name ?: ""} · $mid"
        } else ""
        return safeFileName(c.title) + "-http.txt" to HttpLogFormatter.build(c.title, model, list)
    }

    private fun safeFileName(s: String): String {
        val cleaned = s.trim().map { ch -> if (ch in "\\/:*?\"<>|") '_' else ch }.joinToString("")
        return cleaned.ifEmpty { "对话" }
    }

    private fun rebuildModelOptions() {
        val opts = settings.providers.filter { it.enabled }.flatMap { p ->
            p.models.filter { it.enabled }.map { ModelOption(p, it) }
        }
        modelOptions.clear()
        modelOptions.addAll(opts)
        var sel: ModelOption? = null
        val pid = selected?.data?.providerId
        val mid = selected?.data?.modelId
        if (pid != null && mid != null) {
            sel = opts.firstOrNull { it.provider.id == pid && it.model.id == mid }
        }
        if (sel == null) {
            val cpid = settings.currentProviderId
            val cmid = settings.currentModelId
            if (cpid != null && cmid != null) {
                sel = opts.firstOrNull { it.provider.id == cpid && it.model.id == cmid }
            }
        }
        if (sel == null) sel = opts.firstOrNull() // 首次启动无保存记录时,自动选中第一个可用模型
        selectedModel = sel
    }

    private fun restoreModelFor(c: ConversationUi) {
        val pid = c.data.providerId ?: return
        val mid = c.data.modelId ?: return
        val m = modelOptions.firstOrNull { it.provider.id == pid && it.model.id == mid } ?: return
        if (m != selectedModel) selectedModel = m
    }

    companion object {
        private const val CompactPrompt =
            "你是对话摘要器。请把下面的对话历史压缩成一段简洁摘要,保留:用户的核心需求与约束、已得到的关键结论与数据、尚未解决的问题。直接输出摘要正文,不要任何评论或开场白。"
    }
}
