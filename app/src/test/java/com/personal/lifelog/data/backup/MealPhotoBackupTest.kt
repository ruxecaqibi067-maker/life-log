package com.personal.lifelog.data.backup

import android.app.Application
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.repository.DataSnapshot
import com.personal.lifelog.domain.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MealPhotoBackupTest {
    private lateinit var f:BackupFixture
    @Before fun setup() { f=BackupFixture() }
    @After fun close() { f.close() }
    private fun engine(importer:BackupImporter,fault:suspend(RestoreFaultPoint)->Unit={})=RestoreEngine(f.db,f.writes,f.snapshots,f.exporter,importer,f.root,f.clock,syncDirectory={},availableBytes={Long.MAX_VALUE},fault=fault)
    private fun content(snapshot:DataSnapshot)=snapshot.copy(media=emptyList())
    private suspend fun addPhotos():DataSnapshot {
        val meal=f.db.records().allMeals().single();val original=f.db.metadata().media(meal.photoId!!)!!
        val more=List(2) {
            val id=id();val path="media/${original.generationId}/$id.jpg"
            File(f.root,original.relativePath).copyTo(File(f.root,path))
            original.copy(id=id,relativePath=path)
        }
        val ids=listOf(more[1].id,original.id,more[0].id)
        assertTrue(f.records.saveMealPhotos(MealDraft(meal.id,meal.occurredAtUtcMs,meal.zoneId,meal.text,meal.satiety,photoId=ids.first(),photoIds=ids),more) is DataResult.Success)
        return f.snapshots.read()
    }
    @Test fun roundTripAndRollback()=runBlocking<Unit> {
        f.seedWithWork(targetSleepMinutes=480);val before=addPhotos();val zip=f.zip()
        val imported=BackupArchive().verify(zip,BackupFiles(f.root).mkdir("verify7"))
        assertEquals(7,BackupJson.VERSION);assertEquals(3,imported.manifest.counts["mealPhotos"]);assertEquals(3,imported.data.media.size);assertEquals(before.mealPhotos,imported.data.mealPhotos)
        val importer=BackupImporter(f.root,f.clock);val ready=(importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
        assertEquals(3,ready.preview.counts["media"]);assertEquals(3,ready.preview.counts["mealPhotos"])
        val meal=f.db.records().allMeals().single();assertTrue(f.records.saveMealPhotos(MealDraft(meal.id,meal.occurredAtUtcMs,meal.zoneId,"changed",photoIds=emptyList())) is DataResult.Success)
        val changed=f.snapshots.read();val service=engine(importer)
        assertTrue(service.restore(ready.token) is RestoreResult.Committed);val after=f.snapshots.read()
        assertEquals(content(before),content(after));assertEquals(before.media.map { it.sha256 },after.media.map { it.sha256 })
        val rollback=(service.preflightRollback() as BackupResult.Success).value;assertTrue(service.restore(rollback.token) is RestoreResult.Committed);assertEquals(content(changed),content(f.snapshots.read()))
        val evidence=File("../artifacts/t27/comparison/v7-full.zip").apply { parentFile!!.mkdirs() };zip.copyTo(evidence,true)
    }
    private suspend fun legacy(zip:File,version:Int):File {
        val changed=editData(zip,File(f.root,"legacy-data-$version.zip")) { data ->
            data.put("dataVersion",version);data.remove("mealPhotos")
            if(version<6) { data.remove("todos");data.remove("habits") }
            if(version<5) data.getJSONArray("sessions").objects().forEach { it.remove("effort");it.remove("effortText") }
            if(version<4) { data.remove("workRecords");data.getJSONObject("settings").remove("targetWorkMinutes") }
            if(version<3) data.getJSONObject("settings").remove("targetSleepMinutes")
            if(version==1) data.getJSONArray("meals").objects().forEach { it.remove("satietyText") }
        }
        val entries=zipEntries(changed);val manifest=JSONObject(String(entries.getValue("manifest.json"))).put("formatVersion",version)
        manifest.getJSONObject("counts").apply { remove("mealPhotos");if(version<6) {remove("todos");remove("habits")};if(version<4)remove("workRecords") }
        entries["manifest.json"]=manifest.toString().toByteArray();return writeZip(File(f.root,"legacy-$version.zip"),entries)
    }
    @Test fun oldOneThroughSix()=runBlocking<Unit> {
        f.seed();val single=f.zip();val originalMeal=f.db.records().allMeals().single()
        for(version in 1..6) {
            addPhotos();val before=f.snapshots.read();val zip=legacy(single,version);val importer=BackupImporter(f.root,f.clock)
            val ready=(importer.preflight(BackupSource {zip.inputStream()}) as BackupResult.Success).value;assertEquals(1,ready.preview.counts["mealPhotos"])
            val service=engine(importer);assertTrue(service.restore(ready.token) is RestoreResult.Committed)
            assertEquals(listOf(originalMeal.photoId),f.records.mealPhotoIds(originalMeal.id));assertEquals(1,f.snapshots.read().media.size)
            val evidence=File("../artifacts/t27/comparison/v$version-legacy.zip").apply {parentFile!!.mkdirs()};zip.copyTo(evidence,true);f.zip().copyTo(File(evidence.parentFile,"v$version-to-v7.zip"),true)
            val rollback=(service.preflightRollback() as BackupResult.Success).value;assertTrue(service.restore(rollback.token) is RestoreResult.Committed)
            assertEquals(content(before),content(f.snapshots.read()))
        }
    }
    @Test fun rejectsBadAndKeepsRollback()=runBlocking<Unit> {
        f.seedWithWork();val before=addPhotos();val zip=f.zip()
        for(edit in listOf<(JSONObject)->Unit>(
            {it.remove("mealPhotos")},
            {it.getJSONArray("mealPhotos").getJSONObject(1).put("sortOrder",0)},
            {it.getJSONArray("mealPhotos").getJSONObject(0).put("photoId",id())},
            {it.getJSONArray("mealPhotos").getJSONObject(0).put("mealId",id())},
            {it.getJSONArray("meals").getJSONObject(0).put("photoId",id())}
        )) {
            val bad=editData(zip,File(f.root,"bad-${id()}.zip"),edit)
            assertTrue(BackupImporter(f.root,f.clock).preflight(BackupSource {bad.inputStream()}) is BackupResult.Failure)
            assertEquals(before,f.snapshots.read())
        }
        for(cancel in listOf(false,true)) {
            val importer=BackupImporter(f.root,f.clock);val ready=(importer.preflight(BackupSource {zip.inputStream()}) as BackupResult.Success).value
            val service=engine(importer) {if(it==RestoreFaultPoint.IN_TRANSACTION) {if(cancel) throw CancellationException("synthetic") else throw IOException("synthetic")}}
            if(cancel) try {service.restore(ready.token);fail("expected cancel")} catch(_:CancellationException) {} else assertTrue(service.restore(ready.token) is RestoreResult.FailedBeforeCommit)
            assertEquals(before,f.snapshots.read())
        }
        f.db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_link BEFORE INSERT ON MealPhoto WHEN NEW.sortOrder=1 BEGIN SELECT RAISE(ABORT,'synthetic'); END")
        val importer=BackupImporter(f.root,f.clock);val ready=(importer.preflight(BackupSource {zip.inputStream()}) as BackupResult.Success).value
        assertTrue(engine(importer).restore(ready.token) is RestoreResult.FailedBeforeCommit);assertEquals(before,f.snapshots.read())
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_link")
    }
}
