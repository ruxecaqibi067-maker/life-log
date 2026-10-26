package com.personal.lifelog.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.personal.lifelog.App
import com.personal.lifelog.MainActivity
import com.personal.lifelog.R
import com.personal.lifelog.data.backup.format.BackupResult
import com.personal.lifelog.data.backup.importer.BackupSource
import com.personal.lifelog.data.backup.restore.RestoreResult
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.domain.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One supervisor-owned acceptance pass. Never run fixtures against a personal installation. */
@RunWith(AndroidJUnit4::class)
class FeedbackRoundTwoDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<App>()
    private val c get() = app.container

    private fun waitTag(tag: String, unmerged: Boolean = false) = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty()
    }
    private fun clickTag(tag: String, scroll: Boolean = false) {
        waitTag(tag)
        val node = compose.onNodeWithTag(tag)
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun back() { compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick() }
    private fun top() { compose.onNodeWithTag("home-timeline").performScrollToIndex(0) }
    private fun photoCount(count: Int) {
        compose.waitUntil(20_000) {
            runCatching { compose.onNodeWithTag("meal-photo-count").assertTextContains(count.toString(), substring = true) }.isSuccess
        }
    }
    private fun shot(name: String) {
        compose.waitForIdle()
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "feedback-round2/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val config = app.resources.configuration
        File(file.parentFile, "$name.json").writeText(org.json.JSONObject()
            .put("fontScale", config.fontScale).put("widthDp", config.screenWidthDp)
            .put("api", android.os.Build.VERSION.SDK_INT).put("widthPx", image.width)
            .put("heightPx", image.height).put("capturedAt", Instant.now().toString())
            .put("timezone", ZoneId.systemDefault().id).toString(2))
        image.recycle()
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    @Test fun allSevenFeedbackItemsWorkTogetherWithRealStorageAndRestore() {
        assertTrue("Fixtures require the dedicated package", app.packageName.endsWith(".feedback2"))
        val zone = ZoneId.systemDefault().id
        val now = c.clock.millis()
        val workId = RecordIds.newId()
        val sleepId = RecordIds.newId()
        val mealId = RecordIds.newId()
        val exerciseId = RecordIds.newId()
        val photoIds = runBlocking {
            assertTrue(c.snapshots.read().meals.isEmpty())
            assertTrue(c.settings.setTheme(ThemeMode.LIGHT) is DataResult.Success)
            assertTrue(c.settings.setTargetSleepMinutes(480) is DataResult.Success)
            assertTrue(c.settings.setTargetWorkMinutes(240) is DataResult.Success)
            assertTrue(c.work.saveWork(WorkDraft(workId, now - 75 * 60_000, zone)) is DataResult.Success)
            assertTrue(c.records.saveSleep(SleepDraft(sleepId, now - 30 * 60_000, zone)) is DataResult.Success)
            assertTrue(c.workouts.saveExercise(ExerciseDraft(exerciseId,
                "统一验收很长的自定义力量训练动作名称用于检查自然换行", ExerciseType.STRENGTH)) is DataResult.Success)
            val prepared = listOf(Color.RED, Color.GREEN, Color.BLUE).map { color ->
                val request = (c.photos.beginCapture() as PhotoResult.Success).value
                val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                app.contentResolver.openOutputStream(request.uri)!!.use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it))
                }
                bitmap.recycle()
                (c.photos.finishCapture(request.ticket, true) as PhotoResult.Success).value
            }
            val ids = prepared.map { it.ticket.id }
            val saved = c.photos.saveMealPhotos(MealDraft(mealId, now, zone,
                text = "统一验收虚构午餐", photoId = ids.first(), photoIds = ids), prepared.map { it.ticket })
            assertTrue(saved is PhotoResult.Success)
            assertEquals(ids, c.records.mealPhotoIds(mealId))
            ids
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitTag("work-active-duration", true); waitTag("sleep-active-duration", true)
            val progress = compose.onNodeWithTag("work-progress", useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertTrue("Active work must have visible progress", progress in 0.30f..0.36f)
            assertNull(runBlocking { c.workSummary.observe(java.time.LocalDate.now()).first() }
                .let { (it as DataResult.Success).value.recordedDurationMs })
            compose.onAllNodesWithText("查看 / 补录").assertCountEquals(0)
            compose.onNodeWithTag("work-edit-active").assertDoesNotExist()
            compose.onNodeWithTag("work-undo").assertDoesNotExist()
            shot("home-live-central-actions")

            clickTag("work-target-edit"); waitTag("work-goal-save")
            compose.onNodeWithTag("work-history").assertDoesNotExist()
            compose.onNodeWithText("取消").performClick()
            val oldHeight = compose.onNodeWithTag("home-timeline").fetchSemanticsNode().boundsInRoot.height
            clickTag("home-quick-toggle")
            compose.onNodeWithTag("home-quick-actions").assertDoesNotExist()
            assertTrue(compose.onNodeWithTag("home-timeline").fetchSemanticsNode().boundsInRoot.height > oldHeight)
            scenario.recreate(); waitTag("home-quick-toggle")
            compose.onNodeWithTag("home-quick-actions").assertDoesNotExist()
            shot("home-quick-collapsed")
            clickTag("home-quick-toggle"); waitTag("work-start-finish")
            clickTag("work-start-finish")
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first() == null } }
            top(); clickTag("work-summary-history")
            clickTag("work-undo", scroll = true)
            compose.onNodeWithText(app.getString(R.string.work_confirm)).performClick()
            compose.waitUntil(20_000) { runBlocking { c.work.activeWork().first()?.id == workId } }
            clickTag("work-edit-active", scroll = true); waitTag("work-form"); back()
            waitTag("work-history"); shot("work-actions-moved-to-history"); back(); waitTag("home-timeline")
            top(); clickTag("sleep-summary-history")
            clickTag("sleep-edit-active", scroll = true); waitTag("sleep-form"); back(); back(); waitTag("home-timeline")

            compose.onNodeWithText("记运动").performClick(); waitTag("workout-form")
            compose.onNodeWithText(app.getString(R.string.workout_add)).performScrollTo().performClick()
            waitTag("exercise-controls")
            compose.onNodeWithTag("exercise-pick-$exerciseId").performScrollTo()
            shot("exercise-compact-normal")
            clickTag("exercise-pick-$exerciseId")
            waitTag("workout-form")
            compose.waitUntil(20_000) {
                runCatching {
                    compose.onNodeWithTag("workout-form").performScrollToNode(
                        hasText("统一验收很长的自定义力量训练动作名称用于检查自然换行", substring = true))
                }.isSuccess
            }
            compose.onNodeWithText("统一验收很长的自定义力量训练动作名称用于检查自然换行", substring = true).assertExists()
            back()
            compose.onNodeWithText(app.getString(R.string.workout_discard)).performClick()
            waitTag("home-timeline")

            compose.onNodeWithTag("home-timeline").performScrollToNode(hasText("统一验收虚构午餐"))
            compose.onNodeWithText("统一验收虚构午餐").performClick()
            waitTag("meal-detail-photos"); photoCount(3)
            clickTag("meal-photo-${photoIds.first()}", scroll = true)
            compose.onNodeWithText("关闭预览").performClick()
            compose.onNodeWithTag("meal-photo-gallery").performScrollToIndex(2)
            compose.onNodeWithTag("meal-photo-${photoIds.last()}").assertIsDisplayed()
            shot("meal-three-photos")
            compose.onNodeWithTag("meal-photo-gallery").performScrollToIndex(0)
            compose.onNodeWithContentDescription(app.getString(R.string.meal_detail_edit)).performClick()
            clickTag("meal-photo-remove-${photoIds.first()}", scroll = true)
            photoCount(2)
            back(); compose.onNodeWithText(app.getString(R.string.meal_discard_confirm)).performClick()
            waitTag("meal-detail-photos"); photoCount(3)
            assertEquals(photoIds, runBlocking { c.records.mealPhotoIds(mealId) })
            compose.onNodeWithContentDescription(app.getString(R.string.meal_detail_edit)).performClick()
            clickTag("meal-photo-remove-${photoIds.first()}", scroll = true)
            clickTag("meal_save_button", scroll = true)
            waitTag("meal-detail-photos"); photoCount(2)
            scenario.recreate(); waitTag("meal-detail-photos")
            assertEquals(photoIds.drop(1), runBlocking { c.records.mealPhotoIds(mealId) })
            shot("meal-two-photos-after-save-recreate")
        }

        runBlocking {
            val before = c.snapshots.read()
            val archive = c.writes.withExclusiveAccess {
                c.backupExporter.prepare(File(app.filesDir, "feedback2-export").apply { mkdirs() })
            }
            val ready = (c.backupImporter.preflight(BackupSource { archive.inputStream() }) as BackupResult.Success).value
            assertEquals(2, ready.preview.counts["media"])
            assertTrue(c.photos.deleteMeal(mealId) is PhotoResult.Success)
            assertTrue(c.records.mealPhotoIds(mealId).isEmpty())
            val result = c.restoreEngine.restore(ready.token)
            assertTrue(result is RestoreResult.Committed || result is RestoreResult.CommittedCleanupPending)
            val after = c.snapshots.read()
            assertEquals(before.mealPhotos, after.mealPhotos)
            assertEquals(before.media.map { it.id to it.sha256 }, after.media.map { it.id to it.sha256 })
            assertEquals(photoIds.drop(1), c.records.mealPhotoIds(mealId))
            photoIds.drop(1).forEach { assertTrue(c.photos.photoUri(it) is PhotoResult.Success) }
        }

        shell("settings put system font_scale 1.5")
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitTag("home-quick-toggle"); assertEquals(1.5f, app.resources.configuration.fontScale, 0.01f)
                compose.onNodeWithTag("home-quick-actions").assertExists()
                shot("home-large-font")
                compose.onNodeWithText("记运动").performClick(); waitTag("workout-form")
                compose.onNodeWithText(app.getString(R.string.workout_add)).performScrollTo().performClick()
                waitTag("exercise-controls"); shot("exercise-controls-large-font")
                compose.onNodeWithTag("exercise-pick-$exerciseId").performScrollTo().assertIsDisplayed()
                shot("exercise-long-name-large-font")
            }
        } finally { shell("settings put system font_scale 1.0") }
    }
}
