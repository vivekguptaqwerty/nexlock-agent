package com.nexlock.agent.service

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexlock.agent.data.model.BootstrapClaimData
import com.nexlock.agent.data.repository.DeviceRepository
import java.util.concurrent.TimeUnit

/**
 * Redmi/Poco bootstrap flow only. Fires after NexLock becomes Device Owner via TestDPC ownership
 * transfer (NexLockDeviceAdminReceiver.onTransferOwnershipComplete).
 *
 * Unlike QR provisioning, an ownership transfer carries no enrollmentToken/otp — TestDPC's transfer
 * bundle is a fixed placeholder it can't be made to forward our values through. So we recover them
 * from the backend by IMEI (readable now that we're Device Owner) via POST /mdm/bootstrap-claim,
 * then hand off to the exact same ProvisioningHandshakeWorker tail the QR path uses — nothing
 * downstream cares how Device Owner status was obtained.
 */
class BootstrapClaimWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val repository = DeviceRepository()

    override suspend fun doWork(): Result {
        val imeis = readImeis()
        if (imeis.isEmpty()) {
            // No telephony/IMEI yet (stack may not be up right after transfer, or a no-SIM/tablet
            // device). Retry a few times before giving up — IMEI is the only identifier we have.
            Log.w(TAG, "No IMEI available (attempt $runAttemptCount) — retrying")
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }

        // The dealer typed one IMEI into inventory (usually slot 0's), but on a dual-SIM phone we
        // can't be sure which slot it was — try each until the backend matches one.
        var claim: BootstrapClaimData? = null
        for (imei in imeis) {
            val result = repository.claimBootstrapEnrollment(imei)
            if (result.isSuccess) {
                claim = result.getOrNull()
                break
            }
        }

        if (claim?.enrollmentToken.isNullOrBlank() && claim?.otp.isNullOrBlank()) {
            // No matching device / pending session yet, or a transient failure. The dealer may not
            // have created the enrollment on the backend at the moment of transfer — keep retrying.
            Log.w(TAG, "Bootstrap claim unresolved (attempt $runAttemptCount) — retrying")
            return if (runAttemptCount < 10) Result.retry() else Result.failure()
        }

        Log.i(TAG, "Bootstrap claim succeeded — handing off to provisioning handshake")
        ProvisioningHandshakeWorker.enqueue(
            context = applicationContext,
            enrollmentToken = claim?.enrollmentToken,
            otp = claim?.otp,
            serverUrl = claim?.serverUrl,
            // Consent is captured by the dealer-mediated enrolment itself; there's no customer-facing
            // "I Agree" step in the bootstrap flow the way the QR path shows one.
            termsAccepted = true
        )
        return Result.success()
    }

    private fun readImeis(): List<String> {
        return try {
            val tm = applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return emptyList()
            val slots = try { tm.phoneCount } catch (e: Exception) { 1 }
            val out = mutableListOf<String>()
            for (slot in 0 until maxOf(1, slots)) {
                val imei = try {
                    // Device Owner is granted privileged phone-state access, so getImei() works here
                    // even on Android 10+ where an ordinary app would be blocked.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        tm.getImei(slot)
                    } else {
                        @Suppress("DEPRECATION") tm.deviceId
                    }
                } catch (e: Exception) {
                    null
                }
                if (!imei.isNullOrBlank() && imei !in out) out.add(imei)
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BootstrapClaimWorker"
        const val WORK_NAME = "BootstrapClaimWorkerTask"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BootstrapClaimWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
