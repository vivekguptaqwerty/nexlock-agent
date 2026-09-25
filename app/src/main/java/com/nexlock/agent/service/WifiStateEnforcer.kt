package com.nexlock.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Registered dynamically from HeartbeatForegroundService (not declared in the manifest — most
 * implicit system broadcasts, WIFI_STATE_CHANGED_ACTION included, can't be received that way
 * since Android 8). The service's own lifecycle is what keeps this alive; HeartbeatForegroundService
 * already exists specifically because it sits much higher in Android's process-importance model
 * than a plain broadcast receiver or periodic worker would.
 *
 * Fires the instant the OS reports a Wi-Fi state transition (near-immediate, not on the
 * foreground service's own 15-minute polling cadence) — if the customer flips Wi-Fi back on via
 * Quick Settings while WifiBlockManager considers it blocked, this disables it again right away.
 */
class WifiStateEnforcer : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return

        val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
        val turnedOn = wifiState == WifiManager.WIFI_STATE_ENABLED || wifiState == WifiManager.WIFI_STATE_ENABLING
        if (turnedOn && WifiBlockManager.isBlocked(context)) {
            WifiBlockManager.disableWifi(context)
        }
    }

    companion object {
        fun register(context: Context): WifiStateEnforcer {
            val receiver = WifiStateEnforcer()
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // NOT_EXPORTED is correct even though this is a system broadcast — it only
                // restricts which apps can send a matching Intent to this receiver directly,
                // which the OS itself is never affected by.
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            return receiver
        }
    }
}
