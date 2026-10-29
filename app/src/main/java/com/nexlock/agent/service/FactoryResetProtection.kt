package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.app.admin.FactoryResetProtectionPolicy
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Software-side hard-reset blocking (setUninstallBlocked / DISALLOW_FACTORY_RESET) doesn't cover
 * the hardware recovery-mode wipe (volume-up + power) — that path runs before Android, and this
 * app, ever starts, so it can't be blocked at all. Factory Reset Protection is the only backstop
 * for that case, but by default it only recognizes whatever Google account(s) happened to already
 * be signed in before the reset — for a financed device, that's the customer's own personal
 * account, not NexLock's. setFactoryResetProtectionPolicy() (API 31+) overrides that default: it
 * explicitly names NexLock's own recovery account as the one FRP demands after a reset,
 * regardless of which account(s) were actually on the device beforehand.
 *
 * NOT currently called from anywhere (see DeviceRestrictionPolicy.applyBaselineRestrictions,
 * which still short-circuits before arming FRP at all) — the OLD inline implementation that
 * precedes this file passed a plain email address to setFactoryResetProtectionAccounts(), which
 * a real-device test proved doesn't work: sign-in to the recovery account succeeded (correct
 * credentials), but the FRP challenge was never satisfied. This file fixes that root cause (see
 * FRP_RECOVERY_ACCOUNT's own comment) but has not yet been confirmed end-to-end on real hardware
 * since being ported in — do not wire this into applyBaselineRestrictions until that's done.
 */
object FactoryResetProtection {
    private const val TAG = "NexLockDeviceAdmin"

    // Google's OWN reference DPC implementation (TestDPC, see FactoryResetProtectionPolicyFragment
    // .isValidAccountId()) validates this string as a 21-digit numeric Google account ID (the
    // account's Gaia ID) — not an email address. A prior real-device test using the plain email
    // string here got a real, reproducible failure: sign-in to it@nexusdig.in succeeded (correct
    // credentials), but the FRP challenge was never satisfied — consistent with the policy never
    // having contained anything Google's FRP backend could actually match against the signed-in
    // account. This is that account's real Gaia ID, extracted from the `sub` claim of a Google
    // OAuth 2.0 ID token obtained for it@nexusdig.in (matches Google's own documented guidance:
    // the OIDC `sub` claim is the account's stable, unique numeric identifier).
    private const val FRP_RECOVERY_ACCOUNT = "104780728457726357123" // it@nexusdig.in

    // TEMPORARY test-only safety net — once the org policy is active, only accounts in this
    // exact list can satisfy FRP after a reset (the normal multi-account fallback is explicitly
    // disabled per Android's own docs), so a second, personally-known-good account is included
    // here purely so a real-device test can't end in a stuck phone if the primary ID doesn't
    // work for some still-unverified reason. This must NOT ship to production as-is — a personal
    // account has no business being a permanent recovery path for real customer devices. Remove
    // this second entry once FRP testing confirms the primary ID works on its own.
    private const val TEST_SAFETY_NET_ACCOUNT = "113447419819059736980" // vivekg3216@gmail.com

    fun apply(context: Context, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "Factory Reset Protection policy needs API 31+ — skipping (device is API ${Build.VERSION.SDK_INT}).")
            return
        }
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            // No isDeviceOwnerApp() pre-check here deliberately — real-device testing showed the
            // framework doesn't finish committing device-owner state until shortly after
            // onEnabled() fires, so that check can race and return false even though this call
            // itself, moments later, succeeds fine. setFactoryResetProtectionPolicy() already
            // throws SecurityException on its own if we're genuinely not device owner yet, which
            // the catch below logs — a second, racy gate here only risked silently swallowing a
            // real success.
            val policy = FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(listOf(FRP_RECOVERY_ACCOUNT, TEST_SAFETY_NET_ACCOUNT))
                .setFactoryResetProtectionEnabled(true)
                .build()
            dpm.setFactoryResetProtectionPolicy(admin, policy)
            Log.i(TAG, "Factory Reset Protection recovery account set to $FRP_RECOVERY_ACCOUNT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set Factory Reset Protection policy", e)
        }
    }

    /** Passing null clears the org-designated policy entirely, reverting FRP to its default
     * "any account previously synced on the device" behavior. Needed for two cases: releasing a
     * paid-off device back to the customer (the org policy would otherwise keep exclusively
     * requiring NexLock's account even after the loan is done — a real bug from the customer's
     * side), and — the reason this exists right now — real-device testing found the org policy's
     * own documentation states it makes FRP exclusively require the listed accounts, disabling
     * the normal multi-account fallback entirely. That's a real risk during testing: if the org
     * policy account doesn't work (as already seen once on this hardware) and it also excludes
     * every other synced account, there's no fallback account left to unlock with at all. */
    fun clear(context: Context, admin: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm.setFactoryResetProtectionPolicy(admin, null)
            Log.i(TAG, "Factory Reset Protection org policy cleared — reverted to default multi-account FRP")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Factory Reset Protection policy", e)
        }
    }
}
