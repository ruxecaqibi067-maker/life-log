package com.personal.lifelog

import android.content.Context
import com.personal.lifelog.data.backup.export.BackupExporter
import com.personal.lifelog.data.backup.importer.BackupImporter
import com.personal.lifelog.data.backup.restore.RestoreEngine
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.SleepSummaryRepository
import com.personal.lifelog.data.repository.SnapshotRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.data.repository.WorkSummaryRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.feature.settings.BackupRestoreController
import java.time.Clock
import java.time.ZoneId

/** Application-scoped, manually wired data dependencies. */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemUTC(),
    val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    private val appContext = context.applicationContext
    val writes = WriteCoordinator { restoreEngine.ensureRecovered() }
    internal val database: AppDatabase by lazy { AppDatabase.create(appContext, clock) }
    val records: RecordRepository by lazy { RecordRepository(database, writes, clock, zoneIdProvider) }
    val photos: PhotoStorage by lazy { PhotoStorage(appContext, database, records, writes, clock) }
    val workouts: WorkoutRepository by lazy { WorkoutRepository(database, writes, clock) }
    val timeline: TimelineRepository by lazy { TimelineRepository(records, workouts, work) }
    val settings: SettingsRepository by lazy { SettingsRepository(database, writes, clock) }
    val work: WorkRepository by lazy {
        WorkRepository(database, writes, clock, zoneIdProvider)
    }
    val workSummary: WorkSummaryRepository by lazy {
        WorkSummaryRepository(database)
    }
    val sleepSummary: SleepSummaryRepository by lazy { SleepSummaryRepository(database) }
    val snapshots: SnapshotRepository by lazy { SnapshotRepository(database) }
    val backupExporter: BackupExporter by lazy {
        val installedVersion = appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        BackupExporter(snapshots, settings, writes, appContext.filesDir, clock, installedVersion)
    }
    val backupImporter: BackupImporter by lazy { BackupImporter(appContext.filesDir, clock) }
    val restoreEngine: RestoreEngine by lazy {
        RestoreEngine(database, writes, snapshots, backupExporter, backupImporter, appContext.filesDir, clock)
    }
    /** Survives Activity/ViewModel recreation; owns in-memory preview tokens only. */
    val backupRestore: BackupRestoreController by lazy {
        BackupRestoreController(backupExporter, backupImporter, restoreEngine, clock)
    }
}
