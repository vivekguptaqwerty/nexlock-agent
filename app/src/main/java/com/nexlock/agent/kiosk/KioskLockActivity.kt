package com.nexlock.agent.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
        // Real-hardware testing found ACTION_DIAL does not work here: it opens the general
        // Dialer app, which is a normal third-party package from this pinned app's perspective
        // and is not in the lock-task allowlist (setLockTaskPackages only includes our own
        // package) — the OS silently blocks the launch, with no visible error. That's a
        // meaningfully different thing from an emergency call: ACTION_CALL directly to a
        // number the OS recognizes as a genuine emergency number (isEmergencyNumber()) is
        // treated specially by the platform — exempt from the CALL_PHONE runtime permission,
        // and (per AOSP's LockTaskController) from lock-task foreground restrictions too, since
        // devices are required to remain able to reach emergency services regardless of any
        // kiosk/lock state. Deliberately not widening lock-task features (e.g. adding HOME) as
        // a shortcut instead — that would let a "locked" device reach the home screen and other
        // apps generally, which defeats the actual point of the lock.
        try {
            startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$EMERGENCY_NUMBER")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Emergency call launch failed", e)
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
        // 112 is the universal GSM emergency number, recognized as a genuine emergency number
        // by Android's platform emergency-number database in every region NexLock operates in
        // (including India, where 112 has been the unified emergency number since 2021) — this
        // recognition is what triggers the OS's permission and lock-task exemptions above.
        private const val EMERGENCY_NUMBER = "112"
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
