package com.nexlock.agent.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiResponse<T>(
    @Json(name = "success") val success: Boolean,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: T? = null
)

@JsonClass(generateAdapter = true)
data class HandshakeRequest(
    @Json(name = "enrollmentToken") val enrollmentToken: String? = null,
    @Json(name = "otp") val otp: String? = null,
    @Json(name = "androidId") val androidId: String? = null,
    @Json(name = "imei") val imei: String? = null,
    @Json(name = "serialNumber") val serialNumber: String? = null,
    @Json(name = "deviceModel") val deviceModel: String? = null,
    @Json(name = "manufacturer") val manufacturer: String? = null,
    @Json(name = "androidVersion") val androidVersion: String? = null,
    @Json(name = "sdkVersion") val sdkVersion: Int? = null,
    @Json(name = "appVersion") val appVersion: String = "1.0.0",
    @Json(name = "timestamp") val timestamp: String? = null
)

@JsonClass(generateAdapter = true)
data class HandshakeData(
    @Json(name = "deviceToken") val deviceToken: String,
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "loanId") val loanId: String? = null,
    @Json(name = "heartbeatInterval") val heartbeatInterval: Long = 60,
    @Json(name = "pollingInterval") val pollingInterval: Long = 300,
    @Json(name = "serverTime") val serverTime: String
)

@JsonClass(generateAdapter = true)
data class HeartbeatRequest(
    @Json(name = "batteryLevel") val batteryLevel: Int? = null,
    @Json(name = "isCharging") val isCharging: Boolean? = null,
    @Json(name = "networkType") val networkType: String? = "WIFI",
    @Json(name = "networkOperator") val networkOperator: String? = null,
    @Json(name = "storageAvailableMb") val storageAvailableMb: Long? = null,
    @Json(name = "storageTotalMb") val storageTotalMb: Long? = null,
    @Json(name = "ramAvailableMb") val ramAvailableMb: Long? = null,
    @Json(name = "ramTotalMb") val ramTotalMb: Long? = null,
    @Json(name = "screenState") val screenState: String? = "ON",
    @Json(name = "appVersion") val appVersion: String = "1.0.0",
    @Json(name = "timestamp") val timestamp: String? = null,
    // Refreshed opportunistically — reuses the heartbeat pipeline rather than a dedicated
    // FCM-token-registration endpoint.
    @Json(name = "fcmToken") val fcmToken: String? = null,
    @Json(name = "simPresent") val simPresent: Boolean? = null,
    @Json(name = "phoneNumber") val phoneNumber: String? = null,
    @Json(name = "latitude") val latitude: Double? = null,
    @Json(name = "longitude") val longitude: Double? = null,
    @Json(name = "locationAccuracy") val locationAccuracy: Float? = null
)

@JsonClass(generateAdapter = true)
data class HeartbeatData(
    @Json(name = "serverTime") val serverTime: String,
    @Json(name = "heartbeatInterval") val heartbeatInterval: Long = 60,
    @Json(name = "status") val status: String = "HEALTHY"
)

@JsonClass(generateAdapter = true)
data class CommandItem(
    @Json(name = "id") val id: String,
    @Json(name = "deviceId") val deviceId: String,
    @Json(name = "commandType") val commandType: String,
    @Json(name = "status") val status: String,
    @Json(name = "createdAt") val createdAt: String? = null
)

@JsonClass(generateAdapter = true)
data class AckRequest(
    @Json(name = "status") val status: String,
    @Json(name = "error") val error: String? = null,
    @Json(name = "executedAt") val executedAt: String? = null
)
