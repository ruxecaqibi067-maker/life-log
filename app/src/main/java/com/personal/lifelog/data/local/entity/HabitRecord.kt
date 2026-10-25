package com.personal.lifelog.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.personal.lifelog.core.validation.TodoRules
import com.personal.lifelog.domain.HabitFrequency
import com.personal.lifelog.domain.HabitStatus
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Entity(tableName = "HabitRecord")
data class HabitRecord(
    @PrimaryKey val id: String,
    val title: String,
    val startDate: String,
    val scheduledMinute: Int?,
    val frequency: HabitFrequency,
    val intervalDays: Int,
    val weekdaysMask: Int,
    val status: HabitStatus,
    val createdAtUtcMs: Long,
    val updatedAtUtcMs: Long,
) {
    /** A paused or not-yet-started habit stays visible; expiry never depends on completed counts. */
    fun isOngoingOn(date: LocalDate): Boolean = status != HabitStatus.ARCHIVED &&
        date <= LocalDate.parse(startDate).plusDays(TodoRules.HABIT_DAYS - 1)

    fun isDueOn(date: LocalDate): Boolean {
        val elapsed = ChronoUnit.DAYS.between(LocalDate.parse(startDate), date)
        if (status != HabitStatus.ACTIVE || elapsed !in 0 until TodoRules.HABIT_DAYS) return false
        return when (frequency) {
            HabitFrequency.DAILY -> true
            HabitFrequency.INTERVAL -> elapsed % intervalDays == 0L
            HabitFrequency.WEEKLY -> weekdaysMask and (1 shl (date.dayOfWeek.value - 1)) != 0
        }
    }
}
