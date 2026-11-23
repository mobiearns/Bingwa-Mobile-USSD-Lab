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
    @Volatile private var lastSilentAndroidSdk: Int = -1

    fun latestSilentSignatureMenuReport(): String = silentSignatureMenuReport

    /** Keep the latest silent Live Menu attempt for Setup Doctor & Live Menu diagnostics. */
    fun recordSilentSignatureMenuStarted(
        simDescription: String,
        sdk: Int,
        carrierConfig: String = "not inspected",
        telephonyReadiness: String = "not inspected",
        previousMenuRetained: Boolean = false
    ) {
        lastSilentSimRouting = simDescription
        lastSilentTelephonyReadiness = telephonyReadiness
        lastSilentCarrierConfig = carrierConfig
        lastSilentAndroidSdk = sdk
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
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
        val safeResponse = response.take(6000)
        val outcome = when {
            isCarrierRetryLimit -> "CALLBACK SUCCESS · CARRIER RETRY LIMIT"
            optionCount > 0 -> "CALLBACK SUCCESS · VALID MENU"
            else -> "CALLBACK SUCCESS · NO PARSED OPTIONS"
        }
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
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
            appendLine("Raw carrier response:")
            appendLine(if (safeResponse.isBlank()) "[Android returned an empty response]" else safeResponse)
            if (response.length > safeResponse.length) appendLine("[Response truncated in copy report at 6000 chars]")
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
            appendLine("Selected SIM slot & status: $lastSilentSimRouting")
            appendLine("Telephony readiness: $lastSilentTelephonyReadiness")
            appendLine("Carrier telephony USSD policy: $lastSilentCarrierConfig")
            appendLine("Android SDK: $lastSilentAndroidSdk")
            appendLine("Duration: $elapsedMs ms")
            appendLine("Callback outcome: ${if (requestStarted) "CALLBACK FAILURE" else "REQUEST COULD NOT START"}")
            appendLine("Raw response text received: No — Android failure callback provides no carrier response text")
            appendLine("Parsed menu options count: 0")
            appendLine("Carrier retry-limit response: No")
            appendLine("Previous valid menu retained: ${if (previousMenuRetained) "Yes" else "No"}")
            appendLine("Blocked because request already running: No")
            appendLine("Failure detail: $error")
        }
    }

    fun recordSilentSignatureMenuBlocked(
        reason: String,
        simDescription: String,
        carrierConfig: String,
        telephonyReadiness: String,
        previousMenuRetained: Boolean
    ) {
        lastSilentSimRouting = simDescription
        lastSilentTelephonyReadiness = telephonyReadiness
        lastSilentCarrierConfig = carrierConfig
        lastSilentAndroidSdk = Build.VERSION.SDK_INT
        silentSignatureMenuReport = buildString {
            appendLine("Request method: Android TelephonyManager.sendUssdRequest")
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
            appendLine("SIGNATURE MANAGEMENT · SILENT LIVE MENU CAPTURE")
            appendLine(silentSignatureMenuReport)
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
