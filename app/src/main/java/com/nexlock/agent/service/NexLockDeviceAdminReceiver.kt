package com.nexlock.agent.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.nexlock.agent.provisioning.TermsAcceptanceActivity

/**
 * The DeviceAdminReceiver that becomes NexLock's Device Owner once a dealer's QR is scanned
 * during Setup Wizard on a factory-reset device. onProfileProvisioningComplete is the real
 * entry point — everything before that (onEnabled) fires for the plain-Device-Admin case too,
 * which this app doesn't otherwise use.
 */
class NexLockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Device Owner apps cannot normally be deactivated via the standard "deactivate admin"
        // UI (setUninstallBlocked/DISALLOW_FACTORY_RESET see to that) — this only logs in case
        // it's reached via an adb/debug path on a test device.
        Log.w(TAG, "Device Admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Device Owner provisioning complete — showing consent screen before handshake")

        val extras = provisioningExtras(intent)
        val enrollmentToken = extras?.getString("enrollmentToken")
        val otp = extras?.getString("otp")
        val serverUrl = extras?.getString("serverUrl")

        // The handshake (and everything after it — restrictions, heartbeat) only fires once
        // the customer taps "I Agree" here; see TermsAcceptanceActivity.onAccept. Setup Wizard
        // does not automatically return to this app's launcher Activity after this callback,
        // so this doubles as the "setting up your device" hand-off screen too.
        TermsAcceptanceActivity.launchForAutoFlow(context, enrollmentToken, otp, serverUrl)
    }

    /**
     * Fires when this app becomes Device Owner via DevicePolicyManager.transferOwnership() — the
     * Redmi/Poco bootstrap path, where Google's TestDPC is the temporary Device Owner and hands
     * off to us. onProfileProvisioningComplete is NOT called in this case, so this is the parallel
     * entry point. The incoming bundle is TestDPC's fixed placeholder (it can't forward our
     * enrollmentToken/otp), so instead of reading it we claim those from the backend by IMEI off
     * the receiver thread — see BootstrapClaimWorker.
     */
    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        super.onTransferOwnershipComplete(context, bundle)
        Log.i(TAG, "Device Owner received via ownership transfer — starting bootstrap enrollment claim")
        BootstrapClaimWorker.enqueue(context)
    }

    @Suppress("DEPRECATION")
    private fun provisioningExtras(intent: Intent): PersistableBundle? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                PersistableBundle::class.java
            )
        } else {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
    }

    companion object {
        private const val TAG = "NexLockDeviceAdmin"
    }
}
