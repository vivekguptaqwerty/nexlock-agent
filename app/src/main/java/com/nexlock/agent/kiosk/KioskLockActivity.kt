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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexlock.agent.data.storage.LockStateManager

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

        val lockStateManager = LockStateManager(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    KioskLockedScreen(
                        reason = lockStateManager.getLockReason(),
                        dealerName = lockStateManager.getDealerName(),
                        dealerPhone = lockStateManager.getDealerPhone(),
                        dealerEmail = lockStateManager.getDealerEmail(),
                        supportEmail = lockStateManager.getSupportEmail(),
                        supportPhone = lockStateManager.getSupportPhone()
                    )
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
private fun KioskLockedScreen(
    reason: String?,
    dealerName: String?,
    dealerPhone: String?,
    dealerEmail: String?,
    supportEmail: String?,
    supportPhone: String?
) {
    val scrollState = rememberScrollState()
    val hasDealerContact = !dealerPhone.isNullOrBlank() || !dealerEmail.isNullOrBlank()
    val hasSupportContact = !supportPhone.isNullOrBlank() || !supportEmail.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
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
            text = reason ?: "This device has been locked due to an overdue EMI payment.",
            color = Color(0xFF94A3B8),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )

        if (hasDealerContact) {
            Spacer(modifier = Modifier.height(24.dp))
            InfoCard(title = dealerName ?: "Your Dealer") {
                dealerPhone?.takeIf { it.isNotBlank() }?.let { ContactRow(label = "Phone", value = it) }
                dealerEmail?.takeIf { it.isNotBlank() }?.let { ContactRow(label = "Email", value = it) }
            }
        }

        if (hasSupportContact) {
            Spacer(modifier = Modifier.height(12.dp))
            InfoCard(title = "Payment already done? Dealer not responding?") {
                Text(
                    text = "Contact NexLock support directly:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                supportPhone?.takeIf { it.isNotBlank() }?.let { ContactRow(label = "Phone", value = it) }
                supportEmail?.takeIf { it.isNotBlank() }?.let { ContactRow(label = "Email", value = it) }
            }
        }

    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
private fun ContactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(text = value, color = Color(0xFFE2E8F0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
