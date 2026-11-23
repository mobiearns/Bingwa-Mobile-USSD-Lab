package com.bingwa.mobile.relay

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------------------------------------------
 * Bingwa Mobile – Relay (two-phone mode) UI
 * Matches the approved HTML prototype. UI only: it renders RelayUiState
 * and reports user intent through RelayCallbacks. Build RelayUiState
 * from the EXISTING Relay code (connection, pairing and request state).
 * Do not change the Relay protocol or USSD engine.
 * Font: add Sora to res/font and replace AppFont.
 * ------------------------------------------------------------------ */

// ---------- Tokens (same values as the HTML) ----------
object RelayColors {
    val Bg = Color(0xFF121316)
    val Card = Color(0xFF1B1D22)
    val Card2 = Color(0xFF262A31)
    val Line = Color(0xFF343942)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFFA3ABB7)
    val Amber = Color(0xFFF5B83D)
    val Cyan = Color(0xFF38D5E8)
    val Red = Color(0xFFFF7B7B)
    val Green = Color(0xFF4ADE80)
    val OnAmber = Color(0xFF1A1400)
    val AmberDim = Color(0xFF2B2416)
}
private val AppFont = FontFamily.Default // TODO: Sora
private val CardShape = RoundedCornerShape(com.bingwa.mobile.UiDimens.CardRadius)

// ---------- Models ----------
enum class ConnectionMethod(val label: String) { HOTSPOT("Hotspot"), SMS("SMS") }
enum class Role { PRIMARY, SECONDARY }
enum class Tone { OK, WARN, BAD, OFF, BUSY, GOOD }
enum class NodeState { PENDING, BUSY, GOOD, BAD, WARN }
enum class RelayAction { TURN_ON, PAIR, DONE, RECONNECT, CANCEL, OPEN_HISTORY }

data class TrackNode(val state: NodeState, val label: String)           // 3 nodes: this phone, second phone, network
data class SecondaryPhone(val name: String, val statusLabel: String, val tone: Tone, val connectionDetail: String)

data class RelayUiState(
    val title: String,
    val body: String,
    val tone: Tone,
    val linkTone: Tone,
    val linkAnimating: Boolean,
    val method: ConnectionMethod,
    val secondary: SecondaryPhone?,            // null = nothing paired
    val actionLabel: String?,
    val action: RelayAction?,
    val track: List<TrackNode>?,               // null when no request is active/finished
    val tips: List<String>,                    // recovery tips; empty when none
    val relayEnabled: Boolean,
    val autoReconnect: Boolean,
    val role: Role,
)

sealed interface PairingUi {
    data object PickSetup : PairingUi
    data class Instructions(val steps: List<String>) : PairingUi          // steps from the real flow
    data object Waiting : PairingUi
    data class Success(val deviceName: String) : PairingUi
    data class Failed(val reason: String) : PairingUi
}

interface RelayCallbacks {
    fun onAdvancedSettings() {}
    fun onAction(action: RelayAction)
    fun onMethodChange(method: ConnectionMethod)
    fun onRelayEnabledChange(enabled: Boolean)
    fun onRoleChange(role: Role)
    fun onAutoReconnectChange(enabled: Boolean)
    fun onStartPairing(method: ConnectionMethod, phoneNumber: String?)    // phoneNumber only for SMS
    fun onCancelPairing()
    fun onPairingBack()
    fun onPairingContinue(role: Role, method: ConnectionMethod)       // role + method chosen -> instructions
    fun onPairingClose()
    fun onUnpairConfirmed()
}

private fun Tone.color() = when (this) {
    Tone.OK -> RelayColors.Green; Tone.WARN, Tone.BUSY -> RelayColors.Amber
    Tone.BAD -> RelayColors.Red; Tone.GOOD -> RelayColors.Green; Tone.OFF -> RelayColors.Muted
}
private fun NodeState.color() = when (this) {
    NodeState.PENDING -> RelayColors.Line; NodeState.BUSY, NodeState.WARN -> RelayColors.Amber
    NodeState.GOOD -> RelayColors.Green; NodeState.BAD -> RelayColors.Red
}

// ---------- Screen ----------
@Composable
fun RelayScreen(
    state: RelayUiState,
    pairing: PairingUi?,                 // non-null = pairing sheet visible
    callbacks: RelayCallbacks,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showUnpair by remember { mutableStateOf(false) }

    Column(
        modifier.fillMaxSize().background(RelayColors.Bg).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(com.bingwa.mobile.UiDimens.ScreenPadding)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                        .background(RelayColors.Card2),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = RelayColors.Text)
                }
            }
            Column(Modifier.weight(1f)) {
                RText("Relay", 26.sp, FontWeight.Bold)
                RText("Two-phone mode", 14.sp, color = RelayColors.Muted)
            }
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(RelayColors.Card2),
            ) {
                RText("\u2699", 20.sp)
            }
        }

        RCard {
            Hero(state) {
                state.action?.let { action ->
                    PrimaryButton(
                        state.actionLabel.orEmpty(),
                        emphasised = state.tone != Tone.OK && state.tone != Tone.GOOD,
                    ) { callbacks.onAction(action) }
                }
            }
        }
        state.track?.let { RCard { RequestTrack(it) } }
        if (state.tips.isNotEmpty()) RCard { Tips(state.tips) }
        state.secondary?.let {
            DeviceCard(it, state.method, onRemove = { showUnpair = true }, onChange = { showSettings = true })
        }
        RText(
            "Finished transactions are saved in History.",
            14.sp,
            color = RelayColors.Muted,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }

    if (pairing != null) PairingSheet(pairing, state.role, state.method, callbacks)
    if (showSettings) SettingsSheet(state, callbacks) { showSettings = false }
    if (showUnpair) AlertDialog(
        onDismissRequest = { showUnpair = false },
        containerColor = RelayColors.Card,
        title = { RText("Remove ${state.secondary?.name.orEmpty()}?", 20.sp, FontWeight.Bold) },
        text = { RText("Relay will stop sending requests to it. You can connect it again anytime.", color = RelayColors.Muted) },
        confirmButton = { TextButton({ showUnpair = false; callbacks.onUnpairConfirmed() }) { RText("Remove phone", color = RelayColors.Red, weight = FontWeight.SemiBold) } },
        dismissButton = { TextButton({ showUnpair = false }) { RText("Keep it", weight = FontWeight.SemiBold) } },
    )
}

// ---------- Hero: two phones + link ----------
@Composable
private fun Hero(s: RelayUiState, action: @Composable () -> Unit) {
    val paired = s.secondary != null
    val secondTone = if (!paired) Tone.OFF else if (s.linkTone == Tone.OK) Tone.OK else s.linkTone
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PhoneColumn("This phone", if (s.role == Role.PRIMARY) "Primary" else "Secondary", RelayColors.Green, false, Modifier.weight(1f))
        Column(Modifier.weight(.78f), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(22.dp))
            Wire(s.linkTone.color(), s.linkAnimating, dashedFine = s.linkTone == Tone.BAD, dim = s.linkTone == Tone.OFF)
            Spacer(Modifier.height(12.dp))
            Text(s.method.label, Modifier.clip(CircleShape).background(RelayColors.Card2).padding(horizontal = 10.dp, vertical = 3.dp),
                color = RelayColors.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = AppFont,)
        }
        PhoneColumn(if (paired) "Second phone" else "No phone yet", if (s.role == Role.PRIMARY) "Secondary" else "Primary",
            secondTone.color(), !paired, Modifier.weight(1f))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(s.tone.color()))
            Spacer(Modifier.width(7.dp))
            RText(s.title, 18.sp, FontWeight.Bold, align = TextAlign.Center)
        }
    }
    RText(s.body, 14.sp, color = RelayColors.Muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp))
    action()
}

@Composable
private fun PhoneColumn(name: String, role: String, color: Color, dashed: Boolean, modifier: Modifier) =
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PhoneIcon(color, dashed); Spacer(Modifier.height(10.dp))
        RText(name, 13.sp, FontWeight.Bold, align = TextAlign.Center)
        RText(role, 12.sp, color = RelayColors.Muted, align = TextAlign.Center)
    }

@Composable
fun PhoneIcon(color: Color, dashed: Boolean = false) {
    Canvas(Modifier.size(46.dp, 76.dp)) {
        val sw = 2.dp.toPx()
        drawRoundRect(color, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), CornerRadius(14.dp.toPx()),
            style = Stroke(sw, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        drawRoundRect(color, Offset(17.dp.toPx(), 6.dp.toPx()), Size(size.width - 34.dp.toPx(), 3.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawRoundRect(color.copy(alpha = .18f), Offset(7.dp.toPx(), 16.dp.toPx()), Size(size.width - 14.dp.toPx(), size.height - 25.dp.toPx()), CornerRadius(6.dp.toPx()))
    }
}

@Composable
private fun Wire(color: Color, animating: Boolean, dashedFine: Boolean, dim: Boolean) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(animating) {
        if (animating) {
            while (true) {
                phase.animateTo(1f, animationSpec = tween(1400, easing = LinearEasing))
                phase.snapTo(0f)
            }
        } else {
            phase.snapTo(0f)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val c = if (dim) color.copy(alpha = .5f) else color
        val dash = if (dashedFine) floatArrayOf(3.dp.toPx(), 14.dp.toPx()) else floatArrayOf(6.dp.toPx(), 4.dp.toPx())
        drawLine(c, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(dash))
        if (animating) drawCircle(color, 4.dp.toPx(), Offset(5.dp.toPx() + phase.value * (size.width - 10.dp.toPx()), size.height / 2))
    }
}

// ---------- Request track (This phone > Second phone > Network) ----------
@Composable
private fun RequestTrack(nodes: List<TrackNode>) {
    val names = listOf("This phone", "Second phone", "Network")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.take(3).forEachIndexed { i, n ->
            val c = n.state.color()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                val filled = n.state in listOf(NodeState.GOOD, NodeState.BAD, NodeState.WARN)
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (filled) c else Color.Transparent)
                    .border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
                    RText(when (n.state) { NodeState.GOOD -> "\u2713"; NodeState.BAD -> "\u2715"; NodeState.WARN -> "!"; else -> "${i + 1}" },
                        15.sp, FontWeight.Bold, color = if (filled) RelayColors.Text else c)
                }
                Spacer(Modifier.height(8.dp))
                RText(names.getOrNull(i) ?: "Step ${i + 1}", 12.sp, FontWeight.Bold, align = TextAlign.Center)
                RText(n.label.ifBlank { "\u2013" }, 11.sp, color = RelayColors.Muted, align = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Tips(tips: List<String>) {
    RText("What to try", weight = FontWeight.Bold)
    tips.forEachIndexed { i, t ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            NumberBadge(i + 1)
            RText(t, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DeviceCard(p: SecondaryPhone, method: ConnectionMethod, onRemove: () -> Unit, onChange: () -> Unit) = RCard {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RText(p.name, weight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(p.tone.color())); Spacer(Modifier.width(7.dp))
                RText(p.statusLabel, 12.sp, color = p.tone.color())
            }
        }
        TextButton(onRemove) { RText("Remove", 14.sp, FontWeight.SemiBold, color = RelayColors.Cyan) }
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = RelayColors.Line)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RText("Connected by", 14.sp, color = RelayColors.Muted)
            RText(method.label, weight = FontWeight.Bold)
            RText(p.connectionDetail, 12.sp, color = RelayColors.Muted)
        }
        TextButton(onChange) { RText("Change", 14.sp, FontWeight.SemiBold, color = RelayColors.Cyan) }
    }
}

// ---------- Pairing sheet ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingSheet(p: PairingUi, currentRole: Role, method: ConnectionMethod, cb: RelayCallbacks) {
    var number by remember { mutableStateOf("") }
    var selectedRole by remember(p) { mutableStateOf(currentRole) }
    var selectedMethod by remember(p) { mutableStateOf(method) }
    ModalBottomSheet(onDismissRequest = cb::onPairingClose, containerColor = RelayColors.Card,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.padding(horizontal = com.bingwa.mobile.UiDimens.ScreenHorizontal).padding(bottom = com.bingwa.mobile.UiDimens.SpacingLg).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            when (p) {
                PairingUi.PickSetup -> {
                    RText("Connect a phone", 22.sp, FontWeight.Bold)
                    RText("Choose how this phone will participate, then select how the two phones will communicate.",
                        13.sp, color = RelayColors.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                    RText("1. Choose this phone's role", 15.sp, FontWeight.Bold)
                    Role.entries.forEach { role ->
                        val chosen = selectedRole == role
                        SetupChoice(
                            title = if (role == Role.PRIMARY) "Primary phone" else "Secondary phone",
                            description = if (role == Role.PRIMARY)
                                "Controls the workflow and sends requests to the other phone."
                            else "Receives requests and performs the USSD work.",
                            selected = chosen,
                            onClick = { selectedRole = role }
                        )
                    }
                    RText("2. Choose connection method", 15.sp, FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
                    SetupChoice("Hotspot", "Fast local connection using the phones' Wi-Fi hotspot.",
                        selectedMethod == ConnectionMethod.HOTSPOT) { selectedMethod = ConnectionMethod.HOTSPOT }
                    SetupChoice("SMS", "Pair using text messages when a local hotspot is not suitable. Carrier charges may apply.",
                        selectedMethod == ConnectionMethod.SMS) { selectedMethod = ConnectionMethod.SMS }
                    PrimaryButton("Continue", true) { cb.onPairingContinue(selectedRole, selectedMethod) }
                    SecondaryButton("Cancel", cb::onPairingClose)
                }
                is PairingUi.Instructions -> {
                    RText("Connect with ${method.label}", 20.sp, FontWeight.Bold)
                    RText("Three quick steps.", color = RelayColors.Muted, modifier = Modifier.padding(bottom = 18.dp))
                    p.steps.forEachIndexed { i, s -> Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { NumberBadge(i + 1); RText(s) } }
                    if (method == ConnectionMethod.SMS) {
                        RText("Second phone number", 14.sp, color = RelayColors.Muted)
                        OutlinedTextField(number, { number = it }, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                            placeholder = { RText("07XX XXX XXX", color = RelayColors.Muted) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(14.dp), singleLine = true)
                    }
                    PrimaryButton(if (method == ConnectionMethod.SMS) "Send pairing text" else "I\u2019m ready", true) {
                        cb.onStartPairing(method, number.takeIf { method == ConnectionMethod.SMS })
                    }
                    SecondaryButton("Back", cb::onPairingBack)
                }
                PairingUi.Waiting -> {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PhoneIcon(RelayColors.Amber); Spacer(Modifier.height(16.dp))
                        RText("Waiting for second phone\u2026", 20.sp, FontWeight.Bold, align = TextAlign.Center)
                        RText(if (method == ConnectionMethod.SMS) "Waiting for the second phone to reply to the pairing text." else "Waiting for the second phone to join the hotspot.",
                            color = RelayColors.Muted, align = TextAlign.Center)
                    }
                    SecondaryButton("Cancel", cb::onCancelPairing)
                }
                is PairingUi.Success -> {
                    ResultBadge("\u2713", RelayColors.Green, "Pairing details saved", "${p.deviceName} is configured for ${method.label}. The live link is verified only after a relay request is acknowledged.")
                    PrimaryButton("Done", true, cb::onPairingClose)
                }
                is PairingUi.Failed -> {
                    ResultBadge("\u2715", RelayColors.Red, "Couldn\u2019t connect", p.reason)
                    PrimaryButton("Try again", true, cb::onPairingBack)
                    SecondaryButton("Close", cb::onPairingClose)
                }
            }
        }
    }
}

@Composable
private fun SetupChoice(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(15.dp))
            .background(if (selected) RelayColors.AmberDim else RelayColors.Card2)
            .border(1.25.dp, if (selected) RelayColors.Amber else RelayColors.Line, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (selected) RelayColors.Amber else RelayColors.Muted, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(RelayColors.Amber))
        }
        Column(Modifier.weight(1f)) {
            RText(title, 14.sp, FontWeight.Bold, color = if (selected) RelayColors.Amber else RelayColors.Text)
            RText(description, 12.sp, color = RelayColors.Muted)
        }
    }
}

@Composable
private fun SetupMiniStep(number: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(RelayColors.Card2).padding(horizontal = 7.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        NumberBadge(number.toInt())
        RText(label, 10.sp, FontWeight.SemiBold, color = RelayColors.Muted)
    }
}

// ---------- Settings sheet ----------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(s: RelayUiState, cb: RelayCallbacks, onClose: () -> Unit) =
    ModalBottomSheet(onDismissRequest = onClose, containerColor = RelayColors.Card, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.padding(horizontal = com.bingwa.mobile.UiDimens.ScreenHorizontal).padding(bottom = com.bingwa.mobile.UiDimens.Spacing2xl).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            RText("Relay settings", 22.sp, FontWeight.Bold)
            RText("Control how this two-phone connection behaves. Phone pairing is handled separately from these settings.", 13.sp, color = RelayColors.Muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            ToggleRow("Relay service", "Enable or pause two-phone USSD relay", s.relayEnabled, cb::onRelayEnabledChange)
            RText("Connection method", weight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(RelayColors.Card2).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ConnectionMethod.entries.forEach { m ->
                    val on = m == s.method
                    TextButton({ cb.onMethodChange(m) }, Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (on) RelayColors.Amber else Color.Transparent)) {
                        RText(m.label, weight = FontWeight.SemiBold, color = if (on) RelayColors.OnAmber else RelayColors.Muted)
                    }
                }
            }
            RText("Changing the method means reconnecting the second phone.", 14.sp, color = RelayColors.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            ToggleRow("Reconnect automatically", "Try again if the link drops", s.autoReconnect, cb::onAutoReconnectChange)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Advanced setup · QR, IP, prefix and PIN") {
                onClose()
                cb.onAdvancedSettings()
            }
        }
    }

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(
        Modifier.fillMaxWidth().padding(vertical = com.bingwa.mobile.UiDimens.SpacingMd).heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RText(title, weight = FontWeight.Bold)
            RText(sub, 14.sp, color = RelayColors.Muted)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = RelayColors.Cyan,
                checkedThumbColor = RelayColors.Amber,
                checkedBorderColor = RelayColors.Amber,
                uncheckedTrackColor = RelayColors.Card2,
                uncheckedThumbColor = RelayColors.Muted
            )
        )
    }

// ---------- Shared bits ----------
@Composable
private fun RCard(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(CardShape).background(RelayColors.Card)
        .border(1.25.dp, RelayColors.Line, CardShape).padding(horizontal = 16.dp, vertical = 16.dp), content = content)

@Composable
private fun RText(text: String, size: androidx.compose.ui.unit.TextUnit = 16.sp, weight: FontWeight = FontWeight.Normal,
                  color: Color = RelayColors.Text, align: TextAlign? = null, modifier: Modifier = Modifier) =
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = AppFont, textAlign = align, lineHeight = size * 1.4f)

@Composable
private fun PrimaryButton(label: String, emphasised: Boolean, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().padding(top = com.bingwa.mobile.UiDimens.SpacingSm).heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (emphasised) RelayColors.Amber else RelayColors.Card2,
            contentColor = if (emphasised) RelayColors.OnAmber else RelayColors.Text),
        border = if (emphasised) null else BorderStroke(1.dp, RelayColors.Line)) { RText(label, weight = FontWeight.SemiBold, color = if (emphasised) RelayColors.OnAmber else RelayColors.Text) }

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) = PrimaryButton(label, false, onClick)

@Composable
private fun NumberBadge(n: Int) = Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(CircleShape).background(RelayColors.Amber), contentAlignment = Alignment.Center) {
    RText("$n", 14.sp, FontWeight.Bold, color = RelayColors.OnAmber)
}

@Composable
private fun ResultBadge(icon: String, color: Color, title: String, body: String) =
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { RText(icon, 30.sp, FontWeight.Bold, color = RelayColors.Text) }
        Spacer(Modifier.height(12.dp)); RText(title, 20.sp, FontWeight.Bold, align = TextAlign.Center)
        RText(body, color = RelayColors.Muted, align = TextAlign.Center)
    }