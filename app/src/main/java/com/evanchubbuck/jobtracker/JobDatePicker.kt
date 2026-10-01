package com.evanchubbuck.jobtracker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Material's calendar represents calendar days at midnight UTC. Keep the stored
// date as that same day, rather than converting it through the phone's time zone.
internal fun pickerDate(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(millis))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobDatePickerDialog(label: String, value: String, minimumDate: String?,
    onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val minimum = minimumDate?.let { parseDate(it, "UTC") }
    val selected = parseDate(value, "UTC")?.takeIf { minimum == null || it >= minimum }
    fun year(millis: Long) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = millis
    }.get(Calendar.YEAR)
    val minimumYear = minimum?.let(::year)
    val selectedYear = selected?.let(::year)
    val defaultYears = DatePickerDefaults.YearRange
    val selectableDates = remember(minimum) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = minimum == null || utcTimeMillis >= minimum
            override fun isSelectableYear(year: Int) = minimumYear == null || year >= minimumYear
        }
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = selected,
        initialDisplayedMonthMillis = selected ?: minimum, selectableDates = selectableDates,
        yearRange = minOf(defaultYears.first, selectedYear ?: defaultYears.first, minimumYear ?: defaultYears.first)..
            maxOf(defaultYears.last, selectedYear ?: defaultYears.last, minimumYear ?: defaultYears.last))
    val colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
    var returningToCalendar by remember { mutableStateOf(false) }
    DatePickerDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(24.dp), colors = colors,
        modifier = Modifier.testTag("job_date_picker"),
        confirmButton = {
            TextButton(enabled = !returningToCalendar && state.selectedDateMillis?.let(selectableDates::isSelectableDate) == true,
                onClick = { state.selectedDateMillis?.let { onSelect(pickerDate(it)) } }) { Text("Use date") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }) {
        // These belong to the dialog's window, which owns the date input focus and IME.
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        fun switchMode() {
            if (state.displayMode == DisplayMode.Picker) {
                state.displayMode = DisplayMode.Input
            } else if (!returningToCalendar) {
                returningToCalendar = true
                focus.clearFocus(force = true)
                keyboard?.hide()
                scope.launch {
                    // Finish the keyboard resize before mounting the taller calendar.
                    withTimeoutOrNull(1_000) {
                        snapshotFlow { ime.getBottom(density) }.first { it == 0 }
                    }
                    state.displayMode = DisplayMode.Picker
                    returningToCalendar = false
                }
            }
        }
        // Keep selection in the shared state, but create each mode directly. Material's
        // built-in slide/size transition otherwise competes with the IME resize.
        key(state.displayMode) {
            DatePicker(state, colors = colors, showModeToggle = false,
                title = { Text(label, modifier = Modifier.padding(start = 24.dp, top = 20.dp, end = 24.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                headline = {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.selectedDateMillis?.let { displayDate(pickerDate(it), "UTC") } ?: "Choose a date",
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            if (minimum != null) Text("On or after ${displayDate(pickerDate(minimum), "UTC")}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = ::switchMode, enabled = !returningToCalendar,
                            modifier = Modifier.testTag("date_picker_mode_toggle")) {
                            Icon(painterResource(if (state.displayMode == DisplayMode.Picker) R.drawable.ic_edit else R.drawable.ic_calendar),
                                contentDescription = if (state.displayMode == DisplayMode.Picker) "Switch to text input mode" else "Switch to calendar input mode",
                                modifier = Modifier.size(24.dp))
                        }
                    }
                })
        }
    }
}
