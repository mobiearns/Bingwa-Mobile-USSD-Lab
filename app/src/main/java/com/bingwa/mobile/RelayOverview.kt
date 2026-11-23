package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bingwa.mobile.relay.*

@Composable
internal fun RelayOverview(onOpenAdvancedSettings: () -> Unit = {}, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    LaunchedEffect(context) {
        val config = RelayManager.load(context)
        if (config.enabled && config.role == "RELAY" && config.method == "HOTSPOT") {
            RelayManager.startRelayHotspotService(context)
        } else if (config.enabled && config.role == "PRIMARY" && config.method == "HOTSPOT") {
            RelayManager.startHotspotMonitor(context)
        }
    }
    val config by RelayManager.configState.collectAsState()
    val hotspot by RelayManager.hotspotState.collectAsState()
    var pairing by remember { mutableStateOf<PairingUi?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    fun persistAndRefresh() {
        RelayManager.stopRelayHotspotService(context)
        val refreshed = RelayManager.load(context)
        if (refreshed.enabled && refreshed.role == "RELAY" && refreshed.method == "HOTSPOT") {
            RelayManager.startRelayHotspotService(context)
        } else if (refreshed.enabled && refreshed.role == "PRIMARY" && refreshed.method == "HOTSPOT") {
            RelayManager.startHotspotMonitor(context)
        } else {
            RelayManager.stopHotspotMonitor()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true
        else pairing = PairingUi.Failed("Camera permission is required to scan the Relay phone QR code.")
    }

    val connected = config.enabled && config.role == "PRIMARY" &&
        (config.method != "HOTSPOT" || hotspot == RelayManager.HotspotLinkState.CONNECTED)
    val peerConfigured = config.pairedPhone.isNotBlank() || config.relayIp.isNotBlank()
    val tone = when {
        !config.enabled -> Tone.OFF
        connected -> Tone.GOOD
        hotspot == RelayManager.HotspotLinkState.CHECKING -> Tone.BUSY
        peerConfigured -> Tone.WARN
        else -> Tone.OFF
    }
    val linkTone = when {
        !config.enabled || !peerConfigured -> Tone.OFF
        connected -> Tone.GOOD
        hotspot == RelayManager.HotspotLinkState.CHECKING -> Tone.BUSY
        else -> Tone.WARN
    }
    val uiState = RelayUiState(
        title = when {
            !config.enabled -> "Relay is off"
            config.role == "RELAY" -> "Ready to receive requests"
            connected -> "Relay connected"
            peerConfigured -> "Checking relay link"
            else -> "Pair your second phone"
        },
        body = when {
            !config.enabled -> "Enable two-phone mode to forward selected offers to another device."
            config.role == "RELAY" -> "This phone is configured to execute requests forwarded by the PRIMARY phone."
            config.method == "HOTSPOT" && hotspot == RelayManager.HotspotLinkState.DISCONNECTED -> "The relay phone is not responding on the hotspot. Check the hotspot and IP address."
            connected -> "The PRIMARY phone can forward selected offers to this device."
            else -> "Configure the other phone to finish setting up your relay connection."
        },
        tone = tone,
        linkTone = linkTone,
        linkAnimating = hotspot == RelayManager.HotspotLinkState.CHECKING,
        method = if (config.method == "HOTSPOT") ConnectionMethod.HOTSPOT else ConnectionMethod.SMS,
        secondary = if (peerConfigured) SecondaryPhone(
            name = config.pairedPhone.ifBlank { "Relay phone" },
            statusLabel = when {
                config.method == "HOTSPOT" && connected -> "Connected"
                config.method == "HOTSPOT" -> hotspot.name.lowercase().replace('_', ' ')
                else -> "SMS destination configured"
            },
            tone = if (connected) Tone.GOOD else Tone.WARN,
            connectionDetail = if (config.method == "HOTSPOT") config.relayIp.ifBlank { "Hotspot IP auto-detected" } else config.pairedPhone
        ) else null,
        actionLabel = when {
            !peerConfigured -> "Connect phone"
            !config.enabled -> "Enable Relay"
            config.method == "HOTSPOT" && !connected -> "Check connection"
            else -> "Relay enabled"
        },
        action = when {
            !peerConfigured -> RelayAction.PAIR
            !config.enabled -> RelayAction.TURN_ON
            config.method == "HOTSPOT" && !connected -> RelayAction.RECONNECT
            else -> null
        },
        track = null,
        tips = if (config.enabled && config.method == "HOTSPOT" && !connected && peerConfigured)
            listOf("Keep both phones connected to the same hotspot network.", "Confirm the RELAY phone has Two-Phone Mode enabled with the RELAY role.", "Check the relay IP or use the QR pairing tool in Advanced Settings.")
        else emptyList(),
        relayEnabled = config.enabled,
        autoReconnect = config.relayIpAuto,
        role = if (config.role == "RELAY") Role.SECONDARY else Role.PRIMARY
    )

    val callbacks = remember(context, config, onOpenAdvancedSettings) {
        object : RelayCallbacks {
            override fun onAdvancedSettings() = onOpenAdvancedSettings()

            override fun onAction(action: RelayAction) {
                when (action) {
                    RelayAction.TURN_ON -> {
                        prefs.edit().putBoolean("two_phone_enabled", true).apply()
                        persistAndRefresh()
                    }
                    RelayAction.PAIR -> {
                        prefs.edit().putBoolean("two_phone_enabled", true).apply()
                        persistAndRefresh()
                        pairing = PairingUi.PickSetup
                    }
                    RelayAction.RECONNECT -> {
                        RelayManager.startHotspotMonitor(context)
                        Toast.makeText(context, "Checking relay connection", Toast.LENGTH_SHORT).show()
                    }
                    RelayAction.DONE -> Unit
                    RelayAction.CANCEL -> pairing = null
                    RelayAction.OPEN_HISTORY -> Toast.makeText(context, "Open Settings → Transaction History to review relay activity.", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onMethodChange(method: ConnectionMethod) {
                prefs.edit().putString("relay_method", if (method == ConnectionMethod.HOTSPOT) "HOTSPOT" else "SMS").apply()
                persistAndRefresh()
            }

            override fun onRelayEnabledChange(enabled: Boolean) {
                prefs.edit().putBoolean("two_phone_enabled", enabled).apply()
                persistAndRefresh()
            }

            override fun onRoleChange(role: Role) {
                prefs.edit().putString("two_phone_role", if (role == Role.SECONDARY) "RELAY" else "PRIMARY").apply()
                persistAndRefresh()
            }

            override fun onAutoReconnectChange(enabled: Boolean) {
                prefs.edit().putBoolean("relay_ip_auto", enabled).apply()
                persistAndRefresh()
            }

            override fun onStartPairing(method: ConnectionMethod, phoneNumber: String?) {
                if (method == ConnectionMethod.SMS) {
                    val number = phoneNumber.orEmpty().filter(Char::isDigit)
                    if (!Regex("^(?:254|0)?[17]\\d{8}$").matches(number)) {
                        pairing = PairingUi.Failed("Enter a valid Kenyan mobile number for the other phone.")
                        return
                    }
                    val normalized = when {
                        number.startsWith("254") -> "0" + number.drop(3)
                        number.length == 9 -> "0$number"
                        else -> number
                    }
                    prefs.edit().putString("paired_phone", normalized).putBoolean("two_phone_enabled", true).apply()
                    persistAndRefresh()
                    pairing = PairingUi.Success("SMS destination saved")
                    return
                }

                if (config.role == "RELAY") {
                    val payload = RelayQrCodec.createPayload(
                        pairedPhone = config.pairedPhone,
                        relayPrefix = config.prefix,
                        relayPin = config.pin,
                        relayIp = config.relayIp,
                        sendResultsSms = config.sendResultsSms
                    )
                    if (payload.relayIp.isBlank()) {
                        pairing = PairingUi.Failed("Turn on the RELAY phone hotspot or enter its IP address in Advanced Settings first.")
                    } else {
                        qrBitmap = RelayQrCodec.createBitmap(RelayQrCodec.encode(payload))
                        showQr = true
                        pairing = PairingUi.Waiting
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        showScanner = true
                    } else {
                        cameraLauncher.launch(Manifest.permission.CAMERA)
                    }
                    pairing = PairingUi.Waiting
                }
            }

            override fun onCancelPairing() {
                pairing = null
                showScanner = false
                showQr = false
            }

            override fun onPairingBack() { pairing = PairingUi.PickSetup }

            override fun onPairingContinue(role: Role, method: ConnectionMethod) {
                prefs.edit()
                    .putBoolean("two_phone_enabled", true)
                    .putString("two_phone_role", if (role == Role.SECONDARY) "RELAY" else "PRIMARY")
                    .putString("relay_method", if (method == ConnectionMethod.HOTSPOT) "HOTSPOT" else "SMS")
                    .apply()
                persistAndRefresh()
                pairing = PairingUi.Instructions(
                    if (method == ConnectionMethod.HOTSPOT) listOf(
                        if (role == Role.SECONDARY) "Keep this phone connected to its hotspot and get ready to display its connection QR code." else "On the Secondary phone, open Relay and choose Connect phone → Secondary → Hotspot.",
                        "Make sure both phones are connected to the same Wi-Fi hotspot network.",
                        if (role == Role.SECONDARY) "The Primary phone scans this phone's QR code to import the connection." else "Scan the QR code shown by the Secondary phone to import its network details."
                    ) else listOf(
                        "Enter the other phone's mobile number when prompted.",
                        "Use the same Relay prefix and PIN on both phones.",
                        "Save the settings on both devices before forwarding offers."
                    )
                )
            }

            override fun onPairingClose() {
                pairing = null
                showQr = false
                showScanner = false
            }

            override fun onUnpairConfirmed() {
                prefs.edit().putString("paired_phone", "").putString("relay_ip", "").apply()
                persistAndRefresh()
            }
        }
    }

    RelayScreen(state = uiState, pairing = pairing, callbacks = callbacks, onBack = onBack)

    val bitmap = qrBitmap
    if (showQr && bitmap != null) {
        AlertDialog(
            onDismissRequest = { showQr = false; pairing = null },
            confirmButton = { TextButton(onClick = { showQr = false; pairing = null }) { Text("Close") } },
            title = { Text("Scan To Connect") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bitmap.asImageBitmap(), contentDescription = "Relay connection QR", modifier = Modifier.size(230.dp))
                    Text("On the PRIMARY phone, scan this QR from Relay → Advanced Settings.", color = Color.Gray)
                }
            }
        )
    }

    if (showScanner) {
        RelayHotspotScannerDialog(
            onDismiss = { showScanner = false; pairing = null },
            onQrDetected = { raw ->
                val payload = RelayQrCodec.decode(raw)
                if (payload == null) false else {
                    prefs.edit()
                        .putBoolean("two_phone_enabled", true)
                        .putString("two_phone_role", "PRIMARY")
                        .putString("relay_method", "HOTSPOT")
                        .putString("paired_phone", payload.relayPhone)
                        .putString("relay_ip", payload.relayIp)
                        .putBoolean("relay_ip_auto", payload.relayIp.isBlank())
                        .putString("relay_prefix", payload.relayPrefix)
                        .putString("relay_pin", payload.relayPin)
                        .putBoolean("relay_send_results", payload.sendResultsSms)
                        .apply()
                    persistAndRefresh()
                    pairing = PairingUi.Success("Relay phone")
                    showScanner = false
                    true
                }
            }
        )
    }
}
