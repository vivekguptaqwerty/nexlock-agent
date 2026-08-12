package com.nexlock.agent.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The real kiosk lock screen — pinned via startLockTask() once the app is Device Owner, unlike
 * the deleted MainActivity POC card. Launched from CommandDispatcher.executeLockCommand() (a
 * background-launch that only works because Device Owner apps are exempt from Android's
 * background-activity-launch restrictions), from MainActivity's launch-time guard, and from
 * BootReceiver on reboot while LockStateManager.isLocked() is true.
 *
 * Not registered as a launcher Activity and not exported — the only ways into this screen are
 * the app's own code paths above.
 */
class KioskLockActivity : ComponentActivity() {

    private var receiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_UNLOCK) {
                exitKiosk()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureLockScreenWindow()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentional no-op — the back gesture/button must not be a way out of this
                // screen. Kiosk pinning (startLockTask) is the real enforcement; this is
                // defense in depth for the moment between launch and the pin taking effect.
            }
        })

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    KioskLockedScreen(onEmergencyCall = { launchEmergencyDialer() })
                }
            }
        }

        try {
            startLockTask()
        } catch (e: Exception) {
            // Non-fatal: if this isn't Device Owner yet (a stray launch on a test build) the
            // screen still renders and still blocks casual navigation via the back-callback
            // above, it just isn't OS-enforced pinning.
            Log.w(TAG, "startLockTask failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_UNLOCK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(unlockReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            unregisterReceiver(unlockReceiver)
            receiverRegistered = false
        }
    }

    private fun configureLockScreenWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun launchEmergencyDialer() {
        // ACTION_DIAL is public API available to any app with no permission requirement —
        // opens the system Dialer so the user can place an emergency call themselves. Flagged
        // in the Phase 3 plan as OEM-skin-sensitive (whether the Dialer is reachable from a
        // lock-task-pinned app varies by OEM) — must be validated on real hardware across
        // multiple OEM skins, not assumed correct from this implementation alone.
        try {
            startActivity(Intent(Intent.ACTION_DIAL))
        } catch (e: Exception) {
            Log.w(TAG, "Emergency dialer launch failed", e)
        }
    }

    private fun exitKiosk() {
        // stopLockTask() must be called by the Activity currently in lock-task mode itself,
        // not from a Service — this is why UNLOCK is delivered here via broadcast rather than
        // CommandDispatcher calling stopLockTask() directly.
        try {
            stopLockTask()
        } catch (e: Exception) {
            Log.w(TAG, "stopLockTask failed", e)
        }
        finish()
    }

    companion object {
        private const val TAG = "KioskLockActivity"
        const val ACTION_UNLOCK = "com.nexlock.agent.ACTION_UNLOCK"
    }
}

@Composable
private fun KioskLockedScreen(onEmergencyCall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "DEVICE LOCKED",
            color = Color(0xFFF87171),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "This device has been locked by NexLock due to an overdue payment.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Contact your dealer to resume payment and unlock this device.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onEmergencyCall,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
        ) {
            Text("EMERGENCY CALL")
        }
    }
}
