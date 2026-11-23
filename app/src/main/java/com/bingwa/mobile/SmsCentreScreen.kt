package com.bingwa.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SmsCentreScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contacts = remember { mutableStateListOf<SavedContact>().apply { addAll(SavedContactStore.load(context)) } }
    var connectionInput by remember { mutableStateOf("") }
    val initialSmsConnection = remember { SmsCredentialVault.load(context) }
    val initialSender = initialSmsConnection?.let { connection ->
        if (connection.provider == SmsProvider.TALKSASA) connection.senderId.ifBlank { "TALKSASA" } else connection.senderId
    }.orEmpty()
    var sender by remember { mutableStateOf(initialSender) }
    var savedSender by remember { mutableStateOf(initialSender) }
    var showProviderSaveDialog by remember { mutableStateOf(false) }
    var showDisconnectProviderDialog by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(SmsCredentialVault.load(context)?.provider) }
    var connected by remember { mutableStateOf(SmsCredentialVault.load(context) != null) }
    var status by remember { mutableStateOf("") }
    val phoneImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val imported = readSmsPhoneContacts(context)
            contacts.clear()
            contacts.addAll(SavedContactStore.merge(context, imported))
            status = "Imported ${imported.size} valid contacts."
        } else status = "Contacts permission denied."
    }
    var verifying by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var showSendConfirmation by remember { mutableStateOf(false) }
    var deletingGroup by remember { mutableStateOf<SmsContactGroup?>(null) }
    var message by remember { mutableStateOf("") }
    var showMessageEditor by rememberSaveable { mutableStateOf(false) }
    var manualNumber by remember { mutableStateOf("") }
    var detectedRecipientName by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var selectedCustomerPhone by rememberSaveable { mutableStateOf<String?>(null) }
    var clearCustomerHistoryPhone by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var bulkGroupDialog by remember { mutableStateOf(false) }
    var recipientMode by remember { mutableStateOf("Individual") }
    var showRecipientPicker by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(10) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var contactsFilter by rememberSaveable { mutableStateOf("All") }
    var groups by remember { mutableStateOf(SmsContactGroupStore.load(context)) }
    var groupName by remember { mutableStateOf("") }
    var groupDialog by remember { mutableStateOf(false) }
    var groupCreationSearch by remember { mutableStateOf("") }
    var groupCreationSelectedPhones by remember { mutableStateOf(setOf<String>()) }
    var showManualContactDialog by remember { mutableStateOf(false) }
    var newContactName by remember { mutableStateOf("") }
    var newContactPhone by remember { mutableStateOf("") }
    var groupFromSelection by remember { mutableStateOf(false) }
    var groupTemplateId by remember { mutableStateOf<String?>(null) }
    var selectedGroups by remember { mutableStateOf(setOf<String>()) }
    val selectedValidGroupPhones = selected.mapNotNull(::normalizeKenyanSmsNumber).toSet()
    val duplicateGroupName = groupName.trim().isNotEmpty() &&
        groups.any { it.name.equals(groupName.trim(), ignoreCase = true) }
    var notificationsEnabled by remember { mutableStateOf(SmsNotificationPreferences.enabled(context)) }
    var failureNotifications by remember { mutableStateOf(SmsNotificationPreferences.sendFailures(context)) }
    var successText by remember { mutableStateOf(SmsNotificationPreferences.template(context, "success")) }
    var failureText by remember { mutableStateOf(SmsNotificationPreferences.template(context, "failure")) }
    val savedBusiness = remember { SmsMarketingTemplates.businessDetails(context) }
    var businessName by remember { mutableStateOf(savedBusiness.first.ifBlank { "Bingwa Sokoni" }) }
    var tillNumber by remember { mutableStateOf(savedBusiness.second) }
    var helpLine by remember { mutableStateOf(savedBusiness.third) }
    var pendingMarketingTemplate by remember { mutableStateOf<SmsMarketingTemplate?>(null) }
    var showBusinessDetailsDialog by remember { mutableStateOf(false) }
    var consentPhone by remember { mutableStateOf("") }
    var editingGroup by remember { mutableStateOf<SmsContactGroup?>(null) }
    var editingGroupName by remember { mutableStateOf("") }
    var scheduleDelay by remember { mutableStateOf("10") }
    var historyRefresh by remember { mutableIntStateOf(0) }
    var dashboard by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var dayBoundary by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            val nextMidnight = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            delay((nextMidnight - System.currentTimeMillis()).coerceAtLeast(1_000L))
            dayBoundary = System.currentTimeMillis()
        }
    }
    var dashboardLoading by remember { mutableStateOf(false) }
    var balanceError by remember { mutableStateOf<String?>(null) }

    // Keep the provider connection and SMS balance fresh automatically while the
    // Overview or Send workspace is visible. Provider API tokens are credentials,
    // not renewable sessions; a rejected token is surfaced without exposing it.
    LaunchedEffect(tab, connected, provider) {
        if ((tab == 7 || tab == 10) && connected) {
            suspend fun refreshProviderBalance() {
                if (dashboardLoading) return
                val current = SmsCredentialVault.load(context)
                if (current == null) {
                    balanceError = "Saved provider token is unavailable. Reconnect your provider."
                    connected = false
                    provider = null
                    dashboard = null
                    return
                }
                dashboardLoading = true
                try {
                    val result = SmsProviderClient.balance(current)
                    if (result.isSuccess) {
                        dashboard = result.getOrNull()
                        balanceError = null
                    } else {
                        balanceError = result.exceptionOrNull()?.message
                            ?: "Could not load provider balance."
                        status = balanceError.orEmpty()
                    }
                } finally {
                    dashboardLoading = false
                }
            }

            refreshProviderBalance()
            while (true) {
                delay(120_000L)
                refreshProviderBalance()
            }
        }
    }
    LaunchedEffect(tab, historyRefresh, contacts.size) {
        if (tab == 1) TransactionStore.load(context, forceReload = true).forEach { CustomerPurchaseHistoryStore.capture(context, it) }
    }
    val allTransactions = remember(historyRefresh, tab) {
        if (tab == 1) TransactionStore.load(context, forceReload = true) else emptyList()
    }
    val successfulByPhone = remember(allTransactions) {
        allTransactions.asSequence().filter { it.statusEnum == TransactionStatus.SUCCESS }
            .groupBy { SmsCommandHandler.normalizePhone(it.phoneNumber) }
    }
    val loyalCount = contacts.count { (successfulByPhone[SmsCommandHandler.normalizePhone(it.phone)]?.size ?: 0) >= 4 }
    val newContactCount = contacts.count { (successfulByPhone[SmsCommandHandler.normalizePhone(it.phone)]?.size ?: 0) == 0 }
    val visibleContacts = contacts.filter {
        search.isBlank() || it.name.contains(search, true) || it.phone.contains(search)
    }
    val filteredContacts = visibleContacts.filter { contact ->
        val phone = SmsCommandHandler.normalizePhone(contact.phone)
        val purchases = successfulByPhone[phone]?.size ?: 0
        val lastPurchaseAt = successfulByPhone[phone]?.maxOfOrNull { transactionTimestamp(it) } ?: 0L
        val daysSincePurchase = if (lastPurchaseAt > 0L) {
            ((System.currentTimeMillis() - lastPurchaseAt).coerceAtLeast(0L) / 86_400_000L)
        } else Long.MAX_VALUE
        val consented = normalizeKenyanSmsNumber(contact.phone)?.let { SmsNotificationPreferences.consented(context, it) } == true
        when (contactsFilter) {
            "New" -> purchases == 0 && consented
            "Prospect" -> purchases == 0 && !consented
            "Loyal" -> purchases >= 4
            "Regular" -> purchases in 1..3 && daysSincePurchase < 30
            "At Risk" -> purchases in 1..3 && daysSincePurchase in 30L..89L
            "Inactive" -> purchases > 0 && daysSincePurchase >= 90
            else -> true
        }
    }
    val messageEstimate = remember(message) { SmsMarketingTemplates.estimate(message) }
    val segmentCount = messageEstimate.segments
    val groupRecipients = if (recipientMode == "Groups") SmsContactGroupStore.recipients(context, selectedGroups) else emptySet()
    val selectedRecipients = when (recipientMode) {
        "Individual" -> setOf(manualNumber.trim()).filter { it.isNotBlank() }.toSet()
        "Contacts" -> selected
        else -> groupRecipients
    }.toList().distinct()
    val manualEntries = remember(manualNumber) { splitSmsRecipientInput(manualNumber) }
    val manualNormalizedNumbers = remember(manualNumber) { manualEntries.mapNotNull(::normalizeKenyanSmsNumber).distinct() }
    val manualNormalized = manualNormalizedNumbers.singleOrNull()
    var detectedRecipientNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(manualNormalizedNumbers) {
        val resolvedNames = withContext(Dispatchers.IO) {
            manualNormalizedNumbers.mapNotNull { number ->
                lookupSmsContactName(context, number)?.let { number to it }
            }.toMap()
        }
        detectedRecipientNames = resolvedNames
        detectedRecipientName = manualNormalized?.let { resolvedNames[it] }
    }
    val invalidManualEntries = remember(manualEntries) { manualEntries.filter { normalizeKenyanSmsNumber(it) == null } }
    val manualSingleSend = recipientMode == "Individual" && manualEntries.size == 1 && manualNormalizedNumbers.size == 1
    val manualBulkSend = recipientMode == "Individual" && manualEntries.size > 1
    val optedOutManualNumbers = remember(manualNormalizedNumbers) {
        manualNormalizedNumbers.filter { SmsNotificationPreferences.optedOut(context, it) }.toSet()
    }
    val recipients = selectedRecipients.flatMap { raw ->
        if (recipientMode == "Individual") splitSmsRecipientInput(raw).mapNotNull(::normalizeKenyanSmsNumber)
        else listOfNotNull(normalizeKenyanSmsNumber(raw))
    }.distinct().filter { number ->
        // Manual one-to-one sends do not require an address-book record.
        // Bulk contact/group campaigns continue to require recorded consent.
        !SmsNotificationPreferences.optedOut(context, number) &&
            (manualSingleSend || SmsNotificationPreferences.consented(context, number))
    }
    val estimatedCost = SmsMarketingTemplates.cost(message, recipients.size, provider, context)
    val detectedConnection = remember(connectionInput) {
        val value = SmsProviderDetector.normalize(connectionInput)
        when {
            value.startsWith("smshub_", true) -> "SMSHub Personal Access Token detected"
            value.startsWith("https://", true) -> "This looks like a URL, not an API token. Paste your TalkSasa API token."
            value.length >= 12 -> "TalkSasa API token detected"
            else -> "Paste your SMSHub or TalkSasa API token"
        }
    }

    val overviewTabContent: @Composable () -> Unit = {
        val overviewHistory = remember(historyRefresh, tab) { SmsMessageHistory.load(context) }
        val recentSentMessages = remember(overviewHistory) {
            overviewHistory.asSequence()
                .filter { it.optString("to").isNotBlank() }
                .sortedByDescending { it.optLong("at", 0L) }
                .take(2)
                .toList()
        }
        val localMidnight = remember(dayBoundary) {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val monthStart = remember(dayBoundary) {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val todayCount = overviewHistory.count { it.optLong("at", 0L) >= localMidnight }
        val acceptedToday = overviewHistory.count {
            it.optString("status").equals("Accepted", true) && it.optLong("at", 0L) >= localMidnight
        }
        val deliveryRate = if (todayCount == 0) "—" else "%.0f%%".format(acceptedToday * 100.0 / todayCount)
        val monthMessages = overviewHistory.filter { it.optLong("at", 0L) >= monthStart }
        val deliveredMonth = monthMessages.count { it.optString("status").equals("Accepted", true) || it.optString("status").equals("Delivered", true) }
        val failedMonth = monthMessages.count { it.optString("status").equals("Failed", true) || it.optString("status").equals("Rejected", true) }
        val pendingMonth = monthMessages.count { it.optString("status").equals("Pending", true) || it.optString("status").equals("Queued", true) || it.optString("status").equals("Scheduled", true) }
        val balanceHeadline = dashboard?.let { data ->
            val providerBalance = findSmsMetric(data, listOf("provider_balance", "remaining_balance"))
            val unitBalance = findSmsMetric(data, listOf("remaining_units", "sms_units_remaining", "available_units", "remaining_sms", "sms_credits", "units_remaining", "sms_balance"))
            when {
                provider == SmsProvider.TALKSASA && (unitBalance != null || providerBalance != null) ->
                    formatTalkSasaUnits(unitBalance ?: providerBalance.orEmpty())
                else -> unitBalance ?: providerBalance ?: findSmsBalance(data)?.second ?: "Balance reported"
            }
        } ?: if (connected) "Refresh balance" else "Not connected"

        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("TODAY AT A GLANCE", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .9.sp)
                    Text("Messaging overview", color = C.t1, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
                Surface(color = C.surface, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, C.border)) {
                    Text(if (connected) "CONNECTED" else "SETUP NEEDED", color = if (connected) C.green else C.amber,
                        fontSize = 8.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
            SmsCard(
                borderColor = C.border,
                containerColor = C.card
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("MESSAGE PERFORMANCE", color = C.t2, fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
                            Text("Today's activity", color = C.t1, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(todayCount.toString(), color = C.amber, fontSize = 21.sp,
                                fontWeight = FontWeight.ExtraBold)
                            Text("Sent today", color = C.t3, fontSize = 9.sp)
                        }
                    }
                    HorizontalDivider(color = C.border.copy(alpha = .8f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(deliveryRate, color = C.cyan, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Delivery rate · today", color = C.t2, fontSize = 9.sp)
                        }
                        Box(Modifier.width(1.dp).height(30.dp).background(C.border))
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(deliveredMonth.toString(), color = C.green, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Delivered · month", color = C.t2, fontSize = 9.sp)
                        }
                    }
                    HorizontalDivider(color = C.border.copy(alpha = .8f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(C.red))
                            Text("Failed", color = C.t2, fontSize = 10.sp)
                            Text(failedMonth.toString(), color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(C.amber))
                            Text("Pending", color = C.t2, fontSize = 10.sp)
                            Text(pendingMonth.toString(), color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            SmsCard(
                borderColor = if (connected) C.green.copy(alpha = .30f) else C.cyan.copy(alpha = .30f),
                containerColor = C.card
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(Modifier.size(36.dp), shape = RoundedCornerShape(11.dp),
                        color = (if (connected) C.green else C.cyan).copy(alpha = .12f)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(if (connected) Icons.Outlined.Sms else Icons.Outlined.Link, null,
                                tint = if (connected) C.green else C.cyan, modifier = Modifier.size(19.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (connected) (provider?.name ?: "SMS Provider") else "Connect messaging provider",
                            color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (connected) "Sender: ${sender.ifBlank { "Not configured" }}" else "SMSHub and TalkSasa supported",
                            color = C.t2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(balanceHeadline, color = C.amber, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (provider == SmsProvider.TALKSASA) "units" else "balance", color = C.t3, fontSize = 9.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (connected && dashboardLoading) {
                        CircularProgressIndicator(Modifier.size(15.dp), color = C.amber, strokeWidth = 2.dp)
                    } else if (connected) {
                        TextButton(
                            onClick = {
                                val current = SmsCredentialVault.load(context)
                                if (current != null) scope.launch {
                                    dashboardLoading = true
                                    val result = SmsProviderClient.balance(current)
                                    dashboardLoading = false
                                    if (result.isSuccess) {
                                        dashboard = result.getOrNull()
                                        balanceError = null
                                    } else {
                                        balanceError = result.exceptionOrNull()?.message ?: "Could not load provider balance."
                                        status = balanceError.orEmpty()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) { Icon(Icons.Outlined.Refresh, null, tint = C.amber, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh", color = C.amber, fontSize = 10.sp) }
                    }
                    TextButton(onClick = { tab = if (connected) 7 else 7 }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(if (connected) "Settings" else "Connect", color = C.t1, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                balanceError?.let { Text(it, color = C.red, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("RECENT ACTIVITY", color = C.t2, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { tab = 4 }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                    Text("View all · ${overviewHistory.size}", color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (overviewHistory.isEmpty()) {
                Surface(Modifier.fillMaxWidth().weight(1f), color = C.card, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, C.border)) {
                    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.Sms, null, tint = C.t3, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text("No recent messages", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text("Sent messages and delivery updates will appear here.", color = C.t3, fontSize = 9.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    recentSentMessages.forEachIndexed { index, row ->
                        val accepted = row.optString("status").equals("Accepted", true) || row.optString("status").equals("Delivered", true)
                        val failed = row.optString("status").equals("Failed", true) || row.optString("status").equals("Rejected", true)
                        Row(
                            Modifier.fillMaxWidth().weight(1f).clickable { tab = 4 }.padding(horizontal = 3.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(if (accepted) C.green else if (failed) C.red else C.amber))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.optString("recipient_name").ifBlank { row.optString("to") }.ifBlank { "Unknown recipient" },
                                    color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(row.optString("text"), color = C.t2, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.optString("status").ifBlank { "Pending" }, color = if (accepted) C.green else if (failed) C.red else C.amber,
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(row.optString("time").ifBlank { "SMS" }, color = C.t3, fontSize = 8.sp, maxLines = 1)
                            }
                        }
                        if (index == 0 && recentSentMessages.size > 1) HorizontalDivider(color = C.border.copy(alpha = .65f))
                    }
                }
            }
        }
    }

    val sendTabContent: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.Link, null,
                    tint = if (connected) C.green else C.amber, modifier = Modifier.size(15.dp))
                Text(
                    if (connected) "${provider?.name ?: "Provider"} · ${sender.ifBlank { "Sender not set" }}"
                    else "Provider not connected",
                    Modifier.weight(1f), color = C.t2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { tab = 7 }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                    Text(if (connected) "Account" else "Setup", color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("RECIPIENTS", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text("${recipients.size} eligible", color = C.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Individual", "Contacts", "Groups").forEach { mode ->
                        FilterChip(
                            selected = recipientMode == mode,
                            onClick = {
                                recipientMode = mode
                                if (mode == "Contacts" || mode == "Groups") showRecipientPicker = true
                            },
                            label = { Text(mode, fontSize = 10.sp, maxLines = 1) },
                            modifier = Modifier.weight(1f),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = C.amberDim,
                                selectedLabelColor = C.amber,
                                labelColor = C.t2
                            )
                        )
                    }
                }
                when (recipientMode) {
                    "Individual" -> {
                        OutlinedTextField(
                            value = manualNumber,
                            onValueChange = { value ->
                                manualNumber = value.filter { ch ->
                                    ch.isDigit() || ch == '+' || ch == ' ' || ch == '-' ||
                                        ch == '(' || ch == ')' || ch == ',' || ch == ';' || ch == '\n'
                                }.take(600)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Phone numbers / contacts", fontSize = 11.sp) },
                            placeholder = { Text("07… , 07… , +254…", fontSize = 11.sp) },
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            colors = smsFieldColors(),
                            isError = manualNumber.isNotBlank() && (invalidManualEntries.isNotEmpty() || manualNormalizedNumbers.isEmpty()),
                            trailingIcon = {
                                if (manualNumber.isNotBlank() && invalidManualEntries.isEmpty() && optedOutManualNumbers.isEmpty()) {
                                    Icon(Icons.Outlined.CheckCircle, "Valid recipients", tint = C.green, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                        if (manualNumber.isNotBlank()) {
                            val previewNames = manualNormalizedNumbers.take(3).mapNotNull { number ->
                                detectedRecipientNames[number]?.let { name -> "$name ($number)" }
                            }
                            Text(
                                buildString {
                                    append("${manualNormalizedNumbers.size} valid recipient(s)")
                                    if (previewNames.isNotEmpty()) append(" · " + previewNames.joinToString(", "))
                                },
                                color = C.t2, fontSize = 10.sp, lineHeight = 14.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                            if (invalidManualEntries.isNotEmpty()) {
                                Text(
                                    "${invalidManualEntries.size} invalid entr${if (invalidManualEntries.size == 1) "y" else "ies"} will be skipped. Separate numbers with commas.",
                                    color = C.red, fontSize = 10.sp, lineHeight = 14.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            if (optedOutManualNumbers.isNotEmpty()) {
                                Text(
                                    "${optedOutManualNumbers.size} opted-out number(s) excluded.",
                                    color = C.red, fontSize = 10.sp, lineHeight = 14.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                    "Contacts" -> Surface(Modifier.fillMaxWidth(), color = C.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, C.border)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${selected.size} contacts selected", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${recipients.size} consent-eligible", color = C.t2, fontSize = 9.sp)
                            }
                            SmsSecondaryButton("Choose", { showRecipientPicker = true }, minHeight = 36.dp)
                        }
                    }
                    else -> Surface(Modifier.fillMaxWidth(), color = C.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, C.border)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${selectedGroups.size} groups selected", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${groupRecipients.size} members · ${recipients.size} eligible", color = C.t2, fontSize = 9.sp)
                            }
                            SmsSecondaryButton("Choose", { showRecipientPicker = true }, minHeight = 36.dp)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("MESSAGE", color = C.t1, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showMessageEditor = true }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Text("Full editor", color = C.cyan, fontSize = 10.sp)
                    }
                    TextButton(onClick = { tab = 8 }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Icon(Icons.Outlined.Description, null, tint = C.amber, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Templates", color = C.amber, fontSize = 10.sp)
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp, max = 82.dp),
                    label = { Text("Write your SMS", fontSize = 11.sp) },
                    placeholder = { Text("Type your message…", fontSize = 11.sp) },
                    minLines = 2,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    colors = smsFieldColors()
                )
                if (showMessageEditor) {
                    Dialog(
                        onDismissRequest = { showMessageEditor = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 12.dp),
                            color = C.bg,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, C.border)
                        ) {
                            Column(
                                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Write your SMS", color = C.t1, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                                        Text("Expanded editor · full message preview", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = { showMessageEditor = false }) { Text("Done", color = C.amber, fontWeight = FontWeight.Bold) }
                                }
                                OutlinedTextField(
                                    value = message,
                                    onValueChange = { message = it },
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    placeholder = { Text("Type your message…") },
                                    minLines = 8,
                                    maxLines = Int.MAX_VALUE,
                                    textStyle = MaterialTheme.typography.bodyLarge,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = smsFieldColors()
                                )
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${messageEstimate.characters} characters · ${messageEstimate.encodedUnits} units", color = C.t2, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                    Text("${segmentCount} SMS segment(s)", color = C.amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                                Text("Your message stays in sync with the Send screen.", color = C.t3, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${messageEstimate.characters} chars · ${messageEstimate.encodedUnits} units",
                        color = C.t2, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${segmentCount} SMS segment(s)", color = C.amber, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    Modifier.weight(1f).heightIn(min = 152.dp),
                    color = C.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, C.border)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text("ESTIMATED COST", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = .45.sp, maxLines = 2, lineHeight = 13.sp)
                        val amount = estimatedCost.totalKes?.let { "KES " + String.format(java.util.Locale.US, "%,.2f", it) } ?: "Rate not set"
                        Text(amount, color = C.amber, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                            maxLines = 2, lineHeight = 21.sp)
                        Text("${estimatedCost.totalSegments} billable segments · ${recipients.size} recipients",
                            color = C.t2, fontSize = 10.sp, lineHeight = 14.sp)
                        val rate = estimatedCost.ratePerSegment?.let { "KES " + String.format(java.util.Locale.US, "%.4f", it) } ?: "Not configured"
                        Text("Rate per segment: $rate", color = C.t2, fontSize = 10.sp, lineHeight = 14.sp)
                        Text(
                            "Source: " + (provider?.let { SmsMarketingTemplates.rateSource(context, it) } ?: "Connect a provider"),
                            color = C.t3, fontSize = 9.sp, lineHeight = 13.sp
                        )
                    }
                }
                Surface(
                    Modifier.weight(1f).heightIn(min = 152.dp),
                    color = C.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, C.border)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text("SCHEDULE DELAY", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                            letterSpacing = .45.sp, maxLines = 2, lineHeight = 13.sp)
                        OutlinedTextField(
                            value = scheduleDelay,
                            onValueChange = { scheduleDelay = it.filter(Char::isDigit).take(5) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            placeholder = { Text("Minutes", fontSize = 12.sp) },
                            label = { Text("Minutes") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(9.dp),
                            colors = smsFieldColors()
                        )
                        Text("Minimum 1 minute", color = C.t2, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                }
            }
            Text(
                if (recipientMode == "Individual") {
                    when {
                        manualNumber.isBlank() -> "Enter one or more Kenyan numbers. Separate recipients with commas, semicolons or new lines."
                        invalidManualEntries.isNotEmpty() -> "Invalid entries will be skipped. Only valid Kenyan mobile numbers are sent."
                        optedOutManualNumbers.isNotEmpty() -> "Opted-out numbers are excluded automatically."
                        manualBulkSend -> "Multiple direct recipients require recorded consent. Opted-out numbers are excluded."
                        else -> "Send only to people who expect this message."
                    }
                } else {
                    "${recipients.size} of ${selectedRecipients.size} recipients are eligible. Bulk SMS requires consent and no opt-out."
                },
                color = C.t2, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
    }

    val contactsTabContent: @Composable () -> Unit = {
                val customer = contacts.firstOrNull { SmsCommandHandler.normalizePhone(it.phone) == selectedCustomerPhone }
                if (customer == null) {

                    var csvImport by remember { mutableStateOf("") }
                    var showCsvImport by remember { mutableStateOf(false) }

                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("Search name or phone") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.t2) },
                            trailingIcon = {
                                if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                                    Icon(Icons.Outlined.Close, "Clear search", tint = C.t2)
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = smsFieldColors(),
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("All", "New", "Loyal", "Regular", "At Risk", "Inactive", "Prospect").forEach { category ->
                                FilterChip(
                                    selected = contactsFilter == category,
                                    onClick = { contactsFilter = category },
                                    label = { Text(category, maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = C.amberDim,
                                        selectedLabelColor = C.amber,
                                        labelColor = C.t2,
                                    ),
                                )
                            }
                        }
                        SmsPrimaryButton(
                            text = "Add Contact",
                            onClick = { newContactName = ""; newContactPhone = ""; showManualContactDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.PersonAdd,
                            minHeight = 48.dp,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmsCompactToolbarButton(
                                "Import", Icons.Outlined.PersonAddAlt1,
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                        val imported = readSmsPhoneContacts(context)
                                        contacts.clear()
                                        contacts.addAll(SavedContactStore.merge(context, imported))
                                        status = "Imported ${imported.size} valid contacts."
                                    } else phoneImportLauncher.launch(Manifest.permission.READ_CONTACTS)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SmsCompactToolbarButton(
                                "Export", Icons.Outlined.Share,
                                onClick = {
                                    if (contacts.isEmpty()) {
                                        status = "There are no saved contacts to export."
                                    } else {
                                        val quote = 34.toChar().toString()
                                        fun csvCell(value: String): String = quote + value.replace(quote, quote + quote) + quote
                                        val csv = buildString {
                                            appendLine("name,phone")
                                            contacts.sortedBy { it.name.lowercase() }.forEach { contact ->
                                                append(csvCell(contact.name))
                                                append(',')
                                                appendLine(csvCell(contact.phone))
                                            }
                                        }
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/csv"
                                            putExtra(Intent.EXTRA_SUBJECT, "Bingwa Mobile contacts")
                                            putExtra(Intent.EXTRA_TEXT, csv)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Export saved contacts"))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            SmsCompactToolbarButton(
                                if (showCsvImport) "Close CSV" else "CSV",
                                Icons.Outlined.Description,
                                onClick = { showCsvImport = !showCsvImport },
                                modifier = Modifier.weight(1f),
                            )
                            SmsCompactToolbarButton(
                                "Refresh", Icons.Outlined.Refresh,
                                onClick = {
                                    contacts.clear()
                                    contacts.addAll(SavedContactStore.load(context))
                                    status = "Refreshed ${contacts.size} saved contacts."
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (showManualContactDialog) {
                            AlertDialog(
                                onDismissRequest = { showManualContactDialog = false },
                                containerColor = C.card,
                                title = { Text("Save a contact", color = C.t1, fontWeight = FontWeight.Bold) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Save a Kenyan mobile number for reuse in messages and groups.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                        OutlinedTextField(value = newContactName, onValueChange = { newContactName = it.take(70) }, modifier = Modifier.fillMaxWidth(), label = { Text("Contact name") }, singleLine = true, colors = smsFieldColors())
                                        OutlinedTextField(value = newContactPhone, onValueChange = { newContactPhone = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' }.take(20) }, modifier = Modifier.fillMaxWidth(), label = { Text("Phone number") }, placeholder = { Text("07XXXXXXXX or +2547XXXXXXXX") }, singleLine = true, colors = smsFieldColors())
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val normalized = normalizeKenyanSmsNumber(newContactPhone)
                                        val local = normalized?.let { "0" + it.drop(3) }
                                        if (newContactName.isBlank()) status = "Enter the contact's name."
                                        else if (local == null) status = "Enter a valid Kenyan mobile number."
                                        else {
                                            contacts.clear()
                                            contacts.addAll(SavedContactStore.upsert(context, local, newContactName.trim()))
                                            status = "Saved ${newContactName.trim()} to Contacts."
                                            showManualContactDialog = false
                                            newContactName = ""
                                            newContactPhone = ""
                                        }
                                    }) { Text("Save contact", color = C.amber, fontWeight = FontWeight.Bold) }
                                },
                                dismissButton = { TextButton(onClick = { showManualContactDialog = false }) { Text("Cancel", color = C.t2) } }
                            )
                        }

                        if (showCsvImport) {
                            SmsCard(borderColor = C.amber.copy(alpha = 0.35f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("IMPORT CONTACTS", color = C.amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                                        Text("Paste CSV rows in name,phone format.", color = C.t2, style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(onClick = { showCsvImport = false }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Outlined.Close, "Close CSV import", tint = C.t2)
                                    }
                                }
                                OutlinedTextField(
                                    value = csvImport,
                                    onValueChange = { csvImport = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("CSV: name,phone") },
                                    minLines = 3,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = smsFieldColors(),
                                )
                                SmsPrimaryButton(
                                    text = "Import CSV contacts",
                                    onClick = {
                                        val imported = csvImport.lineSequence().filterIndexed { index, line ->
                                            line.isNotBlank() && !(index == 0 && line.lowercase().let { it.contains("name") || it.contains("phone") })
                                        }.mapNotNull { line ->
                                            val cols = line.split(",").map { it.trim().trim('"') }
                                            if (cols.size < 2) null else {
                                                val digits = cols[1].filter(Char::isDigit)
                                                val number = when {
                                                    digits.startsWith("254") -> "0" + digits.drop(3)
                                                    digits.startsWith("0") -> digits
                                                    digits.length == 9 -> "0$digits"
                                                    else -> ""
                                                }
                                                if (number.matches(Regex("^0[17]\\d{8}$"))) SavedContact(cols[0], number) else null
                                            }
                                        }.toList()
                                        contacts.clear()
                                        contacts.addAll(SavedContactStore.merge(context, imported))
                                        status = "Imported ${imported.size} valid contacts."
                                        csvImport = ""
                                        showCsvImport = false
                                    },
                                    enabled = csvImport.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Outlined.FileUpload,
                                    minHeight = 48.dp,
                                )
                            }
                        }

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CONTACTS", color = C.t2, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                                Text("${filteredContacts.size} matching · ${contacts.size} saved", color = C.t3, style = MaterialTheme.typography.labelSmall)
                            }
                            SmsSecondaryButton(
                                text = if (selectionMode) "Done" else "Select",
                                onClick = {
                                    selectionMode = !selectionMode
                                    if (!selectionMode) selected = emptySet()
                                },
                                icon = if (selectionMode) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist,
                                minHeight = 40.dp,
                            )
                        }

                        if (selectionMode || selected.isNotEmpty()) {
                            val visiblePhones = filteredContacts.map { it.phone }.toSet()
                            val allVisibleSelected = visiblePhones.isNotEmpty() && visiblePhones.all { it in selected }
                            val eligibleSelected = selected.count { raw ->
                                val normalized = normalizeKenyanSmsNumber(raw)
                                normalized != null &&
                                    !SmsNotificationPreferences.optedOut(context, normalized) &&
                                    SmsNotificationPreferences.consented(context, normalized)
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = C.cardHi,
                                border = BorderStroke(1.dp, C.amber.copy(alpha = .55f))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("${selected.size} selected", color = C.t1, fontWeight = FontWeight.Bold)
                                            Text(
                                                "$eligibleSelected eligible for bulk SMS · consent required",
                                                color = C.t2,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                selected = if (allVisibleSelected) selected - visiblePhones else selected + visiblePhones
                                                selectionMode = true
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(if (allVisibleSelected) "Unselect all" else "Select all", color = C.amber, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(
                                            onClick = { selected = emptySet() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Clear", color = C.t2)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        SmsPrimaryButton(
                                            text = "New group (${selected.size})",
                                            onClick = {
                                                groupName = ""
                                                groupTemplateId = null
                                                groupFromSelection = true
                                                groupDialog = true
                                            },
                                            enabled = selected.isNotEmpty(),
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Outlined.GroupAdd,
                                            minHeight = 46.dp,
                                        )
                                        SmsSecondaryButton(
                                            text = "Compose",
                                            onClick = {
                                                recipientMode = "Contacts"
                                                tab = 0
                                                selectionMode = false
                                                status = "Ready to compose for ${selected.size} selected contacts. Only consented, non-opted-out numbers can receive bulk SMS."
                                            },
                                            enabled = selected.isNotEmpty(),
                                            modifier = Modifier.weight(1f),
                                            icon = Icons.Outlined.Send,
                                            minHeight = 46.dp,
                                        )
                                    }
                                    if (groups.isNotEmpty()) {
                                        SmsTonalButton(
                                            text = "Add selected to existing group",
                                            onClick = { bulkGroupDialog = true },
                                            enabled = selected.isNotEmpty(),
                                            modifier = Modifier.fillMaxWidth(),
                                            icon = Icons.Outlined.Group,
                                        )
                                    }
                                }
                            }
                        }

                        if (bulkGroupDialog) {
                            AlertDialog(
                                onDismissRequest = { bulkGroupDialog = false },
                                containerColor = C.card,
                                title = { Text("Add ${selected.size} contacts to group", color = C.t1, fontWeight = FontWeight.Bold) },
                                text = {
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text("Choose an existing group. Contacts already in it will remain unchanged.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                        groups.forEach { group ->
                                            TextButton(
                                                onClick = {
                                                    selected.forEach { phone -> SmsContactGroupStore.setMember(context, group.id, phone, true) }
                                                    groups = SmsContactGroupStore.load(context)
                                                    bulkGroupDialog = false
                                                    status = "Added ${selected.size} selected contacts to ${group.name}."
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Outlined.Group, null, tint = C.amber)
                                                Spacer(Modifier.width(8.dp))
                                                Text(group.name, color = C.t1)
                                                Spacer(Modifier.weight(1f))
                                                Text("${group.phones.size}", color = C.t3)
                                            }
                                        }
                                    }
                                },
                                confirmButton = { TextButton(onClick = { bulkGroupDialog = false }) { Text("Close", color = C.t2) } }
                            )
                        }

                        when {
                            contacts.isEmpty() -> SmsEmptyState(
                                "No contacts yet",
                                "Import contacts from your phone or use CSV to add customers."
                            )
                            filteredContacts.isEmpty() -> SmsEmptyState(
                                if (search.isNotBlank()) "No matching results" else "No ${contactsFilter.lowercase()} contacts",
                                if (search.isNotBlank()) "Nothing matches your search. Try another name or phone number."
                                else "Contacts in this category will appear here."
                            )
                            else -> Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(0.dp),
                                color = Color.Transparent,
                                border = BorderStroke(0.dp, Color.Transparent)
                            ) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(0.dp)
                                ) {
                                    filteredContacts.sortedBy { it.name.ifBlank { it.phone }.lowercase() }.forEachIndexed { index, contact ->
                                        val count = successfulByPhone[SmsCommandHandler.normalizePhone(contact.phone)]?.size ?: 0
                                        val customerType = when {
                                            count == 0 -> "New"
                                            count >= 4 -> "Loyal"
                                            else -> "Regular"
                                        }
                                        val customerTone = if (count >= 4) C.green else if (count == 0) C.cyan else C.t2
                                        val normalizedContact = normalizeKenyanSmsNumber(contact.phone)
                                        val hasSmsConsent = normalizedContact?.let { SmsNotificationPreferences.consented(context, it) } == true
                                        val optedOut = normalizedContact?.let { SmsNotificationPreferences.optedOut(context, it) } == true
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (contact.phone in selected) C.amberDim.copy(alpha = .55f) else Color.Transparent)
                                                .clickable {
                                                    if (selectionMode) {
                                                        selected = if (contact.phone in selected) selected - contact.phone else selected + contact.phone
                                                    } else {
                                                        selectedCustomerPhone = contact.phone
                                                    }
                                                }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            if (selectionMode) {
                                                Checkbox(
                                                    checked = contact.phone in selected,
                                                    onCheckedChange = { checked ->
                                                        selected = if (checked) selected + contact.phone else selected - contact.phone
                                                    }
                                                )
                                            }
                                            val avatarBg = when {
                                                count >= 4 -> C.greenDim
                                                count == 0 -> C.amberDim
                                                else -> C.blueDim
                                            }
                                            val avatarFg = when {
                                                count >= 4 -> C.green
                                                count == 0 -> C.amber
                                                else -> C.blue
                                            }
                                            Surface(
                                                modifier = Modifier.size(34.dp),
                                                shape = CircleShape,
                                                color = avatarBg,
                                                border = BorderStroke(1.dp, avatarFg.copy(alpha = 0.28f))
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        contact.name.split(" ").filter { it.isNotBlank() }.take(2)
                                                            .joinToString("") { it.first().uppercase() }.ifBlank { "•" },
                                                        color = avatarFg,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                }
                                            }
                                            Column(
                                                Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    contact.name.ifBlank { "Unknown customer" },
                                                    color = C.t1,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    overflow = TextOverflow.Clip
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                                ) {
                                                    Text(
                                                        contact.phone,
                                                        color = C.t2,
                                                        style = MaterialTheme.typography.labelSmall,
                                                    )
                                                    Text("·", color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                    Text(
                                                        customerType,
                                                        color = customerTone,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            Column(
                                                horizontalAlignment = Alignment.End,
                                                verticalArrangement = Arrangement.spacedBy(1.dp)
                                            ) {
                                                Text(
                                                    when { optedOut -> "Opted out"; hasSmsConsent -> "Consent"; else -> "No consent" },
                                                    color = when { optedOut -> C.red; hasSmsConsent -> C.green; else -> C.t3 },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                if (normalizedContact != null && !optedOut) {
                                                    TextButton(
                                                        onClick = {
                                                            SmsNotificationPreferences.setConsent(context, normalizedContact, !hasSmsConsent)
                                                            status = if (hasSmsConsent) "Consent record cleared." else "Consent recorded. Only mark this after the customer has agreed to receive SMS."
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                    ) {
                                                        Text(if (hasSmsConsent) "Revoke" else "Record", color = C.amber, style = MaterialTheme.typography.labelSmall)
                                                    }
                                                } else {
                                                    Text("${count} purchases", color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (!selectionMode) {
                                                Icon(
                                                    Icons.Outlined.ChevronRight,
                                                    contentDescription = "View ${contact.name} details",
                                                    tint = C.t3,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        if (index < filteredContacts.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(start = if (selectionMode) 104.dp else 62.dp, end = 14.dp),
                                                color = C.border.copy(alpha = .7f),
                                                thickness = 1.dp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                
                }

                if (customer != null) {
                    ModalBottomSheet(
                        onDismissRequest = { selectedCustomerPhone = null },
                        containerColor = C.bg,
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 680.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {

                    val phone = SmsCommandHandler.normalizePhone(customer.phone)
                    val history = CustomerPurchaseHistoryStore.load(context, phone)
                    val enabled = CustomerPurchaseHistoryStore.isEnabled(context, phone)
                    val retention = CustomerPurchaseHistoryStore.retention(context, phone)
                    val purchases = successfulByPhone[phone]?.size ?: 0
                    val transactionHistory = allTransactions
                        .filter { SmsCommandHandler.normalizePhone(it.phoneNumber) == phone && it.statusEnum == TransactionStatus.SUCCESS }
                        .sortedByDescending(::transactionTimestamp)
                    var retentionMenu by remember(phone) { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = RoundedCornerShape(14.dp), color = C.card, border = BorderStroke(1.dp, C.border)) {
                            IconButton(onClick = { selectedCustomerPhone = null }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.ArrowBack, "Back to contacts", tint = C.t1)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(customer.name.ifBlank { "Customer" }, color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(customer.phone, color = C.t2, style = MaterialTheme.typography.bodySmall)
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = C.card, border = BorderStroke(1.dp, C.border)) {
                            IconButton(onClick = { clearCustomerHistoryPhone = phone }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.MoreVert, "Customer history options", tint = C.t1)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CUSTOMER OVERVIEW", color = C.t3, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmsTintedStatTile(
                                value = purchases.toString(),
                                label = "Purchases",
                                accent = C.amber,
                                modifier = Modifier.weight(1f),
                            )
                            SmsTintedStatTile(
                                value = if (purchases >= 4) "Loyal" else if (purchases == 0) "New" else "Regular",
                                label = "Tier",
                                accent = if (purchases >= 4) C.green else C.blue,
                                modifier = Modifier.weight(1f),
                            )
                            SmsTintedStatTile(
                                value = history.size.toString(),
                                label = "Timeline",
                                accent = C.purple,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Recorded purchases", color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                Text("${transactionHistory.size} successful transactions", color = C.t2, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("History", color = C.t3, style = MaterialTheme.typography.labelSmall)
                        }
                        if (transactionHistory.isEmpty()) {
                            SmsEmptyState("No completed purchases yet", "Successful purchases for this phone number will appear here with the offer, amount, date and time.")
                        } else {
                            transactionHistory.take(30).forEachIndexed { index, tx ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tx.description.ifBlank { "Purchase" }, modifier = Modifier.weight(1f), color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                        Text(tx.amount.ifBlank { "KSh ${String.format(java.util.Locale.getDefault(), "%,.0f", tx.amountValue)}" }, color = C.amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(transactionTimestamp(tx))), color = C.t2, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                        Text(java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(transactionTimestamp(tx))), color = C.t2, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (index < transactionHistory.take(30).lastIndex) HorizontalDivider(color = C.border.copy(alpha = .65f))
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Individual purchase history", color = C.t1, fontWeight = FontWeight.SemiBold)
                                    Text(if (enabled) "Tracking is enabled for this customer." else "Off by default. Enable only when you want a customer-specific purchase timeline.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(checked = enabled, onCheckedChange = { value ->
                                    if (value) CustomerPurchaseHistoryStore.enable(context, phone)
                                    else CustomerPurchaseHistoryStore.disable(context, phone)
                                    historyRefresh++
                                })
                            }
                            if (enabled) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("History retention", color = C.t1, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Text("Automatically remove older entries", color = C.t2, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Box {
                                        OutlinedButton(onClick = { retentionMenu = true }, shape = RoundedCornerShape(12.dp)) {
                                            Text(retention.label, color = C.amber)
                                            Icon(Icons.Outlined.ExpandMore, null, tint = C.amber)
                                        }
                                        DropdownMenu(expanded = retentionMenu, onDismissRequest = { retentionMenu = false }) {
                                            CustomerHistoryRetention.entries.forEach { option ->
                                                DropdownMenuItem(text = { Text(option.label, color = if (option == retention) C.amber else C.t1) }, onClick = {
                                                    CustomerPurchaseHistoryStore.setRetention(context, phone, option)
                                                    retentionMenu = false
                                                    historyRefresh++
                                                })
                                            }
                                        }
                                    }
                                }
                                Text("Tracking starts from now. Existing transactions are not copied into this private timeline.", color = C.t3, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Purchase history", color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(if (enabled) "${history.size} saved entries" else "Not being recorded", color = C.t2, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (!enabled) SmsEmptyState("Individual history is off", "Turn on tracking above to save future successful purchases.")
                    else if (history.isEmpty()) SmsEmptyState("No saved purchases yet", "New successful purchases will appear here with their date and time.")
                    else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { item ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(item.offer, color = C.t1, fontWeight = FontWeight.SemiBold,)
                                            Text(java.text.SimpleDateFormat("EEE, dd MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(item.purchasedAt)), color = C.t2, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(item.amount, color = C.amber, fontWeight = FontWeight.Bold)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Icon(Icons.Outlined.Schedule, null, tint = C.t3, modifier = Modifier.size(13.dp))
                                            Text(java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(item.purchasedAt)), color = C.t2, style = MaterialTheme.typography.labelSmall)
                                        }
                                        Text(item.status, color = C.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                        }
                    }
                    if (clearCustomerHistoryPhone == phone) {
                        AlertDialog(
                            onDismissRequest = { clearCustomerHistoryPhone = null },
                            containerColor = C.card, shape = RoundedCornerShape(UiDimens.DialogRadius),
                            title = { Text("Clear purchase history?", color = C.t1, fontWeight = FontWeight.Bold) },
                            text = { Text("Only this customer's saved individual purchase timeline will be cleared. The contact and main transaction records will remain.", color = C.t2) },
                            confirmButton = { TextButton(onClick = {
                                CustomerPurchaseHistoryStore.clear(context, phone)
                                clearCustomerHistoryPhone = null
                                historyRefresh++
                                status = "Customer purchase history cleared. Contact retained."
                            }) { Text("Clear history", color = C.red, fontWeight = FontWeight.Bold) } },
                            dismissButton = { TextButton(onClick = { clearCustomerHistoryPhone = null }) { Text("Cancel", color = C.t2) } },
                        )
                    }
                
                        }
                    }
                }
    }

    val groupsTabContent: @Composable () -> Unit = {
                var expandedGroupId by remember { mutableStateOf<String?>(null) }
                var groupMemberSearch by remember { mutableStateOf("") }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Contact Groups", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Organise customers so you can message them together", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    SmsPrimaryButton(
                        text = "New Group",
                        onClick = {
                            groupTemplateId = null
                            groupFromSelection = false
                            groupName = ""
                            groupCreationSearch = ""
                            groupCreationSelectedPhones = emptySet()
                            groupDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.GroupAdd,
                        minHeight = 48.dp,
                    )
                }
                if (groups.isEmpty()) {
                    SmsEmptyState("No groups created", "Create a group to organise customers and send messages to selected audiences.")
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { group ->
                        val isExpanded = expandedGroupId == group.id
                        val linked = SmsMarketingTemplates.find(context, group.templateId)
                        var menuExpanded by remember(group.id) { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(C.card)
                                .border(1.dp, C.border, RoundedCornerShape(16.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { expandedGroupId = if (isExpanded) null else group.id }
                                    .padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier.size(40.dp).background(C.cardHi, RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Outlined.Group, contentDescription = null, tint = C.amber, modifier = Modifier.size(20.dp))
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(group.name, color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${group.phones.size} members" + (linked?.let { " · Template: ${it.title}" } ?: ""),
                                        color = C.t2,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Outlined.MoreVert, "Group actions", tint = C.t2)
                                    }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Manage members") },
                                            onClick = { menuExpanded = false; expandedGroupId = if (isExpanded) null else group.id },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Send SMS") },
                                            onClick = {
                                                menuExpanded = false
                                                recipientMode = "Groups"
                                                selectedGroups = setOf(group.id)
                                                if (linked != null) {
                                                    if (linked.usesTill && (tillNumber.isBlank() || helpLine.isBlank())) {
                                                        pendingMarketingTemplate = linked
                                                        showBusinessDetailsDialog = true
                                                    } else {
                                                        message = if (linked.usesTill || linked.usesOffers) SmsMarketingTemplates.render(linked, context, tillNumber, helpLine) else linked.body
                                                        tab = 0
                                                        status = "Template loaded for " + group.name + ". Review recipients and message before sending."
                                                    }
                                                } else {
                                                    tab = 0
                                                    status = "Selected group " + group.name + ". Write your message below."
                                                }
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            onClick = { menuExpanded = false; editingGroup = group; editingGroupName = group.name },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete", color = C.red) },
                                            onClick = { menuExpanded = false; deletingGroup = group },
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = C.border.copy(alpha = 0.65f))
                            if (isExpanded) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Group members", color = C.t1, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text("${group.phones.size} currently added", color = C.t3, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = C.amberDim,
                                    ) {
                                        Text(
                                            "${group.phones.size} members",
                                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            color = C.amber,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                if (contacts.isEmpty()) {
                                    Text("No saved contacts yet. Import contacts in the Contacts tab first.", color = C.t3, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    OutlinedTextField(
                                        value = groupMemberSearch,
                                        onValueChange = { groupMemberSearch = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        placeholder = { Text("Filter contacts to add/remove…") },
                                        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = C.t2) },
                                        trailingIcon = {
                                            if (groupMemberSearch.isNotEmpty()) {
                                                IconButton(onClick = { groupMemberSearch = "" }) {
                                                    Icon(Icons.Outlined.Close, "Clear member filter", tint = C.t2)
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = smsFieldColors(),
                                    )
                                    val matchingContacts = contacts.filter { c ->
                                        groupMemberSearch.isBlank() ||
                                            c.name.contains(groupMemberSearch, true) ||
                                            c.phone.contains(groupMemberSearch)
                                    }
                                    val filteredMemberPhones = matchingContacts.mapNotNull { normalizeKenyanSmsNumber(it.phone) }.toSet()
                                    val allFilteredAreMembers = filteredMemberPhones.isNotEmpty() && filteredMemberPhones.all { it in group.phones }
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        TextButton(
                                            onClick = {
                                                matchingContacts.forEach { contact ->
                                                    SmsContactGroupStore.setMember(context, group.id, contact.phone, !allFilteredAreMembers)
                                                }
                                                groups = SmsContactGroupStore.load(context)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(if (allFilteredAreMembers) "Remove visible" else "Add visible", color = C.amber, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    matchingContacts.forEach { contact ->
                                        val normalized = normalizeKenyanSmsNumber(contact.phone)
                                        if (normalized != null) {
                                            val inGroup = normalized in group.phones
                                            Row(
                                                Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (inGroup) C.amberDim.copy(alpha = 0.38f) else C.surface)
                                                    .clickable {
                                                        SmsContactGroupStore.setMember(context, group.id, contact.phone, !inGroup)
                                                        groups = SmsContactGroupStore.load(context)
                                                    }
                                                    .padding(horizontal = 9.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                            ) {
                                                Surface(
                                                    modifier = Modifier.size(34.dp),
                                                    shape = CircleShape,
                                                    color = if (inGroup) C.amber.copy(alpha = 0.16f) else C.cardHi,
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            contact.name.trim().firstOrNull()?.uppercase() ?: "#",
                                                            color = if (inGroup) C.amber else C.t2,
                                                            fontWeight = FontWeight.Bold,
                                                        )
                                                    }
                                                }
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                    Text(contact.name.ifBlank { contact.phone }, color = C.t1, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    if (contact.name.isNotBlank()) Text(contact.phone, color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = if (inGroup) C.amberDim else C.cardHi,
                                                    border = BorderStroke(1.dp, if (inGroup) C.amber.copy(alpha = 0.25f) else C.border),
                                                ) {
                                                    Text(
                                                        if (inGroup) "Added" else "Add",
                                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                        color = if (inGroup) C.amber else C.t2,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (deletingGroup != null) {
                    AlertDialog(
                        onDismissRequest = { deletingGroup = null },
                        containerColor = C.card,
                        shape = RoundedCornerShape(UiDimens.DialogRadius),
                        title = { Text("Delete group?", color = C.t1, fontWeight = FontWeight.Bold) },
                        text = { Text("This removes the group and its membership list. Your saved contacts will not be deleted.", color = C.t2) },
                        confirmButton = {
                            TextButton(onClick = {
                                deletingGroup?.let { group ->
                                    SmsContactGroupStore.delete(context, group.id)
                                    groups = SmsContactGroupStore.load(context)
                                    selectedGroups = selectedGroups - group.id
                                }
                                deletingGroup = null
                            }) { Text("Delete", color = C.red, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { deletingGroup = null }) { Text("Cancel", color = C.t2) } }
                    )
                }
                if (editingGroup != null) {
                    AlertDialog(
                        onDismissRequest = { editingGroup = null },
                        containerColor = C.card,
                        shape = RoundedCornerShape(UiDimens.DialogRadius),
                        title = { Text("Rename group", color = C.t1, fontWeight = FontWeight.Bold) },
                        text = {
                            OutlinedTextField(
                                value = editingGroupName,
                                onValueChange = { editingGroupName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Group name") },
                                singleLine = true,
                                shape = RoundedCornerShape(13.dp),
                                colors = smsFieldColors(),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val group = editingGroup
                                if (group != null && SmsContactGroupStore.rename(context, group.id, editingGroupName)) {
                                    groups = SmsContactGroupStore.load(context)
                                    editingGroup = null
                                } else status = "Enter a unique group name."
                            }) { Text("Save", color = C.amber, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { editingGroup = null }) { Text("Cancel", color = C.t2) } }
                    )
                }
                if (groupDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            groupDialog = false
                            groupFromSelection = false
                        },
                        icon = {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = C.amberDim,
                                border = BorderStroke(1.dp, C.amber.copy(alpha = 0.25f))
                            ) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Groups, null, tint = C.amber, modifier = Modifier.size(23.dp))
                                }
                            }
                        },
                        title = {
                            Text(
                                if (groupFromSelection) "Create contact group" else "New contact group",
                                color = C.t1,
                                fontWeight = FontWeight.ExtraBold
                            )
                        },
                        text = {
                            Column(
                                Modifier.fillMaxWidth()
                                    .heightIn(max = 440.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (groupFromSelection) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = C.amberDim,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, C.amber.copy(alpha = 0.28f))
                                    ) {
                                        Column(
                                            Modifier.fillMaxWidth().padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Outlined.PeopleAlt, null, tint = C.amber)
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        "${selectedValidGroupPhones.size} contacts ready",
                                                        color = C.t1,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.titleSmall
                                                    )
                                                    Text(
                                                        "These contacts will be added when you create the group.",
                                                        color = C.t2,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = C.card
                                                ) {
                                                    Text(
                                                        selectedValidGroupPhones.size.toString(),
                                                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                                        color = C.amber,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        style = MaterialTheme.typography.labelLarge
                                                    )
                                                }
                                            }
                                            val memberNames = contacts
                                                .filter { contact -> normalizeKenyanSmsNumber(contact.phone)?.let { it in selectedValidGroupPhones } == true }
                                                .distinctBy { normalizeKenyanSmsNumber(it.phone) }
                                                .take(4)
                                            if (memberNames.isNotEmpty()) {
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    memberNames.forEach { member ->
                                                        Surface(
                                                            color = C.card,
                                                            shape = RoundedCornerShape(50),
                                                            border = BorderStroke(1.dp, C.border)
                                                        ) {
                                                            Text(
                                                                member.name.ifBlank { member.phone },
                                                                Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                                                color = C.t1Soft,
                                                                style = MaterialTheme.typography.labelSmall,)
                                                        }
                                                    }
                                                    val remaining = selectedValidGroupPhones.size - memberNames.size
                                                    if (remaining > 0) {
                                                        Text(
                                                            "+$remaining more",
                                                            Modifier.align(Alignment.CenterVertically),
                                                            color = C.t2,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (!groupFromSelection && contacts.isNotEmpty()) {
                                    val normalizedSelected = groupCreationSelectedPhones
                                    val availableContacts = contacts
                                        .filter { contact ->
                                            groupCreationSearch.isBlank() ||
                                                contact.name.contains(groupCreationSearch, true) ||
                                                contact.phone.contains(groupCreationSearch)
                                        }
                                        .distinctBy { normalizeKenyanSmsNumber(it.phone) ?: it.phone }
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = C.surface,
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, C.border),
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text("Add members", color = C.t1, fontWeight = FontWeight.Bold)
                                                    Text("Choose contacts now or add them later.", color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                }
                                                Surface(shape = RoundedCornerShape(50), color = C.amberDim) {
                                                    Text("${normalizedSelected.size} selected", Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = C.amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            OutlinedTextField(
                                                value = groupCreationSearch,
                                                onValueChange = { groupCreationSearch = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                singleLine = true,
                                                placeholder = { Text("Search saved contacts") },
                                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                                trailingIcon = {
                                                    if (groupCreationSearch.isNotBlank()) IconButton(onClick = { groupCreationSearch = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = smsFieldColors(),
                                            )
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                TextButton(
                                                    onClick = {
                                                        val visible = availableContacts.mapNotNull { normalizeKenyanSmsNumber(it.phone) }.toSet()
                                                        groupCreationSelectedPhones = if (visible.isNotEmpty() && visible.all { it in normalizedSelected }) normalizedSelected - visible else normalizedSelected + visible
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        if (availableContacts.isNotEmpty() && availableContacts.all { normalizeKenyanSmsNumber(it.phone)?.let { phone -> phone in normalizedSelected } == true }) "Clear visible" else "Select visible",
                                                        color = C.amber,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            }
                                            Column(
                                                Modifier.fillMaxWidth().heightIn(max = 184.dp).verticalScroll(rememberScrollState()),
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                availableContacts.forEach { contact ->
                                                    val phone = normalizeKenyanSmsNumber(contact.phone)
                                                    if (phone != null) {
                                                        val checked = phone in normalizedSelected
                                                        Row(
                                                            Modifier.fillMaxWidth()
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(if (checked) C.amberDim.copy(alpha = 0.55f) else C.card)
                                                                .clickable { groupCreationSelectedPhones = if (checked) normalizedSelected - phone else normalizedSelected + phone }
                                                                .padding(horizontal = 8.dp, vertical = 7.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                                                        ) {
                                                            Surface(Modifier.size(34.dp), shape = CircleShape, color = if (checked) C.amber.copy(alpha = 0.18f) else C.cardHi) {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Text(contact.name.trim().firstOrNull()?.uppercase() ?: "#", color = if (checked) C.amber else C.t2, fontWeight = FontWeight.Bold)
                                                                }
                                                            }
                                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                                Text(contact.name.ifBlank { contact.phone }, color = C.t1, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                                if (contact.name.isNotBlank()) Text(contact.phone, color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                            }
                                                            Checkbox(checked = checked, onCheckedChange = { isChecked ->
                                                                groupCreationSelectedPhones = if (isChecked) normalizedSelected + phone else normalizedSelected - phone
                                                            })
                                                        }
                                                    }
                                                }
                                                if (availableContacts.isEmpty()) Text("No contacts match your search.", color = C.t3, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = groupName,
                                    onValueChange = { groupName = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Group name") },
                                    placeholder = { Text("e.g. Loyal customers") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    singleLine = true,
                                    isError = duplicateGroupName,
                                    supportingText = {
                                        Text(
                                            when {
                                                duplicateGroupName -> "A group with this name already exists."
                                                groupFromSelection -> "Give this group a name you can recognize later."
                                                else -> "Use a clear name to find this group easily."
                                            },
                                            color = if (duplicateGroupName) C.red else C.t3
                                        )
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val members = if (groupFromSelection) {
                                        selected.mapNotNull(::normalizeKenyanSmsNumber).toSet()
                                    } else {
                                        groupCreationSelectedPhones.mapNotNull(::normalizeKenyanSmsNumber).toSet()
                                    }
                                    val duplicateName = groups.any { it.name.equals(groupName.trim(), ignoreCase = true) }
                                    if (groupFromSelection && members.isEmpty()) {
                                        status = "No valid phone numbers are selected. Check the selected contacts."
                                    } else if (duplicateName) {
                                        status = "A group with that name already exists. Choose another name."
                                    } else if (SmsContactGroupStore.create(context, groupName, members, groupTemplateId)) {
                                        groups = SmsContactGroupStore.load(context)
                                        status = if (groupFromSelection) {
                                            "Created ${groupName.trim()} with ${members.size} contacts."
                                        } else {
                                            "Created contact group ${groupName.trim()}."
                                        }
                                        if (groupFromSelection) {
                                            selected = emptySet()
                                            selectionMode = false
                                        }
                                        groupCreationSelectedPhones = emptySet()
                                        groupCreationSearch = ""
                                        groupName = ""
                                        groupDialog = false
                                        groupFromSelection = false
                                        groupTemplateId = null
                                    } else {
                                        status = "Enter a unique group name."
                                    }
                                },
                                enabled = groupName.isNotBlank() &&
                                    !duplicateGroupName &&
                                    (!groupFromSelection || selectedValidGroupPhones.isNotEmpty()),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = C.amber,
                                    contentColor = C.bg,
                                    disabledContainerColor = C.cardMuted,
                                    disabledContentColor = C.t3
                                ),
                                shape = RoundedCornerShape(13.dp),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)
                            ) {
                                Icon(Icons.Outlined.GroupAdd, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    when {
                                        groupFromSelection -> "Create group · ${selectedValidGroupPhones.size}"
                                        groupCreationSelectedPhones.isNotEmpty() -> "Create group · ${groupCreationSelectedPhones.size}"
                                        else -> "Create group"
                                    },
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                groupDialog = false
                                groupFromSelection = false
                            }) { Text("Cancel") }
                        },
                        containerColor = C.card
                    )
                }
    }

    val autoMessagesTabContent: @Composable () -> Unit = {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SmsSectionHeading(
                        title = "Auto Messages",
                        subtitle = "Automatic transaction confirmations synced with Customer Notifications.",
                        accent = C.green,
                    )

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmsSectionHeading(title = "Automatic Triggers", accent = C.green)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = C.greenDim,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.TaskAlt, null, tint = C.green, modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Successful transactions", fontWeight = FontWeight.SemiBold, color = C.t1)
                                Text("Send the saved success message after a confirmed delivery.", style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 17.sp)
                            }
                            Switch(checked = notificationsEnabled, onCheckedChange = {
                                notificationsEnabled = it
                                SmsNotificationPreferences.setEnabled(context, it)
                            })
                        }
                        HorizontalDivider(color = C.border)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = C.amberDim,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.ReportProblem, null, tint = C.amber, modifier = Modifier.size(18.dp))
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Failed transactions", fontWeight = FontWeight.SemiBold, color = C.t1)
                                Text("Optionally send the saved failure message.", style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 17.sp)
                            }
                            Switch(checked = failureNotifications, onCheckedChange = {
                                failureNotifications = it
                                SmsNotificationPreferences.setSendFailures(context, it)
                            })
                        }
                    }

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmsSectionHeading(title = "Message Templates", accent = C.amber)
                        OutlinedTextField(
                            value = successText,
                            onValueChange = { successText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Success message") },
                            minLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            colors = smsFieldColors(),
                            supportingText = { Text("Used by automatic successful-transaction notifications.", lineHeight = 15.sp) }
                        )
                        OutlinedTextField(
                            value = failureText,
                            onValueChange = { failureText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Failure message") },
                            minLines = 2,
                            shape = RoundedCornerShape(14.dp),
                            colors = smsFieldColors(),
                            supportingText = { Text("One-way customer message without reply instructions.", lineHeight = 15.sp) }
                        )
                        Surface(
                            color = C.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, C.border),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AVAILABLE PLACEHOLDERS", style = MaterialTheme.typography.labelSmall, color = C.t3, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("{name}", "{offer}", "{amount}", "{phone}", "{transaction_id}").forEach { token ->
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = C.cardHi,
                                            border = BorderStroke(1.dp, C.border),
                                        ) {
                                            Text(
                                                token,
                                                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                color = C.amber,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        SmsPrimaryButton(
                            text = "Save shared templates",
                            onClick = {
                                SmsNotificationPreferences.setTemplate(context, "success", successText)
                                SmsNotificationPreferences.setTemplate(context, "failure", failureText)
                                status = "Transaction templates saved and synced with Customer Notifications."
                            },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.Save,
                        )
                    }

                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmsSectionHeading(title = "Customer SMS Consent", accent = C.blue)
                        Text(
                            "Record opt-in consent or opt-out status for a customer phone number before bulk campaigns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = C.t2,
                            lineHeight = 17.sp,
                        )
                        OutlinedTextField(
                            value = consentPhone,
                            onValueChange = { consentPhone = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Customer phone") },
                            placeholder = { Text("07XXXXXXXX or 2547XXXXXXXX") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = smsFieldColors(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SmsPrimaryButton(
                                text = "Record consent",
                                onClick = {
                                    val d = consentPhone.filter(Char::isDigit)
                                    val n = when {
                                        d.startsWith("254") -> d
                                        d.startsWith("0") -> "254" + d.drop(1)
                                        else -> d
                                    }
                                    if (n.matches(Regex("^254[17]\\d{8}$"))) {
                                        SmsNotificationPreferences.setConsent(context, n, true)
                                        SmsNotificationPreferences.setOptedOut(context, n, false)
                                        status = "SMS consent recorded."
                                    } else status = "Enter a valid Kenyan phone number."
                                },
                                modifier = Modifier.weight(1f),
                                icon = Icons.Outlined.CheckCircle,
                                minHeight = 46.dp,
                            )
                            OutlinedButton(
                                onClick = {
                                    val d = consentPhone.filter(Char::isDigit)
                                    val n = when {
                                        d.startsWith("254") -> d
                                        d.startsWith("0") -> "254" + d.drop(1)
                                        else -> d
                                    }
                                    if (n.matches(Regex("^254[17]\\d{8}$"))) SmsNotificationPreferences.setOptedOut(context, n, true)
                                    status = "Opt-out recorded for the entered number."
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, C.red.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red),
                            ) {
                                Icon(Icons.Outlined.Block, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Opt out", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
    }

    val historyTabContent: @Composable () -> Unit = {
                val history = remember(historyRefresh) { SmsMessageHistory.load(context) }
                val acceptedCount = history.count { it.optString("status") == "Accepted" }
                val failedCount = history.size - acceptedCount
                var activityFilter by rememberSaveable { mutableStateOf("All") }
                val filteredHistory = when (activityFilter) {
                    "Accepted" -> history.filter { it.optString("status") == "Accepted" }
                    "Failed" -> history.filter { it.optString("status") != "Accepted" }
                    else -> history
                }
                SmsSectionHeading(
                    title = "Message activity",
                    subtitle = "Provider acceptance and delivery responses",
                    accent = C.green,
                    trailing = {
                        SmsSecondaryButton(
                            text = "Refresh",
                            onClick = { historyRefresh++ },
                            icon = Icons.Outlined.Refresh,
                            minHeight = 40.dp,
                        )
                    }
                )
                if (history.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Total  ${history.size}", color = C.t1, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text("Accepted  $acceptedCount", color = C.green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text("Failed  $failedCount", color = if (failedCount > 0) C.red else C.t3, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Accepted", "Failed").forEach { option ->
                            val active = activityFilter == option
                            Surface(
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { activityFilter = option },
                                shape = RoundedCornerShape(50),
                                color = if (active) C.amber else C.card,
                                border = BorderStroke(1.dp, if (active) C.amber else C.border),
                            ) {
                                Text(
                                    option,
                                    Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    color = if (active) C.bg else C.t2,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
                if (history.isEmpty()) {
                    SmsEmptyState("No messages sent yet", "Messages you send from Bingwa will appear here with their provider response and status.")
                } else if (filteredHistory.isEmpty()) {
                    SmsEmptyState("No ${activityFilter.lowercase()} messages", "Switch filters above to view all recorded messages.")
                } else {
                    Column(Modifier.fillMaxWidth()) {
                            filteredHistory.take(100).forEachIndexed { index, row ->
                                val isAccepted = row.optString("status") == "Accepted"
                                val timestamp = row.optLong("at", 0L)
                                val timeLabel = if (timestamp > 0L) {
                                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                                        .format(java.util.Date(timestamp))
                                } else ""
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        modifier = Modifier.size(34.dp),
                                        shape = CircleShape,
                                        color = if (isAccepted) C.greenDim else C.red.copy(alpha = 0.12f),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                row.optString("recipient_name").ifBlank { row.optString("to") }.take(1).uppercase(),
                                                color = if (isAccepted) C.green else C.red,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                Text(
                                                    row.optString("recipient_name").ifBlank { row.optString("to") },
                                                    color = C.t1,
                                                    fontWeight = FontWeight.SemiBold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (row.optString("recipient_name").isNotBlank()) {
                                                    Text(row.optString("to"), color = C.t3, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            Text(
                                                row.optString("status"),
                                                color = if (isAccepted) C.green else C.red,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        Text(
                                            row.optString("text"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = C.t2,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                listOf(row.optString("provider"), row.optString("detail"))
                                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = C.t3,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (timeLabel.isNotBlank()) {
                                                Spacer(Modifier.width(8.dp))
                                                Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = C.t3)
                                            }
                                        }
                                    }
                                }
                                if (index < filteredHistory.take(100).lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 44.dp),
                                        color = C.border.copy(alpha = 0.65f),
                                    )
                                }
                            }
                        }
                }
    }

    val insightsTabContent: @Composable () -> Unit = {
                SmsCustomerInsightsPanel(
                    context = context,
                    contacts = contacts,
                    refreshKey = historyRefresh,
                    onRefresh = { historyRefresh++ },
                    onPrepareCampaign = { segment ->
                        val audience = segment.customers
                        val baseName = segment.title
                        val groupLabel = if (groups.any { it.name.equals(baseName, true) }) "$baseName ${System.currentTimeMillis() % 10000}" else baseName
                        if (SmsContactGroupStore.create(context, groupLabel)) {
                            val created = SmsContactGroupStore.load(context).lastOrNull()
                            if (created != null) {
                                audience.forEach { customer -> SmsContactGroupStore.setMember(context, created.id, customer.phone, true) }
                                groups = SmsContactGroupStore.load(context)
                                selectedGroups = setOf(created.id)
                                recipientMode = "Groups"
                                message = segment.campaignHint
                                tab = 0
                                status = "Created $groupLabel with ${audience.size} customers (${segment.eligibleCustomers.size} consent-eligible). Review the audience before sending."
                            } else status = "The group was created, but could not be loaded. Refresh Groups."
                        } else status = "Could not create this customer group. Try again."
                    }
                )
    }

    val campaignsTabContent: @Composable () -> Unit = {
                SmsCampaignManagerPanel(
                    context = context,
                    groups = groups,
                    sender = savedSender.ifBlank { sender },
                    connected = connected
                )
    }

    val templatesTabContent: @Composable () -> Unit = {
                SmsMarketingTemplatesPanel(
                    context = context,
                    onChoose = { template ->
                        if (tillNumber.isBlank() || helpLine.isBlank()) {
                            pendingMarketingTemplate = template
                            showBusinessDetailsDialog = true
                        } else {
                            message = SmsMarketingTemplates.render(template, context, tillNumber, helpLine)
                            tab = 0
                            status = "Template prepared with saved business details and current enabled offer prices. Review before sending."
                        }
                    },
                    onEditBusinessDetails = {
                        pendingMarketingTemplate = null
                        showBusinessDetailsDialog = true
                    },
                    onCreateGroup = { template ->
                        groupTemplateId = template.id
                        groupName = template.title + " group"
                        groupFromSelection = false
                        groupDialog = true
                        tab = 2
                        status = "Name the new group for this template, then add contacts to it."
                    }
                )
    }

    val pricingTabContent: @Composable () -> Unit = {
                SmsPricingPanel(context, provider)
    }

    val schedulesTabContent: @Composable () -> Unit = {
                val scheduled = remember(tab, historyRefresh) {
                    SmsScheduledQueue.all(context).sortedBy { it.optLong("scheduled") }
                }
                SmsSectionHeading(
                    title = "Schedules",
                    subtitle = "${scheduled.size} scheduled message(s)",
                    accent = C.blue,
                )
                if (scheduled.isEmpty()) {
                    SmsEmptyState(
                        "No scheduled messages",
                        "Messages scheduled from Send SMS will appear here until they are sent or cancelled.",
                    )
                } else {
                    scheduled.forEachIndexed { index, item ->
                        val id = item.optString("id")
                        val scheduledAt = item.optLong("scheduled")
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.optString("to"), color = C.t1, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        if (scheduledAt > 0L) java.text.DateFormat.getDateTimeInstance(
                                            java.text.DateFormat.MEDIUM,
                                            java.text.DateFormat.SHORT,
                                        ).format(java.util.Date(scheduledAt)) else "Scheduled",
                                        color = C.t2,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("bingwa_sms_${id}")
                                        SmsScheduledQueue.remove(context, id)
                                        historyRefresh++
                                        status = "Scheduled message cancelled."
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text("Cancel", color = C.red, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Text(
                                item.optString("message"),
                                color = C.t2,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index < scheduled.lastIndex) HorizontalDivider(color = C.border.copy(alpha = 0.65f))
                    }
                }
    }

    val providersTabContent: @Composable () -> Unit = {
                if (showDisconnectProviderDialog) {
                    AlertDialog(
                        onDismissRequest = { showDisconnectProviderDialog = false },
                        title = { Text("Change messaging provider?", color = C.t1, fontWeight = FontWeight.Bold) },
                        text = { Text("Your current provider credentials will be disconnected on this device. You can then verify and save the replacement provider. Existing message history will remain.", color = C.t2) },
                        confirmButton = { TextButton(onClick = {
                            SmsCredentialVault.clear(context)
                            connected = false; provider = null; dashboard = null; sender = ""; savedSender = ""; connectionInput = ""
                            showDisconnectProviderDialog = false
                            status = "Disconnected. Paste a new provider connection below."
                        }) { Text("Continue", color = C.amber, fontWeight = FontWeight.Bold) } },
                        dismissButton = { TextButton(onClick = { showDisconnectProviderDialog = false }) { Text("Cancel", color = C.t2) } },
                        containerColor = C.card, titleContentColor = C.t1, textContentColor = C.t2,
                        shape = RoundedCornerShape(UiDimens.DialogRadius),
                    )
                }
                if (showProviderSaveDialog) {
                    AlertDialog(
                        onDismissRequest = { connectionInput = ""; showProviderSaveDialog = false },
                        title = { Text("Verify and save provider?", color = C.t1, fontWeight = FontWeight.Bold) },
                        text = { Text("Bingwa will identify the provider and verify this API token before storing it securely. If verification fails, your current connection will remain unchanged.", color = C.t2) },
                        confirmButton = {
                            TextButton(onClick = {
                                showProviderSaveDialog = false
                                val value = SmsProviderDetector.normalize(connectionInput)
                                when {
                                    value.startsWith("smshub_", true) -> {
                                        verifying = true
                                        status = "Detected SMSHub. Verifying account…"
                                        scope.launch {
                                            val result = SmsProviderClient.verify(SmsConnection(SmsProvider.SMSHUB, value, sender))
                                            verifying = false
                                            if (result.isSuccess) {
                                                SmsCredentialVault.save(context, SmsProvider.SMSHUB, value, sender)
                                                savedSender = sender
                                                connected = true; provider = SmsProvider.SMSHUB; dashboard = null; connectionInput = ""
                                                status = result.getOrNull().orEmpty()
                                            } else status = result.exceptionOrNull()?.message ?: "SMSHub verification failed."
                                        }
                                    }
                                    value.startsWith("https://", true) -> status = "Paste the TalkSasa API token, not a website or backend URL."
                                    value.length >= 12 -> {
                                        verifying = true
                                        status = "Checking provider token…"
                                        scope.launch {
                                            val selectedProvider = SmsProviderDetector.detect(value) ?: SmsProvider.TALKSASA
                                            val result = SmsProviderClient.verify(SmsConnection(selectedProvider, value, sender))
                                            verifying = false
                                            if (result.isSuccess) {
                                                val defaultSender = if (selectedProvider == SmsProvider.TALKSASA && sender.isBlank()) "TALKSASA" else sender
                                                SmsCredentialVault.save(context, selectedProvider, value, defaultSender)
                                                sender = defaultSender
                                                savedSender = defaultSender
                                                connected = true; provider = selectedProvider; dashboard = null; connectionInput = ""
                                                status = result.getOrNull().orEmpty()
                                            } else status = result.exceptionOrNull()?.message ?: "Provider verification failed."
                                        }
                                    }
                                    else -> status = "Enter a valid SMSHub token beginning smshub_ or your TalkSasa API token."
                                }
                            }) { Text("Verify & save", color = C.amber, fontWeight = FontWeight.Bold) }
                        },
                        dismissButton = { TextButton(onClick = { connectionInput = ""; showProviderSaveDialog = false }) { Text("Cancel", color = C.t2) } },
                        containerColor = C.card, titleContentColor = C.t1, textContentColor = C.t2,
                        shape = RoundedCornerShape(UiDimens.DialogRadius),
                    )
                }
                var showToken by remember { mutableStateOf(false) }
                SmsSectionHeading(
                    title = "Provider settings",
                    subtitle = "Connect SMSHub or TalkSasa and manage your sender ID.",
                    accent = if (connected) C.green else C.amber,
                )
                SmsHeroCard(
                    accent = if (connected) C.green else C.amber,
                    borderColor = if (connected) C.green.copy(alpha = 0.35f) else C.borderStrong,
                ) {
                    if (connected) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("CONNECTED PROVIDER", color = C.t3, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, letterSpacing = .7.sp)
                                Text(provider?.name ?: "Messaging provider", color = C.t1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                            }
                            Surface(color = C.greenDim, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, C.green.copy(alpha = 0.35f))) {
                                Text("ACTIVE", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = C.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text("Provider credentials are encrypted at rest with Android Keystore on this device. They are only used to verify the account, send messages and retrieve balances.", style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 18.sp)
                        SmsSecondaryButton(
                            text = "Change provider",
                            onClick = { showDisconnectProviderDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.SwapHoriz,
                        )
                    } else {
                        SmsStepHeader("01", "Connect Provider", accent = C.amber)
                        OutlinedTextField(
                            value = connectionInput,
                            onValueChange = { connectionInput = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            label = { Text("Provider API token") },
                            placeholder = { Text("Paste SMSHub or TalkSasa API token") },
                            singleLine = true,
                            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showToken = !showToken }) {
                                    Icon(
                                        if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = if (showToken) "Hide token" else "Show token",
                                        tint = C.t2,
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = smsFieldColors(),
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = C.amberDim.copy(alpha = 0.65f),
                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.3f)),
                        ) {
                            Text(detectedConnection, Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = C.amber, fontWeight = FontWeight.SemiBold)
                        }
                        Text("SMSHub: create a Personal Access Token on SMSHub → API tokens. Enable balance:read and sms:send permissions. Paste the complete smshub_ token (with or without 'Bearer '). TalkSasa: paste its API token. No separate backend is required.", style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 18.sp)
                        SmsPrimaryButton(
                            text = if (verifying) "Verifying…" else "Review & connect",
                            onClick = { showProviderSaveDialog = true },
                            enabled = !verifying && connectionInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Outlined.VerifiedUser,
                            loading = verifying,
                        )
                    }
                }
                SmsCard(borderColor = C.borderStrong) {
                    SmsStepHeader("02", "Sender ID", accent = C.blue)
                    if (provider == SmsProvider.TALKSASA) {
                        Text(
                            "TALKSASA is selected automatically as your default sender. No separate approval check is required for this default. You can enter another sender ID if your account is configured to use it.",
                            style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 18.sp
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = connected) { sender = "TALKSASA" },
                            shape = RoundedCornerShape(12.dp),
                            color = if (sender.equals("TALKSASA", true)) C.amberDim else C.surface,
                            border = BorderStroke(1.dp, if (sender.equals("TALKSASA", true)) C.amber.copy(alpha = 0.5f) else C.border),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Outlined.Sms, null, tint = C.amber, modifier = Modifier.size(18.dp))
                                Text("TALKSASA", Modifier.weight(1f), color = C.t1, fontWeight = FontWeight.Bold)
                                Text("DEFAULT", color = C.amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                                if (sender.equals("TALKSASA", true)) Icon(Icons.Outlined.CheckCircle, null, tint = C.green, modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Text(
                            "Choose a suggested sender ID or enter a custom ID registered to your SMSHub account. Bingwa checks the account response before saving it as confirmed.",
                            style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 18.sp
                        )
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("BINGWA-INFO", "BINGWA-PAY").forEach { option ->
                                FilterChip(
                                    selected = sender.equals(option, true),
                                    onClick = { sender = option },
                                    label = { Text(option, fontWeight = FontWeight.SemiBold) },
                                    leadingIcon = if (sender.equals(option, true)) ({ Icon(Icons.Outlined.Check, null, modifier = Modifier.size(16.dp)) }) else null,
                                    enabled = connected,
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.amberDim, selectedLabelColor = C.amber, selectedLeadingIconColor = C.amber),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = sender,
                        onValueChange = { sender = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' }.take(11) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (provider == SmsProvider.TALKSASA) "Sender ID" else "Custom sender ID") },
                        placeholder = { Text(if (provider == SmsProvider.TALKSASA) "TALKSASA" else "Enter your registered sender ID") },
                        supportingText = { Text("Up to 11 letters, numbers, hyphens or underscores.") },
                        singleLine = true,
                        enabled = connected,
                        shape = RoundedCornerShape(12.dp),
                        colors = smsFieldColors(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmsPrimaryButton(
                            text = when { verifying -> "Checking…"; provider == SmsProvider.TALKSASA -> "Save sender ID"; else -> "Verify & save" },
                            onClick = {
                                val current = SmsCredentialVault.load(context)
                                val candidate = sender.trim()
                                if (current == null) status = "Connect a provider first."
                                else if (candidate.isBlank()) status = "Enter a sender ID."
                                else if (provider == SmsProvider.TALKSASA) {
                                    SmsCredentialVault.updateSender(context, candidate)
                                    savedSender = candidate
                                    sender = candidate
                                } else {
                                    verifying = true
                                    scope.launch {
                                        val result = SmsProviderClient.verifySender(current, candidate)
                                        verifying = false
                                        if (result.isSuccess) {
                                            SmsCredentialVault.updateSender(context, candidate)
                                            savedSender = candidate
                                            sender = candidate
                                            status = result.getOrNull().orEmpty()
                                        } else status = result.exceptionOrNull()?.message ?: "Sender ID could not be confirmed."
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = connected && !verifying && sender.isNotBlank(),
                            icon = if (provider == SmsProvider.TALKSASA) Icons.Outlined.Save else Icons.Outlined.VerifiedUser,
                            loading = verifying,
                            minHeight = 46.dp,
                        )
                        if (sender.trim() != savedSender.trim()) {
                            SmsSecondaryButton(text = "Reset", onClick = { sender = savedSender }, modifier = Modifier.weight(.55f), minHeight = 46.dp)
                        }
                    }
                    Text(
                        when {
                            !connected -> "Connect a provider to configure the sender ID."
                            provider == SmsProvider.TALKSASA && sender.equals("TALKSASA", true) -> "TALKSASA is ready as the default sender."
                            provider == SmsProvider.TALKSASA -> "Custom sender saved on this device. Actual acceptance depends on your TalkSasa account configuration."
                            sender.isBlank() -> "Choose a suggested sender ID or enter your registered custom ID."
                            else -> "Selected: $sender · verify it with SMSHub before use."
                        },
                        style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 17.sp
                    )
                }
                SmsCard(borderColor = C.borderStrong) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Account overview", color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(if (provider == SmsProvider.SMSHUB) "SMSHub store credit from your account." else "TalkSasa SMS balance from your provider account.", style = MaterialTheme.typography.bodySmall, color = C.t2)
                        }
                        SmsSecondaryButton(
                            text = "Refresh",
                            onClick = {
                                val current = SmsCredentialVault.load(context)
                                if (current == null) status = "Connect an SMS provider first."
                                else {
                                    dashboardLoading = true
                                    scope.launch {
                                        val result = SmsProviderClient.balance(current)
                                        dashboardLoading = false
                                        if (result.isSuccess) {
                                            dashboard = result.getOrNull()
                                            balanceError = null
                                        } else {
                                            balanceError = result.exceptionOrNull()?.message ?: "Balance refresh failed."
                                            status = balanceError.orEmpty()
                                        }
                                    }
                                }
                            },
                            enabled = !dashboardLoading && connected,
                            icon = Icons.Outlined.Refresh,
                            minHeight = 40.dp,
                        )
                    }
                    if (!connected) {
                        Text("Connect a provider to view its available credit and SMS balance.", style = MaterialTheme.typography.bodySmall, color = C.t2)
                    } else if (dashboardLoading && dashboard == null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.amber)
                            Text("Loading live provider balance…", style = MaterialTheme.typography.bodySmall, color = C.t2)
                        }
                    } else if (dashboard == null) {
                        Text("Balance has not loaded yet. Tap Refresh to retrieve the live balance from your provider.", style = MaterialTheme.typography.bodySmall, color = C.t2)
                    }
                    balanceError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = C.red, lineHeight = 17.sp)
                    }
                    dashboard?.let { data ->
                        val profile = data.optJSONObject("profile")
                        if (profile != null) {
                            val accountName = findSmsMetric(profile, listOf("name", "full_name", "account_name", "business_name", "username"))
                            val accountEmail = findSmsMetric(profile, listOf("email", "email_address"))
                            val accountPhone = findSmsMetric(profile, listOf("phone", "phone_number", "mobile", "msisdn"))
                            val accountId = findSmsMetric(profile, listOf("account_id", "user_id", "customer_id", "id"))
                            val accountStatus = findSmsMetric(profile, listOf("status", "account_status"))
                            val profileRows = listOf(
                                "Account name" to accountName,
                                "Email" to accountEmail,
                                "Phone" to accountPhone,
                                "Account ID" to accountId,
                                "Status" to accountStatus,
                            ).filter { !it.second.isNullOrBlank() }
                            if (profileRows.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = C.surface,
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, C.border),
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Outlined.AccountCircle, contentDescription = null, tint = C.amber)
                                            Text("Provider account", color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                            Spacer(Modifier.weight(1f))
                                            Text("LIVE", color = C.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                        profileRows.forEach { (label, value) -> SmsBalanceMetricRow(label, value.orEmpty()) }
                                    }
                                }
                            }
                        }
                        val balance = findSmsBalance(data)
                        val providerBalance = findSmsMetric(data, listOf("provider_balance", "remaining_balance"))
                        val remainingUnits = findSmsMetric(data, listOf("remaining_units", "sms_units_remaining", "available_units", "remaining_sms", "sms_credits", "units_remaining", "sms_balance"))
                        val usedUnits = findSmsMetric(data, listOf("used_units", "sms_units_used", "consumed_units", "used_sms"))
                        val totalUnits = findSmsMetric(data, listOf("total_units", "sms_units_total", "purchased_units", "total_sms"))
                        val accountBalance = findSmsMetric(data, listOf("account_balance", "wallet_balance", "monetary_balance", "available_balance", "available_credit", "account_credit", "credit_balance", "credit"))
                        val currency = findSmsMetric(data, listOf("currency", "currency_code"))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = C.surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, C.amber.copy(alpha = 0.28f)),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    if (provider == SmsProvider.TALKSASA && (remainingUnits != null || providerBalance != null)) "SMS balance"
                                    else balance?.first ?: "Provider balance",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = C.t2
                                )
                                Text(
                                    if (provider == SmsProvider.TALKSASA && (remainingUnits != null || providerBalance != null))
                                        formatTalkSasaUnits(remainingUnits ?: providerBalance.orEmpty())
                                    else remainingUnits ?: providerBalance ?: balance?.second ?: "Balance not supplied by provider",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    color = C.amber,
                                )
                                Text(
                                    if (provider == SmsProvider.SMSHUB) "Live SMSHub account credit"
                                    else if (remainingUnits != null) "Live TalkSasa SMS unit balance"
                                    else if (providerBalance != null) "Live TalkSasa balance reported by provider"
                                    else "Live TalkSasa balance reported by provider",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = C.t2
                                )
                                if (provider == SmsProvider.TALKSASA && (totalUnits != null || usedUnits != null)) {
                                    HorizontalDivider(color = C.border)
                                    if (totalUnits != null) SmsBalanceMetricRow("Total SMS units", totalUnits)
                                    if (usedUnits != null) SmsBalanceMetricRow("Used SMS units", usedUnits)
                                }
                                if (provider == SmsProvider.TALKSASA && accountBalance != null) {
                                    SmsBalanceMetricRow("Account funds", accountBalance + (currency?.let { " $it" } ?: ""))
                                }
                            }
                        }
                        val totals = data.optJSONObject("totals") ?: data.optJSONObject("data")?.optJSONObject("totals") ?: org.json.JSONObject()
                        val statKeys = listOf("submitted", "accepted", "queued", "sent", "delivered", "failed")
                            .filter { totals.has(it) && !totals.isNull(it) }
                        statKeys.forEach { key ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(key.replaceFirstChar { it.uppercase() }, color = C.t2, style = MaterialTheme.typography.bodySmall)
                                Text(totals.opt(key).toString(), color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.bg)
    ) {
        // Signature Bingwa dual radial ambient glow
        Box(
            Modifier
                .size(320.dp)
                .offset((-120).dp, (-70).dp)
                .background(
                    Brush.radialGradient(listOf(C.amber.copy(alpha = 0.08f), Color.Transparent)),
                    CircleShape
                )
        )
        Box(
            Modifier
                .size(260.dp)
                .align(Alignment.TopEnd)
                .offset(80.dp, (-30).dp)
                .background(
                    Brush.radialGradient(listOf(C.cyan.copy(alpha = 0.06f), Color.Transparent)),
                    CircleShape
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = UiDimens.ScreenContentMaxWidth)
                 .padding(horizontal = UiDimens.ScreenHorizontal, vertical = 6.dp)
                .align(Alignment.TopCenter)
                .padding(bottom = if (tab == 0) 148.dp else 76.dp),
            verticalArrangement = Arrangement.spacedBy(if (tab == 10 || tab == 0) 4.dp else 12.dp),
        ) {
        // Reference-aligned centered app bar: current section title and contextual back navigation.
        val sectionTitle = when (tab) {
            0 -> "Send SMS"
            1 -> "Contacts"
            2 -> "Groups"
            3 -> "Auto Messages"
            4 -> "Message History"
            5 -> "Customer Insights"
            6 -> "Campaigns"
            7 -> "Providers & Settings"
            8 -> "Templates"
            9 -> "SMS Pricing"
            11 -> "Schedules"
            else -> "SMS Centre"
        }
        Row(
            Modifier.fillMaxWidth().height(if (tab == 10 || tab == 0) 48.dp else 64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (tab == 10) onBack?.invoke() else tab = 10 },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = C.t1)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    sectionTitle, color = C.t1, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(48.dp))
        }

        if (showMoreMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMoreMenu = false },
                containerColor = C.surface,
                contentColor = C.t1,
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "SMS WORKSPACE",
                            color = C.t1,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Choose a tool by what you want to manage.",
                            color = C.t2,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    @Composable
                    fun SmsToolGroup(
                        title: String,
                        subtitle: String,
                        destinations: List<Triple<Int, String, androidx.compose.ui.graphics.vector.ImageVector>>,
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = C.card,
                            border = BorderStroke(1.dp, C.border.copy(alpha = .8f)),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(title, color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Text(subtitle, color = C.t3, style = MaterialTheme.typography.labelSmall)
                                }
                                destinations.forEachIndexed { index, destination ->
                                    ListItem(
                                        headlineContent = {
                                            Text(destination.second, color = C.t1, fontWeight = FontWeight.SemiBold)
                                        },
                                        leadingContent = {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = C.amberDim,
                                                modifier = Modifier.size(36.dp),
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(destination.third, contentDescription = null, tint = C.amber, modifier = Modifier.size(19.dp))
                                                }
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            tab = destination.first
                                            showMoreMenu = false
                                        },
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    )
                                    if (index < destinations.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 64.dp, end = 14.dp),
                                            color = C.border.copy(alpha = .55f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SmsToolGroup(
                        "Messaging & Automation",
                        "Prepare campaigns and automate customer messages.",
                        listOf(
                            Triple(3, "Auto Messages", Icons.Outlined.Notifications),
                            Triple(6, "Campaigns", Icons.Outlined.Campaign),
                            Triple(11, "Schedules", Icons.Outlined.Schedule),
                            Triple(8, "Templates", Icons.Outlined.Edit),
                        ),
                    )
                    SmsToolGroup(
                        "Customers & Activity",
                        "Review message history and customer engagement.",
                        listOf(
                            Triple(4, "Message History", Icons.Outlined.List),
                            Triple(5, "Customer Insights", Icons.Outlined.Insights),
                        ),
                    )
                    SmsToolGroup(
                        "Provider & Costs",
                        "Manage your SMS connection and understand charges.",
                        listOf(
                            Triple(7, "Provider Setup", Icons.Outlined.Settings),
                            Triple(9, "SMS Pricing", Icons.Outlined.Payments),
                        ),
                    )
                }
            }
        }

        // The uploaded SMS Centre design uses one dedicated bottom navigation
        // with Overview, Send, Contacts, Groups and More. Section-specific
        // sub-navigation is intentionally omitted.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (tab == 10) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(if (tab == 10 || tab == 0) 6.dp else 12.dp),
        ) {
        if (showBusinessDetailsDialog) {
            AlertDialog(
                onDismissRequest = { showBusinessDetailsDialog = false; pendingMarketingTemplate = null },
                title = { Text("Set up your campaign details", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Add your business name, payment Till and customer helpline once. Bingwa inserts them into the selected template automatically, so you do not need to rewrite its wording.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(businessName, { businessName = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Business name") }, singleLine = true)
                        OutlinedTextField(tillNumber, { tillNumber = it.filter { ch -> ch.isLetterOrDigit() || ch == ' ' || ch == '-' }.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("M-Pesa Till / payment number") }, singleLine = true)
                        OutlinedTextField(helpLine, { helpLine = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' || ch == '-' }.take(24) }, modifier = Modifier.fillMaxWidth(), label = { Text("Customer helpline") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (businessName.isBlank() || tillNumber.isBlank() || helpLine.isBlank()) {
                            status = "Enter your business name, Till number and helpline to use this template."
                        } else {
                            SmsMarketingTemplates.saveBusinessDetails(context, businessName, tillNumber, helpLine)
                            pendingMarketingTemplate?.let { chosen ->
                                message = SmsMarketingTemplates.render(chosen, context, tillNumber, helpLine)
                                tab = 0
                                status = "Template prepared with saved details and current enabled offer prices. Review before sending."
                            }
                            pendingMarketingTemplate = null
                            showBusinessDetailsDialog = false
                        }
                    }) { Text("Save and use template", color = C.amber, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { showBusinessDetailsDialog = false; pendingMarketingTemplate = null }) { Text("Cancel", color = C.t2) } },
                containerColor = C.card,
                titleContentColor = C.t1,
                textContentColor = C.t2,
                shape = RoundedCornerShape(UiDimens.DialogRadius)
            )
        }

        if (showRecipientPicker) {
            Dialog(
                onDismissRequest = { showRecipientPicker = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = C.bg,
                    shape = RoundedCornerShape(0.dp)
                ) {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showRecipientPicker = false }) {
                                Icon(Icons.Outlined.ArrowBack, "Back", tint = C.t1)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(if (recipientMode == "Contacts") "Select contacts" else "Select groups", color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                Text(if (recipientMode == "Contacts") "${selected.size} selected" else "${selectedGroups.size} selected · ${groupRecipients.size} recipients", color = C.t2, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                if (recipientMode == "Contacts") selected = emptySet() else selectedGroups = emptySet()
                            }) { Text("Clear", color = C.cyan) }
                            TextButton(onClick = { showRecipientPicker = false }) { Text("Done", color = C.amber, fontWeight = FontWeight.Bold) }
                        }
                        HorizontalDivider(color = C.border)
                        if (recipientMode == "Contacts") {
                            OutlinedTextField(
                                value = search, onValueChange = { search = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Search name or phone") },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                singleLine = true
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${visibleContacts.size} contacts", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { selected = selected + visibleContacts.map { it.phone } }) { Text("Select all results", color = C.cyan) }
                            }
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(visibleContacts, key = { it.phone }) { contact ->
                                    Row(
                                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                            .background(if (contact.phone in selected) C.amber.copy(alpha = .08f) else C.card)
                                            .clickable {
                                                selected = if (contact.phone in selected) selected - contact.phone else selected + contact.phone
                                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(contact.name.ifBlank { "Saved contact" }, color = C.t1, fontWeight = FontWeight.Medium)
                                            Text(contact.phone, color = C.t2, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Checkbox(contact.phone in selected, onCheckedChange = { checked ->
                                            selected = if (checked) selected + contact.phone else selected - contact.phone
                                        })
                                    }
                                }
                            }
                        } else {
                            if (groups.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("No groups yet. Create a group in the Groups tab.", color = C.t2)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(groups, key = { it.id }) { group ->
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                                .background(if (group.id in selectedGroups) C.amber.copy(alpha = .08f) else C.card)
                                                .clickable {
                                                    selectedGroups = if (group.id in selectedGroups) selectedGroups - group.id else selectedGroups + group.id
                                                }.padding(horizontal = 12.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text(group.name, color = C.t1, fontWeight = FontWeight.SemiBold)
                                                Text("${group.phones.size} members", color = C.t2, style = MaterialTheme.typography.bodySmall)
                                            }
                                            Checkbox(group.id in selectedGroups, onCheckedChange = { checked ->
                                                selectedGroups = if (checked) selectedGroups + group.id else selectedGroups - group.id
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { showRecipientPicker = false },
                            modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.ControlMinHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = C.amber, contentColor = C.bg),
                            shape = RoundedCornerShape(UiDimens.ButtonRadius)
                        ) { Text("Done · ${if (recipientMode == "Contacts") selected.size else selectedGroups.size} selected", fontWeight = FontWeight.ExtraBold) }
                    }
                }
            }
        }

        if (showSendConfirmation) {
            val previewRecipients = recipients.take(5)
            AlertDialog(
                onDismissRequest = { if (!sending) showSendConfirmation = false },
                title = { Text("Review before sending", color = C.t1, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("${recipients.size} recipient(s) · ${provider?.name ?: "SMS provider"}", color = C.t2, style = MaterialTheme.typography.labelMedium)
                        previewRecipients.forEach { number ->
                            val contactName = detectedRecipientNames[number]
                                ?: contacts.firstOrNull { normalizeKenyanSmsNumber(it.phone) == number }?.name
                            Text(if (contactName.isNullOrBlank()) "+$number" else "$contactName · +$number",
                                color = C.t1, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (recipients.size > previewRecipients.size) {
                            Text("and ${recipients.size - previewRecipients.size} more", color = C.t2, style = MaterialTheme.typography.bodySmall)
                        }
                        Divider(color = C.border)
                        Text(message, color = C.t1, style = MaterialTheme.typography.bodyMedium)
                        Text("$segmentCount SMS segment(s) · ${messageEstimate.characters} chars · ${messageEstimate.encoding.name.replace("_", "-")}", color = C.t2, style = MaterialTheme.typography.labelSmall)
                        Text("Estimated campaign cost: " + (estimatedCost.totalKes?.let { "KES " + String.format(java.util.Locale.US, "%.2f", it) } ?: "Rate not configured"), color = C.amber, fontWeight = FontWeight.Bold)
                        Text("Estimate only, not an actual provider charge. Send only to people who expect this message. Opted-out numbers are excluded.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !sending,
                        onClick = {
                            showSendConfirmation = false
                            val current = SmsCredentialVault.load(context)
                            if (current == null) {
                                status = "Connect a provider in Account first."
                            } else {
                                sending = true
                                scope.launch {
                                    var accepted = 0
                                    var failed = 0
                                    try {
                                        recipients.forEach { number ->
                                            val result = SmsProviderClient.send(current.copy(senderId = sender), number, message)
                                            val recipientName = contacts.firstOrNull {
                                                normalizeKenyanSmsNumber(it.phone) == number
                                            }?.name?.takeIf { it.isNotBlank() }
                                                ?: detectedRecipientNames[number].orEmpty()
                                                ?: if (manualNormalized == number) detectedRecipientName.orEmpty() else ""
                                            if (result.isSuccess && result.getOrNull()?.accepted == true) {
                                                SmsMessageHistory.append(
                                                    context, current.provider, number, message, result.getOrNull(),
                                                    recipientName = recipientName
                                                )
                                                accepted++
                                            } else {
                                                SmsMessageHistory.append(
                                                    context, current.provider, number, message, result.getOrNull(),
                                                    result.exceptionOrNull()?.message ?: "Provider did not accept the message",
                                                    recipientName
                                                )
                                                failed++
                                            }
                                        }
                                        status = "Provider accepted $accepted of ${recipients.size} messages · $failed failed. See History for details."
                                        Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                                        historyRefresh++
                                    } finally {
                                        sending = false
                                    }
                                }
                            }
                        }
                    ) { Text("Send now", color = C.amber, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showSendConfirmation = false }, enabled = !sending) {
                        Text("Edit message", color = C.t2)
                    }
                },
                containerColor = C.card,
                titleContentColor = C.t1,
                textContentColor = C.t2,
                shape = RoundedCornerShape(UiDimens.DialogRadius),
            )
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
            10 -> overviewTabContent()
            0 -> sendTabContent()

            1 -> contactsTabContent()

            2 -> groupsTabContent()

            3 -> autoMessagesTabContent()

            4 -> historyTabContent()

            5 -> insightsTabContent()

            6 -> campaignsTabContent()

            8 -> templatesTabContent()

            9 -> pricingTabContent()

            11 -> schedulesTabContent()

            7 -> providersTabContent()
        }
        }
        }
        }
        if (tab == 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 76.dp),
                color = C.surface,
                tonalElevation = 0.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (sending) LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = C.amber,
                        trackColor = C.border,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SmsPrimaryButton(
                            text = if (sending) "Sending…" else "Send now · ${recipients.size}",
                            onClick = {
                                val current = SmsCredentialVault.load(context)
                                if (current == null) { status = "Connect a provider in Account first."; return@SmsPrimaryButton }
                                if (sender.isBlank()) { status = "Set an approved sender ID in Account."; return@SmsPrimaryButton }
                                if (recipients.isEmpty()) {
                                    status = if (recipientMode == "Individual" && !manualBulkSend) {
                                        "Enter a valid Kenyan mobile number. Numbers on your opt-out list cannot receive messages."
                                    } else {
                                        "No selected recipients have valid numbers and recorded SMS consent. Update consent in Transactions before sending."
                                    }
                                    return@SmsPrimaryButton
                                }
                                if (message.isBlank()) { status = "Write a message first."; return@SmsPrimaryButton }
                                showSendConfirmation = true
                            },
                            enabled = !sending,
                            modifier = Modifier.weight(1.25f),
                            icon = Icons.Outlined.Send,
                            loading = sending,
                            minHeight = 42.dp,
                        )
                        SmsSecondaryButton(
                            text = "Schedule",
                            onClick = {
                                val delay = scheduleDelay.toLongOrNull()
                                val phone = recipients.firstOrNull().orEmpty()
                                if (SmsCredentialVault.load(context) == null || sender.isBlank()) status = "Connect a provider and configure an approved sender ID in Account."
                                else if (recipientMode != "Individual" || recipients.size != 1 || !phone.matches(Regex("^254[17]\\d{8}$"))) status = "Scheduling is available for one valid recipient at a time. Use Send now for multiple recipients."
                                else if (message.isBlank()) status = "Write a message first."
                                else if (delay == null || delay < 1) status = "Enter a delay of at least one minute."
                                else {
                                    SmsScheduledQueue.enqueue(context, phone, message, sender, delay)
                                    status = "Message scheduled."
                                }
                            },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Outlined.Schedule,
                            minHeight = 42.dp,
                        )
                    }
                }
            }
        }

        NavigationBar(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
            containerColor = C.surface,
            tonalElevation = 0.dp,
        ) {
            NavigationBarItem(
                selected = tab == 10, onClick = { tab = 10 },
                icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
                label = { Text("Overview", maxLines = 1, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = C.amber, selectedTextColor = C.amber, indicatorColor = C.amberDim, unselectedIconColor = C.t2, unselectedTextColor = C.t2),
            )
            NavigationBarItem(
                selected = tab == 0, onClick = { tab = 0 },
                icon = { Icon(Icons.Outlined.Send, contentDescription = "Send SMS") },
                label = { Text("Send", maxLines = 1, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = C.amber, selectedTextColor = C.amber, indicatorColor = C.amberDim, unselectedIconColor = C.t2, unselectedTextColor = C.t2),
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { tab = 1 },
                icon = { Icon(Icons.Outlined.PeopleAlt, contentDescription = "Contacts") },
                label = { Text("Contacts", maxLines = 1, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = C.amber, selectedTextColor = C.amber, indicatorColor = C.amberDim, unselectedIconColor = C.t2, unselectedTextColor = C.t2),
            )
            NavigationBarItem(
                selected = tab == 2, onClick = { tab = 2 },
                icon = { Icon(Icons.Outlined.Group, contentDescription = "Groups") },
                label = { Text("Groups", maxLines = 1, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = C.amber, selectedTextColor = C.amber, indicatorColor = C.amberDim, unselectedIconColor = C.t2, unselectedTextColor = C.t2),
            )
            NavigationBarItem(
                selected = tab in listOf(3, 4, 5, 6, 7, 8, 9, 11), onClick = { showMoreMenu = true },
                icon = { Icon(Icons.Outlined.MoreVert, contentDescription = "More tools") },
                label = { Text("More", maxLines = 1, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = C.amber, selectedTextColor = C.amber, indicatorColor = C.amberDim, unselectedIconColor = C.t2, unselectedTextColor = C.t2),
            )
        }

    }
}
