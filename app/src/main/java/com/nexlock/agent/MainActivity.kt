package com.nexlock.agent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexlock.agent.data.storage.LockStateManager
import com.nexlock.agent.kiosk.KioskLockActivity
import com.nexlock.agent.provisioning.TermsAcceptanceActivity
import com.nexlock.agent.ui.AgentViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Closes the "tap the app icon while locked" escape path — if a LOCK command is
        // active, redirect straight to the kiosk screen instead of showing the normal
        // dashboard/enrollment UI.
        if (LockStateManager(this).isLocked()) {
            redirectToKioskLock()
            return
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Matches the kiosk lock screen's brand color
                ) {
                    AgentMainScreen(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (LockStateManager(this).isLocked()) {
            redirectToKioskLock()
        }
        // Picks up acceptance recorded by TermsAcceptanceActivity, which this Activity may have
        // just launched and returned from.
        viewModel.refreshTermsAcceptedStatus()
    }

    private fun redirectToKioskLock() {
        startActivity(Intent(this, KioskLockActivity::class.java))
        finish()
    }
}

/**
 * This screen is what the *customer* sees on their own financed phone — deliberately minimal
 * and free of anything technical (no server URLs, device tokens/IDs, raw command-pipeline
 * state, or a way to reset enrollment). Heartbeat and command sync still happen automatically
 * (AgentViewModel's init block + HeartbeatScheduler running in the background) — there's just
 * nothing left here for the customer to manually trigger or break.
 *
 * The enrollment form below only appears pre-enrollment, which in practice means a dealer doing
 * a manual fallback at point of sale (the primary path is automatic, via QR-based Device Owner
 * provisioning — see ProvisioningHandshakeWorker) — a customer holding an already-provisioned
 * phone will only ever see the "protected" state.
 *
 * There is deliberately no in-app "set up Device Owner myself" button here: Android only allows
 * an app to request Device Owner via ACTION_PROVISION_MANAGED_DEVICE while Setup Wizard hasn't
 * completed yet, but sideloading this app at all requires Setup Wizard to already be done — the
 * two conditions can never both hold, so no on-device UI can close that gap. The only working
 * fallback for a device whose QR provisioning fails is `adb shell dpm set-device-owner`, which
 * needs a computer and isn't something this screen can offer a button for.
 */
@Composable
fun AgentMainScreen(viewModel: AgentViewModel) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val needsTermsGate = !viewModel.isEnrolled && !viewModel.isTermsAccepted

    // Auto-launches once per composition when the gate is needed — MainActivity.onResume()
    // re-checks isTermsAccepted when this Activity resumes after TermsAcceptanceActivity
    // finishes, which recomposes this away from the gate and shows EnrollmentCard instead.
    LaunchedEffect(needsTermsGate) {
        if (needsTermsGate) {
            TermsAcceptanceActivity.launchForManualFlow(context)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "NexLock",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        when {
            needsTermsGate -> CircularProgressIndicator(color = Color(0xFF38BDF8))
            !viewModel.isEnrolled -> EnrollmentCard(viewModel)
            else -> ProtectedStatusCard(lastSyncedAt = viewModel.lastHeartbeatTime)
        }
    }
}

@Composable
private fun ProtectedStatusCard(lastSyncedAt: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF166534), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✓",
                    color = Color(0xFF4ADE80),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "This device is protected",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "NexLock is running in the background. No action needed.",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            if (lastSyncedAt != "Not sent yet") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Last synced at $lastSyncedAt",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EnrollmentCard(viewModel: AgentViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Set Up This Device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Enter the enrollment code or 6-digit OTP provided by your dealer.",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            OutlinedTextField(
                value = viewModel.enrollmentTokenInput,
                onValueChange = { viewModel.enrollmentTokenInput = it },
                label = { Text("Enrollment Code") },
                placeholder = { Text("e.g. ENR-89410401-4810...", color = Color(0xFF475569)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF475569),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFF38BDF8),
                    unfocusedLabelColor = Color(0xFF94A3B8)
                ),
                singleLine = true
            )

            OutlinedTextField(
                value = viewModel.otpInput,
                onValueChange = { viewModel.otpInput = it },
                label = { Text("Or 6-Digit OTP") },
                placeholder = { Text("e.g. 849201", color = Color(0xFF475569)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF475569),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedLabelColor = Color(0xFF38BDF8),
                    unfocusedLabelColor = Color(0xFF94A3B8)
                ),
                singleLine = true
            )

            viewModel.enrollmentError?.let { err ->
                Text(text = err, color = Color(0xFFF87171), fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            }

            Button(
                onClick = { viewModel.performHandshake() },
                enabled = !viewModel.isEnrolling,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                if (viewModel.isEnrolling) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                } else {
                    Text("Activate Device", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
