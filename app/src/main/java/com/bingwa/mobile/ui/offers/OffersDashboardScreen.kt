package com.bingwa.mobile.ui.offers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bingwa.mobile.C
import com.bingwa.mobile.UiDimens
import com.bingwa.mobile.OFFER_CATEGORY_CALLS
import com.bingwa.mobile.OFFER_CATEGORY_SMS
import com.bingwa.mobile.OfferItem
import com.bingwa.mobile.normalizeOfferCategory
import com.bingwa.mobile.offerSimSelectionLabel

private val R8 = RoundedCornerShape(8.dp)
private val R10 = RoundedCornerShape(10.dp)
private val R12 = RoundedCornerShape(12.dp)
private val R16 = RoundedCornerShape(16.dp)

@Composable
fun OffersDashboardScreen(
    offers: List<OfferItem>,
    selectedFilter: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onFilterSelect: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    // Keep category filtering and text search independent so either can be used alone.
    val filterOptions = listOf("All", "Data", "Minutes", "SMS")
    val filterCounts = remember(offers) {
        buildMap {
            put("All", offers.size)
            filterOptions.drop(1).forEach { category ->
                put(category, offers.count { offerCategory(it) == category })
            }
        }
    }
    val visible = remember(offers, searchQuery, selectedFilter) {
        val query = searchQuery.trim()
        offers.filter { offer ->
            val matchesCategory = selectedFilter == "All" || offerCategory(offer) == selectedFilter
            val matchesSearch = query.isBlank() || listOf(
                offer.id.toString(), offer.name, offer.bundleSize, offer.offerType, offer.network,
                offerCategory(offer), offerValidity(offer), offer.price.toString(), offer.ussdCode,
                offer.targetDevice, offer.executionMode, offerSimSelectionLabel(offer.simSelection),
                if (offer.pendingLearnedSignature.isNotEmpty()) "pending review" else "",
                if (offer.learnedSignature.isNotEmpty()) "signature learned" else ""
            ).any { it.contains(query, ignoreCase = true) }
            matchesCategory && matchesSearch
        }
    }
    var menuId by remember { mutableStateOf<Int?>(null) }
    var deleteId by remember { mutableStateOf<Int?>(null) }
    val menuOffer = offers.firstOrNull { it.id == menuId }
    val deleteOffer = offers.firstOrNull { it.id == deleteId }

    LazyColumn(
        modifier.fillMaxSize().background(C.bg),
        contentPadding = PaddingValues(
            start = UiDimens.ScreenHorizontal,
            end = UiDimens.ScreenHorizontal,
            top = UiDimens.ScreenVertical,
            bottom = UiDimens.SpacingXl
        ),
        verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing),
    ) {
        item { Header(onBack, onAdd, onRefresh) }
        item { SearchBar(searchQuery, onSearchChange) }
        item { CompactFilters(filterOptions, selectedFilter, filterCounts, onFilterSelect) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${visible.size} ${if (visible.size == 1) "OFFER" else "OFFERS"}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = .8.sp,
                    fontWeight = FontWeight.Bold,
                    color = C.t2,)
                Text(
                    "${visible.count { it.enabled }} ACTIVE",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = .8.sp,
                    fontWeight = FontWeight.Bold,
                    color = C.amber,)
            }
        }
        if (visible.isEmpty()) {
            item {
                EmptyPanel(
                    if (offers.isEmpty()) "No offers yet" else "No matching offers",
                    if (offers.isEmpty()) "Add your first bundle offer to get started." else "Try another search term or clear the selected filters.",
                    if (offers.isEmpty()) "+ Add Offer" else "Clear filters",
                    if (offers.isEmpty()) onAdd else { { onSearchChange(""); onFilterSelect("All") } }
                )
            }
        } else {
            itemsIndexed(visible, key = { _, offer -> offer.id }) { index, offer ->
                OfferCard(offer, number = index + 1) { menuId = offer.id }
            }
        }
    }

    menuOffer?.let { offer ->
        ActionSheet(
            offer,
            onDismiss = { menuId = null },
            onEdit = { menuId = null; onEdit(offer.id) },
            onToggle = { menuId = null; onToggleEnabled(offer.id) },
            onDelete = { menuId = null; deleteId = offer.id }
        )
    }
    deleteOffer?.let { offer ->
        DialogPanel(onDismiss = { deleteId = null }) {
            Text("Delete offer?", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = C.t1)
            Spacer(Modifier.height(6.dp))
            Text("\u201c" + offer.name + "\u201d (" + offer.id + ") will be permanently removed.", fontSize = 13.sp, color = C.t2)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Keep", Modifier.weight(1f)) { deleteId = null }
                ActionButton("Delete", Modifier.weight(1f), C.red) { deleteId = null; onDelete(offer.id) }
            }
        }
    }
}

private fun offerCategory(offer: OfferItem): String = when (normalizeOfferCategory(offer.category)) {
    OFFER_CATEGORY_CALLS -> "Minutes"
    OFFER_CATEGORY_SMS -> "SMS"
    else -> "Data"
}

private fun offerValidity(offer: OfferItem): String {
    val unit = when (offer.validityUnit.uppercase()) {
        "HOURS" -> "Hours"
        "DAYS" -> "Days"
        "WEEKS" -> "Weeks"
        "MONTHS" -> "Months"
        else -> offer.validityUnit.lowercase().replaceFirstChar { it.uppercase() }
    }
    return offer.validityNumber + " " + unit
}

@Composable
private fun Header(onBack: () -> Unit, onAdd: () -> Unit, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Your Offers",
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 92.dp),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 25.sp,
            letterSpacing = (-.35).sp,
            color = C.t1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Clip
        )
        HeaderIcon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            "Back",
            C.t2,
            onBack,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderIcon(Icons.Rounded.Refresh, "Refresh offer catalog", C.t2, onRefresh)
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(C.amber)
                    .border(1.dp, C.amber.copy(alpha = .72f), RoundedCornerShape(UiDimens.ButtonRadius))
                    .clickable(role = Role.Button, onClick = onAdd),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Add, "Add offer", tint = C.bg, modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier.size(UiDimens.IconTouchTarget).clip(RoundedCornerShape(UiDimens.ButtonRadius)).background(C.cardHi)
            .border(1.dp, C.border, R10)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SearchBar(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = UiDimens.ControlMinHeight).clip(RoundedCornerShape(UiDimens.FieldRadius))
            .background(C.surface)
            .border(1.dp, if (focused) C.cyan.copy(alpha = .75f) else C.border, R12)
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = C.t2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search name, network or price…", fontSize = 12.sp, color = C.t3,)
            BasicTextField(value, onChange, singleLine = true, cursorBrush = SolidColor(C.cyan),
                textStyle = TextStyle(color = C.t1, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        }
        if (value.isNotEmpty()) Box(Modifier.size(40.dp).clip(R10).clickable(role = Role.Button, onClick = { onChange("") }), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, "Clear search", tint = C.t2, modifier = Modifier.size(17.dp)) }
    }
}

@Composable
private fun CompactFilters(
    options: List<String>,
    selected: String,
    counts: Map<String, Int>,
    onSelect: (String) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 5.dp
        val horizontalInset = 8.dp
        val availableWidth = (maxWidth - horizontalInset).coerceAtLeast(0.dp)
        // Four standard filters share the available width. Additional filters
        // retain a readable minimum width and become horizontally scrollable.
        val tabWidth = ((availableWidth - gap * (options.size - 1)) / options.size)
            .coerceAtLeast(76.dp)
        val rowWidth = tabWidth * options.size + gap * (options.size - 1)
        val scrollState = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(UiDimens.CardRadius))
                .background(C.surface)
                .border(1.dp, C.border.copy(alpha = .85f), RoundedCornerShape(UiDimens.CardRadius))
                .padding(4.dp)
                .horizontalScroll(scrollState)
        ) {
            Row(
                modifier = Modifier.width(rowWidth),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEach { option ->
                    val chosen = option == selected
                    Row(
                        modifier = Modifier
                            .width(tabWidth)
                            .height(36.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (chosen) C.amberDim else Color.Transparent)
                            .then(
                                if (chosen) Modifier.border(
                                    1.dp,
                                    C.amber.copy(alpha = .48f),
                                    RoundedCornerShape(11.dp)
                                ) else Modifier
                            )
                            .selectable(selected = chosen, role = Role.Tab) { onSelect(option) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            option,
                            fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 11.sp,
                            color = if (chosen) C.amber else C.t2,
                            softWrap = true
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            (counts[option] ?: 0).toString(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (chosen) C.amber.copy(alpha = .82f) else C.t3,
                            softWrap = true
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferCard(offer: OfferItem, number: Int, onMore: () -> Unit) {
    val cardShape = RoundedCornerShape(UiDimens.CardRadius)
    val signatureStatus = when {
        offer.pendingLearnedSignature.isNotEmpty() || offer.pendingSignatureLearningCaptures.isNotEmpty() -> "Review pending"
        offer.learnedSignature.isNotEmpty() || offer.signatureLearningCaptures.isNotEmpty() -> "Learned"
        offer.signatureDetectionEnabled -> "Enabled · not learned"
        else -> "Not configured"
    }
    val executionMode = if (offer.executionMode.equals("ADVANCED", ignoreCase = true)) "Advanced" else "Simple"

    Column(
        Modifier.fillMaxWidth().clip(cardShape)
            .background(Brush.linearGradient(listOf(C.cardHi, C.card, C.bg)))
            .border(1.dp, C.border.copy(alpha = .9f), cardShape)
            .drawBehind {
                drawRect(
                    color = if (offer.enabled) C.amber.copy(alpha = .82f) else C.borderHi.copy(alpha = .65f),
                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)
                )
            }
             .padding(UiDimens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(Modifier.size(29.dp).clip(R10).background(C.amberDim)
                .border(1.dp, C.amber.copy(alpha = .35f), R10), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = C.amber, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(offer.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp,
                    color = C.t1, softWrap = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(offer.network.takeIf { it.isNotBlank() }, "ID ${offer.id}").joinToString(" · "),
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = C.t3, overflow = TextOverflow.Clip
                )
            }
            StatusPill(offer.enabled)
            Box(Modifier.size(40.dp).clip(R10).background(C.surface)
                .border(1.dp, C.border, R10).clickable(role = Role.Button, onClick = onMore),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MoreHoriz, "Offer actions", tint = C.t1, modifier = Modifier.size(19.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ValuePanel("PRICE", "KSh ${offer.price}", C.amber, Modifier.weight(1f))
            ValuePanel("BUNDLE", offer.bundleSize.ifBlank { "—" }, C.green, Modifier.weight(1f))
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Detail("OFFER TYPE", offer.offerType.ifBlank { "—" }, C.t1, Modifier.weight(1f))
                Detail("SIGNATURE", signatureStatus, if (signatureStatus == "Learned") C.green else C.t1, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.border.copy(alpha = .55f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Detail("EXECUTION SIM", offerSimSelectionLabel(offer.simSelection), C.t1, Modifier.weight(1f))
                Detail("EXECUTION MODE", executionMode, C.t1, Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.border.copy(alpha = .55f)))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("USSD CODE", fontFamily = FontFamily.Monospace, fontSize = 9.sp,
                    letterSpacing = .6.sp, fontWeight = FontWeight.SemiBold, color = C.t3)
                Text(
                    offer.ussdCode.ifBlank { "Not set" },
                    modifier = Modifier.fillMaxWidth().clip(R10).background(C.surface)
                        .border(1.dp, C.border.copy(alpha = .7f), R10)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    color = C.t1, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, softWrap = true, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusPill(active: Boolean) {
    val color = if (active) C.green else C.orange
    Text(if (active) "ACTIVE" else "PAUSED", color = color, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, letterSpacing = .7.sp,
        modifier = Modifier.clip(CircleShape).background(if (active) C.greenDim else C.orangeDim)
            .border(1.dp, color.copy(alpha = .65f), CircleShape).padding(horizontal = 10.dp, vertical = 7.dp))
}

@Composable
private fun ValuePanel(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier.heightIn(min = 68.dp).clip(shape).background(C.surface)
            .border(1.dp, C.border, shape)
            .drawBehind {
                drawRect(color = accent.copy(alpha = .85f), size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
            }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            letterSpacing = .75.sp, fontWeight = FontWeight.Bold, color = C.t3,)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp,
            color = accent, maxLines = 2, softWrap = true, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Detail(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            letterSpacing = .55.sp,
            fontWeight = FontWeight.SemiBold,
            color = C.t3,
        )
        Text(
            value,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = color,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun EmptyPanel(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = UiDimens.SpacingSm).clip(RoundedCornerShape(UiDimens.CardRadius))
            .background(C.card).border(1.dp, C.borderHi, RoundedCornerShape(UiDimens.CardRadius))
            .padding(horizontal = 20.dp, vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(19.dp)).background(C.cyanDim), contentAlignment = Alignment.Center) {
            Text("＋", fontSize = 30.sp, color = C.cyan, fontWeight = FontWeight.Bold)
        }
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = C.t1)
        Text(message, fontSize = 13.sp, color = C.t2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(7.dp))
        ActionButton(action, Modifier.padding(horizontal = 6.dp), C.amber, onAction)
    }
}

@Composable
private fun ActionSheet(offer: OfferItem, onDismiss: () -> Unit, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    DialogPanel(onDismiss) {
        Text(offer.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = C.t1)
        Spacer(Modifier.height(10.dp))
        SheetButton("Edit offer", C.t1, onEdit)
        SheetButton(if (offer.enabled) "Disable offer" else "Enable offer", C.t1, onToggle)
        SheetButton("Delete…", C.red, onDelete)
    }
}

@Composable
private fun SheetButton(text: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = UiDimens.SpacingSm).heightIn(min = UiDimens.ControlMinHeight).clip(RoundedCornerShape(UiDimens.ButtonRadius)).background(C.cardHi)
        .border(1.dp, if (color == C.red) C.red.copy(alpha = .27f) else C.border, RoundedCornerShape(UiDimens.ButtonRadius))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun DialogPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.clip(RoundedCornerShape(UiDimens.DialogRadius)).background(C.card).border(1.dp, C.border, RoundedCornerShape(UiDimens.DialogRadius)).padding(UiDimens.DialogPadding), content = content)
    }
}

@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, color: Color = C.t1, onClick: () -> Unit) {
    val primary = color == C.amber
    Box(modifier.heightIn(min = UiDimens.ControlMinHeight).clip(RoundedCornerShape(UiDimens.ButtonRadius)).background(if (primary) C.amber else C.cardHi)
        .border(1.dp, if (primary) C.amber else if (color == C.red) C.red.copy(alpha = .27f) else C.border, RoundedCornerShape(UiDimens.ButtonRadius))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (primary) C.bg else color,)
    }
}
