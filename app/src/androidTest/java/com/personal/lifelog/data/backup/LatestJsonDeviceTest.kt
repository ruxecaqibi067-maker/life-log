package com.personal.lifelog.data.backup

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.data.backup.export.BackupDestination
import com.personal.lifelog.data.backup.export.SharedLatestJsonDestination
import com.personal.lifelog.data.backup.format.BackupJson
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.domain.DataResult
import com.personal.lifelog.domain.MealDraft
import com.personal.lifelog.domain.RecordIds
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only in a disposable emulator using -PdebugTestSuffix=.latestjsontest. */
@RunWith(AndroidJUnit4::class)
class LatestJsonDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<App>()
    private val container get() = app.container
    private val destination = SharedLatestJsonDestination(app)

    private suspend fun awaitMealCount(count: Int): JSONObject = withTimeout(3000) {
        while (true) {
            val json = destination.read()?.let { JSONObject(it) }
            if (json?.getJSONArray("meals")?.length() == count) return@withTimeout json
            delay(40)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    @Test fun autoExportOverwriteDisableManualBackupAndActivityClose() = runBlocking {
        check(app.packageName.endsWith(".latestjsontest")) { "Disposable test package required" }
        container.latestJson.setEnabled(true)
        val initial = container.snapshots.read().meals.size
        awaitMealCount(initial)

        val first = RecordIds.newId()
        assertTrue(container.records.saveMeal(MealDraft(first, container.clock.millis(), "UTC", "synthetic first")) is DataResult.Success)
        val added = awaitMealCount(initial + 1)
        assertEquals(BackupJson.VERSION, added.getInt("dataVersion"))
        assertTrue(added.has("generatedAtUtcMs"))
        assertTrue(added.has("appVersion"))
        assertFalse(added.has("media"))
        assertFalse(added.toString().contains("relativePath"))
        assertFalse(added.toString().contains(".jpg"))

        assertTrue(container.records.deleteMeal(first) is DataResult.Success)
        awaitMealCount(initial)

        container.latestJson.setEnabled(false)
        val disabledContent = destination.read()
        val second = RecordIds.newId()
        assertTrue(container.records.saveMeal(MealDraft(second, container.clock.millis(), "UTC", "synthetic second")) is DataResult.Success)
        delay(1000)
        assertEquals(disabledContent, destination.read())

        val zip = File(app.cacheDir, "latest-json-manual-regression.zip")
        try {
            val result = container.backupExporter.export(object : BackupDestination {
                override fun open() = zip.outputStream()
                override fun deleteIncomplete() { zip.delete() }
            })
            assertTrue(result is BackupResult.Success)
            ZipFile(zip).use { archive ->
                assertNotNull(archive.getEntry("manifest.json"))
                val data = JSONObject(archive.getInputStream(archive.getEntry("data.json")).bufferedReader().use { it.readText() })
                assertTrue(data.has("media"))
                assertEquals(BackupJson.VERSION, data.getInt("dataVersion"))
            }
            assertEquals(disabledContent, destination.read())
        } finally { zip.delete() }

        container.latestJson.setEnabled(true)
        awaitMealCount(initial + 1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val third = RecordIds.newId()
        assertTrue(container.records.saveMeal(MealDraft(third, container.clock.millis(), "UTC", "synthetic third")) is DataResult.Success)
        scenario.close()
        awaitMealCount(initial + 2)
        assertFalse(container.latestJson.writeFailed.value)
        // The adb acceptance pass verifies this preference and the file across real process restart.
        container.latestJson.setEnabled(false)
    }
}
