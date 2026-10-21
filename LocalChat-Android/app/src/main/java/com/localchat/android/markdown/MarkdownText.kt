package com.localchat.android.markdown

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ErrorColor = Color(0xFFD5423F)
private val MutedColor = Color(0xFF6F7680)
private val CodeBg = Color(0xFFF6F8FA)
private val MdBorderColor = Color(0xFFE7EAED)
private val QuoteAccent = Color(0xFFD0D7DE)
private val QuoteBg = Color(0xFFF8F9FB)
private val LinkColor = Color(0xFF0969DA)
private val InlineCodeBg = Color(0xFFEEF0F3)
private val BodyColor = Color(0xFF1F2328)
private val CodeTextColor = Color(0xFF24292F)

/** 把 Markdown 文本渲染为 Compose 元素树(段落/标题/代码块/引用/列表/表格/分隔线)。 */
@Composable
fun MarkdownText(markdown: String, isError: Boolean = false, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { BlockView(it, isError) }
    }
}

@Composable
private fun BlockView(b: MdBlock, isError: Boolean) {
    when (b) {
        is MdCode -> CodeBlock(b)
        is MdHeading -> HeadingView(b)
        is MdRule -> Box(
            Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 5.dp).height(1.dp).background(MdBorderColor)
        )
        is MdQuote -> QuoteView(b)
        is MdItem -> ItemView(b)
        is MdTable -> TableView(b)
        is MdParagraph -> InlineText(
            b.inlines, isError,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun InlineText(
    inlines: List<MdInline>,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 13.5.sp,
    lineHeight: TextUnit = 21.sp,
    fontWeight: FontWeight? = null,
    color: Color = if (isError) ErrorColor else BodyColor
) {
    val context = LocalContext.current
    val annotated = remember(inlines, color, fontWeight) {
        buildAnnotatedString {
            inlines.forEach { mi ->
                when (mi) {
                    is MdLink -> {
                        pushStringAnnotation(tag = "URL", annotation = mi.url)
                        withStyle(SpanStyle(color = LinkColor, textDecoration = TextDecoration.Underline)) {
                            append(mi.text)
                        }
                        pop()
                    }
                    is MdRun -> {
                        var style = SpanStyle(color = color, fontWeight = fontWeight)
                        if (mi.bold) style = style.copy(fontWeight = FontWeight.Bold)
                        if (mi.italic) style = style.copy(fontStyle = FontStyle.Italic)
                        if (mi.strike) style = style.copy(textDecoration = TextDecoration.LineThrough)
                        if (mi.code) style = style.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            background = InlineCodeBg,
                            color = BodyColor
                        )
                        withStyle(style) { append(mi.text) }
                    }
                }
            }
        }
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        fontSize = fontSize,
        lineHeight = lineHeight,
        onTextLayout = { layout.value = it },
        modifier = modifier.pointerInput(annotated) {
            detectTapGestures { pos ->
                val res = layout.value ?: return@detectTapGestures
                val offset = res.getOffsetForPosition(pos)
                annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { ann ->
                    val url = ann.item
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun HeadingView(h: MdHeading) {
    val size = when (h.level) {
        1 -> 19.sp
        2 -> 17.sp
        3 -> 15.5.sp
        else -> 14.sp
    }
    InlineText(
        h.inlines, fontWeight = FontWeight.Bold, fontSize = size, lineHeight = size * 1.45f,
        modifier = Modifier.padding(top = if (h.level <= 2) 10.dp else 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun CodeBlock(c: MdCode) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CodeBg)
            .border(1.dp, MdBorderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        if (c.lang.isNotEmpty()) {
            Text(c.lang, fontSize = 11.sp, color = MutedColor, modifier = Modifier.padding(bottom = 4.dp))
        }
        SelectionContainer {
            Text(
                c.code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = CodeTextColor
            )
        }
    }
}

@Composable
private fun QuoteView(q: MdQuote) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
            .background(QuoteBg)
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(QuoteAccent))
        InlineText(
            q.inlines, color = MutedColor,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ItemView(it: MdItem) {
    Row(Modifier.fillMaxWidth().padding(start = (6 + it.level * 16).dp, top = 2.dp, bottom = 2.dp)) {
        Text(it.marker + " ", color = MutedColor, fontSize = 13.5.sp, lineHeight = 21.sp)
        InlineText(it.inlines, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TableView(tb: MdTable) {
    val colCount = tb.header.size
    if (colCount == 0) return
    // 依据各列最大字符数估宽,整表横向滚动,列宽跨行对齐
    val colChars = IntArray(colCount)
    fun acc(cells: List<List<MdInline>>) {
        for (c in 0 until minOf(colCount, cells.size)) {
            colChars[c] = maxOf(colChars[c], cells[c].sumOf { inline ->
                when (inline) {
                    is MdRun -> inline.text.length
                    is MdLink -> inline.text.length
                }
            })
        }
    }
    acc(tb.header)
    tb.rows.forEach { acc(it) }
    val widths = colChars.map { (it.coerceIn(2, 18) * 8 + 16).dp }
    Column(
        Modifier.padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MdBorderColor)
    ) {
        TableHeaderView(tb.header, widths)
        tb.rows.forEach { TableRowView(it, widths) }
    }
}

@Composable
private fun TableHeaderView(cells: List<List<MdInline>>, widths: List<androidx.compose.ui.unit.Dp>) {
    Row {
        widths.forEachIndexed { c, w ->
            InlineText(
                cells.getOrElse(c) { emptyList() }, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(w).background(CodeBg)
                    .border(width = 0.5.dp, color = MdBorderColor)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun TableRowView(cells: List<List<MdInline>>, widths: List<androidx.compose.ui.unit.Dp>) {
    Row {
        widths.forEachIndexed { c, w ->
            InlineText(
                cells.getOrElse(c) { emptyList() }, fontSize = 12.5.sp, lineHeight = 18.sp,
                modifier = Modifier.width(w)
                    .border(width = 0.5.dp, color = MdBorderColor)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
    }
}
