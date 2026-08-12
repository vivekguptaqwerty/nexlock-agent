package com.nexlock.agent.provisioning

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.nexlock.agent.MainActivity
import com.nexlock.agent.service.ProvisioningHandshakeWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shown immediately after Setup Wizard hands control back to this app at the end of Device
 * Owner provisioning (NexLockDeviceAdminReceiver.onProfileProvisioningComplete). There is no
 * other foreground UI at that point, so this exists purely to show progress while
 * ProvisioningHandshakeWorker completes the enrollment handshake in the background, then hands
 * off to MainActivity either way (success shows the enrolled dashboard; failure lands on the
 * manual token/OTP form as a fallback).
 */
class ProvisioningCompleteActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    ProvisioningProgressScreen()
                }
            }
        }

        lifecycleScope.launch {
            val workManager = WorkManager.getInstance(applicationContext)

            // Query the current state eagerly first, rather than only observing the Flow —
            // if the worker already finished (a real possibility: WorkManager can run it
            // near-instantly, before this Activity's onCreate even starts observing) an
            // observer that only reacts to *future* emissions could otherwise miss it.
            val alreadyFinished = workManager
                .getWorkInfosForUniqueWork(ProvisioningHandshakeWorker.WORK_NAME)
                .await()

            val finished = if (alreadyFinished.any { it.state.isFinished } || alreadyFinished.isEmpty()) {
                alreadyFinished
            } else {
                // Not finished yet — observe for the completion, but never hang forever: if
                // something (a stuck retry loop, an OEM WorkManager quirk) keeps this from
                // resolving, fall through to MainActivity anyway after a bounded wait rather
                // than leaving the user stuck on a screen that isn't telling the truth about
                // what's actually happening. The worker keeps running to completion in the
                // background regardless of what this screen does.
                withTimeoutOrNull(45_000) {
                    workManager
                        .getWorkInfosForUniqueWorkFlow(ProvisioningHandshakeWorker.WORK_NAME)
                        .first { infos -> infos.any { it.state.isFinished } || infos.isEmpty() }
                } ?: emptyList()
            }

            // Whether the handshake succeeded, failed, or timed out inconclusively, MainActivity
            // is the right next screen either way — on anything but confirmed success it still
            // offers the manual OTP form as a recovery path, and will show the enrolled
            // dashboard on next launch if the worker actually succeeded just after this timeout.
            val enrolledSuccessfully = finished.any { it.state == WorkInfo.State.SUCCEEDED }
            startActivity(
                Intent(this@ProvisioningCompleteActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(EXTRA_AUTO_ENROLLED, enrolledSuccessfully)
                }
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_AUTO_ENROLLED = "auto_enrolled"
    }
}

@Composable
private fun ProvisioningProgressScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Color(0xFF38BDF8))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "SETTING UP YOUR DEVICE",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "NexLock is completing enrollment. This only takes a moment.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp
        )
    }
}
