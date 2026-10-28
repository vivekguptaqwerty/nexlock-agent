package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
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
 *
 * Also reasserts the baseline (Tier A) restrictions — factory-reset block, uninstall block,
 * etc. — on every boot, independent of lock state. These are meant to be OS-persisted
 * automatically, but re-applying them defensively here means a restriction that failed to
 * stick the first time (for whatever reason — see DeviceRestrictionPolicy's verification
 * logging) gets a second chance to apply on every single boot, rather than only ever being
 * attempted once at enrollment time.
 *
 * Also fires on ACTION_MY_PACKAGE_REPLACED — the same reassertion is just as relevant right
 * after AgentSelfUpdater silently installs a new version over the running one as it is after a
 * real reboot (the process restarts either way, and nothing here is reboot-specific).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val tokenManager = TokenManager(context)
        if (!tokenManager.isEnrolled()) return
        if (tokenManager.getDeviceToken() == null) return

        // Each step below is independently guarded — this whole receiver previously crashed
        // outright the moment ANY one call threw (observed on real hardware: a HyperOS build
        // that dispatches BOOT_COMPLETED before androidx.startup's InitializationProvider has
        // run for this process, so WorkManager.getInstance() throws "WorkManager is not
        // initialized properly"). Because Kotlin/Java exceptions abort the rest of the
        // function, that one throw from HeartbeatScheduler.schedule() silently skipped EVERY
        // step after it too — HeartbeatForegroundService.start(), the pending-ack retry check,
        // BootSyncWorker.enqueue() — leaving a rebooted device with no working heartbeat and no
        // way to receive LOCK/UNLOCK/RELEASE_DEVICE until the app was somehow reopened by hand.
        // Each call is cheap and independent of the others, so one failing must not take the
        // rest down with it.
        runCatching {
            val diagLog = DeviceRestrictionPolicy.applyBaselineRestrictions(context)
            val token = tokenManager.getDeviceToken()
            if (token != null) {
                // Fire-and-forget on its own IO-dispatcher coroutine — onReceive() itself stays
                // synchronous (no goAsync()), consistent with every other step here; losing this
                // one submission to the receiver's short execution window is an acceptable
                // tradeoff for a best-effort diagnostic, same reasoning as DeviceRepository
                // #submitDiagnostics itself never surfacing a failure.
                CoroutineScope(Dispatchers.IO).launch {
                    DeviceRepository().submitDiagnostics(token, "boot_reassert", diagLog)
                }
            }
        }.onFailure { Log.e(TAG, "applyBaselineRestrictions failed on boot", it) }
        runCatching { WifiBlockManager.enforce(context) }
            .onFailure { Log.e(TAG, "WifiBlockManager.enforce failed on boot", it) }
        runCatching { BluetoothBlockManager.enforce(context) }
            .onFailure { Log.e(TAG, "BluetoothBlockManager.enforce failed on boot", it) }
        runCatching { WallpaperOverlayManager.enforce(context) }
            .onFailure { Log.e(TAG, "WallpaperOverlayManager.enforce failed on boot", it) }

        runCatching {
            if (LockStateManager(context).isLocked()) {
                reassertKioskLock(context)
            }
        }.onFailure { Log.e(TAG, "reassertKioskLock failed on boot", it) }

        runCatching { HeartbeatScheduler.schedule(context) }
            .onFailure { Log.e(TAG, "HeartbeatScheduler.schedule failed on boot", it) }
        runCatching { HeartbeatForegroundService.start(context) }
            .onFailure { Log.e(TAG, "HeartbeatForegroundService.start failed on boot", it) }
        runCatching {
            if (!PendingAckQueue.isEmpty(context)) {
                RetryWorker.scheduleOneShot(context)
            }
        }.onFailure { Log.e(TAG, "RetryWorker.scheduleOneShot failed on boot", it) }

        // Enqueued as a network-constrained WorkManager job rather than fired directly as a
        // coroutine: BOOT_COMPLETED does not guarantee network connectivity is up yet, and a
        // raw one-shot call that loses that race would fail silently with nothing to retry it
        // until the next scheduled heartbeat (up to 24h later). BootSyncWorker won't start
        // until connectivity is actually available, and retries with backoff on failure.
        runCatching { BootSyncWorker.enqueue(context) }
            .onFailure { Log.e(TAG, "BootSyncWorker.enqueue failed on boot", it) }
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
