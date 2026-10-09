package com.nexlock.agent.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nexlock.agent.MainActivity
import com.nexlock.agent.R
import com.nexlock.agent.data.storage.LockStateManager

/**
 * Holds the actual TYPE_APPLICATION_OVERLAY banner — see WallpaperOverlayManager for the full
 * picture (this is only the "draw on top of every app" half, not the wallpaper/restriction
 * half). Runs as a foreground service for the same reason HeartbeatForegroundService does: a
 * plain background object holding a WindowManager view doesn't survive process death the way a
 * foreground service does.
 *
 * Deliberately non-touch-intercepting (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE) — this is a nag,
 * not a lock. The device stays fully usable underneath it.
 *
 * SYSTEM_ALERT_WINDOW ("draw over other apps") is a special permission, not a standard runtime
 * one — DeviceRestrictionPolicy attempts to self-grant it via setPermissionGrantState() the same
 * way every other permission in this app is granted, but unlike those, there's no confirmed
 * guarantee that call actually applies to this specific permission on every OS version. If
 * addView() below fails (logged, not crashed), the wallpaper half of the feature (home/lock
 * screen only) still applies on its own — this degrades gracefully rather than doing nothing.
 */
class WallpaperOverlayService : Service() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var pulseAnimator: ValueAnimator? = null

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
        showOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        if (overlayView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        val banner = TextView(this).apply {
            text = buildMessage()
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D32F2F"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(32, 40, 32, 40)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            windowManager?.addView(banner, params)
            overlayView = banner
            startPulse(banner)
            Log.i(TAG, "Overlay banner shown")
        } catch (e: Exception) {
            Log.e(TAG, "addView failed — SYSTEM_ALERT_WINDOW likely not granted on this device", e)
        }
    }

    // Alternates the banner's opacity so it's harder to tune out visually — deliberately
    // "irritating" per the feature's own purpose, not a bug.
    private fun startPulse(view: View) {
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.5f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { view.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun removeOverlay() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // Non-fatal — already removed, or never successfully added.
            }
        }
        overlayView = null
    }

    private fun buildMessage(): String {
        val dealerPhone = LockStateManager(this).getDealerPhone()
        return if (!dealerPhone.isNullOrBlank()) {
            "PAYMENT OVERDUE — CONTACT YOUR DEALER\n$dealerPhone"
        } else {
            "PAYMENT OVERDUE — CONTACT YOUR DEALER"
        }
    }

    // IMPORTANCE_MIN, same reasoning as every other foreground service's required notification
    // in this app — the overlay itself is the visible signal, this is just the OS-mandated
    // notification a foreground service can't ship without.
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Payment Reminder",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Persistent payment-overdue reminder."
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
            .setContentText("Payment reminder active")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val TAG = "WallpaperOverlayService"
        private const val CHANNEL_ID = "nexlock_wallpaper_overlay"
        private const val NOTIFICATION_ID = 4202

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WallpaperOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WallpaperOverlayService::class.java))
        }
    }
}
