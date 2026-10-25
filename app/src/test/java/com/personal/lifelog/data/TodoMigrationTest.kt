package com.personal.lifelog.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.TodoRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import java.io.File
import java.time.*
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
class TodoMigrationTest {
    @Test fun schemaFiveToSixPreservesDataAndTombstonesAfterReopen() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "t56.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val mealId = RecordIds.newId(); val workId = RecordIds.newId()
        val clock = Clock.fixed(Instant.parse("2026-10-26T08:00:00Z"), ZoneOffset.UTC)
        try {
            SQLiteDatabase.openDatabase(path, SQLiteDatabase.OpenParams.Builder()
                .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY).setJournalMode("DELETE").build()).use { old ->
                val schema = JSONObject(File("schemas/com.personal.lifelog.data.local.AppDatabase/5.json").readText()).getJSONObject("database")
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.execSQL("INSERT INTO AppSettings VALUES (1,'DARK',123,1,2,480,360)")
                old.execSQL("INSERT INTO RestoreState VALUES (1,NULL,1,2)")
                old.execSQL("INSERT INTO MealRecord VALUES (?,1001,'UTC','1970-01-01','虚构旧饮食','CUSTOM',NULL,1,2,'虚构旧感受')", arrayOf(mealId))
                old.execSQL("INSERT INTO WorkRecord VALUES (?,1001,'UTC',2001,'UTC','1970-01-01',NULL,1,2)", arrayOf(workId))
                old.version = 5
            }
            val db = AppDatabase.create(context, clock, name)
            var generatedId: String? = null
            try {
                assertEquals(6, db.openHelper.readableDatabase.version) // Production Room validates schema6 here.
                assertEquals("虚构旧感受", db.records().meal(mealId)!!.satietyText)
                assertEquals(2001L, db.work().work(workId)!!.endedAtUtcMs)
                assertEquals(480, db.metadata().settingsNow().targetSleepMinutes)
                assertEquals(360, db.metadata().settingsNow().targetWorkMinutes)
                assertTrue(db.todos().allTodos().isEmpty()); assertTrue(db.todos().allHabits().isEmpty())
                val repo = TodoRepository(db, WriteCoordinator(), clock) { ZoneOffset.UTC }
                val habitId = RecordIds.newId()
                assertTrue(repo.saveHabit(HabitDraft(habitId, "虚构新增", LocalDate.of(2026, 10, 26))) is DataResult.Success)
                assertEquals(1, (repo.generateFor() as DataResult.Success).value)
                generatedId = db.todos().allTodos().single().id
                assertTrue(repo.deleteTodo(generatedId) is DataResult.Success)
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    db.openHelper.writableDatabase.execSQL("UPDATE HabitRecord SET weekdaysMask = 0, frequency = 'WEEKLY' WHERE id = ?", arrayOf(habitId))
                }
                assertThrows(android.database.sqlite.SQLiteException::class.java) {
                    db.openHelper.writableDatabase.execSQL("UPDATE TodoRecord SET deletedAtUtcMs = NULL WHERE id = ?", arrayOf(generatedId))
                }
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { db.close() }
            val reopened = AppDatabase.create(context, clock, name)
            try {
                val repo = TodoRepository(reopened, WriteCoordinator(), clock) { ZoneOffset.UTC }
                assertEquals(0, (repo.generateFor() as DataResult.Success).value)
                assertNull(repo.todo(generatedId))
                assertNotNull(reopened.todos().allTodos().single().deletedAtUtcMs)
                assertNotNull(reopened.records().meal(mealId))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }
}
