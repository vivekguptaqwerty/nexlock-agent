package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * Applies the always-on restriction set (Tier A) that should hold for the life of the loan,
 * independent of whether the device is currently LOCK'd or UNLOCK'd. Called once, idempotently,
 * right after a successful enrollment handshake — not tied to the LOCK/UNLOCK command, which
 * only toggles the payment-status-dependent tier (see CommandDispatcher).
 *
 * Every call here is a no-op-safe individual try/catch: if the app isn't actually Device Owner
 * yet (e.g. this ran before provisioning completed, or on a non-DO test build), each restriction
 * simply fails to apply and is logged rather than crashing the enrollment flow outright.
 */
object DeviceRestrictionPolicy {

    private const val TAG = "DeviceRestrictionPolicy"

    private val TARGET_RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_SAFE_BOOT
    )

    fun applyBaselineRestrictions(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable — cannot apply any restriction")
            return
        }
        val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)

        val isDeviceOwner = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "isDeviceOwnerApp() check itself threw — treating as not Device Owner", e)
            false
        }
        if (!isDeviceOwner) {
            Log.e(TAG, "Not Device Owner — skipping baseline restriction application entirely. " +
                "This is the exact condition that would let factory-reset/uninstall/etc. through unblocked.")
            return
        }

        for (restriction in TARGET_RESTRICTIONS) {
            applyRestriction(dpm, admin, restriction)
        }

        try {
            dpm.setUninstallBlocked(admin, context.packageName, true)
        } catch (e: Exception) {
            Log.e(TAG, "setUninstallBlocked failed", e)
        }

        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
        } catch (e: Exception) {
            Log.e(TAG, "setLockTaskPackages failed", e)
        }

        try {
            // Minimal starting baseline (Open Decision #4 in the Phase 3 plan) — only
            // widened if real-hardware testing of the emergency-dialer path needs it.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        } catch (e: Exception) {
            Log.e(TAG, "setLockTaskFeatures failed", e)
        }

        applyUserControlLock(context, dpm, admin)
        grantNotificationPermission(context, dpm, admin)

        verifyAppliedRestrictions(context, dpm, admin)
    }

    /**
     * The other half of the loan lifecycle: fully de-provisions the device when the EMI is
     * paid off, not just lifting the payment-status-dependent lock (that's UNLOCK's job — see
     * CommandDispatcher). This must leave the device completely indistinguishable from a
     * never-managed phone: restrictions cleared, uninstall allowed, and Device Owner status
     * itself removed via clearDeviceOwnerApp() so there's no lingering "this device is managed
     * by your organization" messaging and Settings > Reset options works normally again.
     *
     * clearDeviceOwnerApp() is the point of no return — once called, this app loses all
     * Device-Owner-privileged DPM access for good (matching real device release; there's no
     * path back to Device Owner without a fresh factory reset and re-provisioning), so it's
     * called last, after every other cleanup step that still needs Device Owner privilege.
     * It's flagged deprecated in the SDK with no replacement API — still the only public way
     * to do this as of API 34, so the deprecation is suppressed deliberately, not overlooked.
     */
    @Suppress("DEPRECATION")
    fun releaseDevice(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable — cannot release device")
            return false
        }
        val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)

        val isDeviceOwner = try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "isDeviceOwnerApp() check threw during release", e)
            false
        }
        if (!isDeviceOwner) {
            Log.w(TAG, "Not Device Owner — nothing to release (already a normal, unmanaged app)")
            return true
        }

        for (restriction in TARGET_RESTRICTIONS) {
            try {
                dpm.clearUserRestriction(admin, restriction)
                Log.i(TAG, "clearUserRestriction($restriction) completed")
            } catch (e: Exception) {
                Log.e(TAG, "clearUserRestriction($restriction) THREW", e)
            }
        }

        try {
            dpm.setUninstallBlocked(admin, context.packageName, false)
        } catch (e: Exception) {
            Log.e(TAG, "setUninstallBlocked(false) failed", e)
        }

        clearUserControlLock(dpm, admin)

        return try {
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "clearDeviceOwnerApp succeeded — device is fully released")
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearDeviceOwnerApp FAILED — device remains under Device Owner management", e)
            false
        }
    }

    /**
     * Prevents the user from force-stopping this app or clearing its data/battery-optimization
     * exemption from Settings — API 30+ only (setUserControlDisabledPackages was added in
     * Android 11). This closes the gap where a customer re-enables the OS's own battery
     * restriction on the Agent after enrollment, silently breaking background command delivery
     * (observed on real hardware before this existed). It does NOT reach OEM-proprietary
     * restrictions layered on top of stock Android (ColorOS "Auto Launch", MIUI "Autostart",
     * etc.) — no public Android API grants control over those, Device Owner or not.
     */
    private fun applyUserControlLock(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "setUserControlDisabledPackages unavailable below API 30 (this device: ${Build.VERSION.SDK_INT}) — skipping")
            return
        }
        try {
            dpm.setUserControlDisabledPackages(admin, listOf(context.packageName))
            val active = dpm.getUserControlDisabledPackages(admin)
            Log.i(TAG, "VERIFIED setUserControlDisabledPackages: $active")
        } catch (e: Exception) {
            Log.e(TAG, "setUserControlDisabledPackages failed", e)
        }
    }

    private fun clearUserControlLock(dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            dpm.setUserControlDisabledPackages(admin, emptyList())
            Log.i(TAG, "cleared setUserControlDisabledPackages")
        } catch (e: Exception) {
            Log.e(TAG, "clearing setUserControlDisabledPackages failed", e)
        }
    }

    /**
     * POST_NOTIFICATIONS is a runtime-dangerous permission on API 33+ — without it,
     * HeartbeatForegroundService's persistent notification silently never shows (the service
     * itself still runs, but invisibly, defeating the point of a *visible* "this app matters"
     * signal to the OS's process management). Device Owner apps can self-grant this instead of
     * showing a runtime permission prompt during setup.
     */
    private fun grantNotificationPermission(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "Granted POST_NOTIFICATIONS via Device Owner self-grant")
        } catch (e: Exception) {
            Log.e(TAG, "setPermissionGrantState(POST_NOTIFICATIONS) failed", e)
        }
    }

    private fun applyRestriction(dpm: DevicePolicyManager, admin: ComponentName, restriction: String) {
        try {
            dpm.addUserRestriction(admin, restriction)
            Log.i(TAG, "addUserRestriction($restriction) call completed without throwing")
        } catch (e: Exception) {
            Log.e(TAG, "addUserRestriction($restriction) THREW — this restriction is NOT active", e)
        }
    }

    /**
     * Reads restriction state back from the OS after applying it, rather than trusting that
     * addUserRestriction() not throwing means it actually stuck. This is what should have
     * existed from the start: a call not throwing is not the same evidence as the OS actually
     * enforcing it, and the two silently diverging is exactly the kind of gap a factory-reset
     * test on real hardware is supposed to catch.
     */
    private fun verifyAppliedRestrictions(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val active = try {
            dpm.getUserRestrictions(admin)
        } catch (e: Exception) {
            Log.e(TAG, "getUserRestrictions() readback itself threw — cannot verify anything applied", e)
            return
        }

        for (restriction in TARGET_RESTRICTIONS) {
            val isActive = active.getBoolean(restriction, false)
            if (isActive) {
                Log.i(TAG, "VERIFIED active: $restriction")
            } else {
                Log.e(TAG, "NOT ACTIVE despite no exception during apply: $restriction — " +
                    "OS did not persist this restriction")
            }
        }

        val uninstallBlocked = try {
            dpm.isUninstallBlocked(admin, context.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "isUninstallBlocked() readback threw", e)
            null
        }
        Log.i(TAG, "VERIFIED setUninstallBlocked state: $uninstallBlocked")

        val lockTaskPackages = try {
            dpm.getLockTaskPackages(admin).toList()
        } catch (e: Exception) {
            Log.e(TAG, "getLockTaskPackages() readback threw", e)
            emptyList()
        }
        Log.i(TAG, "VERIFIED lock task allowlist: $lockTaskPackages")
    }
}
