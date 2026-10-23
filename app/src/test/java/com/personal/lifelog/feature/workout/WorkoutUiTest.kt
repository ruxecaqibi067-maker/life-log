package com.personal.lifelog.feature.workout

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** Host UI + synthetic screenshot evidence; does not claim device verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneId.of("Asia/Shanghai")
    private lateinit var db: AppDatabase
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        runBlocking { db.workouts().allExercises() }
        val writes = WriteCoordinator()
        val records = RecordRepository(db, writes, clock) { zone }
        val home = HomeViewModel(clock, { zone }, db.records()::datesWithRecords, db.records()::mealsOn)
        val settings = SettingsViewModel(SettingsRepository(db, writes, clock))
        val sleep = SleepViewModel(records, clock) { zone }
        val workouts = WorkoutRepository(db, writes, clock)
        val photos = PhotoStorage(context, db, records, writes, clock)
        compose.setContent { LifeLogTheme(ThemeMode.LIGHT) {
            LifelogApp(home, settings, records, photos, clock, { zone }, sleep, workouts)
        } }
    }
    @After fun close() { db.close() }
    private fun waitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun add(name: String) {
        compose.onNodeWithTag("workout-form").performScrollToNode(hasText("添加项目"))
        compose.onNodeWithText("添加项目").performScrollTo().performClick()
        waitText("搜索项目名称")
        compose.onNodeWithText("搜索项目名称").performTextClearance()
        compose.onNodeWithText("搜索项目名称").performTextInput(name)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("选择").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("选择").performScrollTo().performClick()
        waitText("保存训练")
    }

    @Test fun realNavigationPickerSaveDetailEditAndMultiItemScreenshot() {
        compose.onNodeWithText("记运动").performClick()
        waitText("保存训练")
        add("卧推"); add("深蹲"); add("卧推")
        compose.onNodeWithTag("workout-form").performScrollToIndex(0)
        compose.onNodeWithText("整次训练备注（选填，最多1000字）").performScrollTo().performTextInput("虚构示例：基础训练")
        compose.onNodeWithText("保存训练").performClick()
        compose.waitUntil(10_000) { runBlocking { db.workouts().allSessions().size == 1 } }
        waitText("卧推、深蹲、卧推")
        compose.onNodeWithText("卧推、深蹲、卧推").performScrollTo().performClick()
        waitText("训练详情")
        waitText("1. 卧推 · 力量")
        compose.onNodeWithText("1. 卧推 · 力量").assertExists()
        compose.onNodeWithText("2. 深蹲 · 力量").assertExists()
        compose.onNodeWithText("3. 卧推 · 力量").assertExists()
        compose.onNodeWithText("虚构示例：基础训练").assertExists()
        val evidence = File("../artifacts/t08/multi-item-host.png").absoluteFile
        evidence.parentFile!!.mkdirs()
        // Host PixelCopy has no callback here. Draw the real view with Robolectric native graphics.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            evidence.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("编辑训练").performScrollTo().performClick()
        waitText("保存训练")
        compose.onNodeWithTag("workout-form").performScrollToNode(hasContentDescription("上移项目 2"))
        compose.onNodeWithContentDescription("上移项目 2").performScrollTo().performClick()
        compose.onNodeWithTag("workout-form").performScrollToNode(hasContentDescription("移除项目 3"))
        compose.onNodeWithContentDescription("移除项目 3").performScrollTo().performClick()
        compose.onNodeWithText("保存训练").performClick()
        waitText("1. 深蹲 · 力量")
        val stored = runBlocking { db.workouts().allItems() }
        assertEquals(listOf("深蹲", "卧推"), stored.map { it.nameSnapshot })
        assertTrue(stored.all { it.durationSeconds == null && it.distanceMeters == null })
        assertTrue(runBlocking { db.workouts().allSets().isEmpty() })
        assertEquals(1, runBlocking { db.workouts().allSessions().size })
    }
}
