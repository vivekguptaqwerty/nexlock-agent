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
        val location = LocationHelper.getCurrentLocation(applicationContext)
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
            fcmToken = currentFcmToken(),
            simPresent = telemetry.simPresent,
            phoneNumber = telemetry.phoneNumber,
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationAccuracy = location?.accuracyMeters
        )

        if (result.isFailure) {
            return Result.retry()
        }

        // This periodic worker is the one path in the app with a real NetworkType.CONNECTED
        // constraint AND WorkManager-managed retry — unlike FCM push (silently dropped by some
        // OEM battery managers, observed on real hardware) or the boot-time sync (only fires
        // once per reboot). Checking for pending commands here too means there is at least one
        // periodic, reliable backstop that doesn't depend on either of those working.
        CommandSync.fetchExecuteAck(applicationContext, deviceToken)

        return Result.success()
    }
}
