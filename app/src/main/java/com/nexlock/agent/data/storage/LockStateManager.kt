package com.nexlock.agent.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tracks whether the device is currently supposed to be in the kiosk-locked state, kept
 * separate from TokenManager so enrollment-credential lifecycle and lock-state lifecycle
 * don't get entangled. This is what BootReceiver and MainActivity check to decide whether
 * to relaunch KioskLockActivity, since a pinned lock-task foreground task does not survive
 * a reboot or a killed process on its own.
 */
class LockStateManager(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "nexlock_agent_lock_state_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("LockStateManager", "EncryptedSharedPreferences init failed, falling back to plain prefs", e)
        context.getSharedPreferences("nexlock_agent_lock_state_prefs_fallback", Context.MODE_PRIVATE)
    }

    fun setLocked(locked: Boolean) {
        prefs.edit()
            .putBoolean(KEY_IS_LOCKED, locked)
            .putLong(KEY_LOCKED_AT, if (locked) System.currentTimeMillis() else 0L)
            .apply()
    }

    fun isLocked(): Boolean = prefs.getBoolean(KEY_IS_LOCKED, false)

    fun lockedAt(): Long = prefs.getLong(KEY_LOCKED_AT, 0L)

    // Cached at lock time (see CommandDispatcher.executeLockCommand) so KioskLockActivity can
    // render the reason and contact details without a live network call every time it shows —
    // including on the boot-reassert path, and if the device is offline while locked.
    fun saveLockInfo(
        reason: String?,
        dealerName: String?,
        dealerPhone: String?,
        dealerEmail: String?,
        supportEmail: String?,
        supportPhone: String?
    ) {
        prefs.edit()
            .putString(KEY_LOCK_REASON, reason)
            .putString(KEY_DEALER_NAME, dealerName)
            .putString(KEY_DEALER_PHONE, dealerPhone)
            .putString(KEY_DEALER_EMAIL, dealerEmail)
            .putString(KEY_SUPPORT_EMAIL, supportEmail)
            .putString(KEY_SUPPORT_PHONE, supportPhone)
            .apply()
    }

    fun getLockReason(): String? = prefs.getString(KEY_LOCK_REASON, null)
    fun getDealerName(): String? = prefs.getString(KEY_DEALER_NAME, null)
    fun getDealerPhone(): String? = prefs.getString(KEY_DEALER_PHONE, null)
    fun getDealerEmail(): String? = prefs.getString(KEY_DEALER_EMAIL, null)
    fun getSupportEmail(): String? = prefs.getString(KEY_SUPPORT_EMAIL, null)
    fun getSupportPhone(): String? = prefs.getString(KEY_SUPPORT_PHONE, null)

    companion object {
        private const val KEY_IS_LOCKED = "key_is_locked"
        private const val KEY_LOCKED_AT = "key_locked_at"
        private const val KEY_LOCK_REASON = "key_lock_reason"
        private const val KEY_DEALER_NAME = "key_dealer_name"
        private const val KEY_DEALER_PHONE = "key_dealer_phone"
        private const val KEY_DEALER_EMAIL = "key_dealer_email"
        private const val KEY_SUPPORT_EMAIL = "key_support_email"
        private const val KEY_SUPPORT_PHONE = "key_support_phone"
    }
}
