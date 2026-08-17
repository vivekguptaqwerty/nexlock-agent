package com.nexlock.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nexlock.agent.MainActivity
import com.nexlock.agent.R
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
        when (message.data["type"]) {
            "COMMAND_AVAILABLE" -> handleCommandAvailable()
            "PAYMENT_REMINDER" -> showReminderNotification(
                message.data["title"] ?: "Payment Reminder — NexLock",
                message.data["body"] ?: "Your EMI payment is due."
            )
        }
    }

    private fun handleCommandAvailable() {
        val tokenManager = TokenManager(applicationContext)
        val deviceToken = tokenManager.getDeviceToken()
        if (deviceToken.isNullOrBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            CommandSync.fetchExecuteAck(applicationContext, deviceToken)
        }
    }

    // Dealer-initiated, fully manual (no due-date tracking behind this) — a normal-importance,
    // dismissible notification is the right fit here, unlike HeartbeatForegroundService's
    // low-priority ongoing "Device Protection" one: this is meant to actually get the
    // customer's attention (sound/heads-up), not just signal the app is alive in the background.
    private fun showReminderNotification(title: String, body: String) {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Payment Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Payment reminders sent by your dealer."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(contentIntent)
            .build()

        manager?.notify(REMINDER_NOTIFICATION_ID, notification)
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

    companion object {
        private const val REMINDER_CHANNEL_ID = "nexlock_payment_reminders"
        private const val REMINDER_NOTIFICATION_ID = 4202
    }
}
