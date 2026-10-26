package com.personal.lifelog.feature.sleep

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.*
import com.personal.lifelog.ui.theme.LogDimens
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepHistoryScreen(viewModel: SleepHistoryViewModel, onAdd: () -> Unit, onEdit: (String) -> Unit, onBack: () -> Unit,
    commands: SleepViewModel? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    commands?.let { SleepHomeDialogs(it, confirmUndo) { confirmUndo = false } }
    val scroll = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshZone() }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.refreshZone()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.date) { scroll.scrollToItem(0) }
    if (picking) RecordDatePicker(R.string.sleep_history_choose, state.date,
        onCancel = { picking = false }, onConfirm = { picking = false; viewModel.selectDate(it) })
    Scaffold(topBar = {
        RecordTopBar(title = { Text(stringResource(R.string.sleep_title)) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }) { padding ->
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize().padding(padding).testTag("sleep-history-list"),
            contentPadding = PaddingValues(LogDimens.page), verticalArrangement = Arrangement.spacedBy(LogDimens.group)) {
            item("date") {
                Column(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    Text(sleepHistoryDateFormat.format(state.date), Modifier.testTag("sleep-history-date"),
                        style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                        SecondaryButton(onClick = { picking = true }, modifier = Modifier.testTag("sleep-history-choose")) {
                            Icon(Icons.Filled.DateRange, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(LogDimens.gap))
                            Text(stringResource(R.string.sleep_history_other))
                        }
                        if (state.date != today) ActionButton(viewModel::goToToday) { Text(stringResource(R.string.sleep_history_today)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                        ActionButton({ viewModel.selectDate(state.date.minusDays(1)) }, enabled = state.date > LocalDate.of(1, 1, 1)) {
                            Text(stringResource(R.string.sleep_history_previous))
                        }
                        ActionButton({ viewModel.selectDate(state.date.plusDays(1)) }, enabled = state.date < LocalDate.of(9999, 12, 31)) {
                            Text(stringResource(R.string.sleep_history_next))
                        }
                    }
                    PrimaryButton(onAdd, Modifier.fillMaxWidth()) { Text(stringResource(R.string.sleep_add)) }
                    Text(stringResource(R.string.sleep_history_rule), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            commands?.let { model -> item("record-actions") {
                SleepHomeFeedback(model, onEdit, { confirmUndo = true })
            } }
            when {
                state.loading -> item("loading") { Text(stringResource(R.string.sleep_loading)) }
                state.failed -> item("error") { ErrorState(stringResource(R.string.sleep_history_error), onRetry = viewModel::retry) }
                else -> {
                    item("records-title") { Text(stringResource(R.string.sleep_history_records), style = MaterialTheme.typography.titleMedium) }
                    if (state.records.isEmpty()) item("empty") {
                        EmptyState(stringResource(R.string.sleep_history_empty), hint = stringResource(R.string.sleep_empty_hint))
                    }
                    items(state.records, key = { it.id }) { record ->
                        RecordCard(onClick = { onEdit(record.id) }, modifier = Modifier.fillMaxWidth().testTag("sleep-record-${record.id}")) {
                            Column(Modifier.padding(LogDimens.card)) { SleepRecordSummary(record, showArchive = false) }
                        }
                    }
                    state.day?.let { day -> item("day-analysis") { SleepDayAnalysisCard(day) } }
                    item("week-analysis") { SleepWeekAnalysisCard(state.week, viewModel::selectDate) }
                }
            }
        }
    }
}
