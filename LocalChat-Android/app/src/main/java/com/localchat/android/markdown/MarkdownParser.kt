package com.localchat.android.markdown

// ===== 行内中间模型 =====
sealed class MdInline

/** 普通文本片段(可带粗体/斜体/删除线/行内代码标记)。 */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false
) : MdInline()

/** 超链接。 */
data class MdLink(val text: String, val url: String) : MdInline()

// ===== 块级中间模型 =====
sealed class MdBlock

data class MdParagraph(val inlines: List<MdInline>) : MdBlock()
data class MdHeading(val level: Int, val inlines: List<MdInline>) : MdBlock()
data class MdCode(val lang: String, val code: String) : MdBlock()
data class MdQuote(val inlines: List<MdInline>) : MdBlock()
data class MdItem(val level: Int, val marker: String, val inlines: List<MdInline>) : MdBlock()
class MdRule : MdBlock()
data class MdTable(
    val header: List<List<MdInline>>,
    val rows: List<List<List<MdInline>>>
) : MdBlock()

/**
 * 轻量 Markdown 解析器:块级(标题/段落/代码围栏/引用/列表/分隔线/表格)+ 行内
 * (粗体 **x**、斜体 *x*、删除线 ~~x~~、行内代码 `x`、链接 [t](u))。纯逻辑,不依赖 UI。
 */
object MarkdownParser {
    private val listItemRegex = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")

    fun parse(markdown: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val para = mutableListOf<List<MdInline>>()
        var i = 0
        fun flushPara() {
            if (para.isEmpty()) return
            val inlines = mutableListOf<MdInline>()
            para.forEachIndexed { k, lineParts ->
                if (k > 0) inlines.add(MdRun("\n"))
                inlines.addAll(lineParts)
            }
            blocks.add(MdParagraph(inlines))
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty()) {
                flushPara(); i++; continue
            }
            // 代码围栏(流式输出中未闭合时,剩余行都算代码)
            if (t.startsWith("```")) {
                flushPara()
                val lang = t.substring(3).trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // 跳过结束围栏(不存在则越界结束)
                blocks.add(MdCode(lang, code.toString().trimEnd('\n')))
                continue
            }
            // ATX 标题 # ~ ######
            if (t[0] == '#') {
                var lv = 0
                while (lv < t.length && t[lv] == '#') lv++
                if (lv <= 6 && lv < t.length && t[lv] == ' ') {
                    flushPara()
                    blocks.add(MdHeading(lv, parseInlines(t.substring(lv + 1).trim())))
                    i++; continue
                }
            }
            // 水平分隔线 --- *** - - -
            if (t.all { it == '-' || it == '*' || it == ' ' } && t.any { it == '-' || it == '*' }) {
                flushPara()
                blocks.add(MdRule())
                i++; continue
            }
            // 引用块
            if (t.startsWith(">")) {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size) {
                    val lt = lines[i].trimStart()
                    if (!lt.startsWith(">")) break
                    val content = if (lt.length > 1 && lt[1] == ' ') lt.substring(2) else lt.substring(1).trimStart()
                    q.append(content).append('\n')
                    i++
                }
                blocks.add(MdQuote(parseInlines(q.toString().trimEnd('\n'))))
                continue
            }
            // 列表项(- * + 或 1. 1))
            val lm = listItemRegex.find(line)
            if (lm != null) {
                flushPara()
                val level = lm.groupValues[1].length.coerceAtMost(6) / 2
                val marker = lm.groupValues[2]
                val markerText = if (marker == "-" || marker == "*" || marker == "+") "•" else marker.trimEnd('.') + "."
                blocks.add(MdItem(level, markerText, parseInlines(lm.groupValues[3].trim())))
                i++; continue
            }
            // 表格(下一行是 |---|---| 分隔行)
            if (t.contains('|') && i + 1 < lines.size && isTableSep(lines[i + 1].trim())) {
                flushPara()
                val header = splitRow(t)
                i += 2
                val rows = mutableListOf<List<List<MdInline>>>()
                while (i < lines.size) {
                    val rt = lines[i].trim()
                    if (rt.isEmpty() || !rt.contains('|')) break
                    rows.add(splitRow(rt))
                    i++
                }
                blocks.add(MdTable(header, rows))
                continue
            }
            para.add(parseInlines(t))
            i++
        }
        flushPara()
        return blocks
    }

    /** 解析行内标记(粗体/斜体/删除线/行内代码/链接),未闭合的标记保持字面量。 */
    fun parseInlines(s: String): List<MdInline> {
        val list = mutableListOf<MdInline>()
        val buf = StringBuilder()
        var i = 0
        fun flush() {
            if (buf.isNotEmpty()) {
                list.add(MdRun(buf.toString()))
                buf.clear()
            }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    list.add(MdRun(s.substring(i + 1, end), code = true))
                    i = end + 1
                    continue
                }
            } else if (c == '*' && i + 1 < s.length && s[i + 1] == '*') {
                val end = s.indexOf("**", i + 2)
                if (end > i + 1) {
                    flush()
                    parseInlines(s.substring(i + 2, end)).forEach { x ->
                        list.add(if (x is MdRun) x.copy(bold = true) else x)
                    }
                    i = end + 2
                    continue
                }
            } else if (c == '~' && i + 1 < s.length && s[i + 1] == '~') {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flush()
                    parseInlines(s.substring(i + 2, end)).forEach { x ->
                        list.add(if (x is MdRun) x.copy(strike = true) else x)
                    }
                    i = end + 2
                    continue
                }
            } else if (c == '*' || c == '_') {
                val end = s.indexOf(c, i + 1)
                // 下划线夹在单词字母之间(如 some_var_name)不算斜体
                val bound = c == '*' ||
                    ((i == 0 || !s[i - 1].isLetterOrDigit()) &&
                        (end + 1 >= s.length || !s[end + 1].isLetterOrDigit()))
                if (end > i + 1 && !s[i + 1].isWhitespace() && bound) {
                    flush()
                    parseInlines(s.substring(i + 1, end)).forEach { x ->
                        list.add(if (x is MdRun) x.copy(italic = true) else x)
                    }
                    i = end + 1
                    continue
                }
            } else if (c == '[') {
                val close = s.indexOf("](", i + 1)
                if (close > i) {
                    val end = s.indexOf(')', close + 2)
                    if (end > close) {
                        flush()
                        list.add(MdLink(s.substring(i + 1, close), s.substring(close + 2, end)))
                        i = end + 1
                        continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flush()
        return list
    }

    private fun splitRow(line: String): List<List<MdInline>> {
        val t = line.trim().trim('|')
        return t.split('|').map { parseInlines(it.trim()) }
    }

    private fun isTableSep(t: String): Boolean {
        if (t.isEmpty() || !t.contains('-')) return false
        return t.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty()
    }
}
