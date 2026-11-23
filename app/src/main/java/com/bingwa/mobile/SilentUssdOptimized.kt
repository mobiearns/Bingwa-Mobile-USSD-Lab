package com.bingwa.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun describeSilentUssdFailureCode(code: Int): String = when (code) {
    TelephonyManager.USSD_RETURN_FAILURE ->
        "USSD_RETURN_FAILURE (-1): Android could not complete the silent USSD request and supplied no carrier response text in onReceiveUssdResponseFailed(). If manual dialling works, verify SIM subscription binding, voice network service, and carrier support for app-originated TelephonyManager.sendUssdRequest()."
    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL ->
        "USSD_ERROR_SERVICE_UNAVAIL (-2): Android telephony USSD service was unavailable; Android supplied no carrier response text."
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
        Log.d(TAG, "Starting native silent USSD request #$requestId on SDK ${Build.VERSION.SDK_INT}")

        synchronized(this) {
            successCb = onSuccess
            failureCb = onFailure
            activeRequestId = requestId
            armTimeout(requestId)
            armWatchdog(requestId)
        }

        val dispatchStartedAt = UssdPerformanceDiagnostics.mark()
        val selectedSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { telephonyManager.subscriptionId }.getOrNull()
        } else null
        val requestProfile = "chars=${code.length} digits=${code.count(Char::isDigit)} separators=${code.count { it == '*' }} " +
            "segments=${code.count { it == '*' }} startsWithStar=${code.startsWith('*')} endsWithHash=${code.endsWith('#')} " +
            "subscriptionIdAvailable=${selectedSubscriptionId != null}"
        UssdPerformanceDiagnostics.record("silent.request_profile", detail = requestProfile)
        UssdPerformanceDiagnostics.recordSilentApiEvidence("Request profile: $requestProfile")
        val launchResult = tryPublicApi(
            telephonyManager,
            code,
            requestId,
            preserveRawResponse,
            dispatchStartedAt
        )
        if (launchResult == null) {
            UssdPerformanceDiagnostics.record(
                "silent.dispatch",
                dispatchStartedAt,
                "api=TelephonyManager.sendUssdRequest accepted=true codeChars=${code.length} segments=${code.count { it == '*' }} simBound=true retries=0"
            )
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
                activeRequestId = 0L
                isProcessing.set(false)
            }
        }
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
        requestStartedAtNanos: Long
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
                    val bindingMatches = if (selectedSubscriptionId != null && callbackSubscriptionId != null) {
                        selectedSubscriptionId == callbackSubscriptionId
                    } else null
                    val evidence = "success requestEchoMatches=$requestEchoMatches callbackChars=${request.length} " +
                        "callbackSeparators=${request.count { it == '*' }} subscriptionBindingMatches=${bindingMatches ?: "not_available"}"
                    UssdPerformanceDiagnostics.record("silent.callback_context", detail = evidence)
                    UssdPerformanceDiagnostics.recordSilentApiEvidence("Callback context: $evidence")
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
                    val bindingMatches = if (selectedSubscriptionId != null && callbackSubscriptionId != null) {
                        selectedSubscriptionId == callbackSubscriptionId
                    } else null
                    val category = when (failureCode) {
                        TelephonyManager.USSD_RETURN_FAILURE -> "NETWORK_OR_MODEM_REPORTED_FAILURE"
                        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "ANDROID_TELEPHONY_SERVICE_UNAVAILABLE"
                        else -> "OTHER_FRAMEWORK_FAILURE"
                    }
                    val evidence = "failure category=$category code=$failureCode requestEchoMatches=$requestEchoMatches " +
                        "callbackChars=${request.length} callbackSeparators=${request.count { it == '*' }} " +
                        "subscriptionBindingMatches=${bindingMatches ?: "not_available"}"
                    UssdPerformanceDiagnostics.record("silent.callback_context", detail = evidence)
                    UssdPerformanceDiagnostics.recordSilentApiEvidence("Callback context: $evidence")
                    UssdPerformanceDiagnostics.record(
                        "silent.callback_wait",
                        requestStartedAtNanos,
                        "outcome=failure failureCode=$failureCode"
                    )
                    Log.w(TAG, "onReceiveUssdResponseFailed for req #$requestId: failureCode=$failureCode")
                    deliverFailure(requestId, describeSilentUssdFailureCode(failureCode))
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
        val cb = synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD success for requestId=$requestId")
                return
            }
            cancelTimeout()
            cancelWatchdog()
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

    private fun deliverFailure(requestId: Long, reason: String) {
        val cb = synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD failure for requestId=$requestId")
                return
            }
            cancelTimeout()
            cancelWatchdog()
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
            Log.w(TAG, "Silent USSD request #$requestId timed out after ${TIMEOUT_MS}ms")
            deliverFailure(requestId, "Timeout waiting for USSD response on a weak or delayed network")
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun armWatchdog(requestId: Long) {
        cancelWatchdog()
        val watchdog = Runnable {
            if (isProcessing.get() && activeRequestId == requestId) {
                Log.w(TAG, "Watchdog resetting stuck USSD lock for requestId=$requestId")
                deliverFailure(requestId, "Watchdog reset: USSD request timed out")
            }
        }
        watchdogRunnable = watchdog
        handler.postDelayed(watchdog, TIMEOUT_MS + 5_000L)
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
