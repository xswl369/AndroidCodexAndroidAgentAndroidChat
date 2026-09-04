package com.xs.chat.plugins

import java.util.Locale

/**
 * 内置常识/计算库（离线毫秒级直答）：
 * 覆盖计算器、温度换算、常用单位换算、星座、二十四节气、省份省会、化学元素、传统节日。
 * 命中返回完整答案文本，未命中返回 null（交回搜索引擎 / AI）。
 */
object KnowledgeBase {

    fun answer(question: String): String? {
        val q = question.trim()
        if (q.isEmpty() || q.length > 120) return null
        calc(q)?.let { return it }
        temperature(q)?.let { return it }
        unitConvert(q)?.let { return it }
        zodiac(q)?.let { return it }
        solarTerms(q)?.let { return it }
        festival(q)?.let { return it }
        province(q)?.let { return it }
        element(q)?.let { return it }
        constants(q)?.let { return it }
        return null
    }

    // ---------- 简单表达式计算（+ - * / ^ % 与括号，支持小数） ----------

    private class Parser(private val s: String) {
        private var i = 0
        fun parse(): Double = add()
        private fun add(): Double {
            var v = mul()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = mul()
                v = if (op == '+') v + r else v - r
            }
            return v
        }
        private fun mul(): Double {
            var v = pow()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                val r = pow()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }
        private fun pow(): Double {
            var v = unary()
            if (i < s.length && s[i] == '^') {
                i++
                v = StrictMath.pow(v, pow())
            }
            return v
        }
        private fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            if (i < s.length && s[i] == '(') { i++; val v = add(); if (i < s.length && s[i] == ')') i++; return v }
            val sb = StringBuilder()
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) sb.append(s[i++])
            if (sb.isEmpty()) throw IllegalArgumentException("bad token")
            return sb.toString().toDouble()
        }
    }

    private fun calc(q: String): String? {
        val t = q
            .replace("×", "*").replace("÷", "/").replace("＋", "+").replace("－", "-")
            .replace("％", "%").replace("（", "(").replace("）", ")")
            .replace("²", "^2").replace("³", "^3")
            .replace("=", "").trim()
        if (t.length !in 2..60) return null
        // 百分比直答：“50 的 20%”
        Regex("""^(\d+(?:\.\d+)?)\s*的\s*(\d+(?:\.\d+)?)\s*%$""").find(t)?.let {
            val num = it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100
            return formatResult(num)
        }
        if (!Regex("""^[0-9+*/%^().\- ]+$""").matches(t)) return null
        if (!Regex("""\d""").containsMatchIn(t)) return null
        if (!t.any { it in "+*/%^" || it == '-' }) return null
        val result = try { Parser(t).parse() } catch (_: Exception) { return null }
        if (!result.isFinite()) return null
        return formatResult(result)
    }

    // ---------- 温度换算 ----------

    private fun temperature(q: String): String? {
        Regex("""(\d+(?:\.\d+)?)\s*(?:摄氏(?:度)?|celsius)""", RegexOption.IGNORE_CASE).find(q)?.let {
            val c = it.groupValues[1].toDouble()
            return "${formatResult(c)} 摄氏度 = ${formatResult(c * 9 / 5 + 32)} 华氏度"
        }
        Regex("""(\d+(?:\.\d+)?)\s*(?:华氏(?:度)?|fahrenheit)""", RegexOption.IGNORE_CASE).find(q)?.let {
            val f = it.groupValues[1].toDouble()
            return "${formatResult(f)} 华氏度 = ${formatResult((f - 32) * 5 / 9)} 摄氏度"
        }
        return null
    }

    // ---------- 常用单位换算 ----------

    private class Unit(val factor: Double, val name: String)
    private val UNITS: Map<String, Unit> = mapOf(
        "公里" to Unit(1000.0, "长度"), "千米" to Unit(1000.0, "长度"), "km" to Unit(1000.0, "长度"),
        "米" to Unit(1.0, "长度"), "m" to Unit(1.0, "长度"), "分米" to Unit(0.1, "长度"),
        "厘米" to Unit(0.01, "长度"), "cm" to Unit(0.01, "长度"), "毫米" to Unit(0.001, "长度"),
        "mm" to Unit(0.001, "长度"), "微米" to Unit(1e-6, "长度"), "纳米" to Unit(1e-9, "长度"),
        "里" to Unit(500.0, "长度"), "英里" to Unit(1609.344, "长度"), "英尺" to Unit(0.3048, "长度"),
        "英寸" to Unit(0.0254, "长度"), "码" to Unit(0.9144, "长度"), "海里" to Unit(1852.0, "长度"),
        "千克" to Unit(1.0, "重量"), "公斤" to Unit(1.0, "重量"), "kg" to Unit(1.0, "重量"),
        "克" to Unit(0.001, "重量"), "g" to Unit(0.001, "重量"), "斤" to Unit(0.5, "重量"),
        "两" to Unit(0.05, "重量"), "磅" to Unit(0.45359237, "重量"), "盎司" to Unit(0.0283495, "重量"),
        "升" to Unit(1.0, "容量"), "l" to Unit(1.0, "容量"), "毫升" to Unit(0.001, "容量"),
        "ml" to Unit(0.001, "容量"), "立方米" to Unit(1000.0, "容量"),
        "平方米" to Unit(1.0, "面积"), "平方千米" to Unit(1000000.0, "面积"), "平方厘米" to Unit(1e-4, "面积"),
        "亩" to Unit(2000.0 / 3.0, "面积"), "公顷" to Unit(10000.0, "面积"),
        "秒" to Unit(1.0, "时间"), "s" to Unit(1.0, "时间"), "分钟" to Unit(60.0, "时间"),
        "小时" to Unit(3600.0, "时间"), "天" to Unit(86400.0, "时间"), "周" to Unit(604800.0, "时间"),
        "kb" to Unit(1024.0, "存储"), "mb" to Unit(1048576.0, "存储"), "gb" to Unit(1073741824.0, "存储"),
        "tb" to Unit(1099511627776.0, "存储"), "字节" to Unit(1.0, "存储")
    )

    /** 词边界单位匹配：长单位优先，重叠区域让位（避免“公里”里的“里”、“厘米”里的“米”误判）。 */
    private fun findUnits(q: String): List<Pair<Pair<String, Unit>, IntRange>> {
        val found = mutableListOf<Pair<Pair<String, Unit>, IntRange>>()
        for ((w, u) in UNITS.entries.sortedByDescending { it.key.length }) {
            val r = Regex("(?<![a-zA-Z\\u4e00-\\u9fa5])" + Regex.escape(w))
            r.find(q)?.let { m ->
                val overlap = found.any { it.second.first < m.range.last && m.range.first < it.second.last }
                if (!overlap) found.add((w to u) to m.range)
            }
        }
        return found.sortedBy { it.second.first }
    }

    private fun unitConvert(q: String): String? {
        val lower = q.lowercase(Locale.ROOT)
        if (!lower.contains("等于") && !lower.contains("换算") && !lower.contains("换成") &&
            !lower.contains("=") && !lower.contains("多少") && !lower.contains("怎么算")) return null
        val numM = Regex("""(\d+(?:\.\d+)?)""").find(q) ?: return null
        val value = numM.groupValues[1].toDouble()
        val numStart = numM.range.first
        val units = findUnits(q)
        val src = units.firstOrNull { it.second.first > numStart } ?: return null
        val srcWord = src.first.first
        val srcUnit = src.first.second
        val targetWord = units.firstOrNull { (u, range) -> u.second.name == srcUnit.name && u.first != srcWord && range.first > numStart }
            ?.first?.first
        // 防止误答：无“等于/换算/多少”意图且无同类目标单位时（“跑多少分钟”）不换算
        val afterUnitIdx = src.second.last + 1
        val tail = q.substring(afterUnitIdx).trimStart()
        val explicitConv = tail.startsWith("等于") || tail.startsWith("换算") || tail.startsWith("换成") ||
            tail.startsWith("=") || Regex("""^是?\s*(多少|几)""").containsMatchIn(tail)
        if (targetWord == null && !explicitConv) return null
        if (targetWord == null) {
            val base = UNITS.entries.firstOrNull { (w, u) -> u.name == srcUnit.name && u.factor == 1.0 }?.key
                ?: UNITS.entries.first { (_, u) -> u.name == srcUnit.name }.key
            return "${trimResult(value)} $srcWord = ${trimResult(value * srcUnit.factor)} $base"
        }
        val dst = UNITS[targetWord] ?: return null
        val converted = value * srcUnit.factor / dst.factor
        return "${trimResult(value)} $srcWord = ${trimResult(converted)} $targetWord"
    }

    // ---------- 星座 ----------

    private fun zodiac(q: String): String? {
        if (!q.contains("星座")) return null
        val m = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*日""").find(q) ?: return null
        val month = m.groupValues[1].toInt()
        val day = m.groupValues[2].toInt()
        val name = when {
            (month == 1 && day >= 20) || (month == 2 && day <= 18) -> "水瓶座"
            (month == 2 && day >= 19) || (month == 3 && day <= 20) -> "双鱼座"
            (month == 3 && day >= 21) || (month == 4 && day <= 19) -> "白羊座"
            (month == 4 && day >= 20) || (month == 5 && day <= 20) -> "金牛座"
            (month == 5 && day >= 21) || (month == 6 && day <= 21) -> "双子座"
            (month == 6 && day >= 22) || (month == 7 && day <= 22) -> "巨蟹座"
            (month == 7 && day >= 23) || (month == 8 && day <= 22) -> "狮子座"
            (month == 8 && day >= 23) || (month == 9 && day <= 22) -> "处女座"
            (month == 9 && day >= 23) || (month == 10 && day <= 23) -> "天秤座"
            (month == 10 && day >= 24) || (month == 11 && day <= 22) -> "天蝎座"
            (month == 11 && day >= 23) || (month == 12 && day <= 21) -> "射手座"
            else -> "摩羯座"
        }
        val range = when (name) {
            "水瓶座" -> "1月20日-2月18日"; "双鱼座" -> "2月19日-3月20日"; "白羊座" -> "3月21日-4月19日"
            "金牛座" -> "4月20日-5月20日"; "双子座" -> "5月21日-6月21日"; "巨蟹座" -> "6月22日-7月22日"
            "狮子座" -> "7月23日-8月22日"; "处女座" -> "8月23日-9月22日"; "天秤座" -> "9月23日-10月23日"
            "天蝎座" -> "10月24日-11月22日"; "射手座" -> "11月23日-12月21日"; else -> "12月22日-1月19日"
        }
        return "$month 月 $day 日出生是$name（日期范围 $range）"
    }

    // ---------- 二十四节气 ----------

    private val TERM_ORDER = listOf(
        "立春", "雨水", "惊蛰", "春分", "清明", "谷雨", "立夏", "小满", "芒种", "夏至", "小暑", "大暑",
        "立秋", "处暑", "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至", "小寒", "大寒"
    )
    private val TERM_DATES = mapOf(
        "立春" to "2月4日前后", "雨水" to "2月19日前后", "惊蛰" to "3月6日前后", "春分" to "3月21日前后",
        "清明" to "4月5日前后", "谷雨" to "4月20日前后", "立夏" to "5月6日前后", "小满" to "5月21日前后",
        "芒种" to "6月6日前后", "夏至" to "6月21日前后", "小暑" to "7月7日前后", "大暑" to "7月23日前后",
        "立秋" to "8月8日前后", "处暑" to "8月23日前后", "白露" to "9月8日前后", "秋分" to "9月23日前后",
        "寒露" to "10月8日前后", "霜降" to "10月23日前后", "立冬" to "11月7日前后", "小雪" to "11月22日前后",
        "大雪" to "12月7日前后", "冬至" to "12月22日前后", "小寒" to "1月6日前后", "大寒" to "1月20日前后"
    )

    private fun solarTerms(q: String): String? {
        val hit = TERM_ORDER.firstOrNull { q.contains(it) }
        if (hit != null) {
            val date = TERM_DATES[hit] ?: ""
            return "$hit：公历通常在 $date"
        }
        if (!q.contains("节气")) return null
        if (q.contains("有哪些") || q.contains("顺序") || q.contains("全部") || q.contains("几个")) {
            val list = TERM_ORDER.mapIndexed { i, name -> "${i + 1}. $name（${TERM_DATES[name]}）" }.joinToString(" ")
            return "二十四节气依次为：$list"
        }
        return null
    }

    // ---------- 传统节日（农历日期） ----------

    private val FESTIVALS = listOf(
        "春节" to "农历正月初一", "除夕" to "农历腊月三十（或廿九）", "元宵节" to "农历正月十五",
        "清明节" to "公历4月5日前后（4月4-6日）", "端午节" to "农历五月初五", "七夕" to "农历七月初七",
        "中元节" to "农历七月十五", "中秋节" to "农历八月十五", "重阳节" to "农历九月初九",
        "腊八节" to "农历腊月初八", "小年" to "农历腊月廿三"
    )

    private fun festival(q: String): String? =
        FESTIVALS.firstOrNull { (name, _) -> q.contains(name) }?.let { "${it.first}：${it.second}" }

    // ---------- 中国省份简称 ----------

    private val PROVINCES = listOf(
        "北京" to "京", "天津" to "津", "河北" to "冀", "山西" to "晋", "内蒙古" to "蒙",
        "辽宁" to "辽", "吉林" to "吉", "黑龙江" to "黑", "上海" to "沪", "江苏" to "苏",
        "浙江" to "浙", "安徽" to "皖", "福建" to "闽", "江西" to "赣", "山东" to "鲁",
        "河南" to "豫", "湖北" to "鄂", "湖南" to "湘", "广东" to "粤", "广西" to "桂",
        "海南" to "琼", "重庆" to "渝", "四川" to "川", "贵州" to "黔", "云南" to "滇",
        "西藏" to "藏", "陕西" to "陕", "甘肃" to "甘", "青海" to "青", "宁夏" to "宁",
        "新疆" to "新", "台湾" to "台", "香港" to "港", "澳门" to "澳"
    )

    private fun province(q: String): String? {
        if (q.contains("多少个省") || q.contains("几个省") || q.contains("有哪些省") || q.contains("省级行政区")) {
            return "中国现有 34 个省级行政区：23 个省、5 个自治区（新疆、西藏、广西、宁夏、内蒙古）、" +
                "4 个直辖市（北京、天津、上海、重庆）、2 个特别行政区（香港、澳门）。"
        }
        val isAbbrQ = q.contains("简称") || q.contains("缩写")
        if (!q.contains("省") && !isAbbrQ && !q.contains("自治区") && !q.contains("直辖市")) return null
        val hit = PROVINCES.firstOrNull { (name, _) -> q.contains(name) } ?: return null
        return "${hit.first} 的简称是「${hit.second}」"
    }

    // ---------- 化学元素 ----------

    private val ELEMENTS = listOf(
        "1 氢 H", "2 氦 He", "3 锂 Li", "4 铍 Be", "5 硼 B", "6 碳 C", "7 氮 N", "8 氧 O",
        "9 氟 F", "10 氖 Ne", "11 钠 Na", "12 镁 Mg", "13 铝 Al", "14 硅 Si", "15 磷 P",
        "16 硫 S", "17 氯 Cl", "18 氩 Ar", "19 钾 K", "20 钙 Ca", "21 钪 Sc", "22 钛 Ti",
        "23 钒 V", "24 铬 Cr", "25 锰 Mn", "26 铁 Fe", "27 钴 Co", "28 镍 Ni", "29 铜 Cu",
        "30 锌 Zn", "31 镓 Ga", "32 锗 Ge", "33 砷 As", "34 硒 Se", "35 溴 Br", "36 氪 Kr",
        "37 铷 Rb", "38 锶 Sr", "39 钇 Y", "40 锆 Zr", "41 铌 Nb", "42 钼 Mo", "43 锝 Tc",
        "44 钌 Ru", "45 铑 Rh", "46 钯 Pd", "47 银 Ag", "48 镉 Cd", "49 铟 In", "50 锡 Sn",
        "51 锑 Sb", "52 碲 Te", "53 碘 I", "54 氙 Xe", "55 铯 Cs", "56 钡 Ba", "57 镧 La",
        "58 铈 Ce", "59 镨 Pr", "60 钕 Nd", "61 钷 Pm", "62 钐 Sm", "63 铕 Eu", "64 钆 Gd",
        "65 铽 Tb", "66 镝 Dy", "67 钬 Ho", "68 铒 Er", "69 铥 Tm", "70 镱 Yb", "71 镥 Lu",
        "72 铪 Hf", "73 钽 Ta", "74 钨 W", "75 铼 Re", "76 锇 Os", "77 铱 Ir", "78 铂 Pt",
        "79 金 Au", "80 汞 Hg", "81 铊 Tl", "82 铅 Pb", "83 铋 Bi", "84 钋 Po", "85 砹 At",
        "86 氡 Rn", "87 钫 Fr", "88 镭 Ra", "89 锕 Ac", "90 钍 Th", "91 镤 Pa", "92 铀 U",
        "93 镎 Np", "94 钚 Pu", "95 镅 Am", "96 锔 Cm", "97 锫 Bk", "98 锎 Cf", "99 锿 Es",
        "100 镄 Fm", "101 钔 Md", "102 锘 No", "103 铹 Lr", "104 Rf", "105 Db", "106 Sg",
        "107 Bh", "108 Hs", "109 Mt", "110 Ds", "111 Rg", "112 Cn", "113 Nh", "114 Fl",
        "115 Mc", "116 Lv", "117 Ts", "118 Og"
    )

    private fun element(q: String): String? {
        if (!q.contains("元素") && !q.contains("周期表") && !q.contains("化学符号") && !q.contains("原子序")) return null
        val hit = ELEMENTS.firstOrNull { row ->
            val parts = row.split(" ")
            parts.size == 3 && q.contains(parts[1])
        }
        if (hit != null) {
            val parts = hit.split(' ')
            return "${parts[1]}元素的化学符号是 ${parts[2]}，原子序数 ${parts[0]}"
        }
        if (q.contains("第1个") || q.contains("第一个")) return "周期表第一个元素：氢（H），原子序数 1"
        return null
    }

    // ---------- 数学常数 ----------

    private fun constants(q: String): String? {
        val lower = q.lowercase(Locale.ROOT)
        if ((lower.contains("圆周率") || lower.contains("π") || lower.contains("派")) &&
            (lower.contains("多少") || lower.contains("等于") || lower.contains("是"))) {
            return "圆周率 π ≈ 3.14159265358979"
        }
        if (lower.contains("自然常数") || lower.contains("自然对数的底")) {
            return "自然常数 e ≈ 2.718281828459"
        }
        return null
    }

    // ---------- 数字格式化 ----------

    private fun trimResult(v: Double): String = formatResult(v)

    private fun formatResult(v: Double): String {
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
            return v.toLong().toString()
        }
        return String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
    }
}




