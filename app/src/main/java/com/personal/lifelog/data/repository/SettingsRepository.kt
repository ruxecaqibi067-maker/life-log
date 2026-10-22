package com.personal.lifelog.data.repository

import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.AppSettings
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.ThemeMode
import java.time.Clock

class SettingsRepository(
    private val db: AppDatabase,
    private val writes: WriteCoordinator,
    private val clock: Clock,
) {
    fun settings() = db.metadata().settings()

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
