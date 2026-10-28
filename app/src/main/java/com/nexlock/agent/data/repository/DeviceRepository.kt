package com.nexlock.agent.data.repository

import com.nexlock.agent.data.api.NetworkModule
import com.nexlock.agent.data.model.*
import com.nexlock.agent.service.DeviceRestrictionPolicy

class DeviceRepository {

    suspend fun performHandshake(
        enrollmentToken: String?,
        otp: String?,
        androidId: String,
        deviceModel: String,
        manufacturer: String,
        androidVersion: String,
        sdkVersion: Int,
        termsAccepted: Boolean = false
    ): Result<HandshakeData> {
        return try {
            val req = HandshakeRequest(
                enrollmentToken = enrollmentToken,
                otp = otp,
                androidId = androidId,
                deviceModel = deviceModel,
                manufacturer = manufacturer,
                androidVersion = androidVersion,
                sdkVersion = sdkVersion,
                appVersion = "1.0.0",
                termsAccepted = termsAccepted
            )
            val response = NetworkModule.apiService.performHandshake(req)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()!!.data!!
                Result.success(data)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Handshake rejected by server"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendHeartbeat(
        deviceToken: String,
        batteryLevel: Int? = null,
        isCharging: Boolean? = null,
        networkType: String? = null,
        networkOperator: String? = null,
        storageAvailableMb: Long? = null,
        storageTotalMb: Long? = null,
        ramAvailableMb: Long? = null,
        ramTotalMb: Long? = null,
        screenState: String? = null,
        appVersion: String = "1.0.0",
        fcmToken: String? = null,
        simPresent: Boolean? = null,
        phoneNumber: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        locationAccuracy: Float? = null
    ): Result<HeartbeatData> {
        return try {
            val req = HeartbeatRequest(
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                networkType = networkType,
                networkOperator = networkOperator,
                storageAvailableMb = storageAvailableMb,
                storageTotalMb = storageTotalMb,
                ramAvailableMb = ramAvailableMb,
                ramTotalMb = ramTotalMb,
                screenState = screenState,
                appVersion = appVersion,
                fcmToken = fcmToken,
                simPresent = simPresent,
                phoneNumber = phoneNumber,
                latitude = latitude,
                longitude = longitude,
                locationAccuracy = locationAccuracy
            )
            val response = NetworkModule.apiService.sendHeartbeat("Bearer $deviceToken", req)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Heartbeat rejected"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPendingCommands(deviceToken: String): Result<List<CommandItem>> {
        return try {
            val response = NetworkModule.apiService.getPendingCommands("Bearer $deviceToken")
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.data ?: emptyList())
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Failed to fetch pending commands"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acknowledgeCommand(
        deviceToken: String,
        commandId: String,
        status: String,
        error: String? = null
    ): Result<Boolean> {
        return try {
            val req = AckRequest(status = status, error = error)
            val response = NetworkModule.apiService.acknowledgeCommand("Bearer $deviceToken", commandId, req)
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(true)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Failed to send command ACK"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLockInfo(deviceToken: String): Result<LockInfoData> {
        return try {
            val response = NetworkModule.apiService.getLockInfo("Bearer $deviceToken")
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Failed to fetch lock info"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Best-effort — see DeviceRestrictionPolicy.applyBaselineRestrictions's own doc comment.
     * Never throws outward: a diagnostic-submission failure must not surface as if enrollment
     * itself failed, and nothing downstream needs to react to whether this succeeded.
     */
    suspend fun submitDiagnostics(deviceToken: String, context: String, steps: List<DeviceRestrictionPolicy.DiagStep>) {
        try {
            NetworkModule.apiService.submitDiagnostics(
                "Bearer $deviceToken",
                DiagnosticsRequest(
                    context = context,
                    steps = steps.map { DiagnosticStepPayload(it.step, it.ok, it.durationMs, it.error) }
                )
            )
        } catch (e: Exception) {
            // Swallowed deliberately — see doc comment above.
        }
    }

    /**
     * Checks whether a locally-stored device token still corresponds to a real, current
     * enrollment on the backend — deviceAuthMiddleware returns 401 for a token whose Device row
     * doesn't exist or was soft-deleted (DEV-2026-001384/001387's root cause: a phone reused for
     * a new customer without ever being Released first keeps its OLD local "enrolled" state
     * forever, with no backend-side device to match it, since nothing on launch ever re-checks).
     * Any GET that device-auth-gates would do; getLockInfo is simply already in place for it.
     * Returns the raw HTTP status code — 401 specifically means "this token is gone, safe to
     * clear local state"; anything else (network failure, 5xx, no connectivity) must NOT be
     * treated as "stale," or a genuinely still-locked phone could get wiped by a bad network
     * moment. -1 signals that non-definitive case.
     */
    suspend fun checkDeviceTokenStillValid(deviceToken: String): Int {
        return try {
            NetworkModule.apiService.getLockInfo("Bearer $deviceToken").code()
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun getPrivacyPolicy(): Result<LegalContentData> {
        return try {
            val response = NetworkModule.apiService.getPrivacyPolicy()
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Failed to fetch privacy policy"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTerms(): Result<LegalContentData> {
        return try {
            val response = NetworkModule.apiService.getTerms()
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()!!.data!!)
            } else {
                Result.failure(Exception(NetworkModule.errorMessage(response) ?: "Failed to fetch terms"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
