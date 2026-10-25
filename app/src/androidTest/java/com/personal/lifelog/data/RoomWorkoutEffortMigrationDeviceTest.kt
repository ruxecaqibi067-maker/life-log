package com.personal.lifelog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.domain.RecordIds
import java.time.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkoutEffortMigrationDeviceTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun schema4To5ValidatesAndPreservesOldTrainingAndBothGoals() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "polish-v4-${RecordIds.newId()}.db"
        val exercise = RecordIds.newId(); val session = RecordIds.newId(); val item = RecordIds.newId(); val set = RecordIds.newId()
        try {
            helper.createDatabase(name, 4).use { old ->
                old.execSQL("INSERT INTO ExerciseDefinition VALUES (?,'虚构新名称','虚构新名称','STRENGTH',0,1,NULL,1001,1,2)", arrayOf(exercise))
                old.execSQL("INSERT INTO WorkoutSession VALUES (?,1001,'UTC','1970-01-01','虚构旧记录',1,2)", arrayOf(session))
                old.execSQL("INSERT INTO WorkoutItem VALUES (?,?,?,'虚构历史名称','STRENGTH',0,NULL,NULL,NULL,1,2)", arrayOf(item, session, exercise))
                old.execSQL("INSERT INTO WorkoutSet VALUES (?,?,0,0,12,1,2)", arrayOf(set, item))
                old.execSQL("INSERT INTO AppSettings VALUES (1,'DARK',123,1,2,480,360)")
                old.execSQL("INSERT INTO RestoreState VALUES (1,NULL,1,2)")
            }
            helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5).use { upgraded ->
                upgraded.query("SELECT effort,effortText FROM WorkoutSession").use {
                    assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
                }
                upgraded.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            val db = AppDatabase.create(context, Clock.systemUTC(), name)
            try {
                val old = db.workouts().workout(session)!!
                assertEquals(1001L, old.session.occurredAtUtcMs)
                assertEquals("虚构历史名称", old.items.single().item.nameSnapshot)
                assertEquals(set, old.items.single().sets.single().id)
                assertEquals(0L, old.items.single().sets.single().weightGrams)
                assertEquals(12, old.items.single().sets.single().repetitions)
                assertTrue(db.workouts().exercise(exercise)!!.isHidden)
                assertEquals(480, db.metadata().settingsNow().targetSleepMinutes)
                assertEquals(360, db.metadata().settingsNow().targetWorkMinutes)
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}
