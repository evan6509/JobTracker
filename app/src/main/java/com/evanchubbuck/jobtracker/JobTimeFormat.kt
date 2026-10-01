package com.evanchubbuck.jobtracker

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal data class JobTimeFormat(
    val is24Hour: Boolean = true,
    val pattern: String = "HH:mm",
    val locale: Locale = Locale.getDefault(),
)

internal val LocalJobTimeFormat = compositionLocalOf { JobTimeFormat() }

internal fun phoneTimeFormat(context: Context): JobTimeFormat {
    val is24Hour = DateFormat.is24HourFormat(context)
    val locale = context.resources.configuration.locales[0]
    val pattern = (DateFormat.getTimeFormat(context) as? SimpleDateFormat)?.toPattern()
        ?: DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm")
    return JobTimeFormat(is24Hour, pattern, locale)
}

@Composable
internal fun PhoneTimeFormat(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var format by remember(context, configuration) { mutableStateOf(phoneTimeFormat(context)) }
    DisposableEffect(context, configuration, lifecycle) {
        fun refresh() { format = phoneTimeFormat(context) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        context.contentResolver.registerContentObserver(Settings.System.getUriFor(Settings.System.TIME_12_24), false, observer)
        lifecycle.addObserver(lifecycleObserver)
        refresh()
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }
    CompositionLocalProvider(LocalJobTimeFormat provides format, content = content)
}

internal fun storedTime(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.US, hour, minute)

internal fun displayTime(value: String, format: JobTimeFormat): String {
    val parts = value.split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()
    val minute = parts.getOrNull(1)?.toIntOrNull()
    if (parts.size != 2 || hour == null || hour !in 0..23 || minute == null || minute !in 0..59) return value
    // A stored job time is a wall-clock time in its job's zone. Formatting must
    // change only its appearance, without shifting the hour to the viewer's zone.
    val zone = TimeZone.getTimeZone("UTC")
    val time = Calendar.getInstance(zone).apply {
        clear()
        set(2000, Calendar.JANUARY, 1, hour, minute)
    }.time
    return SimpleDateFormat(format.pattern, format.locale).apply { timeZone = zone }.format(time)
}
