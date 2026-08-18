package com.nexlock.agent.data.repository

import com.nexlock.agent.data.api.NetworkModule
import com.nexlock.agent.data.model.*

class DeviceRepository {

    suspend fun performHandshake(
        enrollmentToken: String?,
        otp: String?,
        androidId: String,
        deviceModel: String,
        manufacturer: String,
        androidVersion: String,
        sdkVersion: Int
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
                appVersion = "1.0.0"
            )
            val response = NetworkModule.apiService.performHandshake(req)
            if (response.isSuccessful && response.body()?.success == true) {
                val data = response.body()!!.data!!
                Result.success(data)
            } else {
                Result.failure(Exception(response.body()?.message ?: "Handshake rejected by server"))
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
                Result.failure(Exception(response.body()?.message ?: "Heartbeat rejected"))
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
                Result.failure(Exception(response.body()?.message ?: "Failed to fetch pending commands"))
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
                Result.failure(Exception(response.body()?.message ?: "Failed to send command ACK"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
