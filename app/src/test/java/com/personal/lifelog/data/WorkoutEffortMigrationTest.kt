package com.personal.lifelog.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.io.File
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class WorkoutEffortMigrationTest {
    /** Builds actual committed old schemas, then opens through production Room migrations. */
    @Test fun everyOldSchemaMigratesToCurrentPreservingSetsSnapshotsAndNullEffort() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..4) {
            val name = "p$version.db"
            val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            val exerciseId = RecordIds.newId(); val sessionId = RecordIds.newId(); val itemId = RecordIds.newId(); val setId = RecordIds.newId()
            try {
                SQLiteDatabase.openDatabase(path, SQLiteDatabase.OpenParams.Builder()
                    .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY).setJournalMode("DELETE").build()).use { old ->
                    val schema = JSONObject(File("schemas/com.personal.lifelog.data.local.AppDatabase/$version.json").readText()).getJSONObject("database")
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                    old.execSQL("INSERT INTO AppSettings (id,themeMode,lastSuccessfulExportAtUtcMs,createdAtUtcMs,updatedAtUtcMs) VALUES (1,'DARK',123,1,2)")
                    old.execSQL("INSERT INTO RestoreState VALUES (1,NULL,1,2)")
                    old.execSQL("INSERT INTO ExerciseDefinition VALUES (?, '虚构已隐藏动作','虚构已隐藏动作','STRENGTH',0,1,NULL,123,1,2)", arrayOf(exerciseId))
                    old.execSQL("INSERT INTO WorkoutSession VALUES (?,1001,'UTC','1970-01-01','虚构旧训练',1,2)", arrayOf(sessionId))
                    old.execSQL("INSERT INTO WorkoutItem VALUES (?,?,?,'虚构历史名称','STRENGTH',0,NULL,NULL,NULL,1,2)", arrayOf(itemId, sessionId, exerciseId))
                    old.execSQL("INSERT INTO WorkoutSet VALUES (?,?,0,0,8,1,2)", arrayOf(setId, itemId))
                    old.version = version
                }
                val db = AppDatabase.create(context, Clock.systemUTC(), name)
                try {
                    val record = db.workouts().workout(sessionId)!!
                    assertEquals(6, db.openHelper.readableDatabase.version)
                    assertEquals("虚构旧训练", record.session.note)
                    assertEquals(1001L, record.session.occurredAtUtcMs)
                    assertNull(record.session.effort); assertNull(record.session.effortText)
                    assertEquals("虚构历史名称", record.items.single().item.nameSnapshot)
                    assertEquals(setId, record.items.single().sets.single().id)
                    assertEquals(0L, record.items.single().sets.single().weightGrams)
                    assertEquals(8, record.items.single().sets.single().repetitions)
                    assertTrue(db.workouts().exercise(exerciseId)!!.isHidden)
                    assertEquals(123L, db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
                    assertEquals(ThemeMode.DARK, db.metadata().settingsNow().themeMode)
                    assertThrows(android.database.sqlite.SQLiteException::class.java) {
                        db.openHelper.writableDatabase.execSQL("UPDATE WorkoutSession SET effort = 'UNKNOWN' WHERE id = ?", arrayOf(sessionId))
                    }
                    assertThrows(android.database.sqlite.SQLiteException::class.java) {
                        db.openHelper.writableDatabase.execSQL("UPDATE WorkoutSession SET effort = 'CUSTOM', effortText = ' ' WHERE id = ?", arrayOf(sessionId))
                    }
                    val saved = WorkoutRepository(db, WriteCoordinator(), Clock.systemUTC()).saveWorkout(
                        WorkoutDraft(sessionId, 1001, "UTC", listOf(WorkoutItemDraft(itemId, exerciseId,
                            sets = listOf(WorkoutSetDraft(setId, 0, 8)))), effort = WorkoutEffort.CUSTOM, effortText = "虚构轻松"))
                    assertTrue(saved.toString(), saved is DataResult.Success)
                    assertEquals("虚构轻松", db.workouts().workout(sessionId)!!.session.effortText)
                } finally { db.close() }
                AppDatabase.create(context, Clock.systemUTC(), name).let { reopened ->
                    try { assertEquals(WorkoutEffort.CUSTOM, reopened.workouts().workout(sessionId)!!.session.effort) }
                    finally { reopened.close() }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
