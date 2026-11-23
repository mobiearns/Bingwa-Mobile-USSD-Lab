package com.bingwa.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ====================================================================== */
/*  1. UI model                                                           */
/*  A DISPLAY model only. Build it from your real saved-offer model with  */
/*  a mapper; never store it and never fill it with sample data.          */
/* ====================================================================== */

enum class FlagTone { Good, Pending, Neutral }

data class OfferFlagUi(val label: String, val tone: FlagTone)

data class OfferCardUi(
    val id: String,
    val name: String,
    val isLive: Boolean,          // false => the whole card is greyed out
    val typeIcon: ImageVector,    // icon for the bundle type
    val tags: List<String>,       // e.g. bundle type, device, SIM label
    val price: String,            // already formatted, e.g. "KES 5"
    val mode: String,
    val device: String,
    val protection: String,
    val ussdCode: String,
    val ussdStatus: String,       // e.g. "Ready"
    val ussdStatusTone: FlagTone,
    val hint: String?,
    val flags: List<OfferFlagUi>,
)

/*
 * Mapper skeleton: bind every value to your REAL offer fields.
 *
 * fun YourOfferModel.toCardUi() = OfferCardUi(
 *     id = ...,          // real id
 *     name = ...,        // real name
 *     isLive = ...,      // real active / inactive state
 *     ...
 * )
 *
 * The list number is NOT stored. The screen shows index + 1 of the visible list.
 */

/* ====================================================================== */
/*  2. Design tokens                                                      */
/* ====================================================================== */

private object Ui {
    // The offer screen uses the same app-wide semantic palette as every other screen.
    val page get() = C.bg
    val card get() = C.card
    val cardOff get() = C.surface
    val panel get() = C.bg
    val panelOff get() = C.surface
    val chip get() = C.cardHi
    val chipOff get() = C.surface
    val tile get() = C.cardHi
    val tileOff get() = C.surface
    val codeBox get() = C.surface
    val codeBoxOff get() = C.bg
    val pill get() = C.cardHi
    val edge get() = C.borderHi
    val edgeOff get() = C.border
    val divider get() = C.border
    val text get() = C.t1
    val textOff get() = C.t3
    val label get() = C.t2
    val labelOff get() = C.t3
    val soft get() = C.t2
    val amber get() = C.amber
    val cyan get() = C.cyan
    val dotOff get() = C.t3
    val danger get() = C.red
    val menu get() = C.cardHi
    val chipSel get() = C.cardHi
    val chipBorder get() = C.border
}

private fun FlagTone.color(): Color = when (this) {
    FlagTone.Good -> C.green
    FlagTone.Pending -> Ui.amber
    FlagTone.Neutral -> Ui.dotOff
}

/* ====================================================================== */
/*  3. Screen (stateless)                                                 */
/* ====================================================================== */

/**
 * Whole "Bundle Offers" content: top bar, type filter, list, empty state.
 *
 * Stateless on purpose: your ViewModel / existing logic decides which
 * offers are visible and handles every callback.
 *
 * @param offers        already filtered by [selectedFilter]
 * @param filterOptions "All offers" plus the types found in your real offers
 * @param emptyMessage  e.g. "No Calls offers"
 */
@Composable
fun BundleOffersScreen(
    offers: List<OfferCardUi>,
    filterOptions: List<String>,
    selectedFilter: String,
    onFilterSelect: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (offerId: String) -> Unit,
    onToggleEnabled: (offerId: String) -> Unit,
    onDelete: (offerId: String) -> Unit,
    emptyMessage: String,
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
    savedCount: Int = offers.size,
    activeCount: Int = offers.count { it.isLive },
    modifier: Modifier = Modifier,
    title: String = "Offers",
) {
    Column(modifier.fillMaxSize().background(Ui.page)) {
        TopBar(title, onBack, onRefresh, onAdd)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiDimens.ScreenHorizontal, vertical = UiDimens.SpacingSm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountText("$savedCount SAVED")
            CountText("$activeCount ACTIVE")
        }
        OfferSearchField(searchQuery, onSearchChange)
        OfferTypeFilterRow(filterOptions, selectedFilter, onFilterSelect)

        if (offers.isEmpty()) {
            EmptyState(emptyMessage)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = UiDimens.ScreenHorizontal, end = UiDimens.ScreenHorizontal, top = UiDimens.SpacingSm, bottom = UiDimens.SpacingXl),
                verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing),
            ) {
                itemsIndexed(offers, key = { _, o -> o.id }) { index, offer ->
                    BundleOfferCard(
                        offer = offer,
                        number = index + 1,
                        onEdit = { onEdit(offer.id) },
                        onToggleEnabled = { onToggleEnabled(offer.id) },
                        onDelete = { onDelete(offer.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit) {
    // Keep the page title on the true horizontal centreline. The trailing
    // actions share one edge group so they never push the title off-centre.
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = UiDimens.ScreenHorizontal, vertical = UiDimens.SpacingXs)
            .heightIn(min = UiDimens.IconTouchTarget),
    ) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
            color = Ui.text,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Clip,
        )
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart).size(UiDimens.IconTouchTarget),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ui.soft)
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.size(UiDimens.IconTouchTarget),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ui.soft)
            }
            IconButton(
                onClick = onAdd,
                modifier = Modifier.size(UiDimens.IconTouchTarget),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add offer", tint = Ui.amber)
            }
        }
    }
}

@Composable
private fun OfferSearchField(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = UiDimens.ScreenHorizontal, vertical = UiDimens.SpacingSm)
            .clip(RoundedCornerShape(UiDimens.CardRadius))
            .background(Ui.panel)
            .border(1.dp, Ui.edge, RoundedCornerShape(UiDimens.CardRadius))
            .padding(horizontal = UiDimens.CardPadding, vertical = UiDimens.SpacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = Ui.label, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isBlank()) {
                Text("Search name, network or type", color = Ui.label, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Ui.text, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ui.cyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotBlank()) {
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(UiDimens.IconTouchTarget),
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Ui.soft, modifier = Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun CountText(text: String) {
    Text(text, color = Ui.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp)
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Ui.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Ui.label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(message, color = Ui.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Tap + to add one.", color = Ui.label, fontSize = 14.sp)
    }
}

/* ====================================================================== */
/*  4. Type filter row  ("All offers · Data · Calls · SMS")               */
/* ====================================================================== */

@Composable
fun OfferTypeFilterRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = UiDimens.ScreenHorizontal, vertical = UiDimens.SpacingSm),
        horizontalArrangement = Arrangement.spacedBy(UiDimens.SpacingSm),
    ) {
        items(options, key = { it }) { option ->
            val isSelected = option == selected
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .heightIn(min = UiDimens.ControlMinHeight)
                    .clip(shape)
                    .background(if (isSelected) Ui.chipSel else Color.Transparent)
                    .border(1.dp, if (isSelected) Ui.chipSel else Ui.chipBorder, shape)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    color = if (isSelected) Ui.text else Ui.soft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/* ====================================================================== */
/*  5. Offer card                                                         */
/* ====================================================================== */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BundleOfferCard(
    offer: OfferCardUi,
    number: Int,                  // 1-based position in the visible list
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = offer.isLive
    val spec = tween<Color>(durationMillis = 250)

    // Every colour animates between "live" and "greyed out". No alpha is used,
    // so the action menu always stays fully opaque.
    val surface by animateColorAsState(if (on) Ui.card else Ui.cardOff, spec, label = "surface")
    val panel by animateColorAsState(if (on) Ui.panel else Ui.panelOff, spec, label = "panel")
    val chip by animateColorAsState(if (on) Ui.chip else Ui.chipOff, spec, label = "chip")
    val text by animateColorAsState(if (on) Ui.text else Ui.textOff, spec, label = "text")
    val label by animateColorAsState(if (on) Ui.label else Ui.labelOff, spec, label = "label")
    val code by animateColorAsState(if (on) Ui.codeBox else Ui.codeBoxOff, spec, label = "code")

    var ussdOpen by rememberSaveable(offer.id) { mutableStateOf(false) } // collapsed by default
    val chevron by animateFloatAsState(if (ussdOpen) 0f else -90f, label = "chevron")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (on) "Live" else "Disabled" },
        shape = RoundedCornerShape(UiDimens.CardRadius),
        color = surface,
        border = BorderStroke(1.25.dp, if (on) C.borderHi else C.border),
        shadowElevation = if (on) 9.dp else 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(UiDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing),
        ) {
            // 1. Header: status (left) · number (centre) · menu (right)
            CardHeader(number, on, label, onEdit, onToggleEnabled, onDelete)

            Box(Modifier.padding(end = 8.dp).fillMaxWidth().height(1.dp).background(Ui.divider))

            // 2. Identity: icon + name
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Ui.tile else Ui.tileOff),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        offer.typeIcon,
                        contentDescription = null,
                        tint = if (on) Ui.soft else Ui.labelOff,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = offer.name,
                    modifier = Modifier.weight(1f),
                    color = text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                )
            }

            // 3. Labels: tags, then status flags
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    offer.tags.forEach { tag ->
                        Text(
                            text = tag.uppercase(),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(chip)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            color = if (on) Ui.soft else Ui.textOff,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.7.sp,
                        )
                    }
                }
                if (offer.flags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        offer.flags.forEach { flag ->
                            StatusLabel(
                                label = flag.label.uppercase(),
                                dot = if (on) flag.tone.color() else Ui.dotOff,
                                textColor = if (on) Ui.label else Ui.labelOff,
                                size = 10.5f,
                            )
                        }
                    }
                }
            }

            // 4. Data: one panel, four equal cells, nothing highlighted
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(panel)
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Stat("PRICE", offer.price, Modifier.weight(1f), text, label)
                    VDivider()
                    Stat("MODE", offer.mode, Modifier.weight(1f), text, label)
                }
                HDivider()
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Stat("DEVICE", offer.device, Modifier.weight(1f), text, label)
                    VDivider()
                    Stat("PROTECTION", offer.protection, Modifier.weight(1f), text, label)
                }
            }

            // 5. USSD: tap the header to collapse / expand
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(panel),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = if (ussdOpen) "Collapse USSD details" else "Expand USSD details",
                        ) { ussdOpen = !ussdOpen }
                        .heightIn(min = 44.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "USSD MODE",
                        color = label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.9.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusLabel(
                            label = offer.ussdStatus,
                            dot = if (on) offer.ussdStatusTone.color() else Ui.dotOff,
                            textColor = if (on) Ui.soft else Ui.textOff,
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = label,
                            modifier = Modifier.size(20.dp).rotate(chevron),
                        )
                    }
                }

                AnimatedVisibility(visible = ussdOpen) {
                    Column(
                        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Text(
                            text = offer.ussdCode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(code)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            color = if (on) Ui.text else Ui.textOff,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                        offer.hint?.let { hint ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = label,
                                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                                )
                                Text(hint, color = label, fontSize = 12.5.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ====================================================================== */
/*  6. Card pieces                                                        */
/* ====================================================================== */

@Composable
private fun CardHeader(
    number: Int,
    on: Boolean,
    iconTint: Color,
    onEdit: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Keep status and list position together so they cannot collide
        // with the trailing action menu on narrow Android screens.
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Ui.pill)
                .padding(start = 9.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Ui.cyan else Ui.dotOff))
            Spacer(Modifier.width(7.dp))
            Text(
                text = if (on) "LIVE" else "OFF",
                color = if (on) Ui.soft else Ui.textOff,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.9.sp,
            )
        }
        Text(
            text = "#$number",
            modifier = Modifier.semantics { contentDescription = "Offer $number" },
            color = if (on) Ui.amber else Ui.labelOff,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
        )
        Spacer(Modifier.weight(1f))

        // Action menu stays anchored to the trailing edge with a full touch target.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Offer actions", tint = iconTint)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Ui.menu, // Material3 1.2+. On older versions use Modifier.background(Ui.menu)
                shape = RoundedCornerShape(16.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("Edit", color = Ui.text, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = Ui.soft) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            if (on) "Disable" else "Enable",
                            color = Ui.text,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (on) Icons.Default.Clear else Icons.Default.PlayArrow,
                            null,
                            tint = Ui.soft,
                        )
                    },
                    onClick = { menuOpen = false; onToggleEnabled() },
                )
                Box(
                    Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Ui.divider)
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = Ui.danger, fontWeight = FontWeight.SemiBold) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = Ui.danger) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun StatusLabel(
    label: String,
    dot: Color,
    textColor: Color,
    size: Float = 11.5f,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        Text(
            text = label,
            color = textColor,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
        )
    }
}

@Composable
private fun Stat(
    title: String,
    value: String,
    modifier: Modifier,
    valueColor: Color,
    titleColor: Color,
) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
        Text(
            title,
            color = titleColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun VDivider() = Box(Modifier.fillMaxHeight().width(1.dp).background(Ui.divider))

@Composable
private fun HDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Ui.divider))

/* ====================================================================== */
/*  7. Wiring example (keep in your ViewModel / screen, not here)         */
/* ====================================================================== */
/*
val ALL = "All offers"
var selected by rememberSaveable { mutableStateOf(ALL) }
val visible = offers.filter { selected == ALL || it.<yourTypeField> == selected }

BundleOffersScreen(
    offers = visible.map { it.toCardUi() },                      // your mapper
    filterOptions = listOf(ALL) + <types from your real offers>,
    selectedFilter = selected,
    onFilterSelect = { selected = it },
    onBack = { /* your existing back handling */ },
    onRefresh = { /* your existing refresh */ },
    onAdd = { /* your existing add-offer navigation */ },
    onEdit = { id -> /* your existing edit navigation */ },
    onToggleEnabled = { id -> /* your existing enable / disable call */ },
    onDelete = { id -> /* your existing delete flow */ },
    emptyMessage = "No $selected offers",
)
*/