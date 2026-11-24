package com.bingwa.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun describeSilentUssdFailureCode(code: Int): String = when (code) {
    TelephonyManager.USSD_RETURN_FAILURE ->
        "USSD_RETURN_FAILURE (-1): Android reports that the USSD request failed to complete. AOSP can also map a carrier/API-policy rejection to this same code, so this code alone cannot distinguish policy rejection from telephony/radio/network failure; no carrier response text was supplied."
    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL ->
        "USSD_ERROR_SERVICE_UNAVAIL (-2): Android telephony USSD service was unavailable; Android supplied no carrier response text."
    -3 ->
        "USSD_ERROR_NOT_ALLOWED (-3): Android reports that this caller/request is not permitted; Android supplied no carrier response text."
    else ->
        "Android USSD failure code ($code); Android supplied no carrier response text."
}

object SilentUssdOptimized {
    private const val TAG = "SilentUssd"
    private const val TIMEOUT_MS = 20_000L

    @Volatile private var successCb: ((String) -> Unit)? = null
    @Volatile private var failureCb: ((String) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null
    private var watchdogRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val isProcessing = AtomicBoolean(false)
    private val requestCounter = AtomicLong(0L)
    @Volatile private var activeRequestId: Long = 0L
    @Volatile private var timeoutNotifiedRequestId: Long = 0L
    @Volatile private var observedTelephonyManager: TelephonyManager? = null
    @Volatile private var radioStateListener: PhoneStateListener? = null

    fun execute(
        context: Context,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
        preserveRawResponse: Boolean = false
    ): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: run {
                onFailure("TelephonyManager service is unavailable")
                return false
            }
        return execute(tm, ussdCode, onSuccess, onFailure, preserveRawResponse)
    }

    fun execute(
        telephonyManager: TelephonyManager,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
        preserveRawResponse: Boolean = false
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailure("Native silent USSD requires Android 8.0 (API 26) or higher")
            return false
        }

        // Do not repair or rewrite a code at the Android boundary. The caller
        // must provide the exact canonical dial string already validated upstream.
        val validationStartedAt = UssdPerformanceDiagnostics.mark()
        val code = canonicalSilentUssdDialString(ussdCode)
        UssdPerformanceDiagnostics.record(
            "silent.validation",
            validationStartedAt,
            "valid=${code != null} codeChars=${ussdCode.length} segments=${ussdCode.count { it == '*' }} unchanged=true"
        )
        if (code == null) {
            onFailure("USSD code is not canonical. Expected *digits*digits# with no spaces or extra characters.")
            return false
        }

        if (!isProcessing.compareAndSet(false, true)) {
            onFailure("USSD execution already in progress")
            return false
        }

        val requestId = requestCounter.incrementAndGet()
        UssdPerformanceDiagnostics.recordNativeRequestStarted(requestId)
        Log.d(TAG, "Starting native silent USSD request #$requestId on SDK ${Build.VERSION.SDK_INT}")

        synchronized(this) {
            successCb = onSuccess
            failureCb = onFailure
            activeRequestId = requestId
            timeoutNotifiedRequestId = 0L
            armTimeout(requestId)
            armWatchdog(requestId)
        }

        val dispatchStartedAt = UssdPerformanceDiagnostics.mark()
        UssdPerformanceDiagnostics.recordSilentTelephonySnapshot("PRE_DISPATCH", describeTelephonyRuntime(telephonyManager))
        val selectedSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { telephonyManager.subscriptionId }.getOrNull()
        } else null
        val requestProfile = "chars=${code.length} digits=${code.count(Char::isDigit)} separators=${code.count { it == '*' }} " +
            "segments=${code.count { it == '*' }} startsWithStar=${code.startsWith('*')} endsWithHash=${code.endsWith('#')} " +
            "numericFieldLengths=${code.removePrefix("*").removeSuffix("#").split('*').joinToString(",") { it.length.toString() }} " +
            "charsAfterHash=0 containsOnlyDialString=true subscriptionIdAvailable=${selectedSubscriptionId != null}"
        UssdPerformanceDiagnostics.record("silent.request_profile", detail = requestProfile)
        UssdPerformanceDiagnostics.recordSilentRequestProfile(requestProfile)
        val integrityEvidence = "inputUnchanged=${code == ussdCode} startsWithStar=${code.startsWith('*')} " +
            "endsWithHash=${code.endsWith('#')} hashCount=${code.count { it == '#' }} " +
            "finalCharacterCodePoint=${code.lastOrNull()?.code ?: -1} length=${code.length} " +
            "asciiOnly=${code.all { it.code in 0x21..0x7E }} exactCanonicalString=true"
        UssdPerformanceDiagnostics.record("silent.command_integrity", detail = integrityEvidence)
        startRadioObservation(telephonyManager, requestId)
        val launchResult = tryPublicApi(
            telephonyManager,
            code,
            requestId,
            preserveRawResponse,
            dispatchStartedAt,
            selectedSubscriptionId
        )
        if (launchResult == null) {
            UssdPerformanceDiagnostics.record(
                "silent.dispatch",
                dispatchStartedAt,
                "api=TelephonyManager.sendUssdRequest accepted=true codeChars=${code.length} segments=${code.count { it == '*' }} simBound=true retries=0"
            )
            UssdPerformanceDiagnostics.recordSilentTelephonySnapshot("API_RETURNED_ACCEPTED", describeTelephonyRuntime(telephonyManager))
            Log.d(TAG, "Native TelephonyManager.sendUssdRequest started for req #$requestId")
            return true
        }
        UssdPerformanceDiagnostics.record(
            "silent.dispatch",
            dispatchStartedAt,
            "api=TelephonyManager.sendUssdRequest accepted=false reason=${launchResult.take(48)}"
        )

        // Release lock cleanly and notify caller synchronously if sendUssdRequest threw.
        synchronized(this) {
            if (activeRequestId == requestId) {
                cancelWatchdog()
                cancelTimeout()
                successCb = null
                failureCb = null
                stopRadioObservation()
                activeRequestId = 0L
                isProcessing.set(false)
            }
        }
        UssdPerformanceDiagnostics.recordNativeRequestFinished(requestId, "SYNCHRONOUS_DISPATCH_EXCEPTION")
        runCatching { onFailure(launchResult) }
        return false
    }

    fun isSilentUssdSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.getSystemService(Context.TELEPHONY_SERVICE) is TelephonyManager
    }

    fun isExecutionInProgress(): Boolean = isProcessing.get()

    @SuppressLint("MissingPermission")
    private fun tryPublicApi(
        tm: TelephonyManager,
        code: String,
        requestId: Long,
        preserveRawResponse: Boolean,
        requestStartedAtNanos: Long,
        selectedSubscriptionId: Int?
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "Native silent USSD requires Android 8.0 (API 26) or higher"
        }
        return try {
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    val callbackSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { telephonyManager.subscriptionId }.getOrNull()
                    } else null
                    val requestEchoMatches = request == code
                    UssdPerformanceDiagnostics.recordSilentTelephonySnapshot("CALLBACK_SUCCESS", describeTelephonyRuntime(telephonyManager))
                    val bindingMatches = if (selectedSubscriptionId != null && callbackSubscriptionId != null) {
                        selectedSubscriptionId == callbackSubscriptionId
                    } else null
                    val evidence = "success requestEchoMatches=$requestEchoMatches callbackChars=${request.length} " +
                        "callbackSeparators=${request.count { it == '*' }} callbackEndsWithHash=${request.endsWith('#')} " +
                        "callbackHashCount=${request.count { it == '#' }} subscriptionBindingMatches=${bindingMatches ?: "not_available"}"
                    UssdPerformanceDiagnostics.record("silent.callback_context", detail = evidence)
                    UssdPerformanceDiagnostics.recordSilentApiEvidence("Callback context: $evidence")
                    UssdPerformanceDiagnostics.recordSilentFirstResponse(
                        response = response.toString(),
                        elapsedMs = ((SystemClock.elapsedRealtimeNanos() - requestStartedAtNanos).coerceAtLeast(0L) / 1_000_000L),
                        requestEchoMatches = requestEchoMatches,
                        subscriptionBindingMatches = bindingMatches
                    )
                    UssdPerformanceDiagnostics.record(
                        "silent.callback_wait",
                        requestStartedAtNanos,
                        "outcome=success responseChars=${response.length}"
                    )
                    Log.d(TAG, "onReceiveUssdResponse received for req #$requestId (chars=${response.length})")
                    val raw = response.toString()
                    val delivered = if (preserveRawResponse) raw else extractUssdPopupResponse(raw)
                    deliverSuccess(requestId, delivered)
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    val callbackSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching { telephonyManager.subscriptionId }.getOrNull()
                    } else null
                    val requestEchoMatches = request == code
                    UssdPerformanceDiagnostics.recordSilentTelephonySnapshot("CALLBACK_FAILURE", describeTelephonyRuntime(telephonyManager))
                    val bindingMatches = if (selectedSubscriptionId != null && callbackSubscriptionId != null) {
                        selectedSubscriptionId == callbackSubscriptionId
                    } else null
                    val category = when (failureCode) {
                        TelephonyManager.USSD_RETURN_FAILURE -> "ANDROID_GENERIC_FAILURE_CALLBACK"
                        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "ANDROID_TELEPHONY_SERVICE_UNAVAILABLE"
                        else -> "OTHER_FRAMEWORK_FAILURE"
                    }
                    val evidence = "failure category=$category code=$failureCode requestEchoMatches=$requestEchoMatches " +
                        "callbackChars=${request.length} callbackSeparators=${request.count { it == '*' }} " +
                        "callbackEndsWithHash=${request.endsWith('#')} callbackHashCount=${request.count { it == '#' }} " +
                        "subscriptionBindingMatches=${bindingMatches ?: "not_available"}"
                    UssdPerformanceDiagnostics.record("silent.callback_context", detail = evidence)
                    UssdPerformanceDiagnostics.recordSilentApiEvidence("Callback context: $evidence")
                    UssdPerformanceDiagnostics.recordSilentFirstFailure(
                        failureCode = failureCode,
                        elapsedMs = ((SystemClock.elapsedRealtimeNanos() - requestStartedAtNanos).coerceAtLeast(0L) / 1_000_000L),
                        requestEchoMatches = requestEchoMatches,
                        subscriptionBindingMatches = bindingMatches
                    )
                    UssdPerformanceDiagnostics.record(
                        "silent.callback_wait",
                        requestStartedAtNanos,
                        "outcome=failure failureCode=$failureCode"
                    )
                    Log.w(TAG, "onReceiveUssdResponseFailed for req #$requestId: failureCode=$failureCode")
                    deliverFailure(requestId, describeSilentUssdFailureCode(failureCode), failureCode)
                }
            }, handler)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "sendUssdRequest rejected by permission check: ${e.message}")
            "CALL_PHONE permission is required for TelephonyManager.sendUssdRequest()"
        } catch (e: Exception) {
            Log.w(TAG, "sendUssdRequest failed to start: ${e.javaClass.simpleName}: ${e.message}")
            "Unable to start TelephonyManager.sendUssdRequest(): ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Observe public telephony state changes while a native USSD request is unresolved.
     * These events can reveal service/technology transitions, but cannot prove that
     * the modem transmitted a USSD PDU or expose private RIL error causes.
     */
    @SuppressLint("MissingPermission")
    private fun startRadioObservation(tm: TelephonyManager, requestId: Long) {
        stopRadioObservation()
        val listener = object : PhoneStateListener() {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                UssdPerformanceDiagnostics.record(
                    "silent.radio_service_state",
                    detail = "requestId=$requestId state=${serviceState.state} roaming=${serviceState.roaming} voiceNetworkType=${runCatching { tm.voiceNetworkType }.getOrNull() ?: "unavailable"}"
                )
            }
            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                UssdPerformanceDiagnostics.record(
                    "silent.radio_data_state",
                    detail = "requestId=$requestId dataState=$state dataNetworkType=$networkType voiceNetworkType=${runCatching { tm.voiceNetworkType }.getOrNull() ?: "unavailable"}"
                )
            }
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                UssdPerformanceDiagnostics.record("silent.radio_call_state", detail = "requestId=$requestId callState=$state")
            }
        }
        try {
            tm.listen(
                listener,
                PhoneStateListener.LISTEN_SERVICE_STATE or
                    PhoneStateListener.LISTEN_DATA_CONNECTION_STATE or
                    PhoneStateListener.LISTEN_CALL_STATE
            )
            observedTelephonyManager = tm
            radioStateListener = listener
            UssdPerformanceDiagnostics.record("silent.radio_observer", detail = "requestId=$requestId registered=true events=service,data,call")
        } catch (security: SecurityException) {
            UssdPerformanceDiagnostics.record("silent.radio_observer", detail = "requestId=$requestId registered=false reason=READ_PHONE_STATE_or_vendor_restriction")
        } catch (error: Exception) {
            UssdPerformanceDiagnostics.record("silent.radio_observer", detail = "requestId=$requestId registered=false reason=${error.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopRadioObservation() {
        val listener = radioStateListener
        val tm = observedTelephonyManager
        radioStateListener = null
        observedTelephonyManager = null
        if (listener != null && tm != null) runCatching { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    /**
     * Public-API-only snapshot around the request. It cannot prove modem dispatch,
     * but it can show whether SIM/service/voice-network state changed before callback.
     * No subscription IDs, phone numbers, or USSD text are included.
     */
    private fun describeTelephonyRuntime(tm: TelephonyManager): String = runCatching {
        val sim = runCatching { tm.simState }.getOrNull()?.let { state ->
            when (state) {
                TelephonyManager.SIM_STATE_READY -> "READY"
                TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
                TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
                else -> "OTHER($state)"
            }
        } ?: "unavailable"
        val service = runCatching { tm.serviceState }.getOrNull()
        val serviceCode = service?.state
        val serviceName = when (serviceCode) {
            android.telephony.ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
            android.telephony.ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
            android.telephony.ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
            android.telephony.ServiceState.STATE_POWER_OFF -> "POWER_OFF"
            null -> "unavailable"
            else -> "OTHER($serviceCode)"
        }
        val voiceType = runCatching { tm.voiceNetworkType }.getOrNull()?.toString() ?: "unavailable"
        val dataType = runCatching { tm.dataNetworkType }.getOrNull()?.toString() ?: "unavailable"
        val callState = runCatching { tm.callState }.getOrNull()?.let { state ->
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                else -> "OTHER($state)"
            }
        } ?: "unavailable"
        "sim=$sim service=$serviceName roaming=${service?.roaming ?: "unavailable"} voiceNetworkType=$voiceType dataNetworkType=$dataType phoneType=${runCatching { tm.phoneType }.getOrNull() ?: "unavailable"} callState=$callState"
    }.getOrElse { "snapshot unavailable (${it.javaClass.simpleName})" }

    private fun extractUssdPopupResponse(rawResponse: String): String {
        if (rawResponse.isBlank()) return ""
        val trimmed = rawResponse.trim()

        val isMenuPopup = Constants.MENU_ITEM_REGEX.containsMatchIn(trimmed)
        val hasUssdKeywords = Constants.USSD_KEYWORDS.any { trimmed.lowercase().contains(it) }

        val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return trimmed

        if (lines.size <= 2 || isMenuPopup) return trimmed
        if (hasUssdKeywords) return trimmed

        val meaningfulLines = lines.filter { line ->
            line.length >= 5 &&
                (line.contains(Constants.MEANINGFUL_LINE_REGEX) ||
                    Constants.ACTIONABLE_KEYWORDS.any { line.lowercase().contains(it) })
        }

        return if (meaningfulLines.isNotEmpty()) {
            meaningfulLines.joinToString("\n")
        } else {
            lines.take(3).joinToString("\n")
        }
    }

    private object Constants {
        val MENU_ITEM_REGEX = Regex("""\d+\s*[\)\].:\-]""")
        val MEANINGFUL_LINE_REGEX = Regex("""\d""")
        val USSD_KEYWORDS = listOf(
            "success", "failed", "error", "balance", "ksh", "kes",
            "thank you", "wait", "enter", "confirm", "please", "option",
            "try again", "maintained", "process", "received", "activated",
            "bundle", "data", "airtime", "loan", "refund", "withdraw",
            "transfer", "send", "buy", "purchase", "subscription",
            "completed", "successful", "unsuccessful", "insufficient"
        )
        val ACTIONABLE_KEYWORDS = listOf(
            "success", "fail", "error", "confirm", "enter", "select", "choose",
            "amount", "balance", "ksh", "kes", "paid", "received", "sent",
            "to", "from", "account", "ref", "transaction", "id", "code"
        )
    }

    private fun deliverSuccess(requestId: Long, response: String) {
        UssdPerformanceDiagnostics.recordNativeRequestFinished(requestId, "CALLBACK_SUCCESS")
        val cb = synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD success for requestId=$requestId")
                return
            }
            cancelTimeout()
            cancelWatchdog()
            stopRadioObservation()
            val c = successCb
            successCb = null
            failureCb = null
            activeRequestId = 0L
            isProcessing.set(false)
            c
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { cb?.invoke(response) }
        } else {
            handler.post { runCatching { cb?.invoke(response) } }
        }
    }

    private fun deliverFailure(requestId: Long, reason: String, failureCode: Int? = null) {
        val outcome = when {
            reason.contains("timeout", ignoreCase = true) -> "APP_TIMEOUT_NO_CALLBACK"
            else -> "APP_FAILURE"
        }
        UssdPerformanceDiagnostics.recordNativeRequestFinished(requestId, outcome, failureCode)
        val cb = synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD failure for requestId=$requestId")
                return
            }
            cancelTimeout()
            cancelWatchdog()
            stopRadioObservation()
            val c = failureCb
            successCb = null
            failureCb = null
            activeRequestId = 0L
            isProcessing.set(false)
            c
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching { cb?.invoke(reason) }
        } else {
            handler.post { runCatching { cb?.invoke(reason) } }
        }
    }

    private fun armTimeout(requestId: Long) {
        cancelTimeout()
        val timeout = Runnable {
            Log.w(TAG, "Silent USSD request #$requestId timed out after ${TIMEOUT_MS}ms; native session remains unresolved")
            notifyTimeoutWithoutReleasingNativeRequest(requestId)
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun armWatchdog(requestId: Long) {
        cancelWatchdog()
        val watchdog = Runnable {
            if (isProcessing.get() && activeRequestId == requestId) {
                Log.w(TAG, "USSD request #$requestId is still unresolved; retaining single-flight lock")
                UssdPerformanceDiagnostics.record("silent.watchdog_unresolved", detail = "requestId=$requestId nativeRequestStillActive=true lockRetained=true automaticRetryBlocked=true")
            }
        }
        watchdogRunnable = watchdog
        handler.postDelayed(watchdog, TIMEOUT_MS + 5_000L)
    }

    /**
     * An app-side timeout is not an Android cancellation signal. Notify the caller,
     * but retain the native request identity and single-flight lock until Android
     * eventually delivers its real success/failure callback or the process exits.
     */
    private fun notifyTimeoutWithoutReleasingNativeRequest(requestId: Long) {
        val callback = synchronized(this) {
            if (activeRequestId != requestId || timeoutNotifiedRequestId == requestId) return
            timeoutNotifiedRequestId = requestId
            val current = failureCb
            failureCb = null
            current
        }
        UssdPerformanceDiagnostics.recordNativeRequestTimedOut(requestId)
        UssdPerformanceDiagnostics.record(
            "silent.timeout_unresolved",
            detail = "requestId=$requestId appTimeoutMs=$TIMEOUT_MS nativeCancellationAvailable=false lockRetained=true"
        )
        runCatching {
            callback?.invoke(
                "Bingwa's response wait timed out after ${TIMEOUT_MS}ms, but Android has not confirmed that the native USSD session ended. " +
                    "Automatic retries are blocked until Android returns a callback; restart the phone if it remains unresolved."
            )
        }
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }


}


/**
 * Returns the original code only when it is already a pure Android dial string.
 * This deliberately does not trim, decode, append '#', or remove characters.
 */
internal fun canonicalSilentUssdDialString(raw: String): String? {
    if (raw.length !in 3..50) return null
    return raw.takeIf { Regex("""^\*[0-9]+(?:\*[0-9]+)*#$""").matches(it) }
}
