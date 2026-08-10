package com.nexlock.agent.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexlock.agent.data.repository.DeviceRepository
import java.util.concurrent.TimeUnit

/**
 * Flushes PendingAckQueue once connectivity is available. WorkManager's own retry/backoff
 * handles repeated failures (e.g. backend briefly down even though network is up) — this
 * worker doesn't need its own retry loop on top of that.
 */
class RetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = DeviceRepository()
        val pending = PendingAckQueue.getAll(applicationContext)
        if (pending.isEmpty()) return Result.success()

        var allSucceeded = true
        for (ack in pending) {
            val result = repository.acknowledgeCommand(
                deviceToken = ack.deviceToken,
                commandId = ack.commandId,
                status = ack.status,
                error = ack.error
            )
            if (result.isSuccess) {
                PendingAckQueue.remove(applicationContext, ack.commandId)
            } else {
                allSucceeded = false
            }
        }

        return if (allSucceeded) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "RetryPendingAcksTask"

        fun scheduleOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<RetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
