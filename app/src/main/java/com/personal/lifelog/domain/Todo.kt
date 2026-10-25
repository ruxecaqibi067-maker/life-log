package com.personal.lifelog.domain

import java.time.LocalDate

enum class HabitFrequency { DAILY, INTERVAL, WEEKLY }
enum class HabitStatus { ACTIVE, PAUSED, ARCHIVED }

data class TodoDraft(
    val id: String,
    val title: String,
    val date: LocalDate,
    val scheduledMinute: Int? = null,
)

data class HabitDraft(
    val id: String,
    val title: String,
    val startDate: LocalDate,
    val scheduledMinute: Int? = null,
    val frequency: HabitFrequency = HabitFrequency.DAILY,
    val intervalDays: Int = 1,
    /** Monday bit 0 through Sunday bit 6. */
    val weekdaysMask: Int = 0,
)
