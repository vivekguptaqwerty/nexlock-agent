package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.nexlock.agent.data.storage.LockStateManager
import com.nexlock.agent.data.storage.TokenManager
import com.nexlock.agent.kiosk.KioskLockActivity

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
 *
 * Also reasserts the baseline (Tier A) restrictions — factory-reset block, uninstall block,
 * etc. — on every boot, independent of lock state. These are meant to be OS-persisted
 * automatically, but re-applying them defensively here means a restriction that failed to
 * stick the first time (for whatever reason — see DeviceRestrictionPolicy's verification
 * logging) gets a second chance to apply on every single boot, rather than only ever being
 * attempted once at enrollment time.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val tokenManager = TokenManager(context)
        if (!tokenManager.isEnrolled()) return
        if (tokenManager.getDeviceToken() == null) return

        DeviceRestrictionPolicy.applyBaselineRestrictions(context)

        if (LockStateManager(context).isLocked()) {
            reassertKioskLock(context)
        }

        HeartbeatScheduler.schedule(context)
        if (!PendingAckQueue.isEmpty(context)) {
            RetryWorker.scheduleOneShot(context)
        }

        // Enqueued as a network-constrained WorkManager job rather than fired directly as a
        // coroutine: BOOT_COMPLETED does not guarantee network connectivity is up yet, and a
        // raw one-shot call that loses that race would fail silently with nothing to retry it
        // until the next scheduled heartbeat (up to 24h later). BootSyncWorker won't start
        // until connectivity is actually available, and retries with backoff on failure.
        BootSyncWorker.enqueue(context)
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
