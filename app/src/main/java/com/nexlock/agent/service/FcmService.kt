package com.nexlock.agent.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes to wake the Agent for immediate command delivery, independent of the
 * app's UI/ViewModel — the app is very often not in the foreground when a push arrives. Runs
 * the same fetch -> execute -> ack flow as AgentViewModel.pollAndExecuteCommands().
 */
class FcmService : FirebaseMessagingService() {

    private val repository = DeviceRepository()

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "COMMAND_AVAILABLE") return

        val tokenManager = TokenManager(applicationContext)
        val deviceToken = tokenManager.getDeviceToken()
        if (deviceToken.isNullOrBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            CommandSync.fetchExecuteAck(applicationContext, deviceToken)
        }
    }

    override fun onNewToken(token: String) {
        val tokenManager = TokenManager(applicationContext)
        if (!tokenManager.isEnrolled()) return
        val deviceToken = tokenManager.getDeviceToken() ?: return

        // Push the refreshed token to the backend immediately via the existing heartbeat
        // pipeline, rather than waiting for the next scheduled 24h heartbeat.
        CoroutineScope(Dispatchers.IO).launch {
            repository.sendHeartbeat(deviceToken = deviceToken, fcmToken = token)
        }
    }
}
