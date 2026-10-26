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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MealPhotoMigrationTest {
    @Test fun oldSchemas()=runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(version in 1..6) {
            val name="mp$version.db";val path=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            val photo=RecordIds.newId();val gen=RecordIds.newId();val a=RecordIds.newId();val b=RecordIds.newId();val empty=RecordIds.newId()
            try {
                SQLiteDatabase.openDatabase(path,SQLiteDatabase.OpenParams.Builder().addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY).setJournalMode("DELETE").build()).use { old ->
                    val schema=JSONObject(File("schemas/com.personal.lifelog.data.local.AppDatabase/$version.json").readText()).getJSONObject("database")
                    val entities=schema.getJSONArray("entities")
                    for(i in 0 until entities.length()) {
                        val e=entities.getJSONObject(i);val table=e.getString("tableName")
                        old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                        e.optJSONArray("indices")?.let { indices -> for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table)) }
                    }
                    schema.getJSONArray("setupQueries").let { for(i in 0 until it.length()) old.execSQL(it.getString(i)) }
                    old.execSQL("INSERT INTO AppSettings(id,themeMode,lastSuccessfulExportAtUtcMs,createdAtUtcMs,updatedAtUtcMs) VALUES(1,'DARK',123,1,2)")
                    old.execSQL("INSERT INTO RestoreState VALUES(1,NULL,1,2)")
                    old.execSQL("INSERT INTO MediaAsset VALUES(?,?,?,'image/jpeg',8,8,12,?,1,2)",arrayOf(photo,gen,"media/$gen/$photo.jpg","a".repeat(64)))
                    for(id in listOf(a,b)) old.execSQL("INSERT INTO MealRecord(id,occurredAtUtcMs,zoneId,localDate,text,satiety,photoId,createdAtUtcMs,updatedAtUtcMs) VALUES(?,1001,'UTC','1970-01-01','legacy','SEVEN',?,1,2)",arrayOf(id,photo))
                    old.execSQL("INSERT INTO MealRecord(id,occurredAtUtcMs,zoneId,localDate,text,satiety,photoId,createdAtUtcMs,updatedAtUtcMs) VALUES(?,1001,'UTC','1970-01-01','text only',NULL,NULL,1,2)",arrayOf(empty))
                    old.version=version
                }
                val db=AppDatabase.create(context,Clock.systemUTC(),name)
                try {
                    assertEquals(7,db.openHelper.readableDatabase.version)
                    val repo=RecordRepository(db,WriteCoordinator(),Clock.systemUTC()) { java.time.ZoneOffset.UTC }
                    assertEquals(listOf(photo),repo.mealPhotoIds(a));assertEquals(listOf(photo),repo.mealPhotoIds(b));assertTrue(repo.mealPhotoIds(empty).isEmpty())
                    assertEquals(photo,repo.meal(a)!!.photoId);assertEquals("text only",repo.meal(empty)!!.text)
                    assertTrue(repo.saveMeal(MealDraft(a,1001,"UTC","text changed",photoId=photo)) is DataResult.Success)
                    assertEquals(listOf(photo),repo.mealPhotoIds(a))
                    assertEquals(123L,db.metadata().settingsNow().lastSuccessfulExportAtUtcMs)
                    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                } finally { db.close() }
                val reopened=AppDatabase.create(context,Clock.systemUTC(),name)
                try { assertEquals(2,reopened.mealPhotos().all().size);assertEquals(1,reopened.metadata().allMedia().size) }
                finally { reopened.close() }
            } finally { context.deleteDatabase(name) }
        }
    }
}
