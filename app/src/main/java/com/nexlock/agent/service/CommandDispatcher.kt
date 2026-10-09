package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.LockStateManager
import com.nexlock.agent.data.storage.TokenManager
import com.nexlock.agent.kiosk.KioskLockActivity

class CommandDispatcher(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    private val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)
    private val lockStateManager = LockStateManager(context)

    data class ExecutionResult(
        val status: String, // SUCCESS, FAILED, NOT_SUPPORTED
        val error: String? = null
    )

    suspend fun executeCommand(commandType: String): ExecutionResult {
        return when (commandType.uppercase()) {
            "LOCK" -> executeLockCommand()
            "UNLOCK" -> executeUnlockCommand()
            "RELEASE_DEVICE" -> executeReleaseDeviceCommand()
            "WIFI_BLOCK" -> executeWifiBlockCommand()
            "WIFI_UNBLOCK" -> executeWifiUnblockCommand()
            "BLUETOOTH_BLOCK" -> executeBluetoothBlockCommand()
            "BLUETOOTH_UNBLOCK" -> executeBluetoothUnblockCommand()
            "WALLPAPER_OVERLAY" -> executeWallpaperOverlayCommand()
            "WALLPAPER_OVERLAY_CLEAR" -> executeWallpaperOverlayClearCommand()
            "ALARM" -> executeAlarmCommand()
            "ALARM_STOP" -> executeAlarmStopCommand()
            else -> ExecutionResult(status = "NOT_SUPPORTED", error = "Unknown command type: $commandType")
        }
    }

    // setWifiEnabled() silently no-ops (returns false, doesn't throw) for a non-Device-Owner
    // install on API 29+ — checked explicitly here rather than trusting the call succeeded, same
    // honesty principle executeLockCommand already follows.
    private fun executeWifiBlockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "FAILED",
                error = "This agent is not Device Owner on this device, so the OS refused to toggle Wi-Fi."
            )
        }
        WifiBlockManager.setBlocked(context, true)
        WifiBlockManager.disableWifi(context)
        return ExecutionResult(status = "SUCCESS")
    }

    private fun executeWifiUnblockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "This agent is not Device Owner on this device, so there is nothing to unblock."
            )
        }
        WifiBlockManager.setBlocked(context, false)
        WifiBlockManager.enableWifi(context)
        return ExecutionResult(status = "SUCCESS")
    }

    // Same honesty pattern as WIFI_BLOCK — BluetoothAdapter.disable() is a no-op for a
    // non-Device-Owner install on API 33+, so this checks explicitly rather than trusting it.
    private fun executeBluetoothBlockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "FAILED",
                error = "This agent is not Device Owner on this device, so the OS refused to toggle Bluetooth."
            )
        }
        BluetoothBlockManager.setBlocked(context, true)
        BluetoothBlockManager.disableBluetooth(context)
        return ExecutionResult(status = "SUCCESS")
    }

    private fun executeBluetoothUnblockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "This agent is not Device Owner on this device, so there is nothing to unblock."
            )
        }
        BluetoothBlockManager.setBlocked(context, false)
        BluetoothBlockManager.enableBluetooth(context)
        return ExecutionResult(status = "SUCCESS")
    }

    // Reuses fetchAndCacheLockInfo() (below) for the dealer phone number shown on the overlay —
    // that method isn't actually lock-specific despite its name, it's just "fetch and cache
    // dealer contact details," which this needs exactly as much as LOCK does.
    private suspend fun executeWallpaperOverlayCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "FAILED",
                error = "This agent is not Device Owner on this device, so the OS refused to set the wallpaper."
            )
        }
        fetchAndCacheLockInfo()
        WallpaperOverlayManager.enable(context)
        return ExecutionResult(status = "SUCCESS")
    }

    private fun executeWallpaperOverlayClearCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "This agent is not Device Owner on this device, so there is nothing to clear."
            )
        }
        WallpaperOverlayManager.disable(context)
        return ExecutionResult(status = "SUCCESS")
    }

    // Same honesty pattern as every other command — in practice any command reaching this
    // dispatcher at all already implies a Device-Owner-enrolled phone (CommandSync only polls
    // once a device token exists, which requires enrollment), but checking explicitly keeps a
    // non-provisioned test build behaving the same honest way across every command type.
    private fun executeAlarmCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(status = "NOT_SUPPORTED", error = "DevicePolicyManager unavailable on this device.")
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(status = "FAILED", error = "This agent is not Device Owner on this device.")
        }
        AlarmPlaybackService.start(context)
        return ExecutionResult(status = "SUCCESS")
    }

    private fun executeAlarmStopCommand(): ExecutionResult {
        AlarmPlaybackService.stop(context)
        return ExecutionResult(status = "SUCCESS")
    }

    // Real hardware lock requires the app to be registered as Device Owner. isDeviceOwnerApp()
    // is checked explicitly (rather than just letting lockNow() throw) so this keeps reporting
    // the same honest FAILED/NOT_SUPPORTED status on a non-provisioned test build that it always
    // has — the command pipeline (queue -> poll -> execute -> ack -> history) stays verifiable
    // end-to-end either way, it just now actually succeeds once Device Owner is real.
    private suspend fun executeLockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "DevicePolicyManager unavailable on this device."
            )
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "FAILED",
                error = "This agent is not Device Owner on this device, so the OS refused the lock request."
            )
        }
        return try {
            lockStateManager.setLocked(true)
            fetchAndCacheLockInfo()
            try {
                dpmLocal.setStatusBarDisabled(admin, true)
            } catch (e: Exception) {
                // Non-fatal — the kiosk pin below is the primary enforcement mechanism;
                // setStatusBarDisabled is defense in depth and deprecated on API 30+.
            }
            dpmLocal.lockNow()
            // Refreshed here (not just once at enrollment) so a device enrolled before the
            // dealer-call-button shipped still picks up the widened lock-task allowlist the
            // next time it locks — see refreshLockTaskPackages's own doc comment.
            DeviceRestrictionPolicy.refreshLockTaskPackages(context, dpmLocal, admin)
            launchKioskLockActivity()
            ExecutionResult(status = "SUCCESS")
        } catch (se: SecurityException) {
            lockStateManager.setLocked(false)
            ExecutionResult(
                status = "FAILED",
                error = "Device Owner rejected the lock request: ${se.localizedMessage}"
            )
        } catch (e: Exception) {
            lockStateManager.setLocked(false)
            ExecutionResult(status = "FAILED", error = e.localizedMessage ?: "Failed to execute lock command")
        }
    }

    // Best-effort — the kiosk screen falls back to a bundled generic reason (no dealer/support
    // contact) if this fails, rather than blocking the lock itself on a network call succeeding.
    private suspend fun fetchAndCacheLockInfo() {
        val deviceToken = TokenManager(context).getDeviceToken() ?: return
        val result = DeviceRepository().getLockInfo(deviceToken)
        result.getOrNull()?.let { info ->
            lockStateManager.saveLockInfo(
                reason = info.lockReason,
                dealerName = info.dealerName,
                dealerPhone = info.dealerPhone,
                dealerEmail = info.dealerEmail,
                supportEmail = info.supportEmail,
                supportPhone = info.supportPhone
            )
        }
    }

    private fun executeUnlockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "DevicePolicyManager unavailable on this device."
            )
        if (!dpmLocal.isDeviceOwnerApp(context.packageName)) {
            return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "This agent is not Device Owner on this device, so there is nothing to unlock."
            )
        }
        return try {
            lockStateManager.setLocked(false)
            try {
                dpmLocal.setStatusBarDisabled(admin, false)
            } catch (e: Exception) {
                // Non-fatal, see executeLockCommand.
            }
            // KioskLockActivity (if currently foreground) listens for this and calls its own
            // stopLockTask() — that call must come from the pinned Activity itself, not here.
            context.sendBroadcast(Intent(KioskLockActivity.ACTION_UNLOCK).setPackage(context.packageName))
            ExecutionResult(status = "SUCCESS")
        } catch (e: Exception) {
            ExecutionResult(status = "FAILED", error = e.localizedMessage ?: "Failed to execute unlock command")
        }
    }

    // Fires when the EMI loan is fully paid off — distinct from UNLOCK (which only reverses
    // the payment-status-dependent lock). This must leave the phone completely normal: no
    // restrictions, uninstallable, Device Owner status itself removed, factory reset working
    // again. Deliberately does not check enrollment/lock state first — a customer's final
    // payment can land while the device happens to be mid-LOCK from a prior missed payment, and
    // release must still fully succeed regardless of what state it's currently in.
    private fun executeReleaseDeviceCommand(): ExecutionResult {
        // Exit the kiosk screen first if it's currently showing, before anything else — once
        // clearDeviceOwnerApp() runs there's no guarantee stopLockTask() semantics are still
        // exactly as expected, so do this while Device Owner privilege is still fully intact.
        lockStateManager.setLocked(false)
        context.sendBroadcast(Intent(KioskLockActivity.ACTION_UNLOCK).setPackage(context.packageName))
        WifiBlockManager.setBlocked(context, false)
        WifiBlockManager.enableWifi(context)
        BluetoothBlockManager.setBlocked(context, false)
        BluetoothBlockManager.enableBluetooth(context)
        WallpaperOverlayManager.disable(context)
        AlarmPlaybackService.stop(context)

        val released = DeviceRestrictionPolicy.releaseDevice(context)
        if (!released) {
            return ExecutionResult(
                status = "FAILED",
                error = "clearDeviceOwnerApp failed — device remains under Device Owner management. See device logs."
            )
        }

        // Local enrollment state is cleared last, after release has actually succeeded — the
        // device token captured by the caller (CommandSync/AgentViewModel) before this method
        // ran is still used to send the ACK for this command, so clearing it here doesn't
        // affect that in-flight call.
        TokenManager(context).clear()
        HeartbeatScheduler.cancel(context)
        HeartbeatForegroundService.stop(context)

        return ExecutionResult(status = "SUCCESS")
    }

    private fun launchKioskLockActivity() {
        val intent = Intent(context, KioskLockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
