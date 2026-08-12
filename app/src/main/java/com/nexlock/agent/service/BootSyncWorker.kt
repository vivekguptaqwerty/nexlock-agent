package com.nexlock.agent.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager

/**
 * Does the actual heartbeat + pending-command sync after a reboot, as a properly constrained
 * WorkManager job rather than a raw coroutine fired directly from BootReceiver. BOOT_COMPLETED
 * firing does not mean network connectivity is up yet — a raw coroutine attempting a network
 * call immediately after boot can lose that race, fail once, and (unlike this Worker) have
 * nothing left to retry it until the next scheduled heartbeat, up to 24h later. This Worker
 * won't even start until NetworkType.CONNECTED is satisfied, and returns Result.retry() on
 * failure so WorkManager keeps trying with backoff instead of giving up silently.
 */
class BootSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DeviceRepository()

    override suspend fun doWork(): Result {
        val tokenManager = TokenManager(applicationContext)
        if (!tokenManager.isEnrolled()) return Result.success()
        val deviceToken = tokenManager.getDeviceToken() ?: return Result.success()

        val telemetry = DeviceTelemetry.capture(applicationContext)
        val heartbeatResult = repository.sendHeartbeat(
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

        if (heartbeatResult.isFailure) {
            return Result.retry()
        }

        CommandSync.fetchExecuteAck(applicationContext, deviceToken)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "BootSyncWorkerTask"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BootSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
