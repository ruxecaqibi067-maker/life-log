package com.personal.lifelog.feature.voice

import com.personal.lifelog.data.local.entity.ExerciseDefinition
import java.math.BigDecimal
import java.time.*

/** Deliberately bounded local grammar. Unknown or ambiguous information remains visible/missing. */
internal object QuickRecordParser {
    private val clockPattern = Regex("(?:(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚间))?(\\d{1,2})(?:[:：](\\d{1,2})|点(?:(\\d{1,2})分?|([半一三]刻|半))?)(?![\\d])")
    private val datePattern = Regex("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})[日号]?")
    fun parse(raw: String, reference: Instant, zone: ZoneId, exercises: List<ExerciseDefinition>, forcedKind: QuickKind? = null): QuickParseResult {
        val text = numbers(raw).replace(" ", "")
        val values = mutableMapOf<QuickField, String>()
        val notices = mutableListOf<String>()
        val available = exercises.filter { !it.isHidden }
        val occurrences = available.flatMap { exercise -> Regex(Regex.escape(exercise.name)).findAll(text).map { exercise to it.range }.toList() }
        val matches = occurrences.filter { (_, range) -> occurrences.none { (_, other) -> other != range && other.first <= range.first && other.last >= range.last } }.map { it.first }.distinctBy { it.id }
            .ifEmpty { if (Regex("慢跑|跑了").containsMatchIn(text)) available.filter { it.name == "跑步" } else emptyList() }
        val kinds = buildList {
            if (Regex("睡了|睡觉|睡眠|入睡|起床|刚醒|我醒了|准备睡|开始睡").containsMatchIn(text)) add(QuickKind.SLEEP)
            if (Regex("工作|上班|下班|加班").containsMatchIn(text)) add(QuickKind.WORK)
            if (Regex("吃了|吃的|吃饭|喝了|饮食|早餐|午餐|晚餐|早饭|午饭|晚饭").containsMatchIn(text)) add(QuickKind.MEAL)
            if (matches.isNotEmpty() || Regex("运动|训练|跑了|跑步|慢跑").containsMatchIn(text)) add(QuickKind.WORKOUT)
        }.distinct()
        val kind = forcedKind ?: kinds.singleOrNull()
        kind?.let { values[QuickField.KIND] = it.name }
        val repeated = listOf("睡了", "工作了", "吃了").any { word -> Regex(word).findAll(text).count() > 1 } ||
            Regex("早餐|午餐|晚餐|早饭|午饭|晚饭").findAll(text).count() > 1
        val multiple = repeated || kinds.size > 1 || matches.map { it.id }.distinct().size > 1 ||
            Regex("(?:睡|工作|吃|跑).*(?:又|然后|另外|还).*(?:睡|工作|吃|跑)").containsMatchIn(text) ||
            Regex("(?:分开|两次|两段|分别)").containsMatchIn(text)
        if (multiple) notices += "检测到多条记录，请拆开输入；第一版一次保存一条"
        if (kind == null) notices += "未能确定记录类型，请选择睡眠、工作、饮食或运动"
        val clocks = clockPattern.findAll(text).toList()
        val withoutClocks = clocks.asReversed().fold(text) { s, m -> s.removeRange(m.range) }
        val minutes = duration(withoutClocks)
        minutes?.let { values[QuickField.DURATION] = decimal(it) }
        val interval = kind in setOf(QuickKind.SLEEP, QuickKind.WORK)
        val startCommand = minutes == null && clocks.size < 2 && when (kind) {
            QuickKind.SLEEP -> Regex("准备睡|开始睡|现在睡觉|我要睡了").containsMatchIn(text)
            QuickKind.WORK -> Regex("开始工作|开始上班|现在上班").containsMatchIn(text)
            else -> false
        }
        val finishCommand = minutes == null && clocks.size < 2 && when (kind) {
            QuickKind.SLEEP -> Regex("我醒了|刚醒|起床了|结束睡眠").containsMatchIn(text)
            QuickKind.WORK -> Regex("结束工作|刚结束工作|下班了|停止工作").containsMatchIn(text)
            else -> false
        }
        val action = if (startCommand) QuickAction.START else if (finishCommand) QuickAction.FINISH else QuickAction.RECORD
        values[QuickField.ACTION] = action.name
        val now = reference.atZone(zone)
        data class Point(val instant: Long?, val date: LocalDate, val explicitDate: Boolean, val period: String)
        var previousDate: LocalDate? = null
        var previousPeriod = ""
        val points = clocks.mapIndexed { index, m ->
            val context = text.substring(if (index == 0) 0 else clocks[index - 1].range.last + 1, m.range.first)
            val foundDate = datePattern.findAll(context).lastOrNull()
            val relative = Regex("前天|昨天|昨晚|今天|今晚|今早|昨日|今日|明天|后天|次日|第二天").findAll(context).lastOrNull()?.value
            var valid = true
            val date = try {
                when {
                    foundDate != null -> LocalDate.of(foundDate.groupValues[1].toIntOrNull() ?: now.year, foundDate.groupValues[2].toInt(), foundDate.groupValues[3].toInt())
                    relative == "明天" -> now.toLocalDate().plusDays(1)
                    relative == "后天" -> now.toLocalDate().plusDays(2)
                    relative == "前天" -> now.toLocalDate().minusDays(2)
                    relative in listOf("昨天", "昨晚", "昨日") -> now.toLocalDate().minusDays(1)
                    relative in listOf("次日", "第二天") -> (previousDate ?: now.toLocalDate()).plusDays(1)
                    relative != null -> now.toLocalDate()
                    else -> previousDate ?: now.toLocalDate()
                }
            } catch (_: DateTimeException) { valid = false; notices += "日期无效，请手动选择正确日期"; now.toLocalDate() }
            var period = m.groupValues[1]
            if (period.isEmpty() && relative == "昨晚") period = "晚上"
            if (period.isEmpty() && relative == null && foundDate == null && index > 0 && previousPeriod in listOf("上午", "下午", "凌晨", "晚上")) period = previousPeriod
            val hourRaw = m.groupValues[2].toInt()
            val minute = m.groupValues[3].toIntOrNull() ?: m.groupValues[4].toIntOrNull() ?: when (m.groupValues[5]) {
                "半" -> 30; "一刻" -> 15; "三刻" -> 45; else -> 0
            }
            val hour = when {
                period in listOf("下午", "傍晚", "晚上", "晚间") && hourRaw in 1..11 -> hourRaw + 12
                period == "中午" && hourRaw in 1..10 -> hourRaw + 12
                period == "凌晨" && hourRaw == 12 -> 0
                else -> hourRaw
            }
            val explicitDate = relative != null || foundDate != null
            // Bare 1..12 is ambiguous unless the phrase supplied a date and a 24h-style range.
            val ambiguous = period.isEmpty() && hourRaw in 1..12 && !explicitDate && index == 0
            if (ambiguous) notices += "“${m.value}”缺少日期或上午/下午，请在预览中补全"
            var actualDate = date
            if (index > 0 && !explicitDate && period in listOf("凌晨", "早上", "早晨", "上午") && previousPeriod in listOf("晚上", "晚间")) actualDate = date.plusDays(1)
            val instant = if (!valid || ambiguous) null else try {
                val local = actualDate.atTime(hour, minute)
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.size != 1) { notices += "该时间不存在或有重复时段，请用时间滚轮确认"; null }
                else local.toInstant(offsets.single()).toEpochMilli()
            } catch (_: DateTimeException) { notices += "钟点无效，请手动修改"; null }
            previousDate = actualDate; previousPeriod = period
            Point(instant, actualDate, explicitDate, period)
        }
        if (clocks.size > 2) notices += "识别到多个时间点，请分开录入并检查预览"
        if (interval) {
            if (action == QuickAction.START) {
                (points.firstOrNull()?.instant ?: reference.toEpochMilli().takeIf { points.isEmpty() && !datePattern.containsMatchIn(text) && !Regex("昨天|前天|昨晚|明天|后天|上午|下午|凌晨|晚上").containsMatchIn(text) })?.let { values[QuickField.START] = it.toString() }
            } else if (points.size >= 2) {
                points[0].instant?.let { values[QuickField.START] = it.toString() }
                points[1].instant?.let { values[QuickField.END] = it.toString() }
            } else if (points.size == 1) {
                val isEnd = Regex("刚醒|起床|结束|到现在|到.*(?:点|:)").containsMatchIn(text) && !Regex("从.*(?:点|:)").containsMatchIn(text)
                points[0].instant?.let { values[if (isEnd) QuickField.END else QuickField.START] = it.toString() }
            }
            if (points.isEmpty() && (action == QuickAction.FINISH || Regex("刚醒|刚结束|到现在|刚起床").containsMatchIn(text))) {
                values[QuickField.END] = reference.toEpochMilli().toString()
                notices += "结束时间建议为本次录入时刻，请核对后保存"
            }
            if (minutes != null && action == QuickAction.RECORD) {
                val ms = runCatching { minutes.multiply(BigDecimal(60000)).longValueExact() }.getOrNull()
                if (ms != null) {
                    val start = values[QuickField.START]?.toLongOrNull(); val end = values[QuickField.END]?.toLongOrNull()
                    if (start != null && end == null && points.none { it.instant == null }) values[QuickField.END] = (start + ms).toString()
                    if (end != null && start == null && points.none { it.instant == null }) values[QuickField.START] = (end - ms).toString()
                }
            }
            if (values[QuickField.START].isNullOrEmpty() && action != QuickAction.FINISH) notices += "请补一个明确时间点；不会仅凭时长倒推到现在"
        } else {
            if (points.isEmpty()) {
                if (datePattern.containsMatchIn(text) || Regex("昨天|前天|昨晚|明天|后天|今晚|今早|凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|上周|星期|周[一二三四五六日天]|上个月").containsMatchIn(text)) notices += "已提及日期或时段，请补全记录钟点"
                else { values[QuickField.START] = reference.toEpochMilli().toString(); notices += "未说记录时间，预览采用本次录入时刻，可修改" }
            } else points.first().instant?.let { values[QuickField.START] = it.toString() }
        }
        if (kind == QuickKind.MEAL) {
            val marker = Regex("吃了|喝了|吃的是|吃的|吃饭|早餐|午餐|晚餐|早饭|午饭|晚饭").find(raw)
            values[QuickField.CONTENT] = if (marker == null) raw.trim() else raw.substring(marker.range.last + 1).trim('，', '。', ',', '：', ':').removePrefix("吃了")
        }
        if (kind == QuickKind.WORKOUT) {
            val matching = matches.singleOrNull()
            matching?.let { values[QuickField.EXERCISE] = it.id }
            if (matching == null) notices += "请选择一个现有运动项目，未自动创建项目"
            Regex("(\\d+(?:\\.\\d+)?)公里|(?:跑了|距离)?(\\d+(?:\\.\\d+)?)千米").find(text)?.let {
                values[QuickField.DISTANCE] = it.groupValues[1].ifEmpty { it.groupValues[2] }
            }
            Regex("(\\d+(?:\\.\\d+)?)米").find(text)?.let { if (QuickField.DISTANCE !in values) values[QuickField.DISTANCE] = decimal(BigDecimal(it.groupValues[1]).divide(BigDecimal(1000))) }
            Regex("(\\d+)组").find(text)?.let { values[QuickField.SETS] = it.groupValues[1] }
            Regex("(\\d+)次").find(text)?.let { values[QuickField.REPS] = it.groupValues[1] }
            Regex("(\\d+(?:\\.\\d+)?)(?:公斤|千克)").find(text)?.let { values[QuickField.WEIGHT] = it.groupValues[1] }
            values[QuickField.CONTENT] = raw.trim()
        }
        val multipleTimes = if (interval) clocks.size > 2 else clocks.size > 1
        if (multipleTimes) notices += "识别到多个记录时点，请拆开录入"
        return QuickParseResult(QuickDraft(values), notices.distinct(), multiple || multipleTimes)
    }
    private fun duration(text: String): BigDecimal? {
        var total = BigDecimal.ZERO
        var found = false
        val hour = Regex("(\\d+(?:\\.\\d+)?)(?:个)?(半)?(?:小时|钟头)(半)?|半(?:个)?小时")
        hour.findAll(text).forEach {
            found = true
            val amount = it.groupValues[1].toBigDecimalOrNull() ?: BigDecimal("0.5")
            total += (amount + if (it.groupValues[2].isNotEmpty() || it.groupValues[3].isNotEmpty()) BigDecimal("0.5") else BigDecimal.ZERO) * BigDecimal(60)
        }
        Regex("(\\d+(?:\\.\\d+)?)(?:分钟|分)(?!钟头)").findAll(text).forEach { found = true; total += BigDecimal(it.groupValues[1]) }
        return total.takeIf { found }
    }
    private fun decimal(n: BigDecimal) = n.stripTrailingZeros().toPlainString()
    internal fun numbers(raw: String): String {
        // A clock's 点 separates hours and minutes; preserve it before decimal normalization.
        val clockText = Regex("([零〇一二两三四五六七八九十百千万0-9]+)点([零〇一二两三四五六七八九十百千万0-9]+)分(?!钟)")
            .replace(raw) { match ->
                val hour = match.groupValues[1].toIntOrNull() ?: chineseInteger(match.groupValues[1])
                val minute = match.groupValues[2].toIntOrNull() ?: chineseInteger(match.groupValues[2])
                "$hour:$minute"
            }
        return Regex("[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二两三四五六七八九]+)?(?=(?:个)?(?:半)?(?:小时|钟头|分钟|分|点|年|月|日|号|公里|千米|米|公斤|千克|组|次))")
        .replace(clockText) { match ->
            val parts = match.value.split('点')
            val integer = chineseInteger(parts[0]).toString()
            if (parts.size == 1) integer else integer + "." + parts[1].map { digit(it) }.joinToString("")
        }
    }
    private fun digit(c: Char) = when(c) { '零', '〇' -> 0; '一' -> 1; '二', '两' -> 2; '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9; else -> 0 }
    private fun chineseInteger(s: String): Int {
        if (s.none { it in "十百千万" }) return s.map { digit(it) }.joinToString("").toIntOrNull() ?: 0
        var total = 0; var current = 0
        s.forEach { c ->
            val unit = when(c) { '十' -> 10; '百' -> 100; '千' -> 1000; '万' -> 10000; else -> 0 }
            if (unit == 0) current = digit(c) else { total += (if (current == 0) 1 else current) * unit; current = 0 }
        }
        return total + current
    }
}
