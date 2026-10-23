package com.personal.lifelog.feature.workout

import android.os.Bundle
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.core.validation.Units
import com.personal.lifelog.data.local.entity.WorkoutWithItems
import com.personal.lifelog.domain.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** UI draft only. Stable item/set IDs and historical snapshots survive recreation. */
data class WorkoutItemForm(
    val id: String,
    val exerciseId: String,
    val name: String,
    val type: ExerciseType,
    val minutes: String = "",
    val seconds: String = "",
    val kilometers: String = "",
    val note: String = "",
    // T09 edits this existing per-set model; basic edits must never discard it.
    val sets: List<WorkoutSetDraft> = emptyList(),
) {
    fun draft(): WorkoutItemDraft {
        val duration = if (minutes.isBlank() && seconds.isBlank()) null else {
            val mins = if (minutes.trim().matches(Regex("(?:0+(?:\\.0*)?|\\.0+)"))) 0L
                else Units.minutesToSeconds(minutes) ?: 0L
            val secs = if (seconds.isBlank()) 0L else seconds.trim().let {
                if (!it.matches(Regex("[0-9]{1,2}"))) reject(ErrorCode.INVALID_NUMBER)
                it.toLong().also { value -> if (value !in 0..59) reject(ErrorCode.INVALID_NUMBER) }
            }
            val total = try { Math.addExact(mins, secs) } catch (_: ArithmeticException) { reject(ErrorCode.INVALID_NUMBER) }
            Rules.positive(total, "durationSeconds")
        }
        return WorkoutItemDraft(id, exerciseId, duration, Units.kilometersToMeters(kilometers),
            Rules.text(note, Rules.NOTE_LIMIT), sets)
    }
}

data class WorkoutForm(
    val id: String,
    val localDateTime: String,
    val zoneId: String,
    val offset: String,
    val note: String = "",
    val items: List<WorkoutItemForm> = emptyList(),
) {
    val local: LocalDateTime get() = LocalDateTime.parse(localDateTime)
    fun draft() = WorkoutDraft(id,
        RecordTime.resolve(local, zoneId, offset.takeIf(String::isNotEmpty)?.let(ZoneOffset::of)),
        zoneId, items.map { it.draft() }, Rules.text(note, Rules.NOTE_LIMIT))

    fun atLocal(value: LocalDateTime) = copy(localDateTime = value.toString(), offset = "")

    fun saved() = Bundle().apply {
        putString("id", id); putString("local", localDateTime); putString("zone", zoneId)
        putString("offset", offset); putString("note", note)
        putParcelableArrayList("items", ArrayList(items.map { row -> Bundle().apply {
            putString("id", row.id); putString("exercise", row.exerciseId); putString("name", row.name)
            putString("type", row.type.name); putString("minutes", row.minutes); putString("seconds", row.seconds)
            putString("km", row.kilometers); putString("note", row.note)
            putParcelableArrayList("sets", ArrayList(row.sets.map { set -> Bundle().apply {
                putString("id", set.id)
                set.weightGrams?.let { putLong("weight", it) }
                set.repetitions?.let { putInt("reps", it) }
            } }))
        } }))
    }

    companion object {
        fun at(id: String, utcMs: Long, zoneId: String): WorkoutForm {
            val time = Instant.ofEpochMilli(utcMs).atZone(ZoneId.of(zoneId))
            return WorkoutForm(id, time.toLocalDateTime().toString(), zoneId, time.offset.id)
        }
        fun from(record: WorkoutWithItems): WorkoutForm = at(record.session.id,
            record.session.occurredAtUtcMs, record.session.zoneId).copy(note = record.session.note.orEmpty(),
            items = record.sorted().items.map { row ->
                val item = row.item
                WorkoutItemForm(item.id, item.exerciseId, item.nameSnapshot, item.typeSnapshot,
                    item.durationSeconds?.let { if (it < 60) "" else (it / 60).toString() }.orEmpty(),
                    item.durationSeconds?.let { if (it % 60 == 0L) "" else (it % 60).toString() }.orEmpty(),
                    item.distanceMeters?.let { BigDecimal.valueOf(it, 3).stripTrailingZeros().toPlainString() }.orEmpty(),
                    item.note.orEmpty(), row.sets.map { WorkoutSetDraft(it.id, it.weightGrams, it.repetitions) })
            })

        @Suppress("DEPRECATION")
        fun restore(bundle: Bundle): WorkoutForm = WorkoutForm(
            bundle.getString("id")!!, bundle.getString("local")!!, bundle.getString("zone")!!,
            bundle.getString("offset")!!, bundle.getString("note")!!,
            bundle.getParcelableArrayList<Bundle>("items").orEmpty().map { row ->
                WorkoutItemForm(row.getString("id")!!, row.getString("exercise")!!, row.getString("name")!!,
                    ExerciseType.valueOf(row.getString("type")!!), row.getString("minutes")!!,
                    row.getString("seconds")!!, row.getString("km")!!, row.getString("note")!!,
                    row.getParcelableArrayList<Bundle>("sets").orEmpty().map { set ->
                        WorkoutSetDraft(set.getString("id")!!,
                            if (set.containsKey("weight")) set.getLong("weight") else null,
                            if (set.containsKey("reps")) set.getInt("reps") else null)
                    })
            })
    }
}
