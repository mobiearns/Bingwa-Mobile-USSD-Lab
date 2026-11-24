package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal const val LIVE_MENU_AUTO_REFRESH_INTERVAL_MS: Long = 12 * 60_000L
internal const val LIVE_MENU_CUSTOM_SOURCE_ID: Int = Int.MIN_VALUE
internal const val DEFAULT_LIVE_MENU_VERIFICATION_TEMPLATE: String = "*180*5*2*pn#"

internal fun resolveLiveMenuVerificationTemplate(savedTemplate: String): String =
    savedTemplate.trim().ifBlank { DEFAULT_LIVE_MENU_VERIFICATION_TEMPLATE }

internal data class SignatureLiveMenuState(
    // Last successfully captured and parsed menu (kept separate from latest attempt)
    val validResponse: String = "",
    val validOptions: List<Pair<Int, String>> = emptyList(),
    val lastSuccessAt: Long = 0L,
    val stale: Boolean = false,

    // Latest request attempt and diagnostic metadata
    val loading: Boolean = false,
    val lastAttemptAt: Long = 0L,
    val lastAttemptDurationMs: Long = 0L,
    val lastAttemptMethod: String = "Android TelephonyManager.sendUssdRequest",
    val lastAttemptOutcome: String = "IDLE",
    val lastAttemptError: String = "",
    val lastAttemptRawResponse: String = "",
    val rawResponseReceived: Boolean = false,
    val lastParsedOptionCount: Int = 0,
    val isCarrierRetryLimit: Boolean = false,
    val isSafaricomServiceError: Boolean = false,
    val previousMenuRetained: Boolean = false,
    val blockedBecauseRunning: Boolean = false,
    val autoRefreshPausedByCarrierLimit: Boolean = false,
    val simDescription: String = "Not inspected yet",
    val telephonyReadiness: String = "Not inspected yet",
    val carrierConfig: String = "Not inspected yet",
    val sourceOfferName: String = "",
    val sourceSummary: String = "Not selected",
    val simAdvice: String = "",
    val recommendedSimSelection: Int? = null
) {
    val hasValidMenu: Boolean get() = validOptions.isNotEmpty()
    val hasDiagnosticIssue: Boolean
        get() = !loading && (lastAttemptError.isNotBlank() || blockedBecauseRunning)
}

internal fun selectLiveMenuSourceOffer(
    offers: List<OfferItem>,
    selectedOfferId: Int
): OfferItem? {
    // Live Menu is exclusively the silent Simple-signature preflight source.
    // Even if the user last selected an Advanced offer elsewhere in Signature
    // Management, never send that offer's unrelated verification code here.
    val selected = offers.firstOrNull {
        it.id == selectedOfferId &&
            normalizeOfferExecutionMode(it.executionMode, it.category) == OFFER_EXECUTION_MODE_SIMPLE &&
            SimpleSignatureVerifier.previewCode(it.ussdCode) != null
    }
    if (selected != null) return selected

    // Prefer an enabled Safaricom *180 Simple-mode offer as the shared
    // Live Menu source. Keep the fallback deterministic when no *180 offer
    // exists, and never select a disabled offer while an enabled one exists.
    val eligible = offers.filter {
        normalizeOfferExecutionMode(it.executionMode, it.category) == OFFER_EXECUTION_MODE_SIMPLE &&
            SimpleSignatureVerifier.previewCode(it.ussdCode) != null
    }
    return eligible.firstOrNull {
        it.enabled && it.ussdCode.trim().startsWith("*180", ignoreCase = true)
    } ?: eligible.firstOrNull { it.enabled } ?: eligible.firstOrNull()
}

internal fun redactPreviewPattern(previewCode: String): String {
    // Diagnostic/UI summaries must never expose the dial string itself. Keep
    // only the fact that a saved verification template is available.
    return if (previewCode.isBlank()) "none" else "saved verification template"
}

internal fun reduceLiveMenuStart(
    previous: SignatureLiveMenuState,
    startedAtWallMs: Long,
    simDescription: String,
    telephonyReadiness: String,
    carrierConfig: String,
    sourceOfferName: String,
    sourceSummary: String = previous.sourceSummary
): SignatureLiveMenuState = previous.copy(
    // Never discard the last valid menu when starting a refresh.
    loading = true,
    lastAttemptAt = startedAtWallMs,
    lastAttemptOutcome = "IN_PROGRESS",
    lastAttemptError = "",
    blockedBecauseRunning = false,
    simDescription = simDescription,
    telephonyReadiness = telephonyReadiness,
    carrierConfig = carrierConfig,
    sourceOfferName = sourceOfferName,
    sourceSummary = sourceSummary
)

internal fun reduceLiveMenuSuccess(
    previous: SignatureLiveMenuState,
    rawResponse: String,
    parsedOptions: List<Pair<Int, String>>,
    elapsedMs: Long,
    completedAtWallMs: Long
): SignatureLiveMenuState {
    val retryLimit = SimpleSignatureVerifier.isCarrierRetryLimitResponse(rawResponse)
    val validCapture = parsedOptions.isNotEmpty() && !retryLimit
    if (validCapture) {
        return previous.copy(
            validResponse = rawResponse,
            validOptions = parsedOptions,
            lastSuccessAt = completedAtWallMs,
            stale = false,
            loading = false,
            lastAttemptDurationMs = elapsedMs,
            lastAttemptOutcome = "CALLBACK SUCCESS · VALID MENU",
            lastAttemptError = "",
            lastAttemptRawResponse = rawResponse,
            rawResponseReceived = true,
            lastParsedOptionCount = parsedOptions.size,
            isCarrierRetryLimit = false,
            isSafaricomServiceError = false,
            previousMenuRetained = false,
            blockedBecauseRunning = false,
            autoRefreshPausedByCarrierLimit = false
        )
    }

    val retained = previous.validOptions.isNotEmpty()
    if (retryLimit) {
        return previous.copy(
            // Preserve the last successfully captured & parsed menu and mark it stale.
            stale = retained,
            loading = false,
            lastAttemptDurationMs = elapsedMs,
            lastAttemptOutcome = "CALLBACK SUCCESS · CARRIER RETRY LIMIT",
            lastAttemptError = "Carrier returned \"Max Number of Menu Retries is Reached\". " +
                "This is a response from the carrier network delivered via Android's USSD callback, not an Android API failure. " +
                "Automatic refresh has been paused; wait briefly and use manual Refresh.",
            lastAttemptRawResponse = rawResponse,
            rawResponseReceived = rawResponse.isNotEmpty(),
            lastParsedOptionCount = 0,
            isCarrierRetryLimit = true,
            isSafaricomServiceError = false,
            previousMenuRetained = retained,
            blockedBecauseRunning = false,
            autoRefreshPausedByCarrierLimit = true
        )
    }

    val safaricomServiceError = SimpleSignatureVerifier.isSafaricomServiceErrorResponse(rawResponse)
    val noMenuError = when {
        safaricomServiceError ->
            "SAFARICOM ERROR RESPONSE: Safaricom returned a service/registration notice instead of the requested offers menu. The carrier response is displayed below exactly as received. Check the verification USSD path and SIM registration/service status; Bingwa will not treat this message as menu options."
        rawResponse.isBlank() ->
            "Carrier callback succeeded, but Android delivered an empty response with no numbered menu options."
        else ->
            "Carrier responded (${rawResponse.length} chars), but no readable numbered menu options could be extracted. The carrier response is displayed below exactly as received."
    }
    return previous.copy(
        stale = retained,
        loading = false,
        lastAttemptDurationMs = elapsedMs,
        lastAttemptOutcome = if (safaricomServiceError) "CALLBACK SUCCESS · SAFARICOM ERROR RESPONSE" else "CALLBACK SUCCESS · NO PARSED OPTIONS",
        lastAttemptError = noMenuError,
        lastAttemptRawResponse = rawResponse,
        rawResponseReceived = rawResponse.isNotEmpty(),
        lastParsedOptionCount = 0,
        isCarrierRetryLimit = false,
        isSafaricomServiceError = safaricomServiceError,
        previousMenuRetained = retained,
        blockedBecauseRunning = false,
        autoRefreshPausedByCarrierLimit = false
    )
}

internal fun reduceLiveMenuFailure(
    previous: SignatureLiveMenuState,
    error: String,
    elapsedMs: Long,
    requestStarted: Boolean,
    completedAtWallMs: Long
): SignatureLiveMenuState {
    val retained = previous.validOptions.isNotEmpty()
    return previous.copy(
        // Never overwrite validResponse / validOptions / lastSuccessAt on failure.
        stale = retained,
        loading = false,
        lastAttemptAt = if (previous.lastAttemptAt == 0L) completedAtWallMs else previous.lastAttemptAt,
        lastAttemptDurationMs = elapsedMs,
        lastAttemptOutcome = if (requestStarted) "CALLBACK FAILURE" else "REQUEST NOT STARTED",
        lastAttemptError = error,
        lastAttemptRawResponse = "",
        rawResponseReceived = false,
        lastParsedOptionCount = 0,
        isCarrierRetryLimit = false,
        isSafaricomServiceError = false,
        previousMenuRetained = retained,
        blockedBecauseRunning = false
    )
}

internal fun reduceLiveMenuBlocked(
    previous: SignatureLiveMenuState,
    reason: String,
    simDescription: String = previous.simDescription,
    telephonyReadiness: String = previous.telephonyReadiness,
    carrierConfig: String = previous.carrierConfig
): SignatureLiveMenuState {
    val retained = previous.validOptions.isNotEmpty()
    return previous.copy(
        blockedBecauseRunning = true,
        previousMenuRetained = retained,
        lastAttemptOutcome = "BLOCKED · REQUEST ALREADY RUNNING",
        lastAttemptError = reason,
        lastAttemptDurationMs = 0L,
        simDescription = simDescription,
        telephonyReadiness = telephonyReadiness,
        carrierConfig = carrierConfig
    )
}

internal fun shouldAutoRefreshLiveMenu(
    state: SignatureLiveMenuState,
    nowMs: Long,
    intervalMs: Long = LIVE_MENU_AUTO_REFRESH_INTERVAL_MS
): Boolean {
    if (state.loading) return false
    if (state.autoRefreshPausedByCarrierLimit) return false
    if (state.lastAttemptAt <= 0L) return true
    return (nowMs - state.lastAttemptAt) >= intervalMs
}

internal fun secondsUntilNextAutoRefresh(
    state: SignatureLiveMenuState,
    nowMs: Long,
    intervalMs: Long = LIVE_MENU_AUTO_REFRESH_INTERVAL_MS
): Int {
    if (state.autoRefreshPausedByCarrierLimit) return 0
    if (state.lastAttemptAt <= 0L) return (intervalMs / 1_000L).toInt()
    val remainingMs = (intervalMs - (nowMs - state.lastAttemptAt)).coerceAtLeast(0L)
    return (remainingMs / 1_000L + if (remainingMs % 1_000L == 0L) 0L else 1L).toInt()
}

internal fun formatLiveMenuDiagnosticsSummary(state: SignatureLiveMenuState): String = buildString {
    appendLine("Request method: ${state.lastAttemptMethod}")
    appendLine("Menu source (redacted): ${state.sourceSummary}")
    appendLine("Selected SIM slot & status: ${state.simDescription}")
    appendLine("Telephony readiness: ${state.telephonyReadiness}")
    appendLine("Carrier USSD policy: ${state.carrierConfig}")
    appendLine("Request duration: ${state.lastAttemptDurationMs} ms")
    appendLine("Callback outcome: ${state.lastAttemptOutcome}")
    appendLine(
        "Raw response text received: " + if (state.rawResponseReceived) {
            "Yes (${state.lastAttemptRawResponse.length} chars)"
        } else {
            "No — Android supplied no carrier response text"
        }
    )
    appendLine("Parsed menu options: ${state.lastParsedOptionCount}")
    appendLine("Carrier retry-limit response: ${if (state.isCarrierRetryLimit) "Yes" else "No"}")
    appendLine("Previous valid menu retained: ${if (state.previousMenuRetained) "Yes (${state.validOptions.size} options)" else "No"}")
    appendLine("Blocked due to running request: ${if (state.blockedBecauseRunning) "Yes" else "No"}")
    if (state.lastAttemptError.isNotBlank()) {
        appendLine()
        appendLine("Explanation: ${state.lastAttemptError}")
    }
    appendLine()
    appendLine("Raw carrier response:")
    if (state.rawResponseReceived) {
        appendLine(state.lastAttemptRawResponse)
    } else {
        appendLine("[No response text was delivered by Android for this attempt]")
    }
}

internal object SignatureLiveMenuCoordinator {
    private val lock = Any()
    private var requestInFlight = false
    private var lastAutoPresentedIssueKey: String? = null
    private val _state = MutableStateFlow(SignatureLiveMenuState())
    val state: StateFlow<SignatureLiveMenuState> = _state.asStateFlow()

    fun consumeAutoPresentIssueKey(state: SignatureLiveMenuState): Boolean {
        if (!state.hasDiagnosticIssue) return false
        val key = "${state.lastAttemptAt}|${state.lastAttemptOutcome}|${state.lastAttemptError}"
        synchronized(lock) {
            if (key == lastAutoPresentedIssueKey) return false
            lastAutoPresentedIssueKey = key
            return true
        }
    }

    fun shouldAutoRefresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldAutoRefreshLiveMenu(_state.value, nowMs)

    fun secondsUntilNextAutoRefresh(nowMs: Long = System.currentTimeMillis()): Int =
        secondsUntilNextAutoRefresh(_state.value, nowMs)

    fun refreshIfDue(
        context: Context,
        offers: List<OfferItem>,
        selectedOfferId: Int,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!shouldAutoRefreshLiveMenu(_state.value, nowMs)) return false
        return refresh(context, offers, selectedOfferId, forceManual = false)
    }

    fun refresh(
        context: Context,
        offers: List<OfferItem>,
        selectedOfferId: Int,
        forceManual: Boolean
    ): Boolean {
        val appContext = context.applicationContext
        val liveMenuCode = SignatureStudioStore.liveMenuCode(appContext)
        val liveMenuSim = SignatureStudioStore.liveMenuSim(appContext)

        // Live Menu is a read-only carrier catalogue, not an offer execution.
        // Its verification request must never depend on a selected offer, its price,
        // or its purchase-code suffix. Use the saved workspace template when present;
        // otherwise use the fixed Safaricom first-screen verification route.
        val sourceCode = resolveLiveMenuVerificationTemplate(liveMenuCode)
        val preview = SimpleSignatureVerifier.previewCode(sourceCode)
        val sourceSummary = if (liveMenuCode.isBlank()) {
            "Built-in Safaricom Live Menu verification template"
        } else {
            "Saved Live Menu verification template"
        }
        // SIM selection belongs to the Live Menu workspace itself. Do not inherit
        // the SIM attached to whichever offer happens to be selected for learning.
        val selectedSim = liveMenuSim.takeUnless { it == OFFER_SIM_USE_GENERAL }

        val sims = getAvailableSims(appContext, forceRefresh = true)
        val resolvedTarget = resolveUssdSimTargets(
            appContext,
            selectionOverride = selectedSim,
            forceRefresh = true
        ).firstOrNull()
        val matchedCarrier = resolvedTarget?.let { target ->
            sims.firstOrNull { it.subscriptionId == target.subId }
                ?.let { info ->
                    val display = info.displayName?.toString()?.trim().orEmpty()
                    if (display.isNotBlank()) display else null
                }
        }
        val simDescription = resolvedTarget?.let {
            val carrierSuffix = matchedCarrier?.let { name -> " · $name" }.orEmpty()
            "SIM slot ${it.slotIndex + 1}$carrierSuffix (subscription ID redacted)"
        } ?: "No active SIM resolved for current selection"
        val defaultVoiceSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.telephony.SubscriptionManager.getDefaultVoiceSubscriptionId()
        } else {
            android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
        val defaultVoiceInfo = sims.firstOrNull { it.subscriptionId == defaultVoiceSubId }
        val recommendedSimSelection = if (
            resolvedTarget != null &&
            defaultVoiceSubId != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID &&
            resolvedTarget.subId != defaultVoiceSubId &&
            defaultVoiceInfo != null
        ) {
            if (defaultVoiceInfo.simSlotIndex == 1) USSD_SIM_SELECTION_SLOT_2 else USSD_SIM_SELECTION_SLOT_1
        } else null
        val simAdvice = when {
            resolvedTarget == null -> "No active SIM is currently resolved. Select an installed SIM slot and refresh."
            recommendedSimSelection != null -> {
                val recommendedSlot = if (recommendedSimSelection == USSD_SIM_SELECTION_SLOT_2) "SIM 2" else "SIM 1"
                "The selected SIM is not Android's default voice SIM. Android's default voice SIM is $recommendedSlot; try it for carrier USSD routing."
            }
            defaultVoiceSubId == android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID ->
                "Android has no default voice SIM selected. Choose a voice SIM in Android system settings if USSD requests fail."
            else -> ""
        }
        val carrierConfig = UssdHelper.describeSilentUssdCarrierConfig(appContext, resolvedTarget?.subId)
        val telephonyReadiness = UssdHelper.describeSilentUssdReadiness(
            appContext,
            resolvedTarget?.subId,
            resolvedTarget?.slotIndex
        )

        val nowWall = System.currentTimeMillis()
        synchronized(lock) {
            val current = _state.value
            if (requestInFlight || current.loading || SilentUssdOptimized.isExecutionInProgress()) {
                val blockedMessage = "Another silent USSD request is already running. Wait for it to finish before refreshing."
                val blockedState = reduceLiveMenuBlocked(
                    previous = current,
                    reason = blockedMessage,
                    simDescription = simDescription,
                    telephonyReadiness = telephonyReadiness,
                    carrierConfig = carrierConfig
                ).copy(sourceSummary = sourceSummary)
                _state.value = blockedState
                UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                    reason = blockedMessage,
                    simDescription = simDescription,
                    carrierConfig = carrierConfig,
                    telephonyReadiness = telephonyReadiness,
                    previousMenuRetained = blockedState.previousMenuRetained,
                    sourceSummary = sourceSummary
                )
                return false
            }

            if (SilentUssdOptimized.isNativeRequestUnresolved() ||
                SilentUssdOptimized.nativeSessionMayBePending()) {
                val blockedMessage = if (SilentUssdOptimized.nativeSessionMayBePending()) {
                    "Native USSD session may still be pending on Android — restart the phone or toggle airplane mode before refreshing."
                } else {
                    "Native USSD session unresolved — Android still owns the previous request. Wait for it to complete or restart the phone before refreshing."
                }
                val blockedState = reduceLiveMenuBlocked(
                    previous = current,
                    reason = blockedMessage,
                    simDescription = simDescription,
                    telephonyReadiness = telephonyReadiness,
                    carrierConfig = carrierConfig
                ).copy(sourceSummary = sourceSummary)
                _state.value = blockedState
                UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                    reason = blockedMessage,
                    simDescription = simDescription,
                    carrierConfig = carrierConfig,
                    telephonyReadiness = telephonyReadiness,
                    previousMenuRetained = blockedState.previousMenuRetained,
                    sourceSummary = sourceSummary
                )
                UssdPerformanceDiagnostics.recordSilentPendingMmiHypothesis(
                    inferred = true,
                    detail = "Live Menu blocked: nativeRequestUnresolved=${SilentUssdOptimized.isNativeRequestUnresolved()} nativeSessionMayBePending=${SilentUssdOptimized.nativeSessionMayBePending()}"
                )
                return false
            }

            if (preview.isNullOrBlank()) {
                val reason = "No offer with a saved PN verification code is available. Select an eligible offer in Offers to inspect its live menu."
                val failedState = reduceLiveMenuFailure(
                    previous = current.copy(
                        simDescription = simDescription,
                        telephonyReadiness = telephonyReadiness,
                        carrierConfig = carrierConfig,
                        sourceOfferName = "Live Menu verification",
                        sourceSummary = sourceSummary,
                        simAdvice = simAdvice,
                        recommendedSimSelection = recommendedSimSelection
                    ),
                    error = reason,
                    elapsedMs = 0L,
                    requestStarted = false,
                    completedAtWallMs = nowWall
                )
                _state.value = failedState
                UssdPerformanceDiagnostics.recordSilentSignatureMenuFailure(
                    error = reason,
                    elapsedMs = 0L,
                    requestStarted = false,
                    previousMenuRetained = failedState.previousMenuRetained
                )
                return false
            }

            requestInFlight = true
            val startedState = reduceLiveMenuStart(
                previous = current,
                startedAtWallMs = nowWall,
                simDescription = simDescription,
                telephonyReadiness = telephonyReadiness,
                carrierConfig = carrierConfig,
                sourceOfferName = "Live Menu verification",
                sourceSummary = sourceSummary
            ).copy(
                simAdvice = simAdvice,
                recommendedSimSelection = recommendedSimSelection
            )
            _state.value = startedState
            UssdPerformanceDiagnostics.recordSilentSignatureMenuStarted(
                simDescription = simDescription,
                sdk = Build.VERSION.SDK_INT,
                carrierConfig = carrierConfig,
                telephonyReadiness = telephonyReadiness,
                previousMenuRetained = startedState.hasValidMenu,
                sourceSummary = sourceSummary
            )
        }

        val requestStartedElapsed = SystemClock.elapsedRealtime()
        val callbackHandled = AtomicBoolean(false)
        val dialCallInProgress = AtomicBoolean(true)

        // 10 fallback variants for the long verification code *180*5*2*pn#.
        // Each variant uses a unique spacing/formatting pattern when constructing
        // the dial string. Spaces are stripped before crossing the silent-USD
        // boundary (canonicalSilentUssdDialString rejects spaces), so the spacing
        // documents the entry method while the actual transmitted string is canonical.
        // First 5 variants use phone 0712345678, last 5 use 0790993046.
        data class UssdFallbackVariant(
            val label: String,
            val spacedTemplate: String,   // contains "pn" placeholder
            val phoneNumber: String
        )

        val ussdFallbackVariants = listOf(
            // Variants 1-5: phone 0712345678
            UssdFallbackVariant("v1_canonical_no_spaces_0712345678", "*180*5*2*pn#", "0712345678"),
            UssdFallbackVariant("v2_spaced_every_separator_0712345678", "* 180 * 5 * 2 * pn * #", "0712345678"),
            UssdFallbackVariant("v3_irregular_spaces_0712345678", "*180 *5* 2 *0712345678 *#", "0712345678"),
            UssdFallbackVariant("v4_alternate_spaces_0712345678", "*180* 5*2* 0712345678 *#", "0712345678"),
            UssdFallbackVariant("v5_double_hash_0712345678", "*180*5*2*pn*#", "0712345678"),
            // Variants 6-10: phone 0790993046
            UssdFallbackVariant("v6_canonical_no_spaces_0790993046", "*180*5*2*pn#", "0790993046"),
            UssdFallbackVariant("v7_spaced_every_separator_0790993046", "* 180 * 5 * 2 * pn * #", "0790993046"),
            UssdFallbackVariant("v8_irregular_spaces_0790993046", "*180 *5* 2 *0790993046 *#", "0790993046"),
            UssdFallbackVariant("v9_alternate_spaces_0790993046", "*180* 5*2* 0790993046 *#", "0790993046"),
            UssdFallbackVariant("v10_double_hash_0790993046", "*180*5*2*pn*#", "0790993046"),
        )

        /**
         * Try fallback variant at the given index. Chains to the next variant on
         * USSD_RETURN_FAILURE (-1). After all 10 variants are exhausted, falls back
         * to *180# for basic menu capture.
         */
        fun tryFallbackVariant(variantIndex: Int) {
            if (variantIndex >= ussdFallbackVariants.size) {
                // All 10 variants exhausted — final fallback to *180#
                val fallbackStarted = UssdHelper.dialUssd(
                    context = appContext,
                    ussdCode = "*180#",
                    silentOnly = true,
                    subIdOverride = selectedSim,
                    preserveRawResponse = true,
                    onSuccess = { fallbackResponse ->
                        if (!callbackHandled.compareAndSet(false, true)) return@dialUssd
                        val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
                        val completedWall = System.currentTimeMillis()
                        val parseStartedAt = UssdPerformanceDiagnostics.mark()
                        val options = SimpleSignatureVerifier.menuOptions(fallbackResponse)
                        UssdPerformanceDiagnostics.record(
                            "silent.response_parse",
                            parseStartedAt,
                            "responseChars=${fallbackResponse.length} parsedOptions=${options.size} classification=${if (options.isNotEmpty()) "menu" else "non_menu_response"} final_fallback=star180"
                        )
                        val updated = synchronized(lock) {
                            requestInFlight = false
                            val next = reduceLiveMenuSuccess(
                                previous = _state.value,
                                rawResponse = fallbackResponse,
                                parsedOptions = options,
                                elapsedMs = elapsed,
                                completedAtWallMs = completedWall
                            )
                            _state.value = next
                            next
                        }
                        UssdPerformanceDiagnostics.recordSilentSignatureMenuSuccess(
                            response = fallbackResponse,
                            optionCount = options.size,
                            elapsedMs = elapsed,
                            isCarrierRetryLimit = updated.isCarrierRetryLimit,
                            previousMenuRetained = updated.previousMenuRetained
                        )
                    },
                    onFailure = { finalError ->
                        if (!callbackHandled.compareAndSet(false, true)) return@dialUssd
                        val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
                        val completedWall = System.currentTimeMillis()
                        val updated = synchronized(lock) {
                            requestInFlight = false
                            val current = _state.value
                            val next = reduceLiveMenuBlocked(
                                previous = current,
                                reason = "All 10 *180*5*2*pn# fallback variants failed with USSD_RETURN_FAILURE (-1). Final fallback *180# also failed: $finalError. Restart the phone or toggle airplane mode before refreshing.",
                                simDescription = simDescription,
                                telephonyReadiness = telephonyReadiness,
                                carrierConfig = carrierConfig
                            )
                            _state.value = next
                            next
                        }
                        UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                            reason = next.lastAttemptError,
                            simDescription = simDescription,
                            carrierConfig = carrierConfig,
                            telephonyReadiness = telephonyReadiness,
                            previousMenuRetained = updated.previousMenuRetained,
                            sourceSummary = sourceSummary
                        )
                    }
                )
                if (!fallbackStarted) {
                    handlePrimaryFailure(
                        callbackHandled = callbackHandled,
                        error = "All 10 fallback variants and *180# could not be started",
                        elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L),
                        completedWall = System.currentTimeMillis(),
                        requestStarted = false,
                        wasBusy = false,
                        simDescription = simDescription,
                        telephonyReadiness = telephonyReadiness,
                        carrierConfig = carrierConfig,
                        sourceSummary = sourceSummary
                    )
                }
                return@tryFallbackVariant
            }

            val variant = ussdFallbackVariants[variantIndex]
            // Replace pn placeholder with the actual phone number, then strip spaces
            // for the silent-USD boundary (canonicalSilentUssdDialString requires no spaces).
            val variantCode = variant.spacedTemplate
                .replace("pn", variant.phoneNumber, ignoreCase = true)
                .replace(" ", "")

            UssdPerformanceDiagnostics.record(
                "silent.fallback_variant",
                detail = "variant=${variant.label} codeChars=${variantCode.length} phoneNumberRedacted=true"
            )

            val variantStarted = UssdHelper.dialUssd(
                context = appContext,
                ussdCode = variantCode,
                silentOnly = true,
                subIdOverride = selectedSim,
                preserveRawResponse = true,
                onSuccess = { variantResponse ->
                    if (!callbackHandled.compareAndSet(false, true)) return@dialUssd
                    val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
                    val completedWall = System.currentTimeMillis()
                    val parseStartedAt = UssdPerformanceDiagnostics.mark()
                    val options = SimpleSignatureVerifier.menuOptions(variantResponse)
                    UssdPerformanceDiagnostics.record(
                        "silent.response_parse",
                        parseStartedAt,
                        "responseChars=${variantResponse.length} parsedOptions=${options.size} classification=${if (options.isNotEmpty()) "menu" else "non_menu_response"} fallback_variant=${variant.label}"
                    )
                    val updated = synchronized(lock) {
                        requestInFlight = false
                        val next = reduceLiveMenuSuccess(
                            previous = _state.value,
                            rawResponse = variantResponse,
                            parsedOptions = options,
                            elapsedMs = elapsed,
                            completedAtWallMs = completedWall
                        )
                        _state.value = next
                        next
                    }
                    UssdPerformanceDiagnostics.recordSilentSignatureMenuSuccess(
                        response = variantResponse,
                        optionCount = options.size,
                        elapsedMs = elapsed,
                        isCarrierRetryLimit = updated.isCarrierRetryLimit,
                        previousMenuRetained = updated.previousMenuRetained
                    )
                },
                onFailure = { variantError ->
                    val isGenericFailure = variantError.contains("USSD_RETURN_FAILURE", ignoreCase = true)
                    if (isGenericFailure && variantIndex + 1 < ussdFallbackVariants.size) {
                        // Chain to the next variant
                        tryFallbackVariant(variantIndex + 1)
                    } else if (isGenericFailure) {
                        // Last variant failed — try final *180# fallback
                        tryFallbackVariant(ussdFallbackVariants.size)
                    } else {
                        // Non-generic failure — try next variant anyway
                        if (variantIndex + 1 < ussdFallbackVariants.size) {
                            tryFallbackVariant(variantIndex + 1)
                        } else {
                            tryFallbackVariant(ussdFallbackVariants.size)
                        }
                    }
                }
            )
            if (!variantStarted) {
                // Variant could not start — try next variant
                if (variantIndex + 1 < ussdFallbackVariants.size) {
                    tryFallbackVariant(variantIndex + 1)
                } else {
                    tryFallbackVariant(ussdFallbackVariants.size)
                }
            }
        }

        val started = try {
            UssdHelper.dialUssd(
                context = appContext,
                ussdCode = preview.orEmpty(),
                silentOnly = true,
                subIdOverride = selectedSim,
                preserveRawResponse = true,
                onSuccess = { response ->
                    if (!callbackHandled.compareAndSet(false, true)) return@dialUssd
                    val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
                    val completedWall = System.currentTimeMillis()
                    val parseStartedAt = UssdPerformanceDiagnostics.mark()
                    val options = SimpleSignatureVerifier.menuOptions(response)
                    UssdPerformanceDiagnostics.record(
                        "silent.response_parse",
                        parseStartedAt,
                        "responseChars=${response.length} parsedOptions=${options.size} classification=${if (options.isNotEmpty()) "menu" else "non_menu_response"}"
                    )
                    val updated = synchronized(lock) {
                        requestInFlight = false
                        val next = reduceLiveMenuSuccess(
                            previous = _state.value,
                            rawResponse = response,
                            parsedOptions = options,
                            elapsedMs = elapsed,
                            completedAtWallMs = completedWall
                        )
                        _state.value = next
                        next
                    }
                    UssdPerformanceDiagnostics.recordSilentSignatureMenuSuccess(
                        response = response,
                        optionCount = options.size,
                        elapsedMs = elapsed,
                        isCarrierRetryLimit = updated.isCarrierRetryLimit,
                        previousMenuRetained = updated.previousMenuRetained
                    )
                },
                onFailure = { error ->
                    val wasBusy = error.contains("already in progress", ignoreCase = true)
                    val isGenericFailure = error.contains("USSD_RETURN_FAILURE", ignoreCase = true)

                    if (isGenericFailure && !wasBusy && SilentUssdOptimized.nativeSessionMayBePending()) {
                        // Primary verification code failed with -1 — chain through 10 fallback variants
                        tryFallbackVariant(0)
                    } else {
                        handlePrimaryFailure(
                            callbackHandled = callbackHandled,
                            error = error,
                            elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L),
                            completedWall = System.currentTimeMillis(),
                            requestStarted = !dialCallInProgress.get(),
                            wasBusy = wasBusy,
                            simDescription = simDescription,
                            telephonyReadiness = telephonyReadiness,
                            carrierConfig = carrierConfig,
                            sourceSummary = sourceSummary
                        )
                    }
                }
            )
        } finally {
            dialCallInProgress.set(false)
        }

        if (!started && callbackHandled.compareAndSet(false, true)) {
            val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
            val completedWall = System.currentTimeMillis()
            val reason = "UssdHelper could not initiate the silent request. Check CALL_PHONE permission, active SIM routing, telephony readiness, and Android API support."
            val updated = synchronized(lock) {
                requestInFlight = false
                val next = reduceLiveMenuFailure(
                    previous = _state.value,
                    error = reason,
                    elapsedMs = elapsed,
                    requestStarted = false,
                    completedAtWallMs = completedWall
                )
                _state.value = next
                next
            }
            UssdPerformanceDiagnostics.recordSilentSignatureMenuFailure(
                error = reason,
                elapsedMs = elapsed,
                requestStarted = false,
                previousMenuRetained = updated.previousMenuRetained
            )
            return false
        }
        return started
    }

    private fun handlePrimaryFailure(
        callbackHandled: AtomicBoolean,
        error: String,
        elapsed: Long,
        completedWall: Long,
        requestStarted: Boolean,
        wasBusy: Boolean,
        simDescription: String,
        telephonyReadiness: String,
        carrierConfig: String,
        sourceSummary: String
    ) {
        if (!callbackHandled.compareAndSet(false, true)) return
        synchronized(lock) {
            requestInFlight = false
            val current = _state.value
            val next = if (wasBusy) {
                reduceLiveMenuBlocked(
                    previous = current.copy(loading = false),
                    reason = error,
                    simDescription = simDescription,
                    telephonyReadiness = telephonyReadiness,
                    carrierConfig = carrierConfig
                )
            } else {
                reduceLiveMenuFailure(
                    previous = current,
                    error = error,
                    elapsedMs = elapsed,
                    requestStarted = requestStarted,
                    completedAtWallMs = completedWall
                )
            }
            _state.value = next
        }
        if (wasBusy) {
            UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                reason = error,
                simDescription = simDescription,
                carrierConfig = carrierConfig,
                telephonyReadiness = telephonyReadiness,
                previousMenuRetained = _state.value.previousMenuRetained,
                sourceSummary = sourceSummary
            )
        } else {
            UssdPerformanceDiagnostics.recordSilentSignatureMenuFailure(
                error = error,
                elapsedMs = elapsed,
                requestStarted = requestStarted,
                previousMenuRetained = _state.value.previousMenuRetained
            )
        }
    }
}
