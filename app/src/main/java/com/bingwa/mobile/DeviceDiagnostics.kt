package com.bingwa.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

private data class DiagnosticItem(
    val title: String,
    val detail: String,
    val healthy: Boolean,
    val iconTint: Color,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null
)

@Composable
fun DeviceDiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val reportScope = rememberCoroutineScope()
    var refreshKey by remember { mutableIntStateOf(0) }
    var copyingReport by remember { mutableIntStateOf(0) }
    val summary = rememberSetupDoctorSummary(refreshKey)
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshKey++
    }

    val criticalPermissions = remember {
        listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
    }
    val missingCriticalPermissions = summary.missingCriticalPermissions
    val grantedCriticalPermissions = criticalPermissions.size - missingCriticalPermissions.size
    val notificationsGranted = summary.notificationsGranted
    val accessibilityEnabled = summary.accessibilityEnabled
    val batteryUnrestricted = summary.batteryUnrestricted
    val exactAlarmReady = summary.exactAlarmReady
    val simCount = summary.simCount
    val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = Build.MODEL
    val sdkLabel = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    val passedCount = summary.passedChecks
    val oemReliabilityLabel = remember(manufacturer) { oemReliabilitySettingsLabel(manufacturer) }

    val diagnostics = remember(
        summary,
        grantedCriticalPermissions,
        notificationsGranted,
        accessibilityEnabled,
        batteryUnrestricted,
        exactAlarmReady,
        simCount,
        missingCriticalPermissions
    ) {
        listOf(
        DiagnosticItem(
            title = "Critical permissions",
            detail = "$grantedCriticalPermissions/${criticalPermissions.size} phone and SMS permissions granted",
            healthy = grantedCriticalPermissions == criticalPermissions.size,
            iconTint = if (grantedCriticalPermissions == criticalPermissions.size) C.green else C.red,
            actionLabel = if (missingCriticalPermissions.isNotEmpty()) "Grant now" else null,
            action = if (missingCriticalPermissions.isNotEmpty()) {
                { phonePermissionLauncher.launch(missingCriticalPermissions.toTypedArray()) }
            } else null
        ),
        DiagnosticItem(
            title = "Notifications",
            detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (notificationsGranted) "Notification permission granted" else "Notification permission is missing on Android 13+"
            } else {
                "No runtime notification permission needed on this Android version"
            },
            healthy = notificationsGranted,
            iconTint = if (notificationsGranted) C.green else C.orange,
            actionLabel = if (!notificationsGranted) "Allow" else null,
            action = if (!notificationsGranted) {
                { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else null
        ),
        DiagnosticItem(
            title = "Accessibility automation",
            detail = if (accessibilityEnabled) {
                "USSD Accessibility service is enabled"
            } else {
                "Advanced USSD automation needs Accessibility enabled"
            },
            healthy = accessibilityEnabled,
            iconTint = if (accessibilityEnabled) C.green else C.orange,
            actionLabel = if (!accessibilityEnabled) "Open" else null,
            action = if (!accessibilityEnabled) {
                { openSettings(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else null
        ),
        DiagnosticItem(
            title = "Battery protection",
            detail = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                "Battery optimization checks are not required on this Android version"
            } else if (batteryUnrestricted) {
                "Battery optimization is already disabled for Bingwa Mobile"
            } else {
                "Battery optimization may stop background work on some phones"
            },
            healthy = batteryUnrestricted,
            iconTint = if (batteryUnrestricted) C.green else C.orange,
            actionLabel = if (!batteryUnrestricted) oemReliabilityLabel else null,
            action = if (!batteryUnrestricted) {
                { openReliabilitySettings(ctx) }
            } else null
        ),
        DiagnosticItem(
            title = "Exact alarms",
            detail = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                "Exact alarm approval is not required on this Android version"
            } else if (exactAlarmReady) {
                "Tomorrow retries and timed recovery can run exactly"
            } else {
                "This phone may delay scheduled retries because exact alarms are not approved"
            },
            healthy = exactAlarmReady,
            iconTint = if (exactAlarmReady) C.green else C.orange,
            actionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmReady) "Open alarm access" else null,
            action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !exactAlarmReady) {
                {
                    openSettings(
                        ctx,
                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            } else null
        ),
        DiagnosticItem(
            title = "SIM detection",
            detail = when {
                simCount > 1 -> "$simCount active SIMs detected"
                simCount == 1 -> "1 active SIM detected"
                grantedCriticalPermissions != criticalPermissions.size -> "Grant phone permission to detect SIMs"
                else -> "No active SIM detected right now"
            },
            healthy = simCount > 0,
            iconTint = if (simCount > 0) C.green else C.orange,
            actionLabel = if (simCount == 0 && grantedCriticalPermissions != criticalPermissions.size) "Grant phone access" else null,
            action = if (simCount == 0 && grantedCriticalPermissions != criticalPermissions.size) {
                { phonePermissionLauncher.launch(missingCriticalPermissions.toTypedArray()) }
            } else null
        )
        )
    }
    val manufacturerAdvice = remember(summary.manufacturerAdvice, manufacturer) {
        if (summary.manufacturerAdvice.startsWith("On ", ignoreCase = true)) {
            summary.manufacturerAdvice
        } else {
            "On $manufacturer phones, ${summary.manufacturerAdvice.replaceFirstChar { it.lowercase() }}"
        }
    }
    val report = remember(diagnostics, manufacturer, model, sdkLabel, passedCount, manufacturerAdvice) {
        buildString {
            appendLine("Bingwa Mobile device diagnostics")
            appendLine("Device: $manufacturer $model")
            appendLine("System: $sdkLabel")
            appendLine("Passed checks: $passedCount/6")
            diagnostics.forEach { item ->
                appendLine("- ${item.title}: ${if (item.healthy) "OK" else "Needs attention"}")
                appendLine("  ${item.detail}")
            }
            appendLine("Advice: $manufacturerAdvice")
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(C.bg),
        contentPadding = PaddingValues(start = UiDimens.ScreenHorizontal, top = UiDimens.ScreenVertical, end = UiDimens.ScreenHorizontal, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(UiDimens.CardSpacing)
    ) {
        item {
            SettingsTopBar("Setup Doctor", "Check setup issues that commonly break automation on other phones", onBack)
        }
        item {
            SettingsGroup("Overview") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(UiDimens.CardPadding),
                    shape = RoundedCornerShape(16.dp),
                    color = C.w04,
                    border = BorderStroke(1.dp, C.border)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(C.cyanDim)
                                    .border(1.dp, C.cyan.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.PhoneAndroid, null, tint = C.cyan, modifier = Modifier.size(21.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$manufacturer $model",
                                    color = C.t1,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = sdkLabel,
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PillBadge("$passedCount/6 checks ready", if (passedCount == 6) C.green else C.orange)
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = C.w04,
                            border = BorderStroke(1.dp, C.border)
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "DEVICE RECOMMENDATION",
                                    color = C.cyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = manufacturerAdvice,
                                    color = C.t2,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { refreshKey++ },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = C.cyan)
                            ) {
                                Icon(Icons.Rounded.Autorenew, null, tint = C.bg, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(9.dp))
                                Text("Refresh diagnostics", color = C.bg, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    if (copyingReport == 0) {
                                        copyingReport = 1
                                        reportScope.launch {
                                            val smsReport = runCatching {
                                                SmsProviderClient.buildDiagnosticReport(ctx)
                                            }.getOrElse { error ->
                                                "SMS CENTRE DIAGNOSTICS\\nReport collection failed: " +
                                                    (error.message ?: "Unknown error") + "\\n"
                                            }
                                            val fullReport = buildString {
                                                append(report)
                                                appendLine()
                                                append(UssdPerformanceDiagnostics.buildReport(ctx))
                                                appendLine()
                                                append(smsReport)
                                            }
                                            copyReport(ctx, fullReport)
                                            copyingReport = 0
                                            Toast.makeText(ctx, "Setup Doctor report copied (including SMS Centre diagnostics)", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
                                shape = RoundedCornerShape(13.dp),
                                border = BorderStroke(1.dp, C.border),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.t1)
                            ) {
                                Text(if (copyingReport == 1) "Collecting diagnostics…" else "Copy diagnostic report", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup("Checks") {
                diagnostics.forEachIndexed { index, item ->
                    DiagnosticRow(item)
                    if (index != diagnostics.lastIndex) GroupDivider()
                }
            }
        }
        item {
            SettingsGroup("Quick Actions") {
                LinkRow(Icons.Rounded.Accessibility, "Open Accessibility", "Turn on Bingwa automation service", C.cyan) {
                    openSettings(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                GroupDivider()
                LinkRow(Icons.Rounded.Info, "Open App Settings", "Manage permissions and app behavior", C.blue) {
                    openSettings(ctx, appDetailsIntent(ctx))
                }
                GroupDivider()
                LinkRow(Icons.Rounded.PhoneAndroid, oemReliabilityLabel, "Open the background, auto-start, or battery screen that matters on this phone", C.orange) {
                    openReliabilitySettings(ctx)
                }
                GroupDivider()
                LinkRow(Icons.Rounded.Schedule, "Open Alarm Access", "Allow exact alarms for scheduled retries", C.purple) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        openSettings(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    } else {
                        openSettings(ctx, appDetailsIntent(ctx))
                    }
                }
            }
        }
        item {
            SettingsGroup("Why This Helps") {
                InfoRow(Icons.Rounded.CheckCircle, C.green, "Permissions", "Prevents SMS, calling, and SIM detection failures.")
                GroupDivider()
                InfoRow(Icons.Rounded.Warning, C.orange, "Battery rules", "Reduces background stops on Tecno, Infinix, Samsung, Xiaomi, and similar phones.")
                GroupDivider()
                InfoRow(Icons.Rounded.Info, C.cyan, "Alarm fallback", "Keeps retries working even when exact-alarm access is not allowed.")
                GroupDivider()
                InfoRow(Icons.Rounded.SimCard, C.blue, "Multi-phone setup", "Makes it easier to spot SIM and background-service issues before they break automation.")
            }
        }
    }
}

@Composable
private fun DiagnosticRow(item: DiagnosticItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(item.iconTint.copy(alpha = 0.1f))
                    .border(1.dp, item.iconTint.copy(alpha = 0.18f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (item.healthy) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    null,
                    tint = item.iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    color = C.t1,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.detail,
                    color = C.t2,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
        if (item.actionLabel != null && item.action != null) {
            OutlinedButton(
                onClick = item.action,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, C.border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.cyan)
            ) {
                Text(
                    text = item.actionLabel,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, title: String, detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(color.copy(alpha = 0.1f))
                .border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = C.t1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = C.t2, fontSize = 11.sp)
        }
    }
}

private fun openSettings(context: Context, intent: Intent) {
    val safeIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        context.startActivity(safeIntent)
    }.getOrElse {
        runCatching { context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private fun openReliabilitySettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val directPromptIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (directPromptIntent.resolveActivity(context.packageManager) != null) {
            openSettings(context, directPromptIntent)
            return
        }
    }
    val intent = bestReliabilitySettingsIntent(context)
        ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    openSettings(context, intent)
}

private fun oemReliabilitySettingsLabel(manufacturer: String): String {
    val normalized = manufacturer.lowercase()
    return when {
        normalized.contains("tecno") || normalized.contains("infinix") || normalized.contains("itel") || normalized.contains("transsion") ->
            "Allow Background & Auto-Start"
        normalized.contains("xiaomi") || normalized.contains("redmi") || normalized.contains("poco") ->
            "Open Autostart"
        normalized.contains("samsung") ->
            "Open Device Care"
        normalized.contains("oppo") || normalized.contains("realme") || normalized.contains("oneplus") ->
            "Open Background Manager"
        normalized.contains("vivo") || normalized.contains("iqoo") ->
            "Open Background Startup"
        normalized.contains("huawei") || normalized.contains("honor") ->
            "Open Launch Manager"
        else ->
            "Open Battery Settings"
    }
}

private fun bestReliabilitySettingsIntent(context: Context): Intent? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val candidates = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> listOf(
            componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            componentIntent("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
        )
        manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") || manufacturer.contains("transsion") -> listOf(
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.activity.AutoStartActivity"),
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartManageActivity"),
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.activity.PermissionControlActivity"),
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.activity.StartActivity"),
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.activity.PowerSettingActivity"),
            componentIntent("com.transsion.phonemaster", "com.transsion.phonemaster.purebackground.PureBackgroundActivity"),
            componentIntent("com.itel.autobootmanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity")
        )
        manufacturer.contains("samsung") -> listOf(
            componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            componentIntent("com.samsung.android.lool", "com.samsung.android.sm.ui.appmanagement.AppManagementActivity")
        )
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> listOf(
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity"),
            componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerConsumptionActivity")
        )
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
            componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        )
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )
        else -> emptyList()
    }
    return candidates.firstOrNull { intent ->
        intent.resolveActivity(context.packageManager) != null
    }
}

private fun componentIntent(packageName: String, className: String): Intent =
    Intent().apply {
        component = ComponentName(packageName, className)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

private fun appDetailsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun copyReport(context: Context, report: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("bingwa-diagnostics", report))
}
