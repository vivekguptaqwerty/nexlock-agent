package com.nexlock.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexlock.agent.MainActivity
import com.nexlock.agent.R

/**
 * Loud buzzer, regardless of silent/vibrate mode — fire-and-forget, no persisted Device-level
 * state (see command.service.ts's ack handling), the agent auto-stops itself after
 * AUTO_STOP_MS. Gated by Dealer.enabledFeatures ("ALARM") on the backend.
 *
 * Silent and vibrate ringer modes mute the RINGTONE/NOTIFICATION streams — they do NOT mute
 * STREAM_ALARM, by design (it's why alarm-clock apps still sound in silent mode). Playing on
 * that stream, after pushing it to max volume, is what actually bypasses silent mode here —
 * no special permission or ringer-mode change needed, unlike trying to flip the device's ringer
 * mode directly (which ACCESS_NOTIFICATION_POLICY would gate, and which the customer could just
 * flip back).
 *
 * Known limitation, same honesty as elsewhere in this app: some OEM skins layer their own
 * additional Do-Not-Disturb/audio-muting logic on top of stock Android that STREAM_ALARM isn't
 * guaranteed to be exempt from. This is the standard, most-likely-to-work mechanism, not a
 * guarantee across every device.
 */
class AlarmPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopSelf() }

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
        startAlarm()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAlarm() {
        acquireWakeLockAndWakeScreen()

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val max = audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (audioManager != null && max != null) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "setStreamVolume(STREAM_ALARM) failed", e)
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val afd = resources.openRawResourceFd(R.raw.alarm_buzzer)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
            Log.i(TAG, "Alarm playback started")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer start failed", e)
        }

        handler.postDelayed(stopRunnable, AUTO_STOP_MS)
    }

    private fun stopAlarm() {
        handler.removeCallbacks(stopRunnable)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            // Non-fatal — already stopped/released, or never successfully prepared.
        }
        mediaPlayer = null
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // Non-fatal.
        }
        wakeLock = null
    }

    // FULL_WAKE_LOCK is deprecated (API 17) but remains the simplest way for a background
    // service with no associated Activity to turn the screen on, matching how a real alarm
    // clock app behaves — the whole point here is to be impossible to miss, not just audible.
    @Suppress("DEPRECATION")
    private fun acquireWakeLockAndWakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "nexlock:alarm"
            )
            wakeLock?.acquire(AUTO_STOP_MS + 5_000L)
        } catch (e: Exception) {
            Log.e(TAG, "Wake lock acquire failed", e)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Remote alarm playback."
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NexLock")
            .setContentText("Alarm active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "AlarmPlaybackService"
        private const val CHANNEL_ID = "nexlock_alarm"
        private const val NOTIFICATION_ID = 4203

        // Auto-stop safety net, independent of the dealer sending ALARM_STOP — a forgotten
        // trigger shouldn't drain the battery or cause a real-world problem if the phone is
        // somewhere like a meeting or a customer's pocket in public.
        private const val AUTO_STOP_MS = 90_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmPlaybackService::class.java))
        }
    }
}
