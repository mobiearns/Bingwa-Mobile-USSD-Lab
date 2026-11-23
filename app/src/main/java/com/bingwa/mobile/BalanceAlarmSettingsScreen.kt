package com.bingwa.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BalanceAlarmSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var airtimeEnabled by remember { mutableStateOf(prefs.getBoolean("balance_alarm_airtime_enabled", false)) }
    var tokensEnabled by remember { mutableStateOf(prefs.getBoolean("balance_alarm_tokens_enabled", false)) }
    var airtimeThreshold by remember { mutableStateOf(prefs.getInt("balance_alarm_airtime_threshold", 50).toString()) }
    var tokenThreshold by remember { mutableStateOf(prefs.getInt("balance_alarm_tokens_threshold", 5).toString()) }
    var snoozeMinutes by remember { mutableIntStateOf(prefs.getInt("balance_alarm_snooze_minutes", 5).coerceIn(1, 180)) }
    var soundMode by remember { mutableStateOf(prefs.getString("balance_alarm_sound_mode", "RING_AND_VIBRATE") ?: "RING_AND_VIBRATE") }
    var ringtoneName by remember {
        mutableStateOf(
            prefs.getString("balance_alarm_ringtone_uri", null)?.let { raw ->
                runCatching { RingtoneManager.getRingtone(ctx, android.net.Uri.parse(raw))?.getTitle(ctx) }.getOrNull()
            } ?: "System default alarm sound"
        )
    }
    var snoozeMenu by remember { mutableStateOf(false) }
    var soundMenu by remember { mutableStateOf(false) }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<android.net.Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString("balance_alarm_ringtone_uri", uri?.toString()).apply()
            ringtoneName = if (uri == null) "System default alarm sound" else runCatching {
                RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) ?: "Selected ringtone"
            }.getOrDefault("Selected ringtone")
        }
    }

    val soundOptions = listOf(
        "RING_AND_VIBRATE" to "Ring + vibrate",
        "RING" to "Ring only",
        "VIBRATE" to "Vibrate only",
        "VIBRATE_THEN_RING" to "Vibrate, then ring",
        "SILENT" to "Visual alert only"
    )
    val selectedSound = soundOptions.firstOrNull { it.first == soundMode }?.second ?: "Ring + vibrate"
    val overlayGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(ctx)

    // The parent BalanceAlarmSettingsPage owns vertical scrolling. Avoid a
    // second nested scroll container so the full settings page moves as one surface.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = UiDimens.ScreenContentMaxWidth).fillMaxWidth()
                .padding(horizontal = UiDimens.ScreenHorizontal, vertical = UiDimens.ScreenVertical),
            verticalArrangement = Arrangement.spacedBy(UiDimens.SectionSpacing)
        ) {
            // A compact hero makes the purpose and current state immediately clear.
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(UiDimens.CardRadius),
                colors = CardDefaults.cardColors(containerColor = C.cardHi)
            ) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(C.cardHi, C.card, C.surface))
                    ).padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = C.amber.copy(alpha = 0.16f), shape = CircleShape) {
                                Text("◷", color = C.amber, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("BALANCE GUARD", color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp, softWrap = true, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                                Text("Smart low-balance alerts", color = C.t1, fontSize = 18.sp, fontWeight = FontWeight.Bold, softWrap = true, overflow = androidx.compose.ui.text.style.TextOverflow.Clip)
                            }
                            Surface(
                                color = if (airtimeEnabled || tokensEnabled) C.amber.copy(alpha = 0.16f) else C.surface,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    if (airtimeEnabled || tokensEnabled) "ON" else "OFF",
                                    color = if (airtimeEnabled || tokensEnabled) C.amber else C.t3,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                                )
                            }
                        }
                        Text(
                            "Stay ahead of low balances.",
                            color = C.t1, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, softWrap = true, overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                        )
                        Text(
                            "Choose your limits and how Bingwa Mobile should alert you when airtime or tokens run low.",
                            color = C.t2, fontSize = 12.sp, lineHeight = 18.sp,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Clip
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AlarmStatusPill("AIRTIME", airtimeEnabled)
                            AlarmStatusPill("TOKENS", tokensEnabled)
                        }
                    }
                }
            }

            AlarmSettingsCard(
                title = "What should we monitor?",
                subtitle = "Enable one or both balance alerts.",
                eyebrow = "01"
            ) {
                AlarmToggleRow(
                    title = "Airtime balance",
                    subtitle = "Get notified when airtime reaches your limit.",
                    checked = airtimeEnabled
                ) {
                    airtimeEnabled = it
                    prefs.edit().putBoolean("balance_alarm_airtime_enabled", it).apply()
                    BalanceAlarmController.evaluateSavedBalances(ctx)
                    if (BalanceAlarmController.areAnyEnabled(ctx)) ServiceLauncher.startBalanceChecker(ctx)
                }
                if (airtimeEnabled) {
                    AlarmThresholdField(
                        value = airtimeThreshold,
                        label = "Airtime alert threshold",
                        suffix = "KSh",
                        onValueChange = { value ->
                            val clean = value.filter(Char::isDigit).take(7)
                            airtimeThreshold = clean
                            clean.toIntOrNull()?.let {
                                prefs.edit().putInt("balance_alarm_airtime_threshold", it.coerceAtLeast(0)).apply()
                                BalanceAlarmController.evaluateSavedBalances(ctx)
                            }
                        }
                    )
                }
                HorizontalDivider(color = C.border.copy(alpha = 0.8f))
                AlarmToggleRow(
                    title = "Token balance",
                    subtitle = "Get notified when your remaining tokens reach your limit.",
                    checked = tokensEnabled
                ) {
                    tokensEnabled = it
                    prefs.edit().putBoolean("balance_alarm_tokens_enabled", it).apply()
                    BalanceAlarmController.evaluateSavedBalances(ctx)
                    if (BalanceAlarmController.areAnyEnabled(ctx)) ServiceLauncher.startBalanceChecker(ctx)
                }
                if (tokensEnabled) {
                    AlarmThresholdField(
                        value = tokenThreshold,
                        label = "Token alert threshold",
                        suffix = "TOKENS",
                        onValueChange = { value ->
                            val clean = value.filter(Char::isDigit).take(7)
                            tokenThreshold = clean
                            clean.toIntOrNull()?.let {
                                prefs.edit().putInt("balance_alarm_tokens_threshold", it.coerceAtLeast(0)).apply()
                                BalanceAlarmController.evaluateSavedBalances(ctx)
                            }
                        }
                    )
                }
                if (!airtimeEnabled && !tokensEnabled) {
                    Surface(color = C.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Turn on an alert to activate Balance Guard.",
                            color = C.t2, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            AlarmSettingsCard(
                title = "Sound & timing",
                subtitle = "Personalize how each alert reaches you.",
                eyebrow = "02"
            ) {
                Text("ALERT STYLE", color = C.t3, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Box {
                    Surface(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .clickable { soundMenu = true },
                        color = C.surface,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, C.border)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 13.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(11.dp)
                        ) {
                            Surface(color = C.amber.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
                                Text("♫", color = C.amber, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(selectedSound, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("Sound and vibration behaviour", color = C.t3, fontSize = 10.sp)
                            }
                            Text("CHANGE", color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    DropdownMenu(expanded = soundMenu, onDismissRequest = { soundMenu = false }) {
                        soundOptions.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    soundMode = value
                                    soundMenu = false
                                    prefs.edit().putString("balance_alarm_sound_mode", value).apply()
                                }
                            )
                        }
                    }
                }

                HorizontalDivider(color = C.border.copy(alpha = 0.8f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Alarm ringtone", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(ringtoneName, color = C.t3, fontSize = 10.sp, lineHeight = 14.sp,)
                    }
                    OutlinedButton(
                        onClick = {
                            val current = prefs.getString("balance_alarm_ringtone_uri", null)?.let(android.net.Uri::parse)
                            val pick = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose Bingwa Mobile alarm ringtone")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
                            }
                            ringtonePicker.launch(pick)
                        },
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.amber)
                    ) { Text("CHOOSE", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = C.border.copy(alpha = 0.8f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Snooze duration", color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Pause an alert temporarily", color = C.t3, fontSize = 10.sp)
                    }
                    Box {
                        OutlinedButton(
                            onClick = { snoozeMenu = true },
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.amber)
                        ) { Text("$snoozeMinutes min", fontWeight = FontWeight.Bold) }
                        DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                            listOf(1, 3, 5, 10, 15, 20, 30, 45, 60, 90, 120).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("$minutes minutes") },
                                    onClick = {
                                        snoozeMinutes = minutes
                                        snoozeMenu = false
                                        prefs.edit().putInt("balance_alarm_snooze_minutes", minutes).apply()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            AlarmSettingsCard(
                title = "Android permissions",
                subtitle = "Allow Android to show alerts at the right time.",
                eyebrow = "03"
            ) {
                AlarmPermissionRow(
                    title = "Display over other apps",
                    subtitle = "Allows the alarm to appear above your current screen.",
                    granted = overlayGranted
                )
                if (!overlayGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Button(
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(13.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.amber, contentColor = C.bg)
                    ) { Text("ENABLE OVERLAY ACCESS", fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                }
                Text(
                    "Some Android versions also require notification and full-screen alarm access, especially when your phone is locked.",
                    color = C.t3, fontSize = 10.sp, lineHeight = 15.sp
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    OutlinedButton(
                        onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.t1)
                    ) { Text("NOTIFICATION SETTINGS", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    OutlinedButton(
                        onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.t1)
                    ) { Text("FULL-SCREEN ALARM ACCESS", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                }
            }

            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.amber, contentColor = C.bg)
            ) {
                Text("DONE", fontWeight = FontWeight.ExtraBold, letterSpacing = 0.7.sp)
            }
            Text(
                "Balance Guard works quietly in the background and alerts you when an enabled balance reaches its limit.",
                color = C.t3, fontSize = 10.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AlarmStatusPill(label: String, enabled: Boolean) {
    Surface(
        color = if (enabled) C.amber.copy(alpha = 0.15f) else C.surface,
        shape = RoundedCornerShape(50),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) C.amber.copy(alpha = 0.28f) else C.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (enabled) C.amber else C.t3))
            Text("$label  ·  ${if (enabled) "ON" else "OFF"}", color = if (enabled) C.amber else C.t2, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AlarmSettingsCard(
    title: String,
    subtitle: String,
    eyebrow: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = C.cardHi),
        border = androidx.compose.foundation.BorderStroke(1.dp, C.border.copy(alpha = 0.75f))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Surface(color = C.amber.copy(alpha = 0.14f), shape = RoundedCornerShape(9.dp)) {
                    Text(eyebrow, color = C.amber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = C.t1, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = C.t3, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
            content()
        }
    }
}

@Composable
private fun AlarmToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (checked) C.amber.copy(alpha = 0.08f) else C.surface.copy(alpha = 0.55f))
            .border(1.dp, if (checked) C.amber.copy(alpha = 0.24f) else C.border, RoundedCornerShape(13.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.t3, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AlarmThresholdField(
    value: String,
    label: String,
    suffix: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        trailingIcon = {
            Text(suffix, color = C.t2, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
        },
        singleLine = true,
        shape = RoundedCornerShape(13.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun AlarmPermissionRow(title: String, subtitle: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(C.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(9.dp).clip(CircleShape)
                .background(if (granted) C.amber else C.t3)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = C.t1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = C.t3, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Text(
            if (granted) "ALLOWED" else "NEEDED",
            color = if (granted) C.amber else C.t3,
            fontSize = 9.sp, fontWeight = FontWeight.Bold
        )
    }
}
