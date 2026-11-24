package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.content.res.Configuration
import java.util.ArrayDeque
import java.util.Locale

/**
 * Low-overhead in-memory USSD performance trace.
 *
 * The hot path only records primitive timestamps and short stage labels. It never writes
 * files, preferences, Logcat, or performs I/O while a USSD popup is being navigated.
 * The bounded ring buffer is formatted only when the user taps Copy report.
 */
internal object UssdPerformanceDiagnostics {
    private const val MAX_ENTRIES = 500

    private data class Entry(
        val elapsedMs: Long,
        val stage: String,
        val durationMs: Double?,
        val detail: String
    )

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    @Volatile private var silentSignatureMenuReport: String =
        "No silent Signature Management Live Menu request has been captured yet."
    @Volatile private var lastSilentSimRouting: String = "not captured"
    @Volatile private var lastSilentTelephonyReadiness: String = "not captured"
    @Volatile private var lastSilentCarrierConfig: String = "not captured"
    @Volatile private var lastSilentSourceSummary: String = "not captured"
    @Volatile private var lastSilentAndroidSdk: Int = -1
    @Volatile private var silentUssdApiEvidence: String = "No native callback evidence captured yet."
    @Volatile private var silentRequestProfileEvidence: String = "No silent request profile captured yet."
    @Volatile private var silentFirstResponseEvidence: String = "No callback received yet."
    @Volatile private var silentPreDispatchSnapshot: String = "not captured"
    @Volatile private var silentAcceptedSnapshot: String = "not captured"
    @Volatile private var silentCallbackSnapshot: String = "not captured"

    // Tracks only Bingwa requests in this process. Android does not expose the
    // system-wide pending MMI session through the public Telephony API.
    private val processStartedAtElapsedMs = SystemClock.elapsedRealtime()
    private val nativeLifecycleLock = Any()
    private var nativeRequestsStartedThisProcess = 0
    private var lastNativeRequestStartedAtElapsedMs = 0L
    private var lastNativeRequestId = 0L
    private var activeNativeLifecycleRequestId = 0L
    private var lastNativeRequestOutcome = "NONE"
    private var lastNativeRequestFailureCode: Int? = null

    fun recordNativeRequestStarted(requestId: Long) {
        synchronized(nativeLifecycleLock) {
            nativeRequestsStartedThisProcess++
            lastNativeRequestStartedAtElapsedMs = SystemClock.elapsedRealtime()
            lastNativeRequestId = requestId
            activeNativeLifecycleRequestId = requestId
            lastNativeRequestOutcome = "IN_PROGRESS"
            lastNativeRequestFailureCode = null
        }
        record("silent.lifecycle_start", detail = "requestId=$requestId processRequestNumber=$nativeRequestsStartedThisProcess")
    }

    fun recordNativeRequestTimedOut(requestId: Long) {
        val accepted = synchronized(nativeLifecycleLock) {
            if (activeNativeLifecycleRequestId != requestId) false else {
                lastNativeRequestOutcome = "APP_TIMEOUT_UNRESOLVED"
                true
            }
        }
        if (accepted) record("silent.lifecycle_timeout", detail = "requestId=$requestId outcome=APP_TIMEOUT_UNRESOLVED nativeRequestStillActive=true")
    }

    fun recordNativeRequestFinished(requestId: Long, outcome: String, failureCode: Int? = null) {
        val accepted = synchronized(nativeLifecycleLock) {
            if (activeNativeLifecycleRequestId != requestId) false else {
                activeNativeLifecycleRequestId = 0L
                lastNativeRequestOutcome = outcome
                lastNativeRequestFailureCode = failureCode
                true
            }
        }
        if (accepted) record("silent.lifecycle_finish", detail = "requestId=$requestId outcome=$outcome failureCode=${failureCode ?: "none"}")
    }

    private fun nativeRequestLifecycleSummary(): String = synchronized(nativeLifecycleLock) {
        val now = SystemClock.elapsedRealtime()
        val processAge = (now - processStartedAtElapsedMs).coerceAtLeast(0L)
        val sinceLastStart = if (lastNativeRequestStartedAtElapsedMs > 0L) (now - lastNativeRequestStartedAtElapsedMs).coerceAtLeast(0L) else null
        buildString {
            appendLine("Device uptime since boot: ${now}ms")
            appendLine("App process age: ${processAge}ms")
            appendLine("Native requests started in this app process: $nativeRequestsStartedThisProcess")
            appendLine("Last app request sequence ID: ${if (lastNativeRequestId > 0L) lastNativeRequestId else "none"}")
            appendLine("Time since last app request started: ${sinceLastStart?.let { "${it}ms" } ?: "none"}")
            appendLine("Last app request outcome: $lastNativeRequestOutcome")
            appendLine("Last Android callback failure code: ${lastNativeRequestFailureCode ?: "none"}")
            appendLine("Native request currently active in Bingwa: ${activeNativeLifecycleRequestId != 0L}")
            appendLine("Unresolved timeout policy: app timeout does not cancel Android USSD; single-flight lock remains held until a real Android callback")
            appendLine("System-wide pending MMI/USSD session: UNKNOWN — not exposed by the public Android Telephony API to this app")
            appendLine("FDN state: NOT COLLECTED — verify in SIM settings or authorized platform diagnostics")
            appendLine("Radio/RIL transmission evidence: NOT AVAILABLE through ordinary app APIs; controlled ADB radio log capture is required")
        }.trimEnd()
    }

    fun recordSilentTelephonySnapshot(stage: String, detail: String) {
        val safe = detail.replace(Regex("[\\r\\n]+"), " ").take(700)
        when (stage) {
            "PRE_DISPATCH" -> silentPreDispatchSnapshot = safe
            "API_RETURNED_ACCEPTED" -> silentAcceptedSnapshot = safe
            else -> silentCallbackSnapshot = "$stage: $safe"
        }
    }

    fun recordSilentRequestProfile(detail: String) {
        silentRequestProfileEvidence = detail.replace(Regex("[\\r\\n]+"), " ").take(500)
    }

    fun recordSilentApiEvidence(detail: String) {
        silentUssdApiEvidence = detail.replace(Regex("[\\r\\n]+"), " ").take(500)
    }

    fun recordSilentFirstResponse(
        response: String,
        elapsedMs: Long,
        requestEchoMatches: Boolean,
        subscriptionBindingMatches: Boolean?
    ) {
        val options = runCatching { SimpleSignatureVerifier.menuOptions(response).size }.getOrDefault(0)
        val lower = response.lowercase(Locale.ROOT)
        val kind = when {
            response.isBlank() -> "EMPTY RESPONSE"
            SimpleSignatureVerifier.isCarrierRetryLimitResponse(response) -> "CARRIER RETRY-LIMIT MESSAGE"
            options > 0 -> "CARRIER MENU"
            listOf("enter", "reply", "select", "choose", "confirm", "option").any { lower.contains(it) } -> "CARRIER INPUT/PROMPT"
            listOf("failed", "failure", "error", "not allowed", "unavailable", "invalid").any { lower.contains(it) } -> "CARRIER ERROR MESSAGE"
            else -> "CARRIER TEXT RESPONSE (UNCLASSIFIED)"
        }
        silentFirstResponseEvidence = "First app-visible response: $kind; callback delay: ${elapsedMs}ms; response characters: ${response.length}; parsed menu options: $options; request echo matches: $requestEchoMatches; subscription binding matches: ${subscriptionBindingMatches ?: "not available"}"
    }

    fun recordSilentFirstFailure(
        failureCode: Int,
        elapsedMs: Long,
        requestEchoMatches: Boolean,
        subscriptionBindingMatches: Boolean?
    ) {
        val category = when (failureCode) {
            android.telephony.TelephonyManager.USSD_RETURN_FAILURE -> "USSD_RETURN_FAILURE (-1): Android reports that the USSD request failed to complete. AOSP can also map a carrier/API-policy rejection to this same code, so this code alone cannot distinguish policy rejection from telephony/radio/network failure; no carrier response text was supplied."
            android.telephony.TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD_ERROR_SERVICE_UNAVAIL (-2): Android telephony USSD service unavailable"
            -3 -> "USSD_ERROR_NOT_ALLOWED (-3): Android rejected the request as not permitted"
            else -> "Unclassified Android USSD failure ($failureCode); Android public API does not define a more specific callback category for this value"
        }
        silentFirstResponseEvidence = "First app-visible callback: FAILURE; callback delay: ${elapsedMs}ms; result: $category; request echo matches: $requestEchoMatches; subscription binding matches: ${subscriptionBindingMatches ?: "not available"}; carrier response text: NOT PROVIDED BY ANDROID"
    }

    fun latestSilentSignatureMenuReport(): String = silentSignatureMenuReport

    /** Keep the latest silent Live Menu attempt for Setup Doctor & Live Menu diagnostics. */
    fun recordSilentSignatureMenuStarted(
        simDescription: String,
        sdk: Int,
        carrierConfig: String = "not inspected",
        telephonyReadiness: String = "not inspected",
        previousMenuRetained: Boolean = false,
        sourceSummary: String = "not captured"
    ) {
        lastSilentSimRouting = simDescription
        lastSilentTelephonyReadiness = telephonyReadiness
        lastSilentCarrierConfig = carrierConfig
        lastSilentSourceSummary = sourceSummary
        lastSilentAndroidSdk = sdk
        silentPreDispatchSnapshot = "not captured"
        silentAcceptedSnapshot = "not captured"
        silentCallbackSnapshot = "not captured"
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
            appendLine("Menu source (redacted): $lastSilentSourceSummary")
            appendLine("Selected SIM slot & status: $simDescription")
            appendLine("Telephony readiness: $telephonyReadiness")
            appendLine("Carrier telephony USSD policy: $carrierConfig")
            appendLine("Android SDK: $sdk")
            appendLine("Duration: in progress")
            appendLine("Callback outcome: REQUEST STARTED")
            appendLine("Raw response text received: Pending")
            appendLine("Parsed menu options count: 0")
            appendLine("Carrier retry-limit response: No")
            appendLine("Previous valid menu retained: ${if (previousMenuRetained) "Yes" else "No"}")
            appendLine("Blocked because request already running: No")
            appendLine("Request shape (no values): $silentRequestProfileEvidence")
            appendLine("Native callback/API evidence: $silentUssdApiEvidence")
            appendLine("First callback classification: $silentFirstResponseEvidence")
            appendLine("Telephony state before request: $silentPreDispatchSnapshot")
            appendLine("Telephony state after API acceptance: $silentAcceptedSnapshot")
            appendLine("Telephony state at callback: $silentCallbackSnapshot")
            appendLine("Request code & phone number: redacted")
        }
    }

    fun recordSilentSignatureMenuSuccess(
        response: String,
        optionCount: Int,
        elapsedMs: Long,
        isCarrierRetryLimit: Boolean = SimpleSignatureVerifier.isCarrierRetryLimitResponse(response),
        previousMenuRetained: Boolean = false
    ) {
        val outcome = when {
            isCarrierRetryLimit -> "CALLBACK SUCCESS · CARRIER RETRY LIMIT"
            optionCount > 0 -> "CALLBACK SUCCESS · VALID MENU"
            else -> "CALLBACK SUCCESS · NO PARSED OPTIONS"
        }
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
            appendLine("Menu source (redacted): $lastSilentSourceSummary")
            appendLine("Selected SIM slot & status: $lastSilentSimRouting")
            appendLine("Telephony readiness: $lastSilentTelephonyReadiness")
            appendLine("Carrier telephony USSD policy: $lastSilentCarrierConfig")
            appendLine("Android SDK: $lastSilentAndroidSdk")
            appendLine("Duration: $elapsedMs ms")
            appendLine("Callback outcome: $outcome")
            appendLine(
                "Raw response text received: " +
                    if (response.isNotEmpty()) "Yes (${response.length} chars)" else "No (0 chars)"
            )
            appendLine("Parsed menu options count: $optionCount")
            appendLine("Carrier retry-limit response: ${if (isCarrierRetryLimit) "Yes" else "No"}")
            appendLine("Previous valid menu retained: ${if (previousMenuRetained) "Yes" else "No"}")
            appendLine("Blocked because request already running: No")
            appendLine("Request shape (no values): $silentRequestProfileEvidence")
            appendLine("Native callback/API evidence: $silentUssdApiEvidence")
            appendLine("First callback classification: $silentFirstResponseEvidence")
            appendLine("Telephony state before request: $silentPreDispatchSnapshot")
            appendLine("Telephony state after API acceptance: $silentAcceptedSnapshot")
            appendLine("Telephony state at callback: $silentCallbackSnapshot")
            appendLine("Carrier response contents: REDACTED — diagnostic exports never include carrier text.")
            appendLine("Carrier response metadata: chars=${response.length}; parsedOptions=$optionCount; classification=$outcome")
        }
    }

    fun recordSilentSignatureMenuFailure(
        error: String,
        elapsedMs: Long,
        requestStarted: Boolean,
        previousMenuRetained: Boolean = false
    ) {
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
            appendLine("Menu source (redacted): $lastSilentSourceSummary")
            appendLine("Selected SIM slot & status: $lastSilentSimRouting")
            appendLine("Telephony readiness: $lastSilentTelephonyReadiness")
            appendLine("Carrier telephony USSD policy: $lastSilentCarrierConfig")
            appendLine("Android SDK: $lastSilentAndroidSdk")
            appendLine("Duration: $elapsedMs ms")
            val isAppTimeout = error.contains("response wait timed out", ignoreCase = true)
            appendLine("Callback outcome: ${when {
                !requestStarted -> "REQUEST COULD NOT START"
                isAppTimeout -> "APP TIMEOUT · NATIVE REQUEST STILL UNRESOLVED"
                else -> "ANDROID CALLBACK FAILURE"
            }}")
            appendLine("Raw response text received: No — Android failure callback provides no carrier response text")
            appendLine("Parsed menu options count: 0")
            appendLine("Carrier retry-limit response: No")
            appendLine("Previous valid menu retained: ${if (previousMenuRetained) "Yes" else "No"}")
            appendLine("Blocked because request already running: No")
            appendLine("Request shape (no values): $silentRequestProfileEvidence")
            appendLine("Native callback/API evidence: $silentUssdApiEvidence")
            appendLine("First callback classification: $silentFirstResponseEvidence")
            appendLine("Telephony state before request: $silentPreDispatchSnapshot")
            appendLine("Telephony state after API acceptance: $silentAcceptedSnapshot")
            appendLine("Telephony state at callback: $silentCallbackSnapshot")
            appendLine("Failure detail: $error")
        }
    }

    fun recordSilentSignatureMenuBlocked(
        reason: String,
        simDescription: String,
        carrierConfig: String,
        telephonyReadiness: String,
        previousMenuRetained: Boolean,
        sourceSummary: String = lastSilentSourceSummary
    ) {
        lastSilentSimRouting = simDescription
        lastSilentTelephonyReadiness = telephonyReadiness
        lastSilentCarrierConfig = carrierConfig
        lastSilentSourceSummary = sourceSummary
        lastSilentAndroidSdk = Build.VERSION.SDK_INT
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
            appendLine("Menu source (redacted): $lastSilentSourceSummary")
            appendLine("Selected SIM slot & status: $simDescription")
            appendLine("Telephony readiness: $telephonyReadiness")
            appendLine("Carrier telephony USSD policy: $carrierConfig")
            appendLine("Android SDK: $lastSilentAndroidSdk")
            appendLine("Duration: 0 ms")
            appendLine("Callback outcome: BLOCKED · REQUEST ALREADY RUNNING")
            appendLine("Raw response text received: No — request blocked before sending")
            appendLine("Parsed menu options count: 0")
            appendLine("Carrier retry-limit response: No")
            appendLine("Previous valid menu retained: ${if (previousMenuRetained) "Yes" else "No"}")
            appendLine("Blocked because request already running: Yes")
            appendLine("Request shape (no values): $silentRequestProfileEvidence")
            appendLine("Native callback/API evidence: $silentUssdApiEvidence")
            appendLine("First callback classification: $silentFirstResponseEvidence")
            appendLine("Telephony state before request: $silentPreDispatchSnapshot")
            appendLine("Telephony state after API acceptance: $silentAcceptedSnapshot")
            appendLine("Telephony state at callback: $silentCallbackSnapshot")
            appendLine("Detail: $reason")
        }
    }
    // Accessibility callbacks can be extremely chatty. Preserve useful USSD evidence
    // by allowing transaction timings to displace routine callback noise when full.
    private var suppressedSelfAccessibilityEvents = 0L
    private var droppedLowPriorityEvents = 0L
    private var evictedLowerPriorityEvents = 0L
    private val responseWaitLock = Any()
    private var pendingResponseStartedAtNanos = 0L
    private var firstPopupAfterSendRecorded = false

    /** Start carrier/UI response timing only after Android accepts a Send action. */
    fun markSubmissionAccepted(path: String) {
        synchronized(responseWaitLock) {
            pendingResponseStartedAtNanos = SystemClock.elapsedRealtimeNanos()
            firstPopupAfterSendRecorded = false
        }
        record("send.accepted", detail = "path=$path")
    }

    /**
     * Separates carrier/dialer waiting from local automation work. The first popup can
     * be a transient "please wait" screen; usable-response timing ends only when a
     * non-transient USSD dialog arrives. No menu contents or entered values are stored.
     */
    fun markPopupObserved(isTransient: Boolean, characterCount: Int) {
        val now = SystemClock.elapsedRealtimeNanos()
        val startedAt: Long
        val recordFirst: Boolean
        synchronized(responseWaitLock) {
            startedAt = pendingResponseStartedAtNanos
            if (startedAt <= 0L) return
            recordFirst = !firstPopupAfterSendRecorded
            if (recordFirst) firstPopupAfterSendRecorded = true
            if (!isTransient) pendingResponseStartedAtNanos = 0L
        }
        val classification = if (isTransient) "transient" else "usable"
        if (recordFirst) {
            record(
                "carrier.first_popup_wait",
                startedAt,
                "popup=$classification chars=$characterCount"
            )
        }
        if (!isTransient) {
            record(
                "carrier.usable_response_wait",
                startedAt,
                "popup=usable chars=$characterCount"
            )
        }
    }

    fun mark(): Long = SystemClock.elapsedRealtimeNanos()

    fun record(stage: String, startedAtNanos: Long? = null, detail: String = "") {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val duration = startedAtNanos?.let {
            ((nowNanos - it).coerceAtLeast(0L) / 1_000_000.0)
        }
        val safeDetail = detail
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(100)

        // Bingwa's own accessibility UI events are not USSD evidence and can flood
        // the trace during foreground transitions.
        if (stage.startsWith("accessibility.event") && safeDetail.contains("pkg=com.bingwa.mobile")) {
            synchronized(entries) { suppressedSelfAccessibilityEvents++ }
            return
        }

        val entry = Entry(
            elapsedMs = SystemClock.elapsedRealtime(),
            stage = stage.take(48),
            durationMs = duration,
            detail = safeDetail
        )
        synchronized(entries) {
            if (entries.size >= MAX_ENTRIES) {
                val incomingPriority = tracePriority(entry.stage)
                // Prefer evicting routine callback noise over transaction evidence.
                val lowerPriorityEntry = entries.firstOrNull {
                    tracePriority(it.stage) < incomingPriority
                }
                if (lowerPriorityEntry != null) {
                    entries.remove(lowerPriorityEntry)
                    evictedLowerPriorityEvents++
                } else if (incomingPriority >= tracePriority(entries.first.stage)) {
                    // Evict oldest entry of equal or lower priority to maintain a fresh rolling trace
                    entries.removeFirst()
                    evictedLowerPriorityEvents++
                } else {
                    droppedLowPriorityEvents++
                    return
                }
            }
            entries.addLast(entry)
        }
    }

    private fun tracePriority(stage: String): Int = when {
        stage.startsWith("input.") ||
            stage.startsWith("send.") ||
            stage.startsWith("carrier.") ||
            stage.startsWith("field.") ||
            stage == "total.local_processing" ||
            stage == "process.total" ||
            stage == "step.resolve" -> 3

        stage.startsWith("root.") ||
            stage.startsWith("snapshot.") ||
            stage.startsWith("popup.") ||
            stage.startsWith("event.root_") ||
            stage.startsWith("event.source_lookup") -> 2

        stage == "accessibility.event" ||
            stage == "accessibility.event_coalesced" ||
            stage == "keyboard.window_changed" -> 0

        else -> 1
    }

    /** Privacy-safe diagnostic stages for USSD signature learning & matching */
    fun recordLearningInitiated(offerId: Int, stepCount: Int) {
        record("learning.initiated", detail = "offerId=$offerId steps=$stepCount")
    }

    fun recordLearningWindowFound(windowPkg: String, windowId: Int, isDialog: Boolean) {
        record("learning.window_found", detail = "pkg=$windowPkg windowId=$windowId isDialog=$isDialog")
    }

    fun recordLearningWindowRejected(windowPkg: String, windowId: Int, reason: String) {
        record("learning.window_rejected", detail = "pkg=$windowPkg windowId=$windowId reason=$reason")
    }

    fun recordLearningExtractionAttempted(stepIndex: Int, isStringStep: Boolean) {
        record("learning.extraction_attempted", detail = "step=$stepIndex isString=$isStringStep")
    }

    fun recordLearningTextExtracted(tokenCount: Int, charCount: Int, hasMenu: Boolean) {
        record("learning.text_extracted", detail = "tokens=$tokenCount chars=$charCount hasMenu=$hasMenu")
    }

    fun recordLearningNormalization(stepIndex: Int, rawChars: Int, normalizedChars: Int) {
        record("learning.text_normalized", detail = "step=$stepIndex rawChars=$rawChars normChars=$normalizedChars")
    }

    fun recordLearningOptionsIdentified(stepIndex: Int, validOptionsCount: Int, menuTitleFound: Boolean) {
        record("learning.options_identified", detail = "step=$stepIndex options=$validOptionsCount hasTitle=$menuTitleFound")
    }

    fun recordLearningSignatureGenerated(stepIndex: Int, optionCount: Int, hasLabel: Boolean) {
        record("learning.signature_generated", detail = "step=$stepIndex options=$optionCount hasLabel=$hasLabel")
    }

    fun recordLearningSignatureStored(offerId: Int, stepsCount: Int, capturesCount: Int) {
        record("learning.signature_stored", detail = "offerId=$offerId steps=$stepsCount captures=$capturesCount")
    }

    fun recordLearningSignatureRetrieved(offerId: Int, stepsCount: Int) {
        record("learning.signature_retrieved", detail = "offerId=$offerId steps=$stepsCount")
    }

    fun recordLearningSignatureMatched(stepIndex: Int, matchedOption: String, autoAdjust: Boolean) {
        record("learning.signature_matched", detail = "step=$stepIndex matchedOption=$matchedOption autoAdjust=$autoAdjust")
    }

    fun recordLearningNavigationAction(stepIndex: Int, action: String, targetOption: String) {
        record("learning.navigation_action", detail = "step=$stepIndex action=$action targetOption=$targetOption")
    }

    fun getRecordedDetails(): List<String> = synchronized(entries) { entries.map { it.detail } }

    fun buildReport(context: Context): String {
        val snapshot = synchronized(entries) { entries.toList() }
        return buildString {
            appendLine("Bingwa Mobile USSD performance report")
            val metrics = context.resources.displayMetrics
            val config = context.resources.configuration
            val screenType = when {
                config.smallestScreenWidthDp >= 600 -> "tablet/large-screen"
                (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE -> "large-screen"
                else -> "phone"
            }
            val appInfo = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                appInfo?.longVersionCode ?: -1L
            } else {
                @Suppress("DEPRECATION")
                (appInfo?.versionCode ?: -1).toLong()
            }
            appendLine("DEVICE IDENTITY")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Device codename: ${Build.DEVICE}")
            appendLine("Product: ${Build.PRODUCT}")
            appendLine("Board: ${Build.BOARD}")
            appendLine("Hardware: ${Build.HARDWARE}")
            appendLine("Device type: $screenType")
            appendLine("Screen: ${metrics.widthPixels}x${metrics.heightPixels} px, ${metrics.densityDpi} dpi, density=${String.format(Locale.US, "%.2f", metrics.density)}")
            appendLine("Supported ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine()
            appendLine("ANDROID / OEM SOFTWARE")
            appendLine("Android version: ${Build.VERSION.RELEASE}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Android codename: ${Build.VERSION.CODENAME}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
                appendLine("Base OS: ${Build.VERSION.BASE_OS.ifBlank { "not reported" }}")
            }
            appendLine("OEM build/display (may identify XOS/HiOS/One UI version): ${Build.DISPLAY}")
            appendLine("Build incremental: ${Build.VERSION.INCREMENTAL}")
            appendLine("Build fingerprint: ${Build.FINGERPRINT}")
            appendLine()
            appendLine("APP")
            appendLine("App package: ${context.packageName}")
            appendLine("App version: ${appInfo?.versionName ?: "unknown"} (code $appVersionCode)")
            appendLine("Capture: in-memory priority-preserving ring buffer; ${snapshot.size}/$MAX_ENTRIES events retained")
            val bufferStats = synchronized(entries) {
                Triple(suppressedSelfAccessibilityEvents, droppedLowPriorityEvents, evictedLowerPriorityEvents)
            }
            appendLine("Diagnostics buffer: self-generated accessibility events suppressed=${bufferStats.first}, low-priority events dropped=${bufferStats.second}, lower-priority events displaced=${bufferStats.third}")
            appendLine("Privacy: no serial number, IMEI, phone numbers, PINs, USSD menu text or entered values are recorded.")
            appendLine()
            appendLine("NATIVE USSD SESSION / CLEAN-TEST CORRELATION")
            appendLine(nativeRequestLifecycleSummary())
            appendLine("Interpretation: this tracks only Bingwa requests in the current app process. It cannot prove that Android has no pending MMI session created by the dialer, another app, or an earlier process.")
            appendLine()
            appendLine("SIGNATURE MANAGEMENT · SILENT LIVE MENU CAPTURE")
            appendLine(silentSignatureMenuReport)
            appendLine()
            appendLine("FIRST CARRIER RESPONSE / FIRST APP-VISIBLE CALLBACK")
            appendLine(silentFirstResponseEvidence)
            appendLine("Important: sendUssdRequest exposes the completed response callback, not intermediate modem signalling. If Android calls failure without text, the exact carrier-side first message is unavailable to this app.")
            appendLine()
            appendLine("SILENT USSD API EVIDENCE")
            appendLine(silentUssdApiEvidence)
            appendLine("Privacy: request contents, subscription identifiers, phone numbers, and raw carrier response text are excluded from diagnostic exports.")
            appendLine()
            appendLine("ADAPTIVE SEND LEARNING")
            appendLine(UssdAdaptiveSendMemory(context).buildReportSummary())
            appendLine()
            appendLine("STAGE MEASUREMENTS")
            appendLine("Stage | Average | Maximum")
            val stageAliases = listOf(
                "Snapshot traversal" to setOf("snapshot.traversal"),
                "Popup snapshot capture" to setOf("popup.snapshot_capture"),
                "Root acquisition" to setOf("event.root_acquire", "root.window_root_retrieval", "root.source_lookup"),
                "Field discovery" to setOf("field.discovery", "field.tree_scan", "field.focused_lookup", "field.common_id_lookup"),
                "Input" to setOf("input.entry"),
                "Send activation" to setOf("send.activation", "send.discovery"),
                "Silent USSD validation" to setOf("silent.validation"),
                "SIM resolution / binding" to setOf("silent.sim_resolution", "silent.sim_binding"),
                "Native dial dispatch" to setOf("silent.dispatch"),
                "Carrier callback wait" to setOf("silent.callback_wait"),
                "Response parsing" to setOf("silent.response_parse"),
                "Send acceptance" to setOf("send.accepted", "send.method_accepted"),
                "Total processing" to setOf("total.local_processing"),
                "Carrier usable response wait" to setOf("carrier.usable_response_wait")
            )
            stageAliases.forEach { (label, stageKeys) ->
                val matching = snapshot.filter { it.durationMs != null && it.stage in stageKeys }
                if (matching.isNotEmpty()) {
                    val values = matching.mapNotNull { it.durationMs }
                    val avg = values.average()
                    val max = values.maxOrNull() ?: 0.0
                    appendLine(String.format(Locale.US, "%-28s| %8.2f ms| %8.2f ms", label, avg, max))
                }
            }
            appendLine()
            appendLine("STAGE SUMMARY (milliseconds)")
            appendLine("Timing note: stages can overlap; accessibility.event includes work performed inside its callback. Do not add stage averages together.")
            val timed = snapshot.filter { it.durationMs != null }.groupBy { it.stage }
            if (timed.isEmpty()) appendLine("No timed events captured yet.")
            timed.forEach { (stage, items) ->
                val values = items.mapNotNull { it.durationMs }.sorted()
                val average = values.average()
                val p95 = values[(kotlin.math.ceil(values.size * 0.95).toInt() - 1).coerceIn(0, values.lastIndex)]
                appendLine(
                    String.format(
                        Locale.US,
                        "%s count=%d avg=%.2f p95=%.2f max=%.2f",
                        stage, values.size, average, p95, values.last()
                    )
                )
            }
            appendLine()
            appendLine("EVENT TRACE (elapsed ms | stage | duration ms | detail)")
            snapshot.forEach { entry ->
                val duration = entry.durationMs?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
                appendLine("${entry.elapsedMs} | ${entry.stage} | $duration | ${entry.detail}")
            }
            if (snapshot.isEmpty()) {
                appendLine("No USSD performance events captured. Run one automated USSD session, then copy this report.")
            }
        }
    }
}
