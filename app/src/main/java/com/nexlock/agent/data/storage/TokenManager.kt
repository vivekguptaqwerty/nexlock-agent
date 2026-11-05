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

    // Set locally the moment the customer taps "I Agree" on TermsAcceptanceActivity (manual
    // flow — see AgentViewModel), read back before performHandshake() sends termsAccepted to
    // the backend. The QR flow doesn't need this: it passes termsAccepted straight through
    // ProvisioningHandshakeWorker's enqueue() extras instead, since there's no gap between
    // acceptance and the handshake call in that flow.
    fun setTermsAccepted(accepted: Boolean) {
        prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, accepted).apply()
    }

    fun isTermsAccepted(): Boolean = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)

    // The actual enforcement point for the persistent-notification timeout — see
    // HeartbeatForegroundService.runLoop(), which checks this on its own 15-minute cycle rather
    // than relying solely on NotificationTimeoutScheduler's WorkManager job to reach in from
    // outside (that job shares the exact OEM-battery-killing unreliability this foreground
    // service itself exists to route around — see HeartbeatForegroundService's own doc comment).
    // 0L means "no deadline set" (not enrolled yet, or already stopped/cleared).
    fun saveNotificationHideAt(hideAtMillis: Long) {
        prefs.edit().putLong(KEY_NOTIFICATION_HIDE_AT, hideAtMillis).apply()
    }

    fun getNotificationHideAt(): Long = prefs.getLong(KEY_NOTIFICATION_HIDE_AT, 0L)

    fun clearNotificationHideAt() {
        prefs.edit().remove(KEY_NOTIFICATION_HIDE_AT).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    // Admin-only, per-device override (DISABLE_SYNC_SERVICE/ENABLE_SYNC_SERVICE — see
    // CommandDispatcher) — deliberately checked inside HeartbeatForegroundService.start() itself
    // rather than at each of its four call sites, so nothing (BootReceiver, a future command, a
    // future call site someone adds later) can accidentally restart the service against an
    // explicit admin decision to keep it off for this device. Trades away this device's most
    // reliable LOCK/UNLOCK delivery path and Wi-Fi/wallpaper self-healing for a fully silent
    // device — the two remaining paths (WorkManager periodic job, FCM push) stay active
    // regardless, so this is reduced reliability, not a full loss of remote control.
    fun setSyncServiceDisabled(disabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_SERVICE_DISABLED, disabled).apply()
    }

    fun isSyncServiceDisabled(): Boolean = prefs.getBoolean(KEY_SYNC_SERVICE_DISABLED, false)

    companion object {
        private const val KEY_DEVICE_TOKEN = "key_device_token"
        private const val KEY_DEVICE_ID = "key_device_id"
        private const val KEY_LOAN_ID = "key_loan_id"
        private const val KEY_HEARTBEAT_INTERVAL = "key_heartbeat_interval"
        private const val KEY_IS_ENROLLED = "key_is_enrolled"
        private const val KEY_TERMS_ACCEPTED = "key_terms_accepted"
        private const val KEY_NOTIFICATION_HIDE_AT = "key_notification_hide_at"
        private const val KEY_SYNC_SERVICE_DISABLED = "key_sync_service_disabled"
    }
}
