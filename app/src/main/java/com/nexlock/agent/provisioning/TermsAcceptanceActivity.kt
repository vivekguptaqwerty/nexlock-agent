package com.nexlock.agent.provisioning

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexlock.agent.data.model.LegalContentData
import com.nexlock.agent.data.repository.DeviceRepository
import com.nexlock.agent.data.storage.TokenManager
import com.nexlock.agent.service.ProvisioningHandshakeWorker

/**
 * Mandatory consent gate shown before enrollment actually completes — required by both entry
 * points into this app:
 *
 * - QR flow: NexLockDeviceAdminReceiver.onProfileProvisioningComplete launches this instead of
 *   enqueueing ProvisioningHandshakeWorker directly, passing the enrollmentToken/otp/serverUrl
 *   through as extras. Accepting here is what actually triggers the handshake.
 * - Manual flow: AgentViewModel/MainActivity launches this before showing the OTP entry form
 *   (no enrollmentToken/otp extras in this case — acceptance is just recorded locally via
 *   TokenManager.setTermsAccepted, then performHandshake() reads it back and reports it to the
 *   backend whenever the OTP is actually submitted).
 *
 * There's deliberately no "Decline" path that undoes anything — this is a formality confirming
 * terms the customer already agreed to as part of the financing purchase, not a true opt-out
 * point (the device is already financed and, in the QR case, already Device Owner by the time
 * this screen shows).
 */
class TermsAcceptanceActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val enrollmentToken = intent.getStringExtra(EXTRA_ENROLLMENT_TOKEN)
        val otp = intent.getStringExtra(EXTRA_OTP)
        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        val isAutoFlow = intent.getBooleanExtra(EXTRA_AUTO_FLOW, false)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
                    TermsAcceptanceScreen(
                        onAccept = {
                            if (isAutoFlow) {
                                ProvisioningHandshakeWorker.enqueue(
                                    context = this@TermsAcceptanceActivity,
                                    enrollmentToken = enrollmentToken,
                                    otp = otp,
                                    serverUrl = serverUrl,
                                    termsAccepted = true
                                )
                                startActivity(
                                    Intent(this@TermsAcceptanceActivity, ProvisioningCompleteActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            } else {
                                TokenManager(this@TermsAcceptanceActivity).setTermsAccepted(true)
                            }
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ENROLLMENT_TOKEN = "enrollment_token"
        private const val EXTRA_OTP = "otp"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_AUTO_FLOW = "auto_flow"

        /** QR flow — acceptance triggers the handshake directly using these extras. */
        fun launchForAutoFlow(context: android.content.Context, enrollmentToken: String?, otp: String?, serverUrl: String?) {
            val intent = Intent(context, TermsAcceptanceActivity::class.java).apply {
                putExtra(EXTRA_ENROLLMENT_TOKEN, enrollmentToken)
                putExtra(EXTRA_OTP, otp)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_AUTO_FLOW, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /** Manual flow — acceptance is just recorded locally; MainActivity reveals the OTP form next. */
        fun launchForManualFlow(context: android.content.Context) {
            val intent = Intent(context, TermsAcceptanceActivity::class.java).apply {
                putExtra(EXTRA_AUTO_FLOW, false)
            }
            context.startActivity(intent)
        }
    }
}

private enum class LegalTab { PRIVACY, TERMS }

@Composable
private fun TermsAcceptanceScreen(onAccept: () -> Unit) {
    var selectedTab by remember { mutableStateOf(LegalTab.PRIVACY) }
    var privacyContent by remember { mutableStateOf<LegalContentData?>(null) }
    var termsContent by remember { mutableStateOf<LegalContentData?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val repository = DeviceRepository()
        val privacyResult = repository.getPrivacyPolicy()
        val termsResult = repository.getTerms()
        privacyContent = privacyResult.getOrNull()
        termsContent = termsResult.getOrNull()
        loadError = privacyResult.isFailure && termsResult.isFailure
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            text = "Before you continue",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Please review and accept the following to complete setup.",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        TabRow(
            selectedTabIndex = if (selectedTab == LegalTab.PRIVACY) 0 else 1,
            containerColor = Color(0xFF1E293B),
            contentColor = Color(0xFF38BDF8)
        ) {
            Tab(
                selected = selectedTab == LegalTab.PRIVACY,
                onClick = { selectedTab = LegalTab.PRIVACY },
                text = { Text("Privacy Policy") }
            )
            Tab(
                selected = selectedTab == LegalTab.TERMS,
                onClick = { selectedTab = LegalTab.TERMS },
                text = { Text("Terms & Conditions") }
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp, bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(14.dp)
        ) {
            val scrollState = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                when {
                    isLoading -> CircularProgressIndicator(
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.align(Alignment.Center)
                    )
                    loadError -> Text(
                        text = "Couldn't load this page. Please check your internet connection and reopen the app.",
                        color = Color(0xFFF87171),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> {
                        val doc = if (selectedTab == LegalTab.PRIVACY) privacyContent else termsContent
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            Text(
                                text = doc?.content ?: "This content is not available right now.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            }
        }

        Button(
            onClick = onAccept,
            enabled = !isLoading && !loadError,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Text("I Agree — Continue", fontWeight = FontWeight.Bold)
        }
    }
}
