package com.personal.lifelog.data

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Host SQL check for the additive v1 -> v2 migration. The full Room schema
 * validation against exported v1/v2 JSON runs in the device test suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MigrationV1ToV2Test {

    private lateinit var helper: SupportSQLiteOpenHelper

    @Before
    fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE MealRecord (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "occurredAtUtcMs INTEGER NOT NULL, " +
                            "zoneId TEXT NOT NULL, " +
                            "localDate TEXT NOT NULL, " +
                            "text TEXT, " +
                            "satiety TEXT, " +
                            "photoId TEXT, " +
                            "createdAtUtcMs INTEGER NOT NULL, " +
                            "updatedAtUtcMs INTEGER NOT NULL)",
                    )
                    listOf("INSERT", "UPDATE").forEach { operation ->
                        db.execSQL(
                            "CREATE TRIGGER guard_MealRecord_$operation BEFORE $operation ON MealRecord " +
                                "WHEN NEW.photoId IS NULL AND (NEW.text IS NULL OR length(trim(NEW.text)) = 0) " +
                                "BEGIN SELECT RAISE(ABORT, 'invalid MealRecord invariant'); END",
                        )
                    }
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun insert(db: SupportSQLiteDatabase, id: String, satiety: String?, satietyText: String?) {
        db.execSQL(
            "INSERT INTO MealRecord (id,occurredAtUtcMs,zoneId,localDate,text,satiety,photoId,createdAtUtcMs,updatedAtUtcMs,satietyText) " +
                "VALUES (?,?,?,?,?,?,NULL,?,?,?)",
            arrayOf<Any?>(id, 1000L, "UTC", "1970-01-01", "虚构记录", satiety, 1L, 2L, satietyText),
        )
    }

    @Test
    fun migrationAddsSatietyTextPreservesLegacyRowsAndEnforcesNewInvariant() {
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO MealRecord (id,occurredAtUtcMs,zoneId,localDate,text,satiety,photoId,createdAtUtcMs,updatedAtUtcMs) " +
                "VALUES (?,?,?,?,?,?,NULL,?,?)",
            arrayOf<Any>("legacy", 1000L, "UTC", "1970-01-01", "旧记录", "SEVEN", 1L, 2L),
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT satiety, satietyText FROM MealRecord WHERE id = 'legacy'").use {
            assertTrue(it.moveToFirst())
            assertEquals("SEVEN", it.getString(0))
            assertNull(it.getString(1))
        }
        insert(db, "custom", "CUSTOM", "七分饱但还想吃")
        try {
            insert(db, "missing", "CUSTOM", null)
            fail("CUSTOM without text must fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
        try {
            insert(db, "preset", "SEVEN", "不允许")
            fail("preset with custom text must fail")
        } catch (_: android.database.sqlite.SQLiteException) { }
    }
}
