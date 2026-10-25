package com.personal.lifelog.feature.timeline

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.personal.lifelog.R
import com.personal.lifelog.core.time.RecordTime
import com.personal.lifelog.data.media.PhotoError
import com.personal.lifelog.data.media.PhotoResult
import com.personal.lifelog.data.media.PhotoStorage
import com.personal.lifelog.domain.TimelineEntry
import com.personal.lifelog.feature.meal.satietyDisplayText
import com.personal.lifelog.ui.components.EmptyState
import com.personal.lifelog.ui.components.ErrorState
import com.personal.lifelog.ui.components.RecordCard
import com.personal.lifelog.ui.theme.LogDimens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val timelineTime = DateTimeFormatter.ofPattern("HH:mm", Locale.SIMPLIFIED_CHINESE)
private val timelineDateTime = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.SIMPLIFIED_CHINESE)

private fun displayZone(id: String?): ZoneId =
    runCatching { ZoneId.of(id!!) }.getOrElse { ZoneId.systemDefault() }

private fun displayTime(utcMs: Long, zoneId: String?): String =
    Instant.ofEpochMilli(utcMs).atZone(displayZone(zoneId)).format(timelineTime)

private fun displayDateTime(utcMs: Long, zoneId: String?): String =
    Instant.ofEpochMilli(utcMs).atZone(displayZone(zoneId)).format(timelineDateTime)

/**
 * Adds the shared timeline rows to a LazyColumn. Entries come pre-sorted from
 * [com.personal.lifelog.data.repository.TimelineRepository]; each row uses its
 * stable key, and meal thumbnails are resolved only when a row is composed.
 */
fun LazyListScope.timelineSection(
    state: TimelineUiState,
    photos: PhotoStorage,
    onOpenMeal: (String) -> Unit,
    onOpenSleep: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenWork: (String) -> Unit = {},
) {
    when {
        state.failed -> item(key = "timeline-failed") {
            ErrorState(
                message = stringResource(R.string.timeline_load_failed),
                onRetry = onRetry,
            )
        }

        state.loading -> item(key = "timeline-loading") {
            Text(
                text = stringResource(R.string.timeline_loading),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        state.entries.isEmpty() -> item(key = "timeline-empty") {
            EmptyState(
                title = stringResource(R.string.empty_timeline_title),
                hint = stringResource(R.string.empty_timeline_hint),
                icon = Icons.AutoMirrored.Outlined.EventNote,
            )
        }

        else -> {
            item(key = "timeline-summary") {
                TimelineSummary(state.entries)
            }
            items(
                items = state.entries,
                key = { it.stableKey },
                contentType = { it.kind },
            ) { entry ->
                when (entry) {
                    is TimelineEntry.Meal -> MealTimelineRow(entry, photos) { onOpenMeal(entry.id) }
                    is TimelineEntry.Sleep -> SleepTimelineRow(entry) { onOpenSleep(entry.id) }
                    is TimelineEntry.Work -> WorkTimelineRow(entry) { onOpenWork(entry.id) }
                    is TimelineEntry.Workout -> WorkoutTimelineRow(entry) { onOpenWorkout(entry.id) }
                }
            }
        }
    }
}

/**
 * Counts only stored facts. A category with no records is omitted rather than
 * rendered as zero, and workout sessions and their items are reported separately.
 */
@Composable
private fun TimelineSummary(entries: List<TimelineEntry>) {
    val meals = entries.count { it is TimelineEntry.Meal }
    val sleeps = entries.count { it is TimelineEntry.Sleep }
    val workouts = entries.filterIsInstance<TimelineEntry.Workout>()
    val parts = mutableListOf<String>()
    if (meals > 0) parts += stringResource(R.string.timeline_summary_meal, meals)
    val work = entries.count { it is TimelineEntry.Work }
    if (work > 0) parts += stringResource(R.string.timeline_summary_work, work)
    if (sleeps > 0) parts += stringResource(R.string.timeline_summary_sleep, sleeps)
    if (workouts.isNotEmpty()) {
        parts += stringResource(
            R.string.timeline_summary_workout,
            workouts.size,
            workouts.sumOf { it.itemNames.size },
        )
    }
    Text(
        text = parts.joinToString(" · "),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MealTimelineRow(
    entry: TimelineEntry.Meal,
    photos: PhotoStorage,
    onClick: () -> Unit,
) {
    val title = entry.text?.takeIf { it.isNotBlank() } ?: stringResource(R.string.meal_photo_only)
    val secondary = satietyDisplayText(entry.satiety, entry.satietyText)
    TimelineCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.timeline_open_meal),
        leading = { MealLeading(entry.photoId, photos) },
        title = title,
        secondary = secondary,
        time = displayTime(entry.sortUtcMs, entry.zoneId),
    )
}

@Composable
private fun SleepTimelineRow(entry: TimelineEntry.Sleep, onClick: () -> Unit) {
    val start = displayDateTime(entry.startedAtUtcMs, entry.startZoneId)
    val end = entry.endedAtUtcMs
        ?.let { displayDateTime(it, entry.endZoneId ?: entry.startZoneId) }
        ?: stringResource(R.string.timeline_sleep_active)
    val range = stringResource(R.string.timeline_sleep_range, start, end)
    val duration = entry.endedAtUtcMs?.let { durationText(entry.startedAtUtcMs, it) }
    val secondary = listOfNotNull(range, duration).joinToString(" · ")
    val sortZone = if (entry.endedAtUtcMs != null) entry.endZoneId ?: entry.startZoneId else entry.startZoneId
    TimelineCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.timeline_open_sleep),
        leading = { TimelineIcon(Icons.Filled.Bedtime) },
        title = stringResource(R.string.timeline_kind_sleep),
        secondary = secondary,
        time = displayTime(entry.sortUtcMs, sortZone),
    )
}

@Composable
private fun WorkTimelineRow(entry: TimelineEntry.Work, onClick: () -> Unit) {
    val start = displayDateTime(entry.startedAtUtcMs, entry.startZoneId)
    val end = entry.endedAtUtcMs
        ?.let { displayDateTime(it, entry.endZoneId ?: entry.startZoneId) }
        ?: stringResource(R.string.timeline_work_active)
    val range = stringResource(R.string.timeline_work_range, start, end)
    val duration = entry.endedAtUtcMs?.let { workDurationText(entry.startedAtUtcMs, it) }
    val secondary = listOfNotNull(range, duration).joinToString(" · ")
    val sortZone = if (entry.endedAtUtcMs != null) entry.endZoneId ?: entry.startZoneId else entry.startZoneId
    TimelineCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.timeline_open_work),
        leading = { TimelineIcon(Icons.Filled.WorkOutline) },
        title = stringResource(R.string.timeline_kind_work),
        secondary = secondary,
        time = displayTime(entry.sortUtcMs, sortZone),
    )
}

@Composable
private fun workDurationText(start: Long, end: Long): String = stringResource(R.string.work_recorded_duration,
    com.personal.lifelog.feature.home.recordedDurationText(Math.subtractExact(end, start)))

@Composable
private fun WorkoutTimelineRow(entry: TimelineEntry.Workout, onClick: () -> Unit) {
    val names = entry.itemNames.joinToString("、")
        .ifBlank { stringResource(R.string.timeline_kind_workout) }
    TimelineCard(
        onClick = onClick,
        onClickLabel = stringResource(R.string.timeline_open_workout),
        leading = { TimelineIcon(Icons.Filled.FitnessCenter) },
        title = names,
        secondary = stringResource(R.string.timeline_workout_detail, entry.itemNames.size),
        time = displayTime(entry.sortUtcMs, entry.zoneId),
    )
}

@Composable
private fun durationText(startUtcMs: Long, endUtcMs: Long): String? {
    val seconds = runCatching { RecordTime.durationSeconds(startUtcMs, endUtcMs) }.getOrNull() ?: return null
    return if (seconds < 60) {
        stringResource(R.string.timeline_sleep_duration_seconds, seconds)
    } else {
        stringResource(R.string.timeline_sleep_duration, seconds / 3600, seconds % 3600 / 60)
    }
}

@Composable
private fun TimelineCard(
    onClick: () -> Unit,
    onClickLabel: String,
    leading: @Composable () -> Unit,
    title: String,
    secondary: String?,
    time: String,
) {
    RecordCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = onClickLabel, onClick = onClick)
                .padding(LogDimens.card),
            horizontalArrangement = Arrangement.spacedBy(LogDimens.group),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary != null) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = time,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Resolves a formal photo URI only when this row is actually composed. */
@Composable
private fun MealLeading(photoId: String?, photos: PhotoStorage) {
    val alt = stringResource(R.string.meal_photo_alt)
    val resolved by produceState<PhotoResult<Uri>?>(initialValue = null, photoId, photos) {
        value = photoId?.let { id ->
            try {
                photos.photoUri(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A damaged/deleted photo is a row-level error, never a timeline crash.
                PhotoResult.Failure(PhotoError.MISSING_PHOTO)
            }
        }
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { contentDescription = alt },
        contentAlignment = Alignment.Center,
    ) {
        when (val result = resolved) {
            null -> if (photoId == null) {
                Icon(
                    imageVector = Icons.Filled.Restaurant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }

            is PhotoResult.Success -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // Visible while Coil decodes, or if the decoder cannot open the file.
                Icon(
                    imageVector = Icons.Filled.Restaurant,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AsyncImage(
                    model = result.value,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            is PhotoResult.Failure, PhotoResult.Cancelled -> Icon(
                imageVector = Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TimelineIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
