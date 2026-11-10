package com.personal.lifelog.feature.work

import androidx.compose.runtime.Composable
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.DayTimeAnalysisCard
import com.personal.lifelog.ui.components.WeekTimeAnalysisCard
import com.personal.lifelog.ui.components.TimeAnalysisLabels
import java.time.LocalDate

internal val workHistoryDateFormat = com.personal.lifelog.ui.components.historyDateFormat
private val labels = TimeAnalysisLabels(
    R.string.work_analysis_day, R.string.work_analysis_week, R.string.work_analysis_open_starts,
    R.string.work_analysis_explanation, R.string.work_analysis_week_hint, "work",
)

@Composable
fun WorkDayAnalysisCard(day: WorkDayAnalysis) = DayTimeAnalysisCard(day, labels)

@Composable
fun WorkWeekAnalysisCard(days: List<WorkDayAnalysis>, onSelectDate: (LocalDate) -> Unit) =
    WeekTimeAnalysisCard(days, labels, onSelectDate)
