package com.nexlock.agent.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenManager(context: Context) {

    // AES256-GCM-encrypted at rest via the Android Keystore-backed MasterKey — the device JWT
    // is a long-lived (365-day) credential, worth more protection than plain SharedPreferences
    // gave it. Falls back to plain prefs only if keystore init itself fails (rare, but better
    // than crashing the Agent outright over a storage-hardening feature).
    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "nexlock_agent_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("TokenManager", "EncryptedSharedPreferences init failed, falling back to plain prefs", e)
        context.getSharedPreferences("nexlock_agent_prefs", Context.MODE_PRIVATE)
    }

    fun saveEnrollmentSession(deviceToken: String, deviceId: String, loanId: String?, heartbeatInterval: Long) {
        prefs.edit()
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_LOAN_ID, loanId)
            .putLong(KEY_HEARTBEAT_INTERVAL, heartbeatInterval)
            .putBoolean(KEY_IS_ENROLLED, true)
            .apply()
    }

    fun getDeviceToken(): String? = prefs.getString(KEY_DEVICE_TOKEN, null)

    fun getDeviceId(): String? = prefs.getString(KEY_DEVICE_ID, null)

    fun getLoanId(): String? = prefs.getString(KEY_LOAN_ID, null)

    fun getHeartbeatInterval(): Long = prefs.getLong(KEY_HEARTBEAT_INTERVAL, 60L)

    fun isEnrolled(): Boolean = prefs.getBoolean(KEY_IS_ENROLLED, false)

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_DEVICE_TOKEN = "key_device_token"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_LOAN_ID = "key_loan_id"
        private const val KEY_HEARTBEAT_INTERVAL = "key_heartbeat_interval"
        private const val KEY_IS_ENROLLED = "key_is_enrolled"
    }
}
