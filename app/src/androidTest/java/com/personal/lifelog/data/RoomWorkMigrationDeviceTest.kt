package com.personal.lifelog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.SleepSummaryRepository
import com.personal.lifelog.data.repository.WorkRepository
import com.personal.lifelog.data.repository.WorkSummaryRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses full committed Room schemas, not a hand-built approximation of a few tables. */
@RunWith(AndroidJUnit4::class)
class RoomWorkMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tables = listOf("MediaAsset", "MealRecord", "SleepRecord", "ExerciseDefinition",
        "WorkoutSession", "WorkoutItem", "WorkoutSet", "AppSettings", "RestoreState")

    @Test fun schema3To4PreservesAllOldValuesAndIndependentGoals() = checkMigration(3)
    @Test fun schema2To3To4PreservesAllOldValuesAndIndependentGoals() = checkMigration(2)
    @Test fun schema1To2To3To4PreservesAllOldValuesAndIndependentGoals() = checkMigration(1)

    private fun put(db: SupportSQLiteDatabase, table: String, vararg fields: Pair<String, Any?>) {
        db.execSQL("INSERT INTO $table (${fields.joinToString { it.first }}) VALUES (${fields.joinToString { "?" }})",
            fields.map { it.second }.toTypedArray())
    }

    private fun seed(db: SupportSQLiteDatabase, version: Int) {
        val photo = RecordIds.newId()
        val generation = RecordIds.newId()
        val exercise = RecordIds.newId()
        val session = RecordIds.newId()
        val item = RecordIds.newId()
        put(db, "MediaAsset", "id" to photo, "generationId" to generation,
            "relativePath" to "media/$generation/$photo.jpg", "mimeType" to "image/jpeg", "width" to 8,
            "height" to 8, "byteSize" to 1234L, "sha256" to "0".repeat(64), "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "MealRecord", "id" to RecordIds.newId(), "occurredAtUtcMs" to 1001L,
            "zoneId" to "UTC", "localDate" to "1970-01-01", "text" to "虚构旧饮食",
            "satiety" to "SEVEN", "photoId" to photo, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        if (version >= 2) put(db, "MealRecord", "id" to RecordIds.newId(), "occurredAtUtcMs" to 1002L,
            "zoneId" to "UTC", "localDate" to "1970-01-01", "text" to "虚构自定义记录", "satiety" to "CUSTOM",
            "satietyText" to "七分饱但还想吃", "photoId" to null, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "SleepRecord", "id" to RecordIds.newId(), "startedAtUtcMs" to 1001L, "startZoneId" to "UTC",
            "endedAtUtcMs" to 8002L, "endZoneId" to "UTC", "archiveLocalDate" to "1970-01-01",
            "activeSlot" to null, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "SleepRecord", "id" to RecordIds.newId(), "startedAtUtcMs" to 9003L, "startZoneId" to "UTC",
            "endedAtUtcMs" to null, "endZoneId" to null, "archiveLocalDate" to "1970-01-01",
            "activeSlot" to 1, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "ExerciseDefinition", "id" to exercise, "name" to "虚构卧推", "normalizedName" to "虚构卧推",
            "type" to "STRENGTH", "isBuiltin" to 0, "isHidden" to 0, "activeNameKey" to "虚构卧推",
            "lastUsedAtUtcMs" to 1001L, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "WorkoutSession", "id" to session, "occurredAtUtcMs" to 1001L, "zoneId" to "UTC",
            "localDate" to "1970-01-01", "note" to "旧训练", "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "WorkoutItem", "id" to item, "sessionId" to session, "exerciseId" to exercise,
            "nameSnapshot" to "旧项目名称", "typeSnapshot" to "STRENGTH", "sortOrder" to 0,
            "durationSeconds" to null, "distanceMeters" to null, "note" to null,
            "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "WorkoutSet", "id" to RecordIds.newId(), "itemId" to item, "sortOrder" to 0,
            "weightGrams" to 0L, "repetitions" to 5, "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        put(db, "AppSettings", "id" to 1, "themeMode" to "DARK", "lastSuccessfulExportAtUtcMs" to 77L,
            "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
        if (version == 3) db.execSQL("UPDATE AppSettings SET targetSleepMinutes = 600 WHERE id = 1")
        put(db, "RestoreState", "id" to 1, "lastCommittedRestoreId" to RecordIds.newId(),
            "createdAtUtcMs" to 1L, "updatedAtUtcMs" to 2L)
    }

    private fun snapshot(db: SupportSQLiteDatabase) = tables.associateWith { table ->
        db.query("SELECT * FROM $table ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.columnNames.mapIndexed { index, column ->
                column to if (cursor.isNull(index)) null else cursor.getString(index)
            }.toMap()) }
        }
    }

    private fun checkMigration(version: Int) = runBlocking<Unit> {
        val name = "t20-migration-$version-${RecordIds.newId()}.db"
        try {
            val before = helper.createDatabase(name, version).use { old -> seed(old, version); snapshot(old) }
            helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4).use { migrated ->
                val expected = before.mapValues { (table, rows) -> rows.map { row -> when (table) {
                    "AppSettings" -> row + ("targetWorkMinutes" to null) + (if (version < 3) mapOf("targetSleepMinutes" to null) else emptyMap())
                    "MealRecord" -> if (version == 1) row + ("satietyText" to null) else row
                    else -> row
                } } }
                assertEquals(expected, snapshot(migrated))
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                for (bad in listOf(0, -1, 1441)) assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    migrated.execSQL("UPDATE AppSettings SET targetSleepMinutes = ? WHERE id = 1", arrayOf<Any>(bad))
                }
            }
            val clock = Clock.systemUTC()
            val db = AppDatabase.create(context, clock, name)
            try {
                assertEquals(if (version == 3) 600 else null, db.metadata().settingsNow().targetSleepMinutes)
                assertNull(db.metadata().settingsNow().targetWorkMinutes)
                assertTrue(db.work().allWorks().isEmpty())
                val sum = (SleepSummaryRepository(db).observe(LocalDate.of(1970, 1, 1)).first() as DataResult.Success).value
                assertEquals(1, sum.completedSegmentCount)
                assertEquals(7001L, sum.recordedDurationMs)
                assertTrue(sum.hasActiveSleep)
                assertTrue(SettingsRepository(db, WriteCoordinator(), clock).setTargetSleepMinutes(480) is DataResult.Success)
                val work = WorkRepository(db, WriteCoordinator(), clock) { java.time.ZoneId.of("UTC") }
                val active = (work.startWork(RecordIds.newId(), 1001L, "UTC") as DataResult.Success).value
                assertTrue(SettingsRepository(db, WriteCoordinator(), clock).setTargetWorkMinutes(420) is DataResult.Success)
                assertEquals(480, db.metadata().settingsNow().targetSleepMinutes)
                for (bad in listOf<Any>(0, -1, 1441, 1.5)) assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    db.openHelper.writableDatabase.execSQL("UPDATE AppSettings SET targetWorkMinutes = ? WHERE id = 1", arrayOf(bad))
                }
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    kotlinx.coroutines.runBlocking { db.work().insertWork(active.copy(id = RecordIds.newId())) }
                }
                assertTrue(work.finishWork(active.id, 8002L, "UTC") is DataResult.Success)
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    db.openHelper.writableDatabase.execSQL("UPDATE WorkRecord SET endedAtUtcMs = startedAtUtcMs")
                }
                val completed = db.work().allWorks().single()
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    kotlinx.coroutines.runBlocking { db.work().insertWork(completed.copy(id = RecordIds.newId(), startedAtUtcMs = 2000L)) }
                }
                assertTrue(work.saveWork(WorkDraft(RecordIds.newId(), 8002L, "UTC", 9003L, "UTC")) is DataResult.Success)
                assertTrue(work.startWork(RecordIds.newId(), 9003L, "UTC") is DataResult.Success)
                val workSum = (WorkSummaryRepository(db).observe(LocalDate.of(1970, 1, 1)).first() as DataResult.Success).value
                assertEquals(8002L, workSum.recordedDurationMs)
                assertEquals(2, workSum.completedSegmentCount)
                assertTrue(workSum.hasActiveWork)
                assertEquals(ThemeMode.DARK, db.metadata().settingsNow().themeMode)
                assertEquals(77L, db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
            } finally { db.close() }
            val reopened = AppDatabase.create(context, clock, name)
            try {
                assertEquals(480, reopened.metadata().settingsNow().targetSleepMinutes)
                assertEquals(420, reopened.metadata().settingsNow().targetWorkMinutes)
                assertEquals(3, reopened.work().allWorks().size)
                assertNotNull(reopened.work().activeWorkNow())
                assertEquals(2, reopened.records().allSleeps().size)
                assertEquals(1, reopened.metadata().allMedia().size)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
