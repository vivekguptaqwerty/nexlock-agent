package com.nexlock.agent.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Wi-Fi blocking, independent of LOCK/UNLOCK — a dealer can block Wi-Fi on a device that's
 * still unlocked (see CommandDispatcher's WIFI_BLOCK/WIFI_UNBLOCK handlers). Mobile data is
 * deliberately untouched; this is Wi-Fi-only, as asked.
 *
 * There is no Android restriction that stops a customer from flipping Wi-Fi back on via Quick
 * Settings once it's off — Device Owner apps are just exempted from the API 29+ restriction on
 * calling setWifiEnabled() at all, nothing more. So this is enforced, not just set-once: the
 * persisted flag here is what WifiStateEnforcer (registered from HeartbeatForegroundService)
 * and BootReceiver check to immediately re-disable Wi-Fi if it comes back on while blocked.
 */
object WifiBlockManager {

    private const val TAG = "WifiBlockManager"
    private const val PREFS_NAME = "nexlock_agent_wifi_block_prefs"
    private const val KEY_BLOCKED = "key_wifi_blocked"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, false)

    fun setBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    /**
     * Actually turns the radio off. Device Owner apps can still call this on API 29+ even
     * though Android deprecated setWifiEnabled() for regular apps — best-effort regardless,
     * since a throw here shouldn't crash whatever caller (command execution, boot, the state
     * receiver) is invoking it.
     */
    fun disableWifi(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled = false
            Log.i(TAG, "Wi-Fi disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Wi-Fi", e)
        }
    }

    fun enableWifi(context: Context) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled = true
            Log.i(TAG, "Wi-Fi re-enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable Wi-Fi", e)
        }
    }

    /** Re-applies the current blocked state — used on boot and by the enforcement receiver. */
    fun enforce(context: Context) {
        if (isBlocked(context)) {
            disableWifi(context)
        }
    }
}
