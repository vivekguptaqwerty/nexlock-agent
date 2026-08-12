package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.LockStateManager
import com.nexlock.agent.data.storage.TokenManager
import com.nexlock.agent.kiosk.KioskLockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores the Agent's session after a device reboot, without needing MainActivity to be
 * opened. Enrollment state already survives reboot on its own (plain SharedPreferences data
 * isn't cleared by Android on restart) — what was missing before this was anything to re-arm
 * the periodic heartbeat and immediately resume command handling.
 *
 * Also reconciles the kiosk lock state: a pinned lock-task foreground task does not survive a
 * reboot on its own (the process and task stack are gone), so if the device was locked when it
 * went down, this relaunches KioskLockActivity immediately rather than leaving a locked device
 * sitting on its normal home screen after a restart.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val tokenManager = TokenManager(context)
        if (!tokenManager.isEnrolled()) return
        val deviceToken = tokenManager.getDeviceToken() ?: return

        if (LockStateManager(context).isLocked()) {
            reassertKioskLock(context)
        }

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

    private fun reassertKioskLock(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
            val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)
            try {
                // UserManager restrictions and the lock-task allowlist are OS-persisted
                // automatically, but setStatusBarDisabled's cross-reboot persistence is less
                // consistently documented — cheap to reassert defensively here regardless.
                dpm.setStatusBarDisabled(admin, true)
            } catch (e: Exception) {
                // Non-fatal — see CommandDispatcher for the same defensive pattern.
            }
        }

        val intent = Intent(context, KioskLockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
