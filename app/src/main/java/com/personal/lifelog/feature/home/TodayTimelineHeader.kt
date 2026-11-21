package com.personal.lifelog.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.personal.lifelog.R
import com.personal.lifelog.ui.components.SecondaryButton
import com.personal.lifelog.ui.theme.LogDimens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayTimelineHeader(onOpenWorkoutHistory: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = LogDimens.gap),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(LogDimens.gap),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.home_timeline_title), Modifier.align(Alignment.CenterVertically),
            style = MaterialTheme.typography.titleMedium)
        SecondaryButton(onClick = onOpenWorkoutHistory, modifier = Modifier.testTag("home-workout-history")) {
            Icon(Icons.Filled.FitnessCenter, null, Modifier.size(18.dp))
            Spacer(Modifier.width(LogDimens.gap))
            Text(stringResource(R.string.home_workout_history), Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
        }
    }
}
