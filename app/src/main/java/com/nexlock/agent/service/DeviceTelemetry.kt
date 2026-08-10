package com.nexlock.agent.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TelemetrySnapshot(
    val batteryLevel: Int?,
    val isCharging: Boolean?,
    val networkType: String?,
    val networkOperator: String?,
    val storageAvailableMb: Long?,
    val storageTotalMb: Long?,
    val ramAvailableMb: Long?,
    val ramTotalMb: Long?,
    val screenState: String?,
    val appVersion: String,
    val timestamp: String
)

/**
 * Reads real device state via standard, non-privileged Android SDK APIs — no Device Owner,
 * no special/dangerous permissions required for any of these. Replaces the hardcoded fake
 * heartbeat values (random battery %, always-WIFI, always-charging) from Sprint 2.6.
 */
object DeviceTelemetry {

    fun capture(context: Context): TelemetrySnapshot {
        val battery = readBattery(context)
        val network = readNetwork(context)
        val storage = readStorage()
        val ram = readRam(context)
        val screenOn = try {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive
        } catch (e: Exception) {
            null
        }

        return TelemetrySnapshot(
            batteryLevel = battery?.first,
            isCharging = battery?.second,
            networkType = network.first,
            networkOperator = network.second,
            storageAvailableMb = storage?.first,
            storageTotalMb = storage?.second,
            ramAvailableMb = ram?.first,
            ramTotalMb = ram?.second,
            screenState = when (screenOn) {
                true -> "ON"
                false -> "OFF"
                null -> null
            },
            appVersion = readAppVersion(context),
            timestamp = isoTimestampNow()
        )
    }

    private fun readBattery(context: Context): Pair<Int, Boolean>? {
        return try {
            val batteryStatus: Intent? =
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level < 0 || scale <= 0) return null

            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging =
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            Pair((level * 100) / scale, charging)
        } catch (e: Exception) {
            null
        }
    }

    private fun readNetwork(context: Context): Pair<String?, String?> {
        val type = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            when {
                caps == null -> "NONE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
        } catch (e: Exception) {
            null
        }

        val operator = try {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)
                ?.networkOperatorName?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        return Pair(type, operator)
    }

    private fun readStorage(): Pair<Long, Long>? {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
            val totalMb = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024)
            Pair(availableMb, totalMb)
        } catch (e: Exception) {
            null
        }
    }

    private fun readRam(context: Context): Pair<Long, Long>? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(info)
            Pair(info.availMem / (1024 * 1024), info.totalMem / (1024 * 1024))
        } catch (e: Exception) {
            null
        }
    }

    private fun readAppVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private fun isoTimestampNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
