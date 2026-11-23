package com.bingwa.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingwa.mobile.ui.fallback.*

private data class FallbackChainConfig(
    val primaryId: Int,
    val backupIds: List<Int>,
    val enabled: Boolean = true,
    val triggerOfferUnavailable: Boolean = true,
    val triggerAlreadyRecommended: Boolean = false
)

@Composable
internal fun OfferFallbacksScreen(
    offers: List<OfferItem>,
    onBack: () -> Unit
) {
    BackHandler(enabled = true) { onBack() }
    val context = LocalContext.current
    val policy = remember(context) { DailyLimitPolicy.load(context) }
    var fallbackEnabled by remember(context) { mutableStateOf(policy.fallbackEnabled) }
    var chains by remember(context, offers) {
        val configuredMappings = if (policy.fallbackMappings.isNotEmpty()) {
            policy.fallbackMappings
        } else {
            val legacyId = policy.legacyFallbackOfferId
            if (legacyId in offers.map { it.id }) {
                offers.filter { it.id != legacyId }.map { DailyLimitFallbackMapping(it.id, listOf(legacyId)) }
            } else emptyList()
        }
        mutableStateOf(configuredMappings.mapNotNull { mapping ->
            val primary = offers.firstOrNull { it.id == mapping.primaryOfferId } ?: return@mapNotNull null
            val backupIds = mapping.fallbackOfferIds.filter { id -> offers.any { it.id == id && it.enabled } }.distinct()
            if (backupIds.isEmpty()) return@mapNotNull null
            FallbackChainConfig(
                primaryId = primary.id,
                backupIds = backupIds,
                enabled = DailyLimitPolicy.isFallbackRouteEnabled(context, primary.id),
                triggerOfferUnavailable = mapping.triggerOfferUnavailable
                    ?: DailyLimitPolicy.ruleIncludesOfferNotFound(policy.fallbackRuleMode),
                triggerAlreadyRecommended = mapping.triggerAlreadyRecommended
                    ?: DailyLimitPolicy.ruleIncludesAlreadyRecommended(policy.fallbackRuleMode)
            )
        })
    }
    var selectedTab by rememberSaveable { mutableStateOf(FallbackTab.AllOffers) }
    var editingPrimaryId by rememberSaveable { mutableIntStateOf(-1) }

    fun save(next: List<FallbackChainConfig>) {
        chains = next
        DailyLimitPolicy.saveFallbackMappings(
            context,
            next.map { chain ->
                DailyLimitFallbackMapping(
                    primaryOfferId = chain.primaryId,
                    fallbackOfferIds = chain.backupIds,
                    triggerOfferUnavailable = chain.triggerOfferUnavailable,
                    triggerAlreadyRecommended = chain.triggerAlreadyRecommended
                )
            }
        )
        next.forEach { chain ->
            DailyLimitPolicy.setFallbackRouteEnabled(context, chain.primaryId, chain.enabled)
        }
    }

    fun uiOffer(offer: OfferItem) = OfferUi(
        id = offer.id.toString(),
        name = offer.name,
        priceKsh = offer.price.toDouble(),
        provider = offer.category,
        validity = offer.executionMode,
        subtitle = offer.ussdCode
    )

    val savedUi = chains.mapNotNull { chain ->
        val primary = offers.firstOrNull { it.id == chain.primaryId } ?: return@mapNotNull null
        SavedFallbackUi(
            primary = uiOffer(primary),
            backups = chain.backupIds.mapNotNull { id -> offers.firstOrNull { it.id == id }?.let(::uiOffer) },
            enabled = chain.enabled,
            triggerOfferUnavailable = chain.triggerOfferUnavailable,
            triggerAlreadyRecommended = chain.triggerAlreadyRecommended
        )
    }

    Column(
        Modifier.fillMaxSize().background(FbColors.Background).statusBarsPadding()
            .padding(horizontal = com.bingwa.mobile.UiDimens.ScreenHorizontal, vertical = com.bingwa.mobile.UiDimens.ScreenVertical),
        verticalArrangement = Arrangement.spacedBy(com.bingwa.mobile.UiDimens.SectionSpacing)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = FbColors.Card,
            border = BorderStroke(1.dp, FbColors.CardHigh)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp).padding(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterStart),
                    shape = RoundedCornerShape(13.dp),
                    color = FbColors.Background,
                    border = BorderStroke(1.dp, FbColors.CardHigh)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = FbColors.TextPrimary)
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "OFFER AUTOMATION",
                        color = FbColors.Amber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        "Offer Fallbacks",
                        color = FbColors.TextPrimary,
                        fontSize = 23.sp,
                        lineHeight = 27.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        softWrap = true
                    )
                    Text(
                        "Smart backup routes for every offer",
                        color = FbColors.TextSecondary,
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,)
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(15.dp),
            color = FbColors.Card,
            border = BorderStroke(1.dp, FbColors.CardHigh)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Enable fallback automation", color = FbColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use saved backup chains when a primary offer hits a configured trigger.", color = FbColors.TextSecondary, fontSize = 11.sp)
                }
                Switch(
                    checked = fallbackEnabled,
                    onCheckedChange = {
                        fallbackEnabled = it
                        context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("daily_limit_fallback_enabled", it).apply()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = FbColors.Background, checkedTrackColor = FbColors.Amber)
                )
            }
        }

        FallbackTabs(selected = selectedTab, savedCount = savedUi.size, onSelect = { selectedTab = it })

        if (selectedTab == FallbackTab.Saved) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                savedFallbackItems(
                    saved = savedUi,
                    onEdit = { id -> editingPrimaryId = id.toIntOrNull() ?: -1 },
                    onToggle = { id, enabled ->
                        val primaryId = id.toIntOrNull() ?: return@savedFallbackItems
                        save(chains.map { if (it.primaryId == primaryId) it.copy(enabled = enabled) else it })
                    },
                    onClear = { id ->
                        val primaryId = id.toIntOrNull() ?: return@savedFallbackItems
                        save(chains.filterNot { it.primaryId == primaryId })
                    },
                    onBrowse = { selectedTab = FallbackTab.AllOffers }
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Available offers", offers.size.toString(), FbColors.TextPrimary, Modifier.weight(1f))
                        StatTile("Configured chains", chains.size.toString(), FbColors.Cyan, Modifier.weight(1f))
                    }
                    Text(
                        "Choose a primary offer, then build an ordered backup route that activates only when your selected conditions are met.",
                        color = FbColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp)
                    )
                }
                items(offers.sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name.lowercase() }.thenBy { it.id }), key = { it.id }) { offer ->
                    val configured = chains.any { it.primaryId == offer.id }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = FbColors.Card,
                        border = BorderStroke(1.dp, FbColors.CardHigh)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val offerNumber = offers.sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name.lowercase() }.thenBy { it.id }).indexOfFirst { it.id == offer.id } + 1
                            Surface(shape = RoundedCornerShape(9.dp), color = FbColors.Cyan.copy(alpha = 0.12f)) {
                                Text("#$offerNumber", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = FbColors.Cyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    offer.name,
                                    color = FbColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                                )
                                Text("KES ${offer.price} · ${offer.category}", color = FbColors.TextSecondary, fontSize = 11.sp,)
                            }
                            TextButton(onClick = { editingPrimaryId = offer.id }) {
                                Text(if (configured) "Edit chain" else "Set backups", color = FbColors.Amber, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (offers.isEmpty()) {
                    item {
                        Text("Add offers to your catalog before configuring fallbacks.", color = FbColors.TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    val primary = offers.firstOrNull { it.id == editingPrimaryId }
    if (primary != null) {
        val existing = chains.firstOrNull { it.primaryId == primary.id }
        var selectedBackups by remember(primary.id, existing) {
            mutableStateOf(existing?.backupIds.orEmpty())
        }
        var unavailable by remember(primary.id, existing) {
            mutableStateOf(existing?.triggerOfferUnavailable ?: true)
        }
        var recommended by remember(primary.id, existing) {
            mutableStateOf(existing?.triggerAlreadyRecommended ?: false)
        }
        var backupSearch by rememberSaveable(primary.id) { mutableStateOf("") }
        val availableBackups = remember(offers, primary.id) {
            offers.filter { it.id != primary.id }
                .sortedWith(compareBy<OfferItem> { it.price }.thenBy { it.name.lowercase() }.thenBy { it.id })
        }
        val visibleBackups = availableBackups.filter {
            backupSearch.isBlank() ||
                it.name.contains(backupSearch, ignoreCase = true) ||
                it.category.contains(backupSearch, ignoreCase = true)
        }
        Dialog(
            onDismissRequest = { editingPrimaryId = -1 },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.98f)
                    .fillMaxHeight(0.99f)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(28.dp),
                color = FbColors.Background,
                border = BorderStroke(1.dp, FbColors.CardHigh),
                tonalElevation = 12.dp
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("BACKUP ROUTE", color = FbColors.Amber, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
                            Text("Choose fallback offers", color = FbColors.TextPrimary, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold,)
                        }
                        IconButton(onClick = { editingPrimaryId = -1 }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close selector", tint = FbColors.TextPrimary)
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = FbColors.Amber.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, FbColors.Amber.copy(alpha = 0.55f))
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(32.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = FbColors.Amber
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("1", color = FbColors.Background, fontSize = 14.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("PRIMARY OFFER", color = FbColors.Amber, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
                                Text(primary.name, color = FbColors.TextPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                Text("KES ${primary.price} · ${primary.category}", color = FbColors.TextSecondary, fontSize = 10.sp, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                            }
                            Surface(shape = RoundedCornerShape(9.dp), color = FbColors.CardHigh) {
                                Text("START", Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = FbColors.TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Surface(shape = RoundedCornerShape(50), color = FbColors.Amber) {
                            Text("2  CHOOSE BACKUPS", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = FbColors.Background, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        Surface(shape = RoundedCornerShape(50), color = FbColors.CardHigh) {
                            Text("3  SET PRIORITY", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = FbColors.TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("${selectedBackups.size} selected", color = FbColors.Amber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = backupSearch,
                        onValueChange = { backupSearch = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        placeholder = { Text("Search offers or categories", fontSize = 12.sp) },
                        leadingIcon = { Text("⌕", color = FbColors.TextSecondary, fontSize = 22.sp) },
                        trailingIcon = {
                            if (backupSearch.isNotEmpty()) {
                                TextButton(onClick = { backupSearch = "" }) { Text("Clear", color = FbColors.Amber, fontSize = 11.sp) }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = FbColors.Amber,
                            unfocusedBorderColor = FbColors.CardHigh,
                            focusedTextColor = FbColors.TextPrimary,
                            unfocusedTextColor = FbColors.TextPrimary,
                            cursorColor = FbColors.Amber
                        )
                    )

                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 3.dp)
                    ) {
                        if (selectedBackups.isNotEmpty()) {
                            item(key = "priority-heading") {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("YOUR BACKUP ORDER", color = FbColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("First tried  →  Last tried", color = FbColors.TextSecondary, fontSize = 10.sp)
                                }
                            }
                            items(selectedBackups, key = { "priority-$it" }) { backupId ->
                                val backup = availableBackups.firstOrNull { it.id == backupId }
                                if (backup != null) {
                                    val index = selectedBackups.indexOf(backupId)
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(14.dp),
                                        color = FbColors.Card,
                                        border = BorderStroke(1.dp, FbColors.Amber.copy(alpha = 0.42f))
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(shape = RoundedCornerShape(9.dp), color = FbColors.Amber) {
                                                Text("${index + 1}", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = FbColors.Background, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                            }
                                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(backup.name, color = FbColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                                Text("KES ${backup.price} · ${backup.category}", color = FbColors.TextSecondary, fontSize = 10.sp,)
                                            }
                                            TextButton(
                                                enabled = index > 0,
                                                onClick = {
                                                    selectedBackups = selectedBackups.toMutableList().also { list ->
                                                        val moved = list.removeAt(index); list.add(index - 1, moved)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                                            ) { Text("↑", color = if (index > 0) FbColors.Amber else FbColors.TextSecondary, fontSize = 17.sp) }
                                            TextButton(
                                                enabled = index < selectedBackups.lastIndex,
                                                onClick = {
                                                    selectedBackups = selectedBackups.toMutableList().also { list ->
                                                        val moved = list.removeAt(index); list.add(index + 1, moved)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)
                                            ) { Text("↓", color = if (index < selectedBackups.lastIndex) FbColors.Amber else FbColors.TextSecondary, fontSize = 17.sp) }
                                            IconButton(onClick = { selectedBackups = selectedBackups - backupId }, modifier = Modifier.size(30.dp)) {
                                                Text("×", color = FbColors.Danger, fontSize = 20.sp)
                                            }
                                        }
                                    }
                                }
                            }
                            item(key = "available-heading") {
                                Text("ADD MORE ALTERNATIVES", color = FbColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp, modifier = Modifier.padding(top = 3.dp, bottom = 0.dp))
                            }
                        } else {
                            item(key = "available-heading") {
                                Text("AVAILABLE OFFERS", color = FbColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                            }
                        }
                        items(visibleBackups, key = { "candidate-${it.id}" }) { backup ->
                            val isSelected = backup.id in selectedBackups
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(13.dp),
                                color = if (isSelected) FbColors.Amber.copy(alpha = 0.09f) else FbColors.Card,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 1.dp,
                                    if (isSelected) FbColors.Amber else FbColors.CardHigh
                                ),
                                onClick = {
                                    selectedBackups = if (isSelected) selectedBackups - backup.id else selectedBackups + backup.id
                                }
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier.size(34.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) FbColors.Amber else FbColors.Background
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(if (isSelected) "✓" else "+", color = if (isSelected) FbColors.Background else FbColors.Amber, fontSize = 20.sp, fontWeight = FontWeight.Black)
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(backup.name, color = FbColors.TextPrimary, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Surface(shape = RoundedCornerShape(7.dp), color = FbColors.CardHigh) {
                                                Text(backup.category, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = FbColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                            }
                                            Text("KES ${backup.price}", color = FbColors.Amber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                    Surface(shape = RoundedCornerShape(9.dp), color = if (isSelected) FbColors.Amber.copy(alpha = 0.17f) else FbColors.CardHigh) {
                                        Text(if (isSelected) "ADDED" else "ADD", Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = if (isSelected) FbColors.Amber else FbColors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
                                    }
                                }
                            }
                        }
                        if (visibleBackups.isEmpty()) {
                            item(key = "empty-search") {
                                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("No matching offers", color = FbColors.TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Try another offer name or category.", color = FbColors.TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = FbColors.Card,
                        border = BorderStroke(1.dp, FbColors.CardHigh)
                    ) {
                        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("START BACKUP WHEN", color = FbColors.Amber, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(unavailable, { unavailable = it }, modifier = Modifier.size(40.dp), colors = CheckboxDefaults.colors(checkedColor = FbColors.Amber))
                                    Text("Unavailable", color = FbColors.TextPrimary, fontSize = 10.sp,)
                                }
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(recommended, { recommended = it }, modifier = Modifier.size(40.dp), colors = CheckboxDefaults.colors(checkedColor = FbColors.Amber))
                                    Text("Already recommended", color = FbColors.TextPrimary, fontSize = 10.sp,)
                                }
                            }
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { editingPrimaryId = -1 },
                            modifier = Modifier.weight(0.85f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, FbColors.CardHigh)
                        ) { Text("Cancel", color = FbColors.TextSecondary) }
                        Button(
                            onClick = {
                                val ordered = selectedBackups.filter { id -> offers.any { it.id == id } }
                                save(chains.filterNot { it.primaryId == primary.id } + FallbackChainConfig(
                                    primaryId = primary.id,
                                    backupIds = ordered,
                                    enabled = existing?.enabled ?: true,
                                    triggerOfferUnavailable = unavailable,
                                    triggerAlreadyRecommended = recommended
                                ))
                                editingPrimaryId = -1
                                selectedTab = FallbackTab.Saved
                            },
                            enabled = selectedBackups.isNotEmpty() && (unavailable || recommended),
                            modifier = Modifier.weight(1.5f).heightIn(min = 44.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = FbColors.Amber, contentColor = FbColors.Background)
                        ) {
                            Text("Save backup route  ·  ${selectedBackups.size}", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    } else if (editingPrimaryId >= 0) {
        editingPrimaryId = -1
    }
}
