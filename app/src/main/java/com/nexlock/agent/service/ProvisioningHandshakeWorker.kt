package com.nexlock.agent.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexlock.agent.data.api.NetworkModule
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager

/**
 * Completes enrollment automatically after real QR-based Device Owner provisioning, using the
 * enrollmentToken/otp/serverUrl the backend embedded in the QR's PROVISIONING_ADMIN_EXTRAS_BUNDLE
 * and passed through by NexLockDeviceAdminReceiver.onProfileProvisioningComplete. Calls the exact
 * same /mdm/enroll/handshake endpoint the manual OTP form in MainActivity already uses — this is
 * a second caller of that route, not a new one. Runs as a WorkManager job rather than directly in
 * the BroadcastReceiver callback because receivers have a short execution window and Setup Wizard
 * may not have finished handing off network connectivity yet.
 */
class ProvisioningHandshakeWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val repository = DeviceRepository()

    override suspend fun doWork(): Result {
        val enrollmentToken = inputData.getString(KEY_ENROLLMENT_TOKEN)
        val otp = inputData.getString(KEY_OTP)
        val serverUrl = inputData.getString(KEY_SERVER_URL)

        if (enrollmentToken.isNullOrBlank() && otp.isNullOrBlank()) {
            // Nothing to hand the backend — this shouldn't happen if the QR was generated
            // correctly, but retrying forever over a malformed payload would be pointless.
            return Result.failure()
        }

        if (!serverUrl.isNullOrBlank()) {
            NetworkModule.setBaseUrl(serverUrl)
        }

        val handshakeResult = repository.performHandshake(
            enrollmentToken = enrollmentToken,
            otp = otp,
            androidId = readAndroidId(),
            deviceModel = Build.MODEL ?: "Unknown Model",
            manufacturer = Build.MANUFACTURER ?: "Unknown Manufacturer",
            androidVersion = Build.VERSION.RELEASE ?: "Unknown",
            sdkVersion = Build.VERSION.SDK_INT,
            termsAccepted = inputData.getBoolean(KEY_TERMS_ACCEPTED, false)
        )

        val data = handshakeResult.getOrNull() ?: return Result.retry()

        TokenManager(applicationContext).saveEnrollmentSession(
            deviceToken = data.deviceToken,
            deviceId = data.deviceId,
            loanId = data.loanId,
            heartbeatInterval = data.heartbeatInterval
        )

        DeviceRestrictionPolicy.applyBaselineRestrictions(applicationContext)
        HeartbeatScheduler.schedule(applicationContext)
        HeartbeatForegroundService.start(applicationContext)
        CommandSync.fetchExecuteAck(applicationContext, data.deviceToken)

        return Result.success()
    }

    private fun readAndroidId(): String {
        return try {
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    companion object {
        // Public so ProvisioningCompleteActivity can observe this specific run's WorkInfo and
        // show real progress instead of a fixed timer.
        const val WORK_NAME = "ProvisioningHandshakeWorkerTask"

        private const val KEY_ENROLLMENT_TOKEN = "enrollment_token"
        private const val KEY_OTP = "otp"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"

        fun enqueue(context: Context, enrollmentToken: String?, otp: String?, serverUrl: String?, termsAccepted: Boolean) {
            val input = workDataOf(
                KEY_ENROLLMENT_TOKEN to enrollmentToken,
                KEY_OTP to otp,
                KEY_SERVER_URL to serverUrl,
                KEY_TERMS_ACCEPTED to termsAccepted
            )
            val request = OneTimeWorkRequestBuilder<ProvisioningHandshakeWorker>()
                .setInputData(input)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
