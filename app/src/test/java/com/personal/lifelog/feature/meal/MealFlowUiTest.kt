package com.personal.lifelog.feature.meal

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.RecordRepository
import com.personal.lifelog.data.repository.SettingsRepository
import com.personal.lifelog.data.repository.TimelineRepository
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * Minimal closed-loop UI test (Robolectric + Compose): create a pure-text meal
 * from the home screen, open detail, edit, cancel an edit, and delete. Photo
 * picker/camera UI and device behavior stay unexecuted and are recorded as such
 * in the handoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MealFlowUiTest {

    @get:Rule
    val compose = createComposeRule()

    private val clock = Clock.fixed(Instant.parse("2026-10-22T20:30:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var root: File

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.filesDir, "t05-ui-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock))
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun setAppContent() {
        val writes = WriteCoordinator()
        val records = RecordRepository(db, writes, clock) { zone }
        val photos = PhotoStorage(
            context = context,
            db = db,
            records = records,
            writes = writes,
            clock = clock,
            root = root,
            openSource = { throw IllegalStateException("no photo in this test") },
            cameraUri = { file -> android.net.Uri.parse("content://synthetic-camera/${file.name}") },
            syncDirectory = { check(it.isDirectory) },
        )
        val settingsViewModel = SettingsViewModel(SettingsRepository(db, WriteCoordinator(), clock))
        val workoutRepository = WorkoutRepository(db, writes, clock)
        val timelineRepository = TimelineRepository(records, workoutRepository)
        val homeViewModel = HomeViewModel(
            clock,
            { zone },
            timelineRepository,
        )
        val sleepViewModel = SleepViewModel(records, clock) { zone }
        compose.setContent {
            LifeLogTheme(ThemeMode.SYSTEM) {
                LifelogApp(
                    homeViewModel = homeViewModel,
                    settingsViewModel = settingsViewModel,
                    sleepViewModel = sleepViewModel,
                    workoutRepository = workoutRepository,
                    timelineRepository = timelineRepository,
                    records = records,
                    photos = photos,
                    clock = clock,
                    zoneIdProvider = { zone },
                )
            }
        }
    }

    private fun mealCount(): Int = runBlocking { db.records().allMeals().size }

    private fun onlyMeal() = runBlocking { db.records().allMeals().singleOrNull() }

    private fun mediaCount(): Int = runBlocking { db.metadata().allMedia().size }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun savedMealSleepShortcutAndExerciseLibraryCoexistAfterIntegration() {
        setAppContent()
        waitForText("准备睡了")
        compose.onNodeWithText("记饮食").performClick()
        compose.onNodeWithTag("meal_text_input").performTextInput("整合测试午饭")
        compose.onNodeWithTag("meal_save_button").performClick()
        waitForText("整合测试午饭")
        compose.onNodeWithText("准备睡了").performClick()
        waitForText("起床了")
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("运动项目管理").performScrollTo().performClick()
        waitForText("新增项目")
        compose.onNodeWithText("搜索项目名称").performTextInput("跑步")
        waitForText("跑步")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("起床了")
        compose.onNodeWithText("整合测试午饭").assertExists()
        assertEquals(1, mealCount())
        runBlocking { assertEquals(1, db.records().allSleeps().size) }
    }

    @Test
    fun createEditCancelAndDeleteFromHome() {
        setAppContent()
        waitForText("这一天还没有记录")

        // Create a pure-text meal: no photo and no satiety are required.
        compose.onNodeWithText("记饮食").performClick()
        compose.onNodeWithTag("meal_text_input").performTextInput("牛肉面")
        compose.onNodeWithTag("meal_save_button").performClick()
        compose.waitUntil(10_000) { mealCount() == 1 }
        waitForText("今日时间线")
        assertEquals("牛肉面", onlyMeal()?.text)
        assertEquals(null, onlyMeal()?.photoId)

        // Detail is opened from the home row.
        compose.onNodeWithText("牛肉面").performClick()
        waitForText("饮食详情")

        // Edit and save.
        compose.onNodeWithContentDescription("编辑记录").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("meal_text_input").assertTextContains("牛肉面") }.isSuccess
        }
        compose.onNodeWithTag("meal_text_input").performTextClearance()
        compose.onNodeWithTag("meal_text_input").performTextInput("牛肉面加蛋")
        compose.onNodeWithTag("meal_save_button").performClick()
        compose.waitUntil(10_000) { onlyMeal()?.text == "牛肉面加蛋" }
        waitForText("牛肉面加蛋")

        // Cancelling an edit must not modify the committed record.
        compose.onNodeWithContentDescription("编辑记录").performClick()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("meal_text_input").assertTextContains("牛肉面加蛋") }.isSuccess
        }
        compose.onNodeWithTag("meal_text_input").performTextClearance()
        compose.onNodeWithTag("meal_text_input").performTextInput("不要保存")
        compose.onNodeWithContentDescription("返回").performClick()
        waitForText("放弃未保存的修改？")
        compose.onNodeWithText("放弃修改").performClick()
        waitForText("饮食详情")
        assertEquals("牛肉面加蛋", onlyMeal()?.text)

        // Confirmed delete removes the record; home reports an actually empty day.
        compose.onNodeWithTag("meal_delete_button").performClick()
        waitForText("删除这条饮食记录？")
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(10_000) { mealCount() == 0 }
        waitForText("这一天还没有记录")
        assertEquals(0, mediaCount())
    }
}
