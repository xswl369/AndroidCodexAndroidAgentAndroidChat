package com.xs.chat.ui
import android.util.Log

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xs.chat.plugins.ScriptRunner

// ---------- 语法树 ----------

sealed interface Span {
    data class Text(val text: String) : Span
    data class Bold(val children: List<Span>) : Span
    data class Italic(val children: List<Span>) : Span
    data class Strike(val children: List<Span>) : Span
    data class Code(val code: String) : Span
    data class Link(val text: String, val url: String) : Span
}

sealed interface Block {
    data class Paragraph(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class ListBlock(val markers: List<String>, val items: List<List<Span>>, val ordered: Boolean) : Block
    data class Quote(val spans: List<Span>) : Block
    data class CodeBlock(val lang: String, val code: String) : Block
    data class Table(val headers: List<String>, val rows: List<List<String>>) : Block
    data object Rule : Block
}

// ---------- 解析器 ----------

object Markdown {

    private val headingRegex = Regex("^#{1,6}\\s+")
    private val ruleRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")
    // 覆盖 1. / 1、/ 一、 / - * • 列表；中文模型常用「1、」「一、」前缀
    private val listRegex = Regex("^(\\*{0,2})(\\d{1,2}[.．、]|[一二三四五六七八九十]{1,3}[.、]|[-•])\\s*(.*)$")
    private val tableSepRegex = Regex("^[\\s|:|-]+$")

    /** 解析失败的兜底：整体作为纯文本段落，保证任何内容都能渲染。 */
    fun parsePlainSpans(source: String): List<Span> = listOf(Span.Text(source))

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = normalizeDenseList(source).replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        val paragraph = mutableListOf<String>()

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(parseInline(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()

            when {
                // 围栏代码块
                trimmed.startsWith("```") -> {
                    flushParagraph()
                    val lang = trimmed.removePrefix("```").trim()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && lines[i].trim() != "```") {
                        code += lines[i]
                        i++
                    }
                    i++ // 跳过结束围栏
                    blocks += Block.CodeBlock(lang, code.joinToString("\n"))
                }
                // 标题
                headingRegex.containsMatchIn(trimmed) -> {
                    flushParagraph()
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += Block.Heading(level, parseInline(trimmed.drop(level).trim()))
                    i++
                }
                // 分隔线
                ruleRegex.matches(trimmed) -> {
                    flushParagraph()
                    blocks += Block.Rule
                    i++
                }
                // 引用
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quote += lines[i].trim().removePrefix(">").trim()
                        i++
                    }
                    blocks += Block.Quote(parseInline(quote.joinToString(" ")))
                }
                // 表格
                trimmed.contains("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1].trim()) -> {
                    flushParagraph()
                    val headers = splitTableRow(trimmed)
                    i += 2 // 跳过表头和分隔行
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().contains("|")) {
                        rows += splitTableRow(lines[i].trim())
                        i++
                    }
                    blocks += Block.Table(headers, rows)
                }
                // 列表
                listRegex.matchEntire(trimmed) != null -> {
                    flushParagraph()
                    val first = listRegex.matchEntire(trimmed)!!
                    val marker0 = first.groupValues[2]
                    val ordered = marker0.first().isDigit() ||
                        "一二三四五六七八九十".contains(marker0.first())
                    val markers = mutableListOf<String>()
                    val items = mutableListOf<List<Span>>()
                    while (i < lines.size) {
                        val t = lines[i].trim()
                        val item = listRegex.matchEntire(t) ?: break
                        val marker = item.groupValues[2]
                        val itemOrdered = marker.first().isDigit() ||
                            "一二三四五六七八九十".contains(marker.first())
                        if (itemOrdered != ordered) break
                        markers += marker.trimEnd('.', '、')
                        var rest = item.groupValues[3]
                        if (item.groupValues[1].length == 2) rest = rest.replaceFirst("**", "")
                        items += parseInline(rest)
                        i++
                    }
                    blocks += Block.ListBlock(markers, items, ordered)
                }
                trimmed.isEmpty() -> {
                    flushParagraph()
                    i++
                }
                else -> {
                    paragraph += raw
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /**
     * 模型常把「B.1. 标题**正文……B.2. 标题**正文…」或「1、标题……2、」连成一行输出，
     * 只在“句子边界（句号/叹号/问号/冒号/行首）”后出现的编号前补换行，避免把正文里的普通数字误拆成条目。
     */
    private fun normalizeDenseList(text: String): String {
        val listStartRe = Regex(
            "(^|[。！？；:：])(?:\\s*\\*{0,2})(\\d{1,2}[.．、]|[一二三四五六七八九十]{1,3}[.、])(?=\\S)",
            RegexOption.MULTILINE
        )
        val out = listStartRe.replace(text) { m ->
            if (m.groupValues[1].isEmpty()) m.value else m.groupValues[1] + "\n" + m.groupValues[3]
        }
        // 条目行内悬挂的闭合 **（如「1.标题**正文」）清理掉，避免残留星号
        return Regex("^((?:\\d{1,2}|[一二三四五六七八九十]{1,3})[.．、])(\\S*?)\\*{2}", RegexOption.MULTILINE)
            .replace(out) { m -> m.groupValues[1] + m.groupValues[2] }
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim().trim('|').trim()
        return t.isNotEmpty() && t.all { it == '-' || it == ':' || it == ' ' } && t.contains('-')
    }

    private fun splitTableRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    private fun parseInline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        val buf = StringBuilder()

        fun flush() {
            if (buf.isNotEmpty()) {
                spans += Span.Text(buf.toString())
                buf.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    buf.append(text[i + 1]); i += 2
                }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans += Span.Code(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        val parenEnd = text.indexOf(')', close + 2)
                        if (parenEnd > close) {
                            flush()
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, parenEnd)
                            spans += Span.Link(label, url)
                            i = parenEnd + 1
                        } else {
                            buf.append(c); i++
                        }
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flush()
                        spans += Span.Bold(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        flush()
                        spans += Span.Italic(parseInline(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '_' && i + 1 < text.length && text[i + 1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i) {
                        flush()
                        spans += Span.Bold(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '_' -> {
                    val end = text.indexOf('_', i + 1)
                    if (end > i) {
                        flush()
                        spans += Span.Italic(parseInline(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        flush()
                        spans += Span.Strike(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        buf.append(c); i++
                    }
                }
                else -> {
                    buf.append(c); i++
                }
            }
        }
        flush()
        return spans
    }
}

/** 解析失败的降级：整段作为纯文本 span，保证任何输入都能渲染不崩溃。 */
private fun parsePlainSpans(text: String): List<Span> = listOf(Span.Text(text))

// ---------- 渲染 ----------

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    onCopyCode: (String) -> Unit = {},
    onRunCode: ((lang: String, code: String) -> Unit)? = null
) {
    // 解析失败时降级为纯文本，避免异常内容导致崩溃
    val blocks = remember(markdown) {
        runCatching { Markdown.parse(markdown) }
            .getOrElse { listOf(Block.Paragraph(parsePlainSpans(markdown))) }
    }
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> InlineText(block.spans, textStyle)
                is Block.Heading -> InlineText(
                    block.spans,
                    textStyle.copy(
                        fontSize = when (block.level) {
                            1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 16.sp
                        },
                        fontWeight = FontWeight.Bold
                    )
                )
                is Block.ListBlock -> {
                    block.items.forEachIndexed { idx, spans ->
                        Row(Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp)) {
                            Text(
                                if (block.ordered) "${idx + 1}." else "•",
                                style = textStyle.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.width(36.dp)
                            )
                            InlineText(spans, textStyle)
                        }
                    }
                }
                is Block.Quote -> Surface(
                    color = codeBg,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    InlineText(block.spans, textStyle, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                is Block.CodeBlock -> CodeBlockView(block, codeBg, onCopyCode, onRunCode)
                is Block.Table -> TableView(block, primary)
                Block.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
private fun InlineText(
    spans: List<Span>,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeFg = MaterialTheme.colorScheme.onSurface
    val annotated = buildAnnotatedString {
        fun appendSpan(span: Span) {
            when (span) {
                is Span.Text -> append(span.text)
                is Span.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = style.fontSize,
                        background = codeBg,
                        color = codeFg
                    )
                ) { append(span.code) }
                is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    span.children.forEach { appendSpan(it) }
                }
                is Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    span.children.forEach { appendSpan(it) }
                }
                is Span.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    span.children.forEach { appendSpan(it) }
                }
                is Span.Link -> {
                    pushLink(
                        LinkAnnotation.Url(
                            url = span.url,
                            styles = TextLinkStyles(style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline))
                        )
                    )
                    append(span.text)
                    pop()
                }
            }
        }
        spans.forEach { appendSpan(it) }
    }
    Text(annotated, style = style, modifier = modifier)
}

@Composable
private fun CodeBlockView(
    block: Block.CodeBlock,
    codeBg: Color,
    onCopyCode: (String) -> Unit,
    onRunCode: ((lang: String, code: String) -> Unit)?
) {
    val runLang = ScriptRunner.langFromFence(block.lang)
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = codeBg,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp)
            ) {
                Text(
                    block.lang.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (runLang != null && onRunCode != null) {
                    IconButton(onClick = { Log.w("XSRunDebug", "run-click lang=" + block.lang + " codeLen=" + block.code.length); onRunCode(block.lang, block.code) }) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "运行代码",
                            tint = primary,
                            modifier = Modifier.width(18.dp)
                        )
                    }
                }
                IconButton(onClick = { onCopyCode(block.code) }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制代码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp)
                    )
                }
            }
            Box(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            ) {
                Text(
                    block.code,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun TableView(block: Block.Table, primary: Color) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth()) {
            block.headers.forEach { h ->
                Text(
                    h,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.widthIn(min = 96.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        HorizontalDivider(color = primary.copy(alpha = 0.4f))
        block.rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                block.headers.indices.forEach { idx ->
                    Text(
                        row.getOrElse(idx) { "" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.widthIn(min = 96.dp).padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}



