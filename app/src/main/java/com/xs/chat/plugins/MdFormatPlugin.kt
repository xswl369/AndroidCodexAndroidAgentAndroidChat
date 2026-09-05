package com.xs.chat.plugins

/**
 * 内置「Markdown 格式修正」插件。
 *
 * 作用：把 AI 回复规整为标准 Markdown，修正模型常见输出错乱：
 * - 块级标记粘连（`文本---##标题```markdown#内容` 挤成一行）-> 自动拆行
 * - 缺失空格的标题（`#标题`）、行首 `*` 列表、`1）`/`（1）` 编号风格
 * - 缺分隔行的表格、未闭合的代码围栏、裸链接
 * - 通用文本：全角空格/标点、重复中文句读、连续空格、行尾空格、BOM/CRLF
 *
 * 纯 Kotlin、零 Android 依赖：JVM 单元测试直接验证（真实有效），
 * 渲染层（MarkdownText）与持久化共用同一入口 [normalize]。对代码围栏内的内容一律不处理。
 */
object MdFormatPlugin {

    const val ID = "md_format"
    const val NAME = "Markdown 格式修正"
    const val DESC = "自动识别并修复 AI 回复的标题、列表、表格、代码块、加粗与链接格式"

    private val fenceCloseRe = Regex("^\\s*```\\s*$")

    /** 全角数字 -> 半角，避免 `１、２` 等编号无法识别。 */
    private val fullWidthDigits = mapOf(
        '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
        '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9'
    )

    /**
     * 规范化入口：任何模型输出文本 -> 可被标准 Markdown 解析器稳定识别的文本。
     * 幂等设计：对已规范文本重复调用结果不变。
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return raw
        var text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
        text = closeUnclosedFence(text)   // 1. 未闭合代码围栏先补齐
        text = splitAdjacentBlocks(text)  // 2. 块级标记粘连（--- / ## / 围栏）拆成独立行
        text = splitDenseItems(text)      // 3. 「1. 甲。2. 乙」挤在一行 -> 拆条目
        text = insertTableSeparators(text) // 4. 表格缺分隔行 -> 补 `| --- |`
        text = fixLines(text)             // 5. 行级修复（标题/列表/编号/裸链接），围栏内跳过
        return collapseBlankLines(text)   // 6. 压缩多余空行
    }

    // ---------- 1. 代码围栏 ----------

    /** 全文扫描（含行内围栏）：若最后打开的围栏未闭合，在末尾补上结束 ```。 */
    private fun closeUnclosedFence(text: String): String {
        var inFence = false
        for (line in text.split("\n")) {
            if (line.contains("```")) inFence = !inFence
        }
        return if (inFence) text.trimEnd('\n') + "\n" + "```" else text
    }

    // ---------- 2. 块粘连拆分 ----------

    /** 模型爱把「文本---## 版本：```markdown#内容```---## 版本二」整排写成一行；
     *  在围栏外把 `---`（分隔线）、`##`（标题）与围栏前后补换行，恢复块级结构。 */
    private fun splitAdjacentBlocks(text: String): String {
        if (!text.contains("```")) return splitAdjacentText(text)
        val sb = StringBuilder(text.length + 128)
        val parts = text.split("```")
        // parts[0] 围栏前文本；此后奇下标=围栏开+内容，偶下标=围栏关闭后的文本
        sb.append(splitAdjacentText(parts[0]))
        for (i in 1 until parts.size) {
            if (i % 2 == 0) {
                // 关闭围栏：fence 后接的文本另起行处理
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append("```")
                if (parts[i].isNotEmpty()) {
                    sb.append('\n').append(splitAdjacentText(parts[i]))
                }
            } else {
                // 打开围栏：保证与前方文本分行为一行（语言标记连同内容保留原样）
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                sb.append("```").append(parts[i])
            }
        }
        return sb.toString()
    }

    /** 围栏外文本：把粘连的 `---`、`#` 标题与围栏边界拆成独立行（表格行跳过）。 */
    private fun splitAdjacentText(text: String): String {
        val sb = StringBuilder(text.length + 64)
        for (line in text.split("\n")) {
            if (line.trimStart().startsWith("|")) {
                // 表格行（含 |---| 分隔行）保持原样，避免拆坏
                sb.append(line).append('\n')
                continue
            }
            var l = line
            // `文本---内容` 或 `文本---`：分隔线独立成行并留空结构
            l = Regex("-{3,}").replace(l) { m -> "\n" + m.value + "\n" }
            // `文本##标题`：标题前补换行，避免 `##` 被吞进段落（行首 # 组不动）
            l = Regex("(?<=[^\\s#])#{1,6}(?=\\S)").replace(l) { m -> "\n" + m.value }
            sb.append(l).append('\n')
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n")
    }

    // ---------- 3. 密集条目 ----------

    /** 模型常把「甲。乙。」或「1、甲。2、乙」写成一行，按句子边界拆成独立行。 */
    private val denseItemRe = Regex(
        "(^|[。！？；;：:])(\\s*\\*{0,2})" +
            "(\\d{1,2}[.．、)）]|[一二三四五六七八九十]{1,3}[.、)）])" +
            "(?=\\s*\\*{0,2}\\S)",
        RegexOption.MULTILINE
    )

    /** 拆行时跳过代码围栏区间，避免误拆代码内容。 */
    private fun splitDenseItems(text: String): String {
        val lines = text.split("\n")
        val out = mutableListOf<String>()
        var inFence = false
        for (line in lines) {
            if (line.contains("```")) inFence = !inFence
            if (inFence) {
                out += line
                continue
            }
            out += denseItemRe.replace(line) { m ->
                if (m.groupValues[1].isEmpty()) m.value
                else {
                    val sep = m.groupValues[1]
                    val keep = if (sep.length == 1 && (sep[0].code == 0x3002 || sep[0].code == 0xFF01 || sep[0].code == 0xFF1F)) "" else sep
                    keep + "\n" + m.groupValues[2] + m.groupValues[3]
                }
            }
        }
        return out.joinToString("\n")
    }

    // ---------- 4. 表格 ----------

    private val tableRowRe = Regex("^\\s*\\|.+\\|\\s*$")
    private val tableSepRe = Regex("^\\s*\\|?[\\s:|-]+\\|?\\s*$")

    /** 表头下紧跟数据行（缺 `|---|---|`）时自动补分隔行，列数跟随表头。 */
    private fun insertTableSeparators(text: String): String {
        val lines = text.split("\n").toMutableList()
        var inFence = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("```")) inFence = !inFence
            if (inFence) { i++; continue }
            if (i + 1 < lines.size && isTableHeader(line)) {
                val next = lines[i + 1]
                val looksData = next.contains("|") &&
                    (next.trimStart().startsWith("|") || next.trimEnd().endsWith("|"))
                if (!isTableSep(next) && looksData) {
                    val cols = line.trim().removePrefix("|").removeSuffix("|").split("|").size
                    val sep = "| " + List(cols) { "---" }.joinToString(" | ") + " |"
                    lines.add(i + 1, sep)
                    i++
                }
            }
            i++
        }
        return lines.joinToString("\n")
    }

    private fun isTableHeader(line: String): Boolean =
        tableRowRe.matches(line) && !tableSepRe.matches(line)

    private fun isTableSep(line: String): Boolean = tableSepRe.matches(line)

    // ---------- 5. 行级修复（围栏外） ----------

    private fun fixLines(text: String): String {
        val sb = StringBuilder(text.length + 64)
        var inFence = false
        for (line in text.split("\n")) {
            if (line.contains("```")) inFence = !inFence
            if (inFence) { sb.append(line).append('\n'); continue }

            var l = line.trimEnd()
            l = fixHeading(l)
            l = fixBulletStar(l)
            l = fixNumbering(l)
            l = wrapBareUrls(l)
            l = fixText(l)
            sb.append(l).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    /** `#标题` -> `# 标题`（保留原级数，`## 标题` 等已规范的不动）。 */
    private val headingSpacingRe = Regex("^(#{1,6})([^\\s#][^\\n]*)$")
    private fun fixHeading(line: String): String =
        headingSpacingRe.replace(line) { m -> m.groupValues[1] + " " + m.groupValues[2] }

    /** 行首 `* ` 无序列表 -> `- `（避免单星号被当作斜体分隔符残留）。不影响 `**加粗**`、`***`。 */
    private val bulletStarRe = Regex("^(\\s*)\\*(\\s+.*)$")
    private fun fixBulletStar(line: String): String =
        bulletStarRe.replace(line) { m -> m.groupValues[1] + "-" + m.groupValues[2] }

    /** 编号风格归一化：全角数字半角化；`1、`/`1）`/`1)` -> `1. `；`（一）`/`(一)` -> `一、`。 */
    private fun fixNumbering(line: String): String {
        var t = line
        if (t.any { fullWidthDigits.containsKey(it) }) {
            t = buildString { line.forEach { append(fullWidthDigits[it] ?: it) } }
        }
        val half = Regex("^\\s*(\\d{1,2})[、.．)）]\\s*(.*)$").matchEntire(t)
        if (half != null) return "${half.groupValues[1]}. ${half.groupValues[2]}"
        val cn = Regex("^\\s*[（(]([一二三四五六七八九十]{1,3})[）)]\\s*(.*)$").matchEntire(t)
        if (cn != null) return "${cn.groupValues[1]}、${cn.groupValues[2]}"
        return t
    }

    /** 裸 URL（`https://...`）包装成 Markdown 链接；已在 `](url)` 内的不重复包装。 */
    private val bareUrlRe = Regex("(?<![\\]\\w\\[(])https?://[^\\s<>\\[\\]》【】。，,；;：:]+")
    private fun wrapBareUrls(line: String): String =
        bareUrlRe.replace(line) { m ->
            val cleaned = m.value.replace(Regex("[.,;:]+$"), "")
            val url = if (cleaned.isNotEmpty()) cleaned else m.value
            if (url.length == m.value.length) "[$url]($url)"
            else "[$url]($url)" + m.value.substring(url.length)
        }

    /** 通用文本格式修正（仅围栏外）：全角空格转半角、重叠中文标点压缩、
     *  多余行首缩进压到 4；行内连续空格合为 1。 */
    private val repeatPunctRe = Regex("([。，、；])\\1{1,}")
    private val multiSpaceRe = Regex("(?<=\\S) {2,}")
    private val leadingIndentRe = Regex("^ {5,}")
    private fun fixText(line: String): String {
        var t = line.replace('\u3000', ' ')
        t = repeatPunctRe.replace(t) { m -> m.groupValues[1] }
        t = leadingIndentRe.replace(t, "    ")
        return multiSpaceRe.replace(t, " ")
    }

    // ---------- 6. 空行 ----------

    /** 围栏外连续空行压为一行，首尾不留空行。 */
    private fun collapseBlankLines(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var inFence = false
        var prevBlank = false
        for (line in text.split("\n")) {
            if (line.contains("```")) inFence = !inFence
            val blank = !inFence && line.isBlank()
            if (blank && prevBlank) continue
            sb.append(line).append('\n')
            prevBlank = blank && !inFence
        }
        return sb.toString().trimEnd('\n', ' ')
    }
}