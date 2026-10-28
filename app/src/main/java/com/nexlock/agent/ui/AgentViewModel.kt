package com.nexlock.agent.ui

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nexlock.agent.data.api.NetworkModule
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager
import com.nexlock.agent.service.CommandDispatcher
import com.nexlock.agent.service.DeviceRestrictionPolicy
import com.nexlock.agent.service.DeviceTelemetry
import com.nexlock.agent.service.HeartbeatForegroundService
import com.nexlock.agent.service.HeartbeatScheduler
import com.nexlock.agent.service.NotificationTimeoutScheduler
import com.nexlock.agent.service.currentFcmToken
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DeviceRepository()
    private val tokenManager = TokenManager(application)
    private val dispatcher = CommandDispatcher(application)

    // Form inputs
    var enrollmentTokenInput by mutableStateOf("")
    var otpInput by mutableStateOf("")
    var serverUrlInput by mutableStateOf("https://nexlock-backend.onrender.com/")
    
    // Status states
    var isEnrolling by mutableStateOf(false)
    var enrollmentError by mutableStateOf<String?>(null)
    var isEnrolled by mutableStateOf(tokenManager.isEnrolled())

    // Local consent gate for the manual (non-QR) enrollment path — see
    // TermsAcceptanceActivity.launchForManualFlow. Re-checked in MainActivity.onResume() since
    // that Activity finishes back here after the customer accepts.
    var isTermsAccepted by mutableStateOf(tokenManager.isTermsAccepted())

    fun refreshTermsAcceptedStatus() {
        isTermsAccepted = tokenManager.isTermsAccepted()
    }

    var deviceId by mutableStateOf(tokenManager.getDeviceId() ?: "")
    var deviceToken by mutableStateOf(tokenManager.getDeviceToken() ?: "")
    
    var lastHeartbeatTime by mutableStateOf("Not sent yet")
    var heartbeatCount by mutableStateOf(0)
    var heartbeatStatus by mutableStateOf("Idle")
    var isSendingHeartbeat by mutableStateOf(false)

    // Command Polling states
    var lastExecutedCommand by mutableStateOf<String?>("None")
    var commandPollingStatus by mutableStateOf("Idle")
    var isPollingCommands by mutableStateOf(false)
    var totalCommandsProcessed by mutableStateOf(0)

    init {
        NetworkModule.setBaseUrl(serverUrlInput)
        if (isEnrolled) {
            scheduleHeartbeatWorker()
            sendManualHeartbeat() // event-triggered: app opened
            pollAndExecuteCommands()
            validateStoredEnrollment()
        }
    }

    /**
     * Self-heals a stale local enrollment — see DeviceRepository.checkDeviceTokenStillValid's
     * doc comment for the root cause (DEV-2026-001384/001387): a phone reused for a new
     * customer without ever being Released first shows isEnrolled=true forever, from a prior
     * enrollment, with no backend-side Device row left to match it — the OTP screen for the new
     * customer never even appears, since isEnrolled alone decides what MainActivity shows.
     * A 401 specifically means the backend has no idea what this token is anymore (the Device
     * row is gone or was never this one); only then is it safe to drop back to the enrollment
     * form. Any other outcome (network failure, 5xx, no connectivity) leaves everything exactly
     * as it was — a phone that's genuinely still enrolled and locked must never lose that
     * protection just because of a bad network moment.
     */
    private fun validateStoredEnrollment() {
        val token = tokenManager.getDeviceToken()
        if (token.isNullOrBlank()) return
        viewModelScope.launch {
            if (repository.checkDeviceTokenStillValid(token) == 401) {
                TokenManager(getApplication()).clear()
                com.nexlock.agent.data.storage.LockStateManager(getApplication()).setLocked(false)
                isEnrolled = false
                deviceId = ""
                deviceToken = ""
            }
        }
    }

    fun performHandshake() {
        if (enrollmentTokenInput.isBlank() && otpInput.isBlank()) {
            enrollmentError = "Provide Enrollment Token or 6-digit OTP"
            return
        }

        enrollmentError = null
        isEnrolling = true
        NetworkModule.setBaseUrl(serverUrlInput)

        // Real device identity read from the OS instead of hardcoded placeholder values —
        // standard, non-privileged android.os.Build/Settings.Secure fields. This manual form
        // is now a support-recovery fallback; the primary enrollment path is the automatic
        // handshake ProvisioningHandshakeWorker performs right after real QR-based Device
        // Owner provisioning completes (see NexLockDeviceAdminReceiver).
        val realAndroidId = try {
            Settings.Secure.getString(getApplication<Application>().contentResolver, Settings.Secure.ANDROID_ID)
                ?: "UNKNOWN"
        } catch (e: Exception) {
            "UNKNOWN"
        }

        viewModelScope.launch {
            // This manual form is the path used right after `adb shell dpm set-device-owner`
            // (see its doc comment above) — exactly the step observed, on real devices, to
            // sometimes report success without actually granting ownership. Completing the
            // handshake anyway left the backend marking the device ENROLLED/Assigned with no
            // real Device Owner behind it — invisible until a dealer tested Lock/Unlock and
            // found it silently failing, at which point the only fix was a full factory-reset
            // re-enrollment. Checking this here, before the handshake is sent, stops that dead
            // in its tracks.
            //
            // Retried rather than checked once: confirmed live (DEV-2026-001384,
            // DEV-2026-001387 — two different dealers, same day) that a single immediate check
            // produces a false negative often enough to matter — isDeviceOwnerApp() can return
            // false for up to a second or two right after a genuinely successful
            // set-device-owner, the exact same framework race FactoryResetProtection.kt's own
            // comment already documents. A single-shot check here blocked the handshake outright
            // on a phone that WAS actually Device Owner, and because the block happens before
            // any network call, the backend saw zero trace of the attempt at all — indistinguishable
            // from a phone that was never touched. Five attempts over four seconds is enough
            // headroom for that race to resolve itself without making a genuinely-not-Device-Owner
            // phone wait meaningfully longer to find that out.
            val dpm = getApplication<Application>().getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            var isDeviceOwner = dpm?.isDeviceOwnerApp(getApplication<Application>().packageName) == true
            var attempt = 1
            while (!isDeviceOwner && attempt < 5) {
                kotlinx.coroutines.delay(1000)
                isDeviceOwner = dpm?.isDeviceOwnerApp(getApplication<Application>().packageName) == true
                attempt++
            }
            if (!isDeviceOwner) {
                isEnrolling = false
                enrollmentError = "Device admin was not set up on this phone (Device Owner is missing). Enrolling now would leave Lock/Unlock broken. Go back to the dealer app and re-run Activate first — do not enter the OTP until that succeeds."
                return@launch
            }

            val result = repository.performHandshake(
                enrollmentToken = enrollmentTokenInput.ifBlank { null },
                otp = otpInput.ifBlank { null },
                androidId = realAndroidId,
                deviceModel = Build.MODEL ?: "Unknown Model",
                manufacturer = Build.MANUFACTURER ?: "Unknown Manufacturer",
                androidVersion = Build.VERSION.RELEASE ?: "Unknown",
                sdkVersion = Build.VERSION.SDK_INT,
                termsAccepted = tokenManager.isTermsAccepted()
            )

            isEnrolling = false
            if (result.isSuccess) {
                val data = result.getOrNull()!!
                tokenManager.saveEnrollmentSession(
                    deviceToken = data.deviceToken,
                    deviceId = data.deviceId,
                    loanId = data.loanId,
                    heartbeatInterval = data.heartbeatInterval
                )
                deviceId = data.deviceId
                deviceToken = data.deviceToken
                isEnrolled = true

                // This manual form is also the only enrollment path when Device Owner was set
                // via `adb shell dpm set-device-owner` (bypassing real QR provisioning) — e.g.
                // a support-recovery re-enrollment. It must apply the same baseline restrictions
                // (factory-reset block, uninstall block, lock-task allowlist, permission
                // self-grants) that ProvisioningHandshakeWorker applies after a QR-based
                // handshake; skipping this left a real Device Owner device with none of that
                // enforcement active until its next reboot.
                val diagLog = DeviceRestrictionPolicy.applyBaselineRestrictions(getApplication())
                repository.submitDiagnostics(data.deviceToken, "manual_otp_handshake", diagLog)
                NotificationTimeoutScheduler.schedule(getApplication(), data.notificationVisibilityMinutes)

                scheduleHeartbeatWorker()
                sendManualHeartbeat()
                pollAndExecuteCommands()
            } else {
                enrollmentError = result.exceptionOrNull()?.message ?: "Handshake rejected by server"
            }
        }
    }

    fun sendManualHeartbeat() {
        if (!isEnrolled || deviceToken.isBlank()) return

        isSendingHeartbeat = true
        heartbeatStatus = "Sending heartbeat telemetry..."

        viewModelScope.launch {
            val telemetry = DeviceTelemetry.capture(getApplication())
            val result = repository.sendHeartbeat(
                deviceToken = deviceToken,
                batteryLevel = telemetry.batteryLevel,
                isCharging = telemetry.isCharging,
                networkType = telemetry.networkType,
                networkOperator = telemetry.networkOperator,
                storageAvailableMb = telemetry.storageAvailableMb,
                storageTotalMb = telemetry.storageTotalMb,
                ramAvailableMb = telemetry.ramAvailableMb,
                ramTotalMb = telemetry.ramTotalMb,
                screenState = telemetry.screenState,
                appVersion = telemetry.appVersion,
                fcmToken = currentFcmToken()
            )

            isSendingHeartbeat = false
            if (result.isSuccess) {
                heartbeatCount++
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                lastHeartbeatTime = sdf.format(Date())
                heartbeatStatus = "Healthy (Acknowledged by server)"
            } else {
                heartbeatStatus = "Failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun pollAndExecuteCommands() {
        if (!isEnrolled || deviceToken.isBlank()) return

        isPollingCommands = true
        commandPollingStatus = "Polling pending MDM commands..."

        viewModelScope.launch {
            val result = repository.fetchPendingCommands(deviceToken)
            isPollingCommands = false

            if (result.isSuccess) {
                val commands = result.getOrNull() ?: emptyList()
                if (commands.isEmpty()) {
                    commandPollingStatus = "No pending commands found"
                } else {
                    commandPollingStatus = "Processing ${commands.size} command(s)..."
                    for (cmd in commands) {
                        val execResult = dispatcher.executeCommand(cmd.commandType)
                        val ackResult = repository.acknowledgeCommand(
                            deviceToken = deviceToken,
                            commandId = cmd.id,
                            status = execResult.status,
                            error = execResult.error
                        )

                        if (ackResult.isSuccess) {
                            totalCommandsProcessed++
                            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            lastExecutedCommand = "${cmd.commandType} (${execResult.status}) at ${sdf.format(Date())}"

                            // Event-triggered heartbeat: lock/unlock executed — gives the
                            // backend an immediate, fresh telemetry snapshot around exactly
                            // the moments that matter most, rather than waiting for the next
                            // 24h cycle.
                            if (cmd.commandType == "LOCK" || cmd.commandType == "UNLOCK") {
                                sendManualHeartbeat()
                            }
                        }
                    }
                    commandPollingStatus = "Command queue synchronized successfully"
                }
            } else {
                commandPollingStatus = "Polling failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    private fun scheduleHeartbeatWorker() {
        // Independently guarded — see BootReceiver's identical pattern for why: observed on real
        // hardware that WorkManager.getInstance() (inside HeartbeatScheduler.schedule) can throw
        // "WorkManager is not initialized properly" in certain timing conditions, and an
        // unguarded throw here would also skip HeartbeatForegroundService.start() right after
        // it, which is the one thing that actually keeps command delivery working.
        runCatching { HeartbeatScheduler.schedule(getApplication()) }
            .onFailure { android.util.Log.e("AgentViewModel", "HeartbeatScheduler.schedule failed", it) }
        runCatching { HeartbeatForegroundService.start(getApplication()) }
            .onFailure { android.util.Log.e("AgentViewModel", "HeartbeatForegroundService.start failed", it) }
    }
}
