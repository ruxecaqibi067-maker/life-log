package com.personal.lifelog.data.backup

import android.app.Application
import android.content.Context
import com.personal.lifelog.data.backup.export.LatestJsonDestination
import com.personal.lifelog.data.backup.export.LatestJsonExporter
import com.personal.lifelog.data.backup.format.BackupJson
import com.personal.lifelog.domain.MealDraft
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatestJsonExporterTest {
    private lateinit var f: BackupFixture
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val destination = MemoryJsonDestination()
    private val preferences get() = f.context.getSharedPreferences("latest-json-test", Context.MODE_PRIVATE)

    @Before fun before() = runBlocking<Unit> {
        f = BackupFixture()
        preferences.edit().clear().commit()
        f.db.metadata().settingsNow()
    }

    @After fun after() = runBlocking {
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        f.close()
    }

    private fun exporter() = LatestJsonExporter(f.db, f.snapshots, f.writes, destination,
        preferences, f.clock, "test-version")

    private suspend fun awaitWrites(count: Int) = withTimeout(3000) {
        while (destination.count.get() < count) delay(20)
    }

    @Test fun usesBackupSchemaAndKeepsPhotoReferencesWithoutReadingFiles() = runBlocking {
        val snapshot = f.seedWithWork()
        snapshot.media.forEach { java.io.File(f.root, it.relativePath).delete() }
        exporter().start(scope)
        awaitWrites(1)
        val actual = JSONObject(destination.content!!)
        assertFalse(actual.has("media"))
        assertFalse(destination.content!!.contains("relativePath"))
        assertFalse(destination.content!!.contains(".jpg"))
        assertEquals(snapshot.meals.first().photoId, actual.getJSONArray("meals").getJSONObject(0).getString("photoId"))
        assertEquals(f.clock.millis(), actual.getLong("generatedAtUtcMs"))
        val expected = BackupJson.encode(snapshot).apply {
            remove("media")
            put("appVersion", "test-version")
            put("generatedAtUtcMs", f.clock.millis())
        }
        assertEquals(expected.toString(), actual.toString())
        assertEquals(snapshot.settings.lastSuccessfulExportAtUtcMs, f.snapshots.read().settings.lastSuccessfulExportAtUtcMs)
    }

    @Test fun committedDaoInsertUpdateAndDeleteRefreshTheFileWithinThreeSeconds() = runBlocking {
        exporter().start(scope)
        awaitWrites(1)
        val mealId = id()
        f.records.saveMeal(MealDraft(mealId, f.clock.millis(), "UTC", "synthetic"))
        awaitWrites(2)
        assertEquals(1, JSONObject(destination.content!!).getJSONArray("meals").length())
        // Go directly through Room, so this proves observation covers callers outside repositories.
        f.db.openHelper.writableDatabase.execSQL("UPDATE MealRecord SET text = 'changed' WHERE id = ?", arrayOf(mealId))
        f.db.invalidationTracker.refreshAsync()
        awaitWrites(3)
        assertEquals("changed", JSONObject(destination.content!!).getJSONArray("meals").getJSONObject(0).getString("text"))
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM MealRecord WHERE id = ?", arrayOf(mealId))
        f.db.invalidationTracker.refreshAsync()
        awaitWrites(4)
        assertEquals(0, JSONObject(destination.content!!).getJSONArray("meals").length())
    }

    @Test fun startupSkipsCurrentFileAndRepairsOldVersionOrMalformedJson() = runBlocking<Unit> {
        destination.content = BackupJson.encode(f.snapshots.read()).apply {
            remove("media")
            put("appVersion", "test-version")
            put("generatedAtUtcMs", 1)
        }.toString()
        val exporter = exporter()
        exporter.start(scope)
        delay(1000)
        assertEquals(0, destination.count.get())
        destination.content = JSONObject(destination.content!!).put("dataVersion", BackupJson.VERSION - 1).toString()
        exporter.requestWrite()
        awaitWrites(1)
        assertEquals(BackupJson.VERSION, JSONObject(destination.content!!).getInt("dataVersion"))
        destination.content = "{broken"
        exporter.requestWrite()
        awaitWrites(2)
        JSONObject(destination.content!!)
    }

    @Test fun rapidChangesAreDebouncedIntoOneWrite() = runBlocking {
        val exporter = exporter()
        exporter.start(scope)
        awaitWrites(1)
        repeat(8) { index ->
            f.records.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "synthetic-$index"))
            exporter.requestWrite()
            delay(40)
        }
        awaitWrites(2)
        delay(800)
        assertEquals(2, destination.count.get())
        assertEquals(8, JSONObject(destination.content!!).getJSONArray("meals").length())
    }

    @Test fun internalTableChangesAlsoTriggerPublication() = runBlocking {
        exporter().start(scope)
        awaitWrites(1)
        val old = f.db.metadata().restoreState()
        f.db.metadata().putRestoreState(old.copy(updatedAtUtcMs = old.updatedAtUtcMs + 1))
        awaitWrites(2)
    }

    @Test fun disabledPreferenceSurvivesRestartAndReenablePublishesLatestData() = runBlocking {
        val exporter = exporter()
        exporter.setEnabled(false)
        exporter.start(scope)
        f.records.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "synthetic"))
        exporter.requestWrite()
        delay(1000)
        assertEquals(0, destination.count.get())
        scope.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val restarted = exporter()
        assertFalse(restarted.enabled.value)
        restarted.start(scope)
        delay(900)
        assertEquals(0, destination.count.get())
        restarted.setEnabled(true)
        awaitWrites(1)
        assertEquals(1, JSONObject(destination.content!!).getJSONArray("meals").length())
    }

    @Test fun storageFailureDoesNotKillObservationAndCanBeRetried() = runBlocking {
        destination.fail = true
        val exporter = exporter()
        exporter.start(scope)
        withTimeout(3000) { while (!exporter.writeFailed.value) delay(20) }
        assertEquals(0, destination.count.get())
        destination.fail = false
        exporter.requestWrite()
        awaitWrites(1)
        assertFalse(exporter.writeFailed.value)
    }

    @Test fun disablingWaitsForPublicationAndPreventsQueuedWrites() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        destination.beforeWrite = {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        val exporter = exporter()
        exporter.start(scope)
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val disable = async(Dispatchers.IO) { exporter.setEnabled(false) }
        delay(50)
        assertFalse(disable.isCompleted)
        release.countDown()
        disable.await()
        assertFalse(exporter.enabled.value)
        f.records.saveMeal(MealDraft(id(), f.clock.millis(), "UTC", "synthetic"))
        exporter.requestWrite()
        delay(1000)
        assertEquals(1, destination.count.get())
    }

    private class MemoryJsonDestination : LatestJsonDestination {
        @Volatile var content: String? = null
        @Volatile var fail = false
        var beforeWrite: () -> Unit = {}
        val count = AtomicInteger()
        override fun read() = content
        override fun write(json: String) {
            if (fail) throw IOException("synthetic storage failure")
            beforeWrite()
            content = json
            count.incrementAndGet()
        }
    }
}
