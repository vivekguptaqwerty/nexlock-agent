package com.nexlock.agent.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.nexlock.agent.provisioning.ProvisioningCompleteActivity

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
        Log.i(TAG, "Device Owner provisioning complete — starting automatic handshake")

        val extras = provisioningExtras(intent)
        val enrollmentToken = extras?.getString("enrollmentToken")
        val otp = extras?.getString("otp")
        val serverUrl = extras?.getString("serverUrl")

        ProvisioningHandshakeWorker.enqueue(
            context = context,
            enrollmentToken = enrollmentToken,
            otp = otp,
            serverUrl = serverUrl
        )

        // Setup Wizard does not automatically return to this app's launcher Activity after
        // this callback — start a lightweight "setting up your device" screen ourselves so the
        // dealer/customer sees progress instead of being dropped on the home screen mid-setup.
        val activityIntent = Intent(context, ProvisioningCompleteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(activityIntent)
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
