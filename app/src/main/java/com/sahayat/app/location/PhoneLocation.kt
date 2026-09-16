package com.sahayat.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sahayat.app.data.model.EventLocation
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The patient phone's real position (fused GPS / Wi-Fi / cell). Returns null when permission is missing,
 * Location is switched off, or no fix arrives in time — the app then shows "Location unavailable".
 */
object PhoneLocation {
    private const val TAG = "SahayatLocation"
    /** A cached fix younger than this is used immediately. */
    private const val FRESH_MS = 2 * 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPrecise(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** "Allow all the time" — needed to read location while the app is in the background (alerts). */
    fun hasBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun ready(context: Context) = hasPermission(context) && hasBackground(context) && locationOn(context)

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 8_000): EventLocation? {
        if (!hasPermission(context) || !locationOn(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            val last = runCatching { client.lastLocation.await() }.getOrNull()
            if (last != null && System.currentTimeMillis() - last.time < FRESH_MS && last.accuracy <= 100f) {
                return last.toEventLocation("last fix")
            }
            val cts = CancellationTokenSource()
            val request = CurrentLocationRequest.Builder()
                .setPriority(if (hasPrecise(context)) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(FRESH_MS)
                .setDurationMillis(timeoutMs)
                .build()
            val fix = withTimeoutOrNull(timeoutMs + 1_000) { client.getCurrentLocation(request, cts.token).await() }
            if (fix == null) cts.cancel()
            (fix ?: last?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L })
                ?.toEventLocation(if (fix != null) "phone" else "last known")
        } catch (e: Exception) {
            // Never log coordinates.
            Log.w(TAG, "Location failed: ${e::class.java.simpleName}")
            null
        }
    }

    private fun Location.toEventLocation(source: String) = EventLocation(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        source = source,
        capturedAtMs = time,
    )

    fun mapsLink(l: EventLocation): String = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", l.lat, l.lng)
}
