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
    @Test fun voiceSupplementsApplyMinimumWithoutWritingShortRows() = runBlocking<Unit> {
        for (text in listOf("睡了五分钟", "工作了四分钟")) {
            val vm = model(); parse(vm, text)
            vm.change(QuickField.END, clock.millis().toString()); save(vm)
            assertFalse(vm.state.value.done)
            assertTrue(vm.state.value.error!!.contains("至少"))
        }
        assertTrue(db.records().allSleeps().isEmpty()); assertTrue(db.work().allWorks().isEmpty())
    }
    @Test fun voiceFinishUsesPreciseMinimumAndAllowsZeroDurationDiscard() = runBlocking<Unit> {
        for (sleeping in listOf(true, false)) {
            val minimum = if (sleeping) 600000L else 300000L
            for (elapsed in listOf(0L, minimum - 1, minimum)) {
                val id = RecordIds.newId()
                if (sleeping) records.startSleep(id, clock.millis() - elapsed, zone.id)
                else work.startWork(id, clock.millis() - elapsed, zone.id)
                val vm = model(); parse(vm, if (sleeping) "我醒了" else "结束工作"); save(vm)
                assertTrue(vm.state.value.toString(), vm.state.value.done)
                if (sleeping) {
                    assertEquals(elapsed == minimum, records.sleep(id) != null)
                    records.deleteSleep(id)
                } else {
                    assertEquals(elapsed == minimum, work.work(id) != null)
                    work.deleteWork(id)
                }
            }
        }
    }
    @Test fun voiceLongFinishCanCancelAndCorrectToDiscardWithFrozenLimit() = runBlocking<Unit> {
        val id = RecordIds.newId(); val start = clock.millis() - 13 * 3600000L
        work.startWork(id, start, zone.id)
        val vm = model(); parse(vm, "结束工作"); save(vm)
        assertTrue(vm.state.value.longConfirmation); assertNull(work.work(id)!!.endedAtUtcMs)
        vm.cancelLong(); assertNull(work.work(id)!!.endedAtUtcMs)
        save(vm); vm.changeFinishEnd(clock.millis() + 1)
        assertEquals(clock.millis(), vm.state.value.draft.end)
        vm.changeFinishEnd(start + 120000)
        assertTrue(vm.state.value.longConfirmation)
        assertEquals("", vm.state.value.draft[QuickField.DURATION])
        vm.save(true); ready(vm); assertTrue(vm.state.value.done); assertNull(work.work(id))
    }
    @Test fun voiceFinishRejectsSameMillisecondEditsAndRecoversDiscardWithoutRebinding() = runBlocking<Unit> {
        val id = RecordIds.newId(); work.startWork(id, clock.millis() - 3600000, zone.id)
        val vm = model(); parse(vm, "结束工作")
        val before = work.work(id)!!
        work.editWork(WorkDraft(id, before.startedAtUtcMs + 60000, zone.id), before)
        assertEquals(before.updatedAtUtcMs, work.work(id)!!.updatedAtUtcMs)
        save(vm); assertFalse(vm.state.value.done); assertTrue(vm.state.value.error!!.contains("变化"))
        work.deleteWork(id)
        val shortId = RecordIds.newId(); work.startWork(shortId, clock.millis(), zone.id)
        val handle = SavedStateHandle(); val short = model(handle); parse(short, "结束工作"); save(short)
        val state = handle.keys().associateWith { handle.get<Any?>(it) }.toMutableMap(); state["quick.done"] = false
        val newId = RecordIds.newId(); work.startWork(newId, clock.millis(), zone.id)
        val restored = model(SavedStateHandle(state)); ready(restored)
        assertTrue(restored.state.value.done); assertNull(work.work(newId)!!.endedAtUtcMs)
    }
    @Test fun voiceFinishRegistersSafeGlobalUndoAndClearsDiscardedStart() = runBlocking<Unit> {
        val parent = com.personal.lifelog.feature.work.WorkViewModel(work, clock) { zone }
        try {
            withTimeout(10000) { parent.status.first { it.loaded && !it.failed } }
            val id = RecordIds.newId(); parent.start(id)
            withTimeout(10000) { parent.busy.first { !it } }
            assertNotNull(parent.undo.value)
            val quick = QuickRecordViewModel(records, work, workouts, SavedStateHandle(), clock,
                onWorkSaved = parent::acceptExternalChange, zoneProvider = { zone }).also { models += it }
            parse(quick, "结束工作"); save(quick)
            assertTrue(quick.state.value.done); assertNull(parent.undo.value); assertNull(work.work(id))
            val normalId = RecordIds.newId(); work.startWork(normalId, clock.millis() - 3600000, zone.id)
            val normal = QuickRecordViewModel(records, work, workouts, SavedStateHandle(), clock,
                onWorkSaved = parent::acceptExternalChange, zoneProvider = { zone }).also { models += it }
            parse(normal, "结束工作"); save(normal)
            assertEquals(normalId, parent.undo.value!!.saved.id); assertNotNull(parent.undo.value!!.previous)
            val undoBeforeSupplement = parent.undo.value
            val supplement = QuickRecordViewModel(records, work, workouts, SavedStateHandle(), clock,
                onWorkSaved = parent::acceptExternalChange, zoneProvider = { zone }).also { models += it }
            parse(supplement, "昨天下午一点到两点工作"); save(supplement)
            assertTrue(supplement.state.value.done)
            assertEquals(undoBeforeSupplement, parent.undo.value)
            parent.undoLast(); withTimeout(10000) { parent.busy.first { !it } }
            assertNull(work.work(normalId)!!.endedAtUtcMs)
        } finally { parent.viewModelScope.cancel() }
    }
}
