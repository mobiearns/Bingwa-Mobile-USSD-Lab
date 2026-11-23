package com.bingwa.mobile

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmsCampaignManagerPanel(context: Context, groups: List<SmsContactGroup>, sender: String, connected: Boolean) {
    var refresh by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var selectedGroup by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("Daily") }
    var notice by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf("") }
    val campaigns = remember(refresh) { SmsCampaignQueue.all(context).sortedByDescending { it.optLong("created") } }
    val intervals = linkedMapOf(
        "Hourly" to TimeUnit.HOURS.toMillis(1),
        "Every 2h" to TimeUnit.HOURS.toMillis(2),
        "Every 6h" to TimeUnit.HOURS.toMillis(6),
        "Daily" to TimeUnit.DAYS.toMillis(1),
        "Weekly" to TimeUnit.DAYS.toMillis(7),
        "Monthly" to TimeUnit.DAYS.toMillis(30)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmsSectionHeading(
            title = "Campaign automation",
            subtitle = "Schedule once, track every recipient and retry automatically.",
            accent = C.purple,
        )
        Text(
            "Create a campaign for a saved group, then track delivery and retry pending recipients.",
            color = C.t2,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )
        SmsPrimaryButton(
            text = if (showCreate) "Close campaign form" else "New Campaign",
            onClick = { showCreate = !showCreate },
            modifier = Modifier.fillMaxWidth(),
            icon = if (showCreate) Icons.Outlined.Close else Icons.Outlined.Add,
            minHeight = 44.dp,
        )
        if (showCreate) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmsSectionHeading(title = "New Campaign", accent = C.amber)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Campaign name") },
                placeholder = { Text("e.g. Weekend data promo") },
                shape = RoundedCornerShape(14.dp),
                colors = smsFieldColors(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Choose a saved group", color = C.t1, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                if (groups.isEmpty()) {
                    Text("Create a group in the Groups tab first.", color = C.t2, style = MaterialTheme.typography.bodySmall)
                }
                groups.forEach { group ->
                    val eligible = group.phones.count { SmsNotificationPreferences.consented(context, it) && !SmsNotificationPreferences.optedOut(context, it) }
                    val isSelected = selectedGroup == group.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) C.amberDim.copy(alpha = 0.55f) else C.surface)
                            .clickable { selectedGroup = group.id }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = { selectedGroup = group.id })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(group.name, color = C.t1, fontWeight = FontWeight.SemiBold)
                            Text("${group.phones.size} members · $eligible consent-eligible", color = C.t2, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Repeat frequency", color = C.t1, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    intervals.keys.forEach { option ->
                        val active = repeat == option
                        Surface(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { repeat = option },
                            color = if (active) C.amber else C.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (active) C.amber else C.border)
                        ) {
                            Text(
                                option,
                                Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                                color = if (active) C.bg else C.t2,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Campaign message") },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
                colors = smsFieldColors(),
                supportingText = { Text("This message repeats on the selected schedule. Pause or edit the campaign any time.") }
            )
            SmsPrimaryButton(
                text = "Create recurring campaign",
                onClick = {
                    val group = groups.firstOrNull { it.id == selectedGroup }
                    if (!connected) notice = "Connect SMSHub or TalkSasa in Account first."
                    else if (group == null) notice = "Select a saved group."
                    else runCatching { SmsCampaignQueue.create(context, title, group.id, group.name, group.phones, body, sender, intervals.getValue(repeat)) }
                        .onSuccess { notice = "Campaign scheduled. The first run starts when background work and network are available."; title = ""; body = ""; showCreate = false; refresh++ }
                        .onFailure { notice = it.message ?: "Could not schedule campaign." }
                },
                enabled = connected && sender.isNotBlank() && groups.isNotEmpty() && selectedGroup.isNotBlank() && title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Outlined.EventRepeat,
                minHeight = 52.dp,
            )
            if (notice.isNotBlank()) {
                Surface(color = C.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, C.amber.copy(alpha = 0.3f))) {
                    Text(notice, Modifier.fillMaxWidth().padding(12.dp), color = C.t1, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Only valid Kenyan numbers with recorded SMS consent and no opt-out are included. Android battery restrictions or missing network can delay background runs.", color = C.t3, style = MaterialTheme.typography.labelSmall, lineHeight = 16.sp)
        }
        }

        SmsSectionHeading(
            title = "Your campaigns",
            subtitle = "${campaigns.size} scheduled campaign(s)",
            accent = C.amber,
            trailing = {
                SmsSecondaryButton(
                    text = "Refresh",
                    onClick = { refresh++ },
                    icon = Icons.Outlined.Refresh,
                    minHeight = 40.dp,
                )
            }
        )
        if (campaigns.isEmpty()) {
            Text("No campaigns yet.", Modifier.fillMaxWidth().padding(vertical = 18.dp), color = C.t2, style = MaterialTheme.typography.bodySmall)
        }
        campaigns.forEach { campaign ->
            CampaignCard(
                campaign,
                expanded == campaign.optString("id"),
                { expanded = if (expanded == campaign.optString("id")) "" else campaign.optString("id") },
                { SmsCampaignQueue.retry(context, campaign.optString("id")); notice = "Pending recipients queued with priority."; refresh++ },
                { SmsCampaignQueue.setActive(context, campaign.optString("id"), !campaign.optBoolean("active")); refresh++ },
                { SmsCampaignQueue.remove(context, campaign.optString("id")); refresh++ }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampaignCard(
    campaign: JSONObject,
    expanded: Boolean,
    onExpand: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
) {
    val recipients = campaign.optJSONArray("recipients") ?: org.json.JSONArray()
    var accepted = 0
    var pending = 0
    var failed = 0
    var skipped = 0
    var creditPaused = 0
    for (i in 0 until recipients.length()) when (recipients.optJSONObject(i)?.optString("state")) {
        "ACCEPTED" -> accepted++
        "FAILED" -> failed++
        "PAUSED_CREDIT" -> creditPaused++
        "SKIPPED" -> skipped++
        else -> pending++
    }
    val active = campaign.optBoolean("active", true)
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(campaign.optString("title"), color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${campaign.optString("groupName")} · ${campaign.optString("state").replace('_', ' ')}",
                    color = C.t2,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Text(
                if (active) "Active" else "Paused",
                color = if (active) C.green else C.t3,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ChevronRight,
                contentDescription = if (expanded) "Collapse campaign" else "Expand campaign",
                tint = C.t3,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(campaign.optString("message"), color = C.t2, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Accepted  $accepted", color = C.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text("Pending  $pending", color = C.amber, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text("Failed  $failed", color = if (failed > 0) C.red else C.t3, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
                if (creditPaused > 0 || skipped > 0) {
                    Text(
                        listOfNotNull(
                            creditPaused.takeIf { it > 0 }?.let { "Credit paused $it" },
                            skipped.takeIf { it > 0 }?.let { "Skipped $it" },
                        ).joinToString(" · "),
                        color = C.t2,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                val nextRun = campaign.optLong("nextRun")
                if (nextRun > 0) {
                    Text(
                        "Next run/check: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(nextRun)),
                        color = C.t3,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (pending > 0 || failed > 0 || creditPaused > 0) {
                    Text(
                        "Unsent recipients remain at the front of the retry queue. Bingwa checks again automatically after a credit error.",
                        color = C.amber,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 17.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsSecondaryButton(
                        text = "Recipients",
                        onClick = onExpand,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.PeopleAlt,
                        minHeight = 40.dp,
                    )
                    SmsSecondaryButton(
                        text = "Retry pending",
                        onClick = onRetry,
                        enabled = pending + failed + creditPaused + skipped > 0,
                        modifier = Modifier.weight(1f),
                        icon = Icons.Outlined.Refresh,
                        minHeight = 40.dp,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsTonalButton(
                        text = if (active) "Pause" else "Resume",
                        onClick = onPause,
                        modifier = Modifier.weight(1f),
                        icon = if (active) Icons.Outlined.PauseCircle else Icons.Outlined.PlayCircle,
                    )
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, C.red.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", fontWeight = FontWeight.Bold)
                    }
                }
                Text("Recipient delivery details", color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                for (i in 0 until recipients.length()) {
                    val row = recipients.optJSONObject(i) ?: continue
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.optString("phone"), color = C.t1, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            if (row.optString("lastError").isNotBlank()) {
                                Text(row.optString("lastError"), color = C.t2, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        val rowState = row.optString("state")
                        Text(
                            rowState.replace('_', ' '),
                            color = when (rowState) {
                                "ACCEPTED" -> C.green
                                "FAILED" -> C.red
                                else -> C.amber
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (i < recipients.length() - 1) HorizontalDivider(color = C.border.copy(alpha = 0.65f))
                }
            }
        }
        HorizontalDivider(color = C.border.copy(alpha = 0.65f))
    }
}

@Composable
private fun Metric(label: String, value: Int, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text("$label $value", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}
