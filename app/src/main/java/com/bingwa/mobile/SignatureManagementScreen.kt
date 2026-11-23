@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.bingwa.mobile

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object SignatureStudioColors {
    val bg = Color(0xFF0F1317)
    val surface = Color(0xFF1C232B)
    val card = Color(0xFF161B21)
    val border = Color(0xFF2A343F)
    val t1 = Color(0xFFE8EDF2)
    val t2 = Color(0xFF8A94A3)
    val t3 = Color(0xFF6B7482)
    val amber = Color(0xFFFFB454)
    val cyan = Color(0xFF74E6D8)
    val green = Color(0xFF7EE08A)
    val red = Color(0xFFFF7A6B)
    val ink = Color(0xFF0B0F13)
    val muted = Color(0xFF8A94A3)
}

private enum class SignatureManagementTab { LIVE_MENU, LEARN, SIMPLE, MANUAL, OFFERS }

@Composable
fun SignatureManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var offers by remember { mutableStateOf(OfferRepository.load(context)) }
    var selectedId by remember {
        mutableIntStateOf(
            offers.firstOrNull { SimpleSignatureVerifier.previewCode(it.ussdCode) != null }?.id ?: -1
        )
    }
    var tab by rememberSaveable { mutableStateOf(SignatureManagementTab.LIVE_MENU) }
    val live by SignatureLiveMenuCoordinator.state.collectAsState()
    var activity by remember { mutableStateOf(SignatureActivityStore.load(context)) }
    var advancedLearning by remember { mutableStateOf(false) }
    var secondsToSync by rememberSaveable {
        mutableIntStateOf(SignatureLiveMenuCoordinator.secondsUntilNextAutoRefresh())
    }
    val selected = offers.firstOrNull { it.id == selectedId }

    fun reloadOffers() {
        offers = OfferRepository.load(context)
        if (selectedId != -1 && offers.none { it.id == selectedId }) selectedId = -1
    }

    fun launchAdvancedLearning() {
        val offer = OfferRepository.findById(context, selectedId)
        if (offer == null) {
            Toast.makeText(context, "Select an offer first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (offer.ussdCode.isBlank()) {
            Toast.makeText(context, "This offer has no USSD code to learn.", Toast.LENGTH_LONG).show()
            return
        }
        if (advancedLearning) return

        // Learning is a free calibration session. The launcher routes it through the
        // existing Advanced accessibility navigator and never dispatches a purchase.
        val learningPhone = "0712345678"
        val learningCode = offer.ussdCode.replace(Regex("(?i)\\[?pn\\]?"), learningPhone)
        advancedLearning = true
        activity = SignatureActivityStore.record(context, offer.name + ": Advanced signature learning started")
        Toast.makeText(context, "Learning Advanced signature for ${offer.name}. Complete the carrier USSD prompts.", Toast.LENGTH_LONG).show()
        context.startOfferAutomation(
            offer = offer,
            phoneNumber = learningPhone,
            txId = -1,
            finalCode = learningCode,
            mode = OFFER_EXECUTION_MODE_ADVANCED,
            signatureLearning = true,
            executionPriority = USSD_EXECUTION_PRIORITY_SPECIAL
        )
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                reloadOffers()
                if (intent.action == "com.bingwa.mobile.OFFER_SIGNATURE_LEARNED") {
                    advancedLearning = false
                    val learned = OfferRepository.findById(context, selectedId)
                    activity = SignatureActivityStore.record(
                        context,
                        (learned?.name ?: "Offer") + if (
                            learned?.pendingLearnedSignature?.isNotEmpty() == true ||
                            learned?.pendingSignatureLearningCaptures?.isNotEmpty() == true
                        ) ": Advanced signature captured · awaiting approval"
                        else ": Advanced signature learning did not produce a reviewable capture"
                    )
                } else {
                    activity = SignatureActivityStore.load(context)
                }
            }
        }
        val registered = registerAppReceiver(context, receiver, android.content.IntentFilter().apply {
            addAction("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
            addAction(OfferRepository.ACTION_OFFERS_UPDATED)
        })
        onDispose {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
        }
    }

    fun refreshLiveMenu() {
        SignatureLiveMenuCoordinator.refresh(
            context = context,
            offers = offers,
            selectedOfferId = selectedId,
            forceManual = true
        )
        secondsToSync = SignatureLiveMenuCoordinator.secondsUntilNextAutoRefresh()
    }

    val latestOffers by rememberUpdatedState(offers)
    val latestSelectedId by rememberUpdatedState(selectedId)
    LaunchedEffect(tab) {
        if (tab == SignatureManagementTab.LIVE_MENU) {
            // Only send a silent USSD request on first entry or after the controlled
            // 12-minute interval — never repeat on tab switches or recomposition,
            // and never auto-retry while paused by a carrier retry-limit response.
            SignatureLiveMenuCoordinator.refreshIfDue(context, latestOffers, latestSelectedId)
            while (true) {
                val now = System.currentTimeMillis()
                secondsToSync = SignatureLiveMenuCoordinator.secondsUntilNextAutoRefresh(now)
                if (SignatureLiveMenuCoordinator.shouldAutoRefresh(now)) {
                    SignatureLiveMenuCoordinator.refreshIfDue(context, latestOffers, latestSelectedId, now)
                }
                delay(1_000L)
            }
        } else {
            secondsToSync = SignatureLiveMenuCoordinator.secondsUntilNextAutoRefresh()
        }
    }

    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = SignatureStudioColors.bg,
        bottomBar = {
            SignatureKeycapNav(tab) { tab = it }
        }
    ) { scaffoldPadding ->
        Column(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            SignatureTerminalHeader(
                tab = tab,
                sync = secondsToSync,
                autoSyncPaused = live.autoRefreshPausedByCarrierLimit,
                onBack = onBack
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                when (tab) {
                    SignatureManagementTab.LEARN -> SignatureLearnPanel(
                        offers = offers,
                        selectedOfferId = selectedId,
                        learning = advancedLearning,
                        onSelectOffer = { selectedId = it },
                        onLearn = {
                            val offer = OfferRepository.findById(context, selectedId)
                            if (offer == null) {
                                Toast.makeText(context, "Select an offer first.", Toast.LENGTH_SHORT).show()
                            } else if (normalizeOfferExecutionMode(offer.executionMode, offer.category) == OFFER_EXECUTION_MODE_ADVANCED) {
                                launchAdvancedLearning()
                            } else {
                                tab = SignatureManagementTab.LIVE_MENU
                            }
                        },
                        onApprove = {
                            val item = OfferRepository.findById(context, selectedId)
                            if (item != null) {
                                OfferRepository.approveStagedSignature(context, item.id)
                                reloadOffers()
                                activity = SignatureActivityStore.record(context, item.name + ": Signature approved")
                            }
                        },
                        onDiscardPending = {
                            val item = OfferRepository.findById(context, selectedId)
                            if (item != null) {
                                OfferRepository.clearPendingSignature(context, item.id)
                                reloadOffers()
                                activity = SignatureActivityStore.record(context, item.name + ": Pending signature discarded")
                            }
                        }
                    )
                    SignatureManagementTab.OFFERS -> SignatureOffersPanel(
                        offers = offers,
                        selectedId = selectedId,
                        onSelect = { id ->
                            selectedId = id
                            val offer = offers.firstOrNull { it.id == id }
                            tab = if (offer != null && normalizeOfferExecutionMode(offer.executionMode, offer.category) == OFFER_EXECUTION_MODE_ADVANCED)
                                SignatureManagementTab.MANUAL else SignatureManagementTab.SIMPLE
                        }
                    )
                    SignatureManagementTab.LIVE_MENU -> SignatureLiveMenuPanel(
                        state = live,
                        offers = offers,
                        selectedOfferId = selectedId,
                        onRefresh = ::refreshLiveMenu
                    ) { position, label ->
                        val offer = OfferRepository.findById(context, selectedId)
                        if (offer == null) {
                            Toast.makeText(context, "Select an offer in Offers before saving a Simple signature.", Toast.LENGTH_LONG).show()
                        } else {
                            val saved = UssdSignatureStep(
                                stepIndex = 0,
                                expectedInput = position.toString(),
                                menuTitle = offer.name,
                                menuText = live.validResponse,
                                selectedOptionLabel = label,
                                menuOptionsSnapshot = live.validOptions.sortedBy { it.first }.map { "${it.first}. ${it.second}" }
                            )
                            OfferRepository.updateSimpleSignature(context, offer.id, listOf(saved))
                            val current = OfferRepository.findById(context, offer.id)
                            if (current != null) {
                                val aliases = listOf(current.name, current.bundleSize, "${current.bundleSize} ${current.validityNumber} ${current.validityUnit}")
                                    .filter { it.isNotBlank() }.joinToString(", ")
                                OfferRepository.save(context, OfferRepository.load(context).map { if (it.id == current.id) it.copy(simpleSignatureAliases = aliases) else it })
                            }
                            reloadOffers()
                            activity = SignatureActivityStore.record(context, offer.name + ": Simple signature saved at position " + position)
                            tab = SignatureManagementTab.SIMPLE
                        }
                    }
                    SignatureManagementTab.SIMPLE -> SimpleSignatureSettingsPanel(
                        offers = offers,
                        onOpenLiveMenu = { offerId ->
                            selectedId = offerId
                            tab = SignatureManagementTab.LIVE_MENU
                        },
                        onSaveAliases = { item, aliases, sensitivity ->
                            OfferRepository.save(context, OfferRepository.load(context).map { offer ->
                                if (offer.id == item.id) offer.copy(
                                    simpleSignatureAliases = aliases,
                                    simpleSignatureSensitivity = sensitivity
                                ) else offer
                            })
                            reloadOffers()
                            activity = SignatureActivityStore.record(context, item.name + ": Simple signature settings saved")
                        },
                        onToggle = { item, enabled ->
                            OfferRepository.setSimpleSignatureEnabled(context, item.id, enabled)
                            reloadOffers()
                            activity = SignatureActivityStore.record(context, item.name + ": Simple signature " + if (enabled) "enabled" else "disabled")
                        },
                        onClear = { item ->
                            OfferRepository.clearSimpleSignature(context, item.id)
                            reloadOffers()
                            activity = SignatureActivityStore.record(context, item.name + ": Simple signature cleared")
                        }
                    )
                    SignatureManagementTab.MANUAL -> ManualSignatureSettingsPanel(
                        offers = offers,
                        selectedOfferId = selectedId,
                        learning = advancedLearning,
                        onSelectOffer = { selectedId = it },
                        onLearn = ::launchAdvancedLearning,
                        onApprove = {
                            val item = OfferRepository.findById(context, selectedId)
                            if (item != null) {
                                OfferRepository.approveStagedSignature(context, item.id)
                                reloadOffers()
                                activity = SignatureActivityStore.record(context, item.name + ": Advanced signature approved")
                            }
                        },
                        onDiscardPending = {
                            val item = OfferRepository.findById(context, selectedId)
                            if (item != null) {
                                OfferRepository.clearPendingSignature(context, item.id)
                                reloadOffers()
                                activity = SignatureActivityStore.record(context, item.name + ": Pending Advanced signature discarded")
                            }
                        },
                        onChangeAction = { item, action ->
                            OfferRepository.save(context, OfferRepository.load(context).map { offer ->
                                if (offer.id == item.id) offer.copy(signatureAction = action) else offer
                            })
                            reloadOffers()
                        },
                        onUpdateStep = { item, updatedStep ->
                            val steps = item.learnedSignature.map { step ->
                                if (step.stepIndex == updatedStep.stepIndex) updatedStep else step
                            }
                            OfferRepository.updateSignature(context, item.id, steps, item.signatureLearningCaptures)
                            reloadOffers()
                        },
                        onDeleteStep = { item, stepIndex ->
                            val steps = item.learnedSignature.filterNot { it.stepIndex == stepIndex }
                                .sortedBy { it.stepIndex }.mapIndexed { index, step -> step.copy(stepIndex = index) }
                            OfferRepository.updateSignature(context, item.id, steps, item.signatureLearningCaptures)
                            reloadOffers()
                        },
                        onMoveStep = { item, stepIndex, moveUp ->
                            val steps = item.learnedSignature.sortedBy { it.stepIndex }.toMutableList()
                            val from = steps.indexOfFirst { it.stepIndex == stepIndex }
                            val to = from + if (moveUp) -1 else 1
                            if (from >= 0 && to in steps.indices) {
                                val moved = steps.removeAt(from)
                                steps.add(to, moved)
                                OfferRepository.updateSignature(
                                    context, item.id,
                                    steps.mapIndexed { index, step -> step.copy(stepIndex = index) },
                                    item.signatureLearningCaptures
                                )
                                reloadOffers()
                            }
                        },
                        onInsertStep = { item, stepIndex ->
                            val steps = item.learnedSignature.sortedBy { it.stepIndex }.toMutableList()
                            val at = (steps.indexOfFirst { it.stepIndex == stepIndex } + 1).coerceIn(0, steps.size)
                            steps.add(at, UssdSignatureStep(stepIndex = at, menuTitle = "New menu step"))
                            OfferRepository.updateSignature(
                                context, item.id,
                                steps.mapIndexed { index, step -> step.copy(stepIndex = index) },
                                item.signatureLearningCaptures
                            )
                            reloadOffers()
                        },
                        onToggle = { item, enabled ->
                            OfferRepository.save(context, OfferRepository.load(context).map { offer ->
                                if (offer.id == item.id) offer.copy(signatureDetectionEnabled = enabled) else offer
                            })
                            reloadOffers()
                        },
                        onClear = { item ->
                            OfferRepository.clearManualSignature(context, item.id)
                            reloadOffers()
                            activity = SignatureActivityStore.record(context, item.name + ": Advanced signature cleared")
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
private fun SignatureTerminalHeader(
    tab: SignatureManagementTab,
    sync: Int,
    autoSyncPaused: Boolean,
    onBack: () -> Unit
) {
    val subtitle = when (tab) {
        SignatureManagementTab.LIVE_MENU ->
            if (autoSyncPaused) "Current carrier menu · auto-sync paused by carrier limit"
            else "Current carrier menu · auto-sync every 12 min"
        SignatureManagementTab.LEARN -> "Capture the menu position of an offer"
        SignatureManagementTab.SIMPLE -> "Silent verify, then run — no popups"
        SignatureManagementTab.MANUAL -> "Multi-step USSD navigation paths"
        SignatureManagementTab.OFFERS -> "Signature coverage across your catalogue"
    }
    Column {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = SignatureStudioColors.t1)
                }
                Box(Modifier.size(7.dp).background(SignatureStudioColors.cyan, CircleShape))
                Text("SIGNATURE STUDIO", color = SignatureStudioColors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
                Text(
                    if (autoSyncPaused) "SYNC PAUSED" else "SYNC %02d:%02d".format(sync / 60, sync % 60),
                    color = if (autoSyncPaused) SignatureStudioColors.amber else SignatureStudioColors.t2,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            Text("Signatures", color = SignatureStudioColors.t1, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Text(subtitle, color = SignatureStudioColors.t2, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SignatureStudioColors.border))
        Text(
            "▸ *180*5*2*0712345678# ▸ SILENT VERIFY ▸ MATCH name · aliases · data · duration · choice ▸ PRICE IGNORED ▸ EXECUTE ▸ ".repeat(3),
            Modifier.fillMaxWidth().background(SignatureStudioColors.ink).padding(vertical = 5.dp).basicMarquee(iterations = Int.MAX_VALUE, velocity = 40.dp),
            color = SignatureStudioColors.amber, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(SignatureStudioColors.border))
    }
}

@Composable
private fun SignatureKeycapNav(selected: SignatureManagementTab, onSelect: (SignatureManagementTab) -> Unit) {
    val entries = listOf(
        Triple(SignatureManagementTab.LIVE_MENU, "Live", Icons.Rounded.Sensors),
        Triple(SignatureManagementTab.LEARN, "Learn", Icons.Rounded.GpsFixed),
        Triple(SignatureManagementTab.SIMPLE, "Simple", Icons.Rounded.Bolt),
        Triple(SignatureManagementTab.MANUAL, "Advanced", Icons.Rounded.AccountTree),
        Triple(SignatureManagementTab.OFFERS, "Offers", Icons.Rounded.Sell)
    )
    Row(
        Modifier.fillMaxWidth().background(SignatureStudioColors.ink).navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        entries.forEach { (tab, label, icon) ->
            val active = selected == tab
            val shape = RoundedCornerShape(10.dp)
            val edge = if (active) SignatureStudioColors.amber else Color(0xFF0A0D10)
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(
                        Brush.verticalGradient(
                            if (active) listOf(Color(0xFF2A2418), Color(0xFF1B1A16))
                            else listOf(Color(0xFF222B34), Color(0xFF181F26))
                        )
                    )
                    .border(1.dp, if (active) Color(0xFF5B4522) else SignatureStudioColors.border, shape)
                    .drawBehind {
                        drawRect(edge, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
                    }
                    .clickable { onSelect(tab) }
                    .padding(top = 8.dp, bottom = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(icon, contentDescription = label, tint = if (active) SignatureStudioColors.amber else SignatureStudioColors.t2,
                    modifier = Modifier.size(20.dp))
                Text(label, color = if (active) SignatureStudioColors.amber else SignatureStudioColors.t2,
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SignatureStatusPill(label: String, active: Boolean, advanced: Boolean = false) {
    val tint = if (active) SignatureStudioColors.amber else if (advanced) SignatureStudioColors.cyan else SignatureStudioColors.t3
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Text(
            label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            softWrap = true
        )
    }
}
@Composable
private fun SignatureSectionIntro(eyebrow: String, title: String, description: String, accent: Color) {
    Column(
        Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(eyebrow, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .9.sp)
        Text(title, color = SignatureStudioColors.t1, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp)
        Text(description, color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun SignatureOffersPanel(offers: List<OfferItem>, selectedId: Int, onSelect: (Int) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("ALL") }
    val ready = offers.count { it.simpleSignatureEnabled || it.signatureDetectionEnabled }
    val attention = offers.count {
        !it.simpleSignatureEnabled && !it.signatureDetectionEnabled ||
            it.pendingLearnedSignature.isNotEmpty() || it.pendingSignatureLearningCaptures.isNotEmpty()
    }
    val visible = offers.filter { offer ->
        when (filter) {
            "READY" -> offer.simpleSignatureEnabled || offer.signatureDetectionEnabled
            "ATTENTION" -> (!offer.simpleSignatureEnabled && !offer.signatureDetectionEnabled) ||
                offer.pendingLearnedSignature.isNotEmpty() || offer.pendingSignatureLearningCaptures.isNotEmpty()
            "NEW" -> offer.learnedSignature.isEmpty() && offer.simpleLearnedSignature.isEmpty()
            else -> true
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignatureMetric(ready, "Ready", SignatureStudioColors.cyan, Modifier.weight(1f))
            SignatureMetric(attention, "Need action", SignatureStudioColors.red, Modifier.weight(1f))
            SignatureMetric(offers.size, "Total", SignatureStudioColors.t1, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL" to "All", "ATTENTION" to "Needs attention", "READY" to "Ready", "NEW" to "Not learned").forEach { (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(label, fontSize = 10.sp, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SignatureStudioColors.amber.copy(alpha = .16f),
                        selectedLabelColor = SignatureStudioColors.amber
                    )
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("YOUR OFFERS", color = SignatureStudioColors.t1, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            Text("${visible.size} shown", color = SignatureStudioColors.t3, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        if (visible.isEmpty()) {
            SignatureManagementCard("NOTHING TO SHOW", "No offers match this filter. Try another status.", SignatureStudioColors.cyan) {}
        } else visible.forEach { offer ->
            val simple = when {
                offer.simpleSignatureEnabled -> "Simple active"
                offer.simpleLearnedSignature.isNotEmpty() -> "Simple saved"
                else -> "Simple not set"
            }
            val advanced = when {
                offer.signatureDetectionEnabled -> "Advanced active"
                offer.learnedSignature.isNotEmpty() -> "Advanced saved"
                else -> "Advanced not set"
            }
            SignatureOfferPickerRow(
                offer = offer,
                selected = offer.id == selectedId,
                detail = "KES ${offer.price} · ${offer.category} · ${if (normalizeOfferExecutionMode(offer.executionMode, offer.category) == OFFER_EXECUTION_MODE_ADVANCED) "Advanced" else "Simple"}\n$simple · $advanced",
                status = when {
                    offer.pendingLearnedSignature.isNotEmpty() || offer.pendingSignatureLearningCaptures.isNotEmpty() -> "REVIEW"
                    offer.simpleSignatureEnabled || offer.signatureDetectionEnabled -> "READY"
                    else -> "NOT SET"
                },
                accent = SignatureStudioColors.amber,
                onClick = { onSelect(offer.id) }
            )
        }
    }
}

@Composable
private fun SignatureMetric(value: Int, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(SignatureStudioColors.card)
            .border(1.dp, SignatureStudioColors.border, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(value.toString(), color = accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = SignatureStudioColors.t2, fontSize = 10.sp, maxLines = 2, softWrap = true, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignaturePathKey(value: String, accent: Color) {
    Surface(Modifier.size(width = 34.dp, height = 32.dp), color = accent.copy(alpha = .10f),
        shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, accent.copy(alpha = .4f))) {
        Box(contentAlignment = Alignment.Center) {
            Text(value, color = accent, fontSize = if (value.length > 2) 8.sp else 12.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
}

@Composable
private fun SignatureManagementCard(title: String, description: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SignatureStudioColors.card,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SignatureStudioColors.border)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
            Text(description, color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 16.sp)
            content()
        }
    }
}

@Composable
private fun SignatureLiveMenuPanel(
    state: SignatureLiveMenuState,
    offers: List<OfferItem>,
    selectedOfferId: Int,
    onRefresh: () -> Unit,
    onSavePosition: (Int, String) -> Unit
) {
    val context = LocalContext.current
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    val issueKey = if (state.hasDiagnosticIssue) {
        "${state.lastAttemptAt}|${state.lastAttemptOutcome}|${state.lastAttemptError}"
    } else null

    LaunchedEffect(issueKey) {
        if (SignatureLiveMenuCoordinator.consumeAutoPresentIssueKey(state)) {
            showDiagnosticsDialog = true
        }
    }

    if (showDiagnosticsDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val accent = when {
                state.isCarrierRetryLimit || state.blockedBecauseRunning -> SignatureStudioColors.amber
                state.lastAttemptError.isNotBlank() -> SignatureStudioColors.red
                else -> SignatureStudioColors.cyan
            }
            Surface(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                color = SignatureStudioColors.bg,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, accent.copy(alpha = .55f))
            ) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(
                            Modifier.size(42.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = accent.copy(alpha = .13f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Info, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                when {
                                    state.isCarrierRetryLimit -> "Carrier retry limit reached"
                                    state.blockedBecauseRunning -> "Request already running"
                                    state.lastAttemptOutcome == "CALLBACK SUCCESS · NO PARSED OPTIONS" -> "No numbered menu options found"
                                    state.lastAttemptError.isNotBlank() -> "Live Menu refresh unsuccessful"
                                    else -> "Live Menu diagnostics"
                                },
                                color = SignatureStudioColors.t1,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text("Silent USSD · latest attempt report", color = SignatureStudioColors.t2, fontSize = 11.sp)
                        }
                        IconButton(onClick = { showDiagnosticsDialog = false }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Close diagnostics", tint = SignatureStudioColors.t2)
                        }
                    }

                    if (state.lastAttemptError.isNotBlank()) {
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = SignatureStudioColors.card,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, SignatureStudioColors.border)
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (state.isCarrierRetryLimit) "CARRIER RESPONSE EXPLANATION" else "WHAT HAPPENED",
                                    color = accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = .8.sp
                                )
                                Text(state.lastAttemptError, color = SignatureStudioColors.t1, fontSize = 13.sp, lineHeight = 19.sp)
                                if (state.previousMenuRetained) {
                                    Text(
                                        "Your last valid Live Menu (${state.validOptions.size} options) is preserved separately and marked stale so signatures remain reviewable.",
                                        color = SignatureStudioColors.t2,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        Modifier.fillMaxWidth(),
                        color = SignatureStudioColors.card,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SignatureStudioColors.border)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "TELEPHONY & PARSER DIAGNOSTICS",
                                color = SignatureStudioColors.cyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = .8.sp
                            )
                            DiagnosticRow("Method", state.lastAttemptMethod)
                            DiagnosticRow("Selected SIM", state.simDescription)
                            DiagnosticRow("Duration", "${state.lastAttemptDurationMs} ms")
                            DiagnosticRow("Callback outcome", state.lastAttemptOutcome)
                            DiagnosticRow(
                                "Raw response text",
                                if (state.rawResponseReceived) "Yes (${state.lastAttemptRawResponse.length} chars)"
                                else "No — not supplied by Android"
                            )
                            DiagnosticRow("Parsed options", state.lastParsedOptionCount.toString())
                            DiagnosticRow("Carrier retry-limit", if (state.isCarrierRetryLimit) "Yes" else "No")
                            DiagnosticRow(
                                "Prior menu retained",
                                if (state.previousMenuRetained) "Yes (${state.validOptions.size} options)" else "No"
                            )
                            DiagnosticRow("Blocked (overlap)", if (state.blockedBecauseRunning) "Yes" else "No")
                        }
                    }

                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "RAW CARRIER RESPONSE (COMPLETE)",
                            color = SignatureStudioColors.amber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = .8.sp
                        )
                        Surface(
                            Modifier.fillMaxSize(),
                            color = SignatureStudioColors.ink,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, SignatureStudioColors.border)
                        ) {
                            SelectionContainer {
                                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                                    val finalText = when {
                                        state.rawResponseReceived -> state.lastAttemptRawResponse
                                        state.blockedBecauseRunning ->
                                            "No request was sent because another silent USSD request was already running."
                                        else ->
                                            "Android supplied no carrier response text for this failed request (onReceiveUssdResponseFailed receives only a status code)."
                                    }
                                    Text(
                                        finalText,
                                        color = SignatureStudioColors.t1,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                showDiagnosticsDialog = false
                                onRefresh()
                            },
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignatureStudioColors.amber,
                                contentColor = Color.Black
                            )
                        ) { Text("Manual refresh", fontWeight = FontWeight.Bold) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText(
                                            "Live Menu Diagnostics",
                                            formatLiveMenuDiagnosticsSummary(state)
                                        )
                                    )
                                    Toast.makeText(context, "Diagnostics copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                            ) { Text("Copy report", color = SignatureStudioColors.cyan) }
                            OutlinedButton(
                                onClick = { showDiagnosticsDialog = false },
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                            ) { Text("Close", color = SignatureStudioColors.t2) }
                        }
                    }
                }
            }
        }
    }

    val selectedOffer = offers.firstOrNull { it.id == selectedOfferId }
    val options = state.validOptions.sortedBy { it.first }
    fun identityMatches(offer: OfferItem, label: String): Boolean {
        val step = offer.simpleLearnedSignature.firstOrNull { it.stepIndex == 0 }
            ?: offer.simpleLearnedSignature.firstOrNull() ?: return false
        val expected = step.selectedOptionLabel
        val liveIdentity = UssdSignatureMatchPolicy.normalize(
            Regex("(?i)\\b(?:kshs?|kes)\\s*[:.]?\\s*[0-9][0-9,.]*\\b|(?<!\\w)@[ ]*[0-9][0-9,.]*")
                .replace(label, " ")
        )
        if (liveIdentity.isBlank()) return false
        val candidates = buildList {
            add(expected)
            add(offer.name)
            addAll(offer.simpleSignatureAliases.split(Regex("[,;\\n]")))
            add(offer.bundleSize)
            add("${offer.bundleSize} ${offer.validityNumber} ${offer.validityUnit}")
        }.map { value ->
            UssdSignatureMatchPolicy.normalize(
                Regex("(?i)\\b(?:kshs?|kes)\\s*[:.]?\\s*[0-9][0-9,.]*\\b|(?<!\\w)@[ ]*[0-9][0-9,.]*")
                    .replace(value, " ")
            )
        }.filter { it.isNotBlank() }.distinct()
        val liveTokens = liveIdentity.split(' ').filter(String::isNotBlank).toSet()
        return candidates.any { candidate ->
            val tokens = candidate.split(' ').filter(String::isNotBlank).toSet()
            when (offer.simpleSignatureSensitivity.uppercase()) {
                "STRICT" -> candidate == liveIdentity
                "FLEXIBLE" -> candidate == liveIdentity || (tokens.size >= 2 &&
                    tokens.intersect(liveTokens).size >= maxOf(2, kotlin.math.ceil(tokens.size * .7).toInt()))
                else -> candidate == liveIdentity || (tokens.size >= 2 && liveTokens.containsAll(tokens))
            }
        }
    }

    val formattedLastSuccess = remember(state.lastSuccessAt) {
        if (state.lastSuccessAt > 0L) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(state.lastSuccessAt))
        } else ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            color = SignatureStudioColors.card,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SignatureStudioColors.border)
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Live Menu", color = SignatureStudioColors.t1, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            state.loading -> "Querying carrier silently via TelephonyManager…"
                            state.stale && state.hasValidMenu ->
                                "Stale · last valid $formattedLastSuccess (${options.size} options)"
                            state.hasValidMenu ->
                                "Last valid refresh $formattedLastSuccess · ${options.size} options"
                            state.isCarrierRetryLimit ->
                                "Carrier retry limit reached · auto-refresh paused"
                            state.lastAttemptError.isNotBlank() ->
                                "Latest request could not capture a menu"
                            else -> "Never retrieved"
                        },
                        color = SignatureStudioColors.t2,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (state.simDescription != "Not inspected yet") {
                        Text(
                            state.simDescription,
                            color = SignatureStudioColors.t3,
                            fontSize = 10.sp
                        )
                    }
                }
                SignatureStatusPill(
                    label = when {
                        state.loading -> "SYNCING"
                        state.autoRefreshPausedByCarrierLimit -> "PAUSED"
                        state.stale -> "STALE"
                        state.lastAttemptError.isNotBlank() -> "ERROR"
                        state.hasValidMenu -> "LIVE"
                        else -> "IDLE"
                    },
                    active = state.hasValidMenu && !state.stale && state.lastAttemptError.isBlank(),
                    advanced = state.loading || state.hasValidMenu
                )
                IconButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.size(40.dp)) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), color = SignatureStudioColors.cyan, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Refresh, "Manual refresh menu", tint = SignatureStudioColors.cyan)
                }
            }
        }

        if (state.hasDiagnosticIssue) {
            val alertColor = if (state.isCarrierRetryLimit || state.blockedBecauseRunning) SignatureStudioColors.amber else SignatureStudioColors.red
            Surface(
                Modifier.fillMaxWidth().clickable { showDiagnosticsDialog = true },
                color = alertColor.copy(alpha = .09f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, alertColor.copy(alpha = .4f))
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when {
                                state.isCarrierRetryLimit -> "CARRIER RETRY LIMIT · AUTO-REFRESH PAUSED"
                                state.blockedBecauseRunning -> "OVERLAPPING REQUEST BLOCKED"
                                state.stale -> "STALE MENU · LATEST REFRESH FAILED"
                                else -> "LATEST REFRESH DID NOT RETURN A MENU"
                            },
                            color = alertColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Diagnostics ▸", color = alertColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        state.lastAttemptError,
                        color = SignatureStudioColors.t1,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    if (state.rawResponseReceived) {
                        Text(
                            "Raw response (${state.lastAttemptDurationMs} ms): ${state.lastAttemptRawResponse}",
                            color = SignatureStudioColors.t2,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (state.stale && state.hasValidMenu) {
                        Text(
                            "Showing the last valid menu below for reference. Refresh cleanly before saving any position.",
                            color = SignatureStudioColors.t2,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        when {
            state.loading && options.isEmpty() -> {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    val pulse = rememberInfiniteTransition(label = "signature-live-pulse")
                        .animateFloat(.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(50.dp), color = SignatureStudioColors.cyan.copy(alpha = pulse), strokeWidth = 1.5.dp)
                        Box(Modifier.size(10.dp).background(SignatureStudioColors.cyan, CircleShape))
                    }
                    Text("Reading menu silently…", color = SignatureStudioColors.t2, fontSize = 12.sp)
                }
                repeat(4) {
                    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp))
                        .background(SignatureStudioColors.surface))
                }
            }
            state.hasDiagnosticIssue && options.isEmpty() -> {
                SignatureManagementCard(
                    title = if (state.isCarrierRetryLimit) "CARRIER RETRY LIMIT REACHED" else "COULD NOT UPDATE LIVE MENU",
                    description = if (state.isCarrierRetryLimit) {
                        "Auto-refresh is paused because the carrier returned its retry-limit notice. Wait before tapping Manual refresh."
                    } else {
                        "Inspect diagnostics for SIM status and callback details, then try Manual refresh."
                    },
                    accent = if (state.isCarrierRetryLimit) SignatureStudioColors.amber else SignatureStudioColors.red
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { showDiagnosticsDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("View diagnostics", color = SignatureStudioColors.cyan)
                        }
                        Button(
                            onClick = onRefresh,
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignatureStudioColors.amber,
                                contentColor = Color.Black
                            )
                        ) { Text("Manual refresh", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            options.isEmpty() -> {
                SignatureManagementCard(
                    "NO MENU RETRIEVED YET",
                    "Fetch the live menu silently to compare it with your saved offer signatures.",
                    SignatureStudioColors.t2
                ) {
                    Button(
                        onClick = onRefresh,
                        enabled = !state.loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SignatureStudioColors.amber,
                            contentColor = Color.Black
                        )
                    ) { Text("Fetch live menu", fontWeight = FontWeight.Bold) }
                }
            }
            else -> {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = Color(0xFF10282A),
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, Color(0xFF245A55))
                ) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Info, null, tint = Color(0xFFA9EFE6), modifier = Modifier.size(16.dp))
                        Text(
                            "Matched using name, aliases, data & duration plus the saved choice. Prices are never compared.",
                            color = Color(0xFFA9EFE6),
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                Text(
                    "PAGE 1 · CHOICES 1–7",
                    color = SignatureStudioColors.t2,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
                options.filter { it.first <= 7 }.forEach { (position, label) ->
                    val matchingOffers = if (state.stale) emptyList() else offers.filter { offer ->
                        offer.simpleLearnedSignature.isNotEmpty() && identityMatches(offer, label)
                    }
                    val matchOffer = matchingOffers.firstOrNull { offer ->
                        offer.simpleLearnedSignature.firstOrNull()?.expectedInput?.toIntOrNull() == position
                    }
                    val movedOffer = matchingOffers.firstOrNull { offer ->
                        offer.simpleLearnedSignature.firstOrNull()?.expectedInput?.toIntOrNull() != position
                    }
                    val identityOffer = matchOffer ?: movedOffer
                    val savedPosition = identityOffer?.simpleLearnedSignature?.firstOrNull()?.expectedInput?.toIntOrNull()
                    val match = matchOffer != null
                    val moved = !match && movedOffer != null
                    Surface(
                        Modifier.fillMaxWidth().then(
                            if (!state.stale && !state.loading && selectedOffer != null && !match && !moved)
                                Modifier.clickable { onSavePosition(position, label) } else Modifier
                        ),
                        color = SignatureStudioColors.card,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (match) SignatureStudioColors.green.copy(alpha = .4f) else SignatureStudioColors.border)
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SignaturePathKey(position.toString(), if (match) SignatureStudioColors.green else SignatureStudioColors.amber)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(label, color = SignatureStudioColors.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        match -> "${matchOffer?.name} · saved choice $position · ready"
                                        moved -> "${movedOffer?.name} · saved at $savedPosition · now listed at $position"
                                        state.stale -> "Unavailable to save until a fresh menu succeeds"
                                        selectedOffer != null -> "Tap to save for ${selectedOffer.name}"
                                        else -> "Learn to enable"
                                    },
                                    color = SignatureStudioColors.t2,
                                    fontSize = 12.sp
                                )
                            }
                            SignatureStatusPill(
                                when {
                                    match -> "MATCHES"
                                    moved -> "MOVED"
                                    state.stale -> "STALE"
                                    else -> "NO SIGNATURE"
                                },
                                match,
                                !match && !moved
                            )
                        }
                    }
                }
                val pageTwo = options.filter { it.first > 7 }
                if (pageTwo.isNotEmpty()) {
                    Text(
                        "PAGE 2 · CHOICE 8+",
                        color = SignatureStudioColors.t2,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    pageTwo.forEach { (position, label) ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            color = SignatureStudioColors.card,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, SignatureStudioColors.border)
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SignaturePathKey(position.toString(), SignatureStudioColors.t3)
                                Column(Modifier.weight(1f)) {
                                    Text(label, color = SignatureStudioColors.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Not available for simple execution", color = SignatureStudioColors.t2, fontSize = 12.sp)
                                }
                                SignatureStatusPill("NEXT PAGE", false)
                            }
                        }
                    }
                }
                if (state.lastAttemptAt > 0L) {
                    TextButton(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Inspect latest Live Menu diagnostics (${state.lastAttemptDurationMs} ms)", color = SignatureStudioColors.t2, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            color = SignatureStudioColors.t2,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(126.dp)
        )
        Text(
            value,
            color = SignatureStudioColors.t1,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
private fun SimpleSignatureSettingsPanel(
    offers: List<OfferItem>,
    onOpenLiveMenu: (Int) -> Unit,
    onSaveAliases: (OfferItem, String, String) -> Unit,
    onToggle: (OfferItem, Boolean) -> Unit,
    onClear: (OfferItem) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.card, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SignatureStudioColors.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How simple execution works", color = SignatureStudioColors.t1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(Modifier.weight(1f), color = SignatureStudioColors.surface, shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, SignatureStudioColors.amber.copy(alpha = .4f))) {
                        Text("① Verify menu · silent", Modifier.padding(9.dp), color = SignatureStudioColors.amber,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                    Surface(Modifier.weight(1f), color = SignatureStudioColors.surface, shape = RoundedCornerShape(9.dp),
                        border = BorderStroke(1.dp, SignatureStudioColors.cyan.copy(alpha = .4f))) {
                        Text("② Run code · on match", Modifier.padding(9.dp), color = SignatureStudioColors.cyan,
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
                Text("Verification uses 0712345678 as a placeholder. Choices 1–7 are on page 1; later choices cannot run silently. Prices are ignored when matching offers.",
                    color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SignatureMetric(offers.count { it.simpleSignatureEnabled }, "Active", SignatureStudioColors.cyan, Modifier.weight(1f))
            SignatureMetric(offers.count { it.simpleLearnedSignature.isNotEmpty() }, "Saved", SignatureStudioColors.amber, Modifier.weight(1f))
            SignatureMetric(offers.size, "Offers", SignatureStudioColors.t1, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${offers.size} SIMPLE OFFERS", color = SignatureStudioColors.t1, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            Text("${offers.count { it.simpleSignatureEnabled }} ready", color = SignatureStudioColors.cyan, fontSize = 10.sp)
        }
        if (offers.isEmpty()) {
            SignatureManagementCard("NO OFFERS AVAILABLE", "Create an offer first, then return here to configure its Simple signature.", SignatureStudioColors.amber) {}
        } else offers.forEach { offer ->
            var expanded by rememberSaveable(offer.id) { mutableStateOf(false) }
            var aliases by rememberSaveable(offer.id) { mutableStateOf(offer.simpleSignatureAliases.ifBlank { offer.name }) }
            var sensitivity by rememberSaveable(offer.id) { mutableStateOf(offer.simpleSignatureSensitivity) }
            val step = offer.simpleLearnedSignature.firstOrNull()
            val position = step?.expectedInput?.toIntOrNull() ?: 0
            Surface(
                Modifier.fillMaxWidth(), color = SignatureStudioColors.card, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (offer.simpleSignatureEnabled) SignatureStudioColors.amber.copy(alpha = .55f) else SignatureStudioColors.border)
            ) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(Modifier.size(34.dp), color = if (position in 1..7) SignatureStudioColors.amber.copy(alpha = .13f) else SignatureStudioColors.surface,
                            shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, SignatureStudioColors.border)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(if (position > 0) position.toString() else "—", color = if (position in 1..7) SignatureStudioColors.amber else SignatureStudioColors.t3,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(offer.name, color = SignatureStudioColors.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (step == null) "No menu position saved" else "Position $position · ${step.selectedOptionLabel.ifBlank { "Saved choice" }}",
                                color = SignatureStudioColors.t2, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SignatureStatusPill(if (position > 7) "UNAVAILABLE" else if (offer.simpleSignatureEnabled) "ACTIVE" else if (step != null) "SAVED" else "NOT SET",
                                offer.simpleSignatureEnabled && position in 1..7)
                            Switch(checked = offer.simpleSignatureEnabled, onCheckedChange = { onToggle(offer, it) },
                                enabled = offer.simpleLearnedSignature.isNotEmpty() && position in 1..7)
                        }
                    }
                    if (position > 7) {
                        Text("Choice $position is on menu page 2. Simple execution is unavailable.", color = SignatureStudioColors.red, fontSize = 10.sp)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (expanded) "Signature settings" else "Manage signature", color = SignatureStudioColors.t2, fontSize = 10.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                            Text(if (expanded) "Collapse" else "Configure", color = SignatureStudioColors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (expanded) {
                        HorizontalDivider(color = SignatureStudioColors.border)
                        OutlinedTextField(value = aliases, onValueChange = { aliases = it }, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Offer identity aliases · no prices") },
                            supportingText = { Text("Use key terms such as 1GB, 1 hour, 250MB or 24 hours. Separate alternatives with commas.") },
                            minLines = 1, maxLines = 2, shape = RoundedCornerShape(11.dp))
                        Text("MATCHING SENSITIVITY", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("STRICT" to "Strict", "BALANCED" to "Balanced", "FLEXIBLE" to "Flexible").forEach { (value, label) ->
                                FilterChip(selected = sensitivity == value, onClick = { sensitivity = value }, modifier = Modifier.weight(1f),
                                    label = { Text(label, fontSize = 9.sp, maxLines = 1) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button(onClick = { onSaveAliases(offer, aliases, sensitivity) }, modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SignatureStudioColors.amber, contentColor = SignatureStudioColors.bg)) {
                                Text("Save settings", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                            OutlinedButton(onClick = { onOpenLiveMenu(offer.id) }, modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 7.dp)) {
                                Text(if (step == null) "Learn signature" else "Update signature", fontSize = 10.sp, maxLines = 1)
                            }
                        }
                        if (step != null) {
                            Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.surface, shape = RoundedCornerShape(10.dp)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("SAVED MENU SIGNATURE", color = SignatureStudioColors.amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text("Position $position · ${step.selectedOptionLabel.ifBlank { "Saved choice" }}", color = SignatureStudioColors.t1, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    if (step.menuText.isNotBlank()) SignatureReadOnlyText(step.menuText)
                                    step.menuOptionsSnapshot.forEach { option -> Text(option, color = SignatureStudioColors.t2, fontSize = 10.sp) }
                                }
                            }
                            TextButton(onClick = { onClear(offer) }, modifier = Modifier.align(Alignment.End),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                Text("Remove signature", color = SignatureStudioColors.red, fontSize = 10.sp)
                            }
                        }
                        Text("Verification runs silently. Prices are not used to identify offers.", color = SignatureStudioColors.t3, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignatureOfferPickerRow(
    offer: OfferItem,
    selected: Boolean,
    detail: String,
    status: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) accent.copy(alpha = 0.09f) else SignatureStudioColors.card,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.85f) else SignatureStudioColors.border)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (selected) accent.copy(alpha = 0.18f) else SignatureStudioColors.surface,
                border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.55f) else SignatureStudioColors.border)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (selected) "✓" else offer.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•",
                        color = if (selected) accent else SignatureStudioColors.t2,
                        fontSize = if (selected) 17.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    offer.name,
                    color = SignatureStudioColors.t1,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
                Text(
                    detail,
                    color = SignatureStudioColors.t2,
                    fontSize = 9.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Surface(
                    color = if (selected) accent.copy(alpha = 0.16f) else SignatureStudioColors.surface,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        status,
                        color = if (selected) accent else SignatureStudioColors.t3,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        maxLines = 1
                    )
                }
                Text(
                    if (selected) "Selected" else "Choose",
                    color = if (selected) accent else SignatureStudioColors.t3,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SignatureLearnPanel(
    offers: List<OfferItem>,
    selectedOfferId: Int,
    learning: Boolean,
    onSelectOffer: (Int) -> Unit,
    onLearn: () -> Unit,
    onApprove: () -> Unit,
    onDiscardPending: () -> Unit
) {
    var sessionOpen by rememberSaveable { mutableStateOf(false) }
    val learnScrollState = rememberScrollState()
    val selected = offers.firstOrNull { it.id == selectedOfferId }
    LaunchedEffect(selectedOfferId, sessionOpen) {
        if (sessionOpen) learnScrollState.animateScrollTo(0)
    }
    if (!sessionOpen || selected == null) {
        Column(
            Modifier.fillMaxSize().verticalScroll(learnScrollState)
                .padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignatureMetric(offers.count { it.pendingLearnedSignature.isNotEmpty() || it.pendingSignatureLearningCaptures.isNotEmpty() },
                    "To review", SignatureStudioColors.amber, Modifier.weight(1f))
                SignatureMetric(offers.count { it.learnedSignature.isEmpty() && it.simpleLearnedSignature.isEmpty() },
                    "New", SignatureStudioColors.t1, Modifier.weight(1f))
                SignatureMetric(offers.count { it.learnedSignature.isNotEmpty() || it.simpleLearnedSignature.isNotEmpty() },
                    "Saved", SignatureStudioColors.cyan, Modifier.weight(1f))
            }
            SignatureSectionIntro(
                eyebrow = "SIGNATURE LEARNING",
                title = "Choose an offer to learn",
                description = "Select the bundle you want to teach Bingwa. Learning records carrier menu text and choices for review; it does not submit a purchase. Verification uses 0712345678.",
                accent = SignatureStudioColors.cyan
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("AVAILABLE OFFERS", color = SignatureStudioColors.t1, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Tap one offer to open its learning session", color = SignatureStudioColors.t3, fontSize = 10.sp)
                }
                Text("${offers.size} total", color = SignatureStudioColors.t2, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (offers.isEmpty()) {
                SignatureManagementCard("NO OFFERS AVAILABLE", "Create an offer first. It will appear here for signature learning.", SignatureStudioColors.cyan) {}
            } else {
                offers.forEach { offer ->
                    val active = offer.id == selectedOfferId
                    val pending = offer.pendingLearnedSignature.isNotEmpty() || offer.pendingSignatureLearningCaptures.isNotEmpty()
                    val detail = when {
                        offer.learnedSignature.isNotEmpty() -> "${offer.learnedSignature.size} approved steps · ready to relearn"
                        pending -> "New capture awaiting review"
                        offer.ussdCode.isBlank() -> "USSD code required before learning"
                        else -> "Not learned yet · ready to set up"
                    }
                    val status = when {
                        pending -> "REVIEW"
                        offer.learnedSignature.isNotEmpty() -> "LEARNED"
                        else -> "NEW"
                    }
                    SignatureOfferPickerRow(offer, active, detail, status, SignatureStudioColors.cyan) {
                        onSelectOffer(offer.id)
                        sessionOpen = true
                    }
                }
            }
        }
        return
    }

    val pending = selected.pendingLearnedSignature.isNotEmpty() || selected.pendingSignatureLearningCaptures.isNotEmpty()
    val phaseIndex = when {
        pending -> 2
        learning -> 1
        selected.learnedSignature.isNotEmpty() -> 2
        else -> 0
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(learnScrollState)
            .padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TextButton(onClick = { sessionOpen = false }, contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)) {
            Icon(Icons.Rounded.ArrowBack, null, tint = SignatureStudioColors.t2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("All offers", color = SignatureStudioColors.t2, fontSize = 11.sp)
        }
        Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.card, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SignatureStudioColors.border)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("LEARNING", color = SignatureStudioColors.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace)
                        Text(selected.name, color = SignatureStudioColors.t1, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    SignatureStatusPill(
                        when {
                            pending -> "PENDING REVIEW"
                            learning -> "LEARNING"
                            selected.learnedSignature.isNotEmpty() -> "APPROVED"
                            else -> "NOT LEARNED"
                        },
                        selected.signatureDetectionEnabled || selected.learnedSignature.isNotEmpty(),
                        true
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("1 Select", "2 Learn", "3 Review").forEachIndexed { index, label ->
                        val accent = when {
                            index < phaseIndex -> SignatureStudioColors.cyan
                            index == phaseIndex -> SignatureStudioColors.amber
                            else -> SignatureStudioColors.t3
                        }
                        Surface(Modifier.weight(1f), color = SignatureStudioColors.surface, shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, if (index <= phaseIndex) accent.copy(alpha = .5f) else SignatureStudioColors.border)) {
                            Text(label, Modifier.padding(vertical = 7.dp), color = accent, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        }
                    }
                }
                if (pending) {
                    Text("SIGNATURE PREVIEW", color = SignatureStudioColors.t3, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.ink, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SignatureStudioColors.border)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row { Text("name", color = SignatureStudioColors.t2, fontSize = 11.sp, modifier = Modifier.width(78.dp), fontFamily = FontFamily.Monospace)
                                Text(selected.name, color = SignatureStudioColors.cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            Row { Text("mode", color = SignatureStudioColors.t2, fontSize = 11.sp, modifier = Modifier.width(78.dp), fontFamily = FontFamily.Monospace)
                                Text("Advanced", color = SignatureStudioColors.cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                            Row { Text("price", color = SignatureStudioColors.t2, fontSize = 11.sp, modifier = Modifier.width(78.dp), fontFamily = FontFamily.Monospace)
                                Text("KES ${selected.price} · ignored", color = SignatureStudioColors.red, fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) }
                        }
                    }
                    selected.pendingLearnedSignature.forEach { step ->
                        AdvancedStepDetail(step.stepIndex + 1, step.expectedInput, step.menuTitle,
                            step.menuText, step.menuOptionsSnapshot, step.selectedOptionLabel, true)
                    }
                    selected.pendingSignatureLearningCaptures.forEachIndexed { index, capture ->
                        AdvancedStepDetail(index + 1, capture.enteredInput, "Captured popup",
                            capture.popupText, emptyList(), capture.selectedOptionLabel, true)
                    }
                    Text("Approving replaces the previous saved path. Rejecting leaves the existing approved signature unchanged.",
                        color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 15.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDiscardPending, modifier = Modifier.weight(1f)) {
                            Text("Reject", color = SignatureStudioColors.red)
                        }
                        Button(onClick = onApprove, modifier = Modifier.weight(1f),
                            enabled = selected.pendingLearnedSignature.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = SignatureStudioColors.amber, contentColor = Color.Black)) {
                            Text("Approve", fontWeight = FontWeight.Bold)
                        }
                    }
                } else if (learning) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(48.dp), color = SignatureStudioColors.cyan, strokeWidth = 2.dp)
                        Text("Learning through carrier USSD…", color = SignatureStudioColors.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Complete the carrier prompts on your phone. Bingwa is recording the menu path for review; no purchase is submitted.",
                            color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 16.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    Text("The app starts the saved USSD code, captures menu text and choices, then stages the result for your approval. Learning does not submit a purchase.",
                        color = SignatureStudioColors.t2, fontSize = 12.sp, lineHeight = 17.sp)
                    Text("USSD CODE", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text(if (selected.ussdCode.isBlank()) "Not configured" else selected.ussdCode,
                        color = if (selected.ussdCode.isBlank()) SignatureStudioColors.red else SignatureStudioColors.amber,
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Button(onClick = onLearn, enabled = selected.ussdCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SignatureStudioColors.amber, contentColor = Color.Black)) {
                        Text(if (selected.learnedSignature.isEmpty()) "Start learning" else "Relearn signature",
                            fontWeight = FontWeight.Bold)
                    }
                    if (selected.ussdCode.isBlank()) Text("Add a USSD code to this offer before learning.",
                        color = SignatureStudioColors.red, fontSize = 10.sp)
                }
            }
        }
    }
}


@Composable
private fun ManualSignatureSettingsPanel(
    offers: List<OfferItem>,
    selectedOfferId: Int,
    learning: Boolean,
    onSelectOffer: (Int) -> Unit,
    onLearn: () -> Unit,
    onApprove: () -> Unit,
    onDiscardPending: () -> Unit,
    onChangeAction: (OfferItem, String) -> Unit,
    onUpdateStep: (OfferItem, UssdSignatureStep) -> Unit,
    onDeleteStep: (OfferItem, Int) -> Unit,
    onMoveStep: (OfferItem, Int, Boolean) -> Unit,
    onInsertStep: (OfferItem, Int) -> Unit,
    onToggle: (OfferItem, Boolean) -> Unit,
    onClear: (OfferItem) -> Unit
) {
    val selected = offers.firstOrNull { it.id == selectedOfferId }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            offers.forEach { item ->
                val active = item.id == selectedOfferId
                Surface(
                    color = if (active) Color(0xFF241F15) else SignatureStudioColors.card,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, if (active) Color(0xFF5B4522) else SignatureStudioColors.border),
                    modifier = Modifier.clickable { onSelectOffer(item.id) }
                ) {
                    Text(item.name, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (active) SignatureStudioColors.amber else SignatureStudioColors.t2,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (selected == null) {
            SignatureManagementCard("NO OFFERS AVAILABLE", "Create an offer first. It will appear here so you can learn its multi-step signature.", SignatureStudioColors.cyan) {}
            return@Column
        }
        Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.card, shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, SignatureStudioColors.border)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(selected.name, color = SignatureStudioColors.t1, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${selected.learnedSignature.size} steps · KES ${selected.price}", color = SignatureStudioColors.t2, fontSize = 10.sp)
                }
                SignatureStatusPill(
                    when {
                        selected.pendingLearnedSignature.isNotEmpty() || selected.pendingSignatureLearningCaptures.isNotEmpty() -> "PENDING REVIEW"
                        selected.signatureDetectionEnabled -> "ACTIVE"
                        selected.learnedSignature.isNotEmpty() -> "SAVED"
                        else -> "NOT LEARNED"
                    },
                    selected.signatureDetectionEnabled,
                    true
                )
            }
        }
        Text("EXECUTION PATH", color = SignatureStudioColors.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        if (selected.learnedSignature.isEmpty()) {
            SignatureManagementCard("NO STEPS YET",
                "Learn the path automatically or add menu steps manually. New captures are staged for review before they replace an approved signature.",
                SignatureStudioColors.cyan) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLearn, modifier = Modifier.weight(1f),
                        enabled = !learning && selected.ussdCode.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = SignatureStudioColors.cyan, contentColor = Color.Black)) {
                        if (learning) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.Black)
                        else Text("Learn path")
                    }
                    OutlinedButton(onClick = { onInsertStep(selected, -1) }, modifier = Modifier.weight(1f)) {
                        Text("Add step")
                    }
                }
            }
        } else {
            Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.surface, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SignatureStudioColors.border)) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        SignaturePathKey("DIAL", SignatureStudioColors.amber)
                        Column(Modifier.weight(1f)) {
                            Text(selected.ussdCode, color = SignatureStudioColors.amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("Entry code", color = SignatureStudioColors.t3, fontSize = 9.sp)
                        }
                    }
                    selected.learnedSignature.sortedBy { it.stepIndex }.forEachIndexed { index, step ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                SignaturePathKey((step.stepIndex + 1).toString(), SignatureStudioColors.cyan)
                                if (index < selected.learnedSignature.lastIndex) {
                                    Spacer(Modifier.width(2.dp).height(20.dp).background(SignatureStudioColors.border))
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                EditableAdvancedStepDetail(
                                    step = step,
                                    onSave = { onUpdateStep(selected, it) },
                                    onDelete = { onDeleteStep(selected, step.stepIndex) },
                                    onMoveUp = { onMoveStep(selected, step.stepIndex, true) },
                                    onMoveDown = { onMoveStep(selected, step.stepIndex, false) },
                                    onInsertAfter = { onInsertStep(selected, step.stepIndex) }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        SignaturePathKey("RUN", SignatureStudioColors.green)
                        Column {
                            Text("Executes offer", color = SignatureStudioColors.t1, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("Path complete after ${selected.learnedSignature.size} step(s)", color = SignatureStudioColors.t3, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
        if (selected.pendingLearnedSignature.isNotEmpty() || selected.pendingSignatureLearningCaptures.isNotEmpty()) {
            SignatureManagementCard("NEW CAPTURE · REVIEW BEFORE SAVING",
                "Your approved signature remains unchanged until you approve this new capture.", SignatureStudioColors.amber) {
                selected.pendingLearnedSignature.forEach { step ->
                    AdvancedStepDetail(step.stepIndex + 1, step.expectedInput, step.menuTitle, step.menuText, step.menuOptionsSnapshot, step.selectedOptionLabel, true)
                }
                selected.pendingSignatureLearningCaptures.forEachIndexed { index, capture ->
                    AdvancedStepDetail(index + 1, capture.enteredInput, "Captured popup", capture.popupText, emptyList(), capture.selectedOptionLabel, true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f), enabled = selected.pendingLearnedSignature.isNotEmpty()) { Text("Approve & save") }
                    OutlinedButton(onClick = onDiscardPending, modifier = Modifier.weight(1f)) { Text("Discard") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SIGNATURE EXECUTION", color = SignatureStudioColors.t1, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Enable this learned multi-step path", color = SignatureStudioColors.t3, fontSize = 9.sp)
            }
            Switch(checked = selected.signatureDetectionEnabled, onCheckedChange = { onToggle(selected, it) },
                enabled = selected.learnedSignature.isNotEmpty())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onLearn, modifier = Modifier.weight(1f), enabled = !learning && selected.ussdCode.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SignatureStudioColors.cyan, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                if (learning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.Black)
                else Text("Relearn", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = { onClear(selected) }, modifier = Modifier.weight(1f),
                enabled = selected.learnedSignature.isNotEmpty() || selected.pendingLearnedSignature.isNotEmpty() || selected.pendingSignatureLearningCaptures.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp)) {
                Text("Clear", color = SignatureStudioColors.red, fontSize = 11.sp)
            }
        }
    }
}


@Composable
private fun EditableAdvancedStepDetail(
    step: UssdSignatureStep,
    onSave: (UssdSignatureStep) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onInsertAfter: () -> Unit
) {
    var expanded by rememberSaveable(step.stepIndex) { mutableStateOf(false) }
    var response by remember(step) { mutableStateOf(step.expectedInput) }
    var selectedOption by remember(step) { mutableStateOf(step.selectedOptionLabel) }
    var title by remember(step) { mutableStateOf(step.menuTitle) }
    var popupText by remember(step) { mutableStateOf(step.menuText) }
    Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SignatureStudioColors.border)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("STEP ${step.stepIndex + 1}  ·  INPUT ${step.expectedInput.ifBlank { "—" }}", color = SignatureStudioColors.cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    Text(step.selectedOptionLabel.ifBlank { step.menuTitle.ifBlank { "Captured USSD step" } }, color = SignatureStudioColors.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(if (expanded) "Hide" else "Edit", color = SignatureStudioColors.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("EXTRACTED POPUP TEXT", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            if (step.menuText.isNotBlank()) SignatureReadOnlyText(step.menuText)
            if (step.menuOptionsSnapshot.isNotEmpty()) {
                Text("AVAILABLE OPTIONS", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                step.menuOptionsSnapshot.forEach { Text(it, color = SignatureStudioColors.t2, fontSize = 10.sp, lineHeight = 15.sp) }
            }
            if (step.selectedOptionLabel.isNotBlank()) {
                Surface(color = SignatureStudioColors.cyan.copy(alpha = .10f), shape = RoundedCornerShape(8.dp)) {
                    Text("SELECTED OPTION  ·  ${step.selectedOptionLabel}", color = SignatureStudioColors.cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(9.dp))
                }
            }
            if (expanded) {
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Menu title") }, singleLine = true)
                OutlinedTextField(value = popupText, onValueChange = { popupText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Extracted popup text") }, minLines = 3)
                OutlinedTextField(value = selectedOption, onValueChange = { selectedOption = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Selected option / offer identity") }, singleLine = true)
                OutlinedTextField(value = response, onValueChange = { response = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(), label = { Text("Option number entered") }, singleLine = true)
                Button(onClick = { onSave(step.copy(expectedInput = response, selectedOptionLabel = selectedOption, menuTitle = title, menuText = popupText)) }, modifier = Modifier.fillMaxWidth()) { Text("Save step changes") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedButton(onClick = onInsertAfter, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("+ Insert", fontSize = 10.sp) }
                    OutlinedButton(onClick = onMoveUp, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("↑ Up", fontSize = 10.sp) }
                    OutlinedButton(onClick = onMoveDown, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("↓ Down", fontSize = 10.sp) }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("Delete", fontSize = 10.sp, color = SignatureStudioColors.red) }
                }
            }
        }
    }
}

@Composable
private fun AdvancedStepDetail(
    index: Int,
    response: String,
    title: String,
    popupText: String,
    options: List<String>,
    selectedOption: String,
    pending: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("STEP ${index}  ·  RESPONSE ${response.ifBlank { "—" }}", color = SignatureStudioColors.cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            if (pending) SignatureStatusPill("PENDING", false, true)
        }
        if (title.isNotBlank()) Text(title, color = SignatureStudioColors.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (popupText.isNotBlank()) {
            Text("FULL EXTRACTED POPUP TEXT", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            SignatureReadOnlyText(popupText)
        } else {
            Text("No popup text was stored for this step.", color = SignatureStudioColors.t3, fontSize = 11.sp)
        }
        if (options.isNotEmpty()) {
            Text("AVAILABLE OPTIONS", color = SignatureStudioColors.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            options.forEach { option -> Text(option, color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 16.sp) }
        }
        if (selectedOption.isNotBlank()) {
            Surface(color = SignatureStudioColors.cyan.copy(alpha = .10f), shape = RoundedCornerShape(9.dp)) {
                Text("SELECTED OPTION  ·  $selectedOption", color = SignatureStudioColors.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().padding(10.dp))
            }
        }
    }
}

@Composable
private fun SignatureReadOnlyText(value: String) {
    SelectionContainer {
        Surface(Modifier.fillMaxWidth(), color = SignatureStudioColors.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, SignatureStudioColors.border)) {
            Text(value, color = SignatureStudioColors.t2, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(11.dp))
        }
    }
}

