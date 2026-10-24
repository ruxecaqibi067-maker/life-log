package com.personal.lifelog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.RecordIds
import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android SQLite checks; JVM Robolectric success does not replace these. */
@RunWith(AndroidJUnit4::class)
class RoomV1DeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var name: String

    @Before fun create() {
        context = ApplicationProvider.getApplicationContext()
        name = "t02-device-${RecordIds.newId()}.db"
        db = AppDatabase.create(context, Clock.systemUTC(), name)
    }

    @After fun close() { db.close(); context.deleteDatabase(name) }

    @Test fun freshV2MatchesExportedSchemaWithoutInventingAnOldMigration() = runBlocking<Unit> {
        assertEquals(8, db.workouts().allExercises().size)
        assertEquals(1, db.metadata().settingsNow().id)
        db.close()
        // Same real v2 on both sides: schema validation only, no fictional v0 -> v1 migration.
        helper.runMigrationsAndValidate(name, 2, true).close()
    }

    @Test fun v1ToV2AddsSatietyTextAndPreservesExistingMeals() = runBlocking<Unit> {
        val migrationName = "t05-migration-${RecordIds.newId()}.db"
        val mealId = RecordIds.newId()
        try {
            helper.createDatabase(migrationName, 1).use { old ->
                old.execSQL(
                    "INSERT INTO MealRecord (id,occurredAtUtcMs,zoneId,localDate,text,satiety,photoId,createdAtUtcMs,updatedAtUtcMs) " +
                        "VALUES (?,?,?,?,?,?,NULL,?,?)",
                    arrayOf<Any>(mealId, 1000L, "UTC", "1970-01-01", "旧记录", "SEVEN", 1L, 2L),
                )
            }
            helper.runMigrationsAndValidate(migrationName, 2, true, AppDatabase.MIGRATION_1_2).close()
            val migrated = AppDatabase.create(context, Clock.systemUTC(), migrationName)
            try {
                val meal = migrated.records().meal(mealId)
                assertEquals("旧记录", meal?.text)
                assertEquals(com.personal.lifelog.domain.Satiety.SEVEN, meal?.satiety)
                assertNull(meal?.satietyText)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(migrationName)
        }
    }

    @Test fun androidSqliteEnforcesForeignKeysAndUniqueActiveSleep() = runBlocking<Unit> {
        val records = RecordRepository(db, WriteCoordinator(), Clock.systemUTC()) { ZoneId.of("UTC") }
        val active = (records.startSleep(RecordIds.newId(), 1000, "UTC") as DataResult.Success).value
        db.openHelper.readableDatabase.query("PRAGMA foreign_keys").use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        try {
            db.records().putSleep(active.copy(id = RecordIds.newId()))
            fail("Expected unique activeSlot constraint")
        } catch (_: android.database.SQLException) { }
        assertEquals(1, db.records().allSleeps().size)
    }
}
