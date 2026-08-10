package com.nexlock.agent.service

import android.content.Context
import com.nexlock.agent.data.repository.DeviceRepository

/**
 * Shared headless fetch -> execute -> ack pass, used by both FcmService (push-triggered) and
 * BootReceiver (boot-triggered) — anywhere the Agent needs to sync commands without a
 * ViewModel/UI present. AgentViewModel.pollAndExecuteCommands() keeps its own copy since it
 * also drives UI state (status text, counters) that this headless path has no use for.
 */
object CommandSync {

    suspend fun fetchExecuteAck(context: Context, deviceToken: String) {
        val repository = DeviceRepository()
        val dispatcher = CommandDispatcher(context)

        try {
            val result = repository.fetchPendingCommands(deviceToken)
            val commands = result.getOrNull() ?: emptyList()
            for (cmd in commands) {
                val execResult = dispatcher.executeCommand(cmd.commandType)
                val ackResult = repository.acknowledgeCommand(
                    deviceToken = deviceToken,
                    commandId = cmd.id,
                    status = execResult.status,
                    error = execResult.error
                )

                if (ackResult.isFailure) {
                    // The command already executed locally — don't lose track of its
                    // outcome just because the ACK couldn't be delivered right now.
                    PendingAckQueue.enqueue(
                        context,
                        PendingAck(deviceToken, cmd.id, execResult.status, execResult.error)
                    )
                    RetryWorker.scheduleOneShot(context)
                }

                if (cmd.commandType == "LOCK" || cmd.commandType == "UNLOCK") {
                    val telemetry = DeviceTelemetry.capture(context)
                    repository.sendHeartbeat(
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
                        appVersion = telemetry.appVersion
                    )
                }
            }
        } catch (e: Exception) {
            // Best-effort — no UI to surface this to. The next push or the periodic
            // heartbeat/retry cycle will pick up anything missed here.
        }
    }
}
