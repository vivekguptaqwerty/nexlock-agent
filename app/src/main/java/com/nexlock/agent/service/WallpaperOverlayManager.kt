package com.nexlock.agent.service

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.UserManager
import android.util.Log
import com.nexlock.agent.data.storage.LockStateManager

/**
 * Payment-overdue nag, independent of LOCK/UNLOCK — a dealer can turn this on without locking
 * the device at all. Deliberately does NOT block interaction (no touch-intercepting overlay,
 * no lock-task pin): the device stays fully usable, it's just impossible to miss. Two parts,
 * applied together:
 *
 * 1. The actual device wallpaper, replaced with a high-contrast rendered message, locked in
 *    place via DISALLOW_SET_WALLPAPER so the customer can't just pick a different one. Only
 *    visible on the home/lock screen, same limitation any wallpaper has.
 * 2. A persistent TYPE_APPLICATION_OVERLAY banner (see WallpaperOverlayService) that draws on
 *    top of every app, not just the home screen — this is what actually makes it hard to ignore
 *    while using WhatsApp, a banking app, etc.
 *
 * Gated by Dealer.enabledFeatures ("WALLPAPER_OVERLAY") on the backend — not every dealer has
 * this available, unlike WIFI_BLOCK/BLUETOOTH_BLOCK.
 */
object WallpaperOverlayManager {

    private const val TAG = "WallpaperOverlayManager"
    private const val PREFS_NAME = "nexlock_agent_wallpaper_overlay_prefs"
    private const val KEY_ACTIVE = "key_active"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    fun enable(context: Context) {
        setActive(context, true)
        applyWallpaper(context)
        applyRestriction(context, true)
        WallpaperOverlayService.start(context)
    }

    fun disable(context: Context) {
        setActive(context, false)
        applyRestriction(context, false)
        resetWallpaper(context)
        WallpaperOverlayService.stop(context)
    }

    /**
     * Re-applies the current state — called on boot and, more importantly, on every heartbeat
     * (see HeartbeatForegroundService.runLoop) since the real-world failure mode observed isn't
     * a reboot, it's an OEM battery manager killing WallpaperOverlayService mid-session.
     *
     * Stops the service before restarting it rather than just calling start() — start() alone is
     * a no-op against an already-running service instance (showOverlay()'s overlayView != null
     * guard), which wouldn't help if the OS silently tore down the overlay WINDOW while the
     * process itself stayed alive (observed as a distinct failure mode from a full process kill
     * on some OEM skins). Stop+start forces onDestroy() to clean up whatever stale state exists,
     * then a fresh showOverlay() call on the next onCreate()/onStartCommand().
     */
    fun enforce(context: Context) {
        if (isActive(context)) {
            applyRestriction(context, true)
            WallpaperOverlayService.stop(context)
            WallpaperOverlayService.start(context)
        }
    }

    private fun applyWallpaper(context: Context) {
        try {
            WallpaperManager.getInstance(context).setBitmap(renderMessageBitmap(context))
        } catch (e: Exception) {
            Log.e(TAG, "setBitmap failed", e)
        }
    }

    private fun resetWallpaper(context: Context) {
        try {
            // Can't restore whatever the customer's original wallpaper was — Device Owner apps
            // have no API to read back a previous third-party wallpaper once it's been
            // overwritten. Resets to a plain neutral color instead of leaving the alarming
            // red/white message in place after the dealer clears it.
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.parseColor("#E6F4FE"))
            WallpaperManager.getInstance(context).setBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "wallpaper reset failed", e)
        }
    }

    private fun renderMessageBitmap(context: Context): Bitmap {
        val dm = context.resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(720)
        val height = dm.heightPixels.coerceAtLeast(1280)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#D32F2F"))

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.085f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = width * 0.05f
            textAlign = Paint.Align.CENTER
        }

        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawText("PAYMENT OVERDUE", centerX, centerY - titlePaint.textSize * 0.6f, titlePaint)
        canvas.drawText("CONTACT YOUR DEALER", centerX, centerY + titlePaint.textSize * 0.6f, titlePaint)

        val dealerPhone = LockStateManager(context).getDealerPhone()
        if (!dealerPhone.isNullOrBlank()) {
            canvas.drawText(dealerPhone, centerX, centerY + titlePaint.textSize * 2f, subPaint)
        }

        return bitmap
    }

    private fun applyRestriction(context: Context, disallow: Boolean) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val admin = ComponentName(context, NexLockDeviceAdminReceiver::class.java)
            if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return
            if (disallow) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SET_WALLPAPER)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SET_WALLPAPER)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle DISALLOW_SET_WALLPAPER", e)
        }
    }
}
