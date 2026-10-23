package com.personal.lifelog.data.backup

import android.graphics.Bitmap
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.core.validation.MediaRules
import com.personal.lifelog.data.backup.export.*
import com.personal.lifelog.data.backup.format.*
import com.personal.lifelog.data.backup.importer.*
import com.personal.lifelog.data.backup.restore.*
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.MediaAsset
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.ZoneId

/** Two separate instrumentation invocations: interrupt really kills this process; recover verifies restart.
 * Uses only a fixed QA database and synthetic files under restore-process-qa, never the user database.
 */
@RunWith(AndroidJUnit4::class)
class RestoreProcessDeviceTest {
    @Test fun processBoundary() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val action = args.getString("restoreAction")
        org.junit.Assume.assumeTrue("Requires explicit process-matrix arguments", action != null)
        val point = RestoreFaultPoint.valueOf(args.getString("restorePoint")!!)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "restore-process-qa")
        val name = "restore-process-qa.db"
        if (action == "interrupt") {
            BackupFiles(context.filesDir).remove(root)
            context.deleteDatabase(name)
        }
        check(root.isDirectory || root.mkdirs())
        val clock = Clock.systemUTC()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addCallback(AppDatabase.creationCallback(clock)).build()
        try {
            val writes = WriteCoordinator(); val snapshots = SnapshotRepository(db)
            val records = RecordRepository(db, writes, clock) { ZoneId.of("UTC") }
            val exporter = BackupExporter(snapshots, SettingsRepository(db, writes, clock), writes, root, clock, "qa")
            val importer = BackupImporter(root, clock)
            val engine = RestoreEngine(db, writes, snapshots, exporter, importer, root, clock,
                fault = { if (action == "interrupt" && it == point) Process.killProcess(Process.myPid()) })
            when (action) {
                "interrupt" -> {
                    val photo = RecordIds.newId(); val gen = RecordIds.newId()
                    val path = MediaRules.relativePath(gen, photo)
                    val image = BackupFiles(root).file(path).apply { parentFile!!.mkdirs() }
                    val bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
                    image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; bitmap.recycle()
                    val hash = digest(image)
                    val asset = MediaAsset(photo, gen, path, "image/jpeg", 12, 8, hash.byteSize, hash.sha256, 1, 1)
                    assertTrue(records.saveMeal(MealDraft(RecordIds.newId(), clock.millis(), "UTC", "synthetic-old", photoId = photo), asset) is DataResult.Success)
                    val old = snapshots.read()
                    val incoming = old.copy(meals = old.meals.map { it.copy(text = "synthetic-new") })
                    val zip = BackupArchive().create(incoming, { BackupFiles(root).file(it) },
                        BackupFiles(root).mkdir("source"), "qa", clock.millis())
                    val ready = (importer.preflight(BackupSource { zip.inputStream() }) as BackupResult.Success).value
                    engine.restore(ready.token)
                    fail("Process boundary was not reached")
                }
                "recover" -> {
                    assertTrue(engine.recoverPending() is BackupResult.Success)
                    val data = snapshots.read()
                    val committed = point in listOf(RestoreFaultPoint.AFTER_COMMIT, RestoreFaultPoint.CLEANUP)
                    assertEquals(if (committed) "synthetic-new" else "synthetic-old", data.meals.single().text)
                    BackupValidation.validate(data, BackupLimits())
                    data.media.forEach { BackupValidation.photo(BackupFiles(root).file(it.relativePath), it, BackupLimits()) }
                    assertFalse(File(root, "media-recovery.pending").exists())
                }
                else -> fail("Unknown restoreAction")
            }
        } finally { db.close() }
    }
}
