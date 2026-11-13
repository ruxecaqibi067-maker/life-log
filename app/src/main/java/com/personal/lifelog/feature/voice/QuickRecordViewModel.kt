package com.personal.lifelog.feature.voice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.lifelog.core.validation.Units
import com.personal.lifelog.core.time.IntervalDurationPolicy
import com.personal.lifelog.data.local.entity.SleepRecord
import com.personal.lifelog.data.local.entity.WorkRecord
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.data.repository.*
import com.personal.lifelog.domain.*
import com.personal.lifelog.ui.voice.VoiceInputSession
import java.time.*
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class QuickRecordState(
    val raw: String = "", val draft: QuickDraft = QuickDraft(), val loading: Boolean = true,
    val busy: Boolean = false, val preview: Boolean = false, val needsParse: Boolean = false, val loadFailed: Boolean = false,
    val notices: List<String> = emptyList(), val multiple: Boolean = false,
    val error: String? = null, val longConfirmation: Boolean = false,
    val done: Boolean = false, val bedtime: Boolean = false, val manual: Set<QuickField> = emptySet(),
)

/** Owns one transient operation. No Room write is made by parsing, editing or speech callbacks. */
internal class QuickRecordViewModel(
    private val records: RecordRepository, private val work: WorkRepository,
    private val workouts: WorkoutRepository, private val saved: SavedStateHandle,
    private val clock: Clock,
    private val onSleepSaved: (SleepRecord?, SleepRecord?) -> Unit = { _, _ -> },
    private val onWorkSaved: (WorkRecord?, WorkRecord?) -> Unit = { _, _ -> },
    zoneProvider: () -> ZoneId,
) : ViewModel() {
    val zone: ZoneId = ZoneId.of(saved.get<String>("quick.zone") ?: zoneProvider().id.also { saved["quick.zone"] = it })
    val reference: Instant = Instant.ofEpochMilli(saved.get<Long>("quick.reference") ?: clock.millis().also { saved["quick.reference"] = it })
    private val id = saved.get<String>("quick.id") ?: RecordIds.newId().also { saved["quick.id"] = it }
    val voice = VoiceInputSession(saved, "quick.voice")
    private val mutable = MutableStateFlow(QuickRecordState(
        raw = saved["quick.raw"] ?: "",
        draft = QuickDraft(QuickField.entries.mapIndexedNotNull { i, f -> saved.get<ArrayList<String>>("quick.fields")?.getOrNull(i)?.let { f to it } }.toMap()),
        preview = saved["quick.preview"] ?: false, needsParse = saved["quick.needsParse"] ?: false,
        notices = saved.get<ArrayList<String>>("quick.notices").orEmpty(), multiple = saved["quick.multiple"] ?: false,
        done = saved["quick.done"] ?: false, bedtime = saved["quick.bedtime"] ?: false,
        manual = saved.get<ArrayList<String>>("quick.manual").orEmpty().mapNotNull { runCatching { QuickField.valueOf(it) }.getOrNull() }.toSet(),
    ))
    val state = mutable.asStateFlow()
    var exercises: List<ExerciseDefinition> = emptyList(); private set

    init { load() }
    fun load() {
        if (state.value.busy) return
        mutable.value = state.value.copy(loading = true, loadFailed = false, error = null)
        viewModelScope.launch {
            try {
                exercises = workouts.exercises().first()
                recoverSubmittedOperation()
                mutable.value = state.value.copy(loading = false)
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.value = state.value.copy(loading = false, loadFailed = true, error = "运动项目加载失败，请重试") }
        }
    }
    private suspend fun recoverSubmittedOperation() {
        if (state.value.done || saved.get<Boolean>("quick.submitted") != true) return
        val d = state.value.draft
        val finishId = saved.get<String>("quick.activeId")
        val exists = when (d.kind) {
            QuickKind.MEAL -> records.meal(id) != null
            QuickKind.WORKOUT -> workouts.workout(id) != null
            QuickKind.SLEEP -> if (d.action == QuickAction.FINISH) finishId?.let { records.sleep(it) }?.let { it.endedAtUtcMs == d.end } ?: submittedShortFinish(d, finishId) else records.sleep(id) != null
            QuickKind.WORK -> if (d.action == QuickAction.FINISH) finishId?.let { work.work(it) }?.let { it.endedAtUtcMs == d.end } ?: submittedShortFinish(d, finishId) else work.work(id) != null
            null -> false
        }
        if (exists) set(state.value.copy(done = true, bedtime = d.kind == QuickKind.SLEEP && d.action == QuickAction.START && records.sleep(id)?.endedAtUtcMs == null))
    }
    private fun submittedShortFinish(draft: QuickDraft, target: String?): Boolean =
        target != null && saved.get<String>("quick.submittedTarget") == target &&
            saved.get<Boolean>("quick.submittedShort") == true && draft.action == QuickAction.FINISH

    private fun snapshot(row: SleepRecord) = arrayListOf(row.id, row.startedAtUtcMs.toString(), row.startZoneId,
        row.endedAtUtcMs?.toString().orEmpty(), row.endZoneId.orEmpty(), row.archiveLocalDate,
        row.activeSlot?.toString().orEmpty(), row.createdAtUtcMs.toString(), row.updatedAtUtcMs.toString())

    private fun snapshot(row: WorkRecord) = arrayListOf(row.id, row.startedAtUtcMs.toString(), row.startZoneId,
        row.endedAtUtcMs?.toString().orEmpty(), row.endZoneId.orEmpty(), row.archiveLocalDate,
        row.activeSlot?.toString().orEmpty(), row.createdAtUtcMs.toString(), row.updatedAtUtcMs.toString())

    fun changeFinishEnd(value: Long) {
        val start = state.value.draft.start ?: return
        if (value <= start || value > reference.toEpochMilli()) return
        if (!editable()) return
        voice.cancel()
        set(state.value.copy(
            draft = state.value.draft.with(QuickField.END, value.toString()).with(QuickField.DURATION, ""),
            manual = state.value.manual + setOf(QuickField.END, QuickField.DURATION), error = null,
        ))
    }
    private fun editable() = !state.value.busy && !state.value.done && !state.value.loading && !state.value.loadFailed
    fun captureOnce() {
        if (!editable() || saved.get<Boolean>("quick.captureUsed") == true) return
        saved["quick.captureUsed"] = true
        beginVoice()
    }
    fun beginVoice() { if (editable()) voice.begin("quick") }
    fun onVoiceText(target: String, text: String) {
        if (target != "quick" || !editable()) return
        rawChanged(state.value.raw + text)
        parse()
    }
    fun rawChanged(text: String) {
        if (!editable()) return
        voice.cancel()
        set(state.value.copy(raw = text, needsParse = true, error = null, longConfirmation = false))
    }
    fun change(field: QuickField, value: String) {
        if (!editable()) return
        voice.cancel()
        var draft = state.value.draft.with(field, value)
        if (draft.kind in setOf(QuickKind.SLEEP, QuickKind.WORK) && draft.action == QuickAction.RECORD && field in setOf(QuickField.START, QuickField.END, QuickField.DURATION)) {
            val seconds = runCatching { Units.minutesToSeconds(draft[QuickField.DURATION]) }.getOrNull()
            if (seconds != null) {
                if (draft.start != null && draft.end == null) draft = draft.with(QuickField.END, (draft.start!! + seconds * 1000).toString())
                else if (draft.end != null && draft.start == null) draft = draft.with(QuickField.START, (draft.end!! - seconds * 1000).toString())
            }
        }
        set(state.value.copy(draft = draft, preview = true, manual = state.value.manual + field, error = null, longConfirmation = false))
        if (field == QuickField.ACTION) bindFinish()
    }
    fun kind(kind: QuickKind) {
        if (!editable()) return
        set(state.value.copy(draft = QuickDraft().with(QuickField.KIND, kind.name), manual = setOf(QuickField.KIND)))
        parse()
    }
    fun parse() {
        if (!editable()) return
        voice.cancel()
        val current = state.value
        val forced = current.draft.kind.takeIf { QuickField.KIND in current.manual }
        val result = QuickRecordParser.parse(current.raw, reference, zone, exercises, forced)
        val retained = current.draft.values.filterKeys { it in current.manual }
        set(current.copy(draft = QuickDraft(result.draft.values + retained), preview = true, needsParse = false,
            notices = result.notices + if (retained.isEmpty()) emptyList() else listOf("已保留手动修改的字段；保存以预览字段为准"),
            multiple = result.multiple, error = null, longConfirmation = false))
        bindFinish()
    }
    private fun bindFinish() {
        val draft = state.value.draft
        if (draft.action != QuickAction.FINISH || draft.kind !in setOf(QuickKind.SLEEP, QuickKind.WORK)) return
        // Busy protects this snapshot from being rebound by a late parse or another field change.
        mutable.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val sleep = if (draft.kind == QuickKind.SLEEP) records.activeSleep().first() else null
                val working = if (draft.kind == QuickKind.WORK) work.activeWork().first() else null
                val activeId = sleep?.id ?: working?.id
                val version = sleep?.updatedAtUtcMs ?: working?.updatedAtUtcMs
                saved["quick.activeId"] = activeId
                saved["quick.activeVersion"] = version
                saved["quick.activeSnapshot"] = sleep?.let(::snapshot) ?: working?.let(::snapshot)
                val start = sleep?.startedAtUtcMs ?: working?.startedAtUtcMs
                set(state.value.copy(busy = false, draft = state.value.draft.with(QuickField.START, start?.toString().orEmpty())
                    .with(QuickField.END, state.value.draft.end?.toString() ?: reference.toEpochMilli().toString()),
                    error = if (activeId == null) "没有对应的进行中记录，请选择补录并补全起止时间" else null))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { mutable.value = state.value.copy(busy = false, error = "读取进行中记录失败，请重新解析") }
        }
    }
    fun close() { voice.cancel() }
    fun cancelLong() { mutable.value = state.value.copy(longConfirmation = false) }
    fun save(confirmLong: Boolean = false) {
        if (!editable()) return
        voice.cancel()
        val s = state.value
        val problem = validate(s)
        if (problem != null) { mutable.value = s.copy(error = problem); return }
        val d = s.draft
        saved["quick.submitted"] = true
        saved["quick.submittedTarget"] = saved.get<String>("quick.activeId")
        val minimum = if (d.kind == QuickKind.SLEEP) IntervalDurationPolicy.MIN_SLEEP_MS else IntervalDurationPolicy.MIN_WORK_MS
        saved["quick.submittedShort"] = d.action == QuickAction.FINISH &&
            runCatching { IntervalDurationPolicy.elapsed(d.start!!, d.end!!) < minimum }.getOrDefault(false)
        mutable.value = s.copy(busy = true, error = null, longConfirmation = false)
        viewModelScope.launch {
            try {
                var previousSleep: SleepRecord? = null
                var previousWork: WorkRecord? = null
                val result: DataResult<*> = when (d.kind!!) {
                    QuickKind.MEAL -> records.saveMeal(MealDraft(id, d.start!!, zone.id, d[QuickField.CONTENT]))
                    QuickKind.WORKOUT -> workouts.saveWorkout(workout(d))
                    QuickKind.SLEEP -> when (d.action) {
                        QuickAction.RECORD -> records.saveSleep(SleepDraft(id, d.start!!, zone.id, d.end!!, zone.id, confirmLong), checkDuration = true)
                        QuickAction.START -> records.startSleep(id, d.start!!, zone.id).let { r ->
                            if (r is DataResult.Success && r.value.id != id) DataResult.Failure(DataError(ErrorCode.OVERLAPPING_SLEEP)) else r
                        }
                        QuickAction.FINISH -> {
                            val old = saved.get<String>("quick.activeId")?.let { records.sleep(it) }
                            if (old == null || old.endedAtUtcMs != null || old.updatedAtUtcMs != saved.get<Long>("quick.activeVersion")) DataResult.Failure(DataError(ErrorCode.NOT_FOUND))
                            else if (snapshot(old) != saved.get<ArrayList<String>>("quick.activeSnapshot")) DataResult.Failure(DataError(ErrorCode.NOT_FOUND))
                            else { previousSleep = old; records.completeSleep(old, d.end!!, zone.id, confirmLong) }
                        }
                    }
                    QuickKind.WORK -> when (d.action) {
                        QuickAction.RECORD -> work.saveWork(WorkDraft(id, d.start!!, zone.id, d.end!!, zone.id, confirmLong), checkDuration = true)
                        QuickAction.START -> work.startWork(id, d.start!!, zone.id).let { r ->
                            if (r is DataResult.Success && r.value.id != id) DataResult.Failure(DataError(ErrorCode.OVERLAPPING_WORK)) else r
                        }
                        QuickAction.FINISH -> {
                            val old = saved.get<String>("quick.activeId")?.let { work.work(it) }
                            if (old == null || old.endedAtUtcMs != null || old.updatedAtUtcMs != saved.get<Long>("quick.activeVersion")) DataResult.Failure(DataError(ErrorCode.NOT_FOUND))
                            else if (snapshot(old) != saved.get<ArrayList<String>>("quick.activeSnapshot")) DataResult.Failure(DataError(ErrorCode.NOT_FOUND))
                            else { previousWork = old; work.completeWork(old, d.end!!, zone.id, confirmLong) }
                        }
                    }
                }
                when (result) {
                    is DataResult.Success -> {
                        if (d.action != QuickAction.RECORD) when (d.kind) {
                            QuickKind.SLEEP -> onSleepSaved(result.value as SleepRecord?, previousSleep)
                            QuickKind.WORK -> onWorkSaved(result.value as WorkRecord?, previousWork)
                            else -> Unit
                        }
                        set(state.value.copy(busy = false, done = true, bedtime = d.kind == QuickKind.SLEEP && d.action == QuickAction.START))
                    }
                    is DataResult.Failure -> {
                        saved["quick.submitted"] = false
                        mutable.value = state.value.copy(busy = false,
                            longConfirmation = result.error.code == ErrorCode.LONG_INTERVAL_CONFIRMATION,
                            error = if (result.error.code == ErrorCode.LONG_INTERVAL_CONFIRMATION) null else errorText(result.error.code))
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: DataViolation) { mutable.value = state.value.copy(busy = false, error = errorText(e.error.code))
            } catch (_: Exception) { mutable.value = state.value.copy(busy = false, error = "保存失败，草稿已保留，请重试") }
        }
    }
    private fun validate(s: QuickRecordState): String? {
        val d = s.draft
        if (!s.preview || s.needsParse) return "请先解析修改后的文字，再确认预览"
        if (s.multiple) return "一次只能处理一条记录，请拆开口述并重新解析"
        val kind = d.kind ?: return "请选择记录类型"
        if (kind in setOf(QuickKind.SLEEP, QuickKind.WORK) && d.action == QuickAction.FINISH && saved.get<String>("quick.activeId") == null) return "没有对应的进行中记录，请选择补录并补全时间"
        if (d.start == null) return if (kind in setOf(QuickKind.SLEEP, QuickKind.WORK)) "请补一个明确时间点；仅有时长不能确定记录区间" else "请选择记录时间"
        if (kind in setOf(QuickKind.SLEEP, QuickKind.WORK)) {
            if (d.start!! > clock.millis()) return "开始时间在未来，请核对日期"
            if (d.action != QuickAction.START && d.end == null) return "请补全结束时间"
            if (d.action != QuickAction.START && (d.end!! < d.start!! || (d.action == QuickAction.RECORD && d.end == d.start))) return "结束时间必须晚于开始时间，请核对跨日日期"
            if (d.action != QuickAction.START && d.end!! > clock.millis()) return "结束时间在未来，请核对日期"
            val seconds = try { Units.minutesToSeconds(d[QuickField.DURATION]) } catch (_: DataViolation) { return "请填写有效的正数时长（分钟）" }
            if (d.action != QuickAction.START && seconds != null && abs(d.end!! - d.start!! - seconds * 1000) > 1000) return "起止时间与口述时长不一致，请修改时间或时长"
        }
        if (kind == QuickKind.MEAL && d[QuickField.CONTENT].isBlank()) return "请填写吃了什么"
        if (kind == QuickKind.WORKOUT) {
            if (exercises.none { it.id == d[QuickField.EXERCISE] }) return "请选择现有运动项目"
            try { workout(d) } catch (_: Exception) { return "请检查运动数量；组数1–100，次数和时长须为正数，重量可为0" }
        }
        return null
    }
    private fun workout(d: QuickDraft): WorkoutDraft {
        val definition = exercises.first { it.id == d[QuickField.EXERCISE] }
        val count = d[QuickField.SETS].takeIf { it.isNotBlank() }?.toIntOrNull() ?: if (d[QuickField.SETS].isBlank()) 1 else reject(ErrorCode.INVALID_NUMBER)
        if (count !in 1..100) reject(ErrorCode.INVALID_NUMBER)
        val reps = Units.repetitions(d[QuickField.REPS]); val weight = Units.kilogramsToGrams(d[QuickField.WEIGHT])
        if (definition.type == ExerciseType.STRENGTH && d[QuickField.SETS].isNotBlank() && reps == null && weight == null) reject(ErrorCode.INVALID_NUMBER)
        val sets = if (reps == null && weight == null) emptyList() else List(count) { WorkoutSetDraft(RecordIds.newId(), weight, reps) }
        return WorkoutDraft(id, d.start!!, zone.id, listOf(WorkoutItemDraft(id, definition.id,
            Units.minutesToSeconds(d[QuickField.DURATION]), Units.kilometersToMeters(d[QuickField.DISTANCE]), sets = sets)), d[QuickField.CONTENT])
    }
    private fun set(value: QuickRecordState) {
        saved["quick.raw"] = value.raw; saved["quick.fields"] = ArrayList(QuickField.entries.map { value.draft[it] })
        saved["quick.manual"] = ArrayList(value.manual.map { it.name }); saved["quick.preview"] = value.preview
        saved["quick.needsParse"] = value.needsParse; saved["quick.notices"] = ArrayList(value.notices)
        saved["quick.multiple"] = value.multiple; saved["quick.done"] = value.done; saved["quick.bedtime"] = value.bedtime
        mutable.value = value
    }
    private fun errorText(code: ErrorCode): String = when (code) {
        ErrorCode.OVERLAPPING_SLEEP, ErrorCode.OVERLAPPING_WORK -> "与已有或进行中的记录重叠，请先处理原记录"
        ErrorCode.NOT_FOUND -> "进行中记录已变化，请重新解析并确认目标"
        ErrorCode.TEXT_TOO_LONG -> "文字超过原字段上限，请缩短后保存"
        ErrorCode.EMPTY_MEAL -> "请填写饮食内容"
        ErrorCode.INVALID_INTERVAL -> "起止时间无效，请核对"
        ErrorCode.INTERVAL_TOO_SHORT -> "睡眠至少10分钟、工作至少5分钟，请调整补录时段"
        ErrorCode.INVALID_FIELDS_FOR_TYPE -> "数量与运动类型不符，请清空不适用的字段"
        ErrorCode.HIDDEN_EXERCISE -> "运动项目已隐藏，请重新选择"
        else -> "保存失败，请检查字段或稍后重试"
    }
}
