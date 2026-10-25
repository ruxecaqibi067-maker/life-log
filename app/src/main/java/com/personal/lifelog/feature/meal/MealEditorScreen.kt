package com.personal.lifelog.feature.meal

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.personal.lifelog.R
import com.personal.lifelog.data.media.PickMealPhoto
import com.personal.lifelog.data.media.TakeMealPhoto
import com.personal.lifelog.domain.Satiety
import com.personal.lifelog.ui.components.RecordDateTimePicker
import com.personal.lifelog.ui.components.RecordTimeChoice
import com.personal.lifelog.ui.components.ActionButton
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.PrimaryButton
import com.personal.lifelog.ui.components.RecordTextField
import com.personal.lifelog.ui.components.RecordTopBar
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens
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
            RecordTopBar(
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
                onSatietyTextChanged = viewModel::onSatietyTextChanged,
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
                ActionButton(
                    onClick = {
                        showDiscard = false
                        viewModel.discard()
                    },
                ) { Text(stringResource(R.string.meal_discard_confirm)) }
            },
            dismissButton = {
                ActionButton(onClick = { showDiscard = false }) {
                    Text(stringResource(R.string.meal_discard_continue))
                }
            },
        )
    }

    if (showTimePicker) {
        val zone = ZoneId.of(state.zoneId)
        val local = java.time.LocalDateTime.of(state.date, state.time)
        val initial = java.time.ZonedDateTime.ofLocal(local, zone, state.timeOffset?.let(ZoneOffset::of))
        RecordDateTimePicker(R.string.picker_meal_title, initial, zone, { showTimePicker = false }, { selected, offset ->
            viewModel.onDateTimePicked(selected, offset); showTimePicker = false
        })
    }
}

@Composable
private fun MealForm(
    state: MealEditorUiState,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    onTextChanged: (String) -> Unit,
    onToggleSatiety: (Satiety) -> Unit,
    onSatietyTextChanged: (String) -> Unit,
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
            .padding(horizontal = LogDimens.page, vertical = 8.dp),
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

        RecordTextField(
            value = state.text,
            onValueChange = onTextChanged,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 132.dp)
                .testTag("meal_text_input"),
            enabled = !state.saving,
            label = { Text(stringResource(R.string.meal_text_label)) },
            placeholder = { Text(stringResource(R.string.meal_text_placeholder)) },
            supportingText = { Text(stringResource(R.string.meal_text_hint)) },
            isError = state.error == MealUiError.TEXT_TOO_LONG,
            maxLines = 8,
        )

        Column(verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
            Text(
                text = stringResource(R.string.meal_satiety_label),
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(LogDimens.gap), verticalArrangement = Arrangement.spacedBy(LogDimens.gap)) {
                satietyOptions.forEach { value ->
                    FilterChip(
                        selected = state.satiety == value,
                        onClick = { onToggleSatiety(value) },
                        label = { Text(stringResource(satietyLabelRes(value))) },
                        enabled = !state.saving,
                        modifier = Modifier.heightIn(min = LogDimens.touch).testTag("meal_satiety_${value.name}"),
                    )
                }
            }
            if (state.satiety == Satiety.CUSTOM) {
                RecordTextField(
                    value = state.satietyText,
                    onValueChange = onSatietyTextChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("meal_satiety_custom_input"),
                    enabled = !state.saving,
                    label = { Text(stringResource(R.string.meal_satiety_custom_label)) },
                    supportingText = { Text(stringResource(R.string.meal_satiety_custom_hint)) },
                    isError = state.error == MealUiError.SATIETY_TEXT_TOO_LONG,
                    singleLine = true,
                )
            }
        }

        RecordTimeChoice(R.string.meal_time_label,
            "${state.date.format(editorDateFormatter)} ${state.time.format(editorTimeFormatter)}",
            enabled = !state.saving, onClick = onShowTimePicker)

        state.error?.let { error ->
            ErrorState(message = stringResource(error.messageRes()))
        }

        PrimaryButton(
            onClick = onSave,
            enabled = !state.saving && state.photoStatus != MealPhotoStatus.PROCESSING,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("meal_save_button"),
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
        shape = MaterialTheme.shapes.large,
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
                        verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
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

                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
                    ) {
                        Icon(Icons.Filled.Restaurant, contentDescription = null,
                            modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.meal_photo_placeholder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
            ) {
                if (state.photoPreview != null) {
                    ActionButton(onClick = onTakePhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_take))
                    }
                    ActionButton(onClick = onPickPhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_replace))
                    }
                    ActionButton(onClick = onRemovePhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_remove))
                    }
                } else {
                    SecondaryButton(onClick = onTakePhoto, enabled = !busy) {
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
                    SecondaryButton(onClick = onPickPhoto, enabled = !busy) {
                        Text(stringResource(R.string.meal_photo_pick))
                    }
                    if (state.captureTicket != null) {
                        ActionButton(onClick = onRemovePhoto, enabled = !busy) {
                            Text(stringResource(R.string.meal_photo_remove))
                        }
                    }
                }
            }
        }
    }
}
