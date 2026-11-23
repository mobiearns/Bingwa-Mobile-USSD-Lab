@file:OptIn(ExperimentalMaterial3Api::class)

package com.bingwa.mobile.manual

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import com.bingwa.mobile.OFFER_SIM_USE_GENERAL
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_1
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_2
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private object ManualRefColors {
    val Bg get() = com.bingwa.mobile.C.bg
    val Card get() = com.bingwa.mobile.C.card
    val Field get() = com.bingwa.mobile.C.surface
    val Border get() = com.bingwa.mobile.C.border
    val Mint get() = com.bingwa.mobile.C.cyan
    val Amber get() = com.bingwa.mobile.C.amber
    val Red get() = com.bingwa.mobile.C.red
    val Green get() = com.bingwa.mobile.C.green
    val Blue get() = com.bingwa.mobile.C.blue
    val Text get() = com.bingwa.mobile.C.t1
    val Muted get() = com.bingwa.mobile.C.t2
    val Mono = FontFamily.Monospace
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
}

@Composable
internal fun ManualTerminalReferenceLayout(
    state: ManualUiState,
    customerName: String,
    vm: ManualViewModel,
    onPhoneChanged: (String) -> Unit,
    onCustomerNameChanged: (String) -> Unit,
    onOfferSelected: (Offer) -> Unit,
    onModeChanged: (Boolean) -> Unit,
    onSimSelectionChanged: (Int) -> Unit,
) {
    val c = ManualRefColors
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    Scaffold(
        containerColor = c.Bg,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (!keyboardVisible) {
                ManualRefActionBar(
                    state = state,
                    onExecute = vm::execute,
                    onSchedule = { vm.openSchedule(true) },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // Keep the terminal controls reachable on short and narrow displays.
            // Compact spacing reduces visual density while scrolling prevents clipping.
            val ultraCompact = maxHeight < 620.dp || maxWidth < 340.dp
            val compact = maxHeight < 760.dp || maxWidth < 380.dp
            Column(
                // The pinned action bar remains available while the workspace can scroll.
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = if (maxWidth < 360.dp) 8.dp else com.bingwa.mobile.UiDimens.ScreenHorizontal,
                        end = if (maxWidth < 360.dp) 8.dp else com.bingwa.mobile.UiDimens.ScreenHorizontal,
                        top = if (ultraCompact) 2.dp else if (compact) 4.dp else 8.dp,
                        bottom = if (ultraCompact) 4.dp else if (compact) 6.dp else 10.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(
                    if (ultraCompact) 4.dp else if (compact) 6.dp else 9.dp,
                ),
            ) {
                ManualRefHeader(compact = compact)
                ManualRefCustomerCard(
                    state = state,
                    customerName = customerName,
                    compact = compact,
                    onPhoneChanged = { value ->
                        vm.onInput(value)
                        onPhoneChanged(value.filter(Char::isDigit).take(10))
                    },
                    onCustomerNameChanged = onCustomerNameChanged,
                )
                ManualRefOfferPicker(
                    state = state,
                    compact = compact,
                    onSelect = { index ->
                        vm.select(index)
                        state.offers.getOrNull(index)?.let { offer ->
                            vm.setAdvanced(offer.advanced)
                            vm.setSimSelection(offer.simSelection)
                            onModeChanged(offer.advanced)
                            onSimSelectionChanged(offer.simSelection)
                            onOfferSelected(offer)
                        }
                    },
                )
                ManualRefChoiceCard(
                    state = state,
                    compact = compact,
                    onMode = { advanced ->
                        vm.setAdvanced(advanced)
                        onModeChanged(advanced)
                    },
                    onSim = { selection ->
                        vm.setSimSelection(selection)
                        onSimSelectionChanged(selection)
                    },
                )
                // Keep feedback in the layout flow; it never overlays controls.
                AnimatedVisibility(
                    visible = state.phase != RunPhase.Idle || state.statusLine.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ManualRefStatus(state, compact = compact)
                }
            }
        }
    }

    if (state.scheduleOpen) {
        ManualRefScheduleSheet(
            state = state,
            onDismiss = { vm.openSchedule(false) },
            onConfirm = vm::schedule,
        )
    }
}

@Composable
private fun ManualRefHeader(compact: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Bingwa Mobile", color = ManualRefColors.Text, fontSize = if (compact) 17.sp else 19.sp, fontWeight = FontWeight.Bold)
        Text("MANUAL TERMINAL", color = ManualRefColors.Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
    }
}

@Composable
private fun ManualRefCard(compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val ultraCompact = compact
    Column(
        Modifier.fillMaxWidth().clip(ManualRefColors.Large)
            .background(com.bingwa.mobile.C.cardHi)
            .border(1.2.dp, com.bingwa.mobile.C.borderHi, ManualRefColors.Large)
            .padding(horizontal = if (ultraCompact) 10.dp else 12.dp, vertical = if (ultraCompact) 6.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(if (ultraCompact) 4.dp else 5.dp),
        content = content,
    )
}

@Composable
private fun ManualRefLabel(value: String) {
    Text(value.uppercase(), color = ManualRefColors.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun ManualRefCustomerCard(
    state: ManualUiState,
    customerName: String,
    compact: Boolean,
    onPhoneChanged: (String) -> Unit,
    onCustomerNameChanged: (String) -> Unit,
) {
    val c = ManualRefColors
    var focused by remember { mutableStateOf(false) }
    val valid = state.validity is PhoneValidity.Valid
    val invalid = state.validity is PhoneValidity.NotKenyan
    val edge = when {
        invalid -> c.Red
        focused -> c.Mint
        valid -> c.Green
        else -> c.Border
    }
    ManualRefCard(compact = compact) {
        ManualRefLabel("Customer phone")
        Row(
            Modifier.fillMaxWidth().height(if (compact) 42.dp else 48.dp).clip(c.Medium)
                .background(c.Field).border(1.5.dp, edge, c.Medium)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.PhoneAndroid, null, tint = if (valid) c.Green else c.Muted, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = state.input,
                onValueChange = onPhoneChanged,
                modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                enabled = !state.running,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = if (invalid) c.Red else c.Text,
                    fontFamily = c.Mono,
                    fontSize = if (compact) 19.sp else 21.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.Mint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                decorationBox = { inner ->
                    if (state.input.isEmpty()) Text("07XXXXXXXX", color = com.bingwa.mobile.C.t3, fontFamily = c.Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    inner()
                },
            )
            if (state.input.isNotEmpty() && !state.running) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(c.Card).clickable { onPhoneChanged("") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, "Clear number", tint = c.Muted, modifier = Modifier.size(14.dp)) }
            }
        }
        val hint = when (val v = state.validity) {
            PhoneValidity.Empty -> "Enter a 10-digit Kenyan number"
            is PhoneValidity.Incomplete -> "Add ${v.missing} more digit${if (v.missing == 1) "" else "s"}"
            PhoneValidity.NotKenyan -> "Number must start with 07 or 01"
            is PhoneValidity.Valid -> "✓ Number looks good"
        }
        val hintColor = when (state.validity) {
            is PhoneValidity.Valid -> c.Green
            is PhoneValidity.NotKenyan -> c.Red
            is PhoneValidity.Incomplete -> c.Amber
            PhoneValidity.Empty -> c.Muted
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hint, color = hintColor, fontSize = 11.sp, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f))
            Text("${state.input.length}/10", color = hintColor, fontSize = 11.sp, fontFamily = c.Mono)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.Border))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(if (compact) 28.dp else 32.dp).clip(CircleShape).background(c.Field).border(1.dp, c.Border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                val initial = customerName.trim().firstOrNull()?.uppercase()
                if (initial != null) Text(initial, color = c.Mint, fontWeight = FontWeight.Bold)
                else Icon(Icons.Outlined.Person, null, tint = c.Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = customerName,
                onValueChange = onCustomerNameChanged,
                modifier = Modifier.weight(1f).height(if (compact) 30.dp else 34.dp),
                enabled = !state.running,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.Mint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                        if (customerName.isEmpty()) Text(
                            if (valid) "Customer name" else "Complete number to add a name",
                            color = c.Muted, fontSize = 14.sp,
                        )
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun ManualRefOfferPicker(state: ManualUiState, compact: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val c = ManualRefColors
    val selected = state.offer
    ManualRefCard(compact = compact) {
        ManualRefLabel("Bundle offer")
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (compact) 52.dp else 58.dp).clip(c.Large).background(c.Card)
                .border(1.dp, c.Border, c.Large)
                .clickable(enabled = !state.running && state.offers.isNotEmpty()) { open = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(c.Small).background(c.Field).border(1.dp, if (selected != null) c.Mint else c.Border, c.Small), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Inventory2, null, tint = if (selected != null) c.Mint else c.Muted, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(selected?.name ?: "Choose an offer", color = c.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, softWrap = true, overflow = TextOverflow.Clip)
                Text(selected?.let { "${if (state.advanced) "Advanced" else "Simple"} execution · ${it.ussdPrefix}" } ?: "Tap to browse saved offers", color = c.Muted, fontSize = 11.sp, softWrap = true, overflow = TextOverflow.Clip)
            }
            selected?.let { Text("KES ${it.priceKes}", color = c.Amber, fontFamily = c.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            Text("›", color = c.Muted, fontSize = 22.sp)
        }
    }
    if (open) {
        var pending by remember { mutableIntStateOf(state.selected) }
        val filtered = remember(state.offers, search) {
            val q = search.trim().lowercase()
            if (q.isEmpty()) state.offers else state.offers.filter { it.name.lowercase().contains(q) || it.ussdPrefix.lowercase().contains(q) }
        }
        ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = c.Card,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxHeight(0.82f).padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
                Text("Select bundle offer", color = c.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search offers", color = c.Muted) },
                    shape = c.Medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.Text, unfocusedTextColor = c.Text,
                        focusedContainerColor = c.Field, unfocusedContainerColor = c.Field,
                        focusedBorderColor = c.Mint, unfocusedBorderColor = c.Border,
                        cursorColor = c.Mint,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (filtered.isEmpty()) item {
                        Text(if (state.offers.isEmpty()) "No saved offers available." else "No offers match your search.", color = c.Muted, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                    }
                    items(filtered.size) { index ->
                        val offer = filtered[index]
                        val originalIndex = state.offers.indexOf(offer)
                        val isSelected = originalIndex == pending
                        Row(
                            Modifier.fillMaxWidth().clip(c.Medium)
                                .background(if (isSelected) c.Mint.copy(alpha = 0.08f) else c.Field)
                                .border(1.dp, if (isSelected) c.Mint else c.Border, c.Medium)
                                .clickable { pending = originalIndex }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(offer.name, color = c.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, softWrap = true, overflow = TextOverflow.Clip)
                                Text("${if (offer.advanced) "Advanced" else "Simple"} execution · ${offer.ussdPrefix}", color = c.Muted, fontSize = 11.sp, softWrap = true, overflow = TextOverflow.Clip)
                            }
                            Text("KES ${offer.priceKes}", color = c.Amber, fontFamily = c.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp,)
                            Spacer(Modifier.width(9.dp))
                            if (isSelected) Icon(Icons.Outlined.Inventory2, null, tint = c.Mint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Button(
                    onClick = { onSelect(pending); open = false },
                    enabled = pending in state.offers.indices,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    shape = c.Medium,
                    colors = ButtonDefaults.buttonColors(containerColor = c.Mint, contentColor = com.bingwa.mobile.C.bg),
                ) { Text("CONFIRM SELECTION", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ManualRefChoiceCard(
    state: ManualUiState,
    compact: Boolean,
    onMode: (Boolean) -> Unit,
    onSim: (Int) -> Unit,
) {
    val c = ManualRefColors
    ManualRefCard(compact = compact) {
        // Keep SIM selection visible in both execution modes. It appears before
        // optional Advanced helper text so it cannot be pushed below the fold.
        ManualRefLabel("Execution SIM")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val choices = listOf(
                USSD_SIM_SELECTION_SLOT_1 to "SIM 1 (Slot 1)",
                USSD_SIM_SELECTION_SLOT_2 to "SIM 2 (Slot 2)",
            )
            choices.forEach { (value, label) ->
                val active = state.simSelection == value
                val bg by animateColorAsState(if (active) c.Mint.copy(alpha = 0.10f) else c.Card, label = "manualSimBg")
                val edge by animateColorAsState(if (active) c.Mint else c.Border, label = "manualSimEdge")
                Row(
                    Modifier .weight(1f).height(if (compact) 40.dp else 44.dp).clip(c.Medium).background(bg).border(1.dp, edge, c.Medium)
                        .clickable(enabled = !state.running) { onSim(value) }
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.SimCard, null, tint = if (active) c.Mint else c.Muted, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (compact) label.substringBefore(" (") else label,
                        color = if (active) c.Text else c.Muted,
                        fontSize = if (compact) 12.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                        softWrap = true,
                    )
                }
            }
        }
        Spacer(Modifier.height(if (compact) 2.dp else 4.dp))
        ManualRefLabel("Execution mode")
        Row(
            Modifier.fillMaxWidth().clip(c.Medium).background(c.Field)
                .border(1.dp, c.Border, c.Medium).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(false to "Simple Mode", true to "Advanced Mode").forEach { (advanced, label) ->
                val active = state.advanced == advanced
                Box(
                    Modifier.weight(1f).height(if (compact) 38.dp else 42.dp).clip(c.Small)
                        .background(if (active) c.Mint else Color.Transparent)
                        .clickable(enabled = !state.running) { onMode(advanced) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (active) com.bingwa.mobile.C.bg else c.Muted,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (state.advanced && !compact) {
            Text(
                "Advanced mode uses Accessibility execution",
                color = c.Muted,
                fontSize = 10.sp,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun ManualRefStatus(state: ManualUiState, compact: Boolean) {
    val c = ManualRefColors
    val color = when (state.phase) {
        RunPhase.Done -> c.Green
        RunPhase.Failed -> c.Red
        RunPhase.Pending -> c.Amber
        RunPhase.WaitingCarrier -> c.Blue
        RunPhase.Preparing -> c.Amber
        RunPhase.Idle -> c.Muted
    }
    val label = when (state.phase) {
        RunPhase.Done -> "Successful"
        RunPhase.Failed -> "Failed"
        RunPhase.Pending -> "Pending"
        RunPhase.WaitingCarrier -> "Waiting for carrier"
        RunPhase.Preparing -> "Preparing transaction"
        RunPhase.Idle -> "Ready"
    }
    ManualRefCard(compact = compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.running) CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            else Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(9.dp))
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (state.phase == RunPhase.WaitingCarrier) {
                Spacer(Modifier.weight(1f))
                Text("${state.carrierSeconds}s", color = c.Muted, fontFamily = c.Mono, fontSize = 11.sp)
            }
        }
        Text(state.statusLine.ifBlank { "Ready to execute." }, color = c.Text, fontSize = 12.sp, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun ManualRefActionBar(state: ManualUiState, onExecute: () -> Unit, onSchedule: () -> Unit) {
    val c = ManualRefColors
    Surface(color = c.Bg, tonalElevation = 0.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 10.dp),
        ) {
            val selectedOffer = state.offer
            Text(
                if (state.validity is PhoneValidity.Valid && selectedOffer != null)
                    "${state.phone} · ${selectedOffer.name} · KES ${selectedOffer.priceKes}"
                else "Enter a number and choose an offer to continue",
                color = c.Muted, fontSize = 11.sp, overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(17.dp),
            )
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSchedule,
                    enabled = state.canAct,
                    shape = c.Medium,
                    border = BorderStroke(1.dp, if (state.canAct) c.Amber else c.Border),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Outlined.Schedule, null, tint = if (state.canAct) c.Amber else c.Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("SCHEDULE", color = if (state.canAct) c.Amber else c.Muted, fontWeight = FontWeight.Bold, fontSize = 11.sp,)
                }
                Button(
                    onClick = onExecute,
                    enabled = state.canAct,
                    shape = c.Medium,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.Mint, contentColor = Color(0xFF05140F),
                        disabledContainerColor = c.Field, disabledContentColor = c.Muted,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Text(if (state.running) "WORKING…" else "EXECUTE NOW", fontWeight = FontWeight.Bold, fontSize = 11.sp,)
                }
            }
        }
    }
}

@Composable
private fun ManualRefScheduleSheet(state: ManualUiState, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val c = ManualRefColors
    val tomorrow = remember { System.currentTimeMillis() + 24L * 60L * 60L * 1000L }
    val dateState = rememberDatePickerState(initialSelectedDateMillis = tomorrow)
    val timeState = rememberTimePickerState(initialHour = 8, initialMinute = 30, is24Hour = true)
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    val dateText = dateState.selectedDateMillis?.let { SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(it)) } ?: "Pick a date"
    val timeText = "%02d:%02d".format(timeState.hour, timeState.minute)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.Card) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${state.offer?.name.orEmpty()} · ${state.phone}", color = c.Text, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ManualRefPickerField("Date", dateText, Modifier.weight(1.35f)) { showDate = true }
                ManualRefPickerField("Time", timeText, Modifier.weight(1f)) { showTime = true }
            }
            state.scheduleMessage?.let { Text(it, color = c.Red, fontSize = 12.sp) }
            Button(
                onClick = {
                    val selectedDay = dateState.selectedDateMillis ?: return@Button
                    val day = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = selectedDay }
                    val local = Calendar.getInstance().apply {
                        set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), timeState.hour, timeState.minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onConfirm(local.timeInMillis)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                shape = c.Medium,
                colors = ButtonDefaults.buttonColors(containerColor = c.Amber, contentColor = com.bingwa.mobile.C.bg),
            ) { Text("CONFIRM SCHEDULE", fontWeight = FontWeight.Bold) }
        }
    }
    if (showDate) DatePickerDialog(
        onDismissRequest = { showDate = false },
        confirmButton = { TextButton(onClick = { showDate = false }) { Text("OK", color = c.Mint) } },
    ) { DatePicker(dateState) }
    if (showTime) AlertDialog(
        onDismissRequest = { showTime = false },
        confirmButton = { TextButton(onClick = { showTime = false }) { Text("OK", color = c.Mint) } },
        text = { TimePicker(timeState) },
        containerColor = c.Card,
    )
}

@Composable
private fun ManualRefPickerField(label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(ManualRefColors.Medium).background(ManualRefColors.Field)
            .border(1.dp, ManualRefColors.Border, ManualRefColors.Medium)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ManualRefLabel(label)
        Text(value, color = ManualRefColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}
