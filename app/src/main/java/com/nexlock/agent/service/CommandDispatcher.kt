package com.nexlock.agent.service

import android.app.admin.DevicePolicyManager
import android.content.Context

class CommandDispatcher(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    data class ExecutionResult(
        val status: String, // SUCCESS, FAILED, NOT_SUPPORTED
        val error: String? = null
    )

    fun executeCommand(commandType: String): ExecutionResult {
        return when (commandType.uppercase()) {
            "LOCK" -> executeLockCommand()
            "UNLOCK" -> executeUnlockCommand()
            else -> ExecutionResult(status = "NOT_SUPPORTED", error = "Unknown command type: $commandType")
        }
    }

    // Phase 2.5 note: real hardware screen lock/unlock requires the app to be registered as a
    // Device Admin (or Device Owner), which is explicitly out of scope until Phase 3. Rather
    // than reporting a fake SUCCESS when that registration doesn't exist, this honestly acks
    // FAILED/NOT_SUPPORTED so the command pipeline (queue -> poll -> execute -> ack -> history)
    // can still be verified end-to-end without misrepresenting what actually happened on the
    // device.
    private fun executeLockCommand(): ExecutionResult {
        val dpmLocal = dpm
            ?: return ExecutionResult(
                status = "NOT_SUPPORTED",
                error = "DevicePolicyManager unavailable on this device."
            )
        return try {
            dpmLocal.lockNow()
            ExecutionResult(status = "SUCCESS")
        } catch (se: SecurityException) {
            ExecutionResult(
                status = "FAILED",
                error = "Device Admin is not registered on this agent yet, so the OS refused the lock request. " +
                    "Device Admin/Owner provisioning is planned for Phase 3."
            )
        } catch (e: Exception) {
            ExecutionResult(status = "FAILED", error = e.localizedMessage ?: "Failed to execute lock command")
        }
    }

    private fun executeUnlockCommand(): ExecutionResult {
        // There is no OS-level lock state to reverse yet (see executeLockCommand) — reporting
        // NOT_SUPPORTED here is the honest counterpart rather than claiming an unlock happened.
        return ExecutionResult(
            status = "NOT_SUPPORTED",
            error = "No Device Admin lock is currently enforced on this agent, so there is nothing to unlock yet."
        )
    }
}
