package com.personal.lifelog.ui.components

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.personal.lifelog.R
import com.personal.lifelog.ui.theme.LogDimens

/** Presentation only: callers keep ownership of events, state and enabled rules. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier.heightIn(min = LogDimens.action), enabled = enabled,
    shape = MaterialTheme.shapes.large, colors = colors, content = content)

@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier.heightIn(min = LogDimens.touch), enabled = enabled,
    shape = MaterialTheme.shapes.large, colors = colors, content = content)

@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier.heightIn(min = LogDimens.touch), enabled = enabled,
    shape = MaterialTheme.shapes.medium, colors = colors, content = content)

@Composable
fun RecordCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) = OutlinedCard(modifier, shape = shape,
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(LogDimens.border, MaterialTheme.colorScheme.outlineVariant), content = content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) = OutlinedCard(onClick, modifier, enabled = enabled, shape = shape,
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(LogDimens.border, MaterialTheme.colorScheme.outlineVariant), content = content)

@Composable
fun RecordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) = OutlinedTextField(value, onValueChange, modifier, enabled = enabled,
    textStyle = MaterialTheme.typography.bodyLarge, label = label, placeholder = placeholder,
    supportingText = supportingText, isError = isError, keyboardOptions = keyboardOptions,
    singleLine = singleLine, maxLines = maxLines, minLines = minLines,
    shape = MaterialTheme.shapes.large)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) = TopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon, actions = actions,
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ))

/** Native date/time dialogs must follow the stored app theme, including API 28. */
@Composable
fun rememberPickerContext(): Context {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return remember(context, dark) { createPickerContext(context, dark) }
}

internal fun createPickerContext(context: Context, dark: Boolean): Context =
    ContextThemeWrapper(context, if (dark) R.style.Theme_LifeLog_Picker_Dark else R.style.Theme_LifeLog_Picker_Light)

/** Android 9 has an opaque navigation scrim; its background must match manual theme icons. */
@Composable
internal fun SyncLegacySystemBarBackground(bedtime: Boolean) {
    val view = LocalView.current
    val background = if (bedtime) android.graphics.Color.BLACK else MaterialTheme.colorScheme.background.toArgb()
    SideEffect {
        if (Build.VERSION.SDK_INT == 28) {
            (view.context as? Activity)?.window?.navigationBarColor = background
        }
    }
}
