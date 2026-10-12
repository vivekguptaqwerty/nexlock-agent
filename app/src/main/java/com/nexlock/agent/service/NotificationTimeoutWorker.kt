package com.nexlock.agent.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexlock.agent.data.storage.TokenManager

/**
 * Fires once, after NotificationTimeoutScheduler's delay — stops HeartbeatForegroundService
 * permanently (no reschedule). Harmless no-op if the device was released or re-enrolled before
 * this fired (stopping an already-stopped service), or if it's no longer enrolled at all.
 */
class NotificationTimeoutWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (TokenManager(applicationContext).isEnrolled()) {
            HeartbeatForegroundService.stop(applicationContext)
        }
        return Result.success()
    }
}
