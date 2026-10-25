package com.personal.lifelog.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Query projection only; there is no summary table or persisted duration cache. */
data class SleepSummaryRow(
    val targetSleepMinutes: Int?,
    val completedSegmentCount: Int,
    val recordedDurationMs: Long?,
    val hasActiveSleep: Boolean,
)

@Dao
interface SleepSummaryDao {
    // One SQL snapshot prevents a restore/finish from mixing independently emitted settings
    // and record states. SUM stays null for no completed segments and retains milliseconds.
    @Query("""
        SELECT targetSleepMinutes,
            (SELECT COUNT(*) FROM SleepRecord WHERE archiveLocalDate = :date AND endedAtUtcMs IS NOT NULL) AS completedSegmentCount,
            (SELECT SUM(endedAtUtcMs - startedAtUtcMs) FROM SleepRecord WHERE archiveLocalDate = :date AND endedAtUtcMs IS NOT NULL) AS recordedDurationMs,
            EXISTS(SELECT 1 FROM SleepRecord WHERE activeSlot = 1) AS hasActiveSleep
        FROM AppSettings WHERE id = 1
    """)
    fun observe(date: String): Flow<SleepSummaryRow?>
}
