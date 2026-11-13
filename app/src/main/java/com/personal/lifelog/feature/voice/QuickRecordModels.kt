package com.personal.lifelog.feature.voice

/** Transient preview fields. These enum names are saved-state keys, never database columns. */
enum class QuickKind(val label: String) { SLEEP("睡眠"), WORK("工作"), MEAL("饮食"), WORKOUT("运动") }
enum class QuickAction(val label: String) { RECORD("补录已完成记录"), START("开始"), FINISH("结束进行中的记录") }
enum class QuickField { KIND, ACTION, START, END, DURATION, CONTENT, EXERCISE, DISTANCE, SETS, REPS, WEIGHT }

data class QuickDraft(val values: Map<QuickField, String> = emptyMap()) {
    operator fun get(field: QuickField): String = values[field].orEmpty()
    fun with(field: QuickField, value: String) = copy(values = values + (field to value))
    val kind get() = runCatching { QuickKind.valueOf(get(QuickField.KIND)) }.getOrNull()
    val action get() = runCatching { QuickAction.valueOf(get(QuickField.ACTION)) }.getOrDefault(QuickAction.RECORD)
    val start get() = get(QuickField.START).toLongOrNull()
    val end get() = get(QuickField.END).toLongOrNull()
}

data class QuickParseResult(val draft: QuickDraft, val notices: List<String> = emptyList(), val multiple: Boolean = false)
