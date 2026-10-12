package com.nexlock.agent.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the one-time switch from "fast, reliable" to "slower, no notification" delivery —
 * stops HeartbeatForegroundService (and with it, WiFi-block / wallpaper-overlay self-healing,
 * not just command delivery) after a backend-configured delay from enrollment
 * (HandshakeData.notificationVisibilityMinutes, ~2 hours by default). The dealer's own
 * post-activation lock/unlock test happens in this window; by the time it elapses the customer
 * already has the phone in the real-world flow (the dealer app hands the OTP to the customer
 * immediately, not hours later), so a slower FCM/WorkManager-only fallback from here on is an
 * accepted trade for not showing a permanent notification.
 *
 * A single one-time WorkManager request with setInitialDelay — not an AlarmManager alarm —
 * specifically because WorkManager already persists pending work across reboots on its own
 * (same mechanism HeartbeatScheduler's periodic work relies on), so a device that reboots
 * partway through the window doesn't get an extra free notification-visible period.
 *
 * Called only from the two real enrollment completion points (AgentViewModel's manual/OTP flow,
 * ProvisioningHandshakeWorker's QR flow) — deliberately NOT from BootReceiver's baseline-
 * restriction reapplication, which would otherwise reset this window on every boot.
 */
object NotificationTimeoutScheduler {
    private const val WORK_NAME = "NotificationTimeoutWorkerTask"

    fun schedule(context: Context, delayMinutes: Long) {
        val workRequest = OneTimeWorkRequestBuilder<NotificationTimeoutWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(1), TimeUnit.MINUTES)
            .build()

        // KEEP, not REPLACE — if this somehow got scheduled twice (shouldn't happen, the two
        // call sites are alternate enrollment paths, not both-fire), the first schedule wins
        // rather than restarting the clock.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, workRequest)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
