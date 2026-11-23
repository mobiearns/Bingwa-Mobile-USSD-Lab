package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

internal const val LIVE_MENU_AUTO_REFRESH_INTERVAL_MS: Long = 12 * 60_000L

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
    val previousMenuRetained: Boolean = false,
    val blockedBecauseRunning: Boolean = false,
    val autoRefreshPausedByCarrierLimit: Boolean = false,
    val simDescription: String = "Not inspected yet",
    val telephonyReadiness: String = "Not inspected yet",
    val carrierConfig: String = "Not inspected yet",
    val sourceOfferName: String = ""
) {
    val hasValidMenu: Boolean get() = validOptions.isNotEmpty()
    val hasDiagnosticIssue: Boolean
        get() = !loading && (lastAttemptError.isNotBlank() || blockedBecauseRunning)
}

internal fun reduceLiveMenuStart(
    previous: SignatureLiveMenuState,
    startedAtWallMs: Long,
    simDescription: String,
    telephonyReadiness: String,
    carrierConfig: String,
    sourceOfferName: String
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
    sourceOfferName = sourceOfferName
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
            previousMenuRetained = retained,
            blockedBecauseRunning = false,
            autoRefreshPausedByCarrierLimit = true
        )
    }

    val noMenuError = if (rawResponse.isBlank()) {
        "Carrier callback succeeded, but Android delivered an empty response with no numbered menu options."
    } else {
        "Carrier responded (${rawResponse.length} chars), but no readable numbered menu options could be extracted."
    }
    return previous.copy(
        stale = retained,
        loading = false,
        lastAttemptDurationMs = elapsedMs,
        lastAttemptOutcome = "CALLBACK SUCCESS · NO PARSED OPTIONS",
        lastAttemptError = noMenuError,
        lastAttemptRawResponse = rawResponse,
        rawResponseReceived = rawResponse.isNotEmpty(),
        lastParsedOptionCount = 0,
        isCarrierRetryLimit = false,
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
    return (remainingMs / 1_000L).toInt()
}

internal fun formatLiveMenuDiagnosticsSummary(state: SignatureLiveMenuState): String = buildString {
    appendLine("Request method: ${state.lastAttemptMethod}")
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

        // The live-menu workspace resolves its silent verification code from an
        // eligible offer's PN template (or saved verification code), never from free input.
        val sourceOffer = offers.firstOrNull {
            it.id == selectedOfferId && SimpleSignatureVerifier.previewCode(it.ussdCode) != null
        } ?: offers.firstOrNull { SimpleSignatureVerifier.previewCode(it.ussdCode) != null }
        val sourceCode = sourceOffer?.ussdCode ?: liveMenuCode
        val preview = SimpleSignatureVerifier.previewCode(sourceCode)
        val selectedSim = sourceOffer?.simSelection?.takeUnless { it == OFFER_SIM_USE_GENERAL }
            ?: liveMenuSim.takeUnless { it == OFFER_SIM_USE_GENERAL }

        val resolvedTarget = resolveUssdSimTargets(
            appContext,
            selectionOverride = selectedSim,
            forceRefresh = true
        ).firstOrNull()
        val simDescription = resolvedTarget?.let { "SIM slot ${it.slotIndex + 1} (subscription ID redacted)" }
            ?: "No active SIM resolved for current selection"
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
                )
                _state.value = blockedState
                UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                    reason = blockedMessage,
                    simDescription = simDescription,
                    carrierConfig = carrierConfig,
                    telephonyReadiness = telephonyReadiness,
                    previousMenuRetained = blockedState.previousMenuRetained
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
                        sourceOfferName = sourceOffer?.name.orEmpty()
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
                sourceOfferName = sourceOffer?.name.orEmpty()
            )
            _state.value = startedState
            UssdPerformanceDiagnostics.recordSilentSignatureMenuStarted(
                simDescription = simDescription,
                sdk = Build.VERSION.SDK_INT,
                carrierConfig = carrierConfig,
                telephonyReadiness = telephonyReadiness,
                previousMenuRetained = startedState.hasValidMenu
            )
        }

        val requestStartedElapsed = SystemClock.elapsedRealtime()
        val callbackHandled = AtomicBoolean(false)
        val dialCallInProgress = AtomicBoolean(true)

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
                    val options = SimpleSignatureVerifier.menuOptions(response)
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
                    if (!callbackHandled.compareAndSet(false, true)) return@dialUssd
                    val elapsed = (SystemClock.elapsedRealtime() - requestStartedElapsed).coerceAtLeast(0L)
                    val completedWall = System.currentTimeMillis()
                    val requestStarted = !dialCallInProgress.get()
                    val wasBusy = error.contains("already in progress", ignoreCase = true)
                    val updated = synchronized(lock) {
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
                        next
                    }
                    if (wasBusy) {
                        UssdPerformanceDiagnostics.recordSilentSignatureMenuBlocked(
                            reason = error,
                            simDescription = simDescription,
                            carrierConfig = carrierConfig,
                            telephonyReadiness = telephonyReadiness,
                            previousMenuRetained = updated.previousMenuRetained
                        )
                    } else {
                        UssdPerformanceDiagnostics.recordSilentSignatureMenuFailure(
                            error = error,
                            elapsedMs = elapsed,
                            requestStarted = requestStarted,
                            previousMenuRetained = updated.previousMenuRetained
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
}
