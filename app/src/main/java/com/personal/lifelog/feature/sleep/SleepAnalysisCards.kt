package com.personal.lifelog.feature.sleep

import androidx.compose.runtime.Composable
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.DayTimeAnalysisCard
import com.personal.lifelog.ui.components.WeekTimeAnalysisCard
import com.personal.lifelog.ui.components.TimeAnalysisLabels
import java.time.LocalDate

internal val sleepHistoryDateFormat = com.personal.lifelog.ui.components.historyDateFormat
private val labels = TimeAnalysisLabels(
    R.string.sleep_analysis_day, R.string.sleep_analysis_week, R.string.sleep_analysis_open_starts,
    R.string.sleep_analysis_explanation, R.string.sleep_analysis_week_hint, "sleep",
)

@Composable
fun SleepDayAnalysisCard(day: SleepDayAnalysis) = DayTimeAnalysisCard(day, labels)

@Composable
fun SleepWeekAnalysisCard(days: List<SleepDayAnalysis>, onSelectDate: (LocalDate) -> Unit) =
    WeekTimeAnalysisCard(days, labels, onSelectDate)
