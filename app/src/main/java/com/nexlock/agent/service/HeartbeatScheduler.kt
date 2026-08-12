package com.nexlock.agent.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Shared with AgentViewModel (app-open path) and BootReceiver (boot-recovery path) so the
 * periodic heartbeat is scheduled identically regardless of what triggered the Agent to run.
 */
object HeartbeatScheduler {
    const val WORK_NAME = "HeartbeatWorkerTask"

    fun schedule(context: Context) {
        // 15 minutes is Android WorkManager's minimum periodic interval — used here
        // deliberately, not as a low-frequency health-check floor. Real hardware testing
        // showed FCM-pushed commands and boot-time sync can both fail to reach the device on
        // some OEM battery managers (observed on ColorOS) with nothing else picking up pending
        // commands until the next attempt. HeartbeatWorker now also checks for pending commands
        // on every run (see HeartbeatWorker), so this interval is the reliable backstop for
        // command delivery, not just a telemetry ping — the tradeoff is battery usage, accepted
        // because a lock/unlock command silently sitting for up to 24h is worse.
        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, workRequest)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
