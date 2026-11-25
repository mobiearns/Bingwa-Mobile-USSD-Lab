package com.bingwa.mobile.experimental

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.bingwa.mobile.C
import com.bingwa.mobile.OFFER_SIM_USE_GENERAL
import com.bingwa.mobile.SettingsTopBar
import com.bingwa.mobile.UiDimens

@Composable
fun ExperimentalInternalUssdScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedSim by remember { mutableIntStateOf(OFFER_SIM_USE_GENERAL) }
    var probeReport by remember { mutableStateOf<ExperimentalProbeReport?>(null) }
    var execResult by remember { mutableStateOf<ExperimentalExecutionResult?>(null) }
    var running by remember { mutableStateOf(false) }

    val simChoices = remember(selectedSim, execResult?.executedAtMs) {
        InternalUssdHandleExperiment.buildSimChoices(context)
    }

    LaunchedEffect(selectedSim) {
        probeReport = InternalUssdHandleExperiment.probeEnvironment(context, selectedSim)
    }

    fun copyToClipboard(label: String, content: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun buildCombinedReport(): String = buildString {
        probeReport?.let { appendLine(it.formattedReport) }
        if (probeReport != null && execResult != null) appendLine()
        execResult?.let { appendLine(it.rawDiagnosticReport) }
    }.ifBlank { "Run a probe or handleUssdRequest test first." }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg)
            .navigationBarsPadding()
    ) {
        SettingsTopBar(
            title = "ITelephony Experiment",
            subtitle = "Isolated ResultReceiver probe for *180*5*2*0790993046# (read-only)",
            onBack = onBack
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = UiDimens.ScreenHorizontal,
                end = UiDimens.ScreenHorizontal,
                top = 4.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.card,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, C.cyan.copy(alpha = 0.35f))
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Science, contentDescription = null, tint = C.cyan, modifier = Modifier.size(18.dp))
                            Text(
                                "ISOLATED EXPERIMENTAL HARNESS",
                                color = C.cyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Text(
                            "Tests Android's internal ITelephony.handleUssdRequest() + custom ResultReceiver directly with $EXPERIMENTAL_LIVE_MENU_USSD. Uses no root, no Accessibility, no overlay, no popup dialer, and sends no reply. Production USSD & Simple Signature flows are untouched.",
                            color = C.t2,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            simChoices.forEach { (simVal, simLabel) ->
                                FilterChip(
                                    selected = selectedSim == simVal,
                                    enabled = !running,
                                    onClick = { selectedSim = simVal },
                                    label = { Text(simLabel, fontSize = 11.sp, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = C.cyan.copy(alpha = 0.16f),
                                        selectedLabelColor = C.cyan
                                    )
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    probeReport = InternalUssdHandleExperiment.probeEnvironment(context, selectedSim)
                                },
                                enabled = !running,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Bolt, contentDescription = null, tint = C.cyan, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(4.dp))
                                Text("Probe Binder", color = C.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    if (running) return@Button
                                    running = true
                                    probeReport = InternalUssdHandleExperiment.probeEnvironment(context, selectedSim)
                                    InternalUssdHandleExperiment.executeReadOnlyHandleUssdRequest(
                                        context = context,
                                        requestedSimSelection = selectedSim
                                    ) { result ->
                                        running = false
                                        execResult = result
                                    }
                                },
                                enabled = !running,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = C.amber,
                                    contentColor = Color.Black
                                )
                            ) {
                                if (running) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.Black,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    if (running) "Waiting…" else "Run Test",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { copyToClipboard("ITelephony experiment report", buildCombinedReport()) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, tint = C.t1, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Copy Full Experiment Report", color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            probeReport?.let { probe ->
                item {
                    val isBlocked = probe.hiddenApiPolicy.enforcementLikelyActive || !probe.callPhoneGranted
                    val accent = if (probe.discoveredOverloads.isNotEmpty()) C.green else if (isBlocked) C.amber else C.red
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = C.card,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "ANDROID 12 HIDDEN-API & BINDER PROBE",
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            Text(probe.overallVerdict, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                            ProbeLine("Device", probe.deviceSummary)
                            ProbeLine("Permissions", "CALL_PHONE=${probe.callPhoneGranted} · READ_PHONE_STATE=${probe.readPhoneStateGranted}")
                            ProbeLine("Hidden API policy", probe.hiddenApiPolicy.summary)
                            ProbeLine("Target SIM", probe.simDescription)
                            ProbeLine("Path A (getITelephony)", probe.getITelephonyStatus)
                            ProbeLine("Path B (ServiceManager)", probe.serviceManagerStatus)
                            ProbeLine(
                                "handleUssdRequest overloads",
                                if (probe.discoveredOverloads.isEmpty()) "none" else probe.discoveredOverloads.joinToString("\n")
                            )
                            ProbeLine("UssdResponse class", probe.ussdResponseInspection)
                        }
                    }
                }
            }

            execResult?.let { res ->
                item {
                    val hasText = !res.extractedPayload?.bestExtractedText.isNullOrBlank()
                    val accent = when {
                        hasText && res.parsedMenuOptions.isNotEmpty() -> C.green
                        hasText -> C.cyan
                        res.timedOut || res.exceptionType != null || (res.resultCode ?: 0) < 0 -> C.red
                        else -> C.amber
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = C.card,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "RESULTRECEIVER EXECUTION OUTPUT",
                                color = accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
                            )
                            ProbeLine("Binder path", res.acquisitionPathUsed)
                            ProbeLine("Overload invoked", res.overloadInvoked)
                            ProbeLine("Request dispatched", "${res.requestStarted} · elapsed=${res.elapsedMs}ms · timedOut=${res.timedOut}")
                            ProbeLine("resultCode", res.resultCodeLabel)
                            if (res.exceptionType != null) {
                                ProbeLine("Exception", "${res.exceptionType}: ${res.exceptionMessage ?: "none"}")
                                if (!res.causeChain.isNullOrBlank()) {
                                    ProbeLine("Cause chain", res.causeChain)
                                }
                            }
                            res.extractedPayload?.let { payload ->
                                ProbeLine("Bundle keys", payload.bundleSummary)
                                ProbeLine("UssdResponse class", payload.rawUssdResponseClass ?: "none")
                                ProbeLine("getUssdRequest()", payload.ussdRequestText ?: "null")
                                ProbeLine("getReturnMessage()", payload.returnMessageText ?: "null")
                                ProbeLine("Parcel fallback message", payload.parcelFallbackMessage ?: "null")
                                if (payload.extractionNotes.isNotEmpty()) {
                                    ProbeLine("Decoder notes", payload.extractionNotes.joinToString("\n"))
                                }

                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "CAPTURED RAW RESPONSE (${payload.bestExtractedText.length} CHARS)",
                                    color = C.cyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = C.bg,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, C.border)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = payload.bestExtractedText.ifBlank {
                                                "[No message text received in ResultReceiver Bundle]"
                                            },
                                            modifier = Modifier.padding(10.dp),
                                            color = if (payload.bestExtractedText.isNotBlank()) C.t1 else C.t3,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                            }
                            if (res.parsedMenuOptions.isNotEmpty()) {
                                Text(
                                    "PARSED MENU OPTIONS (${res.parsedMenuOptions.size})",
                                    color = C.green,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                res.parsedMenuOptions.forEach { (num, label) ->
                                    Text(
                                        "$num. $label",
                                        color = C.t1,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = C.card,
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, C.border)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "INFINIX X669 NON-ROOT ADB TEST PROCEDURE",
                            color = C.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            "1. Run 'Probe Binder' & 'Run Test' BEFORE ADB changes to verify baseline Android 12 enforcement (targetSdk=36).\n" +
                                "2. Connect Infinix X669 via USB debugging and relax hidden API checks without root:\n" +
                                "$ADB_RELAX_HIDDEN_API_CMD\n" +
                                "3. Re-open this screen, tap 'Run Test', and inspect resultCode + UssdResponse text. Never reply to any menu.\n" +
                                "4. Optional RIL trace: $ADB_RADIO_LOGCAT_CMD\n" +
                                "5. Revert after testing:\n$ADB_REVERT_HIDDEN_API_CMD",
                            color = C.t2,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { copyToClipboard("ADB relax command", ADB_RELAX_HIDDEN_API_CMD) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Copy Relax ADB", color = C.amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { copyToClipboard("ADB revert command", ADB_REVERT_HIDDEN_API_CMD) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Copy Revert ADB", color = C.t2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProbeLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = C.t1, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
    }
}
