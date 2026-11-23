package com.bingwa.mobile

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun SmsMarketingTemplatesPanel(
    context: Context,
    onChoose: (SmsMarketingTemplate) -> Unit,
    onEditBusinessDetails: () -> Unit,
    onCreateGroup: (SmsMarketingTemplate) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<SmsMarketingTemplate?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SmsMarketingTemplate?>(null) }
    var deleting by remember { mutableStateOf<SmsMarketingTemplate?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    val custom = remember(refresh) { SmsMarketingTemplates.custom(context) }
    val builtIn = SmsMarketingTemplates.all

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmsSectionHeading(
            title = "Message templates",
            subtitle = "${builtIn.size + custom.size} templates · tap any row to read and use",
            accent = C.blue,
            trailing = {
                SmsPrimaryButton(
                    text = "Add",
                    onClick = { editing = null; title = ""; body = ""; showEditor = true },
                    icon = Icons.Outlined.Add,
                    minHeight = 40.dp,
                )
            }
        )
        SmsSecondaryButton(
            text = "Edit business name, Till and helpline",
            onClick = onEditBusinessDetails,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Storefront,
        )
        Text(
            "Offer prices are filled from your enabled offers. Open a template to read it in full, use it, or create a contact group for that message.",
            color = C.t2,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )
        if (notice.isNotBlank()) {
            Text(notice, Modifier.fillMaxWidth(), color = C.amber, style = MaterialTheme.typography.bodySmall)
        }

        (builtIn + custom).groupBy { it.category }.forEach { (category, templates) ->
            val categoryAccent = when {
                category.contains("Offer campaign", true) -> C.amber
                category.contains("announcement", true) -> C.purple
                category.contains("Service +", true) -> C.green
                category.contains("Service", true) -> C.blue
                else -> C.amber
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmsSectionHeading(
                    title = category,
                    accent = categoryAccent,
                )
                templates.forEach { template ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selected = template }
                            .padding(vertical = 10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(template.title, color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                Text(template.body, color = C.t2, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp, maxLines = 2)
                                Text(
                                    "${SmsMarketingTemplates.estimate(template.body).segments} SMS segment(s) · Tap to preview",
                                    color = categoryAccent,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Open template", tint = C.t3, modifier = Modifier.size(18.dp))
                        }
                        HorizontalDivider(color = C.border.copy(alpha = 0.65f))
                    }
                }
            }
        }
    }

    if (selected != null) {
        val template = selected!!
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(template.title, color = C.t1, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(template.category.uppercase(Locale.ROOT), color = C.amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, letterSpacing = .7.sp)
                    Surface(Modifier.fillMaxWidth(), color = C.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, C.border)) {
                        Text(template.body, Modifier.padding(14.dp), color = C.t1, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                    }
                    Text("${SmsMarketingTemplates.estimate(template.body).characters} characters · ${SmsMarketingTemplates.estimate(template.body).segments} SMS segment(s)", color = C.t2, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsPrimaryButton(
                        text = "Use this template",
                        onClick = { onChoose(template); selected = null },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.Send,
                        minHeight = 48.dp,
                    )
                    SmsSecondaryButton(
                        text = "Create group for this template",
                        onClick = { onCreateGroup(template); selected = null },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.GroupAdd,
                        minHeight = 46.dp,
                    )
                    if (template.id.startsWith("custom_")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { title = template.title; body = template.body; editing = template; selected = null; showEditor = true }) { Text("Edit", color = C.amber, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { deleting = template; selected = null }) { Text("Delete", color = C.red, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close", color = C.t2) } },
            containerColor = C.card,
            shape = RoundedCornerShape(22.dp)
        )
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false; editing = null; title = ""; body = "" },
            title = { Text(if (editing == null) "Create template" else "Edit template", color = C.t1, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it.take(70) }, modifier = Modifier.fillMaxWidth(), label = { Text("Template name") }, singleLine = true, shape = RoundedCornerShape(13.dp), colors = smsFieldColors())
                    OutlinedTextField(body, { body = it.take(2000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Full message text") }, minLines = 5, shape = RoundedCornerShape(13.dp), colors = smsFieldColors())
                    Text("${SmsMarketingTemplates.estimate(body).characters} characters · ${SmsMarketingTemplates.estimate(body).segments} SMS segment(s)", color = C.t2, style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                SmsPrimaryButton(
                    text = "Save template",
                    onClick = {
                        if (title.isBlank() || body.isBlank()) notice = "Enter both a template name and full message."
                        else {
                            val id = editing?.id ?: "custom_" + java.util.UUID.randomUUID().toString()
                            SmsMarketingTemplates.saveCustom(context, SmsMarketingTemplate(id, "My templates", title.trim(), body.trim()))
                            refresh++
                            showEditor = false
                            editing = null
                            title = ""; body = ""
                            notice = "Template saved."
                        }
                    },
                    minHeight = 44.dp,
                )
            },
            dismissButton = { TextButton(onClick = { showEditor = false; editing = null; title = ""; body = "" }) { Text("Cancel", color = C.t2) } },
            containerColor = C.card,
            shape = RoundedCornerShape(22.dp)
        )
    }

    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete template?", color = C.t1, fontWeight = FontWeight.Bold) },
            text = { Text("Delete “${deleting?.title}”? This cannot be undone.", color = C.t2) },
            confirmButton = {
                TextButton(onClick = {
                    deleting?.let { SmsMarketingTemplates.deleteCustom(context, it.id) }
                    deleting = null
                    refresh++
                    notice = "Template deleted."
                }) { Text("Delete", color = C.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = C.t2) } },
            containerColor = C.card,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
internal fun SmsPricingPanel(context: Context, activeProvider: SmsProvider?) {
    var refresh by remember { mutableIntStateOf(0) }
    var talkRate by remember(refresh) { mutableStateOf(SmsMarketingTemplates.rate(context, SmsProvider.TALKSASA)?.toString() ?: "0.35") }
    var hubRate by remember(refresh) { mutableStateOf(SmsMarketingTemplates.rate(context, SmsProvider.SMSHUB)?.toString().orEmpty()) }
    var talkInvoice by remember { mutableStateOf("") }
    var hubInvoice by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SmsSectionHeading(
            title = "SMS pricing & estimates",
            subtitle = "Set provider rates and review estimated campaign costs.",
            accent = C.blue,
        )
        SmsHeroCard(
            accent = C.amber,
            borderColor = C.amber.copy(alpha = .35f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, tint = C.amber, modifier = Modifier.size(18.dp))
                Text("ESTIMATES ONLY", color = C.amber, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium, letterSpacing = .8.sp)
            }
            Text("These are planning estimates, not actual provider charges or a live quote. Final billing can vary by route, sender and provider rules.", color = C.t1, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
            Text("TalkSasa reference: 1 segment = 1 SMS credit · KES 35 = 100 credits. Example: 46 recipients × 1 segment ≈ KES 16.10 at the default rate.", color = C.t2, style = MaterialTheme.typography.labelSmall, lineHeight = 17.sp)
        }
        ProviderRateEditor("TalkSasa", SmsProvider.TALKSASA, talkRate, { talkRate = it }, talkInvoice, { talkInvoice = it },
            SmsMarketingTemplates.rateSource(context, SmsProvider.TALKSASA),
            { val v = talkRate.toDoubleOrNull(); if (v == null || v <= 0) notice = "Enter a valid TalkSasa rate." else { SmsMarketingTemplates.saveRate(context, SmsProvider.TALKSASA, v); notice = "TalkSasa rate saved."; refresh++ } },
            { val v = talkInvoice.toDoubleOrNull(); if (v == null) notice = "Enter the TalkSasa invoice amount." else runCatching { SmsMarketingTemplates.calibrateFromInvoice(context, SmsProvider.TALKSASA, v) }.onSuccess { pair -> notice = "Learned reference KES " + String.format(Locale.US, "%.4f", pair.first) + " per segment from " + pair.second + " accepted segments. Configured rate unchanged." }.onFailure { notice = it.message ?: "Could not calibrate." } },
            activeProvider == SmsProvider.TALKSASA)
        ProviderRateEditor("SMSHub", SmsProvider.SMSHUB, hubRate, { hubRate = it }, hubInvoice, { hubInvoice = it },
            SmsMarketingTemplates.rateSource(context, SmsProvider.SMSHUB),
            { val v = hubRate.toDoubleOrNull(); if (v == null || v <= 0) notice = "Enter a valid SMSHub rate." else { SmsMarketingTemplates.saveRate(context, SmsProvider.SMSHUB, v); notice = "SMSHub rate saved."; refresh++ } },
            { val v = hubInvoice.toDoubleOrNull(); if (v == null) notice = "Enter the SMSHub invoice amount." else runCatching { SmsMarketingTemplates.calibrateFromInvoice(context, SmsProvider.SMSHUB, v) }.onSuccess { pair -> notice = "Learned reference KES " + String.format(Locale.US, "%.4f", pair.first) + " per segment from " + pair.second + " accepted segments. Configure the rate above to use it." }.onFailure { notice = it.message ?: "Could not calibrate." } },
            activeProvider == SmsProvider.SMSHUB)
        if (notice.isNotBlank()) {
            Surface(color = C.card, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, C.amber.copy(alpha = 0.35f))) {
                Text(notice, Modifier.fillMaxWidth().padding(12.dp), color = C.t1, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Rate learning needs a provider invoice or actual spend for the same period. Bingwa divides that amount by the GSM/Unicode segments calculated from accepted message history.", color = C.t3, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
    }
}

@Composable
private fun ProviderRateEditor(title: String, provider: SmsProvider, rate: String, onRate: (String) -> Unit, invoice: String, onInvoice: (String) -> Unit, source: String, save: () -> Unit, calibrate: () -> Unit, active: Boolean) {
    SmsCard(borderColor = if (active) C.green.copy(alpha = .45f) else C.border) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = C.t1, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                if (active) {
                    Surface(color = C.greenDim, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, C.green.copy(alpha = 0.35f))) {
                        Text("ACTIVE", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = C.green, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(source, color = C.t2, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(
            value = rate,
            onValueChange = { onRate(it.filter { c -> c.isDigit() || c == '.' }.take(10)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Estimated KES per SMS segment") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = smsFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { Text(if (provider == SmsProvider.TALKSASA) "Default KES 0.35 per segment; configurable." else "Set your SMSHub rate or calibrate it from an invoice.") }
        )
        SmsPrimaryButton(
            text = "Save $title rate",
            onClick = save,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.Save,
        )
        HorizontalDivider(color = C.border)
        Text("Calibrate from provider invoice", color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = invoice,
            onValueChange = { onInvoice(it.filter { c -> c.isDigit() || c == '.' }.take(12)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Billed amount (KES)") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = smsFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        SmsSecondaryButton(
            text = "Learn reference rate from history",
            onClick = calibrate,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Outlined.AutoGraph,
        )
    }
}

@Composable
internal fun SmsCostEstimatePanel(estimate: SmsCostEstimate, provider: SmsProvider?, context: Context) {
    val amount = estimate.totalKes?.let { "KES " + String.format(Locale.US, "%,.2f", it) } ?: "Rate not set"
    val rateLabel = estimate.ratePerSegment?.let { "KES " + String.format(Locale.US, "%.4f", it) } ?: "Not configured"
    val rateSource = provider?.let { SmsMarketingTemplates.rateSource(context, it) } ?: "Connect a provider"
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Estimated cost", color = C.t1, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(amount, color = C.amber, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
        }
        CostBreakdownRow("Eligible recipients", "${estimate.recipients}")
        CostBreakdownRow("Segments per recipient", "${estimate.segmentsPerRecipient}")
        CostBreakdownRow("Total billable segments", "${estimate.totalSegments}", emphasize = true)
        CostBreakdownRow("Rate per segment", rateLabel)
        CostBreakdownRow("Rate source", rateSource)
        Text(
            when {
                provider == null -> "Connect SMSHub or TalkSasa to calculate a provider-specific estimate."
                estimate.ratePerSegment == null -> "No ${provider.name} rate is configured. Add the rate in Pricing; no price is guessed."
                else -> "Estimate only. Final charges may vary by route, sender ID, multipart handling or provider fees."
            },
            color = C.t2,
            style = MaterialTheme.typography.bodySmall,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun CostBreakdownRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (emphasize) C.t1 else C.t2,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = if (emphasize) C.amber else C.t1,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
