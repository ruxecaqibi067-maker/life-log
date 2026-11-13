package com.personal.lifelog.feature.voice

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.lifelog.data.local.AppDatabase
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class QuickRecordViewModelTest {
    private lateinit var db: AppDatabase
    private lateinit var records: RecordRepository
    private lateinit var work: WorkRepository
    private lateinit var workouts: WorkoutRepository
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private val models = mutableListOf<QuickRecordViewModel>()
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = QuickRecordViewModel(records, work, workouts, saved, clock) { zone }.also { models += it }
    private suspend fun ready(vm: QuickRecordViewModel) = withTimeout(10000) { vm.state.first { !it.loading && !it.busy } }
    private suspend fun parse(vm: QuickRecordViewModel, text: String) { ready(vm); vm.rawChanged(text); vm.parse(); ready(vm) }
    private suspend fun save(vm: QuickRecordViewModel) { vm.save(); ready(vm) }
    private fun time(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(), AppDatabase::class.java).addCallback(AppDatabase.creationCallback(clock)).build()
        val writes = WriteCoordinator(); records = RecordRepository(db, writes, clock) { zone }
        work = WorkRepository(db, writes, clock) { zone }; workouts = WorkoutRepository(db, writes, clock)
    }
    @After fun close() { models.forEach { it.viewModelScope.cancel() }; db.close(); Dispatchers.resetMain() }

    @Test fun parsingAndEditingNeverWriteUntilConfirmationAndDoubleSaveUsesOneId() = runBlocking<Unit> {
        val vm = model(); parse(vm, "今天中午十二点吃了面")
        assertTrue(db.records().allMeals().isEmpty())
        vm.change(QuickField.CONTENT, "改成米饭")
        vm.save(); vm.save(); ready(vm)
        assertTrue(vm.state.value.done)
        assertEquals("改成米饭", db.records().allMeals().single().text)
        vm.onVoiceText("quick", "迟到结果"); vm.save()
        assertEquals(1, db.records().allMeals().size)
    }
    @Test fun sourceEditsRequireReparseAndRetainManuallyEditedFields() = runBlocking<Unit> {
        val vm = model(); parse(vm, "吃了面")
        vm.change(QuickField.CONTENT, "手改内容"); vm.change(QuickField.START, time("2026-11-12T12:00").toString())
        vm.rawChanged("吃了鸡蛋"); save(vm)
        assertFalse(vm.state.value.done); assertTrue(db.records().allMeals().isEmpty())
        vm.parse(); assertEquals("手改内容", vm.state.value.draft[QuickField.CONTENT])
        assertEquals(time("2026-11-12T12:00"), vm.state.value.draft.start)
        save(vm); assertTrue(vm.state.value.done)
    }
    @Test fun durationOnlyMustBeAnchoredByUserAndCanThenSaveSleep() = runBlocking<Unit> {
        val vm = model(); parse(vm, "睡了五小时"); save(vm)
        assertFalse(vm.state.value.done); assertTrue(db.records().allSleeps().isEmpty())
        vm.change(QuickField.END, time("2026-11-12T07:00").toString())
        assertEquals(time("2026-11-12T02:00"), vm.state.value.draft.start)
        save(vm); assertTrue(vm.state.value.done)
        assertEquals("2026-11-12", db.records().allSleeps().single().archiveLocalDate)
    }
    @Test fun conflictingSpokenDurationMustBeCorrectedBeforeSave() = runBlocking<Unit> {
        val vm = model(); parse(vm, "昨晚十一点到今天凌晨六点睡了五小时"); save(vm)
        assertTrue(vm.state.value.error!!.contains("不一致")); assertTrue(db.records().allSleeps().isEmpty())
        vm.change(QuickField.DURATION, "420"); save(vm); assertTrue(vm.state.value.done)
    }
    @Test fun workRangeAndWorkoutQuantitiesUseOriginalRepositories() = runBlocking<Unit> {
        val vm = model(); parse(vm, "今天上午九点到十一点工作"); save(vm)
        assertTrue(vm.state.value.done); assertEquals(7200000L, db.work().allWorks().single().let { it.endedAtUtcMs!! - it.startedAtUtcMs })
        val run = model(); parse(run, "今天下午五点跑步三十分钟五公里"); save(run)
        assertTrue(run.state.value.toString(), run.state.value.done)
        val row = db.workouts().allItems().single(); assertEquals(1800L, row.durationSeconds); assertEquals(5000L, row.distanceMeters)
        val strength = model(); parse(strength, "卧推三组每组十次二十公斤"); save(strength)
        assertTrue(strength.state.value.toString(), strength.state.value.done)
        assertEquals(3, db.workouts().allSets().size)
    }
    @Test fun finishRequiresActiveRecordAndBindsSnapshotAgainstExternalChanges() = runBlocking<Unit> {
        val absent = model(); parse(absent, "结束工作"); save(absent)
        assertFalse(absent.state.value.done); assertTrue(db.work().allWorks().isEmpty())
        val id = RecordIds.newId()
        work.startWork(id, clock.millis() - 7200000, zone.id)
        val finishing = model(); parse(finishing, "结束工作")
        work.finishWork(id, clock.millis() - 60000, zone.id)
        save(finishing); assertFalse(finishing.state.value.done)
        assertTrue(finishing.state.value.error!!.contains("变化"))
    }
    @Test fun startSleepAndFinishSleepRemainExplicitAndReturnBedtimeOnlyOnStart() = runBlocking<Unit> {
        val start = model(); parse(start, "准备睡了")
        start.change(QuickField.START, (clock.millis() - 3600000).toString())
        assertTrue(db.records().allSleeps().isEmpty())
        save(start); assertTrue(start.state.value.done); assertTrue(start.state.value.bedtime)
        val finish = model(); parse(finish, "我醒了"); save(finish)
        assertTrue(finish.state.value.done); assertFalse(finish.state.value.bedtime)
        assertEquals(1, db.records().allSleeps().size); assertEquals(clock.millis(), db.records().allSleeps().single().endedAtUtcMs)
    }
    @Test fun overlapAndLongIntervalStillUseOriginalValidation() = runBlocking<Unit> {
        val a = model(); parse(a, "今天上午九点到十一点工作"); save(a)
        val b = model(); parse(b, "今天上午十点到十二点工作"); save(b)
        assertFalse(b.state.value.done); assertTrue(b.state.value.error!!.contains("重叠"))
        val long = model(); parse(long, "前天上午九点到昨天下午五点工作"); save(long)
        assertTrue(long.state.value.longConfirmation); assertFalse(long.state.value.done)
        long.save(true); ready(long); assertTrue(long.state.value.done)
    }
    @Test fun savedStateKeepsDraftManualEditsFrozenReferenceAndSuccessfulOperationIdentity() = runBlocking<Unit> {
        val handle = SavedStateHandle(); val vm = model(handle); parse(vm, "吃了面")
        vm.change(QuickField.CONTENT, "保留手改")
        val copy = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val restored = model(copy); ready(restored); restored.parse(); save(restored)
        assertEquals("保留手改", db.records().allMeals().single().text)
        val afterSave = model(SavedStateHandle(copy.keys().associateWith { copy.get<Any?>(it) })); ready(afterSave)
        afterSave.save(); assertEquals(1, db.records().allMeals().size)
        assertTrue(afterSave.state.value.done)
    }
    @Test fun multipleRecordInputCannotBeSavedByChoosingAType() = runBlocking<Unit> {
        val vm = model(); parse(vm, "睡了五小时又工作了两小时"); vm.kind(QuickKind.WORK)
        vm.change(QuickField.START, time("2026-11-12T09:00").toString()); vm.change(QuickField.END, time("2026-11-12T11:00").toString())
        save(vm); assertFalse(vm.state.value.done); assertTrue(db.work().allWorks().isEmpty())
    }
    @Test fun saveCancelsOutstandingSpeechAndLateTokenCannotUpdateDraft() = runBlocking<Unit> {
        val vm = model(); parse(vm, "吃了面"); vm.voice.begin("quick"); val token = vm.voice.request.value!!.token
        save(vm); assertNull(vm.voice.consume(token)); assertEquals("吃了面", vm.state.value.raw)
    }
    @Test fun interruptedAcknowledgementRecoversWithoutEditingTheAlreadySavedRecord() = runBlocking<Unit> {
        val handle = SavedStateHandle(); val vm = model(handle); parse(vm, "吃了面"); save(vm)
        // Model a process snapshot taken after submission but before the success acknowledgement.
        val snapshot = handle.keys().associateWith { handle.get<Any?>(it) }.toMutableMap()
        snapshot["quick.done"] = false
        val restored = model(SavedStateHandle(snapshot)); ready(restored)
        assertTrue(restored.state.value.done)
        restored.change(QuickField.CONTENT, "不应覆盖"); restored.save()
        assertEquals("面", db.records().allMeals().single().text)
    }
}
