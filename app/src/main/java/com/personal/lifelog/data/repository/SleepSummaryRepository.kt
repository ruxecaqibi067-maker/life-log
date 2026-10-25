package com.personal.lifelog.data.repository

import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.dao.SleepSummaryRow
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.domain.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SleepSummaryRepository(private val db: AppDatabase) {
    /**
     * Pass HomeViewModel.today's date; historical archive dates are never recomputed.
     * Cold flow: re-collect (or call observe again) to retry after Failure. Loading belongs
     * to the consumer; a successful empty summary is distinct from a read failure.
     */
    fun observe(date: LocalDate): Flow<DataResult<SleepSummary>> = db.sleepSummaries().observe(date.toString())
        .map<SleepSummaryRow?, DataResult<SleepSummary>> { row ->
            val saved = row ?: reject(ErrorCode.STORAGE)
            Rules.targetSleepMinutes(saved.targetSleepMinutes)
            DataResult.Success(SleepSummary(date, saved.targetSleepMinutes, saved.completedSegmentCount,
                saved.recordedDurationMs, saved.hasActiveSleep))
        }
        .catch { failure ->
            if (failure is CancellationException) throw failure
            emit(DataResult.Failure(DataError(ErrorCode.STORAGE)))
        }
        .distinctUntilChanged()
}
