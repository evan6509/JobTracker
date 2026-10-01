package com.evanchubbuck.jobtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobTimePickerDialog(value: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val timeFormat = LocalJobTimeFormat.current
    val parts = value.split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()
    val minute = parts.getOrNull(1)?.toIntOrNull()
    val valid = parts.size == 2 && hour != null && hour in 0..23 && minute != null && minute in 0..59
    val selection = rememberTimePickerState(initialHour = if (valid) hour!! else 8,
        initialMinute = if (valid) minute!! else 0, is24Hour = timeFormat.is24Hour)
    // Material's format flag is not observable. Recreate only the display wrapper
    // when the phone preference changes, preserving the shared hour/minute selection.
    val state = remember(selection, timeFormat.is24Hour) {
        object : TimePickerState by selection {
            override var is24hour = timeFormat.is24Hour
        }
    }
    var inputMode by rememberSaveable { mutableStateOf(false) }
    var returningToClock by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val colors = TimePickerDefaults.colors(containerColor = scheme.surface,
        clockDialColor = scheme.surfaceVariant, selectorColor = scheme.primary,
        clockDialSelectedContentColor = scheme.onPrimary,
        clockDialUnselectedContentColor = scheme.onSurface,
        periodSelectorBorderColor = scheme.outline,
        periodSelectorSelectedContainerColor = scheme.primaryContainer,
        periodSelectorSelectedContentColor = scheme.onPrimaryContainer,
        periodSelectorUnselectedContainerColor = scheme.surfaceVariant,
        periodSelectorUnselectedContentColor = scheme.onSurfaceVariant,
        timeSelectorSelectedContainerColor = scheme.primaryContainer,
        timeSelectorSelectedContentColor = scheme.onPrimaryContainer,
        timeSelectorUnselectedContainerColor = scheme.surfaceVariant,
        timeSelectorUnselectedContentColor = scheme.onSurface)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        fun switchMode() {
            if (!inputMode) inputMode = true else if (!returningToClock) {
                returningToClock = true
                focus.clearFocus(force = true)
                keyboard?.hide()
                scope.launch {
                    withTimeoutOrNull(1_000) { snapshotFlow { ime.getBottom(density) }.first { it == 0 } }
                    inputMode = false
                    returningToClock = false
                }
            }
        }
        Surface(Modifier.padding(horizontal = 24.dp).widthIn(max = 360.dp).fillMaxWidth()
            .testTag("job_time_picker"), shape = RoundedCornerShape(24.dp), color = scheme.surface,
            contentColor = scheme.onSurface, tonalElevation = 0.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Start time", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Phone's time format", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = ::switchMode, enabled = !returningToClock,
                        modifier = Modifier.testTag("time_picker_mode_toggle")) {
                        Icon(painterResource(if (inputMode) R.drawable.ic_schedule else R.drawable.ic_edit),
                            contentDescription = if (inputMode) "Switch to clock mode" else "Switch to typed time",
                            modifier = Modifier.size(24.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (inputMode) key(timeFormat.is24Hour) { TimeInput(state, colors = colors) }
                else TimePicker(state, colors = colors, layoutType = TimePickerLayoutType.Vertical)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = !returningToClock, onClick = {
                        onSelect(storedTime(state.hour, state.minute))
                    }) { Text("Use time") }
                }
            }
        }
    }
}
