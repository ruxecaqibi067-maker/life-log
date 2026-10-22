package com.personal.lifelog.feature.meal

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.personal.lifelog.R
import com.personal.lifelog.data.media.PickMealPhoto
import com.personal.lifelog.data.media.TakeMealPhoto
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.ErrorState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val editorDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.SIMPLIFIED_CHINESE)
private val editorTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)

/**
 * Meal create/edit form. Text, one photo and the optional satiety are peers: a photo
 * alone or a single sentence is enough to save. The screen only leaves after the
 * ViewModel reports a committed save (or an explicit discard).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MealEditorScreen(
    viewModel: MealEditorViewModel,
    onFinished: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    val pickLauncher = rememberLauncherForActivityResult(PickMealPhoto()) { uri ->
        viewModel.onPhotoPicked(uri)
    }
    val captureLauncher = rememberLauncherForActivityResult(TakeMealPhoto()) { succeeded ->
        viewModel.onCaptureResult(succeeded)
    }

    LaunchedEffect(state.captureRequest) {
        val request = state.captureRequest ?: return@LaunchedEffect
        // Consume before launching so a recomposition cannot launch twice. Restoration
        // never puts a request here, so the app does not reopen the camera by itself.
        viewModel.onCaptureRequestLaunched()
        try {
            captureLauncher.launch(request.uri)
        } catch (_: ActivityNotFoundException) {
            viewModel.onCaptureLaunchFailed()
        } catch (_: SecurityException) {
            viewModel.onCaptureLaunchFailed()
        }
    }
    LaunchedEffect(state.savedSuccessfully) {
        if (state.savedSuccessfully) onFinished()
    }
    LaunchedEffect(state.discarded) {
        if (state.discarded) onFinished()
    }

    val requestBack: () -> Unit = {
        when {
            state.saving -> Unit
            state.dirty -> showDiscard = true
            else -> onFinished()
        }
    }
    BackHandler { requestBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditing) R.string.meal_editor_edit_title else R.string.meal_editor_create_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = requestBack, enabled = !state.saving) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.loadFailed -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(MealUiError.LOAD_FAILED.messageRes()),
                    onRetry = viewModel::retryLoad,
                )
            }

            state.missing -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.meal_record_missing))
            }

            else -> MealForm(
                state = state,
                innerPadding = innerPadding,
                onTextChanged = viewModel::onTextChanged,
                onToggleSatiety = viewModel::toggleSatiety,
                onShowDatePicker = { showDatePicker = true },
                onShowTimePicker = { showTimePicker = true },
                onTakePhoto = viewModel::beginCapture,
                onPickPhoto = { pickLauncher.launch(Unit) },
                onRemovePhoto = viewModel::removePhoto,
                onSave = viewModel::save,
            )
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text(stringResource(R.string.meal_discard_title)) },
            text = { Text(stringResource(R.string.meal_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscard = false
                        viewModel.discard()
                    },
                ) { Text(stringResource(R.string.meal_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.meal_discard_continue))
                }
            },
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.onDateChanged(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.meal_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.meal_cancel))
                }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(
            initialHour = state.time.hour,
            initialMinute = state.time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.meal_time_of_day_label)) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onTimeChanged(LocalTime.of(pickerState.hour, pickerState.minute))
                        showTimePicker = false
                    },
                ) { Text(stringResource(R.string.meal_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.meal_cancel))
                }
            },
        )
    }
}

@Composable
private fun MealForm(
    state: MealEditorUiState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onTextChanged: (String) -> Unit,
    onToggleSatiety: (Satiety) -> Unit,
    onShowDatePicker: () -> Unit,
    onShowTimePicker: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhotoSection(
            state = state,
            onTakePhoto = onTakePhoto,
            onPickPhoto = onPickPhoto,
            onRemovePhoto = onRemovePhoto,
        )
        state.photoError?.let { error ->
            ErrorState(message = stringResource(error.messageRes()))
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth().heightIn(min = 132.dp),
            enabled = !state.saving,
            label = { Text(stringResource(R.string.meal_text_label)) },
            placeholder = { Text(stringResource(R.string.meal_text_placeholder)) },
            supportingText = { Text(stringResource(R.string.meal_text_hint)) },
            isError = state.error == MealUiError.TEXT_TOO_LONG,
            maxLines = 8,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.meal_satiety_label),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Satiety.entries.forEach { value ->
                    FilterChip(
                        selected = state.satiety == value,
                        onClick = { onToggleSatiety(value) },
                        label = { Text(stringResource(satietyLabelRes(value))) },
                        enabled = !state.saving,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.meal_time_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onShowDatePicker, enabled = !state.saving) {
                    Text(state.date.format(editorDateFormatter))
                }
                OutlinedButton(onClick = onShowTimePicker, enabled = !state.saving) {
                    Text(state.time.format(editorTimeFormatter))
                }
            }
            val systemZone = remember { ZoneId.systemDefault().id }
            if (state.zoneId != systemZone) {
                Text(
                    text = stringResource(R.string.meal_time_zone_note, state.zoneId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.error?.let { error ->
            ErrorState(message = stringResource(error.messageRes()))
        }

        Button(
            onClick = onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.meal_saving))
            } else {
                Text(stringResource(R.string.meal_save))
            }
        }
    }
}

@Composable
private fun PhotoSection(
    state: MealEditorUiState,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
) {
    val busy = state.saving || state.photoStatus == MealPhotoStatus.PROCESSING
    Surface(
        modifier = Modifier.fillMaxWidth().height(232.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.photoPreview != null -> AsyncImage(
                        model = state.photoPreview,
                        contentDescription = stringResource(R.string.meal_photo_alt),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )

                    state.photoStatus == MealPhotoStatus.PROCESSING -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.meal_photo_processing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    state.photoStatus == MealPhotoStatus.CAPTURE_PENDING -> Text(
                        text = stringResource(R.string.meal_photo_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.photoPreview != null) {
                    TextButton(onClick = onTakePhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_take))
                    }
                    TextButton(onClick = onPickPhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_replace))
                    }
                    TextButton(onClick = onRemovePhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_remove))
                    }
                } else {
                    OutlinedButton(onClick = onTakePhoto, enabled = !busy) {
                        Text(
                            stringResource(
                                if (state.captureTicket != null) {
                                    R.string.meal_photo_retry_capture
                                } else {
                                    R.string.meal_photo_take
                                },
                            ),
                        )
                    }
                    OutlinedButton(onClick = onPickPhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_pick))
                    }
                    if (state.captureTicket != null) {
                        TextButton(onClick = onRemovePhoto, enabled = !busy) {
                            Text(stringResource(R.string.meal_photo_remove))
                        }
                    }
                }
            }
        }
    }
}
