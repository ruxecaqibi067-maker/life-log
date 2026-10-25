package com.personal.lifelog.data.repository

import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.core.validation.TodoRules
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.HabitRecord
import com.personal.lifelog.data.local.entity.TodoRecord
import com.personal.lifelog.domain.*
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class TodoRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
    private val zoneIdProvider: () -> ZoneId,
) {
    fun today(): LocalDate = LocalDate.now(clock.withZone(zoneIdProvider()))
    fun observeOn(date: LocalDate) = db.todos().observeOn(TodoRules.date(date).toString())
    fun observeHabits() = db.todos().observeHabits()
    fun observeOngoingHabits(date: LocalDate) = observeHabits().map { rows -> rows.filter { it.isOngoingOn(date) } }
    suspend fun todo(id: String) = db.todos().todo(Rules.id(id))?.takeIf { it.deletedAtUtcMs == null }
    suspend fun habit(id: String) = db.todos().habit(Rules.id(id))

    suspend fun saveTodo(draft: TodoDraft): DataResult<TodoRecord> = write(db, writes) {
        Rules.id(draft.id)
        val title = TodoRules.title(draft.title)
        val date = TodoRules.date(draft.date).toString()
        val minute = TodoRules.minute(draft.scheduledMinute)
        val old = db.todos().todo(draft.id)
        if (old?.deletedAtUtcMs != null) reject(ErrorCode.NOT_FOUND)
        val now = clock.millis()
        val row = if (old == null) TodoRecord(draft.id, title, date, minute,
            null, null, null, null, now, now)
        else old.copy(title = title, localDate = date, scheduledMinute = minute,
            updatedAtUtcMs = updatedAt(now, old.createdAtUtcMs, old.updatedAtUtcMs))
        if (old == null) db.todos().insertTodo(row) else db.todos().updateTodo(row)
        row
    }

    /** Explicit desired state makes retries and rapid repeated taps idempotent. */
    suspend fun setCompleted(id: String, completed: Boolean): DataResult<TodoRecord> = write(db, writes) {
        val old = visibleTodo(id)
        if (old.isCompleted == completed) old else old.copy(
            completedAtUtcMs = if (completed) maxOf(clock.millis(), old.createdAtUtcMs) else null,
            updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs),
        ).also { db.todos().updateTodo(it) }
    }

    suspend fun deleteTodo(id: String): DataResult<Boolean> = write(db, writes) {
        val old = db.todos().todo(Rules.id(id))
        if (old == null || old.deletedAtUtcMs != null) false else {
            db.todos().updateTodo(old.copy(deletedAtUtcMs = maxOf(clock.millis(), old.createdAtUtcMs),
                updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs)))
            true
        }
    }

    suspend fun saveHabit(draft: HabitDraft): DataResult<HabitRecord> = write(db, writes) {
        Rules.id(draft.id)
        val old = db.todos().habit(draft.id)
        if (old?.status == HabitStatus.ARCHIVED) reject(ErrorCode.NOT_FOUND)
        val now = clock.millis()
        val row = HabitRecord(draft.id, TodoRules.title(draft.title), TodoRules.date(draft.startDate).toString(),
            TodoRules.minute(draft.scheduledMinute), draft.frequency,
            if (draft.frequency == HabitFrequency.INTERVAL) TodoRules.interval(draft.intervalDays) else 1,
            if (draft.frequency == HabitFrequency.WEEKLY) TodoRules.weekdays(draft.weekdaysMask) else 0,
            old?.status ?: HabitStatus.ACTIVE, old?.createdAtUtcMs ?: now,
            updatedAt(now, old?.createdAtUtcMs ?: now, old?.updatedAtUtcMs ?: now))
        if (old == null) db.todos().insertHabit(row) else db.todos().updateHabit(row)
        row
    }

    suspend fun setHabitStatus(id: String, status: HabitStatus): DataResult<HabitRecord> = write(db, writes) {
        val old = db.todos().habit(Rules.id(id)) ?: reject(ErrorCode.NOT_FOUND)
        if (old.status == status) old else {
            if (old.status == HabitStatus.ARCHIVED) reject(ErrorCode.NOT_FOUND)
            old.copy(status = status, updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
                .also { db.todos().updateHabit(it) }
        }
    }

    /** Screen-driven, bounded to one current local date. No missed-day backfill or future pre-generation. */
    suspend fun generateFor(date: LocalDate = today()): DataResult<Int> = write(db, writes) {
        TodoRules.date(date)
        if (date != today()) 0 else {
            val key = date.toString()
            val now = clock.millis()
            var count = 0
            db.todos().allHabits().filter { it.isDueOn(date) }.forEach { habit ->
                if (db.todos().generated(habit.id, key) == null) {
                    val id = UUID.nameUUIDFromBytes("habit:${habit.id}:$key".toByteArray(Charsets.UTF_8)).toString()
                    val row = TodoRecord(id, habit.title, key, habit.scheduledMinute, habit.id, key,
                        null, null, now, now)
                    db.todos().insertTodo(row)
                    count++
                }
            }
            count
        }
    }

    private suspend fun visibleTodo(id: String): TodoRecord = db.todos().todo(Rules.id(id))
        ?.takeIf { it.deletedAtUtcMs == null } ?: reject(ErrorCode.NOT_FOUND)
}
