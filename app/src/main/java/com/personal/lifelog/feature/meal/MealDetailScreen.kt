package com.personal.lifelog.feature.meal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.ErrorState
import java.time.ZoneId

/**
 * Read-only meal detail with edit and confirmed delete. Deleting goes through
 * PhotoStorage so the record and its unreferenced photo are committed together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealDetailScreen(
    viewModel: MealDetailViewModel,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onBack()
    }
    BackHandler { if (!state.deleting) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meal_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.deleting) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    val meal = state.meal
                    IconButton(
                        onClick = { meal?.let { onEdit(it.id) } },
                        enabled = meal != null && !state.deleting,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.meal_detail_edit),
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

            state.loadError != null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                ErrorState(
                    message = stringResource(state.loadError!!.messageRes()),
                    onRetry = viewModel::retryLoad,
                )
            }

            state.missing || state.meal == null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(title = stringResource(R.string.meal_record_missing))
            }

            else -> MealDetailContent(
                state = state,
                innerPadding = innerPadding,
                onDelete = { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.meal_delete_title)) },
            text = { Text(stringResource(R.string.meal_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.meal_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.meal_cancel))
                }
            },
        )
    }
}

@Composable
private fun MealDetailContent(
    state: MealDetailUiState,
    innerPadding: PaddingValues,
    onDelete: () -> Unit,
) {
    val meal = state.meal ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (meal.photoId != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                when {
                    state.photoUri != null -> AsyncImage(
                        model = state.photoUri,
                        contentDescription = stringResource(R.string.meal_photo_alt),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    state.photoMissing -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.meal_photo_missing),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    else -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }

        if (!meal.text.isNullOrBlank()) {
            Text(
                text = meal.text,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        val satiety = satietyDisplayText(meal.satiety, meal.satietyText)
        if (satiety != null) {
            DetailRow(
                label = stringResource(R.string.meal_satiety_detail),
                value = satiety,
            )
        }

        DetailRow(
            label = stringResource(R.string.meal_time_label),
            value = MealTimeDisplay.full(meal.occurredAtUtcMs, meal.zoneId),
        )
        val systemZone = remember { ZoneId.systemDefault().id }
        if (meal.zoneId != systemZone) {
            Text(
                text = stringResource(R.string.meal_time_zone_note, meal.zoneId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state.deleteError?.let { error ->
            ErrorState(message = stringResource(error.messageRes()))
        }

        OutlinedButton(
            onClick = onDelete,
            enabled = !state.deleting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("meal_delete_button"),
        ) {
            if (state.deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.meal_deleting))
            } else {
                Text(stringResource(R.string.meal_detail_delete))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
