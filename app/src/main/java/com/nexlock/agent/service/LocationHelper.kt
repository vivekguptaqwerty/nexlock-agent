package com.nexlock.agent.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float
)

/**
 * Wraps the Fused Location Provider for the heartbeat loop. Uses PRIORITY_BALANCED_POWER_ACCURACY
 * rather than PRIORITY_HIGH_ACCURACY deliberately — a fleet of devices requesting a full GPS fix
 * every 15 minutes would be a meaningful battery cost, and GPS frequently fails indoors anyway
 * (which is where these financed phones spend most of their time). Balanced mode blends Wi-Fi/cell
 * signals, returns fast, and works indoors — at the cost of "approximate" (tens–hundreds of
 * meters) rather than pinpoint accuracy. See CommandDispatcher-adjacent design notes on this
 * tradeoff.
 */
object LocationHelper {

    private const val TIMEOUT_MS = 20_000L

    suspend fun getCurrentLocation(context: Context): LocationFix? {
        if (!hasLocationPermission(context)) return null

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val client = LocationServices.getFusedLocationProviderClient(context)
                val cancellationSignal = CancellationSignal()
                continuation.invokeOnCancellation { cancellationSignal.cancel() }

                try {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) {
                                val fix = location?.let {
                                    LocationFix(it.latitude, it.longitude, it.accuracy)
                                }
                                continuation.resume(fix)
                            }
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                } catch (e: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
