package com.nexlock.agent.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Bluetooth blocking, independent of LOCK/UNLOCK and of WifiBlockManager — same pattern, its own
 * toggle (see CommandDispatcher's BLUETOOTH_BLOCK/BLUETOOTH_UNBLOCK handlers).
 *
 * Unlike Wi-Fi, this doesn't need a BroadcastReceiver + BootReceiver re-enforcement loop to stay
 * effective: DISALLOW_BLUETOOTH is a UserManager restriction that blocks the customer from
 * turning Bluetooth back on at all (via Quick Settings or Settings), rather than NexLock racing
 * to re-disable it after the fact the way WifiStateEnforcer does. BootReceiver still re-applies
 * it defensively on every boot, same as the other baseline restrictions, in case it failed to
 * stick the first time.
 *
 * BluetoothAdapter.disable()/enable() were deprecated for regular apps on API 33+, but Device
 * Owner apps are explicitly exempted and retain full access — see
 * DeviceRestrictionPolicy.grantBluetoothPermission() for the self-granted BLUETOOTH_CONNECT this
 * requires on API 31+.
 */
object BluetoothBlockManager {

    private const val TAG = "BluetoothBlockManager"
    private const val PREFS_NAME = "nexlock_agent_bluetooth_block_prefs"
    private const val KEY_BLOCKED = "key_bluetooth_blocked"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlocked(context: Context): Boolean = prefs(context).getBoolean(KEY_BLOCKED, false)

    fun setBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCKED, blocked).apply()
    }

    private fun adapter(context: Context): BluetoothAdapter? {
        val bluetoothManager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter
    }

    /**
     * DISALLOW_BLUETOOTH is what actually keeps this from being manually reversed — disable()
     * below only flips the radio off once, the same as WifiBlockManager's setWifiEnabled(false).
     */
    private fun applyDisallowRestriction(context: Context, disallow: Boolean) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            val admin = android.content.ComponentName(context, NexLockDeviceAdminReceiver::class.java)
            if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return
            if (disallow) {
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_BLUETOOTH)
            } else {
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_BLUETOOTH)
            }
            Log.i(TAG, "DISALLOW_BLUETOOTH set to $disallow")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle DISALLOW_BLUETOOTH", e)
        }
    }

    fun disableBluetooth(context: Context) {
        applyDisallowRestriction(context, true)
        try {
            @Suppress("DEPRECATION")
            adapter(context)?.disable()
            Log.i(TAG, "Bluetooth disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable Bluetooth", e)
        }
    }

    fun enableBluetooth(context: Context) {
        applyDisallowRestriction(context, false)
        try {
            @Suppress("DEPRECATION")
            adapter(context)?.enable()
            Log.i(TAG, "Bluetooth re-enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable Bluetooth", e)
        }
    }

    /** Re-applies the current blocked state — used on boot, same as WifiBlockManager.enforce(). */
    fun enforce(context: Context) {
        if (isBlocked(context)) {
            disableBluetooth(context)
        }
    }
}
