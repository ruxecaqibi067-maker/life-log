package com.personal.lifelog.feature.exercise

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.repository.WorkoutRepository
import com.personal.lifelog.data.repository.WriteCoordinator
import com.personal.lifelog.domain.*
import com.personal.lifelog.ui.theme.LifeLogTheme
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device interactions: host Robolectric cannot idle even a plain Material dialog here. */
@RunWith(AndroidJUnit4::class)
class ExerciseLibraryUiTest {
    @get:Rule val compose = createComposeRule()
    private val clock = Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var vm: ExerciseLibraryViewModel

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .addCallback(AppDatabase.creationCallback(clock)).build()
        repository = WorkoutRepository(db, WriteCoordinator(), clock)
        runBlocking { db.workouts().allExercises() }
    }
    @After fun close() {
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        db.close()
    }
    private fun screen(onSelected: ((ExerciseDefinition) -> Unit)? = null) {
        compose.runOnUiThread {
            vm = ExerciseLibraryViewModel(repository, SavedStateHandle(), selecting = onSelected != null)
        }
        compose.setContent { LifeLogTheme(ThemeMode.SYSTEM) { ExerciseLibraryScreen(vm, {}, onSelected) } }
        compose.waitForIdle()
        compose.waitUntil(10_000) { !vm.state.value.loading }
    }
    private fun waitForWrite() { compose.waitUntil(10_000) { !vm.busy.value } }

    @Test fun pickerCreatesAndImmediatelyReturnsProjectAndCanSelectItAgain() {
        var selected: ExerciseDefinition? = null
        screen { selected = it }
        compose.onNodeWithText("新增项目").performClick()
        compose.onNodeWithText("项目名称").performTextInput("  虚构弹力带  ")
        compose.onNode(hasText("力量") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { selected != null }
        assertEquals("虚构弹力带", selected!!.name)
        assertEquals(ExerciseType.STRENGTH, selected!!.type)
        val createdId = selected!!.id
        compose.onNodeWithText("搜索项目名称").performTextInput("虚构弹力带")
        compose.waitUntil(10_000) { vm.state.value.rows.size == 1 }
        selected = null
        compose.onNodeWithText("选择").performScrollTo().performClick()
        assertEquals(createdId, selected!!.id)
        assertEquals(9, runBlocking { db.workouts().allExercises().size })
    }

    @Test fun managementCanRenameHideAndEnableWithoutDeletingDefinition() {
        screen()
        compose.onNodeWithText("搜索项目名称").performTextInput("跑步")
        compose.waitUntil(10_000) { vm.state.value.rows.size == 1 }
        compose.onNodeWithText("修改名称").performScrollTo().performClick()
        compose.onNodeWithText("项目名称").performTextReplacement("虚构慢跑")
        compose.onNodeWithText("保存").performClick()
        waitForWrite()
        compose.onNodeWithText("搜索项目名称").performTextReplacement("虚构慢跑")
        compose.waitUntil(10_000) { vm.state.value.rows.size == 1 }
        compose.onNodeWithText("隐藏", substring = false).performScrollTo().performClick()
        waitForWrite()
        compose.waitUntil(10_000) { vm.state.value.rows.isEmpty() }
        compose.onNodeWithText("显示隐藏项目").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.rows.size == 1 }
        compose.onNodeWithText("重新启用").performScrollTo().performClick()
        waitForWrite()
        assertFalse(runBlocking { db.workouts().allExercises().single { it.name == "虚构慢跑" }.isHidden })
        assertEquals(8, runBlocking { db.workouts().allExercises().size })
    }

    @Test fun blankNameIsRejectedAndCancelConfirmsDiscardOfUnfinishedInput() {
        screen()
        compose.onNodeWithText("新增项目").performClick()
        compose.onNodeWithText("项目名称").performTextInput("   ")
        compose.onNode(hasText("其他") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10_000) { vm.error.value == ErrorCode.INVALID_NAME }
        compose.onNodeWithText("请输入去除首尾空白后 1–40 字的名称").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertExists()
        compose.onNodeWithText("继续编辑").performClick()
        assertEquals("   ", vm.editor.value!!.name)
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃修改", substring = false).performClick()
        assertNull(vm.editor.value)
        assertEquals(8, runBlocking { db.workouts().allExercises().size })
    }
}
