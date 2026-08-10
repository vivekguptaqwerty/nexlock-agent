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
        // Low-frequency baseline (cost-efficiency decision from the architecture review) —
        // the 24h periodic cycle is just a health-check floor; real signal timing comes from
        // event-triggered heartbeats and FCM-pushed commands, not from polling frequently.
        val workRequest = PeriodicWorkRequestBuilder<HeartbeatWorker>(24, TimeUnit.HOURS)
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
