package com.personal.lifelog.feature.sleep

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.lifelog.R
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** Month/year changes clamp the day (Jan 31 -> Feb 28/29), never roll into March. */
fun wheelDateTime(year: Int, month: Int, day: Int, hour: Int, minute: Int): LocalDateTime =
    LocalDateTime.of(year, month, day.coerceIn(1, YearMonth.of(year, month).lengthOfMonth()), hour, minute)

@Composable
fun SleepDateTimePicker(
    title: Int,
    initial: ZonedDateTime,
    zone: ZoneId,
    onCancel: () -> Unit,
    onConfirm: (LocalDateTime, ZoneOffset) -> Unit,
) {
    var year by rememberSaveable { mutableIntStateOf(initial.year.coerceIn(1, 9999)) }
    var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
    var day by rememberSaveable { mutableIntStateOf(initial.dayOfMonth) }
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var occurrence by rememberSaveable { mutableIntStateOf(0) }
    val local = wheelDateTime(year, month, day, hour, minute)
    val offsets = zone.rules.getValidOffsets(local)
    val preferred = offsets.indexOf(initial.offset).coerceAtLeast(0)
    val offset = offsets.getOrNull((preferred + occurrence) % offsets.size.coerceAtLeast(1))
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(240).dp),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(chineseSleepTime(local), style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Wheel(R.string.sleep_year, year, 1, 9999, Modifier.weight(1.4f)) { year = it; day = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth()) }
                        Wheel(R.string.sleep_month, month, 1, 12, Modifier.weight(1f)) { month = it; day = day.coerceAtMost(YearMonth.of(year, month).lengthOfMonth()) }
                        Wheel(R.string.sleep_day, local.dayOfMonth, 1, YearMonth.of(year, month).lengthOfMonth(), Modifier.weight(1f)) { day = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Wheel(R.string.sleep_hour, hour, 0, 23, Modifier.weight(1f)) { hour = it }
                        Wheel(R.string.sleep_minute, minute, 0, 59, Modifier.weight(1f)) { minute = it }
                    }
                    if (offsets.isEmpty()) SleepError(com.personal.lifelog.domain.ErrorCode.INVALID_LOCAL_TIME)
                    if (offsets.size > 1) {
                        Text(stringResource(R.string.sleep_repeated_time))
                        offsets.forEachIndexed { index, item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = item == offset, onClick = { occurrence = (index - preferred + offsets.size) % offsets.size })
                                Text(stringResource(if (index == 0) R.string.sleep_first_occurrence else R.string.sleep_second_occurrence))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.sleep_cancel)) }
                    TextButton(onClick = { offset?.let { onConfirm(local, it) } }, enabled = offset != null) {
                        Text(stringResource(R.string.sleep_confirm))
                    }
                }
            }
        }
    }
}

@Composable
private fun Wheel(label: Int, value: Int, min: Int, max: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    val name = stringResource(label)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val textPixels = with(LocalDensity.current) { 20.sp.toPx() }
    val height = (144 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Column(modifier.testTag("sleep-wheel-$name").semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), min.toFloat()..max.toFloat(), (max - min - 1).coerceAtLeast(0))
        setProgress { onValue(it.roundToInt().coerceIn(min, max)); true }
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        key(darkTheme) { AndroidView(
            modifier = Modifier.fillMaxWidth().height(height),
            factory = { context -> createSleepWheel(context, darkTheme).apply {
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                wrapSelectorWheel = false
                setOnValueChangedListener { _, _, next -> onValue(next) }
            } },
            update = { picker ->
                // Clear old displayed values before changing bounds (e.g. February).
                picker.displayedValues = null
                picker.minValue = min
                picker.maxValue = max
                picker.value = value.coerceIn(min, max)
                picker.contentDescription = name
                if (Build.VERSION.SDK_INT >= 29) {
                    picker.textColor = color
                    picker.textSize = textPixels
                }
            },
        ) }
    }
}

/** API 28 has no public textColor setter; the native theme must follow the app's explicit choice. */
internal fun createSleepWheel(context: Context, darkTheme: Boolean): NumberPicker = NumberPicker(
    ContextThemeWrapper(context, if (darkTheme) android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar),
)
