package com.bingwa.mobile

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_RETRY_PENDING = "com.bingwa.mobile.ACTION_RETRY_PENDING"
        const val ACTION_RETRY_AIRTIME_TOPUP = "com.bingwa.mobile.ACTION_RETRY_AIRTIME_TOPUP"
        const val ACTION_RETRY_ZERO_TOKENS = "com.bingwa.mobile.ACTION_RETRY_ZERO_TOKENS"
        private const val ACTION_RETRY_MAINTENANCE = "com.bingwa.mobile.ACTION_RETRY_MAINTENANCE"
        private const val ACTION_RETRY_RETRIABLE_RESPONSE = "com.bingwa.mobile.ACTION_RETRY_RETRIABLE_RESPONSE"
        const val ACTION_RUN_SCHEDULED = "com.bingwa.mobile.ACTION_RUN_SCHEDULED"
        private const val CHANNEL_ID = "automation_service"
        private const val NOTIFICATION_ID = 2014
        private const val RETRIABLE_RESPONSE_PREFS = "retriable_ussd_response_retry"
        private const val ACTIVE_RETRY_WINDOW_MS = 60_000L
        private const val ACTIVE_RETRY_INTERVAL_MS = 5_000L
        private const val FIRST_BACKOFF_MS = 5 * 60_000L
        private const val REPEATED_BACKOFF_MS = 10 * 60_000L

        fun cancelRetriableResponseRetry(context: Context, txId: Int) {
            if (txId < 0) return
            runCatching {
                val intent = Intent(context, AutomationService::class.java).apply {
                    action = ACTION_RETRY_RETRIABLE_RESPONSE
                }
                val pi = PendingIntent.getService(
                    context,
                    txId,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarm?.cancel(pi)
                pi.cancel()
            }
        }
    }

    private data class AutomationRequest(
        val code: String,
        val phoneNumber: String,
        val txId: Int,
        val mode: String,
        val offerId: Int,
        val offerName: String,
        val simSelection: Int,
        val signatureEnabled: Boolean,
        val simpleSignatureEnabled: Boolean,
        val signatureMode: String,
        val signatureLearning: Boolean,
        val returnToAppAggressively: Boolean,
        val tokenPreDeducted: Boolean = false,
        val instantDialStarted: Boolean = false
    )

    private data class RetriableResponseRetryState(
        val windowStartAtMillis: Long,
        val completedWindows: Int,
        val nextAttemptStartsNewWindow: Boolean,
        val totalAttempts: Int
    )

    private val patternManager by lazy { UssdResponsePatternManager(this) }

    private fun usesAdvancedFlow(request: AutomationRequest): Boolean {
        return (request.signatureEnabled && !request.simpleSignatureEnabled) || request.signatureLearning ||
            request.mode.equals(OFFER_EXECUTION_MODE_ADVANCED, ignoreCase = true)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = buildRequest(intent) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        val recoveryAction = intent?.action
        if (recoveryAction == ACTION_RETRY_AIRTIME_TOPUP || recoveryAction == ACTION_RETRY_ZERO_TOKENS) {
            if (!AutomaticTransactionRecovery.begin(this, request.txId, recoveryAction)) {
                Log.d(TAG, "Automatic recovery no longer eligible txId=${request.txId}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (recoveryAction == ACTION_RETRY_PENDING ||
            recoveryAction == ACTION_RETRY_MAINTENANCE ||
            recoveryAction == ACTION_RETRY_RETRIABLE_RESPONSE ||
            recoveryAction == ACTION_RETRY_AIRTIME_TOPUP ||
            recoveryAction == ACTION_RETRY_ZERO_TOKENS
        ) {
            Log.d(TAG, "Retry-pending alarm txId=${request.txId}")
        } else {
            Log.d(TAG, "onStartCommand mode=${request.mode} code=${request.code} txId=${request.txId}")
        }
        if (intent?.action == ACTION_RETRY_RETRIABLE_RESPONSE) {
            armRetriableResponseWindow(request.txId)
        }
        if (intent?.action == ACTION_RUN_SCHEDULED) {
            ScheduledOfferDispatchStore.markExecuted(this, request.txId)
            if (request.txId >= 0) {
                val msg = "Scheduled dispatch started."
                saveTransactionResponse(request.txId, TransactionStatus.PROCESSING.value, msg)
                sendBroadcastUpdate(request.txId, TransactionStatus.PROCESSING.value, msg)
            }
        }

        // Global safety check: block dispatches to blacklisted numbers (even if scheduled earlier).
        if (request.phoneNumber.isNotBlank() && BlacklistedContactStore.isBlacklisted(this, request.phoneNumber)) {
            processResponse(
                request = request,
                response = "Blocked: this phone number is blacklisted and cannot receive data bundles.",
                forcedStatus = TransactionStatus.CANCELLED.value
            )
            stopSelf()
            return START_NOT_STICKY
        }

        // Signature learning is a local calibration run, not a bundle purchase.
        // It must never spend tokens, even when it is learning against a saved offer ID.
        // Actual bundle executions (including signature-guarded purchases) remain protected
        // by the normal centralized token gate.
        val isSignatureLearning = request.signatureLearning
        val isCommercialDispatch = request.offerId != -1 ||
            (request.txId >= 0 && request.phoneNumber.isNotBlank())
        val isRetryAlarm = intent?.action == ACTION_RETRY_PENDING ||
            intent?.action == ACTION_RETRY_MAINTENANCE ||
            intent?.action == ACTION_RETRY_RETRIABLE_RESPONSE ||
            intent?.action == ACTION_RETRY_AIRTIME_TOPUP

        if (!isSignatureLearning && isCommercialDispatch && !isRetryAlarm && !request.tokenPreDeducted && !request.simpleSignatureEnabled) {
            val unlimited = UnlimitedManager(this).isActive()
            if (!unlimited) {
                val tokenMgr = TokenManager(this)
                val spent = tokenMgr.spendTokens(1)
                if (!spent) {
                    Log.w(TAG, "Automation blocked: insufficient tokens and no active unlimited plan (txId=${request.txId})")
                    processResponse(
                        request = request,
                        response = "Failed: insufficient tokens to process this bundle. Please purchase tokens or activate Unlimited.",
                        forcedStatus = TransactionStatus.FAILED.value
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        when {
            usesAdvancedFlow(request) -> startAdvanced(request)
            else -> handleSimple(request)
        }
        return START_REDELIVER_INTENT
    }

    private fun buildRequest(intent: Intent?): AutomationRequest? {
        val safeIntent = intent ?: return null
        val code = safeIntent.getStringExtra("code") ?: return null
        val rawPhoneNumber = safeIntent.getStringExtra("phoneNumber") ?: ""
        val ussdPhoneNumber = rawPhoneNumber.takeIf { it.isBlank() }
            ?: UssdHelper.normalizeRecipientForUssdInput(rawPhoneNumber)
        return AutomationRequest(
            code = code,
            phoneNumber = ussdPhoneNumber,
            txId = safeIntent.getIntExtra("txId", -1),
            mode = safeIntent.getStringExtra("mode") ?: OFFER_EXECUTION_MODE_SIMPLE,
            offerId = safeIntent.getIntExtra("offerId", -1),
            offerName = safeIntent.getStringExtra("offerName") ?: "",
            simSelection = normalizeOfferSimSelection(
                safeIntent.getIntExtra("simSelection", OFFER_SIM_USE_GENERAL)
            ),
            signatureEnabled = safeIntent.getBooleanExtra("signatureEnabled", false),
            simpleSignatureEnabled = safeIntent.getBooleanExtra("simpleSignatureEnabled", false),
            signatureMode = (safeIntent.getStringExtra("signatureMode") ?: "STOP").uppercase(),
            signatureLearning = safeIntent.getBooleanExtra("signatureLearning", false),
            returnToAppAggressively = safeIntent.getBooleanExtra("returnToAppAggressively", true),
            tokenPreDeducted = safeIntent.getBooleanExtra("tokenPreDeducted", false),
            instantDialStarted = safeIntent.getBooleanExtra("instantDialStarted", false)
        )
    }

    private fun handleSimple(request: AutomationRequest) {
        if (request.simpleSignatureEnabled) {
            handleSimpleSignaturePreflight(request)
            return
        }
        val subIdOverride = request.simSelection.takeUnless { it == OFFER_SIM_USE_GENERAL }
        val callbackDelivered = AtomicBoolean(false)

        val started = UssdHelper.dialUssd(
            context = this,
            ussdCode = request.code,
            silentOnly = true,
            subIdOverride = subIdOverride,
            onSuccess = { response ->
                if (callbackDelivered.compareAndSet(false, true)) {
                    Log.d(TAG, "SIMPLE success txId=${request.txId} response='${response.take(150)}'")
                    processResponse(request, response)
                } else {
                    Log.w(TAG, "Ignoring duplicate SIMPLE success callback txId=${request.txId}")
                }
            },
            onFailure = { error ->
                if (!callbackDelivered.compareAndSet(false, true)) {
                    Log.w(TAG, "Ignoring duplicate SIMPLE failure callback txId=${request.txId}")
                } else if (
                    error.contains("already in progress", ignoreCase = true) ||
                    UssdNavigationService.isBusyForBalanceCheck() ||
                    SilentUssdOptimized.isExecutionInProgress() ||
                    SilentUssd.isExecutionInProgress()
                ) {
                    // A busy USSD engine is a queue condition, not a failed customer transaction.
                    // The initial dispatch has already passed the token gate, so carry that debit
                    // forward and do not charge again when the queued execution starts.
                    val queuedRequest = request.copy(tokenPreDeducted = true)
                    UssdQueue.enqueue(
                        task = Runnable {
                            ServiceLauncher.startAutomationService(
                                applicationContext,
                                buildAutomationIntent(applicationContext, queuedRequest)
                            )
                        },
                        priority = USSD_EXECUTION_PRIORITY_SPECIAL,
                        maxRetries = 2
                    )
                    Log.d(TAG, "SIMPLE txId=${request.txId} queued until the active USSD session clears")
                    stopSelf()
                } else {
                    Log.e(TAG, "SIMPLE failed txId=${request.txId}: $error")
                    processResponse(request, error, forcedStatus = TransactionStatus.FAILED.value)
                }
            }
        )

        if (!started && callbackDelivered.compareAndSet(false, true)) {
            processResponse(
                request,
                "Silent USSD could not be started on the selected SIM.",
                forcedStatus = TransactionStatus.FAILED.value
            )
        }
    }

    /**
     * Simple protection is a silent, first-screen-only preflight. It never enters the
     * Accessibility/Advanced navigator and never falls back to a visible USSD prompt.
     */
    private fun handleSimpleSignaturePreflight(request: AutomationRequest) {
        val offer = request.offerId.takeIf { it >= 0 }?.let { OfferRepository.findById(this, it) }
        val signatureTemplate = offer?.ussdCode?.takeIf { SimpleSignatureVerifier.previewCode(it) != null }
            ?: request.code
        val previewCode = SimpleSignatureVerifier.previewCode(signatureTemplate)
        if (offer == null || !offer.simpleSignatureEnabled || previewCode.isNullOrBlank() || offer.simpleLearnedSignature.isEmpty()) {
            recordSignatureActivity(this, (offer?.name ?: request.offerName) + ": Simple signature unavailable (missing marker or learned signature)")
            processResponse(
                request,
                "Signature not available: configure a pn position marker and learn a Simple signature first.",
                forcedStatus = TransactionStatus.FAILED.value
            )
            stopSelf()
            return
        }

        val subIdOverride = request.simSelection.takeUnless { it == OFFER_SIM_USE_GENERAL }

        if (SilentUssdOptimized.isNativeRequestUnresolved() ||
            SilentUssdOptimized.nativeSessionMayBePending()) {
            recordSignatureActivity(this, offer.name + ": Simple signature unavailable (native USSD session unresolved or pending)")
            processResponse(
                request,
                "Signature not available: native USSD session may still be pending on Android — restart the phone or toggle airplane mode before retrying.",
                forcedStatus = TransactionStatus.FAILED.value
            )
            stopSelf()
            return
        }

        val preflightDelivered = AtomicBoolean(false)
        val started = UssdHelper.dialUssd(
            context = this,
            ussdCode = previewCode,
            silentOnly = true,
            subIdOverride = subIdOverride,
            onSuccess = { firstScreen ->
                if (!preflightDelivered.compareAndSet(false, true)) return@dialUssd
                val result = SimpleSignatureVerifier.verify(offer, signatureTemplate, firstScreen)
                if (result.status != SimpleSignatureVerifier.Status.VERIFIED) {
                    recordSignatureActivity(this, (offer.name) + ": " + result.message)
                    processResponse(request, result.message, forcedStatus = TransactionStatus.FAILED.value)
                    stopSelf()
                    return@dialUssd
                }

                recordSignatureActivity(this, offer.name + ": Simple signature verified at position " + result.expectedPosition)
                // A failed verification never consumes a purchase token.
                if (!request.tokenPreDeducted && !UnlimitedManager(this).isActive() &&
                    !TokenManager(this).spendTokens(1)
                ) {
                    processResponse(
                        request,
                        "Verified, but insufficient tokens to purchase this bundle.",
                        forcedStatus = TransactionStatus.FAILED.value
                    )
                    stopSelf()
                    return@dialUssd
                }

                // Execute the exact original complete request, unchanged, only after verification.
                val purchaseRequest = request.copy(tokenPreDeducted = true)
                val purchaseDelivered = AtomicBoolean(false)
                val purchaseStarted = UssdHelper.dialUssd(
                    context = this,
                    ussdCode = request.code,
                    silentOnly = true,
                    subIdOverride = subIdOverride,
                    onSuccess = { response ->
                        if (purchaseDelivered.compareAndSet(false, true)) {
                            processResponse(purchaseRequest, response)
                            stopSelf()
                        }
                    },
                    onFailure = { error ->
                        if (purchaseDelivered.compareAndSet(false, true)) {
                            processResponse(
                                purchaseRequest,
                                "Signature verified, but silent purchase request failed: " + error,
                                forcedStatus = TransactionStatus.FAILED.value
                            )
                            stopSelf()
                        }
                    }
                )
                if (!purchaseStarted && purchaseDelivered.compareAndSet(false, true)) {
                    processResponse(
                        purchaseRequest,
                        "Signature verified, but the original request could not be started silently.",
                        forcedStatus = TransactionStatus.FAILED.value
                    )
                    stopSelf()
                }
            },
            onFailure = { error ->
                if (preflightDelivered.compareAndSet(false, true)) {
                    recordSignatureActivity(this, offer.name + ": Simple signature unavailable (" + error + ")")
                    processResponse(
                        request,
                        "Signature not available: silent first-screen verification failed (" + error + ").",
                        forcedStatus = TransactionStatus.FAILED.value
                    )
                    stopSelf()
                }
            }
        )
        if (!started && preflightDelivered.compareAndSet(false, true)) {
            recordSignatureActivity(this, offer.name + ": Simple signature unavailable on selected SIM")
            processResponse(
                request,
                "Signature not available: silent verification is unsupported on the selected SIM.",
                forcedStatus = TransactionStatus.FAILED.value
            )
            stopSelf()
        }
    }

    private fun startAdvanced(request: AutomationRequest) {
        val offer = request.offerId.takeIf { it >= 0 }?.let { OfferRepository.findById(this, it) }
        val automationCode = when {
            request.code.contains("pn", ignoreCase = true) -> request.code
            offer?.ussdCode?.contains("pn", ignoreCase = true) == true -> offer.ussdCode
            else -> request.code
        }
        val clean = automationCode.trim().replace("%23", "#").trimEnd('#')
        val parts = clean.split("*").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            processResponse(request, "Invalid USSD code", forcedStatus = "Failed")
            return
        }
        val dialCode = "*${parts[0]}#"
        val steps = (1 until parts.size).map {
            val part = parts[it]
            val isPhonePlaceholder = part.equals("pn", true) ||
                part.equals("[pn]", true) ||
                part.equals("phone", true) ||
                part.equals("phonenumber", true) ||
                part.equals("recipient", true) ||
                (request.phoneNumber.isNotBlank() && (
                    part == request.phoneNumber ||
                    part == UssdHelper.normalizeRecipientForUssdInput(request.phoneNumber)
                ))
            if (isPhonePlaceholder) "INPUT_PHONE" else part
        }
        Log.d(TAG, "startAdvanced: dial=$dialCode steps=$steps txId=${request.txId}")

        val keepAppUiVisible = request.returnToAppAggressively && BingwaMobileApp.wasInForegroundRecently()
        UssdNavigationService.configureUiReturn(keepAppUiVisible)

        UssdNavigationService.onDispatchComplete = { result ->
            Log.d(TAG, "ADVANCED COMPLETE txId=${request.txId} response='${result.finalResponse}'")
            handleAdvancedResult(request, result)
            UssdNavigationService.onDispatchComplete = null
        }

        val alreadyDialed = request.instantDialStarted || (
            UssdNavigationService.advancedActive &&
            UssdNavigationService.advancedInProgress &&
            UssdNavigationService.advancedDialCode == dialCode &&
            (UssdNavigationService.activeTxId == request.txId || request.txId < 0)
        )
        if (alreadyDialed) {
            Log.d(TAG, "startAdvanced: instant dial fast-path active for txId=${request.txId}; monitoring USSD session")
            return
        }

        // Arm the accessibility session before dialing so fast USSD popups are not missed.
        UssdNavigationService.clearPendingState()
        UssdNavigationService.advancedSteps = steps
        UssdNavigationService.advancedPhoneNumber = request.phoneNumber
        UssdNavigationService.advancedDialCode = dialCode
        UssdNavigationService.retryCount = 0
        UssdNavigationService.retryWindowStartedAt = 0L
        UssdNavigationService.advancedActive = true
        UssdNavigationService.advancedInProgress = true
        UssdNavigationService.currentStep = 0
        UssdNavigationService.advancedOfferId = request.offerId
        UssdNavigationService.advancedOfferName = offer?.name ?: request.offerName
        val effectiveSignatures = when {
            !offer?.learnedSignature.isNullOrEmpty() -> offer!!.learnedSignature
            !offer?.pendingLearnedSignature.isNullOrEmpty() -> offer!!.pendingLearnedSignature
            else -> emptyList()
        }
        // Keep learned records available for review, but enforce them only when the user enabled protection.
        UssdNavigationService.signatureGuardEnabled = request.signatureEnabled && !request.signatureLearning
        UssdNavigationService.signatureAction = request.signatureMode.ifBlank { offer?.signatureAction ?: "STOP" }
        UssdNavigationService.signatureLearningMode = request.signatureLearning
        UssdNavigationService.loadedSignatureSteps = effectiveSignatures
        if (request.signatureLearning) {
            UssdPerformanceDiagnostics.recordLearningInitiated(request.offerId, steps.size)
        }
        if (UssdNavigationService.loadedSignatureSteps.isNotEmpty()) {
            UssdPerformanceDiagnostics.recordLearningSignatureRetrieved(request.offerId, UssdNavigationService.loadedSignatureSteps.size)
        }
        UssdNavigationService.resetSignatureTracking()
        UssdNavigationService.beginAdvancedSessionMonitoring()
        UssdNavigationService.refreshRunningOverlay()

        try {
            val simTargets = resolveUssdSimTargets(
                context = this,
                selectionOverride = request.simSelection.takeUnless { it == OFFER_SIM_USE_GENERAL }
            )
            if (simTargets.isEmpty()) {
                UssdNavigationService.advancedSteps = emptyList()
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                processResponse(request, "Selected SIM slot is unavailable", forcedStatus = "Failed")
                return
            }
            val primaryTarget = simTargets.firstOrNull()
            UssdNavigationService.advancedSubId = primaryTarget?.subId ?: -1
            UssdNavigationService.advancedSimSlot = primaryTarget?.slotIndex ?: -1
            val started = startAdvancedDialAttempt(
                request = request,
                dialCode = dialCode,
                simTargets = simTargets,
                attemptIndex = 0
            )
            if (!started) {
                UssdNavigationService.advancedSteps = emptyList()
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                processResponse(request, "No dialer available", forcedStatus = "Failed")
                return
            }
        } catch (e: Exception) {
            UssdNavigationService.advancedSteps = emptyList()
            UssdNavigationService.advancedActive = false
            UssdNavigationService.advancedInProgress = false
            UssdNavigationService.onDispatchComplete = null
            processResponse(request, "Dial error: ${e.message}", forcedStatus = "Failed")
            return
        }
    }

    private fun startAdvancedDialAttempt(
        request: AutomationRequest,
        dialCode: String,
        simTargets: List<UssdSimTarget>,
        attemptIndex: Int
    ): Boolean {
        val target = simTargets.getOrNull(attemptIndex)
        if (target == null) return false
        if (!UssdHelper.canPlaceCalls(this)) {
            // Without CALL_PHONE the intent degrades to ACTION_DIAL, which only opens the dialer with
            // the number pre-filled: no USSD session is ever started, so waiting for popups is futile.
            Log.w(TAG, "ADVANCED aborted txId=${request.txId}: CALL_PHONE permission is missing")
            processResponse(
                request,
                "Phone permission is required to place USSD requests. Open Bingwa Mobile and allow phone calls, then try again.",
                forcedStatus = "Failed"
            )
            return false
        }
        val slotLabel = target?.slotIndex?.let { "slot ${it + 1}" } ?: "default telephony"
        val callIntent = UssdHelper.buildCallIntent(this, dialCode, target?.subId, target?.slotIndex)
        if (callIntent.resolveActivity(packageManager) == null) {
            val nextTarget = simTargets.getOrNull(attemptIndex + 1)
            if (nextTarget != null) {
                Log.w(TAG, "ADVANCED could not resolve dialer on $slotLabel; retrying slot ${nextTarget.slotIndex + 1}")
                return startAdvancedDialAttempt(request, dialCode, simTargets, attemptIndex + 1)
            }
            return false
        }

        return try {
            Log.d(TAG, "ADVANCED dialing txId=${request.txId} via $slotLabel")
            startActivity(callIntent)
            if (request.returnToAppAggressively && BingwaMobileApp.wasInForegroundRecently()) {
                UssdHelper.relaunchAppUi(
                    context = this,
                    aggressiveRetries = true
                )
            }
            true
        } catch (e: Exception) {
            val nextTarget = simTargets.getOrNull(attemptIndex + 1)
            if (nextTarget != null) {
                Log.w(
                    TAG,
                    "ADVANCED dial launch failed txId=${request.txId} on $slotLabel; retrying slot ${nextTarget.slotIndex + 1}",
                    e
                )
                startAdvancedDialAttempt(request, dialCode, simTargets, attemptIndex + 1)
            } else {
                throw e
            }
        }
    }

    private fun handleAdvancedResult(request: AutomationRequest, result: AdvancedDispatchResult) {
        if (request.signatureLearning) {
            handleSignatureLearningResult(request, result)
            return
        }

        if (result.changeDetected) {
            val title = if (result.autoAdjusted) "USSD Code Auto-Adjusted" else "USSD Code Change Detected"
            val message = buildSignatureSummary(request, result)
            OfferNotifications.notify(this, title, message)
        }

        val finalResponse = mergeSignatureResponse(request, result)
        val forcedStatus = when {
            result.outcomeUncertain -> TransactionStatus.PENDING.value
            result.changeDetected && !result.autoAdjusted -> TransactionStatus.FAILED.value
            else -> null
        }
        processResponse(
            request = request,
            response = finalResponse,
            forcedStatus = forcedStatus,
            transcript = buildPopupTranscript(result),
            suppressAutomationFallback = result.outcomeUncertain
        )
    }

    private fun handleSignatureLearningResult(request: AutomationRequest, result: AdvancedDispatchResult) {
        if (result.outcomeUncertain) {
            OfferNotifications.notify(
                this,
                "USSD Learning Incomplete",
                "The carrier popup sequence did not finish reliably. No signature was approved or saved; run Save & Learn again when the network is stable."
            )
            schedulePostExecutionAirtimeRefresh()
            stopSelf()
            return
        }
        val offerLabel = request.offerName.ifBlank { "offer #${request.offerId}" }
        if (request.offerId < 0) {
            OfferNotifications.notify(
                this,
                "USSD Signature Learning",
                "USSD signature learning finished, but the offer could not be identified for saving."
            )
            schedulePostExecutionAirtimeRefresh()
            stopSelf()
            return
        }
        if (result.learnedSignature.isEmpty() && result.learningCaptures.isEmpty()) {
            OfferNotifications.notify(
                this,
                "USSD Signature Learning",
                "The system could not learn a signature for $offerLabel. Open the offer and run Save & Learn again while the USSD menu is available."
            )
            schedulePostExecutionAirtimeRefresh()
            stopSelf()
            return
        }
        val updated = OfferRepository.stageSignatureReview(
            this,
            request.offerId,
            result.learnedSignature,
            result.learningCaptures
        )
        val learnedLabel = updated?.name ?: offerLabel
        val learningPhone = request.phoneNumber.ifBlank { "the provided test number" }
        val captureSummary = when {
            result.learningCaptures.isEmpty() -> ""
            else -> " Captured ${result.learningCaptures.size} USSD popup(s), the selected option, and the recorded text for each step."
        }
        val finalPopup = result.learningCaptures.lastOrNull()?.popupText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?.let { " Last popup: $it" }
            .orEmpty()
        val learnedSummary = when {
            result.learnedSignature.isNotEmpty() ->
                "The system learned ${result.learnedSignature.size} USSD menu step(s) for $learnedLabel using $learningPhone."
            else ->
                "The system recorded the USSD learning transcript for $learnedLabel using $learningPhone."
        }
        OfferNotifications.notify(
            this,
            "USSD Signature Ready For Approval",
            "$learnedSummary$captureSummary$finalPopup Review the learned steps in Bingwa Mobile, then approve or relearn before this signature replaces the saved one."
        )
        sendBroadcast(
            Intent("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
                .setPackage(packageName)
                .putExtra("offerId", request.offerId)
        )
        schedulePostExecutionAirtimeRefresh()
        stopSelf()
    }

    private fun mergeSignatureResponse(request: AutomationRequest, result: AdvancedDispatchResult): String {
        if (!result.changeDetected) return result.finalResponse
        val prefix = buildSignatureSummary(request, result)
        return if (result.finalResponse.isBlank()) prefix else "$prefix\n\nNetwork response:\n${result.finalResponse}"
    }

    private fun buildPopupTranscript(result: AdvancedDispatchResult): String {
        val entries = result.popupTranscript.toMutableList()
        val normalizedFinal = result.finalResponse.replace(Regex("\\s+"), " ").trim()
        if (normalizedFinal.isNotBlank() && entries.lastOrNull() != normalizedFinal) {
            entries += normalizedFinal
        }
        return entries.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n")
    }

    private fun buildSignatureSummary(request: AutomationRequest, result: AdvancedDispatchResult): String {
        val offerLabel = request.offerName.ifBlank { "this offer" }
        val suggestion = result.suggestedCode.takeIf { it.isNotBlank() }?.let {
            " Suggested updated code: $it."
        }.orEmpty()
        return if (result.autoAdjusted) {
            "The system detected a change in the USSD menu for $offerLabel and automatically matched the correct option.${if (result.changeSummary.isNotBlank()) " ${result.changeSummary}." else ""} Open the offer, update the saved USSD code, then run Save & Learn again to relearn the signature for future dispatches.$suggestion"
        } else {
            "The system detected changes in the USSD menu for $offerLabel and stopped the dispatch to avoid selecting the wrong bundle.${if (result.changeSummary.isNotBlank()) " ${result.changeSummary}." else ""} Review and update the offer, then run Save & Learn again to relearn the signature before dispatching again.$suggestion"
        }
    }

    private fun processResponse(
        request: AutomationRequest,
        response: String,
        forcedStatus: String? = null,
        transcript: String? = null,
        suppressAutomationFallback: Boolean = false
    ) {
        Log.d(TAG, "processResponse txId=${request.txId} mode=${request.mode} response='${response.take(150)}'")

        // Handle token purchase callback. The verdict is failure-first and scoped to the code we dialled,
        // so an unrelated bundle response can neither credit tokens nor swallow its own transaction.
        if (UssdNavigationService.hasPendingTokenPurchase()) {
            val forcedFailure = forcedStatus != null && forcedStatus.equals("Failed", ignoreCase = true)
            val purchaseResponse = if (forcedFailure) {
                "failed $response"
            } else {
                response
            }
            val purchaseResult = UssdNavigationService.resolveTokenPurchase(request.code, purchaseResponse)
            if (purchaseResult != null) {
                Log.d(TAG, "Token purchase callback: success=$purchaseResult txId=${request.txId}")
                schedulePostExecutionAirtimeRefresh()
                stopSelf()
                return
            }
        }

        // Handle balance callback
        UssdNavigationService.balanceCallback?.let { cb ->
            Log.d(TAG, "Balance callback invoked")
            cb(response)
            stopSelf()
            return
        }

        // Save transaction with response
        if (request.txId < 0) {
            schedulePostExecutionAirtimeRefresh()
            stopSelf()
            return
        }

        val status = forcedStatus ?: patternManager.determineResponseStatus(response)
        Log.d(TAG, "Status='$status' for txId=${request.txId}")

        if (shouldRetryRetriableFinalResponse(status, response)) {
            handleRetriableFinalResponse(request, response, status, transcript)
            AutomaticTransactionRecovery.onAttemptFinished(this, request.txId, TransactionStatus.RETRYING.value)
            schedulePostExecutionAirtimeRefresh()
            stopSelf()
            return
        }

        clearRetriableResponseRetryState(request.txId)

        // Save to storage
        saveTransactionResponse(request.txId, status, response, transcript)
        if (status.equals(TransactionStatus.FAILED.value, ignoreCase = true)) {
            if (AutomaticTransactionRecovery.isInsufficientAirtimeFailure(response)) {
                AutomaticTransactionRecovery.onTransactionFailed(this, request.txId, response)
            } else {
                AutomaticTransactionRecovery.onZeroTokenBlocked(this, request.txId)
            }
        }
        AutomaticTransactionRecovery.onAttemptFinished(this, request.txId, status)
        // Broadcast update to UI
        sendBroadcastUpdate(request.txId, status, response)

        when (status) {
            "Pending" -> if (!suppressAutomationFallback) handleDailyLimitPending(request, response)
            "Failed" -> if (!suppressAutomationFallback) handleFailedWithFallback(request, response)
            else -> Unit
        }
        schedulePostExecutionAirtimeRefresh()
        stopSelf()
    }

    private fun handleFailedWithFallback(request: AutomationRequest, response: String) {
        val config = DailyLimitPolicy.load(this)
        if (!config.fallbackEnabled) {
            MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
            return
        }

        if (DailyLimitPolicy.isAlreadyRecommendedResponse(response)) {
            val started = attemptFallback(
                request = request,
                originalTx = loadTransactionById(this, request.txId),
                response = response,
                reasonLabel = "daily limit",
                trigger = DailyLimitPolicy.TRIGGER_ALREADY_RECOMMENDED
            )
            if (!started) MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
            return
        }

        if (isOfferNotFoundFailure(response)) {
            val started = attemptFallback(
                request = request,
                originalTx = loadTransactionById(this, request.txId),
                response = response,
                reasonLabel = "offer not found",
                trigger = DailyLimitPolicy.TRIGGER_OFFER_UNAVAILABLE
            )
            if (!started) MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
            return
        }

        MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
    }

    private fun isOfferNotFoundFailure(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("ussd code change detected") ||
            (lower.contains("detected changes in the ussd menu") && lower.contains("save & learn")) ||
            (lower.contains("stopped the dispatch") && lower.contains("ussd menu")) ||
            lower.contains("no dialer available") ||
            lower.contains("could not be identified for saving")
    }

    private fun attemptFallback(
        request: AutomationRequest,
        originalTx: Transaction?,
        response: String,
        reasonLabel: String,
        trigger: String
    ): Boolean {
        val tx = originalTx
        val fallbackOffers = DailyLimitPolicy.resolveFallbackOffers(
            context = this,
            originalOfferId = request.offerId,
            trigger = trigger
        )

        fallbackOffers.forEachIndexed { index, fallbackOffer ->
            val fallbackStarted = startFallbackDispatch(request, fallbackOffer, tx)
            if (fallbackStarted) {
                val note = buildString {
                    append("Original offer stopped ($reasonLabel). Fallback offer started: ${fallbackOffer.name}.")
                    if (index > 0) {
                        append(" It was selected after earlier fallback plan(s) could not be started.")
                    }
                }
                val message = "$response\n\n$note"
                saveTransactionResponse(request.txId, "Cancelled", message)
                sendBroadcastUpdate(request.txId, "Cancelled", message)
                OfferNotifications.notify(
                    this,
                    "Fallback Dispatched",
                    "${request.offerName.ifBlank { "Original offer" }} stopped ($reasonLabel). ${fallbackOffer.name} was started for ${request.phoneNumber}."
                )
                return true
            }
        }
        return false
    }

    private fun handleDailyLimitPending(request: AutomationRequest, response: String) {
        val config = DailyLimitPolicy.load(this)
        val originalTx = loadTransactionById(this, request.txId)
        if (config.fallbackEnabled) {
            val fallbackOffers = DailyLimitPolicy.resolveFallbackOffers(
                context = this,
                originalOfferId = request.offerId,
                trigger = DailyLimitPolicy.TRIGGER_ALREADY_RECOMMENDED
            )

            fallbackOffers.forEachIndexed { index, fallbackOffer ->
                val fallbackStarted = startFallbackDispatch(request, fallbackOffer, originalTx)
                if (fallbackStarted) {
                    val note = buildString {
                        append("Original offer stopped because of the daily limit. Fallback offer started: ${fallbackOffer.name}.")
                        if (index > 0) {
                            append(" It was selected after earlier fallback plan(s) could not be started.")
                        }
                    }
                    val message = "$response\n\n$note"
                    saveTransactionResponse(request.txId, "Cancelled", message)
                    sendBroadcastUpdate(request.txId, "Cancelled", message)
                    OfferNotifications.notify(
                        this,
                        "Fallback Dispatched",
                        "${request.offerName.ifBlank { "Original offer" }} hit the daily limit. ${fallbackOffer.name} was started for ${request.phoneNumber}."
                    )
                    return
                }
            }
        }

        if (config.mode == DailyLimitPolicy.MODE_NOTICE_ONLY) {
            val note = if (config.repeatNoticeEnabled) {
                "Reply 1 to send another number today, or reply 2 to confirm tomorrow morning dispatch."
            } else {
                "Waiting for an alternative number or a manual retry tomorrow."
            }
            val message = "$response\n\n$note"
            saveTransactionResponse(request.txId, "Pending", message)
            sendBroadcastUpdate(request.txId, "Pending", message)
            if (config.repeatNoticeEnabled) {
                originalTx?.phoneNumber?.takeIf { it.isNotBlank() }?.let { customerPhone ->
                    DailyLimitPolicy.beginReplyMenu(this, customerPhone, request.txId)
                }
            }
            sendCustomerOutcomeSms(this, "limit_notice", originalTx)
            OfferNotifications.notify(
                this,
                "Daily Limit Notice",
                "${request.phoneNumber} has already received today's offer. A notice was sent instead of auto-queueing."
            )
        } else {
            sendCustomerOutcomeSms(this, "pending", originalTx)
            scheduleRetryTomorrow(request)
        }
    }

    private fun startFallbackDispatch(
        request: AutomationRequest,
        fallbackOffer: OfferItem,
        originalTx: Transaction?
    ): Boolean {
        return if (RelayManager.isPrimary(this) && fallbackOffer.targetDevice.uppercase() == "RELAY") {
            RelayManager.forwardBuyAmount(this, request.phoneNumber, fallbackOffer.price)
        } else {
            val finalCode = UssdHelper.normalizeUssdCode(fallbackOffer.ussdCode, request.phoneNumber)
            if (finalCode.isBlank()) return false
            val fallbackTxId = createPendingTransaction(
                this,
                fallbackOffer.name,
                "KSh ${fallbackOffer.price}",
                request.phoneNumber,
                finalCode,
                originalTx?.clientName.orEmpty(),
                status = if (originalTx?.showInRecent == true) TransactionStatus.PROCESSING.value else TransactionStatus.PENDING.value,
                source = originalTx?.source ?: TX_SOURCE_AUTOMATED,
                showInRecent = originalTx?.showInRecent ?: true,
                offerId = fallbackOffer.id
            )
            if (fallbackTxId < 0) return false
            startOfferAutomation(
                offer = fallbackOffer,
                phoneNumber = request.phoneNumber,
                txId = fallbackTxId,
                finalCode = finalCode,
                mode = fallbackOffer.executionMode
            )
            true
        }
    }

    private fun saveTransactionResponse(txId: Int, status: String, response: String, transcript: String? = null) {
        val saved = saveTransactionOutcome(this, txId, status, response, transcript)
        if (saved) {
            Log.d(TAG, "SAVED txId=$txId status=$status response='${response.take(80)}'")
        } else {
            Log.w(TAG, "Transaction $txId not found in list")
        }
    }

    private fun buildAutomationIntent(context: Context, request: AutomationRequest, action: String? = null): Intent =
        Intent(context, AutomationService::class.java).apply {
            this.action = action
            putExtra("mode", request.mode)
            putExtra("code", request.code)
            putExtra("phoneNumber", request.phoneNumber)
            putExtra("txId", request.txId)
            putExtra("offerId", request.offerId)
            putExtra("offerName", request.offerName)
            putExtra("simSelection", request.simSelection)
            putExtra("signatureEnabled", request.signatureEnabled)
            putExtra("simpleSignatureEnabled", request.simpleSignatureEnabled)
            putExtra("signatureMode", request.signatureMode)
            putExtra("signatureLearning", request.signatureLearning)
            putExtra("returnToAppAggressively", request.returnToAppAggressively)
            putExtra("tokenPreDeducted", request.tokenPreDeducted)
            putExtra("instantDialStarted", request.instantDialStarted)
        }

    private fun scheduleRetriableResponseRetry(request: AutomationRequest, delayMs: Long): Boolean {
        return runCatching {
            val triggerAt = System.currentTimeMillis() + delayMs
            val intent = buildAutomationIntent(this, request, ACTION_RETRY_RETRIABLE_RESPONSE)
            val pi = PendingIntent.getService(
                this,
                request.txId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val scheduled = AlarmCompat.scheduleRtcWakeup(
                context = this,
                triggerAtMillis = triggerAt,
                pendingIntent = pi,
                preferExact = false,
                allowWhileIdle = true
            )
            if (scheduled) {
                Log.d(
                    TAG,
                    "Scheduled retriable-response retry for txId=${request.txId} in ${delayMs}ms"
                )
            }
            scheduled
        }.getOrElse { error ->
            Log.e(TAG, "scheduleRetriableResponseRetry failed for txId=${request.txId}", error)
            false
        }
    }

    private fun shouldRetryRetriableFinalResponse(status: String, response: String): Boolean {
        if (response.isBlank()) return false
        // Insufficient airtime is a terminal FAILED outcome until a top-up SMS arrives;
        // do not let a generic "error" keyword route it into the separate transient retry loop.
        if (AutomaticTransactionRecovery.isInsufficientAirtimeFailure(response)) return false
        if (status.equals(TransactionStatus.SUCCESS.value, ignoreCase = true) ||
            status.equals(TransactionStatus.PENDING.value, ignoreCase = true) ||
            status.equals(TransactionStatus.CANCELLED.value, ignoreCase = true)
        ) {
            return false
        }
        return patternManager.matchesRetriableFinalPattern(response)
    }

    private fun handleRetriableFinalResponse(
        request: AutomationRequest,
        response: String,
        originalStatus: String,
        transcript: String?
    ) {
        val now = System.currentTimeMillis()
        val currentState = loadRetriableResponseRetryState(request.txId)
            ?: RetriableResponseRetryState(
                windowStartAtMillis = now,
                completedWindows = 0,
                nextAttemptStartsNewWindow = false,
                totalAttempts = 0
            )
        val windowStart = currentState.windowStartAtMillis.takeIf { it > 0L } ?: now
        val elapsedInWindow = (now - windowStart).coerceAtLeast(0L)
        val withinActiveWindow = elapsedInWindow < ACTIVE_RETRY_WINDOW_MS
        val delayMs = if (withinActiveWindow) {
            ACTIVE_RETRY_INTERVAL_MS
        } else if (currentState.completedWindows == 0) {
            FIRST_BACKOFF_MS
        } else {
            REPEATED_BACKOFF_MS
        }
        val nextState = if (withinActiveWindow) {
            currentState.copy(
                windowStartAtMillis = windowStart,
                nextAttemptStartsNewWindow = false,
                totalAttempts = currentState.totalAttempts + 1
            )
        } else {
            currentState.copy(
                completedWindows = currentState.completedWindows + 1,
                nextAttemptStartsNewWindow = true,
                totalAttempts = currentState.totalAttempts + 1
            )
        }
        if (!scheduleRetriableResponseRetry(request, delayMs)) {
            clearRetriableResponseRetryState(request.txId)
            saveTransactionResponse(request.txId, originalStatus, response, transcript)
            sendBroadcastUpdate(request.txId, originalStatus, response)
            if (originalStatus == TransactionStatus.FAILED.value) {
                handleFailedWithFallback(request, response)
            }
            return
        }
        saveRetriableResponseRetryState(request.txId, nextState)
        val retryMessage = buildRetriableResponseMessage(
            response = response,
            withinActiveWindow = withinActiveWindow,
            delayMs = delayMs,
            state = nextState,
            elapsedInWindow = elapsedInWindow
        )
        saveTransactionResponse(request.txId, TransactionStatus.RETRYING.value, retryMessage, transcript)
        sendBroadcastUpdate(request.txId, TransactionStatus.RETRYING.value, retryMessage)
    }

    private fun buildRetriableResponseMessage(
        response: String,
        withinActiveWindow: Boolean,
        delayMs: Long,
        state: RetriableResponseRetryState,
        elapsedInWindow: Long
    ): String {
        val timingNote = if (withinActiveWindow) {
            val remainingSeconds = ((ACTIVE_RETRY_WINDOW_MS - elapsedInWindow).coerceAtLeast(0L) + 999L) / 1_000L
            "Automatic retry is still active. Next retry in ${formatRetryDelay(delayMs)}. Active retry window remaining: ${remainingSeconds}s."
        } else {
            val backoffLabel = if (state.completedWindows == 1) "5 minutes" else "10 minutes"
            "Automatic retry window was exhausted. Waiting $backoffLabel before starting another 1-minute retry window."
        }
        return "$response\n\n$timingNote"
    }

    private fun formatRetryDelay(delayMs: Long): String = when {
        delayMs % (60_000L) == 0L -> {
            val minutes = delayMs / 60_000L
            if (minutes == 1L) "1 minute" else "$minutes minutes"
        }
        delayMs % 1_000L == 0L -> {
            val seconds = delayMs / 1_000L
            if (seconds == 1L) "1 second" else "$seconds seconds"
        }
        else -> "${delayMs}ms"
    }

    private fun armRetriableResponseWindow(txId: Int) {
        if (txId < 0) return
        val current = loadRetriableResponseRetryState(txId) ?: return
        if (!current.nextAttemptStartsNewWindow && current.windowStartAtMillis > 0L) return
        saveRetriableResponseRetryState(
            txId,
            current.copy(
                windowStartAtMillis = System.currentTimeMillis(),
                nextAttemptStartsNewWindow = false
            )
        )
    }

    private fun retriableResponsePrefs() =
        getSharedPreferences(RETRIABLE_RESPONSE_PREFS, Context.MODE_PRIVATE)

    private fun loadRetriableResponseRetryState(txId: Int): RetriableResponseRetryState? {
        if (txId < 0) return null
        val prefs = retriableResponsePrefs()
        if (!prefs.contains("tx_${txId}_windowStart")) return null
        return RetriableResponseRetryState(
            windowStartAtMillis = prefs.getLong("tx_${txId}_windowStart", 0L),
            completedWindows = prefs.getInt("tx_${txId}_completedWindows", 0),
            nextAttemptStartsNewWindow = prefs.getBoolean("tx_${txId}_nextWindow", false),
            totalAttempts = prefs.getInt("tx_${txId}_attempts", 0)
        )
    }

    private fun saveRetriableResponseRetryState(txId: Int, state: RetriableResponseRetryState) {
        if (txId < 0) return
        retriableResponsePrefs()
            .edit()
            .putLong("tx_${txId}_windowStart", state.windowStartAtMillis)
            .putInt("tx_${txId}_completedWindows", state.completedWindows)
            .putBoolean("tx_${txId}_nextWindow", state.nextAttemptStartsNewWindow)
            .putInt("tx_${txId}_attempts", state.totalAttempts)
            .apply()
    }

    private fun clearRetriableResponseRetryState(txId: Int) {
        if (txId < 0) return
        cancelRetriableResponseRetryAlarm(txId)
        retriableResponsePrefs()
            .edit()
            .remove("tx_${txId}_windowStart")
            .remove("tx_${txId}_completedWindows")
            .remove("tx_${txId}_nextWindow")
            .remove("tx_${txId}_attempts")
            .apply()
    }

    private fun cancelRetriableResponseRetryAlarm(txId: Int) {
        if (txId < 0) return
        runCatching {
            cancelRetriableResponseRetry(this, txId)
        }.onFailure {
            Log.w(TAG, "cancelRetriableResponseRetry failed for txId=$txId", it)
        }
    }

    private fun scheduleRetryTomorrow(request: AutomationRequest) {
        try {
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val intent = buildAutomationIntent(this, request, ACTION_RETRY_PENDING)
            val pi = PendingIntent.getService(
                this, request.txId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val scheduled = AlarmCompat.scheduleRtcWakeup(
                context = this,
                triggerAtMillis = tomorrow.timeInMillis,
                pendingIntent = pi,
                preferExact = true,
                allowWhileIdle = true
            )
            Log.d(TAG, "Retry scheduled=$scheduled for ${tomorrow.time}")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRetryTomorrow failed", e)
        }
    }

    private fun sendBroadcastUpdate(txId: Int, status: String, response: String) {
        Handler(Looper.getMainLooper()).post {
            sendBroadcast(
                Intent("com.bingwa.mobile.TX_UPDATED")
                    .setPackage(packageName)
                    .putExtra("txId", txId)
                    .putExtra("status", status)
                    .putExtra("response", response)
            )
        }
    }

    override fun onDestroy() {
        UssdWakelock.release()
        stopForegroundCompat()
        schedulePostExecutionAirtimeRefresh()
        super.onDestroy()
    }

    private fun schedulePostExecutionAirtimeRefresh() {
        BalanceChecker.scheduleAirtimeRefresh(
            context = this,
            reason = "USSD execution"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "USSD Automation", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bingwa Mobile")
            .setContentText("Running a USSD automation task")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .build()

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
