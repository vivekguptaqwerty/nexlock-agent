package com.nexlock.agent.service

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nexlock.agent.data.storage.TokenManager
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
 * Two independent mechanisms enforce the same deadline, not one:
 * 1. This WorkManager one-time job — kept as a best-effort path, persists pending work across
 *    reboots on its own.
 * 2. A plain timestamp (TokenManager.saveNotificationHideAt) that HeartbeatForegroundService's
 *    own already-running loop checks every 15 minutes and self-enforces. This is the one that
 *    actually matters: confirmed live (notification still showing a full day after enrollment,
 *    not 2 hours) that WorkManager jobs suffer the exact same OEM-battery-killing unreliability
 *    this foreground service exists to route around in the first place (see its own doc
 *    comment) — a one-time delayed WorkManager job can simply never fire on these phones, same
 *    as the periodic heartbeat job it already replaced. The foreground service is the one thing
 *    proven reliable here, so it has to enforce its own shutdown rather than depend on
 *    WorkManager reaching in from outside.
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

        // Same "first wins" semantics as the KEEP policy above — only set the deadline if one
        // isn't already there.
        val tokenManager = TokenManager(context)
        if (tokenManager.getNotificationHideAt() == 0L) {
            tokenManager.saveNotificationHideAt(
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes.coerceAtLeast(1))
            )
        }
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        TokenManager(context).clearNotificationHideAt()
    }
}
