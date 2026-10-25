package com.personal.lifelog.feature.sleep

import android.content.Context
import androidx.compose.runtime.Composable
import com.personal.lifelog.ui.components.RecordDateTimePicker
import com.personal.lifelog.ui.components.createRecordWheel
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Compatibility entry point; all record types share the same production picker. */
@Composable
fun SleepDateTimePicker(title: Int, initial: ZonedDateTime, zone: ZoneId, onCancel: () -> Unit,
    onConfirm: (LocalDateTime, ZoneOffset) -> Unit) =
    RecordDateTimePicker(title, initial, zone, onCancel, onConfirm, tagPrefix = "sleep-wheel")

fun wheelDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
    com.personal.lifelog.ui.components.wheelDateTime(year, month, day, hour, minute)

internal fun createSleepWheel(context: Context, darkTheme: Boolean) = createRecordWheel(context, darkTheme)
