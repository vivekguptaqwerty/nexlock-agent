package com.nexlock.agent.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexlock.agent.MainActivity
import com.nexlock.agent.R
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A genuine persistent foreground service, not just a WorkManager job promoted to the
 * foreground for the duration of one doWork() call. Real-hardware testing showed the actual
 * failure mode isn't work being killed mid-execution — it's the periodic WorkManager job (and
 * FCM pushes) never firing at all under aggressive OEM battery managers (observed on ColorOS).
 * A persistent foreground service with a visible notification sits much higher in Android's
 * process-importance model, which is what actually keeps command delivery working without the
 * customer having to manually open the app.
 *
 * Runs as foregroundServiceType="systemExempted" — the type Android explicitly reserves for
 * Device Owner / Profile Owner / Device Admin apps, with no execution time limit (unlike
 * dataSync, which is capped at 6 hours per 24h as of Android 15). Eligibility requires the app
 * to actually be Device Owner, checked before starting.
 *
 * This does not replace HeartbeatScheduler's WorkManager periodic job or FCM push — both stay
 * in place as redundant fallback paths. If this service itself ever gets killed for some
 * OEM-specific reason, those still provide a (slower) backstop.
 */
class HeartbeatForegroundService : Service() {

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob)
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (loopJob?.isActive != true) {
            loopJob = scope.launch { runLoop() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        supervisorJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runLoop() {
        val tokenManager = TokenManager(applicationContext)
        while (scope.isActive) {
            val deviceToken = tokenManager.getDeviceToken()
            if (!deviceToken.isNullOrBlank()) {
                try {
                    val telemetry = DeviceTelemetry.capture(applicationContext)
                    val repository = DeviceRepository()
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
                        appVersion = telemetry.appVersion,
                        fcmToken = currentFcmToken()
                    )
                    CommandSync.fetchExecuteAck(applicationContext, deviceToken)
                } catch (e: Exception) {
                    // Best-effort — the loop keeps running regardless; next iteration retries.
                }
            }
            delay(INTERVAL_MS)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps NexLock connected so lock/unlock commands are delivered promptly."
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NexLock is protecting this device")
            .setContentText("Maintaining connection for remote lock/unlock.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "nexlock_device_protection"
        private const val NOTIFICATION_ID = 4201
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /**
         * No-op if the app isn't Device Owner (systemExempted eligibility requires it) — this
         * mirrors the same guard every other DeviceRestrictionPolicy entry point uses, rather
         * than crashing or silently failing deep inside the service's own lifecycle.
         */
        fun start(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return

            val intent = Intent(context, HeartbeatForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HeartbeatForegroundService::class.java))
        }
    }
}
