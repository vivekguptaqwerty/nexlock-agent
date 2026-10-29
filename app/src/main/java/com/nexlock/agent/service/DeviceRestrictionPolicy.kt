package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.provider.Settings
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
        UserManager.DISALLOW_SAFE_BOOT
    )

    // Previously part of TARGET_RESTRICTIONS. Removed because it also blocks OEM app cloning
    // (ColorOS/Oppo "App Cloner" showed "This function has been disabled"), which customers need.
    // Still cleared explicitly below so devices enrolled by an older agent get it lifted too.
    private val LEGACY_RESTRICTIONS_TO_CLEAR = listOf(UserManager.DISALLOW_ADD_USER)

    /** One measured step in applyBaselineRestrictions()'s run — see its own doc comment and
     * submitDiagnostics in DeviceRepository.kt for why this exists. `ok` only reflects whether
     * the wrapped call threw, not whether the underlying DevicePolicyManager change actually
     * stuck (most of these steps already self-catch and log internally, same as before this was
     * added) — this is a timing/sequence trail first, an error trail second. */
    data class DiagStep(val step: String, val ok: Boolean, val durationMs: Long, val error: String? = null)

    private inline fun timed(name: String, log: MutableList<DiagStep>, block: () -> Unit) {
        val start = System.currentTimeMillis()
        try {
            block()
            log.add(DiagStep(name, true, System.currentTimeMillis() - start))
        } catch (e: Exception) {
            log.add(DiagStep(name, false, System.currentTimeMillis() - start, e.message))
        }
    }

    /**
     * Returns a step-by-step diagnostic trail of this run — built specifically to chase a
     * screen-blink-right-after-enrollment issue reported live on Samsung and Oppo devices,
     * never reproduced on a test device. Callers are expected to submit this trail via
     * DeviceRepository#submitDiagnostics (best-effort, never blocking on it) rather than this
     * function doing any networking itself.
     */
    fun applyBaselineRestrictions(context: Context): List<DiagStep> {
        val log = mutableListOf<DiagStep>()
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm == null) {
            Log.e(TAG, "DevicePolicyManager unavailable — cannot apply any restriction")
            log.add(DiagStep("getDevicePolicyManager", false, 0, "service unavailable"))
            return log
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
            log.add(DiagStep("isDeviceOwnerApp", false, 0, "not Device Owner"))
            return log
        }

        timed("applyTargetRestrictions", log) {
            for (restriction in TARGET_RESTRICTIONS) {
                applyRestriction(dpm, admin, restriction)
            }
        }
        timed("clearLegacyRestrictions", log) {
            for (restriction in LEGACY_RESTRICTIONS_TO_CLEAR) {
                try {
                    dpm.clearUserRestriction(admin, restriction)
                    Log.i(TAG, "clearUserRestriction($restriction) completed")
                } catch (e: Exception) {
                    Log.e(TAG, "clearUserRestriction($restriction) THREW", e)
                }
            }
        }

        timed("setUninstallBlocked", log) {
            dpm.setUninstallBlocked(admin, context.packageName, true)
        }

        timed("refreshLockTaskPackages", log) {
            refreshLockTaskPackages(context, dpm, admin)
        }

        timed("setLockTaskFeatures", log) {
            // Minimal starting baseline (Open Decision #4 in the Phase 3 plan) — only
            // widened if real-hardware testing of the emergency-dialer path needs it.
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }

        timed("applyUserControlLock", log) { applyUserControlLock(context, dpm, admin) }
        timed("grantNotificationPermission", log) { grantNotificationPermission(context, dpm, admin) }
        timed("grantLocationAndPhonePermissions", log) { grantLocationAndPhonePermissions(context, dpm, admin) }
        timed("grantBluetoothPermission", log) { grantBluetoothPermission(context, dpm, admin) }
        timed("grantSystemAlertWindowPermission", log) { grantSystemAlertWindowPermission(context, dpm, admin) }
        timed("activateFactoryResetProtection", log) { activateFactoryResetProtection(context, admin) }
        timed("disableAdbAndDeveloperOptions", log) { disableAdbAndDeveloperOptions(dpm, admin) }
        timed("writeDevelopmentSettingsFlagDirectly", log) { writeDevelopmentSettingsFlagDirectly(context) }
        timed("verifyAppliedRestrictions", log) { verifyAppliedRestrictions(context, dpm, admin) }

        return log
    }

    /**
     * DISALLOW_DEBUGGING_FEATURES (in TARGET_RESTRICTIONS above) only blocks the user from
     * toggling USB/wireless debugging back ON via Settings — Android does NOT retroactively turn
     * it off if it was already on, which it always is at this exact point: wireless ADB is how
     * Device Owner itself just got set during activation (see AdbActivationModule in
     * nexlock-dealer-rn). Net effect on real devices: the Developer Options entry disappears from
     * Settings, but debugging stays silently active in the background forever, with no way for
     * the customer or dealer to turn it off through the UI — and several banking apps explicitly
     * refuse to run while Settings.Global.ADB_ENABLED is nonzero, locking customers out of apps
     * they need. setGlobalSetting() is how a Device Owner forces these off directly instead of
     * just blocking future changes; called again on every boot (applyBaselineRestrictions runs
     * from BootReceiver too) in case something flipped it back on in the meantime.
     *
     * Safe to run at this point in the flow specifically: the dealer's wireless ADB session is
     * only ever used to install the agent and run `dpm set-device-owner`, which already completed
     * before this runs — this is called from the enrollment handshake (after the customer accepts
     * Terms) or boot, both well after the dealer's phone has disconnected.
     */
    private fun disableAdbAndDeveloperOptions(dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "0")
            Log.i(TAG, "Forced Settings.Global.ADB_ENABLED to 0")
        } catch (e: Exception) {
            Log.e(TAG, "setGlobalSetting(ADB_ENABLED) failed", e)
        }
        // DEVELOPMENT_SETTINGS_ENABLED is NOT on the small allowlist of global settings keys
        // DevicePolicyManager.setGlobalSetting() accepts from a Device Owner (ADB_ENABLED above
        // is; this key isn't) — this call reliably throws SecurityException on real devices and
        // was silently swallowed here before, which is exactly why banking apps that read this
        // raw setting directly (bypassing the Settings UI, which DISALLOW_DEBUGGING_FEATURES
        // above does successfully hide) kept flagging "Developer options enabled" even after
        // enrollment. Left in as a harmless attempt in case a future/OEM build ever does permit
        // it, but writeDevelopmentSettingsFlagDirectly() below is the path that actually works.
        try {
            dpm.setGlobalSetting(admin, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "0")
            Log.i(TAG, "Forced Settings.Global.DEVELOPMENT_SETTINGS_ENABLED to 0 via DPM")
        } catch (e: Exception) {
            Log.e(TAG, "setGlobalSetting(DEVELOPMENT_SETTINGS_ENABLED) failed (expected — not DPM-allowlisted)", e)
        }
    }

    /**
     * The actual fix for DEVELOPMENT_SETTINGS_ENABLED: writing Settings.Global directly requires
     * WRITE_SECURE_SETTINGS, a signature|privileged permission this app can't self-grant via
     * DevicePolicyManager.setPermissionGrantState (that API only reaches ordinary runtime
     * permissions). It's granted once, out-of-band, by the dealer's wireless-ADB activation tool
     * (`pm grant com.nexlock.agent android.permission.WRITE_SECURE_SETTINGS`, run alongside
     * `dpm set-device-owner` — see AdbActivationModule.activate() in nexlock-dealer-rn) while that
     * adb session is still live, since there's no other avenue to obtain it afterward. A device
     * enrolled before that activation-tool change shipped won't have the permission yet; this
     * silently no-ops (logged, not crashing) until the dealer re-runs activation against that
     * phone to pick up the grant — `pm grant` doesn't require the app to not already be Device
     * Owner, so re-running activation is a safe, idempotent repair for already-enrolled devices.
     */
    private fun writeDevelopmentSettingsFlagDirectly(context: Context) {
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted — cannot clear DEVELOPMENT_SETTINGS_ENABLED directly. " +
                "This device needs activation re-run to pick up the pm grant step.")
            return
        }
        try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)
            Log.i(TAG, "Forced Settings.Global.DEVELOPMENT_SETTINGS_ENABLED to 0 directly (WRITE_SECURE_SETTINGS)")
        } catch (e: Exception) {
            Log.e(TAG, "Direct Settings.Global.putInt(DEVELOPMENT_SETTINGS_ENABLED) failed", e)
        }
    }

    /**
     * DISALLOW_FACTORY_RESET (above) only blocks the in-Settings "Erase all data" UI — it has no
     * effect on a recovery-mode wipe triggered by the hardware button combo (Volume Up + Power on
     * most OEMs), which happens in the bootloader before Android, and therefore before this app,
     * is even running. Nothing running as an Android app can intercept that. Factory Reset
     * Protection is the only backstop for that case — see FactoryResetProtection.kt for the real
     * implementation and why the account identifier format matters.
     *
     * RE-ENABLED as of 2026-10-29. The OLD recovery-account list here was plain email addresses,
     * but setFactoryResetProtectionAccounts() actually expects each account's numeric Gaia ID —
     * the email-string policy never contained anything Google's FRP backend could match, which
     * explained the earlier stuck-device incidents (correct password, rejected at the FRP
     * screen). FactoryResetProtection.kt holds the corrected, Gaia-ID-based implementation,
     * confirmed end-to-end on a real device. Already-enrolled devices still carrying the old
     * broken policy are not affected by this — see the DISARM_FRP remote command.
     */
    private fun activateFactoryResetProtection(context: Context, admin: ComponentName) {
        FactoryResetProtection.apply(context, admin)
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
        clearFactoryResetProtection(dpm, admin)

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
     * Must run before clearDeviceOwnerApp() below, same reasoning as clearUserControlLock — once
     * Device Owner is cleared this app has no privilege left to touch FRP at all, and a fully
     * paid-off customer's own phone must not stay locked behind a policy nobody can lift anymore.
     */
    // Not private — CommandDispatcher's DISARM_FRP case calls this directly, as a standalone
    // remote remediation for devices already carrying the old broken email-based FRP policy,
    // separate from the full RELEASE_DEVICE flow that also calls it.
    fun clearFactoryResetProtection(dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            dpm.setFactoryResetProtectionPolicy(admin, null)
            Log.i(TAG, "cleared setFactoryResetProtectionPolicy")
        } catch (e: Exception) {
            Log.e(TAG, "clearing setFactoryResetProtectionPolicy failed", e)
        }
    }

    /**
     * POST_NOTIFICATIONS is a runtime-dangerous permission on API 33+ — without it, the payment
     * reminder push (FcmService.showReminderNotification) would silently never show, and
     * HeartbeatForegroundService's required (though minimized) notification wouldn't show
     * either. Device Owner apps can self-grant this instead of showing a runtime permission
     * prompt during setup.
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

    /**
     * Location + phone-state permissions for the location-tracking and SIM-detection heartbeat
     * fields — same self-grant mechanism as POST_NOTIFICATIONS, no runtime prompt shown to the
     * customer. ACCESS_BACKGROUND_LOCATION must be requested only after the foreground location
     * permissions are already granted (an Android platform requirement since API 30), so it's
     * granted in its own call after the other two, not in the same batch.
     */
    private fun grantLocationAndPhonePermissions(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        for (permission in permissions) {
            try {
                dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                Log.i(TAG, "Granted $permission via Device Owner self-grant")
            } catch (e: Exception) {
                Log.e(TAG, "setPermissionGrantState($permission) failed", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.i(TAG, "Granted ACCESS_BACKGROUND_LOCATION via Device Owner self-grant")
            } catch (e: Exception) {
                Log.e(TAG, "setPermissionGrantState(ACCESS_BACKGROUND_LOCATION) failed", e)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    android.Manifest.permission.READ_PHONE_NUMBERS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.i(TAG, "Granted READ_PHONE_NUMBERS via Device Owner self-grant")
            } catch (e: Exception) {
                Log.e(TAG, "setPermissionGrantState(READ_PHONE_NUMBERS) failed", e)
            }
        }
    }

    /**
     * BLUETOOTH_CONNECT is a runtime-dangerous permission on API 31+ — without it,
     * BluetoothAdapter.disable()/enable() would silently no-op for BLUETOOTH_BLOCK/UNBLOCK the
     * same way an ungranted POST_NOTIFICATIONS would silently no-op the reminder push. Below API
     * 31 the legacy BLUETOOTH/BLUETOOTH_ADMIN manifest permissions (declared maxSdkVersion="30")
     * already cover this with no runtime grant needed at all.
     */
    private fun grantBluetoothPermission(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "Granted BLUETOOTH_CONNECT via Device Owner self-grant")
        } catch (e: Exception) {
            Log.e(TAG, "setPermissionGrantState(BLUETOOTH_CONNECT) failed", e)
        }
    }

    /**
     * SYSTEM_ALERT_WINDOW ("draw over other apps") is a special permission, not a standard
     * runtime one — unlike BLUETOOTH_CONNECT/POST_NOTIFICATIONS above, setPermissionGrantState()
     * is documented for runtime permissions specifically, and there's no confirmed guarantee it
     * actually applies to this one on every Android version. Attempted anyway (best-effort, same
     * defensive pattern as everything else here) since it's free if it doesn't work — see
     * WallpaperOverlayService's doc comment for what happens if this silently doesn't take:
     * the wallpaper half of that feature still works on its own, only the cross-app overlay
     * banner is affected.
     */
    private fun grantSystemAlertWindowPermission(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )
            Log.i(TAG, "Attempted SYSTEM_ALERT_WINDOW grant via Device Owner self-grant")
        } catch (e: Exception) {
            Log.e(TAG, "setPermissionGrantState(SYSTEM_ALERT_WINDOW) failed", e)
        }
    }

    /**
     * The lock-task allowlist controls which packages the OS permits while the kiosk screen is
     * pinned (startLockTask) — everything not in this list is unreachable, including Settings and
     * Home. Widened beyond just this app's own package to include the device's current default
     * dialer, so KioskLockActivity's "Call Dealer" button can actually launch it while pinned;
     * without this the dialer Intent would simply be blocked by the OS like any other app.
     *
     * Called both from applyBaselineRestrictions() (enrollment) and from
     * CommandDispatcher.executeLockCommand() (every LOCK) — the latter matters because this is a
     * plain replace-the-whole-list call, so an already-enrolled device only picks up a newly
     * widened allowlist once it locks again, not automatically from this code shipping.
     *
     * Best-effort: if the default dialer can't be determined (no dialer role holder, or the API
     * throws), the allowlist falls back to just this app's own package — the customer loses the
     * in-lockscreen call button but the lock itself is unaffected either way.
     */
    fun refreshLockTaskPackages(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val dialerPackage = try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
            telecomManager?.defaultDialerPackage
        } catch (e: Exception) {
            Log.e(TAG, "getDefaultDialerPackage failed", e)
            null
        }

        val allowedPackages = if (dialerPackage.isNullOrBlank()) {
            arrayOf(context.packageName)
        } else {
            arrayOf(context.packageName, dialerPackage)
        }

        try {
            dpm.setLockTaskPackages(admin, allowedPackages)
            Log.i(TAG, "setLockTaskPackages applied: ${allowedPackages.toList()}")
        } catch (e: Exception) {
            Log.e(TAG, "setLockTaskPackages failed", e)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val frpPolicy = try {
                dpm.getFactoryResetProtectionPolicy(admin)
            } catch (e: Exception) {
                Log.e(TAG, "getFactoryResetProtectionPolicy() readback threw", e)
                null
            }
            if (frpPolicy != null) {
                Log.i(TAG, "VERIFIED FactoryResetProtectionPolicy: enabled=${frpPolicy.isFactoryResetProtectionEnabled}, accounts=${frpPolicy.factoryResetProtectionAccounts}")
            } else {
                Log.e(TAG, "NOT ACTIVE: getFactoryResetProtectionPolicy returned null — FRP is not armed on this device")
            }
        }

        val hasWriteSecureSettings = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val devSettingsValue = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)
        } catch (e: Exception) {
            -2
        }
        Log.i(TAG, "VERIFIED WRITE_SECURE_SETTINGS granted=$hasWriteSecureSettings, " +
            "DEVELOPMENT_SETTINGS_ENABLED readback=$devSettingsValue (0 = off, 1 = still on, " +
            "-1/-2 = read failed). If granted=false, this device needs activation re-run to pick " +
            "up the pm grant step added to AdbActivationModule.")
    }

    /**
     * On-screen equivalent of the logcat verification above, for devices where
     * DISALLOW_DEBUGGING_FEATURES (applied as part of this same baseline) blocks adb access —
     * TEST BUILD diagnostic only, not meant to stay in a production-facing UI long-term.
     */
    fun getFrpDiagnosticText(context: Context): String {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return "FRP: DevicePolicyManager unavailable"
        val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "FRP: unavailable below API 30"
        return try {
            val policy = dpm.getFactoryResetProtectionPolicy(admin)
            if (policy != null) {
                "FRP: ARMED, enabled=${policy.isFactoryResetProtectionEnabled}, accounts=${policy.factoryResetProtectionAccounts}"
            } else {
                "FRP: NOT ARMED (policy is null)"
            }
        } catch (e: Exception) {
            "FRP: readback threw — ${e.message}"
        }
    }
}
