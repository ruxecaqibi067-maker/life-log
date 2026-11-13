package com.personal.lifelog.feature.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.*
import com.personal.lifelog.ui.voice.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun QuickRecordScreen(vm: QuickRecordViewModel, capture: Boolean, onBack: () -> Unit, onSaved: (Boolean) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.voice.request.collectAsStateWithLifecycle()
    BindVoiceInput(vm.voice, ready = !state.loading && !state.loadFailed, onText = vm::onVoiceText)
    LaunchedEffect(state.loading) { if (!state.loading && capture) vm.captureOnce() }
    LaunchedEffect(state.done) { if (state.done) onSaved(state.bedtime) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val back = { if (!state.busy) { vm.close(); if (state.raw.isNotBlank() || state.manual.isNotEmpty()) discard = true else onBack() } }
    BackHandler { back() }
    val enabled = !state.loading && !state.busy && !state.done && !state.loadFailed
    val draft = state.draft
    val interval = draft.kind in setOf(QuickKind.SLEEP, QuickKind.WORK)
    Scaffold(topBar = { RecordTopBar(title = { Text("语音记录预览") }, navigationIcon = {
        IconButton(onClick = back, enabled = !state.busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("说一句或直接输入，核对后再保存", style = MaterialTheme.typography.bodyMedium)
                RecordTextField(state.raw, vm::rawChanged, Modifier.fillMaxWidth().testTag("quick-raw"), enabled = enabled,
                    label = { Text("识别文字（可修改）") }, minLines = 3, maxLines = 6,
                    voiceInput = voiceInputAction(vm.voice, "quick", enabled))
                if (pending != null) Text("正在等待系统语音结果，可点关闭图标取消等待")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SecondaryButton(vm::beginVoice, enabled = enabled && pending == null) { Icon(Icons.Default.Mic, null); Text("继续说") }
                    PrimaryButton(vm::parse, enabled = enabled && state.raw.isNotBlank(), modifier = Modifier.testTag("quick-parse")) {
                        Text(if (state.preview) "重新解析" else "识别并预览")
                    }
                }
                Text("系统语音可能联网；识别不可用时仍可键入文字", style = MaterialTheme.typography.bodySmall)
                if (state.loading || state.busy) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text(if (state.loading) "正在加载项目…" else "正在处理，请稍候…")
                }
                state.error?.let { ErrorState(it) }
                if (state.loadFailed) SecondaryButton(vm::load, enabled = !state.busy) { Text("重新加载项目") }
                if (state.needsParse) Text("文字已修改，请重新解析后确认预览", color = MaterialTheme.colorScheme.error)
                Text("记录类型", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickKind.entries.forEach { kind -> FilterChip(selected = draft.kind == kind,
                        onClick = { vm.kind(kind) }, enabled = enabled, label = { Text(kind.label) }) }
                }
                if (state.preview) {
                    state.notices.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (interval) {
                        Text("待执行操作", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickAction.entries.forEach { action -> FilterChip(selected = draft.action == action,
                                onClick = { vm.change(QuickField.ACTION, action.name) }, enabled = enabled, label = { Text(action.label) }) }
                        }
                    }
                    if (draft.kind != null) {
                        RecordTimeChoice(R.string.quick_start_time,
                            draft.start?.let { display(it, vm) } ?: "请选择明确的日期和时间",
                            enabled && !(interval && draft.action == QuickAction.FINISH)) { picking = QuickField.START.name }
                        if (interval && draft.action != QuickAction.START) {
                            RecordTimeChoice(R.string.quick_end_time, draft.end?.let { display(it, vm) } ?: "请选择结束时间", enabled) { picking = QuickField.END.name }
                            Quantity(draft, QuickField.DURATION, "口述时长（分钟，可修改或清空）", enabled, vm)
                            if (draft.start != null && draft.end != null && draft.end!! > draft.start!!) {
                                val minutes = java.math.BigDecimal.valueOf(draft.end!! - draft.start!!, 3).divide(java.math.BigDecimal(60), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
                                Text("按起止时间计算：${minutes.toPlainString()}分钟")
                                val spoken = runCatching { com.personal.lifelog.core.validation.Units.minutesToSeconds(draft[QuickField.DURATION]) }.getOrNull()
                                if (spoken != null && kotlin.math.abs(draft.end!! - draft.start!! - spoken * 1000) > 1000) {
                                    Text("起止时间与口述时长不一致，请修改时间或时长", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (draft.kind == QuickKind.MEAL || draft.kind == QuickKind.WORKOUT) {
                            RecordTextField(draft[QuickField.CONTENT], { vm.change(QuickField.CONTENT, it) }, Modifier.fillMaxWidth().testTag("quick-content"),
                                enabled = enabled, label = { Text(if (draft.kind == QuickKind.MEAL) "吃了什么" else "训练备注") }, maxLines = 6)
                        }
                        if (draft.kind == QuickKind.WORKOUT) {
                            var showExercises by rememberSaveable { mutableStateOf(false) }
                            SecondaryButton({ showExercises = !showExercises }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("quick-exercise")) {
                                Text(vm.exercises.find { it.id == draft[QuickField.EXERCISE] }?.name ?: "选择现有运动项目")
                            }
                            if (showExercises) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                vm.exercises.forEach { exercise -> FilterChip(selected = exercise.id == draft[QuickField.EXERCISE],
                                    onClick = { vm.change(QuickField.EXERCISE, exercise.id); showExercises = false }, enabled = enabled, label = { Text(exercise.name) }) }
                            }
                            Text("一次录入一个项目；力量填写组数/次数/重量，其他项目按类型填写时长和距离", style = MaterialTheme.typography.bodySmall)
                            Quantity(draft, QuickField.DURATION, "运动时长（分钟，力量留空）", enabled, vm)
                            Quantity(draft, QuickField.DISTANCE, "距离（公里，仅有氧）", enabled, vm)
                            Quantity(draft, QuickField.SETS, "组数（仅力量）", enabled, vm)
                            Quantity(draft, QuickField.REPS, "每组次数（仅力量）", enabled, vm)
                            Quantity(draft, QuickField.WEIGHT, "每组重量（公斤，仅力量）", enabled, vm)
                        }
                    }
                    Text("保存以以上预览字段为准；取消不会新增记录", style = MaterialTheme.typography.bodySmall)
                }
            }
            PrimaryButton({ vm.save() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("quick-save"),
                enabled = enabled && state.preview && !state.needsParse && !state.multiple && pending == null) {
                Text(if (state.busy) "正在保存…" else "确认保存")
            }
        }
    }
    picking?.let { key ->
        val field = QuickField.valueOf(key)
        val initial = Instant.ofEpochMilli(draft[field].toLongOrNull() ?: vm.reference.toEpochMilli()).atZone(vm.zone)
        RecordDateTimePicker(if (field == QuickField.END) R.string.quick_end_time else R.string.quick_start_time,
            initial, vm.zone, { picking = null }, { local, offset ->
                vm.change(field, local.toInstant(offset).toEpochMilli().toString()); picking = null
            })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃这次记录？") },
        confirmButton = { ActionButton(onBack) { Text("放弃") } }, dismissButton = { ActionButton({ discard = false }) { Text("继续编辑") } })
    if (state.longConfirmation) AlertDialog(onDismissRequest = vm::cancelLong, title = { Text("确认较长的时间区间？") },
        text = { Text("请再次核对预览中的起止时间，确认后继续保存。") },
        confirmButton = { ActionButton({ vm.save(true) }) { Text("确认保存") } }, dismissButton = { ActionButton(vm::cancelLong) { Text("返回检查") } })
}

@Composable
private fun Quantity(draft: QuickDraft, field: QuickField, label: String, enabled: Boolean, vm: QuickRecordViewModel) {
    RecordTextField(draft[field], { vm.change(field, it) }, Modifier.fillMaxWidth().testTag("quick-${field.name.lowercase()}"), enabled = enabled,
        label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
private fun display(value: Long, vm: QuickRecordViewModel) = Instant.ofEpochMilli(value).atZone(vm.zone).format(DateTimeFormatter.ofPattern("uuuu年M月d日 HH:mm"))
