package com.personal.lifelog.feature.workout

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.ThemeMode
import com.personal.lifelog.feature.home.HomeViewModel
import com.personal.lifelog.feature.settings.SettingsViewModel
import com.personal.lifelog.feature.sleep.SleepViewModel
import com.personal.lifelog.ui.navigation.LifelogApp
import com.personal.lifelog.ui.theme.LifeLogTheme
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

/** Real Android follow-up: dialogs, keyboard input, failure retry, and deletion. */
@RunWith(AndroidJUnit4::class)
class WorkoutDeviceTest {
    @get:Rule val compose = createComposeRule()
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
        compose.onNodeWithText("添加项目").performClick()
        waitText("搜索项目名称")
        compose.onNodeWithText("搜索项目名称").performTextClearance()
        compose.onNodeWithText("搜索项目名称").performTextInput(name)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("选择").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("选择").performScrollTo().performClick()
        waitText("保存训练")
    }
    private fun enter(label: String, value: String) {
        compose.onNodeWithTag("workout-form").performScrollToNode(hasText(label))
        compose.onNodeWithText(label).performScrollTo().performTextInput(value)
    }

    @Test fun runWithNumbersCanRetryFailedSaveEditDiscardAndDelete() {
        compose.onNodeWithText("记运动").performClick(); waitText("保存训练")
        add("跑步")
        enter("时长（分钟，选填）", "20.5"); enter("距离（km，选填）", "3.205")
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_item BEFORE INSERT ON WorkoutItem BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        compose.onNodeWithText("保存训练").performClick()
        waitText("读取或保存失败，请重试；未保存的输入已保留。")
        assertTrue(runBlocking { db.workouts().allSessions().isEmpty() })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_item")
        compose.onNodeWithText("保存训练").performClick(); waitText("跑步")
        compose.onNodeWithText("跑步").performScrollTo().performClick(); waitText("训练详情")
        waitText("距离：3.205 km")
        compose.onNodeWithText("时长：20 分钟").assertExists()
        compose.onNodeWithText("30 秒").assertExists()
        compose.onNodeWithText("距离：3.205 km").assertExists()
        val original = runBlocking { db.workouts().allSessions().single() }
        val item = runBlocking { db.workouts().allItems().single() }
        assertEquals(1230L, item.durationSeconds); assertEquals(3205L, item.distanceMeters)
        compose.onNodeWithText("编辑训练").performScrollTo().performClick(); waitText("保存训练")
        enter("整次训练备注（选填，最多1000字）", "不要保存")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("放弃修改").performClick(); waitText("训练详情")
        assertEquals(original, runBlocking { db.workouts().allSessions().single() })
        compose.onNodeWithText("删除训练").performScrollTo().performClick()
        compose.onAllNodesWithText("删除训练").onLast().performClick()
        compose.waitUntil(10_000) { runBlocking { db.workouts().allSessions().isEmpty() } }
        assertTrue(runBlocking { db.workouts().allItems().isEmpty() })
        assertTrue(runBlocking { db.workouts().allSets().isEmpty() })
    }

    @Test fun newCustomProjectReturnsToTrainingAndCanSaveWithNoNumbers() {
        compose.onNodeWithText("记运动").performClick(); waitText("保存训练")
        compose.onNodeWithText("添加项目").performScrollTo().performClick(); waitText("新增项目")
        compose.onNodeWithText("新增项目").performClick()
        compose.onNodeWithText("项目名称").performTextInput("虚构自定义力量")
        compose.onNodeWithText("力量", useUnmergedTree = true).performClick()
        compose.onNodeWithText("保存").performClick(); waitText("保存训练")
        compose.onNodeWithText("保存训练").performClick(); waitText("虚构自定义力量")
        assertEquals("虚构自定义力量", runBlocking { db.workouts().allItems().single().nameSnapshot })
        assertTrue(runBlocking { db.workouts().allSets().isEmpty() })
        assertEquals(9, runBlocking { db.workouts().allExercises().size })
    }
}
