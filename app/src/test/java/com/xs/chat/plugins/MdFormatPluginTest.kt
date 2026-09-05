package com.xs.chat.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MdFormatPlugin 真实有效性：JVM 单元测试覆盖 Markdown + 通用文本格式场景与幂等性。 */
class MdFormatPluginTest {

    private fun n(raw: String): String = MdFormatPlugin.normalize(raw)

    @Test
    fun headings() {
        assertEquals("# 标题", n("#标题"))
        assertEquals("## 标题", n("## 标题"))
        assertEquals("### 子节", n("###子节"))
    }

    @Test
    fun bulletStarToDash() {
        assertEquals("- 苹果\n- 香蕉", n("* 苹果\n* 香蕉"))
        assertEquals("**加粗** 不变", n("**加粗** 不变"))
        assertEquals("文字 *斜体* 文字", n("文字 *斜体* 文字"))
    }

    @Test
    fun parenthesisNumbering() {
        assertEquals("1. 第一项\n2. 第二项", n("1）第一项\n2）第二项"))
        assertEquals("1. 第一项\n2. 第二项", n("1) 第一项\n2) 第二项"))
        assertEquals("一、首项\n二、次项", n("（一）首项\n（二）次项"))
        assertEquals("1. 全角\n2. 数字", n("１、全角\n２、数字"))
    }

    @Test
    fun denseItemsSplit() {
        assertEquals("结论：\n1. 甲\n2. 乙", n("结论：1. 甲。2. 乙"))
        assertEquals("要点：① 说明", n("要点：① 说明"))
    }

    @Test
    fun tableSeparatorInjected() {
        val out = n("| 名称 | 数量 |\n| 苹果 | 3 |\n| 香蕉 | 5 |")
        assertTrue("缺分隔行应补 |---|", out.contains("\n| --- | --- |\n"))
        val withSep = n("| a | b |\n| --- | --- |\n| 1 | 2 |")
        assertTrue("已有分隔行不重复", withSep.split("\n").count { it.contains("---") } == 1)
    }

    @Test
    fun fenceHandling() {
        val out = n("```kotlin\nfun main() {}\n")
        assertTrue("未闭合围栏自动补全", out.endsWith("```"))
        assertTrue("围栏内原样", n("```text\n| a | b |\n```\n正文").contains("\n| a | b |\n"))
    }

    @Test
    fun bareUrlWrapped() {
        assertEquals("详见 [https://a.io/x](https://a.io/x)", n("详见 https://a.io/x"))
        assertEquals("已有链接保持", "已有链接 [标签](https://b.io)", n("已有链接 [标签](https://b.io)"))
        assertTrue("中文句号不被吞", n("见 https://c.cn/文件。好").contains("。"))
    }

    @Test
    fun blankLinesAndWhitespace() {
        assertEquals("a\n\nb", n("a\n\n\n\n\nb"))
        assertEquals("a", n("\uFEFFa\r\n"))
        assertEquals("行尾无空格", n("行尾无空格   \n"))
    }

    @Test
    fun plainTextFixes() {
        assertEquals("甲。乙。丙", n("甲。。乙。丙"))
        assertEquals("A 与 B", n("A   与   B"))
        assertEquals("全角 空格", n("全角　空格"))
        assertEquals("    压缩为四格", n("        压缩为四格"))
    }

    @Test
    fun idempotent() {
        val raw = "#标题\n\n* 列表\n| 列A | 列B |\n| 1 | 2 |\n\n```py\nprint(1)\n```\n\n详情 https://x.io"
        val once = n(raw)
        assertEquals("规范化幂等", once, n(once))
    }

    @Test
    fun kitchenSink() {
        val raw = "任务完成情况：\n#总览\n* 修复了三处 bug。\n* 补充了测试。\n2）运行时无崩溃\n" +
            "| 模块 | 状态 |\n| 渲染 | 通过 |\n\n```kotlin\nval x = 1\n```\n\n```未闭合\nprint(0)\n"
        val out = n(raw)
        System.err.println("ADHESIVE_OUT=[" + out.replace("\n", "\\n") + "]")
        assertTrue(out.contains("# 总览"))
        assertTrue(out.contains("- 修复了三处 bug"))
        assertTrue(out.contains("| --- | --- |"))
        assertTrue(out.endsWith("```"))
        assertTrue(out.contains("2. 运行时无崩溃"))
    }
    @Test
    fun adhesiveBlocksFromRealSample() {
        val raw = "以下是模板选择使用：---##版本一```kotlin#你好，我是 [名字]\n##基本信息\n- **姓名**：[名字]\n```---##版本二：创意型\n```kotlin2\nprint(1)\n```\n你可以替换：`[名字]`"
        val out = n(raw)
        assertTrue("分隔线独立成行", out.contains("\n---\n## 版本一"))
        assertTrue("第二个标题成行", out.contains("## 版本二"))
        assertTrue("围栏闭合后文本另起行", out.contains("\n```\n\n你可以"))
    }

}