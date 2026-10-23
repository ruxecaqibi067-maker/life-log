package com.personal.lifelog

import android.content.Context
import com.personal.lifelog.data.backup.export.BackupExporter
import com.personal.lifelog.data.backup.importer.BackupImporter
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.SnapshotRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import java.time.Clock
import java.time.ZoneId

/** Application-scoped, manually wired data dependencies. */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemUTC(),
    val zoneIdProvider: () -> ZoneId = ZoneId::systemDefault,
) {
    private val appContext = context.applicationContext
    val writes = WriteCoordinator()
    internal val database: AppDatabase by lazy { AppDatabase.create(appContext, clock) }
    val records: RecordRepository by lazy { RecordRepository(database, writes, clock, zoneIdProvider) }
    val photos: PhotoStorage by lazy { PhotoStorage(appContext, database, records, writes, clock) }
    val workouts: WorkoutRepository by lazy { WorkoutRepository(database, writes, clock) }
    val timeline: TimelineRepository by lazy { TimelineRepository(records, workouts) }
    val settings: SettingsRepository by lazy { SettingsRepository(database, writes, clock) }
    val snapshots: SnapshotRepository by lazy { SnapshotRepository(database) }
    val backupExporter: BackupExporter by lazy {
        BackupExporter(snapshots, settings, writes, appContext.filesDir, clock, "0.1.0")
    }
    val backupImporter: BackupImporter by lazy { BackupImporter(appContext.filesDir, clock) }
}
