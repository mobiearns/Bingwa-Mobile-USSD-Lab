package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

object UssdHelper {
    // Cache resolved PhoneAccountHandles to eliminate repeated TelecomManager IPC round-trips
    private val phoneAccountCache = java.util.concurrent.ConcurrentHashMap<String, android.telecom.PhoneAccountHandle>()
    private val relaunchToken = Any()
    private const val RETURN_TO_APP_DELAY_MS = 120L

    fun normalizeRecipientForUssdInput(phone: String): String {
        val digits = phone.trim().replace("+", "").replace(Regex("\\D+"), "")
        if (digits.isBlank()) return ""

        val local = SmsCommandHandler.normalizePhone(phone).replace(Regex("\\D+"), "")
        return when {
            local.length == 10 && local.startsWith("0") -> local
            local.length == 9 && (local.startsWith("7") || local.startsWith("1")) -> "0$local"
            digits.length == 12 && digits.startsWith("254") -> "0${digits.drop(3)}"
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0$digits"
            digits.length == 10 && digits.startsWith("0") -> digits
            else -> digits
        }
    }

    fun normalizeUssdCode(raw: String, phoneNumber: String? = null): String {
        val v = raw.trim()
            .let {
                if (phoneNumber != null) {
                    it.replace("pn", normalizeRecipientForUssdInput(phoneNumber), ignoreCase = true)
                } else {
                    it
                }
            }
            .replace("%23", "#")
        if (v.startsWith("*") && !v.endsWith("#")) return "$v#"
        return v
    }

    /**
     * Validates a USSD code before attempting to dial.
     * Returns null if valid, or an error message if invalid.
     */
    fun validateUssdCode(code: String): String? {
        val trimmed = code.trim().replace("%23", "#", ignoreCase = true)
        if (trimmed.isBlank()) return "USSD code is empty"
        if (!trimmed.startsWith("*")) return "USSD code must start with '*'"
        if (!trimmed.endsWith("#")) return "USSD code must end with '#'"
        if (trimmed.length < 3) return "USSD code too short (minimum *#)"
        if (trimmed.length > 50) return "USSD code too long (maximum 50 chars)"
        // Validate the WHOLE dial string, not merely a matching substring.
        // Silent verification requests must never pass labels, spaces, quotes,
        // brackets, line breaks, or any other decoration to Android telephony.
        val validPattern = Regex("^\\*\\d+(?:\\*\\d+)*#$")
        if (!validPattern.matches(trimmed)) {
            return "USSD code contains invalid characters (only digits, *, and # are allowed)"
        }
        return null
    }

    fun dialUssd(
        context: Context,
        ussdCode: String,
        silentOnly: Boolean = false,
        subIdOverride: Int? = null,
        onSuccess: ((String) -> Unit)? = null,
        onFailure: ((String) -> Unit)? = null,
        preserveRawResponse: Boolean = false
    ): Boolean {
        val delivered = AtomicBoolean(false)
        fun deliverOnceSuccess(response: String) {
            if (delivered.compareAndSet(false, true)) {
                onSuccess?.invoke(response)
            }
        }
        fun deliverOnceFailure(reason: String) {
            if (delivered.compareAndSet(false, true)) {
                onFailure?.invoke(reason)
            }
        }

        // Silent signature requests must cross this boundary unchanged.
        // Do not trim, decode %23, append '#', or otherwise "repair" them here.
        // Visible/manual legacy flows retain their existing normalisation.
        val code = if (silentOnly) {
            canonicalSilentUssdDialString(ussdCode) ?: run {
                val reason = "Silent USSD code is not canonical. Expected an unchanged *digits*digits# string."
                Log.e("UssdHelper", "Silent USSD validation failed: $reason")
                deliverOnceFailure(reason)
                return false
            }
        } else {
            val validationError = validateUssdCode(ussdCode)
            if (validationError != null) {
                Log.e("UssdHelper", "USSD validation failed: $validationError")
                deliverOnceFailure(validationError)
                return false
            }
            normalizeUssdCode(ussdCode)
        }

        if (silentOnly) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                deliverOnceFailure("Native silent USSD requires Android 8.0 (API 26) or higher.")
                return false
            }
            if (!canPlaceCalls(context)) {
                deliverOnceFailure("CALL_PHONE permission is required for silent USSD requests.")
                return false
            }
            if (SilentUssdOptimized.isExecutionInProgress()) {
                deliverOnceFailure("USSD execution already in progress")
                return false
            }
        }

        val simResolutionStartedAt = UssdPerformanceDiagnostics.mark()
        val targets = resolveUssdSimTargets(
            context,
            selectionOverride = subIdOverride,
            forceRefresh = silentOnly
        )
        UssdPerformanceDiagnostics.record(
            "silent.sim_resolution",
            simResolutionStartedAt,
            "targetCount=${targets.size} selectedSlot=${targets.firstOrNull()?.slotIndex?.plus(1) ?: 0} explicitSelection=${subIdOverride != null} silent=${silentOnly}"
        )
        if (targets.isEmpty()) {
            val activeSims = getAvailableSims(context, forceRefresh = true)
            val reason = if (activeSims.isEmpty() && !hasPhoneStatePermission(context)) {
                "READ_PHONE_STATE permission is required to verify the selected SIM subscription."
            } else if (activeSims.isEmpty()) {
                "No active SIM card was detected."
            } else {
                "Selected SIM slot is not active or has no SIM installed."
            }
            deliverOnceFailure(reason)
            return false
        }

        // Silent requests must only target the single resolved SIM subscription;
        // never automatically re-dial the same code onto another SIM slot.
        val effectiveTargets = if (silentOnly) listOf(targets.first()) else targets
        return dialUssdAttempt(
            context = context,
            code = code,
            silentOnly = silentOnly,
            targets = effectiveTargets,
            attemptIndex = 0,
            onSuccess = ::deliverOnceSuccess,
            onFailure = ::deliverOnceFailure,
            preserveRawResponse = preserveRawResponse
        )
    }

    fun dialUssd(context: Context, ussdCode: String) {
        dialUssd(context, ussdCode, silentOnly = false, subIdOverride = null, onSuccess = null, onFailure = null)
    }

    private fun dialUssdAttempt(
        context: Context,
        code: String,
        silentOnly: Boolean,
        targets: List<UssdSimTarget>,
        attemptIndex: Int,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
        preserveRawResponse: Boolean
    ): Boolean {
        val target = targets.getOrNull(attemptIndex)
        if (target == null) {
            onFailure("Selected SIM slot is unavailable")
            return false
        }

        val bindingStartedAt = UssdPerformanceDiagnostics.mark()
        val readinessError = validateSubscriptionForSilentUssd(context, target)
        val tm = if (readinessError == null) selectedTelephonyManager(context, target.subId) else null
        UssdPerformanceDiagnostics.record(
            "silent.sim_binding",
            bindingStartedAt,
            "slot=${target.slotIndex + 1} ready=${readinessError == null} managerBound=${tm != null} subscriptionIdRedacted=true"
        )
        if (tm == null) {
            val reason = readinessError ?: "Telephony subscription is unavailable on the selected SIM"
            return if (!silentOnly && attemptIndex + 1 < targets.size) {
                dialUssdAttempt(context, code, silentOnly, targets, attemptIndex + 1, onSuccess, onFailure, preserveRawResponse)
            } else if (!silentOnly) {
                fallbackToVisible(context, code, onSuccess, onFailure, target.subId)
            } else {
                onFailure(reason)
                false
            }
        }

        val slotLabel = "slot ${target.slotIndex + 1}"
        Log.d("UssdHelper", "Initiating USSD on $slotLabel (silentOnly=$silentOnly)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val silentOk = SilentUssdOptimized.execute(
                telephonyManager = tm,
                ussdCode = code,
                onSuccess = { onSuccess(it) },
                onFailure = { error ->
                    val isBusyError = error.contains("already in progress", ignoreCase = true)
                    val nextTarget = if (!silentOnly && !isBusyError) targets.getOrNull(attemptIndex + 1) else null
                    if (nextTarget != null) {
                        Log.w("UssdHelper", "USSD failed on $slotLabel, retrying slot ${nextTarget.slotIndex + 1}: $error")
                        dialUssdAttempt(context, code, silentOnly, targets, attemptIndex + 1, onSuccess, onFailure, preserveRawResponse)
                    } else if (!silentOnly && !isBusyError) {
                        fallbackToVisible(context, code, onSuccess, onFailure, target.subId)
                    } else {
                        onFailure(error)
                    }
                },
                preserveRawResponse = preserveRawResponse
            )
            if (silentOk) return true
            // SilentUssdOptimized already invoked onFailure synchronously when returning false.
            return false
        }

        // Pre-Oreo (< Android 8.0): native sendUssdRequest does not exist.
        return if (!silentOnly) {
            fallbackToVisible(context, code, onSuccess, onFailure, target.subId)
        } else {
            onFailure("Native silent USSD requires Android 8.0 (API 26) or higher.")
            false
        }
    }

    internal fun validateSubscriptionForSilentUssd(context: Context, target: UssdSimTarget): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Native silent USSD requires Android 8.0 (API 26) or higher."
        }
        if (!canPlaceCalls(context)) {
            return "CALL_PHONE permission is required for silent USSD."
        }
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "TelephonyManager service is unavailable."

        val subId = target.subId
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (hasPhoneStatePermission(context)) {
                return "No valid SIM subscription ID was resolved for SIM slot ${target.slotIndex + 1}."
            }
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasPhoneStatePermission(context)) {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val active = runCatching { sm?.isActiveSubscriptionId(subId) }.getOrNull()
            if (active == false) {
                return "Selected SIM slot ${target.slotIndex + 1} subscription is not active."
            }
        }

        val boundTm = runCatching { baseTm.createForSubscriptionId(subId) }.getOrNull()
            ?: return "Android could not bind TelephonyManager to SIM slot ${target.slotIndex + 1}."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val boundSubId = runCatching { boundTm.subscriptionId }.getOrNull()
            if (boundSubId != subId) {
                return "Android bound TelephonyManager to an unexpected subscription instead of SIM slot ${target.slotIndex + 1}."
            }
        }

        val simState = runCatching { boundTm.simState }.getOrNull()
        when (simState) {
            TelephonyManager.SIM_STATE_ABSENT ->
                return "SIM slot ${target.slotIndex + 1} has no SIM card inserted."
            TelephonyManager.SIM_STATE_PIN_REQUIRED ->
                return "SIM slot ${target.slotIndex + 1} is locked (PIN required)."
            TelephonyManager.SIM_STATE_PUK_REQUIRED ->
                return "SIM slot ${target.slotIndex + 1} is locked (PUK required)."
            TelephonyManager.SIM_STATE_NETWORK_LOCKED ->
                return "SIM slot ${target.slotIndex + 1} is network locked."
            TelephonyManager.SIM_STATE_PERM_DISABLED ->
                return "SIM slot ${target.slotIndex + 1} is permanently disabled."
            TelephonyManager.SIM_STATE_NOT_READY ->
                return "SIM slot ${target.slotIndex + 1} is not ready yet."
        }

        val serviceState = runCatching { boundTm.serviceState?.state }.getOrNull()
        if (serviceState == ServiceState.STATE_POWER_OFF) {
            return "SIM slot ${target.slotIndex + 1} radio is powered off (check airplane mode or SIM toggle)."
        }

        return null
    }

    private fun hasPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun fallbackToVisible(
        context: Context,
        code: String,
        onSuccess: ((String) -> Unit)?,
        onFailure: ((String) -> Unit)?,
        subIdOverride: Int? = null
    ): Boolean {
        try {
            val intent = buildCallIntent(context, code, subIdOverride)
            if (intent.resolveActivity(context.packageManager) != null) {
                val keepAppUiVisible = BingwaMobileApp.wasInForegroundRecently()
                if (keepAppUiVisible) UssdNavigationService.armForegroundUi()
                context.startActivity(intent)
                if (keepAppUiVisible) relaunchAppUi(context)
                return false
            } else {
                onFailure?.invoke("No dialer")
                return false
            }
        } catch (e: SecurityException) {
            OfferNotifications.notify(context, "Permission required", "Open Bingwa Mobile and allow call permission to continue USSD.")
            onFailure?.invoke(e.message ?: "SecurityException")
            return false
        } catch (e: Exception) {
            OfferNotifications.notify(context, "Open app to continue", "Tap to complete USSD request.")
            onFailure?.invoke(e.message ?: "Error")
            return false
        }
    }

    fun canPlaceCalls(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun findPhoneAccountHandleForSubscription(
        context: Context,
        subId: Int,
        targetSlot: Int?
    ): android.telecom.PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val cacheKey = "$subId:${targetSlot ?: -1}"
        phoneAccountCache[cacheKey]?.let { return it }

        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        } else null
        val simInfo = runCatching { sm?.getActiveSubscriptionInfo(subId) }.getOrNull()
        val iccid = simInfo?.iccId?.trim().orEmpty()
        val slot = targetSlot ?: simInfo?.simSlotIndex

        val accounts = runCatching { telecom.callCapablePhoneAccounts }.getOrNull() ?: emptyList()
        if (accounts.isEmpty()) return null

        val handle = resolvePhoneAccountHandleInternal(telecom, accounts, subId, slot, iccid)
        if (handle != null) {
            phoneAccountCache[cacheKey] = handle
        }
        return handle
    }

    private fun resolvePhoneAccountHandleInternal(
        telecom: TelecomManager,
        accounts: List<android.telecom.PhoneAccountHandle>,
        subId: Int,
        slot: Int?,
        iccid: String
    ): android.telecom.PhoneAccountHandle? {
        for (handle in accounts) {
            val account = runCatching { telecom.getPhoneAccount(handle) }.getOrNull()
            val extras = account?.extras
            if (extras != null) {
                val extraSubId = extras.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1)
                    .takeIf { it != -1 }
                    ?: extras.getInt("subscription_id", -1)
                        .takeIf { it != -1 }
                    ?: extras.getString("android.telephony.extra.SUBSCRIPTION_ID")?.toIntOrNull()
                    ?: extras.getString("subscription_id")?.toIntOrNull()
                if (extraSubId == subId) {
                    return handle
                }
            }
        }

        if (slot != null && slot >= 0) {
            for (handle in accounts) {
                val account = runCatching { telecom.getPhoneAccount(handle) }.getOrNull()
                val extras = account?.extras
                if (extras != null) {
                    val extraSlot = extras.getInt("android.telephony.extra.SLOT_INDEX", -1)
                        .takeIf { it != -1 }
                        ?: extras.getInt("slot_index", -1)
                            .takeIf { it != -1 }
                        ?: extras.getString("android.telephony.extra.SLOT_INDEX")?.toIntOrNull()
                        ?: extras.getString("slot_index")?.toIntOrNull()
                    if (extraSlot == slot) {
                        return handle
                    }
                }
            }
        }

        val subIdStr = subId.toString()
        for (handle in accounts) {
            if (handle.id == subIdStr) return handle
        }

        if (iccid.isNotBlank()) {
            for (handle in accounts) {
                if (handle.id == iccid || handle.id.startsWith(iccid) || iccid.startsWith(handle.id)) {
                    return handle
                }
            }
        }

        if (slot != null && slot >= 0) {
            val slotStr = slot.toString()
            for (handle in accounts) {
                val id = handle.id
                if (id == slotStr ||
                    id.endsWith(":$slot") ||
                    id.endsWith("_$slot") ||
                    id.endsWith("/$slot") ||
                    id.equals("sim$slot", ignoreCase = true) ||
                    id.equals("slot$slot", ignoreCase = true) ||
                    id.equals("slot_${slot + 1}", ignoreCase = true)
                ) {
                    return handle
                }
            }

            if (slot in accounts.indices) {
                return accounts[slot]
            }
        }

        Log.w("UssdHelper", "No confirmed PhoneAccountHandle found for slot=$slot; letting intent extras route SIM")
        return null
    }

    fun buildCallIntent(
        context: Context,
        ussdCode: String,
        subIdOverride: Int? = null,
        slotOverride: Int? = null
    ): Intent {
        val code = normalizeUssdCode(ussdCode)
        val uri = Uri.parse("tel:" + Uri.encode(code))
        val canCall = canPlaceCalls(context)
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action).apply { data = uri; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val subId = subIdOverride ?: resolvePreferredUssdSubId(context) ?: -1
        if (subId != -1) {
            val slot = slotOverride ?: runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                    null
                } else {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex
                }
            }.getOrNull()

            intent.putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
            intent.putExtra("subscription", subId)
            intent.putExtra("subscription_id", subId)
            intent.putExtra("sub_id", subId)
            intent.putExtra("subId", subId)
            intent.putExtra("sim_id", subId.toLong())
            intent.putExtra("org.codeaurora.extra.SUB_ID", subId)
            intent.putExtra("phone.subscription", subId)

            if (slot != null && slot >= 0) {
                intent.putExtra("android.telephony.extra.SLOT_INDEX", slot)
                intent.putExtra("com.android.phone.force.slot", slot)
                intent.putExtra("com.android.phone.extra.slot", slot)
                intent.putExtra("slot", slot)
                intent.putExtra("simSlot", slot)
                intent.putExtra("slot_id", slot)
                intent.putExtra("sim_index", slot)
                intent.putExtra("phone_id", slot)
                intent.putExtra("com.mediatek.telephony.slot", slot)
                intent.putExtra("simId", slot)
                intent.putExtra("simnum", slot + 1)
                intent.putExtra("sim_slot", slot)
                intent.putExtra("slotIdx", slot)
                intent.putExtra("com.transsion.telephony.slot", slot)
                intent.putExtra("extra_slot_index", slot)
                intent.putExtra("com.samsung.android.telephony.slot", slot)
                intent.putExtra("com.samsung.android.telephony.simSlot", slot)
                intent.putExtra("com.xiaomi.telephony.slot", slot)
                intent.putExtra("com.oppo.telephony.slot", slot)
                intent.putExtra("com.coloros.telephony.slot", slot)
                intent.putExtra("com.vivo.telephony.slot", slot)
                Log.d("UssdHelper", "buildCallIntent: routed to selected subscription and slot=$slot")
            } else {
                Log.d("UssdHelper", "buildCallIntent: routed to selected subscription")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val handle = findPhoneAccountHandleForSubscription(context, subId, slot)
                if (handle != null) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                }
            }
        }
        return intent
    }

    fun relaunchAppUi(
        context: Context,
        delayMs: Long = RETURN_TO_APP_DELAY_MS,
        aggressiveRetries: Boolean = false
    ) {
        if (!BingwaMobileApp.wasInForegroundRecently()) return
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val handler = Handler(Looper.getMainLooper())
        handler.removeCallbacksAndMessages(relaunchToken)
        val action = Runnable {
            if (BingwaMobileApp.wasInForegroundRecently()) {
                runCatching { context.startActivity(appIntent) }
                    .onFailure { Log.w("UssdHelper", "Unable to relaunch app UI after USSD start", it) }
            }
        }
        val effectiveDelay = delayMs.coerceAtLeast(0L)
        if (effectiveDelay <= 0L) {
            handler.post(action)
        } else {
            handler.postDelayed(action, effectiveDelay)
        }
    }

    /**
     * Read the carrier's own policy for silent TelephonyManager USSD requests.
     * Android's phone process checks this flag before forwarding the request to the modem.
     */
    fun describeSilentUssdCarrierConfig(context: Context, subId: Int?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "Unavailable: silent USSD API requires Android 8.0+"
        if (subId == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return "Carrier policy unavailable: no valid subscription ID was resolved"
        }
        val manager = context.getSystemService(Context.CARRIER_CONFIG_SERVICE) as? CarrierConfigManager
            ?: return "Carrier policy unavailable: CarrierConfigManager service missing"
        val config = runCatching { manager.getConfigForSubId(subId) }.getOrNull()
            ?: return "Carrier policy unavailable: Android returned no config for selected subscription"
        val allowKey = "allow_ussd_requests_via_telephony_manager_bool"
        val allowKeyPresent = config.containsKey(allowKey)
        // AOSP PhoneInterfaceManager checks getBoolean(key), whose absent-key default is false.
        // Report both the raw key presence and the effective value used by that check.
        val effectiveAllowed = runCatching { config.getBoolean(allowKey) }.getOrNull()
        val methodKey = "carrier_ussd_method_int"
        val methodValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && config.containsKey(methodKey)) {
            runCatching { config.getInt(methodKey) }.getOrNull()
        } else null
        val method = when (methodValue) {
            0 -> "CS preferred"
            1 -> "IMS preferred"
            2 -> "CS only"
            3 -> "IMS only"
            null -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "not exposed" else "not available on this Android version"
            else -> "unknown"
        }
        val allowText = when (effectiveAllowed) {
            true -> "EFFECTIVELY ALLOWED"
            false -> if (allowKeyPresent) "BLOCKED (explicit false)" else "BLOCKED BY FRAMEWORK DEFAULT (key absent; getBoolean defaults false)"
            null -> "UNKNOWN (could not read boolean)"
        }
        val methodEvidence = if (methodValue != null) " (carrier_ussd_method_int=$methodValue)" else " (raw method unavailable)"
        return "TelephonyManager USSD policy: $allowText; allow-key-present=$allowKeyPresent; effective-allow=$effectiveAllowed; transport preference: $method$methodEvidence; config bundle present: true"
    }

    /**
     * Privacy-safe preflight snapshot for app-originated silent USSD.
     * Records only slot/service readiness, never SIM identifiers, phone numbers, or subscriber data.
     */
    fun describeSilentUssdReadiness(context: Context, subId: Int?, slotIndex: Int?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "Silent USSD API unavailable before Android 8.0"
        val callPerm = if (canPlaceCalls(context)) "granted" else "MISSING"
        val phonePerm = if (hasPhoneStatePermission(context)) "granted" else "missing"
        if (subId == null || subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return "SIM binding: no valid subscription resolved (CALL_PHONE=$callPerm, READ_PHONE_STATE=$phonePerm)"
        }
        val base = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return "TelephonyManager service unavailable"
        val bound = runCatching { base.createForSubscriptionId(subId) }.getOrNull()
            ?: return "SIM binding: Android could not create a manager for the selected subscription"
        val boundIdMatches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { bound.subscriptionId == subId }.getOrDefault(false)
        } else true
        val activeSub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasPhoneStatePermission(context)) {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            runCatching { sm?.isActiveSubscriptionId(subId) }.getOrNull()?.toString() ?: "unavailable"
        } else "not checked"
        val simState = runCatching { bound.simState }.getOrNull()?.let {
            when (it) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
                TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERMANENTLY_DISABLED"
                else -> "UNKNOWN($it)"
            }
        } ?: "unavailable"
        val serviceState = runCatching { bound.serviceState?.state }.getOrNull()?.let {
            when (it) {
                ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
                ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
                ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
                ServiceState.STATE_POWER_OFF -> "POWER_OFF"
                else -> "UNKNOWN($it)"
            }
        } ?: "unavailable"
        val voiceType = runCatching { bound.voiceNetworkType }.getOrNull()?.toString() ?: "unavailable"
        val radioDetails = runCatching {
            val currentService = bound.serviceState
            // ServiceState does not expose a stable Kotlin data-registration property
            // across the compile SDKs used by this project. Keep diagnostics on the
            // supported TelephonyManager network-type API instead.
            val dataReg = "not exposed by ServiceState"
            val roaming = currentService?.roaming?.toString() ?: "unavailable"
            val dataType = bound.dataNetworkType
            val phoneType = bound.phoneType
            val simOperatorNumeric = runCatching { bound.simOperator }.getOrNull().orEmpty().ifBlank { "unavailable" }
            val networkOperatorNumeric = runCatching { bound.networkOperator }.getOrNull().orEmpty().ifBlank { "unavailable" }
            val simOperatorName = runCatching { bound.simOperatorName }.getOrNull().orEmpty().ifBlank { "unavailable" }
            val networkOperatorName = runCatching { bound.networkOperatorName }.getOrNull().orEmpty().ifBlank { "unavailable" }
            "data service: $dataReg; data network type: $dataType; roaming: $roaming; phone type: $phoneType; SIM operator: $simOperatorName ($simOperatorNumeric); registered network operator: $networkOperatorName ($networkOperatorNumeric)"
        }.getOrElse { "additional radio state unavailable (${it.javaClass.simpleName})" }
        val carrierName = runCatching {
            bound.simOperatorName?.trim().takeUnless { it.isNullOrBlank() }
                ?: bound.networkOperatorName?.trim().takeUnless { it.isNullOrBlank() }
        }.getOrNull() ?: "unknown carrier"
        val isDefaultVoice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { SubscriptionManager.getDefaultVoiceSubscriptionId() == subId }.getOrDefault(false)
        } else true
        val slot = slotIndex?.plus(1)?.toString() ?: "unknown"
        return "SIM slot: $slot ($carrierName); default voice SIM: $isDefaultVoice; active subscription: $activeSub; binding verified: $boundIdMatches; SIM state: $simState; voice service: $serviceState; voice network type: $voiceType; $radioDetails; READ_PHONE_STATE=$phonePerm; CALL_PHONE=$callPerm"
    }

    private fun selectedTelephonyManager(context: Context, subIdOverride: Int? = null): TelephonyManager? {
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val subId = subIdOverride ?: resolvePreferredUssdSubId(context)
        if (subId != null && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        ) {
            val subTm = runCatching { baseTm.createForSubscriptionId(subId) }.getOrNull() ?: return null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val boundSubId = runCatching { subTm.subscriptionId }.getOrNull()
                if (boundSubId != subId) return null
            }
            return subTm
        }
        return if (hasPhoneStatePermission(context) && getAvailableSims(context).isNotEmpty()) null else baseTm
    }
}
