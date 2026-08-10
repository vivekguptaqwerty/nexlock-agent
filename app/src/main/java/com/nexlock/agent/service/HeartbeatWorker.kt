package com.nexlock.agent.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager

class HeartbeatWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DeviceRepository()
    private val tokenManager = TokenManager(context)

    override suspend fun doWork(): Result {
        val deviceToken = tokenManager.getDeviceToken()
        if (deviceToken.isNullOrBlank()) {
            return Result.failure()
        }

        val telemetry = DeviceTelemetry.capture(applicationContext)
        val result = repository.sendHeartbeat(
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

        return if (result.isSuccess) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
