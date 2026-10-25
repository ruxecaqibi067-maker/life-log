package com.personal.lifelog.feature.exercise

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.personal.lifelog.R
import com.personal.lifelog.data.local.entity.ExerciseDefinition
import com.personal.lifelog.domain.ErrorCode
import com.personal.lifelog.domain.ExerciseType
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.components.RecordTextField
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SavingIndicator
import com.personal.lifelog.ui.theme.LogDimens

/** T08 passes onSelected to use the same library as a picker. Hidden rows are never selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseLibraryScreen(
    viewModel: ExerciseLibraryViewModel,
    onBack: () -> Unit,
    onSelected: ((ExerciseDefinition) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val typeRequired by viewModel.typeRequired.collectAsStateWithLifecycle()
    val visibleRows = state.rows.filter { onSelected == null || !it.isHidden }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val dismiss = {
        if (!busy) {
            if (editor?.dirty == true) confirmDiscard = true else viewModel.dismissEditor()
        }
    }
    Scaffold(
        topBar = {
            RecordTopBar(
                title = { Text(stringResource(if (onSelected == null) R.string.settings_exercises else R.string.exercise_select)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(LogDimens.page),
            verticalArrangement = Arrangement.spacedBy(LogDimens.group),
        ) {
            item {
                RecordTextField(
                    value = state.query, onValueChange = viewModel::search,
                    label = { Text(stringResource(R.string.exercise_search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    FilterChip(modifier = Modifier.heightIn(min = LogDimens.touch), selected = !state.recentOnly, onClick = { viewModel.showRecent(false) },
                        label = { Text(stringResource(R.string.exercise_all)) })
                    FilterChip(modifier = Modifier.heightIn(min = LogDimens.touch), selected = state.recentOnly, onClick = { viewModel.showRecent(true) },
                        label = { Text(stringResource(R.string.exercise_recent)) })
                }
                if (onSelected == null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { viewModel.showHidden(!state.includeHidden) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.includeHidden, onCheckedChange = viewModel::showHidden)
                        Text(stringResource(R.string.exercise_show_hidden))
                    }
                }
                PrimaryButton(onClick = viewModel::add, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.exercise_add))
                }
            }
            if (busy && editor == null) item { SavingIndicator() }
            if (error != null && editor == null) item { ErrorState(stringResource(exerciseError(error))) }
            when {
                state.loading -> item { CircularProgressIndicator() }
                state.loadFailed -> item {
                    ErrorState(stringResource(R.string.exercise_load_error), onRetry = viewModel::retryLoad)
                }
                visibleRows.isEmpty() -> item { EmptyState(stringResource(R.string.exercise_no_results)) }
                else -> items(visibleRows, key = { it.id }) { row ->
                    ExerciseRow(row, busy, onSelected, { viewModel.edit(row) }, { viewModel.toggleHidden(row) })
                }
            }
        }
    }

    editor?.let { input ->
        AlertDialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
            title = { Text(stringResource(if (input.isNew) R.string.exercise_add else R.string.exercise_rename)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                    RecordTextField(
                        value = input.name, onValueChange = viewModel::changeName, enabled = !busy,
                        singleLine = true, label = { Text(stringResource(R.string.exercise_name)) },
                        supportingText = { Text(stringResource(R.string.exercise_name_hint)) },
                    )
                    if (input.isNew) {
                        Text(stringResource(R.string.exercise_type_required))
                        ExerciseType.entries.forEach { type ->
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = input.type == type, enabled = !busy, role = Role.RadioButton,
                                    onClick = { viewModel.chooseType(type) },
                                ).padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = input.type == type, onClick = null, enabled = !busy)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(stringResource(type.labelRes()))
                                    Text(stringResource(type.fieldsRes()), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    } else {
                        Text(stringResource(requireNotNull(input.type).labelRes()))
                        Text(stringResource(R.string.exercise_type_fixed), style = MaterialTheme.typography.bodySmall)
                    }
                    if (typeRequired) ErrorState(stringResource(R.string.exercise_choose_type))
                    if (error != null) ErrorState(stringResource(exerciseError(error)))
                    if (busy) SavingIndicator()
                }
            },
            confirmButton = {
                ActionButton(onClick = { viewModel.save { onSelected?.invoke(it) } }, enabled = !busy) {
                    Text(stringResource(R.string.exercise_save))
                }
            },
            dismissButton = {
                ActionButton(onClick = dismiss, enabled = !busy) { Text(stringResource(R.string.exercise_cancel)) }
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.exercise_discard_title)) },
            text = { Text(stringResource(R.string.exercise_discard_hint)) },
            confirmButton = {
                ActionButton(onClick = { confirmDiscard = false; viewModel.dismissEditor() }) {
                    Text(stringResource(R.string.exercise_discard))
                }
            },
            dismissButton = {
                ActionButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.exercise_keep_editing)) }
            },
        )
    }
    BackHandler(enabled = busy && editor == null) { /* Keep write completion attached to this entry. */ }
}

@Composable
private fun ExerciseRow(
    row: ExerciseDefinition,
    busy: Boolean,
    onSelected: ((ExerciseDefinition) -> Unit)?,
    onEdit: () -> Unit,
    onToggleHidden: () -> Unit,
) {
    RecordCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LogDimens.card), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(row.type.labelRes()), style = MaterialTheme.typography.labelLarge)
            if (onSelected == null && row.isHidden) Text(stringResource(R.string.exercise_hidden), style = MaterialTheme.typography.labelSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                if (onSelected == null) {
                    ActionButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.exercise_rename)) }
                    ActionButton(onClick = onToggleHidden, enabled = !busy) {
                        Text(stringResource(if (row.isHidden) R.string.exercise_enable else R.string.exercise_hide))
                    }
                } else {
                    PrimaryButton(onClick = { onSelected(row) }, enabled = !busy && !row.isHidden) {
                        Text(stringResource(R.string.exercise_pick))
                    }
                }
            }
        }
    }
}

@StringRes internal fun ExerciseType.labelRes(): Int = when (this) {
    ExerciseType.CARDIO -> R.string.exercise_cardio
    ExerciseType.STRENGTH -> R.string.exercise_strength
    ExerciseType.OTHER -> R.string.exercise_other
}

@StringRes internal fun ExerciseType.fieldsRes(): Int = when (this) {
    ExerciseType.CARDIO -> R.string.exercise_cardio_fields
    ExerciseType.STRENGTH -> R.string.exercise_strength_fields
    ExerciseType.OTHER -> R.string.exercise_other_fields
}

@StringRes private fun exerciseError(code: ErrorCode?): Int = when (code) {
    ErrorCode.INVALID_NAME -> R.string.exercise_invalid_name
    ErrorCode.DUPLICATE_NAME -> R.string.exercise_duplicate_name
    ErrorCode.USED_EXERCISE_TYPE -> R.string.exercise_type_fixed
    ErrorCode.NOT_FOUND -> R.string.exercise_not_found
    ErrorCode.STORAGE_FULL -> R.string.exercise_storage_full
    else -> R.string.exercise_write_error
}
