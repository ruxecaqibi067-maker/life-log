package com.personal.lifelog.data.repository

import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.core.validation.Rules
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ThemeMode
import java.time.Clock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class SettingsRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
) {
    fun settings() = db.metadata().settings()

    /** Cold Room flow; read failures propagate, never become an unset target. */
    fun targetSleepMinutes() = settings().map { it.targetSleepMinutes }.distinctUntilChanged()

    /** null explicitly clears the goal. No record dates, intervals or other settings change. */
    suspend fun setTargetSleepMinutes(minutes: Int?): DataResult<AppSettings> = write(db, writes) {
        Rules.targetSleepMinutes(minutes)
        val old = db.metadata().settingsNow()
        old.copy(targetSleepMinutes = minutes,
            updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
            .also { db.metadata().putSettings(it) }
    }

    /** Read failures propagate; null is exclusively an unset saved goal. */
    fun targetWorkMinutes() = settings().map { it.targetWorkMinutes }.distinctUntilChanged()

    suspend fun setTargetWorkMinutes(minutes: Int?): DataResult<AppSettings> = write(db, writes) {
        Rules.targetWorkMinutes(minutes)
        val old = db.metadata().settingsNow()
        old.copy(targetWorkMinutes = minutes,
            updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
            .also { db.metadata().putSettings(it) }
    }

    suspend fun setTheme(mode: ThemeMode): DataResult<AppSettings> = write(db, writes) {
        val old = db.metadata().settingsNow()
        old.copy(themeMode = mode, updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
            .also { db.metadata().putSettings(it) }
    }

    /** Call only after the destination file was fully written and closed successfully. */
    suspend fun recordSuccessfulExport(atUtcMs: Long): DataResult<AppSettings> = write(db, writes) {
        val old = db.metadata().settingsNow()
        old.copy(lastSuccessfulExportAtUtcMs = atUtcMs,
            updatedAtUtcMs = updatedAt(clock.millis(), old.createdAtUtcMs, old.updatedAtUtcMs))
            .also { db.metadata().putSettings(it) }
    }
}
