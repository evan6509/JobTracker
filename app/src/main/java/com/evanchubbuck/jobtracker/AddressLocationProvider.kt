package com.evanchubbuck.jobtracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

internal interface AddressLocationProvider {
    suspend fun current(): AddressPoint?
}

/** An approximate, recent location helps order addresses without delaying the first search. */
internal class DeviceAddressLocationProvider(context: Context) : AddressLocationProvider {
    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun current(): AddressPoint? = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext null
        val cached = recentLocation()
        if (cached != null && ageNanos(cached) <= TimeUnit.MINUTES.toNanos(5))
            return@withContext cached.toAddressPoint()
        val fresh = if (Build.VERSION.SDK_INT >= 30) currentLocation() else null
        (fresh?.takeIf { ageNanos(it) <= TimeUnit.MINUTES.toNanos(30) } ?: cached)?.toAddressPoint()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun recentLocation(): Location? {
        if (!hasPermission()) return null
        val providers = if (Build.VERSION.SDK_INT >= 31)
            listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        else listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER,
            LocationManager.GPS_PROVIDER)
        return providers
            .mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) }
                catch (_: SecurityException) { null }
                catch (_: IllegalArgumentException) { null }
            }
            .filter { ageNanos(it) <= TimeUnit.MINUTES.toNanos(30) }
            .minByOrNull(::ageNanos)
    }

    private fun ageNanos(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0)

    private fun Location.toAddressPoint(): AddressPoint? =
        if (latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            AddressPoint((latitude * 100).roundToInt() / 100.0, (longitude * 100).roundToInt() / 100.0) else null

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun currentLocation(): Location? = withTimeoutOrNull(2_500) {
        coroutineScope {
            val network = async { currentLocationFrom(LocationManager.NETWORK_PROVIDER) }
            val fused = if (Build.VERSION.SDK_INT >= 31)
                async { currentLocationFrom(LocationManager.FUSED_PROVIDER) } else null
            if (fused == null) return@coroutineScope network.await()
            val first = select<Pair<Boolean, Location?>> {
                fused.onAwait { true to it }
                network.onAwait { false to it }
            }
            if (first.second != null) {
                if (first.first) network.cancel() else fused.cancel()
                first.second
            } else if (first.first) network.await() else fused.await()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun currentLocationFrom(provider: String): Location? {
        if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) return null
        return suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationSignal()
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                manager.getCurrentLocation(provider, cancellation,
                    ContextCompat.getMainExecutor(context)) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                if (continuation.isActive) continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
