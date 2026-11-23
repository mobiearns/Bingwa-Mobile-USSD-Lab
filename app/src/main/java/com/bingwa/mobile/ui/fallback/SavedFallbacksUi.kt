package com.bingwa.mobile.ui.fallback

import com.bingwa.mobile.C

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow

enum class FallbackTab(val label: String) { AllOffers("All offers"), Saved("Saved fallbacks") }

/** Segmented tab row shown at the top of the Offer Fallbacks screen. */
@Composable
fun FallbackTabs(
    selected: FallbackTab,
    savedCount: Int,
    onSelect: (FallbackTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FallbackTab.values().forEach { tab ->
            val on = tab == selected
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) FbColors.Amber.copy(alpha = 0.15f) else FbColors.Card)
                    .border(1.dp, if (on) FbColors.Amber.copy(alpha = 0.55f) else FbColors.CardHigh, RoundedCornerShape(12.dp))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    tab.label,
                    color = if (on) FbColors.Amber else FbColors.TextSecondary,
                    fontWeight = FontWeight.Medium, fontSize = 14.sp
                )
                if (tab == FallbackTab.Saved && savedCount > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$savedCount", color = C.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(FbColors.Amber)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/** Items for the "Saved fallbacks" tab. Call from inside a LazyColumn. */
fun LazyListScope.savedFallbackItems(
    saved: List<SavedFallbackUi>,
    onEdit: (offerId: String) -> Unit,
    onToggle: (offerId: String, enabled: Boolean) -> Unit,
    onClear: (offerId: String) -> Unit,
    onBrowse: () -> Unit
) {
    val active = saved.count { it.enabled }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Chains", "${saved.size}", FbColors.TextPrimary, Modifier.weight(1f))
            StatTile("Active", "$active", FbColors.Cyan, Modifier.weight(1f))
            StatTile("Paused", "${saved.size - active}", FbColors.Amber, Modifier.weight(1f))
        }
    }
    if (saved.isEmpty()) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FbColors.Card).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("No saved fallbacks yet", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, softWrap = true, overflow = TextOverflow.Clip)
                Text(
                    "Set backups on an offer and your full chains will show up here.",
                    color = FbColors.TextSecondary, fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(onClick = onBrowse, shape = RoundedCornerShape(14.dp)) { Text("Browse offers") }
            }
        }
    } else {
        items(saved, key = { it.primary.id }) { s ->
            SavedFallbackCard(
                saved = s,
                onEdit = { onEdit(s.primary.id) },
                onToggle = { onToggle(s.primary.id, it) },
                onClear = { onClear(s.primary.id) }
            )
        }
    }
}

@Composable
fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(FbColors.Card).border(1.dp, FbColors.CardHigh, RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = FbColors.TextSecondary, fontSize = 11.sp, softWrap = true, overflow = TextOverflow.Clip)
    }
}

/** One full saved chain: primary → backups in order, conditions, and actions. */
@Composable
fun SavedFallbackCard(
    saved: SavedFallbackUi,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by rememberSaveable(saved.primary.id) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FbColors.Card).border(1.dp, FbColors.CardHigh, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(saved.primary.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, softWrap = true, overflow = TextOverflow.Clip)
                Text(
                    if (saved.enabled) "Tries up to ${saved.backups.size + 1} offers in order"
                    else "Paused. Your backups are kept",
                    color = FbColors.TextSecondary, fontSize = 12.sp
                )
            }
            Switch(
                checked = saved.enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = FbColors.Cyan, checkedThumbColor = FbColors.Background)
            )
        }

        Column(Modifier.alpha(if (saved.enabled) 1f else 0.5f)) {
            ChainNode("1st", saved.primary, primary = true)
            saved.backups.forEachIndexed { i, offer ->
                Box(Modifier.padding(start = 20.dp).width(2.dp).height(10.dp).background(FbColors.TextSecondary.copy(alpha = 0.4f)))
                ChainNode("${i + 2}", offer, primary = false)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ConditionChip("Unavailable", saved.triggerOfferUnavailable)
            ConditionChip("Already recommended", saved.triggerAlreadyRecommended)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                if (confirmClear) { confirmClear = false; onClear() } else confirmClear = true
            }) {
                Text(
                    if (confirmClear) "Tap again to clear" else "Clear",
                    color = if (confirmClear) FbColors.Danger else FbColors.TextSecondary
                )
            }
            FilledTonalButton(
                onClick = onEdit,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = FbColors.CardHigh, contentColor = FbColors.Amber),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
    }
}

@Composable
private fun ChainNode(badge: String, offer: OfferUi, primary: Boolean) {
    val accent = if (primary) FbColors.Amber else FbColors.Cyan
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(FbColors.CardHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Text(badge, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(offer.name, Modifier.weight(1f), overflow = TextOverflow.Clip)
        Text(formatKsh(offer.priceKsh), color = FbColors.Amber, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ConditionChip(label: String, on: Boolean) {
    val color = if (on) FbColors.Cyan else FbColors.TextSecondary
    Text(
        label, color = color, fontSize = 11.sp,
        textDecoration = if (on) null else TextDecoration.LineThrough,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

