@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.bingwa.mobile.history

// Dependencies: Compose BOM 2024.02+ (material3 1.2+ for HorizontalDivider), material-icons-extended, Kotlin 1.9+.
// Stateless UI: feed it HistoryUiState and handle HistoryActions in your ViewModel.
// No USSD / database / scheduling logic lives here.

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/* ───────────── Design tokens (match the approved HTML) ───────────── */
internal object HC {
    val Bg get() = com.bingwa.mobile.C.bg
    val Card get() = com.bingwa.mobile.C.card
    val Card2 get() = com.bingwa.mobile.C.cardHi
    val Border get() = com.bingwa.mobile.C.border
    val Text get() = com.bingwa.mobile.C.t1
    val Muted get() = com.bingwa.mobile.C.t2
    val Amber get() = com.bingwa.mobile.C.amber
    val Cyan get() = com.bingwa.mobile.C.cyan
    val Ok get() = com.bingwa.mobile.C.green
    val Bad get() = com.bingwa.mobile.C.red
    val Warn get() = com.bingwa.mobile.C.amber
    val Delay get() = com.bingwa.mobile.C.amber
    val Timeout get() = com.bingwa.mobile.C.orange
    val BundleBg get() = com.bingwa.mobile.C.cardHi
    val CardShape = RoundedCornerShape(com.bingwa.mobile.UiDimens.CardRadius)
    val BoxShape = RoundedCornerShape(16.dp)
    val ScreenPad = com.bingwa.mobile.UiDimens.ScreenHorizontal
}

/* ───────────── Models ───────────── */
enum class ExecStatus(val label: String, val color: Color, val icon: ImageVector, val sub: String, val live: Boolean = false) {
    Completed("Completed", HC.Ok, Icons.Rounded.Check, "Carrier confirmation received"),
    Failed("Failed", HC.Bad, Icons.Rounded.Close, "Execution did not complete"),
    Pending("Pending", HC.Warn, Icons.Rounded.Schedule, "Waiting to start", true),
    Scheduled("Scheduled", HC.Warn, Icons.Rounded.Schedule, "Not executed yet"),
    Processing("Processing", com.bingwa.mobile.C.blue, Icons.Rounded.Refresh, "Execution in progress", true),
    Delayed("Delayed response", HC.Delay, Icons.Rounded.HourglassTop, "Awaiting carrier confirmation", true), // never treated as failed
    TimedOut("Timed out", HC.Timeout, Icons.Rounded.PriorityHigh, "No response in time"),
    Cancelled("Cancelled", HC.Muted, Icons.Rounded.Remove, "Cancelled before completion")
}

enum class ExecType(val label: String, val note: String) {
    Manual("MANUAL", "Started by you from Manual Execution."),
    Automated("AUTOMATED", "Triggered automatically by an offer rule."),
    Scheduled("SCHEDULED", "Set to run at a chosen date and time.")
}

/** Nullable fields are hidden when the app has no value; never fill them with placeholders. */
data class HistoryRecord(
    val id: Long, val name: String, val phone: String, val offer: String, val amountKes: Int,
    val bundle: String, val type: ExecType, val sim: String, val status: ExecStatus,
    val epochMillis: Long, val dayLabel: String, val dateLabel: String, val timeLabel: String, val ago: String,
    val duration: String? = null, val reference: String? = null, val rawMessage: String? = null,
    val ussd: String? = null, val response: String? = null, val error: String? = null,
    val scheduledFor: String? = null, val isDemo: Boolean = false
)

enum class HistoryFilter(val label: String) {
    All("All"), Completed("Completed"), Failed("Failed"), Pending("Pending"),
    Scheduled("Scheduled"), CancelledTimedOut("Cancelled / Timed out");
    fun matches(r: HistoryRecord) = when (this) {
        All -> true
        Completed -> r.status == ExecStatus.Completed
        Failed -> r.status == ExecStatus.Failed
        Pending -> r.status in setOf(ExecStatus.Pending, ExecStatus.Processing, ExecStatus.Delayed)
        Scheduled -> r.status == ExecStatus.Scheduled
        CancelledTimedOut -> r.status == ExecStatus.Cancelled || r.status == ExecStatus.TimedOut
    }
}

enum class HistoryRetention(val label: String, val days: Long) {
    Daily("Daily", 1L), Weekly("Weekly", 7L), Monthly("Monthly", 30L),
    Yearly("Yearly", 365L), Never("Never", Long.MAX_VALUE)
}

enum class DateRange(val label: String, val short: String) {
    All("All time", "All"), Today("Today", "Today"), Yesterday("Yesterday", "Yesterday"),
    Last7("Last 7 days", "7 days"), Last30("Last 30 days", "30 days"), Custom("Custom range…", "Custom");
    fun matches(millis: Long, now: LocalDate = LocalDate.now()): Boolean {
        val d = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        return when (this) {
            All, Custom -> true // Custom: apply the picked range in your ViewModel
            Today -> d == now
            Yesterday -> d == now.minusDays(1)
            Last7 -> !d.isBefore(now.minusDays(6))
            Last30 -> !d.isBefore(now.minusDays(29))
        }
    }
}

data class HistoryUiState(
    val isLoading: Boolean = false, val loadFailed: Boolean = false,
    val records: List<HistoryRecord> = emptyList(),
    val query: String = "", val filter: HistoryFilter = HistoryFilter.All,
    val dateRange: DateRange = DateRange.All, val fullView: Boolean = false,
    val retention: HistoryRetention = HistoryRetention.Never,
    val collapsedDays: Set<String> = emptySet(),
    val maskNumbers: Boolean = false // false = full numbers (default), true = e.g. 072 *** 3230
) {
    val visible: List<HistoryRecord> get() = records.filter { r ->
        filter.matches(r) && dateRange.matches(r.epochMillis) && (query.isBlank() ||
            listOf(r.name, r.phone, r.offer, r.reference.orEmpty(), r.bundle).joinToString(" ").contains(query.trim(), true))
    }
    fun count(f: HistoryFilter) = records.count { f.matches(it) }
}

/** Raw digits in, display text out. Keep the raw number in the model; mask only for display. */
fun formatPhone(raw: String, mask: Boolean): String {
    val d = raw.filter { it.isDigit() }
    if (d.length < 8) return raw // unknown format: show as stored
    return if (mask) "${d.take(3)} *** ${d.takeLast(4)}" else "${d.take(3)} ${d.substring(3, 6)} ${d.substring(6)}"
}

/** Every interactive element → one callback (implement in the ViewModel / repository layer). */
interface HistoryActions {
    fun onBack(); fun onRequestClearAll(); fun onConfirmClearAll()
    fun onQueryChange(q: String); fun onFilter(f: HistoryFilter); fun onDateRange(r: DateRange)
    fun onToggleMaskNumbers()
    fun onRetentionChange(retention: HistoryRetention)
    fun onViewAll(); fun onToggleDay(day: String); fun onReload()
    fun onCopy(text: String)
    fun onRetry(id: Long); fun onReschedule(id: Long); fun onSendMessage(id: Long); fun onCall(id: Long)
    fun onBlacklist(id: Long); fun onRecommendAlt(id: Long); fun onDelete(id: Long)
}

private sealed interface Confirm { data object ClearAll : Confirm; data class Delete(val id: Long) : Confirm }

/* ───────────── Screen ───────────── */
@Composable
fun HistoryScreen(state: HistoryUiState, actions: HistoryActions, modifier: Modifier = Modifier) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val visible = state.visible
    val selected = state.records.firstOrNull { it.id == selectedId }

    Column(modifier.fillMaxSize().background(HC.Bg).statusBarsPadding()) {
        Column(Modifier.fillMaxWidth().widthIn(max = com.bingwa.mobile.UiDimens.ScreenContentMaxWidth).align(Alignment.CenterHorizontally).padding(horizontal = HC.ScreenPad).padding(top = com.bingwa.mobile.UiDimens.ScreenVertical, bottom = com.bingwa.mobile.UiDimens.SpacingSm), verticalArrangement = Arrangement.spacedBy(com.bingwa.mobile.UiDimens.CardSpacing)) {
            TopBar(state.maskNumbers, actions::onToggleMaskNumbers, onBack = actions::onBack, onClear = { confirm = Confirm.ClearAll }, retention = state.retention,
                onRetention = actions::onRetentionChange, showSubtitle = !state.fullView)
            if (!state.fullView) StatsRow(state, onFilter = actions::onFilter)
            SearchRow(state.query, state.dateRange, actions::onQueryChange, actions::onDateRange)
            if (state.fullView) CountTabs(state, actions::onFilter)
        }
        if (state.records.isNotEmpty())
            Text(
                "${if (state.fullView) visible.size else minOf(3, visible.size)} of ${state.records.size} transactions",
                color = HC.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = HC.ScreenPad, vertical = 4.dp)
            )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Skeleton() }
                state.loadFailed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StateMessage(Icons.Rounded.ErrorOutline, HC.Bad, "History unavailable", "Couldn’t load records right now.", "Retry", actions::onReload)
                }
                state.records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StateMessage(Icons.Rounded.Inbox, HC.Cyan, "No transactions yet", "Manual, automated and scheduled runs will appear here.")
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StateMessage(Icons.Rounded.SearchOff, HC.Amber, "No matching results", "Nothing matches your search or filters.", "Clear filters") {
                        actions.onQueryChange(""); actions.onFilter(HistoryFilter.All); actions.onDateRange(DateRange.All)
                    }
                }
                else -> HistoryList(state, visible, actions, onOpen = { selectedId = it.id })
            }
        }
    }

    selected?.let { r ->
        DetailSheet(r, state.maskNumbers, actions, onDismiss = { selectedId = null }, onDelete = { confirm = Confirm.Delete(r.id) })
    }
    confirm?.let { c ->
        val (title, text) = when (c) {
            Confirm.ClearAll -> "Clear all history?" to "This removes completed and inactive transaction records from this device. Active executions are kept safe."
            is Confirm.Delete -> "Delete this record?" to "This removes the record from this device. It can’t be undone."
        }
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = HC.Card, shape = RoundedCornerShape(com.bingwa.mobile.UiDimens.DialogRadius),
            title = { Text(title, color = HC.Text, fontWeight = FontWeight.SemiBold) },
            text = { Text(text, color = HC.Muted, fontSize = 13.sp) },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep", color = HC.Text) } },
            confirmButton = {
                TextButton({
                    when (c) { Confirm.ClearAll -> actions.onConfirmClearAll(); is Confirm.Delete -> { actions.onDelete(c.id); selectedId = null } }
                    confirm = null
                }) { Text(if (c is Confirm.Delete) "Delete" else "Clear history", color = HC.Bad) }
            }
        )
    }
}

/* ───────────── Header ───────────── */
@Composable
private fun TopBar(masked: Boolean, onToggleMask: () -> Unit, onBack: () -> Unit, onClear: () -> Unit, retention: HistoryRetention, onRetention: (HistoryRetention) -> Unit, showSubtitle: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text("Transaction History", color = HC.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, softWrap = true, textAlign = TextAlign.Center)
            if (showSubtitle) Text("All executions in one place", color = HC.Muted, fontSize = 11.sp, softWrap = true, textAlign = TextAlign.Center)
        }
        Row(
            Modifier.widthIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SquareIconButton(
                if (masked) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (masked) "Show full phone numbers" else "Hide phone numbers", onToggleMask,
                tint = if (masked) HC.Amber else HC.Text, borderColor = if (masked) HC.Amber else HC.Border
            )
            Box {
                SquareIconButton(Icons.Rounded.MoreVert, "History settings", { menuOpen = true })
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(HC.Card)) {
                    DropdownMenuItem(
                        text = { Text("History retention", color = HC.Muted, fontWeight = FontWeight.SemiBold) },
                        enabled = false,
                        onClick = {}
                    )
                    HistoryRetention.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, color = if (option == retention) HC.Amber else HC.Text) },
                            trailingIcon = { if (option == retention) Icon(Icons.Rounded.Check, null, tint = HC.Amber) },
                            onClick = { menuOpen = false; onRetention(option) }
                        )
                    }
                    HorizontalDivider(color = HC.Border)
                    DropdownMenuItem(
                        text = { Text("Clear history", color = HC.Bad) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = HC.Bad) },
                        onClick = { menuOpen = false; onClear() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SquareIconButton(icon: ImageVector, desc: String, onClick: () -> Unit, tint: Color = HC.Text, borderColor: Color = HC.Border) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(HC.Card).border(1.dp, borderColor, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun StatsRow(state: HistoryUiState, onFilter: (HistoryFilter) -> Unit) {
    val tiles = listOf(
        Triple(HistoryFilter.Completed, HC.Ok, "Completed"), Triple(HistoryFilter.Pending, HC.Warn, "Pending"),
        Triple(HistoryFilter.Scheduled, HC.Warn, "Scheduled"), Triple(HistoryFilter.Failed, HC.Bad, "Failed")
    )
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(com.bingwa.mobile.UiDimens.CardRadius)).background(HC.Card).border(1.dp, HC.Border, RoundedCornerShape(com.bingwa.mobile.UiDimens.CardRadius)).padding(horizontal = 4.dp, vertical = 6.dp)) {
        tiles.forEach { (f, c, label) ->
            val on = state.filter == f
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) HC.Border else Color.Transparent)
                    .clickable { onFilter(if (on) HistoryFilter.All else f) }.heightIn(min = 48.dp).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${state.count(f)}", color = c, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(label, color = HC.Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SearchRow(query: String, range: DateRange, onQuery: (String) -> Unit, onRange: (DateRange) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true, modifier = Modifier.weight(1f).heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight),
            placeholder = { Text("Search phone, name, offer…", color = HC.Muted, fontSize = 14.sp, overflow = TextOverflow.Clip) },
            trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Rounded.Close, "Clear search", tint = HC.Muted) } },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HC.Card, unfocusedContainerColor = HC.Card, focusedBorderColor = HC.Amber, unfocusedBorderColor = HC.Border,
                focusedTextColor = HC.Text, unfocusedTextColor = HC.Text, cursorColor = HC.Amber
            )
        )
        DateDropdown(range, onRange)
    }
}

@Composable
private fun DateDropdown(range: DateRange, onPick: (DateRange) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val active = range != DateRange.All
    val tint = if (active) HC.Amber else HC.Text
    Box {
        Row(
            Modifier.heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight).widthIn(min = 64.dp, max = 116.dp).clip(RoundedCornerShape(com.bingwa.mobile.UiDimens.ButtonRadius)).background(HC.Card)
                .border(1.dp, if (active) HC.Amber else HC.Border, RoundedCornerShape(12.dp)).clickable { open = true }
                .padding(horizontal = 10.dp).semantics { contentDescription = "Date range: ${range.label}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = tint, modifier = Modifier.size(18.dp))
            if (active) Text(range.short, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, softWrap = true, overflow = TextOverflow.Clip)
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HC.Muted, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(HC.Card).width(220.dp)) {
            DateRange.entries.forEach { r ->
                DropdownMenuItem(
                    text = { Text(r.label, color = if (r == range) HC.Amber else HC.Text, fontWeight = if (r == range) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (r == range) Icon(Icons.Rounded.Check, null, tint = HC.Amber, modifier = Modifier.size(16.dp)) },
                    onClick = { open = false; onPick(r) }
                )
            }
        }
    }
}

@Composable
private fun CountTabs(state: HistoryUiState, onFilter: (HistoryFilter) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            HistoryFilter.entries.forEach { f ->
                val on = state.filter == f
                Column(Modifier.clickable { onFilter(f) }.heightIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("${f.label} (${state.count(f)})", color = if (on) HC.Text else HC.Muted, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) HC.Amber else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = HC.Border)
    }
}

/* ───────────── List & cards ───────────── */
@Composable
private fun HistoryList(state: HistoryUiState, visible: List<HistoryRecord>, actions: HistoryActions, onOpen: (HistoryRecord) -> Unit) {
    val shown = if (state.fullView) visible else visible.take(3)
    val groups = shown.groupBy { it.dayLabel }
    LazyColumn(
        contentPadding = PaddingValues(start = HC.ScreenPad, end = HC.ScreenPad, bottom = com.bingwa.mobile.UiDimens.SpacingXl),
        verticalArrangement = Arrangement.spacedBy(com.bingwa.mobile.UiDimens.CardSpacing), modifier = Modifier.fillMaxSize().navigationBarsPadding()
    ) {
        groups.entries.forEachIndexed { gi, (day, dayItems) ->
            val collapsed = state.fullView && day in state.collapsedDays
            stickyHeader(key = "h_$day") {
                Row(
                    Modifier.fillMaxWidth().background(HC.Bg).padding(vertical = 6.dp)
                        .then(if (state.fullView) Modifier.clickable { actions.onToggleDay(day) } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.fullView) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = HC.Muted, modifier = Modifier.size(20.dp).then(if (collapsed) Modifier.alpha(.6f) else Modifier))
                        Text(day, color = HC.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, softWrap = true, overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 4.dp))
                        Text(" · ${dayItems.first().dateLabel}", color = HC.Muted, fontSize = 13.sp, softWrap = true, overflow = TextOverflow.Clip)
                        Spacer(Modifier.weight(1f))
                        Text("${dayItems.size} txn${if (dayItems.size == 1) "" else "s"}", color = HC.Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
                    } else {
                        Text("RECENT · ${day.uppercase()}", color = HC.Muted, fontSize = 11.sp, letterSpacing = 1.sp, softWrap = true, overflow = TextOverflow.Clip)
                        Spacer(Modifier.weight(1f))
                        if (gi == 0) TextButton(actions::onViewAll, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("View all", color = HC.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = HC.Cyan, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (!collapsed) items(dayItems, key = { it.id }) {
                HistoryTransactionRow(r = it, maskNumbers = state.maskNumbers, onClick = { onOpen(it) })
            }
        }
    }
}

/**
 * Lightweight contact-style transaction row used only by the main History list.
 * Keep the screen's existing header, filters, date grouping and detail interactions intact.
 */
@Composable
private fun HistoryTransactionRow(r: HistoryRecord, maskNumbers: Boolean, onClick: () -> Unit) {
    val accent = r.status.color
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = "${r.name}, ${r.offer}, ${r.timeLabel}, ${r.status.label}, KES ${r.amountKes}. Open details"
                }
                .padding(horizontal = 2.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActivityTicketAvatar(r.name, accent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    r.name.ifBlank { "Unknown customer" },
                    color = HC.Text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    r.offer.ifBlank { "Offer details unavailable" },
                    color = HC.Text.copy(alpha = .88f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        formatPhone(r.phone, maskNumbers),
                        color = HC.Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(Modifier.size(3.dp).background(HC.Muted.copy(alpha = .65f), CircleShape))
                    Text(
                        if (r.status == ExecStatus.Scheduled) r.scheduledFor ?: r.timeLabel else r.timeLabel,
                        color = HC.Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(
                modifier = Modifier.widthIn(min = 72.dp, max = 92.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    "KSh ${r.amountKes}",
                    color = HC.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
                StatusPill(r.status)
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 48.dp),
            color = HC.Border.copy(alpha = .48f),
            thickness = 0.6.dp
        )
    }
}

@Composable
fun TransactionCard(r: HistoryRecord, maskNumbers: Boolean, onClick: () -> Unit, onHide: (() -> Unit)? = null) {
    val accent = r.status.color
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "${r.name}, ${r.status.label}, KES ${r.amountKes}. Open details" }
            .padding(vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ActivityTicketAvatar(r.name, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.name.ifBlank { "Unknown customer" }, color = HC.Text, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatPhone(r.phone, maskNumbers), color = HC.Muted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("KSh ${r.amountKes}", color = HC.Text, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
                ActivityTicketStatus(r.status, accent)
            }
            if (onHide != null) {
                IconButton(onClick = onHide, modifier = Modifier.size(30.dp)) {
                    Icon(if (maskNumbers) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (maskNumbers) "Show full phone numbers" else "Hide phone numbers",
                        tint = if (maskNumbers) HC.Amber else HC.Muted, modifier = Modifier.size(16.dp))
                }
            }
        }
        Text(r.offer.ifBlank { "Offer details unavailable" }, color = HC.Text,
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 46.dp, top = 8.dp),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.fillMaxWidth().padding(start = 46.dp, top = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(r.sim.ifBlank { "SIM not specified" }, color = HC.Muted, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (r.status == ExecStatus.Scheduled) "Scheduled · ${r.scheduledFor ?: r.timeLabel}" else r.timeLabel,
                color = HC.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = HC.Border.copy(alpha = .55f), thickness = 0.7.dp)
    }
}

@Composable
private fun ActivityTicketAvatar(name: String, accent: Color) {
    val initials = name.trim().split(" ").filter { it.isNotBlank() }
        .take(2).mapNotNull { it.firstOrNull() }.joinToString("").uppercase()
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = .16f))
            .border(1.dp, accent.copy(alpha = .32f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(initials, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ActivityTicketStatus(status: ExecStatus, accent: Color) {
    Row(
        Modifier.clip(CircleShape)
            .background(accent.copy(alpha = .14f))
            .padding(start = 7.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Text(
            status.label,
            color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, modifier = Modifier.padding(start = 5.dp)
        )
    }
}

@Composable
private fun TransactionTypePill(type: ExecType) {
    val accent = when (type) {
        ExecType.Manual -> HC.Amber
        ExecType.Automated -> HC.Ok
        ExecType.Scheduled -> HC.Cyan
    }
    Surface(
        color = accent.copy(alpha = .105f),
        shape = CircleShape,
        border = BorderStroke(1.dp, accent.copy(alpha = .24f))
    ) {
        Text(
            text = type.label,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .75.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun StatusAvatar(name: String, status: ExecStatus, size: androidx.compose.ui.unit.Dp) {
    val initials = name.split(" ").filter { it.isNotBlank() }.takeLast(2).joinToString("") { it.first().uppercase() }
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(status.color.copy(alpha = .12f)).border(1.dp, status.color.copy(alpha = .52f), CircleShape), contentAlignment = Alignment.Center) {
            Text(initials, color = HC.Text, fontSize = (size.value * .3f).sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(18.dp).align(Alignment.BottomEnd).offset(4.dp, 4.dp).clip(CircleShape).background(HC.Bg).border(2.dp, status.color, CircleShape), contentAlignment = Alignment.Center) {
            Icon(status.icon, null, tint = status.color, modifier = Modifier.size(10.dp))
        }
    }
}

@Composable
fun StatusPill(status: ExecStatus) {
    val pulse = if (status.live) rememberInfiniteTransition(label = "pulse").animateFloat(1f, .25f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "a").value else 1f
    Row(
        Modifier.clip(CircleShape).background(status.color.copy(alpha = .14f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).alpha(pulse).clip(CircleShape).background(status.color))
        Spacer(Modifier.width(5.dp))
        Text(status.label, color = status.color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,)
    }
}

@Composable
private fun BundleChip(text: String, size: androidx.compose.ui.unit.TextUnit) {
    Text(text, color = HC.Ok, fontSize = size, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(HC.BundleBg).padding(horizontal = 10.dp, vertical = 5.dp))
}

/* ───────────── Detail sheet ───────────── */
@Composable
private fun DetailSheet(r: HistoryRecord, mask: Boolean, actions: HistoryActions, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val copy = { text: String -> clipboard.setText(AnnotatedString(text)); actions.onCopy(text) }
    val primary: Pair<String, () -> Unit>? = when (r.status) {
        ExecStatus.Failed, ExecStatus.TimedOut -> "Retry" to { actions.onRetry(r.id) }
        ExecStatus.Pending, ExecStatus.Scheduled -> "Reschedule" to { actions.onReschedule(r.id) }
        else -> null
    }
    val more = buildList<Triple<String, ImageVector, () -> Unit>> {
        if (primary?.first != "Retry" && r.status in setOf(ExecStatus.Completed, ExecStatus.Failed, ExecStatus.TimedOut)) add(Triple("Retry", Icons.Rounded.Refresh) { actions.onRetry(r.id) })
        if (primary?.first != "Reschedule" && r.status in setOf(ExecStatus.Failed, ExecStatus.Scheduled, ExecStatus.Pending, ExecStatus.TimedOut)) add(Triple("Reschedule", Icons.Rounded.Schedule) { actions.onReschedule(r.id) })
        add(Triple("Send Msg", Icons.Rounded.Sms) { actions.onSendMessage(r.id) })
        add(Triple("Call", Icons.Rounded.Call) { actions.onCall(r.id) })
        add(Triple("Blacklist", Icons.Rounded.Block) { actions.onBlacklist(r.id) })
        add(Triple("Recommend alt", Icons.Rounded.SwapHoriz) { actions.onRecommendAlt(r.id) })
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = HC.Card, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Summary(r, mask)
                r.error?.let { Note("Failure reason", it, HC.Bad) }
                if (r.status == ExecStatus.Delayed) Note("Carrier response still awaited.", "This is not treated as a failure. The status will update when the carrier replies.", HC.Delay)
                if (r.status == ExecStatus.Scheduled) Note("Not executed yet.", "Scheduled for ${r.scheduledFor ?: r.timeLabel}.", HC.Warn)

                Section("Transaction") {
                    KeyValue("Customer", r.name); KeyValue("Phone", formatPhone(r.phone, mask)); r.reference?.let { KeyValue("Reference", it) }
                }
                Section("Execution") {
                    KeyValue("Execution type") { Text(r.type.label, color = HC.Cyan, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.border(1.dp, HC.Amber.copy(alpha = 0.42f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) }
                    Text(r.type.note, color = HC.Muted, fontSize = 12.sp, softWrap = true, overflow = TextOverflow.Clip)
                    r.sim.takeIf { it.isNotBlank() }?.let { KeyValue("SIM used", it) }
                    if (r.status == ExecStatus.Scheduled) {
                        KeyValue("Scheduled for") { Text(r.scheduledFor ?: "—", color = HC.Amber, fontWeight = FontWeight.Bold) }
                        KeyValue("Started") { Text("Not yet", color = HC.Muted) }
                    } else KeyValue(if (r.status.live) "Started" else "Executed") { Text("${r.timeLabel} · ${r.dayLabel}", color = HC.Text, fontSize = 14.sp) }
                    r.duration?.let { KeyValue("Duration") { Text(it, color = HC.Cyan, fontSize = 14.sp) } }
                }
                if (listOf(r.rawMessage, r.ussd, r.response).any { it != null }) {
                    SectionLabel("Carrier & USSD")
                    r.rawMessage?.let { CodeBlock("Raw message", it, false) { copy(it) } }
                    r.ussd?.let { CodeBlock("USSD", it, true) { copy(it) } }
                    r.response?.let { CodeBlock("Response", it, false) { copy(it) } }
                }
                if (more.isNotEmpty()) {
                    SectionLabel("More actions")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        more.forEach { (label, icon, run) ->
                            Row(
                                Modifier.heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight).clip(CircleShape).background(HC.Card).border(1.dp, HC.Border, CircleShape).clickable(onClick = run).padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) { Icon(icon, null, tint = HC.Cyan, modifier = Modifier.size(16.dp)); Text(label, color = HC.Cyan, fontSize = 13.sp) }
                        }
                    }
                }
                OutlinedButton(onDelete, Modifier.fillMaxWidth().heightIn(min = com.bingwa.mobile.UiDimens.ControlMinHeight), shape = RoundedCornerShape(com.bingwa.mobile.UiDimens.ButtonRadius), border = BorderStroke(1.dp, HC.Bad.copy(alpha = 0.42f))) {
                    Text("Delete this record", color = HC.Bad, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = HC.Border)
            Row(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.let { (label, run) ->
                    Button(run, Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(HC.Amber, HC.Bg)) { Text(label, fontWeight = FontWeight.SemiBold) }
                }
                OutlinedButton(
                    { copy(listOf(r.name, formatPhone(r.phone, mask), r.offer, "KES ${r.amountKes}", r.status.label, "${r.dayLabel} ${r.timeLabel}").joinToString(" · ")) },
                    Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, HC.Border)
                ) { Icon(Icons.Rounded.ContentCopy, null, tint = HC.Text, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy details", color = HC.Text, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun HistoryTransactionDetailSheet(
    record: HistoryRecord,
    maskNumbers: Boolean,
    actions: HistoryActions,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    DetailSheet(record, maskNumbers, actions, onDismiss, onDelete)
}

@Composable
private fun Summary(r: HistoryRecord, mask: Boolean) {
    val status = r.status
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(HC.Card2, HC.Card)))
            .border(1.dp, status.color.copy(alpha = .34f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusAvatar(r.name, status, 46.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r.name.ifBlank { "Unknown customer" }, color = HC.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatPhone(r.phone, mask), color = HC.Muted, fontSize = 12.sp)
            }
            if (r.isDemo) Text("DEMO", color = HC.Muted, fontSize = 9.sp, modifier = Modifier.border(1.dp, HC.Muted.copy(alpha = .55f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp))
        }
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(status.color.copy(alpha = .11f))
                .border(1.dp, status.color.copy(alpha = .28f), RoundedCornerShape(13.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(status.color.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                Icon(status.icon, null, tint = status.color, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(status.label, color = status.color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(status.sub, color = HC.Muted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            if (status.live) {
                Text("LIVE", color = status.color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("TRANSACTION VALUE", color = HC.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text(
                    "KES ${r.amountKes}",
                    color = if (status == ExecStatus.Completed) HC.Ok else HC.Amber,
                    fontSize = 25.sp, fontWeight = FontWeight.ExtraBold
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (status == ExecStatus.Scheduled) "RUN TIME" else "TIME", color = HC.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text(if (status == ExecStatus.Scheduled) r.scheduledFor ?: r.timeLabel else r.timeLabel, color = HC.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                Text(r.dayLabel, color = HC.Muted, fontSize = 10.sp)
            }
        }
        HorizontalDivider(color = HC.Border.copy(alpha = .8f))
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(HC.Bg.copy(alpha = .62f))
                .border(1.dp, HC.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("PURCHASED OFFER", color = HC.Muted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text(r.offer.ifBlank { "Offer details unavailable" }, color = HC.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, softWrap = true)
            if (r.bundle.isNotBlank()) BundleChip(r.bundle, 11.sp)
        }
    }
}

/* ───────────── Small building blocks ───────────── */
@Composable private fun SectionLabel(t: String) = Text(t.uppercase(), color = HC.Muted, fontSize = 11.sp, letterSpacing = 1.sp)

@Composable
private fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        Column(Modifier.fillMaxWidth().clip(HC.BoxShape).background(HC.Card2).border(1.dp, HC.Border, HC.BoxShape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable private fun KeyValue(k: String, v: String) = KeyValue(k) { Text(v, color = HC.Text, fontSize = 14.sp, textAlign = TextAlign.End) }

@Composable
private fun KeyValue(k: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = HC.Muted, fontSize = 13.sp, modifier = Modifier.weight(0.9f))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.CenterEnd) { value() }
    }
}

@Composable
private fun Note(title: String, body: String, color: Color) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = .12f)).border(1.dp, color.copy(alpha = .4f), RoundedCornerShape(12.dp)).padding(10.dp, 10.dp)) {
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(body, color = color, fontSize = 12.sp)
    }
}

@Composable
private fun CodeBlock(label: String, text: String, mono: Boolean, onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(HC.BoxShape).background(HC.Card2).border(1.dp, HC.Border, HC.BoxShape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label); Spacer(Modifier.weight(1f))
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(HC.Card).border(1.dp, HC.Border, RoundedCornerShape(10.dp)).clickable(onClick = onCopy), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ContentCopy, "Copy $label", tint = HC.Text, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, color = HC.Text, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, overflow = TextOverflow.Clip, modifier = Modifier.clickable { expanded = !expanded })
    }
}

@Composable
private fun StateMessage(icon: ImageVector, tint: Color, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(32.dp)); Spacer(Modifier.height(8.dp))
        Text(title, color = HC.Text, fontWeight = FontWeight.Bold); Text(body, color = HC.Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        action?.let { Spacer(Modifier.height(12.dp)); OutlinedButton(onAction, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, HC.Border)) { Text(it, color = HC.Text) } }
    }
}

@Composable
private fun Skeleton() {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(.35f, .8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Column(Modifier.padding(HC.ScreenPad), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { Box(Modifier.fillMaxWidth().heightIn(min = 78.dp).alpha(a).clip(RoundedCornerShape(16.dp)).background(HC.Card)) }
    }
}

