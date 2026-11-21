package com.personal.lifelog.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Whitelist-only v1 daily AI review payload. Never contains IDs, paths, hashes, photos or free-text notes. */
data class DailyReviewPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val selectedDate: String,
    val timeZone: String,
    val snapshotAt: String,
    val hasAnyRecord: Boolean,
    val sleep: DailyReviewIntervalSection,
    val work: DailyReviewIntervalSection,
    val meals: DailyReviewMealsSection,
    val workouts: DailyReviewWorkoutsSection,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

data class DailyReviewIntervalSection(
    val targetMinutes: Int?,
    val completedSegmentCount: Int,
    /** Raw millisecond sum of completed segments floored once to minutes; null when no completed segments exist. */
    val completedDurationMinutes: Int?,
    val hasActiveSegment: Boolean,
    val activeStartedAt: String?,
    val completedSegments: List<DailyReviewCompletedSegment>,
)

data class DailyReviewCompletedSegment(
    val startedAt: String,
    val endedAt: String,
    val durationMinutes: Int,
)

data class DailyReviewMealsSection(
    val recordCount: Int,
    val photoOnlyRecordCount: Int,
    val totalPhotoCount: Int,
    val records: List<DailyReviewMealItem>,
)

data class DailyReviewMealItem(
    val occurredAt: String,
    val hasText: Boolean,
    val photoCount: Int,
    val satiety: String?,
)

data class DailyReviewWorkoutsSection(
    val sessionCount: Int,
    val itemCount: Int,
    val sessions: List<DailyReviewWorkoutSession>,
)

data class DailyReviewWorkoutSession(
    val occurredAt: String,
    val effort: String?,
    val itemCount: Int,
    val items: List<DailyReviewWorkoutItem>,
)

data class DailyReviewWorkoutItem(
    val name: String,
    val type: String,
    val durationSeconds: Long?,
    val distanceMeters: Long?,
    val setCount: Int,
    val sets: List<DailyReviewWorkoutSet>,
)

data class DailyReviewWorkoutSet(
    val weightGrams: Long?,
    val repetitions: Int?,
)

/**
 * Frozen in-memory snapshot produced from a single Room read transaction.
 * [rawJson] is the exact string rendered in preview and reused by future send/retry without re-querying Room.
 */
data class DailyReviewSnapshot(
    val payload: DailyReviewPayload,
    val rawJson: String,
)

interface DailyReviewSource {
    suspend fun generate(date: LocalDate): DataResult<DailyReviewSnapshot>
    /** First emission confirms registration. Each later emission invalidates that observation's snapshot. */
    fun observeChanges(): Flow<Unit>
}

/**
 * Explicit whitelist JSON encoder for v1 daily review payloads.
 * Preserves explicit nulls, empty arrays, stable order, and clean IANA timeZone strings without escaping '/'.
 */
object DailyReviewJson {
    fun encode(payload: DailyReviewPayload): String = buildString {
        appendLine("{")
        field(1, "schemaVersion", payload.schemaVersion.toString(), comma = true)
        field(1, "selectedDate", quote(payload.selectedDate), comma = true)
        field(1, "timeZone", quote(payload.timeZone), comma = true)
        field(1, "snapshotAt", quote(payload.snapshotAt), comma = true)
        field(1, "hasAnyRecord", payload.hasAnyRecord.toString(), comma = true)
        appendIntervalSection("sleep", payload.sleep, comma = true)
        appendIntervalSection("work", payload.work, comma = true)
        appendMealsSection(payload.meals, comma = true)
        appendWorkoutsSection(payload.workouts, comma = false)
        append("}")
    }

    private fun StringBuilder.appendIntervalSection(
        name: String,
        section: DailyReviewIntervalSection,
        comma: Boolean,
    ) {
        indent(1).append(quote(name)).appendLine(": {")
        field(2, "targetMinutes", section.targetMinutes?.toString() ?: "null", comma = true)
        field(2, "completedSegmentCount", section.completedSegmentCount.toString(), comma = true)
        field(2, "completedDurationMinutes", section.completedDurationMinutes?.toString() ?: "null", comma = true)
        field(2, "hasActiveSegment", section.hasActiveSegment.toString(), comma = true)
        field(2, "activeStartedAt", section.activeStartedAt?.let(::quote) ?: "null", comma = true)
        if (section.completedSegments.isEmpty()) {
            field(2, "completedSegments", "[]", comma = false)
        } else {
            indent(2).append(quote("completedSegments")).appendLine(": [")
            section.completedSegments.forEachIndexed { index, segment ->
                indent(3).appendLine("{")
                field(4, "startedAt", quote(segment.startedAt), comma = true)
                field(4, "endedAt", quote(segment.endedAt), comma = true)
                field(4, "durationMinutes", segment.durationMinutes.toString(), comma = false)
                indent(3).append("}").appendLine(if (index < section.completedSegments.lastIndex) "," else "")
            }
            indent(2).appendLine("]")
        }
        indent(1).append("}").appendLine(if (comma) "," else "")
    }

    private fun StringBuilder.appendMealsSection(section: DailyReviewMealsSection, comma: Boolean) {
        indent(1).append(quote("meals")).appendLine(": {")
        field(2, "recordCount", section.recordCount.toString(), comma = true)
        field(2, "photoOnlyRecordCount", section.photoOnlyRecordCount.toString(), comma = true)
        field(2, "totalPhotoCount", section.totalPhotoCount.toString(), comma = true)
        if (section.records.isEmpty()) {
            field(2, "records", "[]", comma = false)
        } else {
            indent(2).append(quote("records")).appendLine(": [")
            section.records.forEachIndexed { index, record ->
                indent(3).appendLine("{")
                field(4, "occurredAt", quote(record.occurredAt), comma = true)
                field(4, "hasText", record.hasText.toString(), comma = true)
                field(4, "photoCount", record.photoCount.toString(), comma = true)
                field(4, "satiety", record.satiety?.let(::quote) ?: "null", comma = false)
                indent(3).append("}").appendLine(if (index < section.records.lastIndex) "," else "")
            }
            indent(2).appendLine("]")
        }
        indent(1).append("}").appendLine(if (comma) "," else "")
    }

    private fun StringBuilder.appendWorkoutsSection(section: DailyReviewWorkoutsSection, comma: Boolean) {
        indent(1).append(quote("workouts")).appendLine(": {")
        field(2, "sessionCount", section.sessionCount.toString(), comma = true)
        field(2, "itemCount", section.itemCount.toString(), comma = true)
        if (section.sessions.isEmpty()) {
            field(2, "sessions", "[]", comma = false)
        } else {
            indent(2).append(quote("sessions")).appendLine(": [")
            section.sessions.forEachIndexed { sIndex, session ->
                indent(3).appendLine("{")
                field(4, "occurredAt", quote(session.occurredAt), comma = true)
                field(4, "effort", session.effort?.let(::quote) ?: "null", comma = true)
                field(4, "itemCount", session.itemCount.toString(), comma = true)
                if (session.items.isEmpty()) {
                    field(4, "items", "[]", comma = false)
                } else {
                    indent(4).append(quote("items")).appendLine(": [")
                    session.items.forEachIndexed { iIndex, item ->
                        indent(5).appendLine("{")
                        field(6, "name", quote(item.name), comma = true)
                        field(6, "type", quote(item.type), comma = true)
                        field(6, "durationSeconds", item.durationSeconds?.toString() ?: "null", comma = true)
                        field(6, "distanceMeters", item.distanceMeters?.toString() ?: "null", comma = true)
                        field(6, "setCount", item.setCount.toString(), comma = true)
                        if (item.sets.isEmpty()) {
                            field(6, "sets", "[]", comma = false)
                        } else {
                            indent(6).append(quote("sets")).appendLine(": [")
                            item.sets.forEachIndexed { setIndex, set ->
                                indent(7).appendLine("{")
                                field(8, "weightGrams", set.weightGrams?.toString() ?: "null", comma = true)
                                field(8, "repetitions", set.repetitions?.toString() ?: "null", comma = false)
                                indent(7).append("}").appendLine(if (setIndex < item.sets.lastIndex) "," else "")
                            }
                            indent(6).appendLine("]")
                        }
                        indent(5).append("}").appendLine(if (iIndex < session.items.lastIndex) "," else "")
                    }
                    indent(4).appendLine("]")
                }
                indent(3).append("}").appendLine(if (sIndex < section.sessions.lastIndex) "," else "")
            }
            indent(2).appendLine("]")
        }
        indent(1).append("}").appendLine(if (comma) "," else "")
    }

    private fun StringBuilder.indent(level: Int): StringBuilder {
        repeat(level) { append("  ") }
        return this
    }

    private fun StringBuilder.field(level: Int, key: String, encodedValue: String, comma: Boolean) {
        indent(level).append(quote(key)).append(": ").append(encodedValue).appendLine(if (comma) "," else "")
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
