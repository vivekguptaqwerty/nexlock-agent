package com.nexlock.agent

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexlock.agent.ui.AgentViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0F172A) // Dark MDM theme
                ) {
                    AgentMainScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun AgentMainScreen(viewModel: AgentViewModel) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Title Header
        Text(
            text = "NEXLOCK AGENT",
            color = Color(0xFF38BDF8),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
        Text(
            text = "Android Enterprise Agent Node • Command Pipeline Engine",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // --------------------------------------------------------------------------------
        // PHASE 3.0 DEVICE OWNER POC — TEMPORARY TEST SCAFFOLDING, NOT PRODUCTION CODE.
        // Manually validates that a Device-Owner-granted lockTaskAllowed policy actually
        // lets this app pin/unpin itself in a non-escapable full-screen state, independent
        // of the backend's FCM/command pipeline (which doesn't call this yet). Remove once
        // the POC is validated and real command-triggered LockTask logic is wired in.
        // --------------------------------------------------------------------------------
        val activity = LocalContext.current as? Activity
        var lockTaskPocStatus by remember { mutableStateOf("Not pinned") }

        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF422006)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("DEVICE OWNER POC — LOCKTASK TEST", color = Color(0xFFFBBF24), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Temporary scaffolding for Phase 3.0 validation only.",
                    color = Color(0xFFFCD34D),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Text("Status: $lockTaskPocStatus", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            try {
                                activity?.startLockTask()
                                lockTaskPocStatus = "PINNED (startLockTask succeeded)"
                            } catch (e: Exception) {
                                lockTaskPocStatus = "FAILED: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
                    ) {
                        Text("LOCK (pin)", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            try {
                                activity?.stopLockTask()
                                lockTaskPocStatus = "UNPINNED (stopLockTask succeeded)"
                            } catch (e: Exception) {
                                lockTaskPocStatus = "FAILED: ${e.message}"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D))
                    ) {
                        Text("UNLOCK (unpin)", fontSize = 12.sp)
                    }
                }
            }
        }

        // Server Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("BACKEND SERVER URL", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = viewModel.serverUrlInput,
                    onValueChange = { viewModel.serverUrlInput = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    singleLine = true
                )
            }
        }

        if (!viewModel.isEnrolled) {
            // ENROLLMENT HANDSHAKE CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("DEVICE ENROLLMENT HANDSHAKE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Enter the Enrollment Token or 6-digit OTP generated by the Dealer App.", color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.padding(bottom = 16.dp))

                    Text("ENROLLMENT TOKEN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = viewModel.enrollmentTokenInput,
                        onValueChange = { viewModel.enrollmentTokenInput = it },
                        placeholder = { Text("e.g. ENR-89410401-4810...", color = Color(0xFF475569)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )

                    Text("OR 6-DIGIT OTP", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = viewModel.otpInput,
                        onValueChange = { viewModel.otpInput = it },
                        placeholder = { Text("e.g. 849201", color = Color(0xFF475569)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
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
                            Text("PERFORM ENROLLMENT HANDSHAKE", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // ENROLLED TELEMETRY & COMMAND CARD
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("AGENT ENROLLED & ACTIVE", color = Color(0xFF4ADE80), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF166534), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("ONLINE", color = Color(0xFF4ADE80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("DEVICE ID", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(viewModel.deviceId, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 12.dp))

                    Text("DEVICE TOKEN (ISSUED JWT)", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(viewModel.deviceToken.take(40) + "...", color = Color(0xFF94A3B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 16.dp))

                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 12.dp))

                    // TELEMETRY SECTION
                    Text("HEARTBEAT TELEMETRY ENGINE", color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Heartbeat Sent:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text(viewModel.lastHeartbeatTime, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Telemetry Status:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text(viewModel.heartbeatStatus, color = Color(0xFF38BDF8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { viewModel.sendManualHeartbeat() },
                        enabled = !viewModel.isSendingHeartbeat,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1))
                    ) {
                        if (viewModel.isSendingHeartbeat) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                        } else {
                            Text("SEND MANUAL HEARTBEAT PING", fontSize = 12.sp)
                        }
                    }

                    Divider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 14.dp))

                    // COMMAND POLLING & EXECUTION PIPELINE SECTION
                    Text("MDM COMMAND POLLING & DISPATCHER", color = Color(0xFFA855F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Last Executed Command:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text(viewModel.lastExecutedCommand ?: "None", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Commands Executed:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text("${viewModel.totalCommandsProcessed}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Polling Engine Status:", color = Color(0xFF94A3B8), fontSize = 13.sp)
                        Text(viewModel.commandPollingStatus, color = Color(0xFFA855F7), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { viewModel.pollAndExecuteCommands() },
                        enabled = !viewModel.isPollingCommands,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7E22CE))
                    ) {
                        if (viewModel.isPollingCommands) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        } else {
                            Text("POLL & EXECUTE PENDING COMMANDS NOW")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { viewModel.clearEnrollment() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
                    ) {
                        Text("RESET AGENT ENROLLMENT")
                    }
                }
            }
        }
    }
}
