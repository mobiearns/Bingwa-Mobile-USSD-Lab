package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PeopleAlt
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun OfferPerformanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var period by remember { mutableStateOf(OfferPurchaseAnalytics.period(context)) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    var showAutoClear by remember { mutableStateOf(false) }
    var chosenPeriod by remember { mutableStateOf(period) }

    val catalog = remember(refresh) { OfferRepository.load(context) }
    val snapshot = remember(refresh, catalog) { OfferPurchaseAnalytics.snapshot(context, catalog) }
    val topOffer = snapshot.offers.firstOrNull { it.purchases > 0 }
    val leastOffer = snapshot.offers.minWithOrNull(
        compareBy<OfferPurchaseAnalytics.OfferStats> { it.purchases }
            .thenBy { it.revenue }
            .thenBy { it.name }
    )
    val maxOfferPurchases = (snapshot.offers.maxOfOrNull { it.purchases } ?: 0).coerceAtLeast(1)
    val hasSales = snapshot.totalPurchases > 0

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Box(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(
                horizontal = UiDimens.ScreenHorizontal,
                vertical = UiDimens.SpacingSm
            ).heightIn(min = UiDimens.ControlMinHeight)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 54.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Sales Insights", color = C.t1, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, overflow = TextOverflow.Clip)
                Text(
                    "Understand what drives your sales",
                    color = C.t3,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    overflow = TextOverflow.Clip
                )
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart).size(42.dp)
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = C.t1)
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Sales insight settings", tint = C.t1)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Data retention: ${period.label}") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                        onClick = {
                            chosenPeriod = period
                            showAutoClear = true
                            menuExpanded = false
                        }
                    )
                    HorizontalDivider(color = C.border)
                    DropdownMenuItem(
                        text = { Text("Clear sales insights", color = C.red) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null, tint = C.red) },
                        onClick = { menuExpanded = false; showClear = true }
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UiDimens.ScreenHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Revenue-led summary: one focal panel, with the supporting measures kept inline.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = C.cardHi,
                border = BorderStroke(1.dp, C.borderStrong)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("TOTAL REVENUE", color = C.t2, fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            Text("KSh ${money(snapshot.totalRevenue)}", color = C.t1,
                                fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold)
                            Text(
                                if (hasSales) "From successful purchases recorded on this device"
                                else "Your sales performance will appear here as purchases are recorded",
                                color = C.t3, fontSize = 10.sp, lineHeight = 14.sp
                            )
                        }
                        Surface(shape = RoundedCornerShape(14.dp), color = C.amber.copy(alpha = .13f)) {
                            Icon(Icons.Rounded.TrendingUp, null, tint = C.amber,
                                modifier = Modifier.padding(11.dp).size(22.dp))
                        }
                    }
                    HorizontalDivider(color = C.border)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InlineSalesMetric("PURCHASES", snapshot.totalPurchases.toString(), Modifier.weight(1f))
                        InlineSalesMetric("UNIQUE BUYERS", snapshot.uniqueCustomers.toString(), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InlineSalesMetric("ACTIVE OFFERS", catalog.size.toString(), Modifier.weight(1f))
                        InlineSalesMetric(
                            "AVERAGE SALE",
                            "KSh ${money(if (snapshot.totalPurchases > 0) snapshot.totalRevenue / snapshot.totalPurchases else 0.0)}",
                            Modifier.weight(1f)
                        )
                    }
                }
            }

            // One compact performance summary instead of two separate highlight cards.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = C.card,
                border = BorderStroke(1.dp, C.border.copy(alpha = .8f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("OFFER PERFORMANCE", color = C.t3, fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.TrendingUp, null, tint = C.green, modifier = Modifier.size(14.dp))
                                Text("TOP SELLER", color = C.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(topOffer?.name ?: "No sales yet", color = C.t1, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(topOffer?.let { "${it.purchases} purchases · KSh ${money(it.revenue)}" }
                                ?: "Your strongest offer will appear here", color = C.t3, fontSize = 10.sp)
                        }
                        Box(Modifier.width(1.dp).heightIn(min = 54.dp).background(C.border))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(Icons.Rounded.TrendingDown, null, tint = C.amber, modifier = Modifier.size(14.dp))
                                Text("REVIEW", color = C.amber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(leastOffer?.name ?: "No offers yet", color = C.t1, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(leastOffer?.let {
                                if (it.purchases == 0) "No purchases recorded" else "${it.purchases} purchases recorded"
                            } ?: "Add offers to compare", color = C.t3, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Day and time patterns share one analysis surface for easier scanning.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = C.card,
                border = BorderStroke(1.dp, C.border.copy(alpha = .8f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(9.dp), color = C.amber.copy(alpha = .12f)) {
                            Icon(Icons.Rounded.CalendarMonth, null, tint = C.amber,
                                modifier = Modifier.padding(7.dp).size(16.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("BUYING PATTERNS", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("When customers usually make purchases", color = C.t3, fontSize = 10.sp)
                        }
                    }
                    Text("SALES BY DAY", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                    WeeklySalesChart(snapshot.dayCounts)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PeakInfo("BUSIEST DAY", snapshot.busiestDay ?: "—",
                            "${snapshot.busiestDayPurchases} purchases", Modifier.weight(1f))
                        Box(Modifier.width(1.dp).heightIn(min = 38.dp).background(C.border))
                        PeakInfo("QUIETEST DAY", snapshot.quietestDay ?: "—",
                            "Among days with sales", Modifier.weight(1f))
                    }
                    HorizontalDivider(color = C.border)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SALES BY HOUR", color = C.t3, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = .6.sp)
                            Text("Purchase activity throughout the day", color = C.t2, fontSize = 10.sp)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(snapshot.busiestHour ?: "—", color = C.cyan, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text("PEAK HOUR", color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    HourlySalesChart(snapshot.hourCounts)
                    Text(
                        if (snapshot.busiestHour != null) "${snapshot.busiestHourPurchases} successful purchases during the busiest hour"
                        else "Purchase-time patterns will appear after successful sales are recorded.",
                        color = C.t3, fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
            }

            // Ranked list uses a single surface and lightweight separators, not nested cards.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = C.card,
                border = BorderStroke(1.dp, C.border.copy(alpha = .8f))
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("OFFER SALES", color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("Ranked by successful purchases", color = C.t3, fontSize = 10.sp)
                        }
                        Text("${snapshot.offers.size} offers", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (snapshot.offers.isEmpty()) {
                        EmptySalesState(
                            "Nothing to analyse yet",
                            "Add offers to your catalogue. Successful purchases will build your performance report here."
                        )
                    } else {
                        snapshot.offers.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(color = C.border.copy(alpha = .65f))
                            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Surface(shape = RoundedCornerShape(8.dp),
                                        color = if (index == 0 && item.purchases > 0) C.amber.copy(alpha = .15f) else C.bg) {
                                        Text("${index + 1}", color = if (index == 0 && item.purchases > 0) C.amber else C.t3,
                                            fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.width(27.dp).padding(vertical = 6.dp),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.name, color = C.t1, fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${item.clients} buyers · KSh ${money(item.revenue)}",
                                            color = C.t3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(item.purchases.toString(), color = if (item.purchases > 0) C.amber else C.t2,
                                            fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                                        Text("SALES", color = C.t3, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Box(Modifier.fillMaxWidth().padding(start = 36.dp).height(5.dp)
                                    .background(C.bg, RoundedCornerShape(50))) {
                                    Box(Modifier.fillMaxWidth((item.purchases.toFloat() / maxOfferPurchases).coerceIn(0f, 1f))
                                        .height(5.dp).background(
                                            if (index == 0 && item.purchases > 0) C.amber else C.cyan.copy(alpha = .72f),
                                            RoundedCornerShape(50)))
                                }
                            }
                        }
                    }
                }
            }

            Text(
                "Insights use successful transactions linked to saved offers. Buyer counts use available customer names or phone numbers. Times follow this phone's local time.",
                color = C.t3, fontSize = 10.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAutoClear) AlertDialog(
        onDismissRequest = { showAutoClear = false },
        title = { Text("Automatic data cleanup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Choose how long sales insights are retained. This never deletes transaction history.",
                    color = C.t2,
                    fontSize = 12.sp
                )
                OfferPurchaseAnalytics.Period.entries.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosenPeriod == option, onClick = { chosenPeriod = option })
                        Text(option.label, color = C.t1, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                OfferPurchaseAnalytics.setPeriod(context, chosenPeriod)
                period = chosenPeriod
                refresh++
                showAutoClear = false
            }) { Text("Save", color = C.amber) }
        },
        dismissButton = { TextButton(onClick = { showAutoClear = false }) { Text("Cancel") } }
    )

    if (showClear) AlertDialog(
        onDismissRequest = { showClear = false },
        title = { Text("Clear sales insights?") },
        text = {
            Text("This permanently clears the locally recorded sales-insight events. Your transaction history and actual transaction records will not be deleted.")
        },
        confirmButton = {
            TextButton(onClick = {
                OfferPurchaseAnalytics.clear(context)
                refresh++
                showClear = false
            }) { Text("Clear insights", color = C.red) }
        },
        dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } }
    )
}

@Composable
private fun InlineSalesMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = C.t3, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            letterSpacing = .35.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String,
    trailing: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    accent: Color = C.amber
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = .12f)) {
                Icon(icon, null, tint = accent, modifier = Modifier.padding(8.dp).size(17.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = C.t3, fontSize = 10.sp, lineHeight = 13.sp)
        }
        if (trailing != null) {
            Text(trailing, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun InsightSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C.card,
        border = BorderStroke(1.dp, C.border.copy(alpha = .8f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
            content = content
        )
    }
}

@Composable
private fun SalesMetricTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(C.bg.copy(alpha = .55f), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, null, tint = C.amber, modifier = Modifier.size(13.dp))
            Text(
                label,
                color = C.t3,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = .35.sp,
                softWrap = true
            )
        }
        Text(value, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, softWrap = true)
    }
}

@Composable
private fun PerformanceHighlight(
    title: String,
    name: String,
    detail: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = C.card,
        border = BorderStroke(1.dp, C.border.copy(alpha = .8f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .45.sp)
            }
            Text(
                name,
                color = C.t1,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.Bold,
                overflow = TextOverflow.Clip,
                minLines = 2
            )
            Text(detail, color = C.t3, fontSize = 10.sp, lineHeight = 13.sp, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun PeakInfo(title: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = .45.sp)
        Text(value, color = C.t1, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,)
        Text(detail, color = C.t3, fontSize = 10.sp, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun WeeklySalesChart(days: List<Pair<String, Int>>) {
    val max = (days.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 116.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        days.forEach { (day, count) ->
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    count.toString(),
                    color = if (count > 0) C.t2 else C.t3,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
                    val h = if (count == 0) 3.dp.toPx() else (size.height * count / max).coerceAtLeast(5.dp.toPx())
                    drawRoundRect(
                        color = if (count == max && count > 0) C.amber else C.amber.copy(alpha = if (count == 0) .13f else .55f),
                        topLeft = Offset(0f, size.height - h),
                        size = Size(size.width, h),
                        cornerRadius = CornerRadius(5.dp.toPx())
                    )
                }
                Text(
                    day,
                    color = C.t3,
                    fontSize = 10.sp,
                    fontWeight = if (count == max && count > 0) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun HourlySalesChart(hours: List<Pair<String, Int>>) {
    val max = (hours.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hours.forEach { (_, count) ->
                val peak = count == max && count > 0
                Canvas(Modifier.weight(1f).fillMaxHeight()) {
                    val barHeight = if (count == 0) 2.dp.toPx() else (size.height * count / max).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(
                        color = if (peak) C.cyan else C.cyan.copy(alpha = if (count == 0) .12f else .48f),
                        topLeft = Offset(0f, size.height - barHeight),
                        size = Size(size.width, barHeight),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "23:00").forEach {
                Text(it, color = C.t3, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun EmptySalesState(title: String, description: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(shape = RoundedCornerShape(14.dp), color = C.amber.copy(alpha = .1f)) {
            Icon(
                Icons.Rounded.TrendingUp,
                null,
                tint = C.amber,
                modifier = Modifier.padding(12.dp).size(22.dp)
            )
        }
        Text(title, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            color = C.t3,
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
}

private fun money(value: Double): String =
    "%,.0f".format(Locale.getDefault(), value)
