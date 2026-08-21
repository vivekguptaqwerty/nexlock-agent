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
                DeviceRestrictionPolicy.applyBaselineRestrictions(getApplication())

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
        HeartbeatScheduler.schedule(getApplication())
        HeartbeatForegroundService.start(getApplication())
    }
}
