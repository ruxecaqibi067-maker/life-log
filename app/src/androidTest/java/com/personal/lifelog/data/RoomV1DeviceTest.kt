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

    @Test fun freshV1MatchesExportedSchemaWithoutInventingAnOldMigration() = runBlocking<Unit> {
        assertEquals(8, db.workouts().allExercises().size)
        assertEquals(1, db.metadata().settingsNow().id)
        db.close()
        // Same real v1 on both sides: schema validation only, no fictional v0 -> v1 migration.
        helper.runMigrationsAndValidate(name, 1, true).close()
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
