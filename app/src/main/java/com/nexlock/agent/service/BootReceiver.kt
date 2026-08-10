package com.nexlock.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores the Agent's session after a device reboot, without needing MainActivity to be
 * opened. Enrollment state already survives reboot on its own (plain SharedPreferences data
 * isn't cleared by Android on restart) — what was missing before this was anything to re-arm
 * the periodic heartbeat and immediately resume command handling.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val tokenManager = TokenManager(context)
        if (!tokenManager.isEnrolled()) return
        val deviceToken = tokenManager.getDeviceToken() ?: return

        HeartbeatScheduler.schedule(context)
        if (!PendingAckQueue.isEmpty(context)) {
            RetryWorker.scheduleOneShot(context)
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Event-triggered heartbeat equivalent to "app opened" — the Agent is effectively
            // starting a fresh session after boot, even though no UI was ever shown.
            val telemetry = DeviceTelemetry.capture(context)
            DeviceRepository().sendHeartbeat(
                deviceToken = deviceToken,
                batteryLevel = telemetry.batteryLevel,
                isCharging = telemetry.isCharging,
                networkType = telemetry.networkType,
                networkOperator = telemetry.networkOperator,
                storageAvailableMb = telemetry.storageAvailableMb,
                storageTotalMb = telemetry.storageTotalMb,
                ramAvailableMb = telemetry.ramAvailableMb,
                ramTotalMb = telemetry.ramTotalMb,
                screenState = telemetry.screenState,
                appVersion = telemetry.appVersion,
                fcmToken = currentFcmToken()
            )

            CommandSync.fetchExecuteAck(context, deviceToken)
        }
    }
}
