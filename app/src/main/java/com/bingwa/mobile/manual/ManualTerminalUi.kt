@file:OptIn(ExperimentalMaterial3Api::class)

package com.bingwa.mobile.manual

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import com.bingwa.mobile.C
import com.bingwa.mobile.OFFER_SIM_USE_GENERAL
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_1
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ───────────────────────── Theme ───────────────────────── */

private object BingwaColors {
    val Bg get() = C.bg
    val Card get() = C.card
    val Line get() = C.border
    val Text get() = C.t1
    val Muted get() = C.t2
    val Amber get() = C.amber
    val AmberSoft get() = C.amberDim
    val Cyan get() = C.cyan
    val Green get() = C.green
    val Red get() = C.red
    val Terminal get() = C.surface
    val Ticket get() = C.cardHi
    val TicketMuted get() = C.t2
    val Disabled get() = C.cardHi
    val DisabledText get() = C.t3
    val Power get() = C.green
}

private val Mono = FontFamily.Monospace

/**
 * Mirrors the uploaded ManualTerminalScreen reference: group the Kenyan phone
 * number visually while keeping the underlying value digits-only for execution.
 */
private val ManualPhoneGroupTransformation = androidx.compose.ui.text.input.VisualTransformation { text ->
    val raw = text.text
    val grouped = buildString {
        raw.forEachIndexed { index, digit ->
            if (index == 4 || index == 7) append(' ')
            append(digit)
        }
    }
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(grouped),
        object : androidx.compose.ui.text.input.OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (if (offset > 4) 1 else 0) + (if (offset > 7) 1 else 0)

            override fun transformedToOriginal(offset: Int): Int =
                (if (offset <= 4) offset else if (offset <= 8) offset - 1 else offset - 2)
                    .coerceIn(0, raw.length)
        }
    )
}

/* ───────────────────────── Model ───────────────────────── */

data class Offer(
    val name: String,
    val priceKes: Int,
    val ussdPrefix: String,
    val advanced: Boolean = true,
    val offerId: Int = -1,
    val simSelection: Int = USSD_SIM_SELECTION_SLOT_1,
)

private fun normalizeManualSimSelection(selection: Int): Int =
    if (selection == OFFER_SIM_USE_GENERAL) USSD_SIM_SELECTION_SLOT_1 else selection

enum class RunPhase { Idle, Preparing, WaitingCarrier, Done, Failed, Pending }

data class ManualRequest(
    val phone: String,          // local format, 10 digits, e.g. 0712345678
    val offer: Offer,
    val advanced: Boolean,
    val simSelection: Int = offer.simSelection,
)

sealed interface ExecResult {
    data object Success : ExecResult
    data class Failure(val reason: String) : ExecResult
    data class Pending(val reason: String) : ExecResult
}

/**
 * Plug your real execution engine in here. The UI only needs these two calls,
 * it does not change how execution works.
 */
interface ManualGateway {
    suspend fun execute(request: ManualRequest, onCarrierWaiting: () -> Unit): ExecResult
    suspend fun schedule(request: ManualRequest, atEpochMillis: Long): Boolean
}

data class ManualUiState(
    val offers: List<Offer> = emptyList(),
    val selected: Int = 0,
    val input: String = "",
    val advanced: Boolean = true,
    val simSelection: Int = USSD_SIM_SELECTION_SLOT_1,
    val phase: RunPhase = RunPhase.Idle,
    val carrierSeconds: Int = 0,
    val statusLine: String = "",
    val scheduleOpen: Boolean = false,
    val scheduleMessage: String? = null,
) {
    val phone: String get() = normalizePhone(input)
    val offer: Offer? get() = offers.getOrNull(selected)
    val running: Boolean get() = phase == RunPhase.Preparing || phase == RunPhase.WaitingCarrier
    val validity: PhoneValidity get() = validatePhone(phone)
    val canAct: Boolean get() = validity is PhoneValidity.Valid && offer != null && !running
}

sealed interface PhoneValidity {
    data object Empty : PhoneValidity
    data class Incomplete(val missing: Int) : PhoneValidity
    data object NotKenyan : PhoneValidity
    data class Valid(val pretty: String) : PhoneValidity
}

/** Accepts 07XXXXXXXX, 01XXXXXXXX, 7XXXXXXXX, +2547XXXXXXXX and 2547XXXXXXXX. */
fun normalizePhone(raw: String): String {
    var d = raw.filter { it.isDigit() }
    if (d.startsWith("254")) d = "0" + d.drop(3)
    else if (d.length == 9 && (d.startsWith("7") || d.startsWith("1"))) d = "0$d"
    return d.take(10)
}

fun validatePhone(d: String): PhoneValidity = when {
    d.isEmpty() -> PhoneValidity.Empty
    d.length < 10 -> PhoneValidity.Incomplete(10 - d.length)
    !Regex("^0[17]\\d{8}$").matches(d) -> PhoneValidity.NotKenyan
    else -> PhoneValidity.Valid("+254 ${d.substring(1, 4)} ${d.substring(4, 7)} ${d.substring(7)}")
}

/* ───────────────────────── ViewModel ───────────────────────── */

class ManualViewModel(
    offers: List<Offer>,
    private val gateway: ManualGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    fun dispose() = scope.cancel()

    private val _state = MutableStateFlow(
        ManualUiState(
            offers = offers,
            advanced = offers.firstOrNull()?.advanced ?: false,
            simSelection = normalizeManualSimSelection(offers.firstOrNull()?.simSelection ?: USSD_SIM_SELECTION_SLOT_1),
        )
    )
    val state: StateFlow<ManualUiState> = _state.asStateFlow()

    fun onInput(v: String) = _state.update { it.copy(input = v.filter(Char::isDigit).take(10)) }
    fun clear() = _state.update { it.copy(input = "") }
    fun updateOffers(offers: List<Offer>) = _state.update { old ->
        val selectedOffer = old.offer
        val next = if (selectedOffer == null) -1 else offers.indexOfFirst {
            (selectedOffer.offerId >= 0 && it.offerId == selectedOffer.offerId) ||
                (selectedOffer.offerId < 0 && it.name == selectedOffer.name && it.priceKes == selectedOffer.priceKes)
        }
        val nextSelected = if (next >= 0) next else 0
        val selectionChanged = next < 0
        old.copy(
            offers = offers,
            selected = nextSelected,
            // When the selected offer disappears, align the mode with the replacement offer.
            // Otherwise preserve an explicit Simple/Advanced choice made by the user.
            advanced = if (selectionChanged) offers.getOrNull(nextSelected)?.advanced ?: false else old.advanced,
            simSelection = if (selectionChanged) normalizeManualSimSelection(offers.getOrNull(nextSelected)?.simSelection ?: USSD_SIM_SELECTION_SLOT_1) else old.simSelection,
        )
    }
    fun select(i: Int) = _state.update { if (it.running) it else it.copy(selected = i) }
    fun setAdvanced(a: Boolean) = _state.update { if (it.running) it else it.copy(advanced = a) }
    fun setSimSelection(selection: Int) = _state.update { if (it.running) it else it.copy(simSelection = normalizeManualSimSelection(selection)) }
    fun openSchedule(open: Boolean) = _state.update { it.copy(scheduleOpen = open) }
    fun dismissScheduleMessage() = _state.update { it.copy(scheduleMessage = null) }

    fun execute() {
        val s = _state.value
        val offer = s.offer ?: return
        if (!s.canAct) return                       // blocks repeated taps while running
        val req = ManualRequest(s.phone, offer, s.advanced, s.simSelection)
        _state.update { it.copy(phase = RunPhase.Preparing, statusLine = "Request prepared", carrierSeconds = 0) }
        scope.launch {
            var ticker: kotlinx.coroutines.Job? = null
            val result = try {
                gateway.execute(req) {
                    _state.update { it.copy(phase = RunPhase.WaitingCarrier) }
                    ticker = scope.launch {
                        while (true) {
                            delay(1000)
                            _state.update { it.copy(carrierSeconds = it.carrierSeconds + 1) }
                        }
                    }
                }
            } catch (error: Exception) {
                ExecResult.Failure(error.message?.takeIf { it.isNotBlank() } ?: "Execution could not be started.")
            }
            ticker?.cancel()
            _state.update {
                when (result) {
                    is ExecResult.Success -> it.copy(phase = RunPhase.Done, statusLine = "Done · ${offer.name}")
                    is ExecResult.Failure -> it.copy(phase = RunPhase.Failed, statusLine = result.reason)
                    is ExecResult.Pending -> it.copy(phase = RunPhase.Pending, statusLine = result.reason)
                }
            }
            delay(3500)
            _state.update { if (it.running) it else it.copy(phase = RunPhase.Idle, statusLine = "") }
        }
    }

    fun schedule(atEpochMillis: Long) {
        val s = _state.value
        val offer = s.offer ?: return
        if (!s.canAct) return
        scope.launch {
            val ok = runCatching { gateway.schedule(ManualRequest(s.phone, offer, s.advanced, s.simSelection), atEpochMillis) }.getOrDefault(false)
            _state.update {
                it.copy(
                    scheduleOpen = !ok,
                    scheduleMessage = if (ok) "Scheduled" else "Could not schedule. Try again.",
                )
            }
        }
    }
}

/* ───────────────────────── Screen ───────────────────────── */

@Composable
fun ManualTerminalScreen(
    vm: ManualViewModel,
    onNavigate: (BottomTab) -> Unit = {},
    onOfferSelected: (Offer) -> Unit = {},
    onModeChanged: (Boolean) -> Unit = {},
    onSimSelectionChanged: (Int) -> Unit = {},
    customerName: String = "",
    onPhoneChanged: (String) -> Unit = {},
    onCustomerNameChanged: (String) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    ManualTerminalReferenceLayout(
        state = state,
        customerName = customerName,
        vm = vm,
        onPhoneChanged = onPhoneChanged,
        onCustomerNameChanged = onCustomerNameChanged,
        onOfferSelected = onOfferSelected,
        onModeChanged = onModeChanged,
        onSimSelectionChanged = onSimSelectionChanged,
    )
}

/* ───────────────────────── Top area ───────────────────────── */

@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            "Bingwa Mobile",
            color = BingwaColors.Text,
            fontSize = 29.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center,)
        Text(
            "Manual Terminal",
            color = BingwaColors.Amber,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            textAlign = TextAlign.Center,)
    }
}

/* ───────────────────────── Cards ───────────────────────── */

@Composable
private fun CardBox(compact: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BingwaColors.Card)
            .border(1.25.dp, com.bingwa.mobile.C.borderHi, RoundedCornerShape(18.dp))
            .padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        content = content,
    )
}

@Composable
private fun Caption(text: String) =
    Text(text.uppercase(), color = BingwaColors.Muted, fontSize = 11.sp, fontFamily = Mono,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)

@Composable
private fun TargetCard(
    state: ManualUiState,
    compact: Boolean,
    ultraCompact: Boolean,
    customerName: String,
    onInput: (String) -> Unit,
    onCustomerNameChange: (String) -> Unit
) {
    val validity = state.validity
    val borderColor = when (validity) {
        is PhoneValidity.Valid -> BingwaColors.Amber
        is PhoneValidity.Incomplete, PhoneValidity.NotKenyan -> BingwaColors.Red
        PhoneValidity.Empty -> BingwaColors.Line
    }
    CardBox(compact) {
        Caption("Target number")
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (ultraCompact) 48.dp else 54.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(BingwaColors.Terminal)
                .border(1.5.dp, borderColor, RoundedCornerShape(15.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Phone, null, tint = BingwaColors.Muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(11.dp))
            BasicTextField(
                value = state.input,
                onValueChange = onInput,
                visualTransformation = ManualPhoneGroupTransformation,
                singleLine = true,
                enabled = !state.running,
                textStyle = TextStyle(
                    color = BingwaColors.Text,
                    fontSize = 22.sp,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold
                ),
                cursorBrush = SolidColor(BingwaColors.Amber),
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (state.input.isEmpty()) {
                        Text("07XXXXXXXX", color = BingwaColors.DisabledText, fontSize = 21.sp, fontFamily = Mono)
                    }
                    inner()
                }
            )
        }

        val (message, color) = when (validity) {
            PhoneValidity.Empty -> "Enter a 10-digit mobile number" to BingwaColors.Muted
            is PhoneValidity.Incomplete -> "Add ${validity.missing} more digit${if (validity.missing == 1) "" else "s"}" to BingwaColors.Amber
            PhoneValidity.NotKenyan -> "Enter a valid Kenyan mobile number" to BingwaColors.Red
            is PhoneValidity.Valid -> "✓ ${customerName.ifBlank { "Number complete" }}" to BingwaColors.Green
        }
        Text(message, color = color, fontSize = 11.sp, modifier = Modifier.heightIn(min = 16.dp))

        Caption("Customer name")
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (ultraCompact) 44.dp else 48.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(BingwaColors.Terminal)
                .border(1.dp, BingwaColors.Line, RoundedCornerShape(13.dp))
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Person, null, tint = BingwaColors.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = customerName,
                onValueChange = onCustomerNameChange,
                singleLine = true,
                enabled = validity is PhoneValidity.Valid && !state.running,
                textStyle = TextStyle(
                    color = BingwaColors.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(BingwaColors.Amber),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (customerName.isEmpty()) {
                        Text(
                            if (validity is PhoneValidity.Valid) "Enter the customer's name" else "Complete the number to add a name",
                            color = BingwaColors.DisabledText,
                            fontSize = 13.sp,
                            overflow = TextOverflow.Clip
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun BundleCard(
    s: ManualUiState, compact: Boolean, ultraCompact: Boolean,
    onSelect: (Int) -> Unit, onMode: (Boolean) -> Unit, onSim: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val offer = s.offer
    CardBox(compact) {
        // Keep execution mode at the top of this card so it remains visible on short screens.
        // Offer selection can otherwise push the mode controls below the fixed action area.
        Caption("Execution mode")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeButton(
                "SIMPLE",
                !s.advanced,
                Modifier.weight(1f),
                compact || ultraCompact,
                enabled = !s.running,
            ) { onMode(false) }
            ModeButton(
                "ADVANCED",
                s.advanced,
                Modifier.weight(1f),
                compact || ultraCompact,
                enabled = !s.running,
            ) { onMode(true) }
        }
        if (s.advanced) {
            Text(
                "Needs Accessibility access",
                color = BingwaColors.Muted,
                fontSize = 11.sp,
                modifier = Modifier.heightIn(min = 14.dp),
            )
        }
        Caption("Execution SIM")
        var simMenu by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
                    .background(BingwaColors.Terminal).border(1.dp, BingwaColors.Line, RoundedCornerShape(13.dp))
                    .clickable(enabled = !s.running, onClick = { simMenu = true })
                    .padding(horizontal = 14.dp, vertical = if (compact || ultraCompact) 11.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.SimCard, null, tint = BingwaColors.Cyan, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(manualSimLabel(s.simSelection), color = BingwaColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ExpandMore, null, tint = BingwaColors.Muted)
            }
            DropdownMenu(expanded = simMenu, onDismissRequest = { simMenu = false }, modifier = Modifier.background(BingwaColors.Terminal)) {
                listOf(USSD_SIM_SELECTION_SLOT_1 to "SIM 1 (Slot 1)", USSD_SIM_SELECTION_SLOT_2 to "SIM 2 (Slot 2)").forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(label, color = if (s.simSelection == value) BingwaColors.Amber else BingwaColors.Text) }, onClick = { onSim(value); simMenu = false })
                }
            }
        }
        Caption("Bundle")
        Box {
            Ticket(
                title = offer?.name ?: "No offers",
                subtitle = offer?.let { "KES ${it.priceKes} · ${if (s.advanced) "ADVANCED" else "SIMPLE"}" } ?: "",
                expanded = open,
                compact = compact || ultraCompact,
                enabled = !s.running && s.offers.isNotEmpty(),
                onClick = { open = !open },
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(BingwaColors.Terminal).widthIn(max = 320.dp),
            ) {
                s.offers.forEachIndexed { i, o ->
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("#${i + 1}", color = BingwaColors.Cyan, fontWeight = FontWeight.ExtraBold,
                                    fontFamily = Mono, fontSize = 12.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(o.name, color = BingwaColors.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("KES ${o.priceKes}", color = BingwaColors.Amber, fontFamily = Mono, fontSize = 12.sp)
                                if (i == s.selected) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Filled.Check, null, tint = BingwaColors.Amber, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        onClick = { onSelect(i); open = false },
                        modifier = Modifier
                            .background(if (i == s.selected) BingwaColors.AmberSoft else Color.Transparent)
                            .heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun manualSimLabel(selection: Int): String = when (selection) {
    USSD_SIM_SELECTION_SLOT_2 -> "SIM 2 (Slot 2)"
    else -> "SIM 1 (Slot 1)"
}

@Composable
private fun Ticket(title: String, subtitle: String, expanded: Boolean, compact: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val dotColor = BingwaColors.Bg
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BingwaColors.Card)
            .drawBehind {
                val r = 3.dp.toPx()
                var x = 6.dp.toPx()
                while (x < size.width) {
                    drawCircle(dotColor, r, Offset(x, 0f))
                    drawCircle(dotColor, r, Offset(x, size.height))
                    x += 12.dp.toPx()
                }
            }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compact) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (compact) 40.dp else 46.dp).clip(CircleShape).background(BingwaColors.Amber), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Bolt, null, tint = BingwaColors.Bg, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = BingwaColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, overflow = TextOverflow.Clip)
            Text(subtitle, color = BingwaColors.Muted, fontSize = 11.sp, fontFamily = Mono, letterSpacing = 1.sp)
        }
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "Choose bundle",
            tint = BingwaColors.Muted, modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    on: Boolean,
    modifier: Modifier,
    compact: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .height(if (compact) 44.dp else 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) BingwaColors.Amber else BingwaColors.Card)
            .border(1.dp, if (on) BingwaColors.Amber else BingwaColors.Line, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) BingwaColors.Bg else BingwaColors.Text, fontSize = 13.sp,
            fontFamily = Mono, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
private fun ActionCard(s: ManualUiState, compact: Boolean, ultraCompact: Boolean, onExecute: () -> Unit, onSchedule: () -> Unit) {
    val offer = s.offer
    val line = when (s.phase) {
        RunPhase.Idle -> "> DIAL ${offer?.ussdPrefix.orEmpty()}${s.phone.ifEmpty { "…" }}#"
        RunPhase.Preparing -> "> ${s.statusLine}"
        RunPhase.WaitingCarrier ->
            if (s.carrierSeconds >= 30) "> Carrier is slow to reply · ${s.carrierSeconds}s (app is fine)"
            else "> Waiting for carrier… ${s.carrierSeconds}s"
        RunPhase.Done, RunPhase.Failed, RunPhase.Pending -> "> ${s.statusLine}"
    }
    val lineColor = when (s.phase) {
        RunPhase.WaitingCarrier -> BingwaColors.Cyan
        RunPhase.Done -> BingwaColors.Green
        RunPhase.Failed -> BingwaColors.Red
        RunPhase.Pending -> BingwaColors.Cyan
        else -> BingwaColors.Amber
    }
    CardBox(compact) {
        Text(
            line, color = lineColor, fontSize = 13.sp, fontFamily = Mono, overflow = TextOverflow.Clip,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BingwaColors.Terminal)
                .border(1.dp, BingwaColors.Line, RoundedCornerShape(14.dp))
                .padding(14.dp),
        )
        if (s.running) {
            if (s.phase == RunPhase.Preparing) LinearProgressIndicator(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = BingwaColors.Amber, trackColor = BingwaColors.Line,
            ) else LinearProgressIndicator(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = BingwaColors.Cyan, trackColor = BingwaColors.Line,
            )
        }
        val h = if (ultraCompact) 40.dp else if (compact) 46.dp else 54.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onExecute, enabled = s.canAct,
                modifier = Modifier.weight(1f).height(h),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BingwaColors.Amber, contentColor = BingwaColors.Bg,
                    disabledContainerColor = BingwaColors.Disabled, disabledContentColor = BingwaColors.DisabledText,
                ),
            ) {
                Icon(Icons.Filled.Send, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (s.running) "EXECUTING…" else "EXECUTE", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 0.4.sp)
            }
            OutlinedButton(
                onClick = onSchedule, enabled = s.canAct,
                modifier = Modifier.weight(1f).height(h),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                border = BorderStroke(1.5.dp, if (s.canAct) BingwaColors.Amber else BingwaColors.Line),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BingwaColors.Amber, disabledContentColor = BingwaColors.DisabledText),
            ) {
                Icon(Icons.Outlined.Schedule, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("SCHEDULE", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 0.4.sp)
            }
        }
    }
}

/* ───────────────────────── Bottom navigation ───────────────────────── */

enum class BottomTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Manual("Manual", Icons.Filled.Terminal),
    Power("", Icons.Filled.PowerSettingsNew),
    Tokens("Tokens", Icons.Filled.Layers),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
private fun BottomNav(selected: BottomTab, onClick: (BottomTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(BingwaColors.Card)
            .border(1.dp, BingwaColors.Line, RoundedCornerShape(34.dp))
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomTab.values().forEach { tab ->
            val on = tab == selected
            if (tab == BottomTab.Power) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape)
                        .border(2.dp, BingwaColors.Power, CircleShape)
                        .clickable { onClick(tab) },
                    contentAlignment = Alignment.Center,
                ) { Icon(tab.icon, "Power", tint = BingwaColors.Power, modifier = Modifier.size(26.dp)) }
            } else {
                Column(
                    Modifier.size(56.dp).clip(CircleShape)
                        .background(if (on) BingwaColors.AmberSoft else Color.Transparent)
                        .clickable { onClick(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val c = if (on) BingwaColors.Amber else BingwaColors.Muted
                    Icon(tab.icon, tab.label, tint = c, modifier = Modifier.size(22.dp))
                    Text(tab.label, color = c, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/* ───────────────────────── Schedule sheet ───────────────────────── */

@Composable
private fun ScheduleSheet(state: ManualUiState, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val tomorrow = remember { System.currentTimeMillis() + 24L * 60 * 60 * 1000 }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = tomorrow)
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 30, is24Hour = true)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val dateText = dateState.selectedDateMillis?.let {
        SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(it))
    } ?: "Pick a date"
    val timeText = "%02d:%02d".format(timeState.hour, timeState.minute)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BingwaColors.Card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.offer?.name.orEmpty()} · ${state.phone}", color = BingwaColors.Text, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PickerField("Date", dateText, Modifier.weight(1.4f)) { showDate = true }
                PickerField("Time", timeText, Modifier.weight(1f)) { showTime = true }
            }
            state.scheduleMessage?.let { Text(it, color = BingwaColors.Red, fontSize = 12.sp) }
            Button(
                onClick = {
                    val day = dateState.selectedDateMillis ?: return@Button
                    // DatePicker returns UTC midnight; combine with the chosen local time.
                    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = day }
                    val local = java.util.Calendar.getInstance().apply {
                        set(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH),
                            timeState.hour, timeState.minute, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    onConfirm(local.timeInMillis)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BingwaColors.Amber, contentColor = BingwaColors.Bg),
            ) { Text("SCHEDULE EXECUTION", fontWeight = FontWeight.Black, letterSpacing = 1.sp) }
        }
    }

    if (showDate) DatePickerDialog(
        onDismissRequest = { showDate = false },
        confirmButton = { TextButton({ showDate = false }) { Text("OK", color = BingwaColors.Amber) } },
    ) { DatePicker(dateState) }

    if (showTime) AlertDialog(
        onDismissRequest = { showTime = false },
        confirmButton = { TextButton({ showTime = false }) { Text("OK", color = BingwaColors.Cyan) } },
        text = { TimePicker(timeState) },
    )
}

@Composable
private fun PickerField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BingwaColors.Bg)
            .border(1.dp, BingwaColors.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Caption(label)
        Text(value, color = BingwaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/*
 * Usage (e.g. in your NavHost or Activity):
 *
 * val vm = remember {
 *     ManualViewModel(
 *         offers = yourOffersFromRepository,          // List<Offer>
 *         gateway = yourExecutionEngineAdapter,       // implements ManualGateway
 *     )
 * }
 * ManualScreen(vm, onNavigate = { tab -> /* navigate */ })
 *
 * Gradle: Compose BOM + material3 (1.2+) + material-icons-extended + lifecycle-viewmodel-compose.
 */