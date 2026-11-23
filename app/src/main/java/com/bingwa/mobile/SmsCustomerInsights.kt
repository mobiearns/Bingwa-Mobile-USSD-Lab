package com.bingwa.mobile

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import java.util.Locale

internal data class SmsCustomerInsight(
    val phone: String,
    val name: String,
    val purchases: Int,
    val recentSpend: Double,
    val previousSpend: Double,
    val lastPurchaseAt: Long,
    val daysInactive: Int,
    val segment: String,
    val consentEligible: Boolean
)

internal data class SmsInsightSegment(
    val key: String,
    val title: String,
    val description: String,
    val campaignHint: String,
    val customers: List<SmsCustomerInsight>
) {
    val eligibleCustomers: List<SmsCustomerInsight>
        get() = customers.filter { it.consentEligible }
}

private fun smsInsightPhone(raw: String): String {
    val digits = raw.filter(Char::isDigit)
    return when {
        digits.startsWith("254") && digits.length == 12 -> digits
        digits.startsWith("0") && digits.length == 10 -> "254" + digits.drop(1)
        digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254" + digits
        else -> ""
    }
}

internal fun buildSmsInsightSegments(
    context: Context,
    contacts: List<SavedContact>,
    transactions: List<Transaction>,
    now: Long = System.currentTimeMillis()
): List<SmsInsightSegment> {
    val contactNames = contacts.associate { smsInsightPhone(it.phone) to it.name }
    val successful = transactions
        .filter { it.statusEnum == TransactionStatus.SUCCESS }
        .mapNotNull { tx ->
            val phone = smsInsightPhone(tx.phoneNumber)
            val at = transactionTimestamp(tx)
            if (phone.isBlank() || at <= 0L) null else phone to tx
        }
        .groupBy({ it.first }, { it.second })

    val messageHistory = SmsMessageHistory.load(context)
    val acceptedMessages = messageHistory.filter { it.optString("status") == "Accepted" }

    val customers = successful.mapNotNull { (phone, rows) ->
        val ordered = rows.sortedBy { transactionTimestamp(it) }
        val latest = ordered.lastOrNull() ?: return@mapNotNull null
        val latestAt = transactionTimestamp(latest)
        val previous = ordered.getOrNull(ordered.lastIndex - 1)
        val previousAt = previous?.let(::transactionTimestamp) ?: 0L
        val daysInactive = TimeUnit.MILLISECONDS.toDays((now - latestAt).coerceAtLeast(0L)).toInt()
        val recentStart = now - TimeUnit.DAYS.toMillis(30)
        val previousStart = now - TimeUnit.DAYS.toMillis(60)
        val recentSpend = ordered.filter { transactionTimestamp(it) in recentStart..now }.sumOf { it.amountValue.coerceAtLeast(0.0) }
        val previousSpend = ordered.filter { transactionTimestamp(it) in previousStart until recentStart }.sumOf { it.amountValue.coerceAtLeast(0.0) }
        val messageBetweenPurchases = previousAt > 0L && latestAt > previousAt && acceptedMessages.any { row ->
            val recipient = smsInsightPhone(row.optString("to"))
            val sentAt = row.optLong("at", 0L)
            recipient == phone && sentAt in previousAt until latestAt
        }
        val segment = when {
            previous != null && TimeUnit.MILLISECONDS.toDays((latestAt - previousAt).coerceAtLeast(0L)) >= 45 && messageBetweenPurchases -> "returned"
            daysInactive >= 45 -> "winback"
            daysInactive <= 30 && previousSpend > 0.0 && recentSpend < previousSpend * 0.70 -> "declining"
            ordered.size >= 4 && daysInactive <= 21 -> "loyal"
            ordered.size <= 2 && TimeUnit.MILLISECONDS.toDays((latestAt - transactionTimestamp(ordered.first())).coerceAtLeast(0L)) <= 30 -> "new"
            else -> return@mapNotNull null
        }
        SmsCustomerInsight(
            phone = phone,
            name = latest.clientName.takeIf { it.isNotBlank() } ?: contactNames[phone].orEmpty().ifBlank { "Customer" },
            purchases = ordered.size,
            recentSpend = recentSpend,
            previousSpend = previousSpend,
            lastPurchaseAt = latestAt,
            daysInactive = daysInactive,
            segment = segment,
            consentEligible = SmsNotificationPreferences.consented(context, phone) && !SmsNotificationPreferences.optedOut(context, phone)
        )
    }

    val definitions = listOf(
        SmsInsightSegment("returned", "Returned after a message", "Customers who purchased again after a long gap, with an accepted SMS recorded between their purchases. This is a timing signal, not proof the SMS caused the return.", "Welcome back! We are glad to serve you again. Take a look at the latest offers whenever you are ready.", customers.filter { it.segment == "returned" }),
        SmsInsightSegment("winback", "Inactive customers", "No successful purchase in at least 45 days. A gentle check-in or relevant offer may help restart the relationship.", "We have missed serving you. There are fresh offers available whenever you are ready to shop again.", customers.filter { it.segment == "winback" }),
        SmsInsightSegment("declining", "Buying less recently", "Successful purchase value in the last 30 days is at least 30% below the previous 30-day period. Consider a relevant, lower-cost option.", "Looking for a good-value option? We have offers that may fit your needs and budget. Reply to learn more.", customers.filter { it.segment == "declining" }),
        SmsInsightSegment("new", "New customers", "Customers with one or two purchases and a first recorded purchase within the last 30 days. A helpful onboarding message can build familiarity.", "Thank you for choosing us. We are here to help you find the right offer whenever you need it.", customers.filter { it.segment == "new" }),
        SmsInsightSegment("loyal", "Consistent customers", "Four or more successful purchases with recent activity. These customers are already consistent; avoid frequent promotional messages and prioritise service or occasional appreciation.", "Thank you for being a valued regular customer. We appreciate your continued support.", customers.filter { it.segment == "loyal" })
    )
    return definitions
}

@Composable
internal fun SmsCustomerInsightsPanel(
    context: Context,
    contacts: List<SavedContact>,
    refreshKey: Int,
    onRefresh: () -> Unit,
    onPrepareCampaign: (SmsInsightSegment) -> Unit
) {
    val transactions = remember(refreshKey) { TransactionStore.load(context, forceReload = true) }
    val segments = remember(contacts.toList(), transactions, refreshKey) {
        buildSmsInsightSegments(context, contacts, transactions)
    }
    val totalCustomers = transactions.asSequence()
        .filter { it.statusEnum == TransactionStatus.SUCCESS }
        .map { smsInsightPhone(it.phoneNumber) }
        .filter { it.isNotBlank() }
        .distinct()
        .count()
    val eligibleCustomers = segments.flatMap { it.eligibleCustomers }.distinctBy { it.phone }.size
    val returnedCount = segments.first { it.key == "returned" }.customers.size
    val attentionCount = segments.filter { it.key == "winback" || it.key == "declining" }.sumOf { it.customers.size }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmsSectionHeading(
            title = "Customer insights",
            subtitle = "Understand customer activity and find useful audiences.",
            accent = C.purple,
            trailing = {
                SmsSecondaryButton(
                    text = "Refresh",
                    onClick = onRefresh,
                    icon = Icons.Outlined.Refresh,
                    minHeight = 40.dp,
                )
            }
        )

        SmsCard(borderColor = C.purple.copy(alpha = .32f), containerColor = C.card) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("CUSTOMER OVERVIEW", color = C.t3, fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold, letterSpacing = .9.sp)
                        Text("Your customer base", color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Surface(color = C.purple.copy(alpha = .13f), shape = RoundedCornerShape(10.dp)) {
                        Text("$totalCustomers analysed", color = C.purple, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                    }
                }
                HorizontalDivider(color = C.border)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(returnedCount.toString(), color = C.green, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Returned", color = C.t2, fontSize = 10.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(attentionCount.toString(), color = C.amber, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Need attention", color = C.t2, fontSize = 10.sp)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(eligibleCustomers.toString(), color = C.purple, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text("SMS eligible", color = C.t2, fontSize = 10.sp)
                    }
                }
                Text(
                    "Based on successful purchases saved on this device. Only customers with recorded consent and no opt-out can be added to campaigns.",
                    color = C.t3, fontSize = 10.sp, lineHeight = 15.sp
                )
            }
        }

        val populatedSegments = segments.filter { it.customers.isNotEmpty() }
        if (populatedSegments.isNotEmpty()) {
            SmsSectionHeading(
                title = "Customer segments",
                subtitle = "${populatedSegments.size} active audience(s)",
                accent = C.amber,
            )
        }

        populatedSegments.forEach { segment ->
            val segAccent = when (segment.key) {
                "returned" -> C.green
                "winback" -> C.amber
                "declining" -> C.red
                "new" -> C.blue
                else -> C.purple
            }
            SmsCard(borderColor = segAccent.copy(alpha = 0.32f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(36.dp)
                            .background(segAccent, RoundedCornerShape(2.dp))
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(segment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = C.t1)
                        Text(segment.description, style = MaterialTheme.typography.bodySmall, color = C.t2, lineHeight = 17.sp)
                    }
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = segAccent.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, segAccent.copy(alpha = .32f))
                    ) {
                        Text(
                            segment.customers.size.toString(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            color = segAccent,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }

                segment.customers.take(4).forEachIndexed { index, customer ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = RoundedCornerShape(11.dp),
                            color = segAccent.copy(alpha = .13f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    customer.name.split(" ").filter { it.isNotBlank() }.take(2)
                                        .joinToString("") { it.first().uppercase() }.ifBlank { "•" },
                                    color = segAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(customer.name, color = C.t1, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${customer.purchases} purchases · ${customer.daysInactive}d since last",
                                color = C.t3, fontSize = 9.sp, maxLines = 1
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("KES ${String.format(Locale.getDefault(), "%,.0f", customer.recentSpend)}",
                                color = C.t2, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (customer.consentEligible) "SMS eligible" else "No consent",
                                color = if (customer.consentEligible) C.green else C.t3, fontSize = 8.sp)
                        }
                    }
                    if (index < minOf(segment.customers.size, 4) - 1) {
                        HorizontalDivider(color = C.border.copy(alpha = .55f))
                    }
                }
                if (segment.customers.size > 4) {
                    Text("+ ${segment.customers.size - 4} more customers", color = C.t3, fontSize = 9.sp)
                }
                HorizontalDivider(color = C.border.copy(alpha = .7f))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${segment.eligibleCustomers.size} campaign eligible", color = C.green, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text("Consent recorded · not opted out", color = C.t3, style = MaterialTheme.typography.labelSmall)
                    }
                }
                SmsPrimaryButton(
                    text = if (segment.key == "loyal") "Create appreciation group" else "Create group & prepare",
                    onClick = { onPrepareCampaign(segment) },
                    enabled = segment.eligibleCustomers.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.GroupAdd,
                    minHeight = 48.dp,
                )
                if (segment.eligibleCustomers.isEmpty()) {
                    Text("No campaign can be prepared yet because this segment has no customers with recorded SMS consent.", style = MaterialTheme.typography.labelSmall, color = C.t3)
                }
            }
        }

        if (totalCustomers == 0) {
            SmsInsightsEmptyState(
                "Not enough customer activity yet",
                "As successful purchases are recorded against customer phone numbers, this area will identify useful campaign audiences."
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
@Composable
private fun SmsInsightsEmptyState(title: String, description: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = C.cardMuted,
        border = BorderStroke(1.dp, C.border)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = C.t1, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(description, color = C.t2, style = MaterialTheme.typography.bodySmall)
        }
    }
}
