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

    companion object {
        private const val KEY_IS_LOCKED = "key_is_locked"
        private const val KEY_LOCKED_AT = "key_locked_at"
    }
}
