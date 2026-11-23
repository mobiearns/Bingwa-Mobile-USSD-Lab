package com.bingwa.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import java.util.Locale

import com.bingwa.mobile.UssdResponsePatternManager
import com.bingwa.mobile.ForegroundServiceTypes

class UssdNavigationService : AccessibilityService() {

    companion object {
        const val TAG = "UssdNavigation"
        @Volatile
        private var activeInstance: UssdNavigationService? = null

        /** Returns the running accessibility service when available, without exposing its mutable singleton. */
        fun currentServiceContext(): Context? = activeInstance
        @Volatile
        private var pendingAdvancedArm: Boolean = false

        var airtimeBalance          = "N/A"
        var balanceCallback         : ((String) -> Unit)?  = null
        var tokenPurchaseCallback   : ((Boolean) -> Unit)? = null

        var advancedSteps           : List<String> = emptyList()
        var advancedPhoneNumber     : String       = ""
        var advancedDialCode        : String       = ""
        var advancedOfferId         : Int          = -1
        var advancedOfferName       : String       = ""
        var advancedActive          : Boolean      = false
        var advancedInProgress      : Boolean      = false
        var currentStep             : Int          = 0
        var retryCount              : Int          = 0
        var retryWindowStartedAt    : Long         = 0L
        var lastRedialElapsed       : Long         = 0L
        var signatureGuardEnabled   : Boolean      = false
        var signatureAction         : String       = "STOP"
        var signatureLearningMode   : Boolean      = false
        var loadedSignatureSteps    : List<UssdSignatureStep> = emptyList()

        @Volatile
        var advancedSubId           : Int          = -1
        @Volatile
        var advancedSimSlot         : Int          = -1

        var onDispatchComplete      : ((result: AdvancedDispatchResult) -> Unit)? = null
        @Volatile
        private var tokenPurchaseCode: String = ""
        @Volatile
        private var tokenPurchaseSettled: Boolean = false
        @Volatile
        private var foregroundUiActive: Boolean = false
        @Volatile
        private var foregroundUiUntilElapsed: Long = 0L
        @Volatile
        private var keepAppUiVisibleEnabled: Boolean = true
        @Volatile
        private var uiReturnSuppressed: Boolean = false

        private const val MAX_RETRY_WINDOW_MS    = 60_000L
        private const val SHOW_RUNNING_OVERLAY   = false
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
        private val MENU_OPTION_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?\s*(.+)$""")
        private val MENU_OPTION_NUMBER_ONLY_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?$""")
        // Reuse the compiled menu detector on the accessibility callback hot path.
        private val MENU_LIKE_REGEX = Regex("""\b\d+\s*[\)\].:\-]""")

        private val USSD_PACKAGES = UssdNavigationConfig.USSD_PACKAGES
        private val USSD_PACKAGE_HINTS = UssdNavigationConfig.USSD_PACKAGE_HINTS
        private val BLOCKED_PACKAGES = setOf(
            "com.bingwa.mobile", "com.android.systemui", "com.android.launcher",
            "com.google.android.apps.nexuslauncher", "com.miui.home",
            "com.sec.android.app.launcher", "com.huawei.android.launcher",
            "com.android.settings", "com.google.android.gms", "com.android.keyguard",
            "com.android.packageinstaller"
        )
        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher"
        )
        private val USSD_HINTS = listOf(
            "enter", "ussd", "choose", "select", "option", "menu", "number", "amount",
            "sambaza", "tuma", "please enter", "enter phone", "enter amount",
            "safaricom", "airtel", "telkom", "faiba", "reply", "continue", "submit",
            "balance", "airtime", "ksh", "kes", "bundle", "data", "account", "pin",
            "send money", "confirm", "retry", "proceed", "voucher", "recipient", "mobile",
            // Common English/Swahili USSD wording used by Kenyan operators.
            "bando", "salio", "chagua", "endelea", "thibitisha", "weka", "ingiza",
            "jaza", "kiasi", "namba", "nambari", "simu", "mteja", "huduma",
            "omba", "ombi", "tafadhali", "mpokeaji", "malipo", "nunua", "sajili",
            "entrez", "montant", "numéro", "solde", "continuer", "confirmer",
            "أدخل", "رصيد", "تأكيد", "press", "dial", "call", "response", "respond", "tap"
        )
        // Strong, domain-specific indicators used only when capturing learned
        // signatures. Generic words such as "reply", "send", "tap" and "continue"
        // are intentionally excluded because they also occur in SMS notifications.
        private val SIGNATURE_USSD_HINTS = listOf(
            "ussd", "safaricom", "airtel", "telkom", "faiba", "sambaza",
            "salio", "bando", "kiasi", "nambari", "chagua", "weka", "ingiza",
            "mpokeaji", "malipo", "nunua", "huduma", "voucher", "airtime",
            "bundle", "balance", "account", "pin", "amount", "recipient",
            "enter amount", "enter phone", "enter number", "select option",
            "choose option", "please enter", "confirm transaction"
        )
        private val SEND_BUTTON_LABELS = listOf(
            "send", "ok", "tuma", "call", "sambaza", "enda", "confirm",
            "reply", "next", "continue", "submit", "proceed", "accept", "yes", "done",
            "endelea", "thibitisha", "sawa", "kubali", "jibu", "tuma sasa",
            "confirmar", "envoyer", "suivant", "continuer", "oui", "go", "enter", "dial",
            "execute", "موافق", "إرسال"
        )
        private val SEND_VIEW_ID_HINTS = listOf(
            "send", "submit", "reply", "continue", "next", "confirm", "positive", "ok",
            "button1", "positivebutton", "positive_button", "dialog_button", "send_button",
            "action_button", "btn_ok", "btn_confirm", "btn_send", "btn_positive",
            "alertdialog_button", "right_button", "primary_button"
        )
        private val INPUT_VIEW_ID_HINTS = listOf(
            "input", "reply", "entry", "message", "ussd", "number", "phone", "amount", "pin",
            "edit", "text", "answer", "field", "value", "data", "query", "response"
        )
        private val INPUT_FIELD_HINTS = listOf(
            "enter", "input", "reply", "phone", "number", "amount", "pin", "account", "mobile", "recipient",
            "text", "answer", "value", "type here", "write here",
            "weka", "ingiza", "jaza", "kiasi", "namba", "nambari", "simu", "mteja"
        )
        private val PHONE_INPUT_HINTS = listOf(
            "phone", "phone number", "number", "mobile", "mobile number", "recipient", "recipient number",
            "customer", "customer number", "subscriber", "subscriber number", "beneficiary",
            "beneficiary number", "msisdn", "tel", "telephone", "contact", "line number",
            "enter phone", "enter number", "enter mobile", "enter recipient", "enter customer",
            "namba", "nambari", "simu", "mteja", "mpokeaji", "anayepokea", "tuma kwa"
        )
        private val DISMISS_BUTTON_LABELS = listOf(
            "ok", "cancel", "close", "dismiss", "back", "no", "exit", "annuler", "fermer",
            "ghairi", "funga", "rudi", "hapana", "non", "إلغاء", "خروج"
        )
        private val DISMISS_VIEW_ID_HINTS = listOf(
            "cancel", "dismiss", "close", "negative", "back", "no", "exit",
            "button2", "negativebutton", "negative_button", "btn_cancel", "btn_dismiss",
            "btn_negative", "left_button"
        )
        private val NON_USSD_DIALOG_HINTS = listOf(
            "complete action", "use by default", "just once", "always",
            "allow", "deny", "permission", "grant", "not now",
            "isn't responding", "is not responding", "stopped", "keeps stopping", "close app",
            "want to access", "allow to make", "turn on battery saver"
        )
        private val TRANSIENT_RESPONSE_HINTS = listOf(
            "ussd running", "running", "processing", "please wait", "wait", "loading",
            "requesting", "sending", "fetching", "working", "in progress",
            "tafadhali subiri", "subiri kidogo", "inaendelea", "inafanyiwa kazi",
            "inachakatwa", "inaomba", "ombi linaendelea"
        )
        private val EDITABLE_CLASS_HINTS = listOf(
            "EditText", "TextInputEditText", "AutoCompleteTextView",
            "MultiAutoCompleteTextView", "ExtractEditText",
            "com.samsung.android.widget.SamsungEditText", "android.widget.EditText"
        )
        private val errorKeywords = UssdNavigationConfig.errorKeywords

        private val adjustedStepInputs = linkedMapOf<Int, String>()
        private val learnedSignatureSteps = mutableListOf<UssdSignatureStep>()
        private val learningCaptures = mutableListOf<UssdLearningCapture>()
        private val popupTranscript = mutableListOf<String>()
        private val detectedChangeNotes = mutableListOf<String>()
        private var signatureChangeDetected = false
        private var signatureAutoAdjusted = false

        fun resetSignatureTracking() {
            adjustedStepInputs.clear()
            learnedSignatureSteps.clear()
            learningCaptures.clear()
            popupTranscript.clear()
            detectedChangeNotes.clear()
            signatureChangeDetected = false
            signatureAutoAdjusted = false
        }

        fun isPotentialUssdPackage(pkg: String): Boolean {
            if (pkg.isBlank()) return false
            val lower = pkg.lowercase()
            if (lower.contains("launcher") || lower.contains("home") || pkg in BLOCKED_PACKAGES || pkg in LAUNCHER_PACKAGES) {
                return false
            }
            if (pkg == "android") return true
            if (pkg in USSD_PACKAGES) return true
            return USSD_PACKAGE_HINTS.any { lower.contains(it) }
        }

        fun isPhoneOrStringStep(step: String): Boolean {
            val trimmed = step.trim()
            if (trimmed.equals("INPUT_PHONE", ignoreCase = true)) return true
            if (trimmed.equals("pn", ignoreCase = true)) return true
            if (trimmed.startsWith("07") || trimmed.startsWith("01") || trimmed.startsWith("254") || trimmed.startsWith("+254")) {
                val digits = trimmed.replace("+", "")
                if (digits.all(Char::isDigit) && digits.length in 9..14) return true
            }
            if (!trimmed.all(Char::isDigit)) return true
            val num = trimmed.toLongOrNull()
            if (num != null && (trimmed.length >= 3 || num > 9L) && !trimmed.startsWith("00")) {
                return true
            }
            return false
        }

        fun refreshRunningOverlay() {
            activeInstance?.let { instance ->
                instance.handler.post { instance.updateRunningOverlay() }
            }
        }

        fun beginAdvancedSessionMonitoring() {
            activeInstance?.let { instance ->
                pendingAdvancedArm = false
                instance.handler.post { instance.handleAdvancedSessionArmed() }
            } ?: run {
                pendingAdvancedArm = true
            }
        }

         fun clearPendingState() {
             val instance = activeInstance ?: return
             instance.processStepJob?.cancel()
             instance.stepTimeoutJob?.cancel()
             instance.pendingStepAdvanceTimeoutJob?.cancel()
             instance.pendingStepAdvanceKickJob?.cancel()
             instance.pendingAdvanceKickJob?.cancel()
             instance.clearPendingAdvance()
             instance.clearPendingStepAdvance()
             instance.clearRootRecoveryState()
             instance.clearInputWriteMarker()
             instance.isProcessing = false
             instance.hasSeenAdvancedPopup = false
             instance.hasSeenForegroundPopup = false
         }

         @Volatile
         var activeTxId: Int = -1

         fun armAndDialImmediately(
             context: Context,
             code: String,
             phoneNumber: String,
             txId: Int,
             offer: OfferItem?,
             signatureEnabled: Boolean,
             signatureMode: String,
             signatureLearning: Boolean,
             returnToAppAggressively: Boolean
         ): Boolean {
             val clean = code.trim().replace("%23", "#").trimEnd('#')
             val parts = clean.split("*").filter { it.isNotEmpty() }
             if (parts.isEmpty()) return false
             val dialCode = "*${parts[0]}#"
             val steps = (1 until parts.size).map {
                 val part = parts[it]
                 val isPhonePlaceholder = part.equals("pn", true) ||
                     part.equals("[pn]", true) ||
                     part.equals("phone", true) ||
                     part.equals("phonenumber", true) ||
                     part.equals("recipient", true) ||
                     (phoneNumber.isNotBlank() && (
                         part == phoneNumber ||
                         part == UssdHelper.normalizeRecipientForUssdInput(phoneNumber)
                     ))
                 if (isPhonePlaceholder) "INPUT_PHONE" else part
             }

             clearPendingState()
             advancedSteps = steps
             advancedPhoneNumber = phoneNumber
             advancedDialCode = dialCode
             retryCount = 0
             retryWindowStartedAt = 0L
             advancedActive = true
             advancedInProgress = true
             currentStep = 0
             advancedOfferId = offer?.id ?: -1
             advancedOfferName = offer?.name.orEmpty()
             activeTxId = txId
             val effectiveSignatures = when {
                 !offer?.learnedSignature.isNullOrEmpty() -> offer!!.learnedSignature
                 !offer?.pendingLearnedSignature.isNullOrEmpty() -> offer!!.pendingLearnedSignature
                 else -> emptyList()
             }
             signatureGuardEnabled = signatureEnabled && !signatureLearning
             signatureAction = (signatureMode.ifBlank { offer?.signatureAction ?: "STOP" }).uppercase()
             signatureLearningMode = signatureLearning
             loadedSignatureSteps = effectiveSignatures
             resetSignatureTracking()
             beginAdvancedSessionMonitoring()
             refreshRunningOverlay()

             val keepAppUiVisible = returnToAppAggressively && BingwaMobileApp.wasInForegroundRecently()
             configureUiReturn(keepAppUiVisible)
             UssdWakelock.acquire(context.applicationContext, 90_000L)

             if (!UssdHelper.canPlaceCalls(context)) return false

             val simTargets = resolveUssdSimTargets(
                 context = context,
                 selectionOverride = (offer?.simSelection ?: OFFER_SIM_USE_GENERAL).takeUnless { it == OFFER_SIM_USE_GENERAL }
             )
             val primaryTarget = simTargets.firstOrNull()
             advancedSubId = primaryTarget?.subId ?: -1
             advancedSimSlot = primaryTarget?.slotIndex ?: -1

             val callIntent = UssdHelper.buildCallIntent(context, dialCode, primaryTarget?.subId, primaryTarget?.slotIndex)
             callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

             return try {
                 Log.d(TAG, "armAndDialImmediately: dialing $dialCode txId=$txId slot=${advancedSimSlot + 1}")
                 val dialerContext = activeInstance ?: context
                 dialerContext.startActivity(callIntent)
                 if (keepAppUiVisible) {
                     UssdHelper.relaunchAppUi(context, delayMs = 120L, aggressiveRetries = false)
                 }
                 true
             } catch (e: Exception) {
                 Log.w(TAG, "armAndDialImmediately failed: ${e.message}", e)
                 false
             }
         }

        fun armForegroundUi(timeoutMs: Long = 120_000L) {
            foregroundUiActive = true
            foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + timeoutMs
            refreshRunningOverlay()
        }

        fun disarmForegroundUi() {
            if (!foregroundUiActive) return
            foregroundUiActive = false
            foregroundUiUntilElapsed = 0L
            refreshRunningOverlay()
        }

        fun configureUiReturn(keepVisible: Boolean) {
            keepAppUiVisibleEnabled = keepVisible
            uiReturnSuppressed = false
            refreshRunningOverlay()
        }

        fun suppressUiReturn() {
            uiReturnSuppressed = true
            refreshRunningOverlay()
        }

        fun onAppUiForegrounded() {
            if (uiReturnSuppressed) {
                uiReturnSuppressed = false
                refreshRunningOverlay()
            }
        }

        fun isBusyForBalanceCheck(): Boolean =
            advancedActive ||
                advancedInProgress ||
                signatureLearningMode ||
                tokenPurchaseCallback != null ||
                isForegroundUiActive()

        private fun refreshForegroundUi(timeoutMs: Long = 35_000L) {
            if (!foregroundUiActive) return
            foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + timeoutMs
        }

        private fun isForegroundUiActive(): Boolean =
            foregroundUiActive && SystemClock.elapsedRealtime() < foregroundUiUntilElapsed

        /**
         * Registers the single outstanding token purchase.
         * Returns false when one is already in flight, so a second tap can never overwrite (and later
         * claim) the result of the first one.
         */
        fun beginTokenPurchase(code: String, callback: (Boolean) -> Unit): Boolean {
            if (tokenPurchaseCallback != null) return false
            tokenPurchaseCode = code.trim()
            tokenPurchaseSettled = false
            tokenPurchaseCallback = callback
            Log.d(TAG, "beginTokenPurchase: armed for '$tokenPurchaseCode'")
            return true
        }

        fun hasPendingTokenPurchase(): Boolean = tokenPurchaseCallback != null

        fun pendingTokenPurchaseCode(): String = tokenPurchaseCode

        /**
         * Resolves the pending purchase, but only for a response that belongs to the code we dialled.
         * Returns null when [requestCode] is a different dispatch, so an unrelated bundle response can
         * neither credit tokens nor swallow that bundle's transaction.
         */
        fun resolveTokenPurchase(requestCode: String, response: CharSequence?): Boolean? {
            val callback = tokenPurchaseCallback ?: return null
            val expected = tokenPurchaseCode
            if (expected.isNotBlank() && !sameUssdCode(expected, requestCode)) {
                Log.d(TAG, "resolveTokenPurchase: ignoring unrelated response for '$requestCode'")
                return null
            }
            if (tokenPurchaseSettled) return false
            tokenPurchaseSettled = true
            tokenPurchaseCallback = null
            tokenPurchaseCode = ""
            val success = UssdResponsePatternManager.confirmedPurchaseSuccess(response)
            Log.d(TAG, "resolveTokenPurchase: success=$success response='${response?.toString()?.take(120)}'")
            callback(success)
            return success
        }

        fun cancelTokenPurchase(reason: String) {
            val callback = tokenPurchaseCallback ?: return
            tokenPurchaseSettled = true
            tokenPurchaseCallback = null
            tokenPurchaseCode = ""
            Log.w(TAG, "cancelTokenPurchase: $reason")
            callback(false)
        }

        private fun sameUssdCode(first: String, second: String): Boolean =
            first.trim().replace("%23", "#") == second.trim().replace("%23", "#")
    }

    private val handler            = Handler(Looper.getMainLooper())
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread
    private val processingScope    = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val patternManager by lazy { UssdResponsePatternManager(this) }
        private val valueWriter by lazy { UssdNavigationValueWriter(this) }
        private val sendClick by lazy { UssdNavigationSendClick(this) }
        private val adaptiveSendMemory by lazy { UssdAdaptiveSendMemory(this) }
        internal var cachedInputFieldId: String? = null
        private var pendingAdaptiveSendMethod = ""
        private var pendingAdaptiveSendDurationUs = 0L
        private var pendingAdaptiveSendContext = ""
        internal var cachedSendButtonId: String? = null
        private var isProcessing       = false
    private var lastDialogText     = ""
    private var stepTimeoutJob: Job? = null
    private var processStepJob: Job? = null
    private var processStepVersion = 0
    private var lastFinalResponse  = ""
    internal var lastInputWriteValue = ""
    private var lastInputWriteElapsed = 0L
    private var lastInputWriteStepIndex = -1
    private var inputWriteAttemptCount = 0
    private var lastVerifiedInputValue = ""
    private var lastVerifiedInputElapsed = 0L
    private var pendingProcessToken = 0L
    private var lastWindowPkg = ""
    private var lastWindowId = -1
    // One-shot validation snapshot reused by the same accessibility callback.
    private var validatedRootSnapshot: UssdTreeSnapshot? = null
    private var validatedRootWindowId: Int = -1
    private var validatedRootPackage: String = ""
    // Safe reuse of validated window ID without retaining stale AccessibilityNodeInfo objects
    private var lastValidatedUssdWindowId: Int = -1
    private var lastValidatedUssdWindowPkg: String = ""
    private var lastValidatedUssdWindowElapsed: Long = 0L
    // If event.source repeatedly returns null after a costly Binder lookup for the same
    // telephony window, briefly bypass that lookup and use the active-window fallback.
    // This is deliberately a short, window-scoped cooldown; it never caches AccessibilityNodeInfo.
    private var slowNullEventSourceWindowId: Int = -1
    private var slowNullEventSourcePackage: String = ""
    private var slowNullEventSourceElapsed: Long = 0L

    private fun setValidatedUssdWindow(windowId: Int, pkg: String) {
        if (windowId > 0) {
            lastValidatedUssdWindowId = windowId
            lastValidatedUssdWindowPkg = pkg
            lastValidatedUssdWindowElapsed = SystemClock.elapsedRealtime()
        }
    }

    private fun clearValidatedUssdWindow() {
        lastValidatedUssdWindowId = -1
        lastValidatedUssdWindowPkg = ""
        lastValidatedUssdWindowElapsed = 0L
        invalidateActiveSnapshot()
    }

    // Local processing stage timer (measures local automation duration from dialog handling to send acceptance)
    private var stepProcessingStartedAtNanos: Long = 0L

    // Immutable snapshot cache for the currently active dialog popup
    internal var activePopupSnapshot: UssdTreeSnapshot? = null
    private var activeSnapshotWindowId: Int = -1
    private var activeSnapshotPackage: String = ""
    private var activeSnapshotStepIndex: Int = -1
    private var activeSnapshotCapturedElapsed: Long = 0L

    // Transient carrier popup state tracking to prevent repeated re-acquisitions
    private var lastTransientWindowId: Int = -1
    private var lastTransientPopupElapsed: Long = 0L
    private var lastTransientDialogText: String = ""

    private fun getOrCaptureActiveSnapshot(
        root: AccessibilityNodeInfo,
        windowId: Int = runCatching { root.windowId }.getOrDefault(-1),
        windowPkg: String = root.packageName?.toString().orEmpty()
    ): UssdTreeSnapshot {
        val rootWinId = runCatching { root.windowId }.getOrDefault(-1)
        val effectiveWinId = if (windowId > 0) windowId else rootWinId
        val now = SystemClock.elapsedRealtime()
        val cached = activePopupSnapshot
        if (cached != null &&
            effectiveWinId > 0 &&
            effectiveWinId == activeSnapshotWindowId &&
            (windowPkg.isBlank() || activeSnapshotPackage.isBlank() || windowPkg == activeSnapshotPackage) &&
            currentStep == activeSnapshotStepIndex &&
            now - activeSnapshotCapturedElapsed < 400L
        ) {
            return cached
        }
        val snapshotStartedAt = UssdPerformanceDiagnostics.mark()
        val snapshot = captureTreeSnapshot(root)
        activePopupSnapshot = snapshot
        activeSnapshotWindowId = effectiveWinId
        activeSnapshotPackage = windowPkg
        activeSnapshotStepIndex = currentStep
        activeSnapshotCapturedElapsed = now
        recentUssdSnapshot = snapshot
        UssdPerformanceDiagnostics.record(
            "popup.snapshot_capture",
            snapshotStartedAt,
            "found=${snapshot.dialogText.isNotBlank()} window=$effectiveWinId"
        )
        return snapshot
    }

    private fun invalidateActiveSnapshot() {
        activePopupSnapshot = null
        activeSnapshotWindowId = -1
        activeSnapshotPackage = ""
        activeSnapshotStepIndex = -1
        activeSnapshotCapturedElapsed = 0L
        recentUssdSnapshot = null
        validatedRootSnapshot = null
        validatedRootWindowId = -1
        validatedRootPackage = ""
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        lastTransientWindowId = -1
        lastTransientPopupElapsed = 0L
        lastTransientDialogText = ""
    }
    private var lastRelevantEventElapsed = 0L
    private var hasSeenAdvancedPopup = false
    private var hasSeenForegroundPopup = false
    private var lastMenuSignatureKey = ""
    private var lastMenuSignature: ParsedMenuSignature? = null
    private var loadedSignatureLookupSource: List<UssdSignatureStep> = emptyList()
    private var loadedSignatureLookup: Map<Int, LearnedSignatureContext> = emptyMap()
    private var lastScreenSignatureKey = ""
    private var pendingExpectedValue: String? = null
    private var pendingAdvanceDialogText: String = ""
    private var pendingAdvanceStabilizeUntil: Long = 0L
    private var pendingPhase: PendingPhase = PendingPhase.NONE
    private var pendingSinceElapsed: Long = 0L
    private var pendingAttempts: Int = 0
    private var lastStepActionKey: String = ""
    private var lastStepActionElapsed: Long = 0L
    private var lastStepActionIndex: Int = -1
    private var lastUiReturnElapsed: Long = 0L
    private var lastStepAdvanceElapsed: Long = 0L
    // Highest 1-based step for which a submit action was accepted by Accessibility.
    // Persists across transitions so recovery never redials a partly completed purchase.
    private var lastSubmittedStepNumber: Int = 0
    private var stepRetryCount: Int = 0
    private var pendingStepAdvanceFromKey: String = ""
    private var pendingStepAdvanceDialogKey: String = ""
    private var pendingStepAdvanceMenuKey: String = ""
    private var pendingStepAdvanceSinceElapsed: Long = 0L
    private var pendingStepAdvanceTimeoutJob: Job? = null
    private var pendingStepAdvanceKickJob: Job? = null
    private var pendingAdvanceKickJob: Job? = null
    private var pendingStepAdvanceWindowId: Int = -1
    private var uiKeepVisibleJob: Job? = null
    private var waitingForRootSinceElapsed: Long = 0L
    private var rootRecoveryTimeoutRunnable: Runnable? = null
    private var lastEventFingerprint = ""
    private var lastEventElapsed = 0L
    private var recentUssdRoot: AccessibilityNodeInfo? = null
    private var recentUssdSnapshot: UssdTreeSnapshot? = null
    private var recentUssdWindowId = -1
    private var recentUssdWindowPkg = ""
    private var recentUssdDialogText = ""
    private var recentUssdStrictDialog = false
    private var recentUssdCapturedElapsed = 0L
    private var windowManager: WindowManager? = null
    private var runningOverlayView: View? = null
    private var runningOverlayStatusText: TextView? = null
    private var runningOverlayDetailText: TextView? = null
    private var waitingForNextDialog = false
    private var lastSentStepIndex = -1  // Track which step was last successfully sent
    private var lastSentStepKey = ""    // Dialog signature when step was sent
    private var lastInputStepProcessed = -1
    private var lastVerifiedStepIndex = -1
    private var lastDialogInteractionTime = 0L
    private var accessibilityEventBusy = false
    private var eventRecheckPosted = false
    private var eventRecheckPending = false
    private var stepIdleAttempts = 0
    private var lastLearningEventText = ""  // Cached event text for fast learning capture
    private var lastResolvedStepSignature = ""  // Cache key for last resolved step input
    private var lastResolvedStepValue = ""     // Cached resolved step value
    private var lastResolvedStepIndex = -1     // Step index the cache applies to
    // Prepares deterministic next-step input while the current USSD screen is still active.
    // Menu/signature-dependent numeric choices are deliberately excluded until the real menu arrives.
    private data class PreparedStepInput(
        val stepIndex: Int,
        val rawStep: String,
        val phoneNumber: String,
        val value: String
    )
    private var preparedStepInput: PreparedStepInput? = null
    private val eventRecheckRunnable = Runnable { drainEventRecheck() }
    private var isUsdExecutionLocked = false
    private val useRelaxedAccessibilityTimings: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.P
    private val useAggressiveVerifiedPopupFastPath: Boolean
        get() = !useRelaxedAccessibilityTimings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private enum class PendingPhase { NONE, WAIT_VERIFY, WAIT_SEND }

    private data class RecentUssdContext(
        val root: AccessibilityNodeInfo,
        val snapshot: UssdTreeSnapshot?,
        val windowId: Int,
        val windowPkg: String,
        val dialogText: String,
        val strictDialog: Boolean
    )

    private fun timingForDevice(modernMs: Long, relaxedMs: Long): Long =
        if (useRelaxedAccessibilityTimings) minOf(modernMs + 4L, relaxedMs) else modernMs

    internal fun canPerformGestures(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    private val accessibilityNotificationTimeoutMs: Long
        get() = UssdNavigationConfig.ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS

    private val duplicateEventWindowMs: Long
        get() = timingForDevice(UssdNavigationConfig.DUPLICATE_EVENT_WINDOW_MS, 20L)

    private val eventHotPollMs: Long
        get() = timingForDevice(UssdNavigationConfig.EVENT_HOT_POLL_MS, 8L)

    private val fastVerifyPollMs: Long
        get() = timingForDevice(UssdNavigationConfig.FAST_VERIFY_POLL_MS, 12L)

    private val verifyPollMs: Long
        get() = timingForDevice(UssdNavigationConfig.VERIFY_POLL_MS, 16L)

    private val postWriteVerifyPollMs: Long
        get() = timingForDevice(UssdNavigationConfig.POST_WRITE_VERIFY_POLL_MS, 12L)

    private val hotSendRetryDelayMs: Long
        get() = timingForDevice(UssdNavigationConfig.HOT_SEND_RETRY_DELAY_MS, 16L)

    private val sendRetryDelayMs: Long
        get() = timingForDevice(UssdNavigationConfig.SEND_RETRY_DELAY_MS, 24L)

    private val postWriteSendRetryMs: Long
        get() = timingForDevice(UssdNavigationConfig.POST_WRITE_SEND_RETRY_MS, 12L)

    private val rapidPostPopupPollMs: Long
        get() = timingForDevice(UssdNavigationConfig.RAPID_POST_POPUP_POLL_MS, 12L)

    private val rapidPostPopupVerifyMs: Long
        get() = timingForDevice(UssdNavigationConfig.RAPID_POST_POPUP_VERIFY_MS, 12L)

    private val rapidPostPopupSendRetryMs: Long
        get() = timingForDevice(UssdNavigationConfig.RAPID_POST_POPUP_SEND_RETRY_MS, 16L)

    private val pendingAdvanceKickMs: Long
        get() = timingForDevice(UssdNavigationConfig.PENDING_ADVANCE_KICK_MS, 20L)

    private val rootReacquireRetryDelayMs: Long
        get() = timingForDevice(UssdNavigationConfig.ROOT_REACQUIRE_RETRY_DELAY_MS, 24L)

    private val sendRetryIncrementMs: Long
        get() = timingForDevice(4L, 6L)

    private val fastSendRetryIncrementMs: Long
        get() = timingForDevice(2L, 4L)

    private val maxSendRetryDelayMs: Long
        get() = timingForDevice(36L, 48L)

    private fun handleAdvancedSessionArmed() {
        if (!advancedActive || advancedSteps.isEmpty()) return
        if (retryWindowStartedAt <= 0L) retryWindowStartedAt = SystemClock.elapsedRealtime()
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        isProcessing = false
        lastDialogText = ""
        lastFinalResponse = ""
        lastWindowPkg = ""
        lastWindowId = -1
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        lastScreenSignatureKey = ""
        preparedStepInput = null
        prepareStepInput(currentStep)
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastInputStepProcessed = -1
        lastRelevantEventElapsed = SystemClock.elapsedRealtime()
        pendingProcessToken = lastRelevantEventElapsed
        lastUiReturnElapsed = 0L
        lastSubmittedStepNumber = 0
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        stopKeepingAppUiVisible()
        updateRunningOverlay()
        startStepTimeout()
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        bgThread = HandlerThread("UssdNavigationBg").apply { start() }
        bgHandler = Handler(bgThread.looper)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        startForegroundCompat(
            notificationId = UssdNavigationConfig.NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
        updateRunningOverlay()
        if (pendingAdvancedArm) {
            pendingAdvancedArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.DEFAULT
            // Fast coalescing window eliminates lag without dropping popups
            notificationTimeout = accessibilityNotificationTimeoutMs
        }
        if (pendingAdvancedArm) {
            pendingAdvancedArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onInterrupt() { cleanupAdvanced(); clearCallbacks() }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundCompat()
        runCatching { bgThread.quitSafely() }
        if (activeInstance === this) activeInstance = null
        cleanupAdvanced()
        clearCallbacks()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    private fun updateRunningOverlay() {
        if (!shouldShowRunningOverlay()) {
            hideRunningOverlay()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        val wm = windowManager ?: return
        val overlay = runningOverlayView ?: run {
            val view = buildRunningOverlayView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(18)
            }
            val added = runCatching {
                wm.addView(view, params)
                true
            }.getOrElse {
                Log.w(TAG, "Unable to show running overlay", it)
                false
            }
            if (!added) {
                clearRunningOverlayReferences()
                return
            }
            view
        }
        runningOverlayStatusText?.text = buildRunningOverlayStatusText()
        runningOverlayDetailText?.text = buildRunningOverlayDetailText()
        overlay.visibility = View.VISIBLE
    }

    private fun hideRunningOverlay() {
        val wm = windowManager ?: return
        val overlay = runningOverlayView ?: return
        runCatching { wm.removeView(overlay) }
            .onFailure { Log.w(TAG, "Unable to remove running overlay", it) }
        clearRunningOverlayReferences()
    }

    private fun clearRunningOverlayReferences() {
        runningOverlayView = null
        runningOverlayStatusText = null
        runningOverlayDetailText = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val eventStartedAt = UssdPerformanceDiagnostics.mark()
        val eventTypeForTrace = event.eventType
        val eventPackageForTrace = event.packageName?.toString().orEmpty()
        if (accessibilityEventBusy) {
            // Never discard an event outright: remember that the tree changed while we were busy so
            // the pending state machine is re-evaluated as soon as the current event finishes.
            eventRecheckPending = true
            scheduleEventRecheck()
            UssdPerformanceDiagnostics.record(
                "accessibility.event",
                eventStartedAt,
                "type=$eventTypeForTrace pkg=$eventPackageForTrace coalesced=true"
            )
            UssdPerformanceDiagnostics.record("accessibility.event_coalesced", eventStartedAt)
            return
        }
        accessibilityEventBusy = true

        try {
            if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode && !isForegroundUiActive()) return
            if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) return
            val pkg = event.packageName?.toString() ?: ""
            if (advancedActive &&
                pkg in LAUNCHER_PACKAGES &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            ) {
                suppressUiReturn()
                stopKeepingAppUiVisible()
                updateRunningOverlay()
                return
            }
            if (foregroundUiActive &&
                !advancedActive &&
                pkg in LAUNCHER_PACKAGES &&
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            ) {
                disarmForegroundUi()
                stopKeepingAppUiVisible()
                hasSeenForegroundPopup = false
                updateRunningOverlay()
                return
            }
            val allowUnknownPkg = pkg.isBlank() || pkg == "android"
            val otherAppInFront = (advancedActive || isForegroundUiActive()) &&
                !allowUnknownPkg &&
                pkg != "com.bingwa.mobile" &&
                pkg != "com.android.systemui" &&
                !isPotentialUssdPackage(pkg)
            if (otherAppInFront) {
                if (advancedActive) suppressUiReturn()
                if (isForegroundUiActive() && !advancedActive) {
                    disarmForegroundUi()
                    hasSeenForegroundPopup = false
                }
                stopKeepingAppUiVisible()
                updateRunningOverlay()
                return
            }
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                return
            }
            val allowSystemUi = pkg == "com.android.systemui" &&
                    (advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode || isForegroundUiActive())
            if (pkg in BLOCKED_PACKAGES && !allowSystemUi) return

            // Reject unrelated packages before extracting or normalizing event text.
            val eventDialogText = extractDialogTextFromEvent(event)

            // Fast-path filter for SystemUI: SystemUI continuously dispatches clock, battery,
            // notification shade, heads-up, and status bar updates. Reject them immediately.
            if (pkg == "com.android.systemui") {
                val eventClass = event.className?.toString().orEmpty()
                if (UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_CLASS_HINTS.any { eventClass.contains(it, ignoreCase = true) }) {
                    return
                }
                val lowerEvent = eventDialogText.lowercase()
                val hasUssdContent = USSD_HINTS.any { lowerEvent.contains(it) } ||
                    errorKeywords.any { lowerEvent.contains(it) } ||
                    MENU_LIKE_REGEX.containsMatchIn(lowerEvent)
                if (!hasUssdContent) return
            }

            // Fast-path filter for blank package events: surface/window animations dispatch content changes
            // with empty package and no text. Drop them before expensive root acquisition.
            if (pkg.isBlank() && eventDialogText.isBlank() && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return
            }

            // Fast-path: Suppress redundant tree acquisitions when a transient carrier popup is actively waiting
            val now = SystemClock.elapsedRealtime()
            if (lastTransientWindowId > 0 &&
                now - lastTransientPopupElapsed < 1200L &&
                (event.windowId <= 0 || event.windowId == lastTransientWindowId) &&
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                (eventDialogText.isBlank() || isTransientResponseText(eventDialogText.lowercase()))
            ) {
                lastTransientPopupElapsed = now
                return
            }

            if (shouldSkipDuplicateEvent(event, eventDialogText)) {
                return
            }

            val isSameWindowState = activePopupSnapshot != null &&
                activeSnapshotWindowId > 0 &&
                (event.windowId <= 0 || event.windowId == activeSnapshotWindowId) &&
                currentStep == activeSnapshotStepIndex &&
                now - activeSnapshotCapturedElapsed < 400L &&
                (eventDialogText.isBlank() || activePopupSnapshot?.dialogText?.contains(eventDialogText) == true)

            if (!isSameWindowState && (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || (event.windowId > 0 && event.windowId != activeSnapshotWindowId))) {
                activePopupSnapshot = null
                activeSnapshotWindowId = -1
                activeSnapshotPackage = ""
                activeSnapshotStepIndex = -1
                activeSnapshotCapturedElapsed = 0L
                validatedRootSnapshot = null
                validatedRootWindowId = -1
                validatedRootPackage = ""
            }

            if (advancedActive && event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                recordInputMethodWindowState()
            }
            val windowId = event.windowId
            val windowChanged = windowId != lastWindowId
            lastWindowId = windowId

            // Do not discard cached IDs on windowChanged: USSD dialogs on Infinix/Transsion and AOSP
            // reuse identical view IDs (e.g. android:id/input, button1) across menu steps.
            // Fast viewId lookup validates candidate presence before reuse.

            // Separate Android's event-source/root lookup from popup parsing.
            val root = acquireUssdRootFast(event)
            root ?: return
            try {
                val windowPkg = root.packageName?.toString() ?: ""
                // Both root providers (event-source and active-window fallback) already
                // validate package eligibility before returning a node. Re-running the
                // SystemUI dialog validator here used to walk the same accessibility
                // subtree a second time on the callback's critical path.
                if (windowPkg in BLOCKED_PACKAGES && windowPkg != "com.android.systemui") return
                lastWindowPkg = windowPkg

                val requireStrictPopupScope = shouldRequireStrictPopupScope()
                val popupCaptureStartedAt = UssdPerformanceDiagnostics.mark()
                val snapshot = if (
                    eventDialogText.isBlank() ||
                    advancedActive ||
                    isForegroundUiActive() ||
                    balanceCallback != null ||
                    tokenPurchaseCallback != null
                ) {
                    takeValidatedRootSnapshot(root, windowId, windowPkg)
                        ?: capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
                } else {
                    null
                }
                if (requireStrictPopupScope && snapshot == null && eventDialogText.isBlank()) return
                val dialogText = snapshot?.dialogText
                    ?: normalizeCollapsedText(eventDialogText)
                UssdPerformanceDiagnostics.record(
                    "popup.capture",
                    popupCaptureStartedAt,
                    "chars=${dialogText.length} snapshot=${snapshot != null}"
                )
                if (dialogText.isBlank()) return
                val lower = dialogText.lowercase()
                val isTransient = isTransientResponseText(lower) || isUssdLoadingScreen(dialogText)
                // Attribute the delay between an accepted Send action and the next
                // popup separately from local accessibility processing. Loading screens
                // do not end the wait; only a usable response does.
                UssdPerformanceDiagnostics.markPopupObserved(
                    isTransient = isTransient,
                    characterCount = dialogText.length
                )
                if (isTransient) {
                    lastTransientWindowId = root.windowId
                    lastTransientPopupElapsed = SystemClock.elapsedRealtime()
                    lastTransientDialogText = dialogText
                    return
                }
                lastTransientWindowId = -1
                lastTransientPopupElapsed = 0L
                lastTransientDialogText = ""
                if (handleSimSelectionDialog(root, lower)) return
                if (!isPotentialUssdPackage(windowPkg) && NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return
                val looksLikeDialog = when {
                    snapshot != null -> looksLikeUssdDialog(
                        root = root,
                        snapshot = snapshot,
                        allTextLower = lower,
                        windowPackageName = windowPkg
                    )
                    else -> looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = windowPkg)
                }
                if (!looksLikeDialog) return
                lastRelevantEventElapsed = SystemClock.elapsedRealtime()
                rememberRecentUssdContext(
                    root = root,
                    snapshot = snapshot,
                    windowId = windowId,
                    windowPkg = windowPkg,
                    dialogText = dialogText,
                    strictDialog = requireStrictPopupScope
                )

                if (advancedActive && advancedSteps.isNotEmpty()) {
                    if (!hasSeenAdvancedPopup) {
                        hasSeenAdvancedPopup = true
                        updateRunningOverlay()
                        stopKeepingAppUiVisible()
                    } else if (windowChanged) {
                        stopKeepingAppUiVisible()
                    }

                    // Re-capture only when the popup's visible text changes. This avoids
                    // repeating dialog-subtree discovery for duplicate accessibility events.
                    if (signatureLearningMode) {
                        val learningEventKey = normalizeMenuText(dialogText)
                        if (learningEventKey != lastLearningEventText) {
                            lastLearningEventText = learningEventKey
                            captureLearningDialogIfNeeded(null, root, windowPkg, dialogText)
                        }
                    }

                    if (errorKeywords.any { lower.contains(it) }) {
                        if (signatureLearningMode && currentStep >= advancedSteps.size) {
                            finishAdvancedDispatch(dialogText)
                        } else {
                            dismissErrorAndRestart()
                        }
                        return
                    }

                    if (isTransientResponseText(lower) || isUssdLoadingScreen(dialogText)) {
                        return
                    }

                    if (pendingStepAdvanceFromKey.isNotBlank()) {
                        // A send already happened for the previous step: let the popup change that
                        // this event reports decide whether we move on, instead of waiting for a poll.
                        val handled = handlePendingStepAdvance(
                            windowId = windowId,
                            windowPkg = windowPkg,
                            root = root,
                            snapshot = snapshot,
                            dialogText = dialogText,
                            windowChanged = windowChanged
                        )
                        if (handled) return
                    }

                    if (pendingPhase != PendingPhase.NONE && !isProcessing) {
                        attemptPendingAdvance(root)
                        return
                    }

                    if (lastInputStepProcessed >= currentStep) return

                    if (!isProcessing && currentStep in advancedSteps.indices) {
                        val dialogLower = dialogText.lowercase()
                        if (looksLikeUssdDialogFast(allTextLower = dialogLower, windowPackageName = lastWindowPkg)) {
                            cancelStepTimeout()
                            processStepJob?.cancel()
                            pendingProcessToken = SystemClock.elapsedRealtime()
                            scheduleProcessStep(dialogChanged = true)
                            return
                        }
                    }
                }

                if (isForegroundUiActive()) {
                    refreshForegroundUi()
                    if (!hasSeenForegroundPopup) {
                        hasSeenForegroundPopup = true
                        updateRunningOverlay()
                        requestAppUiBehindPopup(force = true)
                        startKeepingAppUiVisible()
                    } else if (windowChanged) {
                        requestAppUiBehindPopup()
                        startKeepingAppUiVisible()
                    }
                    return
                }

                handleCallbackDialogs(lower, dialogText)
            } finally {
                root.recycle()
            }
        } finally {
            UssdPerformanceDiagnostics.record(
                "accessibility.event",
                eventStartedAt,
                "type=$eventTypeForTrace pkg=$eventPackageForTrace"
            )
            accessibilityEventBusy = false
            drainEventRecheck()
        }
    }

    private fun recordInputMethodWindowState() {
        // Querying service `windows` across Binder IPC on Android 12 blocks the main thread
        // for up to 500ms during keyboard animations. Record event timestamp without IPC.
        UssdPerformanceDiagnostics.record(
            "keyboard.window_changed",
            detail = "type=TYPE_WINDOWS_CHANGED"
        )
    }

    private fun scheduleEventRecheck() {
        if (eventRecheckPosted) return
        eventRecheckPosted = true
        handler.post(eventRecheckRunnable)
    }

    private fun drainEventRecheck() {
        eventRecheckPosted = false
        if (!eventRecheckPending) return
        eventRecheckPending = false
        if (!advancedActive &&
            balanceCallback == null &&
            tokenPurchaseCallback == null &&
            !isForegroundUiActive()
        ) {
            return
        }
        handler.post { continuePendingStateMachine() }
    }

    /**
     * Re-drives the pending state machine (post-send verification / post-send step advance) after a
     * burst of accessibility events was collapsed while the service was busy.
     */
    private fun continuePendingStateMachine() {
        if (!advancedActive) return
        if (pendingStepAdvanceFromKey.isBlank() && pendingPhase == PendingPhase.NONE) return
        val root = getUssdRoot() ?: return
        try {
            if (pendingStepAdvanceFromKey.isNotBlank()) {
                attemptPendingStepAdvanceWithRoot(root)
            } else {
                attemptPendingAdvance(root)
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun extractDialogTextFromEvent(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        runCatching {
            event.text?.forEach { cs ->
                val s = cs?.toString()?.trim().orEmpty()
                if (s.isNotBlank()) parts += s
            }
        }
        return normalizeCollapsedText(parts.distinct().joinToString(" "))
    }

    private fun shouldSkipDuplicateEvent(
        event: AccessibilityEvent,
        eventDialogText: String
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val isStructural = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (isStructural) {
            lastEventElapsed = now
            return false
        }
        val fingerprint = buildEventFingerprint(event, eventDialogText)
        val isDuplicate = fingerprint.isNotBlank() &&
            fingerprint == lastEventFingerprint &&
            now - lastEventElapsed <= duplicateEventWindowMs
        lastEventFingerprint = fingerprint
        lastEventElapsed = now
        return isDuplicate
    }

    private fun buildEventFingerprint(
        event: AccessibilityEvent,
        eventDialogText: String
    ): String {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val contentType = runCatching { event.contentChangeTypes }.getOrDefault(0)
        return "${event.eventType}|${event.windowId}|$contentType|$pkg|$cls|$eventDialogText"
    }

    internal fun looksLikeUssdDialogFast(allTextLower: String, windowPackageName: String): Boolean {
        // Fast rejection: A window with over 500 characters without USSD keywords is a background dialer/call log
        if (allTextLower.length > 500 && !USSD_HINTS.any { allTextLower.contains(it) }) return false

        val hasUssdLanguage = USSD_HINTS.any { allTextLower.contains(it) } ||
            errorKeywords.any { allTextLower.contains(it) }
        val menuLike = MENU_LIKE_REGEX.containsMatchIn(allTextLower)
        if (windowPackageName == "android" || windowPackageName.isBlank()) {
            if (!advancedActive && !isForegroundUiActive()) return false
            return hasUssdLanguage || menuLike
        }
        val likelyUssdPackage = isPotentialUssdPackage(windowPackageName)
        if (!likelyUssdPackage && !advancedActive && !isForegroundUiActive()) return false
        if (advancedActive || isForegroundUiActive()) {
            if (hasSeenAdvancedPopup || hasSeenForegroundPopup) {
                if (allTextLower.length > 450 && !hasUssdLanguage && !menuLike) return false
                return true
            }
            return hasUssdLanguage || menuLike
        }
        return likelyUssdPackage && hasUssdLanguage
    }

    private fun handleSimSelectionDialog(root: AccessibilityNodeInfo, dialogLower: String): Boolean {
        if (!advancedActive && !isForegroundUiActive() && balanceCallback == null && tokenPurchaseCallback == null) return false
        val isSimPrompt = (dialogLower.contains("sim") || dialogLower.contains("slot") || dialogLower.contains("laini")) && (
            dialogLower.contains("choose") || dialogLower.contains("select") || dialogLower.contains("default") ||
            dialogLower.contains("chagua") || dialogLower.contains("send with") || dialogLower.contains("call with") ||
            dialogLower.contains("dial with") || dialogLower.contains("sim 1") || dialogLower.contains("sim 2") ||
            dialogLower.contains("slot 1") || dialogLower.contains("slot 2") || dialogLower.contains("laini 1") ||
            dialogLower.contains("laini 2")
        )
        if (!isSimPrompt) return false
        val preferred = currentUssdSimSelection(this)
        val targetSlot = when {
            advancedSimSlot >= 0 -> advancedSimSlot + 1
            preferred == USSD_SIM_SELECTION_SLOT_2 -> 2
            else -> 1
        }
        val sims = getAvailableSims(this)
        val targetSim = when (targetSlot) {
            2 -> sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)
            else -> sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
        }
        val carrierName = targetSim?.displayName?.toString()?.lowercase()?.trim().orEmpty()
        val otherSim = when (targetSlot) {
            2 -> sims.firstOrNull { it.simSlotIndex == 0 } ?: sims.getOrNull(0)
            else -> sims.firstOrNull { it.simSlotIndex == 1 } ?: sims.getOrNull(1)
        }
        val otherCarrier = otherSim?.displayName?.toString()?.lowercase()?.trim().orEmpty()

        val targetSlotLabels = mutableListOf(
            "sim $targetSlot", "sim$targetSlot", "slot $targetSlot", "slot$targetSlot",
            "sim-$targetSlot", "slot-$targetSlot", "sub $targetSlot", "sub$targetSlot",
            "laini $targetSlot", "laini$targetSlot", "laini-$targetSlot"
        )
        if (targetSlot == 1) targetSlotLabels.add("sim 1") else targetSlotLabels.add("sim 2")
        if (carrierName.isNotBlank() && carrierName.length >= 3 && carrierName != otherCarrier) {
            targetSlotLabels.add(carrierName)
        }

        val targetViewIdHints = listOf(
            "sim$targetSlot", "sim_$targetSlot", "slot$targetSlot", "slot_$targetSlot",
            "btn_sim$targetSlot", "sim_select_$targetSlot", "sim_card_$targetSlot",
            "sub$targetSlot", "sub_$targetSlot"
        )

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        try {
            for (node in candidates) {
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
                val matchesLabel = targetSlotLabels.any { label.contains(it) || desc.contains(it) }
                val matchesId = targetViewIdHints.any { viewId.contains(it) }
                if (matchesLabel || matchesId) {
                    Log.d(TAG, "handleSimSelectionDialog: auto-selected SIM slot $targetSlot on '${label.ifBlank { desc }}' / '$viewId'")
                    return performClick(node)
                }
            }
            if (candidates.size == 2) {
                val targetIndex = (targetSlot - 1).coerceIn(0, 1)
                val node = candidates[targetIndex]
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                Log.d(TAG, "handleSimSelectionDialog: fallback indexed selection SIM slot $targetSlot on '${label.ifBlank { desc }}'")
                return performClick(node)
            }
        } finally {
            candidates.forEach { it.recycle() }
        }
        return false
    }

    private fun looksLikeUssdDialog(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot,
        allTextLower: String,
        windowPackageName: String
    ): Boolean {
        val hasUssdLanguage = USSD_HINTS.any { allTextLower.contains(it) }
        val hasEditField = snapshot.hasEditableField
        val hasSendButton = snapshot.hasSendButton
        val hasDismissButton = snapshot.hasDismissButton
        val hasMenuOptions = parseMenuSignature(snapshot) != null

        // Full-screen dialer screens and call log lists have large text/token counts without USSD controls
        if (allTextLower.length > 500 && !hasUssdLanguage) return false
        if (snapshot.textTokens.size > 25 && !hasEditField && !hasSendButton) return false

        val likelyUssdPackage = isPotentialUssdPackage(windowPackageName) ||
            ((windowPackageName == "android" || windowPackageName.isBlank()) && (hasUssdLanguage || hasMenuOptions))
        val hasDialogLayout = hasDialogLayout(root, snapshot)
        return (hasEditField && (hasSendButton || hasUssdLanguage || hasMenuOptions))
                || ((hasSendButton || hasDismissButton) && (hasUssdLanguage || hasMenuOptions))
                || (hasMenuOptions && hasUssdLanguage)
                || (hasDialogLayout && likelyUssdPackage && (hasEditField || hasSendButton || hasDismissButton))
                || ((advancedActive || isForegroundUiActive()) && likelyUssdPackage && (hasEditField || hasSendButton || hasDismissButton || hasMenuOptions))
    }

    private fun shouldRequireStrictPopupScope(): Boolean {
        if (signatureLearningMode) return true
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || !pendingExpectedValue.isNullOrBlank()) return true
        val step = advancedSteps.getOrNull(currentStep).orEmpty()
        return isPhoneOrStringStep(step)
    }

    internal fun isPhoneOrStringStep(step: String): Boolean = Companion.isPhoneOrStringStep(step)

    private fun shouldUseEventTextFallback(): Boolean =
        !advancedActive &&
            !signatureLearningMode &&
            !isForegroundUiActive() &&
            balanceCallback == null &&
            tokenPurchaseCallback == null

    private fun capturePreferredPopupSnapshot(
        root: AccessibilityNodeInfo,
        requireStrictDialog: Boolean
    ): UssdTreeSnapshot? {
        val rootRetrieveStart = UssdPerformanceDiagnostics.mark()
        val winId = runCatching { root.windowId }.getOrDefault(-1)
        UssdPerformanceDiagnostics.record(
            "snapshot.root_retrieval",
            rootRetrieveStart,
            "windowId=$winId"
        )
        val windowPkg = root.packageName?.toString().orEmpty()
        val snapshot = takeValidatedRootSnapshot(root, winId, windowPkg)
            ?: getOrCaptureActiveSnapshot(root, winId, windowPkg)
        val hasText = snapshot.dialogText.isNotBlank()
        val allowed = hasText && (!requireStrictDialog || shouldAllowRelaxedDialogFallback(root, snapshot))
        if (!allowed) return null
        return snapshot
    }

    private fun obtainInteractionRoot(
        root: AccessibilityNodeInfo,
        requireStrictDialog: Boolean
    ): AccessibilityNodeInfo? {
        findDialogCaptureRoot(root)?.let { return it }
        if (requireStrictDialog && !shouldAllowRelaxedDialogFallback(root)) return null
        return AccessibilityNodeInfo.obtain(root)
    }

    private fun shouldAllowRelaxedDialogFallback(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot? = null
    ): Boolean {
        if (!advancedActive &&
            balanceCallback == null &&
            tokenPurchaseCallback == null &&
            !signatureLearningMode &&
            !isForegroundUiActive()
        ) {
            return false
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == "com.android.systemui") {
            return shouldAllowSystemUiDialogRoot(root, pkg)
        }
        val allowedPkg = pkg.isBlank() ||
            pkg == "android" ||
            isPotentialUssdPackage(pkg)
        if (!allowedPkg) return false

        val effectiveSnapshot = snapshot ?: getOrCaptureActiveSnapshot(root)
        val dialogText = effectiveSnapshot.dialogText
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (!isPotentialUssdPackage(pkg) && NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false

        return looksLikeUssdDialog(root, effectiveSnapshot, lower, pkg) ||
            looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = pkg) ||
            hasDialogLayout(root, effectiveSnapshot) ||
            effectiveSnapshot.hasEditableField ||
            effectiveSnapshot.hasSendButton ||
            effectiveSnapshot.hasDismissButton
    }

    private fun buildScreenSignatureKey(
        stepIndex: Int,
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String
    ): String {
        val cls = root.className?.toString().orEmpty()
        val normalized = normalizeCollapsedText(dialogText)
        val flags = if (snapshot != null) {
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}"
        } else {
            ""
        }
        return "$stepIndex|$windowId|$windowPkg|$cls|$flags|$normalized"
    }

    private fun buildTransitionSignatureKey(
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String
    ): String {
        val cls = root.className?.toString().orEmpty()
        val normalized = normalizeCollapsedText(dialogText)
        val flags = if (snapshot != null) {
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}"
        } else {
            ""
        }
        return "$windowId|$windowPkg|$cls|$flags|$normalized"
    }

    private fun buildMenuTransitionKey(snapshot: UssdTreeSnapshot?): String {
        val menu = snapshot?.let(::parseMenuSignature) ?: return ""
        if (menu.options.isEmpty()) return ""
        val optionsKey = menu.options.entries.joinToString("|") { (key, label) ->
            "$key=${normalizeMenuText(label)}"
        }
        return "${menu.normalizedTitle}|$optionsKey"
    }

    private fun hasMeaningfulPendingStepTransition(
        dialogText: String,
        snapshot: UssdTreeSnapshot?
    ): Boolean {
        val normalizedDialog = normalizeMenuText(dialogText)
        if (normalizedDialog.isBlank()) return false
        if (isTransientResponseText(normalizedDialog)) return false
        return true
    }

    private fun isFinalSignatureLearningStep(stepIndex: Int): Boolean =
        signatureLearningMode && stepIndex == advancedSteps.lastIndex

    private fun processStep() {
        val processStartedAt = UssdPerformanceDiagnostics.mark()
        stepProcessingStartedAtNanos = processStartedAt
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        // Never start another step while the current reply is still being verified or sent.
        if (pendingPhase != PendingPhase.NONE) { isProcessing = false; return }
        if (currentStep !in advancedSteps.indices) {
            isProcessing = false
            return
        }
        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val rootAcquireStartedAt = UssdPerformanceDiagnostics.mark()
        val recentContext = obtainRecentUssdContext(requireStrictDialog = requireStrictPopupScope)
        val usedRecentContext = recentContext?.root != null
        val root = recentContext?.root ?: getUssdRoot() ?: run {
            UssdPerformanceDiagnostics.record(
                "process.root_acquire",
                rootAcquireStartedAt,
                "found=false cached=$usedRecentContext"
            )
            if (shouldWaitForRootRecovery()) {
                isProcessing = false
                waitForRootRecovery()
            } else {
                clearRootRecoveryState()
                isProcessing = false
                handler.postDelayed({ restartFromBeginning() }, 700)
            }
            return
        }
        UssdPerformanceDiagnostics.record(
            "process.root_acquire",
            rootAcquireStartedAt,
            "found=true cached=$usedRecentContext window=${root.windowId}"
        )
try {
                clearRootRecoveryState()
                val windowPkg = recentContext?.windowPkg ?: (root.packageName?.toString() ?: "")
                val snapshotStartedAt = UssdPerformanceDiagnostics.mark()
                val effectiveSnapshot = recentContext?.snapshot ?: capturePreferredPopupSnapshot(
                    root = root,
                    requireStrictDialog = requireStrictPopupScope
                )
                UssdPerformanceDiagnostics.record(
                    "process.snapshot",
                    snapshotStartedAt,
                    "cached=${recentContext?.snapshot != null} available=${effectiveSnapshot != null}"
                )
                if (effectiveSnapshot == null) {
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }
                
                // STRICT POPUP GATING: Validate this is actually a USSD dialog before proceeding
                // This prevents value entry on non-USSD windows (launcher, other apps, etc.)
                val freshDialogText = effectiveSnapshot.dialogText
                val dialogTextForValidation = freshDialogText.ifBlank { lastFinalResponse }
                val lowerForValidation = dialogTextForValidation.lowercase()
                if (!looksLikeUssdDialogFast(allTextLower = lowerForValidation, windowPackageName = windowPkg)) {
                    Log.w(TAG, "processStep: root failed USSD dialog validation, aborting value entry")
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }
                if (!isPotentialUssdPackage(windowPkg) && NON_USSD_DIALOG_HINTS.any { lowerForValidation.contains(it) }) {
                    isProcessing = false
                    return
                }

                val interactionRoot = obtainInteractionRoot(
                    root = root,
                    requireStrictDialog = requireStrictPopupScope
                ) ?: run {
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }
                try {
                    val dialogText = freshDialogText.ifBlank { lastFinalResponse }
                val lower = dialogText.lowercase()
                if (shouldWaitForStepTransition(dialogText, currentStepIndex = currentStep)) {
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }
                if (dialogText.isNotBlank()) {
                    lastFinalResponse = dialogText
                }
                if (!isPotentialUssdPackage(windowPkg) && NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) {
                    isProcessing = false
                    return
                }
                if (!looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = windowPkg)) {
                    isProcessing = false
                    return
                }
                if (currentStep >= advancedSteps.size) {
                    if (shouldWaitForFinalResponse(effectiveSnapshot, dialogText)) {
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false, overrideDelayMs = rapidPostPopupPollMs)
                        return
                    }
                    val finalText = lastFinalResponse
                    Log.d(TAG, "All steps complete, finalText='$finalText'")
                    finishAdvancedDispatch(finalText)
                    return
                }

                val step = advancedSteps[currentStep]
                val menuSignature = parseMenuSignature(effectiveSnapshot)
                // Warm the next deterministic value before this screen is submitted.
                prepareStepInput(currentStep + 1)
                val isStringStep = isPhoneOrStringStep(step)
                if (signatureLearningMode) {
                    captureSignatureStepIfNeeded(
                        currentStep, step, menuSignature, effectiveSnapshot, dialogText,
                        interactionRoot, windowPkg
                    )
                    // Learning is observation-only. Stop on the final prompt before entering
                    // its choice or invoking any Send/menu action (for example the final *1#).
                    if (isFinalSignatureLearningStep(currentStep)) {
                        Log.i(TAG, "Signature learning reached final step; leaving final choice unsubmitted")
                        finishAdvancedDispatch(dialogText)
                        return
                    }
                }
                val resolveStartedAt = UssdPerformanceDiagnostics.mark()
                val resolved = resolveStepInput(currentStep, step, menuSignature)
                UssdPerformanceDiagnostics.record("step.resolve", resolveStartedAt, "step=$currentStep")
                if (!advancedActive) {
                    isProcessing = false
                    return
                }
                val valueToEnter = resolved.first
                val selectedMenuLabel = resolved.second

                // A phone/string step must wait for its actual editable prompt. If the
                // captured popup tree has no EditText and its text does not identify a
                // phone/free-text prompt, a full recursive field search cannot succeed:
                // defer it until Android publishes the next popup tree instead.
                val dialogAllowsPhoneInput = isStringStep &&
                    (dialogSuggestsPhoneInput(lower) || dialogSuggestsTextInput(lower))
                val fieldSearchStartedAt = UssdPerformanceDiagnostics.mark()
                if (isStringStep &&
                    !effectiveSnapshot.hasEditableField &&
                    !dialogAllowsPhoneInput &&
                    !dialogSuggestsTextInput(lower)
                ) {
                    UssdPerformanceDiagnostics.record(
                        "field.discover",
                        fieldSearchStartedAt,
                        "found=false step=$currentStep searched=false deferred=true"
                    )
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }

                // Numeric menu choices do not need an editable-field tree walk. The
                // popup snapshot already tells us whether an EditText exists; only search
                // when the tree reports one or the prompt explicitly expects free text.
                val shouldSearchInputField = effectiveSnapshot.hasEditableField ||
                    isStringStep ||
                    dialogSuggestsTextInput(lower) ||
                    dialogSuggestsPhoneInput(lower)
                var inputField = if (shouldSearchInputField) {
                    // The popup snapshot is already a bounded tree inspection. If it did not
                    // expose an editable node, allow only cheap focused/cached lookups here;
                    // a recursive walk at this point repeatedly scans a prompt before Android
                    // has attached its EditText.
                    findEditableFieldForStep(
                        interactionRoot,
                        step,
                        dialogText,
                        allowTreeScan = effectiveSnapshot.hasEditableField
                    )
                } else null
                // findEditableFieldForStep already performs the ranked tree fallback.
                // Avoid immediately repeating the same full-tree search for string steps.
                UssdPerformanceDiagnostics.record(
                    "field.discover",
                    fieldSearchStartedAt,
                    "found=${inputField != null} step=$currentStep searched=$shouldSearchInputField"
                )
                try {
                    if (isStringStep && inputField == null && !dialogAllowsPhoneInput && !dialogSuggestsTextInput(lower)) {
                        // Wait for the correct prompt/dialog instead of blindly injecting the phone number.
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false)
                        return
                    }
                    val shouldPreferTextInput = inputField != null ||
                        isStringStep ||
                        shouldTreatStepAsTextInput(step, valueToEnter, selectedMenuLabel) ||
                        dialogSuggestsTextInput(lower) ||
                        dialogAllowsPhoneInput
                    if (!shouldPreferTextInput &&
                        !isStringStep &&
                        step.all(Char::isDigit) &&
                        menuSignature != null &&
                        menuSignature.options.isNotEmpty()
                    ) {
                        // Only enforce menu-option matching when this popup behaves like a menu.
                        // Typed reply prompts can still contain numbered text from the previous step.
                        if (!menuSignature.options.containsKey(valueToEnter)) {
                            isProcessing = false
                            pendingProcessToken = SystemClock.elapsedRealtime()
                            scheduleProcessStep(dialogChanged = false)
                            return
                        }
                    }
                    if (inputField == null && shouldPreferTextInput) {
                        if (!effectiveSnapshot.hasEditableField) {
                            // Prompt text can arrive before the carrier's EditText node. Do not
                            // compensate by recursively scanning the same stale tree again (or
                            // scanning the whole phone window); wait for the next accessibility
                            // tree update and retry the focused/cached fast paths.
                            isProcessing = false
                            pendingProcessToken = SystemClock.elapsedRealtime()
                            scheduleProcessStep(dialogChanged = false)
                            return
                        }
                        // The step-aware ranked search above already walked this validated
                        // popup. Keep only the legacy keyword fallback, not another ranked walk
                        // and never broaden the search to the full phone window.
                        inputField = findInputField(interactionRoot, allowRankedSearch = false)
                    }
                    if (inputField != null || shouldPreferTextInput) {
                        // The writer performs the exact current-value check itself.
                        // Avoid reading the same AccessibilityNodeInfo.text here and
                        // then immediately reading it again inside writeValueResult().
                        val writeResult = if (inputField != null) {
                            val writeStartedAt = UssdPerformanceDiagnostics.mark()
                            val result = valueWriter.writeValueResult(inputField, valueToEnter)
                            UssdPerformanceDiagnostics.record(
                                "input.set_text",
                                writeStartedAt,
                                "result=$result"
                            )
                            result
                        } else {
                            UssdNavigationValueWriter.WriteResult.REJECTED
                        }

                        when (writeResult) {
                            UssdNavigationValueWriter.WriteResult.VERIFIED -> {
                                val sendStartedAt = UssdPerformanceDiagnostics.mark()
                                val sentImmediately = tryImmediateVerifiedSend(interactionRoot, inputField, valueToEnter)
                                UssdPerformanceDiagnostics.record(
                                    "send.immediate",
                                    sendStartedAt,
                                    "step=$currentStep outcome=${if (sentImmediately) "accepted" else "deferred_pending"}"
                                )
                                if (sentImmediately) {
                                    markStepAction(dialogText, stepIndex = currentStep)
                                    startPendingStepAdvance(interactionRoot, dialogText)
                                    return
                                }
                                startPendingAdvance(valueToEnter, dialogText)
                                return
                            }
                            UssdNavigationValueWriter.WriteResult.ACCEPTED_AWAITING_VERIFY -> {
                                Log.d(TAG, "processStep: ACTION_SET_TEXT accepted, awaiting event verification")
                                startPendingAdvance(valueToEnter, dialogText)
                                return
                            }
                            UssdNavigationValueWriter.WriteResult.REJECTED -> {
                                Log.w(TAG, "processStep: ACTION_SET_TEXT failed or input field is unavailable; waiting for a fresh popup tree")
                                isProcessing = false
                                pendingProcessToken = SystemClock.elapsedRealtime()
                                scheduleProcessStep(
                                    dialogChanged = false,
                                    overrideDelayMs = maxOf(
                                        verificationPollDelay(valueToEnter),
                                        UssdNavigationConfig.SET_TEXT_RETRY_DELAY_MS
                                    )
                                )
                                return
                            }
                        }
                    }
                } finally {
                    inputField?.recycle()
                }

                val menuBtn = locateMenuButton(interactionRoot, valueToEnter, selectedMenuLabel)
                // REMOVED: fallback to root - only click buttons inside validated dialog

                if (menuBtn != null) {
                    val clicked = try { performClick(menuBtn) } finally { menuBtn.recycle() }
                    if (clicked) {
                        markStepAction(dialogText, stepIndex = currentStep)
                        startPendingStepAdvance(interactionRoot, dialogText)
                        return
                    }
                    isProcessing = false
                    dismissErrorAndRestart()
                    return
                }

                if (menuSignature != null || hasSeenAdvancedPopup) {
                    // Most dialers render USSD menus as plain text instead of clickable rows, so once
                    // the obvious entry points are exhausted keep pushing the option number into the
                    // reply field rather than spinning until the session times out.
                    stepIdleAttempts++
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }

                isProcessing = false
                dismissErrorAndRestart()
            } finally {
                interactionRoot.recycle()
            }
        } finally {
            UssdPerformanceDiagnostics.record(
                "process.total",
                processStartedAt,
                "step=$currentStep"
            )
            root.recycle()
        }
    }

    private fun isSystemUiNotificationOrOverlay(node: AccessibilityNodeInfo): Boolean {
        val viewId = runCatching { node.viewIdResourceName }.getOrNull()?.lowercase().orEmpty()
        if (UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_VIEW_ID_HINTS.any { viewId.contains(it) }) return true

        val className = node.className?.toString().orEmpty()
        if (UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) return true

        // Shallow inspect child view IDs for notification containers (depth 1)
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until minOf(childCount, 8)) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: break
            try {
                val childId = runCatching { child.viewIdResourceName }.getOrNull()?.lowercase().orEmpty()
                val childClass = child.className?.toString().orEmpty()
                if (UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_VIEW_ID_HINTS.any { childId.contains(it) } ||
                    UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_CLASS_HINTS.any { childClass.contains(it, ignoreCase = true) }
                ) {
                    return true
                }
            } finally {
                runCatching { child.recycle() }
            }
        }
        return false
    }

    private fun hasSystemUiDialogStructure(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        if (className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("SystemUIDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true)
        ) return true

        val viewId = runCatching { node.viewIdResourceName }.getOrNull()?.lowercase().orEmpty()
        if (viewId.contains("parentpanel") || viewId.contains("contentpanel") ||
            viewId.contains("alerttitle") || viewId.contains("custompanel") ||
            viewId.contains("buttonpanel") || viewId.contains("ussd")
        ) return true

        // Check if root has a dialog panel or input child
        for (i in 0 until minOf(runCatching { node.childCount }.getOrDefault(0), 10)) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: break
            try {
                val cId = runCatching { child.viewIdResourceName }.getOrNull()?.lowercase().orEmpty()
                val cClass = child.className?.toString().orEmpty()
                if (cClass.contains("Dialog", ignoreCase = true) ||
                    cId.contains("parentpanel") || cId.contains("contentpanel") ||
                    cId.contains("alerttitle") || cId.contains("ussd") ||
                    cId.contains("input")
                ) return true
            } finally {
                runCatching { child.recycle() }
            }
        }
        return false
    }

    private fun getUssdRoot(
        preferredWindowId: Int = -1,
        allowSystemUiFallback: Boolean = true,
        skipActiveWindowCheck: Boolean = false
    ): AccessibilityNodeInfo? {
        val allowSystemWindows = (advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode || isForegroundUiActive()) &&
            allowSystemUiFallback
        if (!skipActiveWindowCheck) {
            val activeRootStartedAt = UssdPerformanceDiagnostics.mark()
            val primary = try { rootInActiveWindow } catch (_: Exception) { null }
            UssdPerformanceDiagnostics.record(
                "root.window_root_retrieval",
                activeRootStartedAt,
                "source=active_window found=${primary != null}"
            )
            if (primary != null) {
                val valStartedAt = UssdPerformanceDiagnostics.mark()
                val pkg = primary.packageName?.toString().orEmpty()
                val isKnownWindow = lastValidatedUssdWindowId > 0 && primary.windowId == lastValidatedUssdWindowId
                val isTelephonyUssd = isPotentialUssdPackage(pkg) && pkg != "android"
                val systemUiDialogAllowed = allowSystemWindows && pkg == "com.android.systemui" && shouldAllowSystemUiDialogRoot(primary, pkg)
                val allowBlocked = pkg !in BLOCKED_PACKAGES || systemUiDialogAllowed
                val allowed = allowBlocked && (
                    isKnownWindow ||
                    isTelephonyUssd ||
                    (pkg == "android" && shouldAllowAndroidDialogRoot(primary, pkg)) ||
                    systemUiDialogAllowed
                )
                UssdPerformanceDiagnostics.record(
                    "root.validation",
                    valStartedAt,
                    "pkg=$pkg allowed=$allowed source=active_window"
                )

                if (allowed) {
                    setValidatedUssdWindow(primary.windowId, pkg)
                    if (signatureLearningMode) {
                        UssdPerformanceDiagnostics.recordLearningWindowFound(pkg, primary.windowId, isDialog = true)
                    }
                    return primary
                }
                primary.recycle()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val getWindowsStartedAt = UssdPerformanceDiagnostics.mark()
            val visibleWindows = try { windows } catch (_: Exception) { null }
            UssdPerformanceDiagnostics.record(
                "root.get_windows",
                getWindowsStartedAt,
                "count=${visibleWindows?.size ?: 0}"
            )
            if (visibleWindows.isNullOrEmpty()) return null
            try {
                val cachedWinId = lastValidatedUssdWindowId

                // Fast target window retrieval: check preferred window, cached window, or active application window
                val activeTargetWinId = visibleWindows.firstOrNull {
                    runCatching { it.isActive }.getOrDefault(false) &&
                        (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM)
                }?.id ?: -1

                val directTargetWinId = when {
                    preferredWindowId > 0 -> preferredWindowId
                    cachedWinId > 0 -> cachedWinId
                    activeTargetWinId > 0 -> activeTargetWinId
                    else -> -1
                }
                var directTargetRootWasRejected = false
                if (directTargetWinId > 0) {
                    val directWindow = visibleWindows.firstOrNull { it.id == directTargetWinId }
                    if (directWindow != null) {
                        val rootStartedAt = UssdPerformanceDiagnostics.mark()
                        val root = try { directWindow.root } catch (_: Exception) { null }
                        if (root != null) {
                            UssdPerformanceDiagnostics.record(
                                "root.window_root_retrieval",
                                rootStartedAt,
                                "winId=$directTargetWinId isEventWindow=${preferredWindowId > 0 && directWindow.id == preferredWindowId} isCached=${directWindow.id == cachedWinId} found=true"
                            )
                            val pkg = root.packageName?.toString().orEmpty()
                            val isSystemUi = pkg == "com.android.systemui"
                            val allowBlocked = pkg !in BLOCKED_PACKAGES || isSystemUi
                            if (allowBlocked) {
                                val valStartedAt = UssdPerformanceDiagnostics.mark()
                                val isTelephonyDialog = isPotentialUssdPackage(pkg) && pkg != "android"
                                val isAndroidDialog = pkg == "android" && shouldAllowAndroidDialogRoot(root, pkg)
                                val isSystemUiDialog = allowSystemWindows && isSystemUi && shouldAllowSystemUiDialogRoot(root, pkg)
                                val isAllowed = isTelephonyDialog || isAndroidDialog || isSystemUiDialog
                                UssdPerformanceDiagnostics.record(
                                    "root.validation",
                                    valStartedAt,
                                    "pkg=$pkg allowed=$isAllowed winId=$directTargetWinId"
                                )
                                if (isAllowed) {
                                    setValidatedUssdWindow(directWindow.id, pkg)
                                    if (signatureLearningMode) {
                                        UssdPerformanceDiagnostics.recordLearningWindowFound(pkg, root.windowId, isDialog = true)
                                    }
                                    return root
                                }
                            }
                            root.recycle()
                            directTargetRootWasRejected = true
                        }
                    }
                }

                val enumStartedAt = UssdPerformanceDiagnostics.mark()
                data class WindowCandidate(
                    val window: AccessibilityWindowInfo,
                    val isEventWindow: Boolean,
                    val isCachedWindow: Boolean,
                    val isActive: Boolean,
                    val isApplication: Boolean,
                    val layer: Int
                )
                val candidateWindows = visibleWindows.mapNotNull { window ->
                    if (directTargetRootWasRejected && window.id == directTargetWinId) return@mapNotNull null
                    if (!allowSystemWindows &&
                        (window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
                    ) return@mapNotNull null
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                        window.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER ||
                        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    ) return@mapNotNull null

                    if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val title = runCatching { window.title?.toString()?.lowercase() }.getOrNull().orEmpty()
                        if (title.isNotBlank() && UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_WINDOW_TITLES.any { title.contains(it) }) {
                            return@mapNotNull null
                        }
                    }
                    WindowCandidate(
                        window = window,
                        isEventWindow = preferredWindowId > 0 && window.id == preferredWindowId,
                        isCachedWindow = window.id == cachedWinId,
                        isActive = runCatching { window.isActive }.getOrDefault(false),
                        isApplication = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                        layer = runCatching { window.layer }.getOrDefault(0)
                    )
                }.sortedWith(
                    compareByDescending<WindowCandidate> { it.isEventWindow }
                        .thenByDescending { it.isCachedWindow }
                        .thenByDescending { it.isActive }
                        .thenByDescending { it.isApplication }
                        .thenByDescending { it.layer }
                )
                UssdPerformanceDiagnostics.record(
                    "root.window_enumeration",
                    enumStartedAt,
                    "candidates=${candidateWindows.size}"
                )

                // Single-pass search across candidate windows to eliminate duplicate Binder IPC queries
                for (candidate in candidateWindows) {
                    val window = candidate.window
                    val rootStartedAt = UssdPerformanceDiagnostics.mark()
                    val root = try { window.root } catch (_: Exception) { null } ?: continue
                    UssdPerformanceDiagnostics.record(
                        "root.window_root_retrieval",
                        rootStartedAt,
                        "winId=${window.id} isEventWindow=${preferredWindowId > 0 && window.id == preferredWindowId} isCached=${window.id == cachedWinId} found=true"
                    )
                    val pkg = root.packageName?.toString().orEmpty()
                    val isSystemUi = pkg == "com.android.systemui"
                    if (pkg in BLOCKED_PACKAGES && !isSystemUi) {
                        root.recycle()
                        continue
                    }
                    val valStartedAt = UssdPerformanceDiagnostics.mark()
                    val isTelephonyDialog = isPotentialUssdPackage(pkg) && pkg != "android"
                    val isAndroidDialog = pkg == "android" && shouldAllowAndroidDialogRoot(root, pkg)
                    val isSystemUiDialog = allowSystemWindows && isSystemUi && shouldAllowSystemUiDialogRoot(root, pkg)
                    val isAllowed = isTelephonyDialog || isAndroidDialog || isSystemUiDialog
                    UssdPerformanceDiagnostics.record(
                        "root.validation",
                        valStartedAt,
                        "pkg=$pkg allowed=$isAllowed winId=${window.id}"
                    )
                    if (isAllowed) {
                        setValidatedUssdWindow(window.id, pkg)
                        if (signatureLearningMode) {
                            UssdPerformanceDiagnostics.recordLearningWindowFound(pkg, root.windowId, isDialog = true)
                        }
                        return root
                    }
                    root.recycle()
                    if (window.id == cachedWinId) {
                        clearValidatedUssdWindow()
                    }
                }
            } finally {
                visibleWindows.forEach { window -> runCatching { window.recycle() } }
            }
        }
        return null
    }

    private fun acquireUssdRootFast(event: AccessibilityEvent): AccessibilityNodeInfo? {
        val rootAcquireStartedAt = UssdPerformanceDiagnostics.mark()
        val eventPkg = event.packageName?.toString().orEmpty()
        val cachedWinId = lastValidatedUssdWindowId

        // Direct Active Window Path:
        // Modal carrier USSD dialogs are active in the foreground. Querying rootInActiveWindow
        // resolves in ~1ms without risking event.source Binder stalls or multi-hop
        // parent Binder traversals. Prefer active window first across all event types.
        val activeRootStartedAt = UssdPerformanceDiagnostics.mark()
        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString().orEmpty()
            val isKnownWindow = cachedWinId > 0 && activeRoot.windowId == cachedWinId
            val isEventWindow = event.windowId > 0 && activeRoot.windowId == event.windowId
            val isTelephonyUssd = isPotentialUssdPackage(pkg) && pkg != "android"
            val systemUiAllowed = pkg == "com.android.systemui" && shouldAllowSystemUiDialogRoot(activeRoot, pkg)
            val allowBlocked = pkg !in BLOCKED_PACKAGES || systemUiAllowed
            val allowed = allowBlocked && (
                isKnownWindow ||
                isEventWindow ||
                isTelephonyUssd ||
                (pkg == "android" && shouldAllowAndroidDialogRoot(activeRoot, pkg)) ||
                systemUiAllowed
            )
            if (allowed) {
                setValidatedUssdWindow(activeRoot.windowId, pkg)
                if (signatureLearningMode) {
                    UssdPerformanceDiagnostics.recordLearningWindowFound(pkg, activeRoot.windowId, isDialog = true)
                }
                UssdPerformanceDiagnostics.record(
                    "root.window_root_retrieval",
                    activeRootStartedAt,
                    "source=active_window fastPath=true found=true pkg=$pkg"
                )
                UssdPerformanceDiagnostics.record(
                    "event.root_acquire",
                    rootAcquireStartedAt,
                    "found=true fastPath=active_window pkg=$pkg"
                )
                return activeRoot
            }
            activeRoot.recycle()
        } else {
            UssdPerformanceDiagnostics.record(
                "root.window_root_retrieval",
                activeRootStartedAt,
                "source=active_window fastPath=true found=false"
            )
        }

        val isLikelyUssdEvent = (cachedWinId > 0 && event.windowId == cachedWinId) ||
            isPotentialUssdPackage(eventPkg) ||
            (eventPkg == "android" && event.windowId > 0)

        // Event Source Fallback: only when rootInActiveWindow was unavailable or non-USSD
        if (isLikelyUssdEvent && event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val sourceCooldownActive =
                event.windowId > 0 &&
                event.windowId == lastValidatedUssdWindowId &&
                eventPkg == lastValidatedUssdWindowPkg &&
                eventPkg == slowNullEventSourcePackage &&
                SystemClock.elapsedRealtime() - slowNullEventSourceElapsed < 450L
            if (sourceCooldownActive) {
                UssdPerformanceDiagnostics.record(
                    "root.source_lookup_skipped",
                    rootAcquireStartedAt,
                    "reason=recent_slow_null windowId=${event.windowId}"
                )
            } else {
                val fromEvent = obtainRootFromEvent(event)
                if (fromEvent != null) {
                    UssdPerformanceDiagnostics.record(
                        "event.root_acquire",
                        rootAcquireStartedAt,
                        "found=true source=event_direct pkg=${fromEvent.packageName}"
                    )
                    return fromEvent
                }
            }
        }

        // Comprehensive fallback through getUssdRoot()
        val fallback = getUssdRoot(
            preferredWindowId = event.windowId,
            allowSystemUiFallback = eventPkg.isBlank() ||
                eventPkg == "android" ||
                eventPkg == "com.android.systemui",
            skipActiveWindowCheck = true
        )
        UssdPerformanceDiagnostics.record(
            "event.root_acquire",
            rootAcquireStartedAt,
            "found=${fallback != null} source=window_list_fallback pkg=${fallback?.packageName}"
        )
        return fallback
    }

    private fun obtainRootFromEvent(event: AccessibilityEvent): AccessibilityNodeInfo? {
        // Fast-path: TYPE_WINDOWS_CHANGED never has an event source in Android framework.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return null
        if (event.windowId <= 0) return null

        val eventPkg = event.packageName?.toString().orEmpty()
        // Fast-rejection for SystemUI: SystemUI fires high-frequency events (status bar, notifications, clock)
        // where calling event.source blocks on Binder IPC (70-100ms) and yields null or an overlay.
        if (eventPkg == "com.android.systemui" && event.windowId != lastValidatedUssdWindowId) {
            return null
        }
        if (eventPkg.isNotBlank() && !isPotentialUssdPackage(eventPkg) && eventPkg != "android" && eventPkg != "com.android.systemui") {
            return null
        }
        if (eventPkg.isNotBlank() && eventPkg in BLOCKED_PACKAGES && eventPkg != "com.android.systemui") {
            return null
        }

        val sourceStartedAt = UssdPerformanceDiagnostics.mark()
        val source = try { event.source } catch (_: Exception) { null }
        val lookupDuration = SystemClock.elapsedRealtimeNanos() - sourceStartedAt
        UssdPerformanceDiagnostics.record(
            "root.source_lookup",
            sourceStartedAt,
            "found=" + (source != null) + " type=" + event.eventType + " pkg=" + eventPkg + " windowId=" + event.windowId
        )
        if (lookupDuration >= 8_000_000L) {
            UssdPerformanceDiagnostics.record(
                "event.source_lookup_slow",
                sourceStartedAt,
                "found=" + (source != null) + " type=" + event.eventType + " windowId=" + event.windowId
            )
        }
        if (source == null && lookupDuration >= 20_000_000L && event.windowId > 0) {
            slowNullEventSourceWindowId = event.windowId
            slowNullEventSourcePackage = eventPkg
            slowNullEventSourceElapsed = SystemClock.elapsedRealtime()
        } else if (source != null && eventPkg == slowNullEventSourcePackage) {
            slowNullEventSourceWindowId = -1
            slowNullEventSourcePackage = ""
            slowNullEventSourceElapsed = 0L
        }
        source ?: return null

        val sourcePkg = source.packageName?.toString().orEmpty()
        if (sourcePkg.isNotBlank() && sourcePkg in BLOCKED_PACKAGES && sourcePkg != "com.android.systemui") {
            source.recycle()
            return null
        }

        // Fast root walk: if source has no parent, it is already the root node
        var result: AccessibilityNodeInfo? = null
        val directParent = try { source.parent } catch (_: Exception) { null }
        if (directParent == null) {
            result = AccessibilityNodeInfo.obtain(source)
            source.recycle()
        } else {
            // When source has a parent, check if rootInActiveWindow belongs to this event's window.
            // On modal USSD dialogs, rootInActiveWindow directly gives the window root in 1ms,
            // avoiding 4-6 multi-hop parent Binder traversals that can stall for 50ms+.
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && activeRoot.windowId == event.windowId) {
                result = activeRoot
                directParent.recycle()
                source.recycle()
            } else {
                activeRoot?.recycle()
                // Bounded parent walk: USSD dialogs have <= 4 levels
                var current: AccessibilityNodeInfo? = directParent
                var depth = 1
                val parentWalkStartedAt = UssdPerformanceDiagnostics.mark()
                try {
                    while (current != null && depth < 4) {
                        val next = try { current.parent } catch (_: Exception) { null }
                        if (next == null) {
                            result = AccessibilityNodeInfo.obtain(current)
                            break
                        }
                        current.recycle()
                        current = next
                        depth++
                    }
                } finally {
                    current?.recycle()
                    source.recycle()
                }
                val parentDuration = SystemClock.elapsedRealtimeNanos() - parentWalkStartedAt
                UssdPerformanceDiagnostics.record(
                    "root.parent_traversal",
                    parentWalkStartedAt,
                    "depth=" + depth + " found=" + (result != null)
                )
                if (parentDuration >= 8_000_000L) {
                    UssdPerformanceDiagnostics.record(
                        "event.parent_walk_slow",
                        parentWalkStartedAt,
                        "depth=" + depth + " found=" + (result != null)
                    )
                }
            }
        }

        val candidate = result ?: return null
        val validationStartedAt = UssdPerformanceDiagnostics.mark()
        val pkg = candidate.packageName?.toString().orEmpty()
        val systemUiDialogAllowed = pkg == "com.android.systemui" && shouldAllowSystemUiDialogRoot(candidate, pkg)
        val allowBlocked = pkg !in BLOCKED_PACKAGES || systemUiDialogAllowed
        val isConfirmedDialog = allowBlocked &&
            (isPotentialUssdPackage(pkg) ||
                pkg == "android" ||
                shouldAllowAndroidDialogRoot(candidate, pkg) ||
                systemUiDialogAllowed)

        val valDuration = SystemClock.elapsedRealtimeNanos() - validationStartedAt
        UssdPerformanceDiagnostics.record(
            "root.validation",
            validationStartedAt,
            "pkg=" + pkg + " allowed=" + isConfirmedDialog + " source=event"
        )
        if (valDuration >= 8_000_000L) {
            UssdPerformanceDiagnostics.record(
                "event.root_validation_slow",
                validationStartedAt,
                "pkg=" + pkg + " allowed=" + isConfirmedDialog
            )
        }
        if (!isConfirmedDialog) {
            candidate.recycle()
            return null
        }
        setValidatedUssdWindow(candidate.windowId, pkg)
        if (signatureLearningMode) {
            UssdPerformanceDiagnostics.recordLearningWindowFound(pkg, candidate.windowId, isDialog = true)
        }
        return candidate
    }

    private fun shouldAllowSystemUiDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (pkg != "com.android.systemui") return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode && !isForegroundUiActive()) return false

        // 1. Fast rejection: check if this is an unrelated SystemUI notification, shade, or status bar
        if (isSystemUiNotificationOrOverlay(root)) {
            if (signatureLearningMode) {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(pkg, runCatching { root.windowId }.getOrDefault(-1), "notification_or_overlay")
            }
            return false
        }

        // 2. Strict dialog container requirement: DecorView/FrameLayout alone is NOT enough for SystemUI
        if (!hasSystemUiDialogStructure(root)) {
            if (signatureLearningMode) {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(pkg, runCatching { root.windowId }.getOrDefault(-1), "lacks_dialog_structure")
            }
            return false
        }

        // 3. Build bounded snapshot
        val summary = captureTreeSnapshot(root)
        val dialogText = summary.dialogText
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false

        // 4. Must have an interactive USSD affordance (reply field OR send+dismiss button pair)
        val hasAction = summary.hasEditableField || (summary.hasSendButton && summary.hasDismissButton)
        if (!hasAction) {
            if (signatureLearningMode) {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(pkg, runCatching { root.windowId }.getOrDefault(-1), "no_dialog_action")
            }
            return false
        }

        // 5. Must contain USSD wording or structured menu options (never allow via startupRelaxed alone)
        val menuLike = MENU_LIKE_REGEX.containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val allowed = hasUssdLanguage || menuLike
        if (allowed) {
            rememberValidatedRootSnapshot(root, summary)
        } else if (signatureLearningMode) {
            UssdPerformanceDiagnostics.recordLearningWindowRejected(pkg, runCatching { root.windowId }.getOrDefault(-1), "no_ussd_content")
        }
        return allowed
    }

    private fun shouldAllowAndroidDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (!(pkg.isBlank() || pkg == "android")) return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !isForegroundUiActive()) return false

        // Fast rejection: check if root has basic dialog-like structure before running expensive snapshot
        val className = root.className?.toString().orEmpty()
        val isDialogLike = className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true) ||
            className.contains("DecorView", ignoreCase = true) ||
            className.contains("FrameLayout", ignoreCase = true) ||
            className.contains("LinearLayout", ignoreCase = true) ||
            className.contains("RelativeLayout", ignoreCase = true) ||
            className.contains("ViewGroup", ignoreCase = true) ||
            (runCatching { root.childCount }.getOrDefault(0) in 1..25)
        if (!isDialogLike) return false

        // Reuse one bounded tree snapshot for layout, text and action validation.
        val summary = captureTreeSnapshot(root)
        if (!hasDialogLayout(root, summary)) return false
        val dialogText = summary.dialogText
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = summary.hasSendButton || summary.hasDismissButton || summary.hasEditableField
        if (!hasAction) return false
        val menuLike = MENU_LIKE_REGEX.containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val startupRelaxed = advancedActive &&
            !hasSeenUssdPopup() &&
            (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= UssdNavigationConfig.STARTUP_UI_KEEP_VISIBLE_MS
        val allowed = hasUssdLanguage || menuLike || startupRelaxed
        if (allowed) rememberValidatedRootSnapshot(root, summary)
        return allowed
    }

    private fun rememberValidatedRootSnapshot(root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot) {
        validatedRootSnapshot = snapshot
        val winId = runCatching { root.windowId }.getOrDefault(-1)
        val pkg = root.packageName?.toString().orEmpty()
        validatedRootWindowId = winId
        validatedRootPackage = pkg
        activePopupSnapshot = snapshot
        activeSnapshotWindowId = winId
        activeSnapshotPackage = pkg
        activeSnapshotStepIndex = currentStep
        activeSnapshotCapturedElapsed = SystemClock.elapsedRealtime()
    }

    private fun takeValidatedRootSnapshot(root: AccessibilityNodeInfo, windowId: Int, windowPackage: String): UssdTreeSnapshot? {
        val rootWinId = runCatching { root.windowId }.getOrDefault(-1)
        val effectiveWinId = if (windowId > 0) windowId else rootWinId
        if (validatedRootWindowId <= 0 || validatedRootWindowId != effectiveWinId) return null
        if (windowPackage.isNotBlank() && validatedRootPackage.isNotBlank() && validatedRootPackage != windowPackage) return null
        return validatedRootSnapshot
    }

    private data class MenuOptionDescriptor(
        val key: String,
        val label: String,
        val normalizedLabel: String
    )

    private data class ParsedMenuSignature(
        val title: String,
        val options: LinkedHashMap<String, String>,
        val normalizedTitle: String,
        val optionDescriptors: List<MenuOptionDescriptor>,
        val normalizedOptionLabels: Set<String>
    )

    private data class LearnedSignatureContext(
        val step: UssdSignatureStep,
        val normalizedSelectedLabel: String,
        val normalizedMenuTitle: String,
        val normalizedOptionSnapshot: Set<String>
    )

    internal data class UssdTreeSnapshot(
        val dialogText: String,
        val normalizedDialogText: String,
        val promptText: String,
        val textTokens: List<String>,
        val hasEditableField: Boolean,
        // Captured during the existing tree walk; avoids another Binder lookup in
        // the pending-step same-field guard when the previously validated ID is known.
        val cachedEditableFieldText: String?,
        val hasSendButton: Boolean,
        val hasDismissButton: Boolean
    )

    private data class DialogCaptureCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    private data class StructuredMenuBlock(
        val titleLines: List<String>,
        val options: LinkedHashMap<String, String>
    )

    private data class MenuOptionMatch(
        val optionKey: String,
        val optionLabel: String,
        val autoAdjustSafe: Boolean
    )

    private fun recordedInputForStep(stepIndex: Int, rawStep: String): String =
        when {
            rawStep == "INPUT_PHONE" -> advancedPhoneNumber
            stepIndex >= 0 -> adjustedStepInputs[stepIndex].takeUnless { it.isNullOrBlank() } ?: rawStep
            else -> rawStep
        }

    private fun selectedOptionLabelForInput(
        enteredInput: String,
        rawStep: String,
        menu: ParsedMenuSignature?
    ): String =
        when {
            rawStep == "INPUT_PHONE" -> "Enter phone number"
            enteredInput.all(Char::isDigit) -> menu?.options?.get(enteredInput)
                ?: menu?.options?.get(rawStep)
                .orEmpty()
            else -> ""
        }

    private fun captureSignatureStepIfNeeded(
        stepIndex: Int,
        rawStep: String,
        menu: ParsedMenuSignature?,
        snapshot: UssdTreeSnapshot,
        dialogText: String,
        interactionRoot: AccessibilityNodeInfo,
        windowPkg: String
    ) {
        if (!signatureLearningMode) return
        val isString = isPhoneOrStringStep(rawStep)
        UssdPerformanceDiagnostics.recordLearningExtractionAttempted(stepIndex, isString)

        val effectiveSnapshot = if (windowPkg == "com.android.systemui") {
            val dialogRoot = findSignatureLearningDialogRoot(interactionRoot, windowPkg)
            if (dialogRoot == null) {
                // CRITICAL: Never fall back to unverified SystemUI notifications/windows for signature learning
                UssdPerformanceDiagnostics.recordLearningWindowRejected(windowPkg, runCatching { interactionRoot.windowId }.getOrDefault(-1), "unverified_systemui_learning_root")
                return
            }
            try {
                getOrCaptureActiveSnapshot(dialogRoot)
            } finally {
                runCatching { dialogRoot.recycle() }
            }
        } else {
            if (isPotentialUssdPackage(windowPkg) || windowPkg == "android" || windowPkg.isBlank()) {
                snapshot
            } else {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(windowPkg, runCatching { interactionRoot.windowId }.getOrDefault(-1), "non_ussd_package")
                return
            }
        }
        val text = effectiveSnapshot.dialogText.trim()
            .ifBlank { effectiveSnapshot.promptText.trim() }
            .ifBlank { effectiveSnapshot.textTokens.joinToString("\n").trim() }
        if (text.isBlank()) return

        val effectiveMenu = parseMenuSignature(effectiveSnapshot) ?: menu
        UssdPerformanceDiagnostics.recordLearningTextExtracted(
            effectiveSnapshot.textTokens.size,
            text.length,
            effectiveMenu != null
        )

        // Stage 2: Normalization
        val normalized = normalizeMenuText(text)
        UssdPerformanceDiagnostics.recordLearningNormalization(stepIndex, text.length, normalized.length)

        // Stage 3: Options identification
        val optionsCount = (effectiveMenu ?: menu)?.options?.size ?: 0
        val hasTitle = (effectiveMenu ?: menu)?.title?.isNotBlank() == true
        UssdPerformanceDiagnostics.recordLearningOptionsIdentified(stepIndex, optionsCount, hasTitle)

        val optionLabel = when {
            isString -> if (rawStep.equals("INPUT_PHONE", ignoreCase = true)) "Enter phone number" else "Text Input"
            effectiveMenu != null && effectiveMenu.options.containsKey(rawStep) -> effectiveMenu.options[rawStep].orEmpty()
            menu != null && menu.options.containsKey(rawStep) -> menu.options[rawStep].orEmpty()
            else -> selectedOptionLabelForInput(rawStep, rawStep, effectiveMenu ?: menu)
        }

        val optionsSnapshot = (effectiveMenu ?: menu)?.options?.values
            ?.map { normalizeCollapsedText(it) }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        // Stage 4: Generate Signature
        val captured = UssdSignatureStep(
            stepIndex = stepIndex,
            expectedInput = rawStep,
            menuTitle = (effectiveMenu ?: menu)?.title ?: "",
            menuText = formatLearningRecordedDialogText(effectiveSnapshot, effectiveMenu),
            selectedOptionLabel = optionLabel,
            menuOptionsSnapshot = optionsSnapshot
        )
        val existingIndex = learnedSignatureSteps.indexOfFirst { it.stepIndex == stepIndex }
        if (existingIndex >= 0) learnedSignatureSteps[existingIndex] = captured else learnedSignatureSteps.add(captured)

        UssdPerformanceDiagnostics.recordLearningSignatureGenerated(
            stepIndex,
            optionsSnapshot.size,
            optionLabel.isNotBlank()
        )

        // Capture dialog snapshot into learningCaptures transcript as well
        captureLearningDialog(effectiveSnapshot)
    }

    private fun scheduleProcessStep(dialogChanged: Boolean, overrideDelayMs: Long? = null) {
        if (dialogChanged && waitingForRootSinceElapsed > 0L) {
            clearRootRecoveryState()
        }
        processStepJob?.cancel()
        val token = pendingProcessToken
        val version = ++processStepVersion
        val delayMs = overrideDelayMs ?: when {
            dialogChanged -> 0L
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> 0L
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> verifyPollMs
        }
        processStepJob = processingScope.launch {
            if (delayMs > 0L) delay(delayMs)
            if (pendingProcessToken != token) return@launch
            if (version != processStepVersion) return@launch
            if (!advancedActive || isProcessing) return@launch
            isProcessing = true
            processStep()
        }
    }

    private fun verificationPollDelay(expected: String, noFieldCount: Int = 0): Long =
        when {
            hasRecentExpectedInput(expected) && hasRecentUssdUiEvent() -> postWriteVerifyPollMs
            hasRecentExpectedInput(expected) -> postWriteVerifyPollMs
            hasSeenAdvancedPopup && noFieldCount > 0 -> rapidPostPopupVerifyMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupVerifyMs
            hasRecentUssdUiEvent() && noFieldCount > 0 -> eventHotPollMs
            noFieldCount > 0 -> fastVerifyPollMs
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> verifyPollMs
        }

    private fun hasRecentUssdUiEvent(): Boolean =
        SystemClock.elapsedRealtime() - lastRelevantEventElapsed <= UssdNavigationConfig.RECENT_UI_EVENT_GRACE_MS

    private fun sendRetryDelay(attempt: Int, expectedValue: String? = null): Long {
        val hasRecentWrite = expectedValue?.let { hasRecentExpectedInput(it) } == true
        val hasRecentVerification = expectedValue?.let { hasRecentVerifiedInput(it) } == true
        if (useAggressiveVerifiedPopupFastPath &&
            attempt <= 1 &&
            (hasRecentVerification || (hasRecentWrite && hasRecentUssdUiEvent()))
        ) {
            return UssdNavigationConfig.PENDING_STEP_ADVANCE_KICK_MS
        }
        val base = when {
            hasRecentVerification || hasRecentWrite -> postWriteSendRetryMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupSendRetryMs
            hasSeenAdvancedPopup -> hotSendRetryDelayMs
            hasRecentUssdUiEvent() -> hotSendRetryDelayMs
            else -> sendRetryDelayMs
        }
        val increment = when {
            hasRecentVerification || hasRecentWrite -> fastSendRetryIncrementMs
            hasSeenAdvancedPopup -> fastSendRetryIncrementMs
            else -> sendRetryIncrementMs
        }
        return minOf(base + (attempt.toLong() * increment), maxSendRetryDelayMs)
    }

    /**
     * Precomputes only values that do not depend on the contents of a future USSD menu.
     * Signature-guarded numeric selections are resolved against the live menu for safety.
     */
    private fun prepareStepInput(stepIndex: Int) {
        val rawStep = advancedSteps.getOrNull(stepIndex) ?: run {
            if (preparedStepInput?.stepIndex == stepIndex) preparedStepInput = null
            return
        }
        if (rawStep.all(Char::isDigit) && signatureGuardEnabled) {
            preparedStepInput = null
            return
        }
        val isPhone = rawStep.equals("INPUT_PHONE", ignoreCase = true) ||
            rawStep.equals("pn", ignoreCase = true) ||
            (advancedPhoneNumber.isNotBlank() &&
                (rawStep == advancedPhoneNumber ||
                    rawStep == UssdHelper.normalizeRecipientForUssdInput(advancedPhoneNumber)))
        val value = if (isPhone) {
            val source = advancedPhoneNumber.ifBlank { rawStep }
            UssdHelper.normalizeRecipientForUssdInput(source)
        } else {
            rawStep
        }
        preparedStepInput = PreparedStepInput(
            stepIndex = stepIndex,
            rawStep = rawStep,
            phoneNumber = advancedPhoneNumber,
            value = value
        )
    }

    private fun resolveStepInput(stepIndex: Int, rawStep: String, menu: ParsedMenuSignature?): Pair<String, String?> {
        val prepared = preparedStepInput
        if (prepared != null &&
            prepared.stepIndex == stepIndex &&
            prepared.rawStep == rawStep &&
            prepared.phoneNumber == advancedPhoneNumber &&
            !(rawStep.all(Char::isDigit) && signatureGuardEnabled)
        ) {
            preparedStepInput = null
            adjustedStepInputs[stepIndex] = rawStep
            val label = menu?.options?.get(prepared.value) ?: menu?.options?.get(rawStep)
            return prepared.value to label
        }
        val isPhone = rawStep.equals("INPUT_PHONE", ignoreCase = true) ||
            rawStep.equals("pn", ignoreCase = true) ||
            (advancedPhoneNumber.isNotBlank() && (rawStep == advancedPhoneNumber || rawStep == UssdHelper.normalizeRecipientForUssdInput(advancedPhoneNumber)))
        if (isPhone) {
            adjustedStepInputs[stepIndex] = rawStep
            val phone = if (advancedPhoneNumber.isNotBlank()) {
                UssdHelper.normalizeRecipientForUssdInput(advancedPhoneNumber)
            } else {
                UssdHelper.normalizeRecipientForUssdInput(rawStep)
            }
            return phone to null
        }
        if (isPhoneOrStringStep(rawStep)) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to null
        }
        if (!rawStep.all(Char::isDigit) || !signatureGuardEnabled) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to menu?.options?.get(rawStep)
        }
        val learned = getLoadedSignatureContext(stepIndex) ?: run {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to menu?.options?.get(rawStep)
        }
        if (menu == null || menu.options.isEmpty()) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to null
        }

        val match = findBestMenuOptionMatch(menu, learned)
        if (match == null) {
            val message = buildChangeMessage(
                learned = learned.step,
                actualOption = null,
                actualLabel = null
            )
            signatureChangeDetected = true
            detectedChangeNotes += message
            if (signatureAction == "CONTINUE") {
                UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "continued_after_change", rawStep)
                adjustedStepInputs[stepIndex] = rawStep
                return rawStep to null
            }
            UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "rejected", rawStep)
            failForSignatureChange(message)
            return rawStep to null
        }
        UssdPerformanceDiagnostics.recordLearningSignatureMatched(stepIndex, match.optionKey, match.autoAdjustSafe)
        if (match.optionKey != rawStep) {
            signatureChangeDetected = true
            detectedChangeNotes += buildChangeMessage(
                learned = learned.step,
                actualOption = match.optionKey,
                actualLabel = match.optionLabel
            )
            if (signatureAction == "CONTINUE") {
                UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "continued_after_change", rawStep)
                adjustedStepInputs[stepIndex] = rawStep
                return rawStep to null
            }
            if (signatureAction != "ADJUST" || !match.autoAdjustSafe) {
                UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "rejected_change", match.optionKey)
                failForSignatureChange(detectedChangeNotes.last())
                return rawStep to null
            }
            signatureAutoAdjusted = true
            adjustedStepInputs[stepIndex] = match.optionKey
            UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "auto_adjusted", match.optionKey)
            return match.optionKey to match.optionLabel
        }
        adjustedStepInputs[stepIndex] = rawStep
        UssdPerformanceDiagnostics.recordLearningNavigationAction(stepIndex, "exact_match", match.optionKey)
        return rawStep to match.optionLabel
    }

    private fun buildChangeMessage(
        learned: UssdSignatureStep,
        actualOption: String?,
        actualLabel: String?
    ): String {
        val menuLabel = learned.menuTitle.ifBlank { advancedOfferName.ifBlank { "the USSD menu" } }
        return if (actualOption.isNullOrBlank() || actualLabel.isNullOrBlank()) {
            "Detected a menu change in $menuLabel. The learned option '${learned.selectedOptionLabel}' is no longer available as selection ${learned.expectedInput}"
        } else {
            "Detected a menu change in $menuLabel. '${learned.selectedOptionLabel}' moved from option ${learned.expectedInput} to option $actualOption"
        }
    }

    private fun failForSignatureChange(message: String) {
        lastFinalResponse = message
        onDispatchComplete?.invoke(buildDispatchResult(message))
        closeCurrentUssdUi()
        advancedInProgress = false
        updateRunningOverlay()
        cleanupAdvanced()
    }

    private fun finishAdvancedDispatch(finalText: String, outcomeUncertain: Boolean = false) {
        val result = buildDispatchResult(finalText, outcomeUncertain)
        onDispatchComplete?.invoke(result)

        closeCurrentUssdUi()
        advancedInProgress = false
        updateRunningOverlay()
        cleanupAdvanced()
    }

    private fun buildDispatchResult(finalResponse: String, outcomeUncertain: Boolean = false): AdvancedDispatchResult {
        val sigList: List<UssdSignatureStep>
        val capList: List<UssdLearningCapture>
        val transcript: List<String>
        sigList = learnedSignatureSteps.toList()
        capList = learningCaptures.toList()
        transcript = popupTranscript.toList()
        return AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = signatureChangeDetected,
            autoAdjusted = signatureAutoAdjusted,
            learningCompleted = signatureLearningMode && (sigList.isNotEmpty() || capList.isNotEmpty()),
            suggestedCode = if (signatureChangeDetected) buildSuggestedCode() else "",
            changeSummary = detectedChangeNotes.joinToString(". "),
            learnedSignature = sigList,
            learningCaptures = capList,
            popupTranscript = transcript,
            outcomeUncertain = outcomeUncertain
        )
    }

    private fun shouldRecordPopupTranscript(): Boolean =
        signatureLearningMode || signatureGuardEnabled

    private fun capturePopupTranscript(snapshot: UssdTreeSnapshot?, dialogText: String) {
        if (!shouldRecordPopupTranscript()) return
        val recordedText = snapshot?.let {
            val menu = parseMenuSignature(it)
            if (signatureLearningMode) {
                formatLearningRecordedDialogText(snapshot = it, menu = menu)
            } else {
                formatRecordedDialogText(it.textTokens, it.dialogText)
            }
        } ?: formatRecordedDialogText(emptyList(), dialogText)
        if (recordedText.isBlank()) return
        if (popupTranscript.lastOrNull() == recordedText) return
        popupTranscript += recordedText
    }

    private fun captureLearningDialog(snapshot: UssdTreeSnapshot) {
        // A popup can expose its text without exposing a parseable numbered menu.
        // Preserve the readable prompt instead of dropping the learning sample.
        val menu = parseMenuSignature(snapshot)
        val recordedText = formatLearningRecordedDialogText(snapshot, menu)
            .ifBlank { snapshot.dialogText }
            .ifBlank { snapshot.promptText }
            .ifBlank { snapshot.textTokens.joinToString("\n") }
            .trim()
        if (recordedText.isBlank()) return

        val captureIndex = when {
            advancedSteps.isEmpty() -> -1
            currentStep >= advancedSteps.size -> advancedSteps.lastIndex
            else -> currentStep
        }
        val rawStep = advancedSteps.getOrNull(captureIndex).orEmpty()
        val enteredInput = recordedInputForStep(captureIndex, rawStep)
        val selectedOptionLabel = selectedOptionLabelForInput(enteredInput, rawStep, menu)
        val capture = UssdLearningCapture(
            stepIndex = captureIndex,
            enteredInput = enteredInput,
            selectedOptionLabel = selectedOptionLabel,
            popupText = recordedText
        )
        val existingIndex = learningCaptures.indexOfFirst {
            it.stepIndex == capture.stepIndex &&
                normalizeMenuText(it.popupText) == normalizeMenuText(capture.popupText)
        }
        if (existingIndex >= 0) {
            val existing = learningCaptures[existingIndex]
            learningCaptures[existingIndex] = existing.copy(
                enteredInput = capture.enteredInput.ifBlank { existing.enteredInput },
                selectedOptionLabel = capture.selectedOptionLabel.ifBlank { existing.selectedOptionLabel },
                popupText = if (capture.popupText.length >= existing.popupText.length) capture.popupText else existing.popupText
            )
        } else {
            learningCaptures += capture
        }
    }

    private fun buildSuggestedCode(): String {
        val dialBase = advancedDialCode.trim().replace("%23", "").trimEnd('#')
        val steps = advancedSteps.mapIndexed { index, step ->
            when {
                step == "INPUT_PHONE" -> "pn"
                adjustedStepInputs[index].isNullOrBlank() -> step
                else -> adjustedStepInputs[index] ?: step
            }
        }
        return if (steps.isEmpty()) "$dialBase#" else "$dialBase*${steps.joinToString("*")}#"
    }

    private fun normalizeMenuText(text: String): String =
        UssdSignatureMatchPolicy.normalize(text)

    private fun calculateDialogSimilarity(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        val setA = a.split(Regex("\\s+")).toSet()
        val setB = b.split(Regex("\\s+")).toSet()
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union > 0) intersection.toDouble() / union else 0.0
    }

    private fun buildLearnedSignatureContext(step: UssdSignatureStep): LearnedSignatureContext {
        val normalizedSelectedLabel = normalizeMenuText(step.selectedOptionLabel)
        val normalizedMenuTitle = normalizeMenuText(step.menuTitle)
        return LearnedSignatureContext(
            step = step,
            normalizedSelectedLabel = normalizedSelectedLabel,
            normalizedMenuTitle = normalizedMenuTitle,
            normalizedOptionSnapshot = step.menuOptionsSnapshot
                .asSequence()
                .map(::normalizeMenuText)
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    private fun getLoadedSignatureContext(stepIndex: Int): LearnedSignatureContext? {
        if (loadedSignatureLookupSource !== loadedSignatureSteps) {
            loadedSignatureLookupSource = loadedSignatureSteps
            loadedSignatureLookup = loadedSignatureSteps
                .asSequence()
                .map(::buildLearnedSignatureContext)
                .associateBy { it.step.stepIndex }
        }
        return loadedSignatureLookup[stepIndex]
    }

    private fun markStepAction(dialogText: String, stepIndex: Int = -1) {
        lastStepActionKey = normalizeMenuText(dialogText)
        lastStepActionElapsed = SystemClock.elapsedRealtime()
        lastStepActionIndex = if (stepIndex >= 0) stepIndex else lastStepActionIndex
    }

    private fun shouldWaitForStepTransition(dialogText: String, currentStepIndex: Int = -1): Boolean {
        val key = normalizeMenuText(dialogText)
        if (lastStepActionKey.isBlank() || key.isBlank()) return false
        val similarity = calculateDialogSimilarity(key, lastStepActionKey)
        val sameOrSimilarPrompt = key == lastStepActionKey || similarity > 0.85
        val differentStep = currentStepIndex >= 0 &&
            lastStepActionIndex >= 0 &&
            currentStepIndex != lastStepActionIndex
        if (!sameOrSimilarPrompt || differentStep) {
            lastStepActionKey = ""
            lastStepActionElapsed = 0L
            lastStepActionIndex = -1
            return false
        }
        // A recreated Android window is not proof of a new carrier prompt.
        return SystemClock.elapsedRealtime() - lastStepActionElapsed <
            UssdNavigationConfig.STEP_TRANSITION_GUARD_MS
    }

    private fun parseMenuSignature(snapshot: UssdTreeSnapshot): ParsedMenuSignature? {
        val cacheKey = snapshot.normalizedDialogText
        if (cacheKey.isNotBlank() && cacheKey == lastMenuSignatureKey) {
            return lastMenuSignature
        }
        val parsed = buildMenuSignature(snapshot.textTokens)
        if (cacheKey.isNotBlank()) {
            lastMenuSignatureKey = cacheKey
            lastMenuSignature = parsed
        }
        return parsed
    }

    private fun parseMenuSignature(
        root: AccessibilityNodeInfo,
        dialogText: String = extractAllText(root)
    ): ParsedMenuSignature? {
        val cacheKey = normalizeCollapsedText(dialogText)
        if (cacheKey.isNotBlank() && cacheKey == lastMenuSignatureKey) {
            return lastMenuSignature
        }
        val parsed = buildMenuSignature(extractTextTokens(root))
        if (cacheKey.isNotBlank()) {
            lastMenuSignatureKey = cacheKey
            lastMenuSignature = parsed
        }
        return parsed
    }

    private fun buildMenuSignature(tokens: List<String>): ParsedMenuSignature? {
        val normalizedTokens = normalizeDialogLines(tokens)
        val menuBlock = extractStructuredMenuBlock(normalizedTokens) ?: return null
        val options = menuBlock.options
        val title = menuBlock.titleLines
            .distinct()
            .takeLast(2)
            .joinToString(" / ")
        val normalizedTitle = normalizeMenuText(title)
        val optionDescriptors = options.entries.map { (key, label) ->
            val normalizedLabel = normalizeMenuText(label)
            MenuOptionDescriptor(
                key = key,
                label = label,
                normalizedLabel = normalizedLabel
            )
        }
        return ParsedMenuSignature(
            title = title,
            options = LinkedHashMap(options),
            normalizedTitle = normalizedTitle,
            optionDescriptors = optionDescriptors,
            normalizedOptionLabels = optionDescriptors
                .asSequence()
                .map { it.normalizedLabel }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    private fun normalizeDialogLines(tokens: List<String>): List<String> {
        val collapsed = mutableListOf<String>()
        tokens.forEach { token ->
            token.lineSequence().forEach { line ->
                val normalizedLine = normalizeCollapsedText(line)
                if (normalizedLine.isNotBlank() && collapsed.lastOrNull() != normalizedLine) {
                    collapsed += normalizedLine
                }
            }
        }
        return collapsed
    }

    private fun extractStructuredMenuBlock(lines: List<String>): StructuredMenuBlock? {
        if (lines.isEmpty()) return null
        var bestBlock: StructuredMenuBlock? = null
        for (startIndex in lines.indices) {
            val token = lines[startIndex]
            val key = parseStructuredMenuKey(token) ?: continue
            val candidate = collectStructuredMenuBlock(lines, startIndex) ?: continue
            val currentBest = bestBlock
            if (
                currentBest == null ||
                candidate.options.size > currentBest.options.size ||
                (
                    candidate.options.size == currentBest.options.size &&
                        candidate.titleLines.size > currentBest.titleLines.size
                    )
            ) {
                bestBlock = candidate
            }
        }
        return bestBlock
    }

    private fun collectStructuredMenuBlock(
        lines: List<String>,
        startIndex: Int
    ): StructuredMenuBlock? {
        val options = linkedMapOf<String, String>()
        var pendingOptionKey: String? = null

        for (index in startIndex until lines.size) {
            val token = lines[index]
            val normalizedAction = normalizeActionLabel(token)
            if (normalizedAction in SEND_BUTTON_LABELS || normalizedAction in DISMISS_BUTTON_LABELS) {
                if (options.isNotEmpty()) break
                continue
            }

            val numberedOption = MENU_OPTION_REGEX.matchEntire(token)
            if (numberedOption != null) {
                val key = numberedOption.groupValues[1]
                if (!isNextStructuredMenuKey(options, key)) {
                    if (options.isNotEmpty()) break
                    continue
                }
                val label = normalizeCollapsedText(numberedOption.groupValues[2])
                if (label.isBlank()) break
                options[key] = label
                pendingOptionKey = null
                continue
            }

            val numberOnly = MENU_OPTION_NUMBER_ONLY_REGEX.matchEntire(token)
            if (numberOnly != null) {
                val key = numberOnly.groupValues[1]
                if (!isNextStructuredMenuKey(options, key)) {
                    if (options.isNotEmpty()) break
                    continue
                }
                pendingOptionKey = key
                continue
            }

            val deferredKey = pendingOptionKey
            if (deferredKey != null) {
                if (!looksLikeMenuLabel(token)) break
                options[deferredKey] = token
                pendingOptionKey = null
                continue
            }

            if (options.isNotEmpty()) break
        }

        if (pendingOptionKey != null) return null
        if (!looksLikeStructuredUssdMenu(LinkedHashMap(options))) return null

        val titleLines = lines
            .take(startIndex)
            .takeLast(2)
            .map(::normalizeCollapsedText)
            .filter { line ->
                line.isNotBlank() &&
                    normalizeActionLabel(line) !in SEND_BUTTON_LABELS &&
                    normalizeActionLabel(line) !in DISMISS_BUTTON_LABELS
            }

        return StructuredMenuBlock(
            titleLines = titleLines,
            options = LinkedHashMap(options)
        )
    }

    private fun parseStructuredMenuKey(token: String): String? =
        MENU_OPTION_REGEX.matchEntire(token)?.groupValues?.get(1)
            ?: MENU_OPTION_NUMBER_ONLY_REGEX.matchEntire(token)?.groupValues?.get(1)

    private fun isNextStructuredMenuKey(
        options: LinkedHashMap<String, String>,
        key: String
    ): Boolean {
        val numericKey = key.toIntOrNull() ?: return false
        if (options.isEmpty()) return true
        if (options.containsKey(key)) return false
        val lastKey = options.keys.lastOrNull()?.toIntOrNull()
        if (lastKey != null) {
            if (numericKey == lastKey + 1) return true
            if (numericKey in setOf(0, 98, 99, 100)) return true
            if (numericKey > lastKey && numericKey <= lastKey + 10) return true
        }
        return options.size < 25
    }

    private fun formatRecordedDialogText(tokens: List<String>, fallbackText: String = ""): String {
        val normalizedLines = normalizeDialogLines(tokens)
        extractStructuredMenuBlock(normalizedLines)?.let { menuBlock ->
            return buildList {
                menuBlock.titleLines.forEach(::add)
                menuBlock.options.forEach { (key, label) -> add("$key. ${normalizeCollapsedText(label)}") }
            }
                .map(::normalizeCollapsedText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }
        if (normalizedLines.isEmpty()) return normalizeCollapsedText(fallbackText)

        val recordedLines = mutableListOf<String>()
        var pendingOptionKey: String? = null

        for (token in normalizedLines) {
            val numberedOption = MENU_OPTION_REGEX.find(token)
            if (numberedOption != null) {
                pendingOptionKey = null
                val key = numberedOption.groupValues[1]
                val label = normalizeCollapsedText(numberedOption.groupValues[2])
                recordedLines += if (label.isBlank()) key else "$key. $label"
                continue
            }

            val numberOnly = MENU_OPTION_NUMBER_ONLY_REGEX.find(token)
            if (numberOnly != null) {
                pendingOptionKey = numberOnly.groupValues[1]
                continue
            }

            val deferredKey = pendingOptionKey
            if (deferredKey != null && looksLikeMenuLabel(token)) {
                recordedLines += "$deferredKey. $token"
                pendingOptionKey = null
                continue
            }

            if (deferredKey != null) {
                recordedLines += deferredKey
                pendingOptionKey = null
            }

            recordedLines += token
        }

        pendingOptionKey?.let(recordedLines::add)

        return recordedLines
            .map(::normalizeCollapsedText)
            .filter { it.isNotBlank() }
            .let { lines ->
                if (lines.size < 2) lines else buildList {
                    lines.forEach { line ->
                        if (lastOrNull() != line) add(line)
                    }
                }
            }
            .joinToString("\n")
    }

    private fun formatLearningRecordedDialogText(
        snapshot: UssdTreeSnapshot,
        menu: ParsedMenuSignature?
    ): String {
        if (menu == null) {
            return formatRecordedDialogText(snapshot.textTokens, snapshot.dialogText)
                .ifBlank { normalizeCollapsedText(snapshot.dialogText) }
        }
        extractStructuredMenuBlock(normalizeDialogLines(snapshot.textTokens))?.let { menuBlock ->
            return buildList {
                menuBlock.titleLines.forEach(::add)
                menuBlock.options.forEach { (key, label) -> add("$key. ${normalizeCollapsedText(label)}") }
            }.joinToString("\n")
        }
        val lines = buildList {
            menu.title
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeCollapsedText)
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            menu.options.forEach { (key, label) ->
                val normalizedLabel = normalizeCollapsedText(label)
                if (normalizedLabel.isBlank()) add(key) else add("$key. $normalizedLabel")
            }
        }
        if (lines.isNotEmpty()) {
            return lines.joinToString("\n")
        }
        return formatRecordedDialogText(snapshot.textTokens, snapshot.dialogText)
    }

    private fun looksLikeMenuLabel(token: String): Boolean {
        val normalized = normalizeActionLabel(token)
        if (normalized.isBlank()) return false
        if (normalized in SEND_BUTTON_LABELS || normalized in DISMISS_BUTTON_LABELS) return false
        return normalized.any(Char::isLetterOrDigit)
    }

    private fun findBestMenuOptionMatch(
        menu: ParsedMenuSignature,
        learned: LearnedSignatureContext
    ): MenuOptionMatch? {
        val normalizedExpected = learned.normalizedSelectedLabel
        if (normalizedExpected.isBlank()) return null
        if (!isMenuContextCompatible(menu, learned)) return null

        // Signature verification is deliberately deterministic: only the same normalized
        // carrier option label is accepted. No fuzzy scoring, token weighting or AI inference.
        val exactMatches = menu.optionDescriptors.filter { descriptor ->
            UssdSignatureMatchPolicy.isExactLabelMatch(
                expectedLabel = normalizedExpected,
                currentLabel = descriptor.normalizedLabel
            )
        }
        return exactMatches.singleOrNull()?.let { descriptor ->
            MenuOptionMatch(
                optionKey = descriptor.key,
                optionLabel = descriptor.label,
                autoAdjustSafe = true
            )
        }
    }

    private fun titlesLookCompatible(
        learnedTitle: String,
        currentTitle: String
    ): Boolean {
        if (learnedTitle.isBlank() || currentTitle.isBlank()) return true
        return learnedTitle == currentTitle ||
            learnedTitle.contains(currentTitle) ||
            currentTitle.contains(learnedTitle)
    }

    private fun isMenuContextCompatible(menu: ParsedMenuSignature, learned: LearnedSignatureContext): Boolean {
        if (!titlesLookCompatible(
                learnedTitle = learned.normalizedMenuTitle,
                currentTitle = menu.normalizedTitle
            )
        ) {
            return false
        }

        val learnedSnapshot = learned.normalizedOptionSnapshot
        val currentSnapshot = menu.normalizedOptionLabels
        if (learnedSnapshot.isEmpty() || currentSnapshot.isEmpty()) return true

        val overlapCount = learnedSnapshot.intersect(currentSnapshot).size
        val requiredOverlap = when (minOf(learnedSnapshot.size, currentSnapshot.size)) {
            0 -> 0
            1, 2, 3 -> 1
            else -> 2
        }
        return overlapCount >= requiredOverlap
    }

    private fun isTransientResponseText(lower: String): Boolean {
        if (MENU_LIKE_REGEX.containsMatchIn(lower)) return false
        return TRANSIENT_RESPONSE_HINTS.any { hint -> lower.contains(hint) }
    }

    private fun hasMeaningfulResponseText(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        if (dialogText.isBlank()) return false
        val lines = normalizeDialogLines(snapshot.textTokens.ifEmpty { listOf(dialogText) })
        if (lines.isEmpty()) return false
        return lines.any { line ->
            val normalized = normalizeActionLabel(line)
            normalized.isNotBlank() &&
                normalized !in SEND_BUTTON_LABELS &&
                normalized !in DISMISS_BUTTON_LABELS &&
                normalized.length >= 3
        }
    }

    private fun shouldWaitForFinalResponse(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        val normalized = normalizeMenuText(dialogText)
        if (!hasMeaningfulResponseText(snapshot, dialogText)) return true
        if (isTransientResponseText(normalized)) return true
        return lastStepActionKey.isNotBlank() && normalized == lastStepActionKey
    }

    private fun extractTextTokens(node: AccessibilityNodeInfo, into: MutableList<String> = mutableListOf()): List<String> {
        // Iterative BFS with early exit - avoids recursion overhead and stack allocation
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        val visited = java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        
        while (queue.isNotEmpty() && into.size < 50) {
            val current = queue.removeFirst()
            if (visited.containsKey(current)) {
                if (current !== node) runCatching { current.recycle() }
                continue
            }
            visited[current] = true
            try {
                current.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
                current.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
                if (into.size < 50) {
                    for (i in 0 until current.childCount) {
                        val child = try { current.getChild(i) } catch (_: Exception) { null } ?: continue
                        queue.add(child)
                    }
                }
            } catch (_: Exception) {
                // A stale OEM node should not abort the rest of the snapshot.
            } finally {
                if (current !== node) runCatching { current.recycle() }
            }
        }
        while (queue.isNotEmpty()) {
            runCatching { queue.removeFirst().recycle() }
        }
        return into
    }

    // Fast single-pass text extraction for learning - uses event text when available
    internal fun extractDialogTextFromRoot(root: AccessibilityNodeInfo): String {
        val tokens = extractTextTokens(root)
        return tokens.distinct().joinToString(" ")
    }

    private fun writeValueToField(value: String): Boolean {
        val root = getUssdRoot() ?: return false
        return try {
            writeValueToField(root, value)
        } finally {
            root.recycle()
        }
    }

    private fun writeValueToField(root: AccessibilityNodeInfo, value: String): Boolean {
        return valueWriter.writeValueToField(root, value)
    }

    private fun writeValueToNode(field: AccessibilityNodeInfo, value: String): Boolean {
        return valueWriter.writeValueToNode(field, value)
    }

    private fun collectAggressiveTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isTextEntryNode(node) || isLooseInputCandidate(node)) {
                into += AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAggressiveTextEntryCandidates(child, into)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    internal fun rememberProgress() {
    }

    private fun tryWriteValueToField(field: AccessibilityNodeInfo, value: String): Boolean {
        return valueWriter.writeValueToNode(field, value)
    }

    private fun doubleConfirmThenSend(expected: String, attempt: Int) {
        if (!advancedActive) { isProcessing = false; return }
        var fieldText: String? = null
        var fieldRef : AccessibilityNodeInfo? = null
        var verified = false
        val root = getUssdRoot()
        if (root != null) {
            try {
                fieldRef  = findEditableField(root)
                if (fieldRef != null) {
                    fieldText = readFieldTextFast(fieldRef) ?: readFieldText(fieldRef)
                    verified = if (fieldText != null && isVerifiedFieldValue(fieldText, expected)) true
                        else verifyExpectedInputFromRoot(root, expected, fieldRef)
                }
            } finally { fieldRef?.recycle(); root.recycle() }
        }
        when {
            fieldText == null -> {
                // A previous write marker is not proof that the current popup still contains
                // the value. Reacquire the field and require an exact read-back before submit.
                handler.postDelayed(
                    { clickSendButton(expected, attempt + 1) },
                    UssdNavigationConfig.VERIFY_POLL_MS
                )
            }
            verified || isVerifiedFieldValue(fieldText, expected) -> {
                rememberVerifiedInput(expected)
                handler.post { clickSendButton(expected, 0, skipFieldVerification = true) }
            }
            else -> {
                // Do not issue another write after the current popup field has been touched.
                handler.postDelayed(
                    { clickSendButton(expected, attempt + 1) },
                    UssdNavigationConfig.VERIFY_POLL_MS
                )
            }
        }
    }

    private fun clickSendButton(expectedValue: String, attempt: Int, skipFieldVerification: Boolean = false) {
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        if (attempt >= UssdNavigationConfig.MAX_SEND_ATTEMPTS) {
            isProcessing = false; handler.post { dismissErrorAndRestart() }; return
        }
        
        val root = getUssdRoot() ?: run {
            // Let pendingAdvance kick handle retry
            schedulePendingAdvanceKick(delayMs = sendRetryDelay(attempt, expectedValue))
            return
        }
        
        var fieldRef: AccessibilityNodeInfo? = null
        var sendBtn: AccessibilityNodeInfo? = null
        
        try {
            if (!skipFieldVerification) {
                fieldRef = findEditableField(root)
                if (fieldRef != null) {
                    val fieldText = readFieldTextFast(fieldRef) ?: readFieldText(fieldRef)
                    // If field has wrong value, let pendingAdvance handle it (will trigger rewrite)
                    if (fieldText != null && !isVerifiedFieldValue(fieldText, expectedValue)) {
                        // Don't recursively poll - pendingAdvance will handle
                        return
                    }
                    if (fieldText != null && isVerifiedFieldValue(fieldText, expectedValue)) {
                        rememberVerifiedInput(expectedValue)
                    }
                }
            }
            
            // Adaptive Send uses the fastest previously confirmed method first. The
            // field is verified before either IME submission or a dialog button click.
            val verifiedForSend = fieldRef != null || skipFieldVerification
            if (verifiedForSend && tryAdaptiveSend(
                    root = root,
                    field = fieldRef,
                    expectedValue = expectedValue,
                    allowFieldLookup = true
                )) {
                val text = recentUssdSnapshot?.dialogText
                    ?: activePopupSnapshot?.dialogText
                    ?: extractAllText(root)
                markStepAction(text, stepIndex = currentStep)
                startPendingStepAdvance(root, text)
                return
            }
            
            // Single retry via pendingAdvance kick - no recursive polling
            if (!hasRecentExpectedInput(expectedValue) && attempt < 1) {
                writeValueToField(expectedValue)
            }
            
            // Schedule a single kick - pendingAdvance mechanism will drive retries via events
            schedulePendingAdvanceKick(delayMs = sendRetryDelay(attempt, expectedValue))
            
        } finally {
            fieldRef?.recycle()
            sendBtn?.recycle()
            root.recycle()
        }
    }

    private fun advanceStep() {
        currentStep++
        isProcessing   = false
        stepIdleAttempts = 0
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastStepAdvanceElapsed = SystemClock.elapsedRealtime()
        lastInputStepProcessed = -1
        stepRetryCount = 0
        waitingForNextDialog = false
        // Clear sent step tracking - new step will set it when sent
        lastSentStepIndex = -1
        lastSentStepKey = ""
        invalidateActiveSnapshot()
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        requestAppUiBehindPopup()
        updateRunningOverlay()
        startStepTimeout()
    }

    private fun finishLearningWithoutFinalSubmission() {
        val finalText = lastFinalResponse.ifBlank {
            "Signature learning captured without submitting the final step"
        }
        currentStep = advancedSteps.size
        isProcessing = false
        clearInputWriteMarker()
        closeCurrentUssdUi()
        onDispatchComplete?.invoke(buildDispatchResult(finalText))
        advancedInProgress = false
        updateRunningOverlay()
        cleanupAdvanced()
    }

    private fun dismissErrorAndRestart() {
        // Restarting the dial code after any accepted reply can repeat a purchase.
        // Stop for review instead of replaying a partly completed carrier transaction.
        if (lastSubmittedStepNumber > 0) {
            finishAmbiguousSubmittedStep("the carrier returned an error or the popup could not be safely continued")
            return
        }
        clearPendingStepAdvance()
        val dismissed = closeCurrentUssdUi()
        val delay = if (dismissed) UssdNavigationConfig.DIALOG_DISMISS_SETTLE_MS else 0L
        handler.postDelayed({ restartFromBeginning() }, delay)
    }

    private fun finishAmbiguousSubmittedStep(reason: String, possibleStepNumber: Int? = null) {
        if (!advancedActive) return
        // Carrier/network delay is not proof that the local Send method failed.
        discardUnconfirmedAdaptiveSend()
        val stepNumber = maxOf(lastSubmittedStepNumber, possibleStepNumber ?: 0).coerceAtLeast(1)
        val observedText = getUssdRoot()?.let { root ->
            try {
                normalizeCollapsedText(extractAllText(root)).take(240)
            } catch (_: Exception) {
                ""
            } finally {
                runCatching { root.recycle() }
            }
        }.orEmpty()
        val observedNote = observedText.takeIf { it.isNotBlank() }?.let {
            "\nLast visible carrier message: $it"
        }.orEmpty()
        val response = "USSD result needs verification. The app submitted or attempted step $stepNumber but could not confirm the next carrier response ($reason). No automatic resend or redial was made, to avoid a duplicate purchase. Check the carrier confirmation or balance before manually retrying.$observedNote"
        Log.w(TAG, "Stopping ambiguous USSD session after step $stepNumber: $reason")
        finishAdvancedDispatch(response, outcomeUncertain = true)
    }

    private fun dismissCurrentDialog(): Boolean {
        val root = getUssdRoot()
        if (root != null) {
            try {
                val btn = findActionButton(root, DISMISS_BUTTON_LABELS)
                if (btn != null) {
                    return try { performClick(btn) } finally { btn.recycle() }
                }
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun closeCurrentUssdUi(): Boolean {
        if (dismissCurrentDialog()) return true
        val root = getUssdRoot() ?: return false
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            val dialogText = normalizeCollapsedText(extractAllText(root))
            val lower = dialogText.lowercase()
            val looksLikeVisibleUssd = dialogText.isNotBlank() &&
                !NON_USSD_DIALOG_HINTS.any { lower.contains(it) } &&
                (
                    looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = pkg) ||
                        hasDialogLayout(root) ||
                        hasEditableField(root) ||
                        hasSendOrOkButton(root) ||
                        hasDismissButton(root)
                    )
            if (!looksLikeVisibleUssd) return false
            performGlobalAction(GLOBAL_ACTION_BACK)
        } finally {
            root.recycle()
        }
    }

    private fun endActiveUssdSession() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecom = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                telecom?.endCall()
            } else {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
                val method = tm?.javaClass?.getMethod("endCall")
                method?.invoke(tm)
            }
        }.onFailure { Log.w(TAG, "Unable to end active USSD session", it) }
    }

    private fun restartFromBeginning() {
        val now = SystemClock.elapsedRealtime()
        if (retryWindowStartedAt <= 0L) retryWindowStartedAt = now
        if (now - retryWindowStartedAt >= MAX_RETRY_WINDOW_MS) {
            val failMsg = if (lastFinalResponse.isNotBlank()) lastFinalResponse else "FAILED after 1 minute of retries"
            onDispatchComplete?.invoke(buildDispatchResult(failMsg))
            cleanupAdvanced()
            return
        }
        endActiveUssdSession()
        retryCount++
        currentStep    = 0
        isProcessing   = false
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastStepAdvanceElapsed = 0L
        lastInputStepProcessed = -1
        stepRetryCount = 0
        lastUiReturnElapsed = 0L
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        lastWindowId = -1
        lastWindowPkg = ""
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        pendingProcessToken = 0L
        clearInputWriteMarker()
        clearRecentUssdContext()
        requestAppUiBehindPopup(force = true)
        updateRunningOverlay()
        redialAdvancedIfNeeded()
        startStepTimeout()
    }

    private fun redialAdvancedIfNeeded() {
        val dialCode = advancedDialCode.trim()
        if (dialCode.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedialElapsed < UssdNavigationConfig.REDIAL_COOLDOWN_MS) return
        lastRedialElapsed = now
        runCatching {
            val subId = advancedSubId.takeIf { it != -1 }
            val slotIndex = advancedSimSlot.takeIf { it != -1 }
            val i = UssdHelper.buildCallIntent(this, dialCode, subId, slotIndex)
            startActivity(i)
            if (shouldKeepAppUiVisible()) UssdHelper.relaunchAppUi(this)
        }
    }

    private fun handleCallbackDialogs(lower: String, dialogText: String) {
        if (!hasPendingTokenPurchase()) return
        // Route through the shared failure-first verdict so a courtesy line such as "Thank you"
        // can never be mistaken for a completed purchase.
        val resolved = resolveTokenPurchase(pendingTokenPurchaseCode(), dialogText)
        if (resolved != null) {
            closeCurrentUssdUi()
            clearCallbacks()
        }
    }

    private fun startStepTimeout() {
        cancelStepTimeout()
        stepTimeoutJob = processingScope.launch {
            delay(currentStepTimeoutMs())
            if (shouldExtendStepTimeoutWindow()) {
                startStepTimeout()
                return@launch
            }
            val dismissed = closeCurrentUssdUi()
            val delay = if (dismissed) UssdNavigationConfig.DIALOG_DISMISS_SETTLE_MS else 0L
            handler.postDelayed({
                handleStepFailure(root = null)
            }, delay)
        }
    }

    private fun cancelStepTimeout() {
        stepTimeoutJob?.cancel()
        stepTimeoutJob = null
    }

    private fun currentStepTimeoutMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return when {
            pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank() -> UssdNavigationConfig.PENDING_STEP_TIMEOUT_MS
            currentStep >= advancedSteps.size -> UssdNavigationConfig.FINAL_RESPONSE_TIMEOUT_MS
            !hasSeenAdvancedPopup -> UssdNavigationConfig.STARTUP_STEP_TIMEOUT_MS
            hasRecentUssdUiEvent() -> UssdNavigationConfig.FINAL_RESPONSE_TIMEOUT_MS
            retryWindowStartedAt > 0L && (now - retryWindowStartedAt) <= UssdNavigationConfig.STARTUP_UI_KEEP_VISIBLE_MS -> UssdNavigationConfig.STARTUP_STEP_TIMEOUT_MS
            else -> UssdNavigationConfig.STEP_TIMEOUT_MS
        }
    }

    private fun shouldExtendStepTimeoutWindow(): Boolean {
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank()) return true
        if (!hasSeenAdvancedPopup) {
            return retryWindowStartedAt <= 0L ||
                (SystemClock.elapsedRealtime() - retryWindowStartedAt) <= UssdNavigationConfig.STARTUP_UI_KEEP_VISIBLE_MS
        }
        if (currentStep >= advancedSteps.size) {
            val normalizedFinal = normalizeMenuText(lastFinalResponse)
            return normalizedFinal.isBlank() ||
                isTransientResponseText(normalizedFinal) ||
                hasRecentUssdUiEvent()
        }
        return hasRecentUssdUiEvent()
    }

    private fun cleanupAdvanced() {
        UssdWakelock.release()
        stopKeepingAppUiVisible()
        cancelStepTimeout()
        processStepJob?.cancel()
        processStepJob = null
        handler.removeCallbacksAndMessages(null)
        currentStep         = 0
        advancedSteps       = emptyList()
        advancedPhoneNumber = ""
        advancedDialCode    = ""
        advancedOfferId     = -1
        advancedOfferName   = ""
        advancedSubId       = -1
        advancedSimSlot     = -1
        retryWindowStartedAt = 0L
        advancedActive      = false
        advancedInProgress  = false
        hideRunningOverlay()
        isProcessing        = false
        retryCount          = 0
        lastRedialElapsed   = 0L
        signatureGuardEnabled = false
        signatureAction     = "STOP"
        signatureLearningMode = false
        loadedSignatureSteps = emptyList()
        lastDialogText      = ""
        lastFinalResponse   = ""
        pendingProcessToken = 0L
        lastWindowPkg       = ""
        lastWindowId        = -1
        lastRelevantEventElapsed = 0L
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        loadedSignatureLookupSource = emptyList()
        loadedSignatureLookup = emptyMap()
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastStepAdvanceElapsed = 0L
        lastSubmittedStepNumber = 0
        lastInputStepProcessed = -1
        lastUiReturnElapsed = 0L
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        clearRecentUssdContext()
        onDispatchComplete  = null
        cancelTokenPurchase("advanced session cleaned up")
        resetSignatureTracking()
        configureUiReturn(true)
    }

    private fun clearPendingAdvanceKick() {
        pendingAdvanceKickJob?.cancel()
        pendingAdvanceKickJob = null
    }

    private fun pendingAdvanceKickDelay(expectedValue: String, phase: PendingPhase): Long {
        val baseDelay = when {
            phase == PendingPhase.WAIT_VERIFY &&
                hasRecentExpectedInput(expectedValue) &&
                hasRecentUssdUiEvent() -> 0L
            phase == PendingPhase.WAIT_SEND && hasRecentVerifiedInput(expectedValue) -> 0L
            hasSeenAdvancedPopup && hasRecentVerifiedInput(expectedValue) -> UssdNavigationConfig.PENDING_STEP_ADVANCE_KICK_MS
            hasRecentExpectedInput(expectedValue) && hasRecentUssdUiEvent() -> postWriteVerifyPollMs
            hasRecentExpectedInput(expectedValue) -> postWriteVerifyPollMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupVerifyMs
            hasRecentVerifiedInput(expectedValue) -> hotSendRetryDelayMs
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> pendingAdvanceKickMs
        }
        // Exponential backoff based on pending attempts to prevent hammering
        val backoffMultiplier = 1 + (pendingAttempts * 0.5)
        return (baseDelay * backoffMultiplier).toLong().coerceAtMost(UssdNavigationConfig.PENDING_ADVANCE_TIMEOUT_MS / 4)
    }

    /**
     * Retry only after the previous attempt could not safely verify or submit the current reply.
     * Counting these retries makes the existing backoff effective, preventing rapid repeated
     * accessibility-tree scans on slow/OEM dialers while keeping the first retry responsive.
     */
    private fun schedulePendingAdvanceRetry(
        expectedValue: String,
        phase: PendingPhase,
        minimumDelayMs: Long = 0L
    ) {
        if (!advancedActive || pendingPhase == PendingPhase.NONE) return
        // One bounded fallback is enough; timer-driven scans of an unchanged popup are wasteful.
        if (pendingAttempts >= 2) return
        pendingAttempts++
        val delayMs = maxOf(minimumDelayMs, pendingAdvanceKickDelay(expectedValue, phase))
        schedulePendingAdvanceKick(delayMs = delayMs)
    }

    private fun schedulePendingAdvanceKick(delayMs: Long = rootReacquireRetryDelayMs) {
        if (!advancedActive || pendingPhase == PendingPhase.NONE) return
        clearPendingAdvanceKick()
        pendingAdvanceKickJob = processingScope.launch {
            delay(delayMs)
            if (!advancedActive || pendingPhase == PendingPhase.NONE) return@launch
            attemptPendingAdvanceWithRoot(null)
        }
    }

    private fun clearPendingAdvance() {
        clearPendingAdvanceKick()
        pendingExpectedValue = null
        pendingAdvanceDialogText = ""
        pendingPhase = PendingPhase.NONE
        pendingSinceElapsed = 0L
        pendingAttempts = 0
    }

    private fun startPendingAdvance(expectedValue: String, dialogText: String) {
        pendingExpectedValue = expectedValue
        pendingAdvanceDialogText = dialogText
        pendingPhase = if (hasRecentVerifiedInput(expectedValue))
            PendingPhase.WAIT_SEND else PendingPhase.WAIT_VERIFY
        pendingSinceElapsed = SystemClock.elapsedRealtime()
        pendingAttempts = 0
        isProcessing = false
        // When already verified, send immediately (0-delay kick) instead of waiting on a timer.
        val kickDelayMs = if (pendingPhase == PendingPhase.WAIT_SEND && hasRecentVerifiedInput(expectedValue)) 0L
            else pendingAdvanceKickDelay(expectedValue, pendingPhase)
        // Safety kick in case OEM doesn't emit a useful event after ACTION_SET_TEXT.
        schedulePendingAdvanceKick(delayMs = kickDelayMs)
    }

    private fun attemptPendingAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) { clearPendingAdvance(); isProcessing = false; return }
        clearPendingAdvanceKick()
        val root = existingRoot ?: getUssdRoot() ?: run {
            if (pendingSinceElapsed > 0L &&
                SystemClock.elapsedRealtime() - pendingSinceElapsed > UssdNavigationConfig.PENDING_ADVANCE_TIMEOUT_MS
            ) {
                if (pendingPhase == PendingPhase.WAIT_SEND) {
                    finishAmbiguousSubmittedStep(
                        "the app could not confirm whether the reply was accepted",
                        possibleStepNumber = currentStep + 1
                    )
                } else {
                    clearPendingAdvance()
                    isProcessing = false
                    dismissErrorAndRestart()
                }
            } else {
                // No root yet. The next accessibility event will retry without a timer loop.
            }
            return
        }
        try {
            attemptPendingAdvance(root)
        } finally {
            if (existingRoot == null) runCatching { root.recycle() }
        }
    }

    private fun attemptPendingAdvance(root: AccessibilityNodeInfo) {
        val expected = pendingExpectedValue ?: run { clearPendingAdvance(); return }
        val elapsed = SystemClock.elapsedRealtime() - pendingSinceElapsed
        if (elapsed > UssdNavigationConfig.PENDING_ADVANCE_TIMEOUT_MS) {
            if (pendingPhase == PendingPhase.WAIT_SEND) {
                finishAmbiguousSubmittedStep(
                    "the app could not confirm whether the reply was accepted",
                    possibleStepNumber = currentStep + 1
                )
            } else {
                clearPendingAdvance()
                isProcessing = false
                if (stepRetryCount < UssdNavigationConfig.MAX_STEP_RETRIES) {
                    stepRetryCount++
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                } else {
                    dismissErrorAndRestart()
                }
            }
            return
        }

        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val interactionRoot = obtainInteractionRoot(root, requireStrictDialog = requireStrictPopupScope) ?: run {
            schedulePendingAdvanceRetry(expected, pendingPhase)
            isProcessing = false
            return
        }

        val currentSnapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
        val currentDialogTextRaw = currentSnapshot?.dialogText ?: normalizeCollapsedText(extractAllText(root))
        val currentDialogText = normalizeMenuText(currentDialogTextRaw)

        if (currentDialogText.isBlank() || isTransientResponseText(currentDialogText)) {
            // One bounded fallback only; subsequent progress comes from accessibility events.
            schedulePendingAdvanceRetry(expected, pendingPhase)
            isProcessing = false
            interactionRoot.recycle()
            return
        }

        val pendingDialogText = normalizeMenuText(pendingAdvanceDialogText)
        if (currentDialogText != pendingDialogText) {
            // A changed flattened dialog can be caused by an EditText being filled or cleared.
            // Until Send is positively accepted, that is never evidence of the next carrier prompt.
            val field = findEditableField(interactionRoot)
            val fieldText = field?.let { readFieldTextFast(it) ?: readFieldText(it) }
            val stillVerified = fieldText != null && isVerifiedFieldValue(fieldText, expected)
            field?.recycle()

            if (stillVerified) {
                // Same carrier prompt and exact value still present: continue to Send now.
                pendingAdvanceDialogText = currentDialogTextRaw
            } else {
                if (pendingPhase == PendingPhase.WAIT_SEND) {
                    // The field cleared before Send was accepted. Never advance to another step.
                    pendingPhase = PendingPhase.WAIT_VERIFY
                }
                schedulePendingAdvanceRetry(expected, pendingPhase)
                isProcessing = false
                interactionRoot.recycle()
                return
            }
        }

        try {
            when (pendingPhase) {
                PendingPhase.WAIT_VERIFY -> {
                    val field = findEditableField(interactionRoot)
                    val verified = try {
                        if (field != null) {
                            verifyExpectedInputFromRoot(
                                root = root,
                                expectedValue = expected,
                                existingField = field
                            )
                        } else {
                            false
                        }
                    } finally {
                        runCatching { field?.recycle() }
                    }

                    if (verified) {
                        pendingPhase = PendingPhase.WAIT_SEND
                        attemptPendingAdvance(root)
                        return
                    }

                    schedulePendingAdvanceRetry(expected, pendingPhase)
                    isProcessing = false
                }

                PendingPhase.WAIT_SEND -> {
                    // interactionRoot is the validated current popup root. It is already
                    // the same window as root; retrying against root immediately repeats the
                    // full verification and Send tree scans without giving the dialer new UI state.
                    val pendingSendStartedAt = UssdPerformanceDiagnostics.mark()
                    val sent = tryImmediateVerifiedSend(
                        interactionRoot,
                        field = null,
                        expectedValue = expected
                    )
                    UssdPerformanceDiagnostics.record(
                        "send.pending",
                        pendingSendStartedAt,
                        "step=$currentStep phase=WAIT_SEND outcome=${if (sent) "accepted" else "retry_scheduled"}"
                    )
                    if (sent) {
                        clearPendingAdvance()
                        val text = currentDialogTextRaw.ifBlank {
                            currentSnapshot?.dialogText.orEmpty()
                        }
                        markStepAction(text, stepIndex = currentStep)
                        startPendingStepAdvance(interactionRoot, text)
                    } else {
                        // Let the next event re-try, but avoid expensive work here.
                        schedulePendingAdvanceRetry(expected, pendingPhase)
                        isProcessing = false
                    }
                }

                PendingPhase.NONE -> Unit
            }
        } finally {
            interactionRoot.recycle()
        }
    }

    private fun clearCallbacks() {
        lastDialogText      = ""
        cancelTokenPurchase("callbacks cleared")
        balanceCallback     = null
        onDispatchComplete  = null
    }

    private fun shouldKeepAppUiVisible(): Boolean =
        keepAppUiVisibleEnabled &&
            !uiReturnSuppressed &&
            ((advancedActive && advancedInProgress) || isForegroundUiActive())

    private fun hasSeenUssdPopup(): Boolean =
        hasSeenAdvancedPopup || hasSeenForegroundPopup

    private fun shouldShowRunningOverlay(): Boolean =
        SHOW_RUNNING_OVERLAY && ((advancedInProgress || (advancedActive && advancedSteps.isNotEmpty())) || isForegroundUiActive())

    private fun requestAppUiBehindPopup(force: Boolean = false) {
        if (!shouldKeepAppUiVisible()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUiReturnElapsed < 900L) return
        lastUiReturnElapsed = now
        UssdHelper.relaunchAppUi(this, delayMs = if (force) 60L else 120L)
    }

    private fun startKeepingAppUiVisible() {
        uiKeepVisibleJob?.cancel()
        uiKeepVisibleJob = processingScope.launch {
            while (isActive) {
                val foregroundExpired = foregroundUiActive && !isForegroundUiActive()
                if (foregroundExpired) {
                    disarmForegroundUi()
                    hasSeenForegroundPopup = false
                    updateRunningOverlay()
                }
                val startupWindowActive = advancedActive &&
                    !hasSeenUssdPopup() &&
                    (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= UssdNavigationConfig.STARTUP_UI_KEEP_VISIBLE_MS
                val canKeepVisible = shouldKeepAppUiVisible() &&
                    (startupWindowActive || (hasSeenUssdPopup() && hasRecentUssdUiEvent()))
                if (!canKeepVisible) {
                    uiKeepVisibleJob = null
                    return@launch
                }
                requestAppUiBehindPopup(force = true)
                delay(UssdNavigationConfig.UI_KEEP_VISIBLE_INTERVAL_MS)
            }
        }
    }

    private fun stopKeepingAppUiVisible() {
        uiKeepVisibleJob?.cancel()
        uiKeepVisibleJob = null
    }

    private fun buildRunningOverlayView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val paddingH = dp(14)
            val paddingV = dp(10)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E61A1A1A"))
                setStroke(dp(1), Color.parseColor("#3329B6F6"))
            }
            elevation = dp(8).toFloat()
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val detail = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD7E3F4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.05f)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            alpha = 0.9f
        }

        container.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            detail,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        container.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dp(6)
            }
        )

        runningOverlayView = container
        runningOverlayStatusText = status
        runningOverlayDetailText = detail
        return container
    }

    private fun buildRunningOverlayStatusText(): String = when {
        retryCount > 0 -> "Bingwa Mobile USSD retrying"
        !hasSeenAdvancedPopup -> "Bingwa Mobile opening USSD"
        currentStep >= advancedSteps.size -> "Bingwa Mobile finishing USSD"
        else -> "Bingwa Mobile USSD running"
    }

    private fun buildRunningOverlayDetailText(): String {
        val detailParts = mutableListOf<String>()
        advancedOfferName.takeIf { it.isNotBlank() }?.let { detailParts += it }
        when {
            advancedSteps.isEmpty() -> detailParts += "Waiting for the network menu"
            currentStep >= advancedSteps.size -> detailParts += "Finalizing network response"
            advancedSteps.getOrNull(currentStep) == "INPUT_PHONE" -> {
                val progress = "Step ${currentStep + 1} of ${advancedSteps.size}"
                detailParts += "$progress, entering phone number"
            }
            else -> detailParts += "Step ${currentStep + 1} of ${advancedSteps.size}"
        }
        if (retryCount > 0) {
            detailParts += "Retry $retryCount within 1-minute window"
        }
        return detailParts.joinToString("  |  ")
            .ifBlank { "USSD session is still active while Bingwa stays visible here" }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun clearPendingStepAdvance() {
        pendingStepAdvanceFromKey = ""
        pendingStepAdvanceDialogKey = ""
        pendingStepAdvanceMenuKey = ""
        pendingStepAdvanceSinceElapsed = 0L
        pendingStepAdvanceTimeoutJob?.cancel()
        pendingStepAdvanceTimeoutJob = null
        pendingStepAdvanceKickJob?.cancel()
        pendingStepAdvanceKickJob = null
        pendingStepAdvanceWindowId = -1
        invalidateActiveSnapshot()
    }

    private fun shouldWaitForRootRecovery(): Boolean {
        if (!advancedActive) return false
        return hasSeenAdvancedPopup ||
            pendingPhase != PendingPhase.NONE ||
            pendingStepAdvanceFromKey.isNotBlank() ||
            hasRecentUssdUiEvent() ||
            (!hasSeenAdvancedPopup && advancedSteps.isNotEmpty() && currentStep in advancedSteps.indices)
    }

    private fun waitForRootRecovery() {
        val now = SystemClock.elapsedRealtime()
        if (waitingForRootSinceElapsed == 0L) {
            waitingForRootSinceElapsed = now
            UssdPerformanceDiagnostics.record(
                "root_recovery.wait_started",
                now,
                "timeoutMs=${UssdNavigationConfig.ROOT_REACQUIRE_TIMEOUT_MS}"
            )
        }

        // Do not repeatedly traverse AccessibilityNodeInfo roots while Android/SystemUI
        // is temporarily between USSD windows. onAccessibilityEvent() already wakes the
        // processor when the next relevant window/dialog event arrives.
        if (rootRecoveryTimeoutRunnable == null) {
            val timeout = object : Runnable {
                override fun run() {
                    if (rootRecoveryTimeoutRunnable !== this) return
                    rootRecoveryTimeoutRunnable = null
                    if (waitingForRootSinceElapsed == 0L || !advancedActive || isProcessing || processStepJob != null || pendingPhase != PendingPhase.NONE) {
                        clearRootRecoveryState()
                        return
                    }

                    val elapsed = SystemClock.elapsedRealtime() - waitingForRootSinceElapsed
                    UssdPerformanceDiagnostics.record(
                        "root_recovery.timeout",
                        waitingForRootSinceElapsed,
                        "elapsedMs=$elapsed"
                    )
                    clearRootRecoveryState()
                    dismissErrorAndRestart()
                }
            }
            rootRecoveryTimeoutRunnable = timeout
            handler.postDelayed(timeout, UssdNavigationConfig.ROOT_REACQUIRE_TIMEOUT_MS)
        }
    }

    private fun clearRootRecoveryState() {
        val startedAt = waitingForRootSinceElapsed
        if (startedAt != 0L) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            UssdPerformanceDiagnostics.record(
                "root_recovery.recovered",
                startedAt,
                "elapsedMs=$elapsed"
            )
        }
        rootRecoveryTimeoutRunnable?.let(handler::removeCallbacks)
        rootRecoveryTimeoutRunnable = null
        waitingForRootSinceElapsed = 0L
    }

    private fun rememberRecentUssdContext(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        windowId: Int,
        windowPkg: String,
        dialogText: String,
        strictDialog: Boolean
    ) {
        recentUssdRoot?.let { existing ->
            if (existing !== root) {
                runCatching { existing.recycle() }
            }
        }
        recentUssdRoot = AccessibilityNodeInfo.obtain(root)
        recentUssdSnapshot = snapshot
        recentUssdWindowId = windowId
        recentUssdWindowPkg = windowPkg
        recentUssdDialogText = dialogText
        recentUssdStrictDialog = strictDialog
        recentUssdCapturedElapsed = SystemClock.elapsedRealtime()
    }

    private fun hasFreshRecentUssdContext(requireStrictDialog: Boolean = false): Boolean {
        if (recentUssdCapturedElapsed <= 0L) return false
        if (SystemClock.elapsedRealtime() - recentUssdCapturedElapsed > UssdNavigationConfig.RECENT_USSD_CONTEXT_WINDOW_MS) return false
        if (requireStrictDialog && !recentUssdStrictDialog) return false
        return recentUssdRoot != null
    }

    private fun obtainRecentUssdContext(requireStrictDialog: Boolean = false): RecentUssdContext? {
        if (!hasFreshRecentUssdContext(requireStrictDialog = requireStrictDialog)) return null
        val root = recentUssdRoot ?: return null
        return RecentUssdContext(
            root = AccessibilityNodeInfo.obtain(root),
            snapshot = recentUssdSnapshot,
            windowId = recentUssdWindowId,
            windowPkg = recentUssdWindowPkg,
            dialogText = recentUssdDialogText,
            strictDialog = recentUssdStrictDialog
        )
    }

    private fun clearRecentUssdContext() {
        recentUssdRoot?.let { runCatching { it.recycle() } }
        recentUssdRoot = null
        recentUssdSnapshot = null
        recentUssdWindowId = -1
        recentUssdWindowPkg = ""
        recentUssdDialogText = ""
        recentUssdStrictDialog = false
        recentUssdCapturedElapsed = 0L
        clearValidatedUssdWindow()
    }

    private fun startPendingStepAdvance(root: AccessibilityNodeInfo, dialogText: String) {
        clearPendingStepAdvance()
        pendingStepAdvanceSinceElapsed = SystemClock.elapsedRealtime()
        pendingStepAdvanceWindowId = lastWindowId
        val snapshot = recentUssdSnapshot ?: getOrCaptureActiveSnapshot(root)
        // Prompt identity deliberately excludes EditText contents. Clearing or filling a field
        // must not look like a new carrier step.
        pendingStepAdvanceDialogKey = normalizeMenuText(snapshot.promptText)
        pendingStepAdvanceMenuKey = buildMenuTransitionKey(snapshot)
        pendingStepAdvanceFromKey = buildTransitionSignatureKey(
            windowId = pendingStepAdvanceWindowId,
            windowPkg = root.packageName?.toString().orEmpty(),
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        // Immediately invalidate recent snapshot so subsequent step evaluation never reuses the submitted step
        recentUssdSnapshot = null
        invalidateActiveSnapshot()
        // Track which step was sent and the dialog signature
        lastSentStepIndex = currentStep
        lastSentStepKey = pendingStepAdvanceDialogKey
        lastSubmittedStepNumber = maxOf(lastSubmittedStepNumber, currentStep + 1)
        pendingStepAdvanceTimeoutJob = processingScope.launch {
            delay(UssdNavigationConfig.PENDING_STEP_ADVANCE_TIMEOUT_MS)
            if (pendingStepAdvanceFromKey.isBlank()) return@launch
            finishAmbiguousSubmittedStep("the next popup did not arrive before the safety timeout")
        }
        schedulePendingStepAdvanceKick()
        isProcessing = false
    }

    private fun schedulePendingStepAdvanceKick(delayMs: Long = UssdNavigationConfig.PENDING_STEP_ADVANCE_KICK_MS) {
        if (!advancedActive || pendingStepAdvanceFromKey.isBlank()) return
        pendingStepAdvanceKickJob?.cancel()
        pendingStepAdvanceKickJob = processingScope.launch {
            delay(delayMs)
            if (!advancedActive || pendingStepAdvanceFromKey.isBlank()) return@launch
            attemptPendingStepAdvanceWithRoot(null)
        }
    }

    private fun attemptPendingStepAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) {
            clearPendingStepAdvance()
            isProcessing = false
            return
        }
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) {
            isProcessing = false
            return
        }
        pendingStepAdvanceKickJob?.cancel()
        pendingStepAdvanceKickJob = null

        val elapsed = SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed
        if (elapsed > UssdNavigationConfig.PENDING_STEP_ADVANCE_TIMEOUT_MS) {
            finishAmbiguousSubmittedStep("the next popup did not arrive before the safety timeout")
            return
        }

        val root = existingRoot ?: getUssdRoot() ?: run {
            isProcessing = false
            return
        }
        try {
            // Proxy for whether a new window has appeared since we sent the previous step.
            val windowChanged = pendingStepAdvanceWindowId != lastWindowId
            val requireStrictPopupScope = shouldRequireStrictPopupScope()
            val snapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
            val dialogText = snapshot?.dialogText
                ?: normalizeCollapsedText(extractAllText(root))
            if (dialogText.isBlank() || (dialogText.length > 500 && !USSD_HINTS.any { dialogText.lowercase().contains(it) })) {
                isProcessing = false
                return
            }
            val currentKey = buildTransitionSignatureKey(
                windowId = pendingStepAdvanceWindowId,
                windowPkg = root.packageName?.toString().orEmpty(),
                root = root,
                snapshot = snapshot,
                dialogText = dialogText
            )
            val promptKey = normalizeMenuText(snapshot?.promptText.orEmpty())
            if (!hasMeaningfulPendingStepTransition(promptKey, snapshot)) {
                // The next accessibility event will re-check the prompt; do not poll the same tree.
                isProcessing = false
                return
            }
            // First reject unchanged prompts/menu signatures using the cheap cached
            // snapshot data. The field-value guard requires additional Binder-backed
            // node queries and is only needed when a real prompt transition is plausible.
            if (currentKey == fromKey) {
                isProcessing = false
                return
            }
            if (!shouldAdvanceToNextStep(
                    windowChanged = windowChanged,
                    snapshot = snapshot,
                    pendingDialogKey = normalizeMenuText(pendingStepAdvanceDialogKey),
                    currentDialogKey = promptKey
                )
            ) {
                isProcessing = false
                return
            }
            val sameStepGuardStartedAt = UssdPerformanceDiagnostics.mark()
            val sameStepFieldUpdate = isSameStepFieldUpdate(root, snapshot)
            UssdPerformanceDiagnostics.record(
                "field.same_step_guard",
                sameStepGuardStartedAt,
                "checked=true sameStep=$sameStepFieldUpdate"
            )
            if (sameStepFieldUpdate) {
                isProcessing = false
                return
            }
            confirmAdaptiveSendProgress()
            clearPendingStepAdvance()
            advanceStep()
            pendingProcessToken = SystemClock.elapsedRealtime()
            scheduleProcessStep(dialogChanged = true)
        } finally {
            if (existingRoot == null) runCatching { root.recycle() }
        }
    }

    private fun handlePendingStepAdvance(
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String,
        windowChanged: Boolean = false
    ): Boolean {
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) return false
        // Large non-USSD background screens should never count as the next carrier response
        if (dialogText.length > 500 && !USSD_HINTS.any { dialogText.lowercase().contains(it) }) return false
        val elapsed = SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed
        if (elapsed > UssdNavigationConfig.PENDING_STEP_ADVANCE_TIMEOUT_MS) {
            finishAmbiguousSubmittedStep("the next popup did not arrive before the safety timeout")
            return true
        }
        // Accessibility events are already the synchronization signal. Validate the prompt
        // immediately rather than imposing a fixed post-send sleep.
        val currentKey = buildTransitionSignatureKey(
            windowId = windowId,
            windowPkg = windowPkg,
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        val promptKey = normalizeMenuText(snapshot?.promptText.orEmpty())
        if (!hasMeaningfulPendingStepTransition(promptKey, snapshot)) return true
        // Avoid searching the editable node for every content-change event. If the
        // prompt/menu signature has not changed, shouldAdvanceToNextStep rejects it
        // without needing to inspect the input field.
        if (currentKey == fromKey) return true
        if (!shouldAdvanceToNextStep(
                windowChanged = windowChanged,
                snapshot = snapshot,
                pendingDialogKey = pendingStepAdvanceDialogKey,
                currentDialogKey = promptKey
            )
        ) return true
        val sameStepGuardStartedAt = UssdPerformanceDiagnostics.mark()
        val sameStepFieldUpdate = isSameStepFieldUpdate(root, snapshot)
        UssdPerformanceDiagnostics.record(
            "field.same_step_guard",
            sameStepGuardStartedAt,
            "checked=true sameStep=$sameStepFieldUpdate"
        )
        if (sameStepFieldUpdate) return true
        confirmAdaptiveSendProgress()
        clearPendingStepAdvance()
        advanceStep()
        pendingProcessToken = SystemClock.elapsedRealtime()
        scheduleProcessStep(dialogChanged = true)
        return true
    }

    private inline fun safeFind(root: AccessibilityNodeInfo, finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        try { finder(root) } catch (_: Exception) { null }

    internal fun isPotentialUssdPackage(pkg: String): Boolean = Companion.isPotentialUssdPackage(pkg)

    private fun hasDialogLayout(node: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot? = null): Boolean {
        val pkg = node.packageName?.toString().orEmpty()
        if (pkg == "com.android.systemui") {
            return hasSystemUiDialogStructure(node)
        }
        val className = node.className?.toString().orEmpty()
        return className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true) ||
            className.contains("DecorView", ignoreCase = true) ||
            className.contains("FrameLayout", ignoreCase = true) ||
            className.contains("LinearLayout", ignoreCase = true) ||
            className.contains("RelativeLayout", ignoreCase = true) ||
            className.contains("ViewGroup", ignoreCase = true) ||
            (node.childCount in 1..25 && (
                snapshot?.hasSendButton == true ||
                    snapshot?.hasDismissButton == true ||
                    snapshot?.hasEditableField == true ||
                    (snapshot == null && (hasSendOrOkButton(node) || hasDismissButton(node) || hasEditableField(node)))
                ))
    }

    private fun hasEditableField(node: AccessibilityNodeInfo): Boolean {
        if (isTextEntryNode(node)) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasEditableField(c); c.recycle()
            if (found) return true
        }
        return false
    }

    private fun isSameStepFieldUpdate(root: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot?): Boolean {
        if (snapshot?.hasEditableField != true) return false
        val expected = advancedSteps.getOrNull(currentStep)?.let { step ->
            if (step == "INPUT_PHONE") advancedPhoneNumber else step
        } ?: return false

        // The normal popup snapshot walk has already read text and resource IDs for
        // every node. Reuse that value for the known field instead of issuing another
        // findFocus()/findAccessibilityNodeInfosByViewId() Binder round-trip.
        if (!cachedInputFieldId.isNullOrBlank()) {
            snapshot.cachedEditableFieldText?.let { cachedText ->
                return isVerifiedFieldValue(cachedText, expected)
            }
        }

        // Compatibility fallback when the cached field is not represented in the
        // snapshot or the OEM hides its text. Reuse the ranked popup-tree scan first;
        // it is much cheaper than issuing findFocus and then a second field lookup.
        val field = findEditableField(root) ?: return false
        val fieldText = try {
            readFieldTextFast(field) ?: readFieldText(field)
        } finally {
            runCatching { field.recycle() }
        }
        return fieldText != null && isVerifiedFieldValue(fieldText, expected)
    }

    /**
     * Strict "NO POP-UP = NO INPUT" guard. Decides whether a detected change represents a
     * genuinely new USSD popup that justifies advancing to the next step.
     *
     * - Popups that expose an editable field are handled by the caller's same-step field
     *   verification, so we return true and let that logic decide.
     * - Menu / dialog popups without an editable field only advance when a genuinely new popup
     *   appears: a new window, or content that is substantially different (not merely a
     *   substring tweak of the previous popup). This prevents injecting the next value while
     *   the same popup is still showing (e.g. transient "loading" or minor text updates).
     * - STRICT: Must have completed the previous step (lastSentStepIndex match) and new popup
     *   must be genuinely different from the one we sent to.
     */
    private fun shouldAdvanceToNextStep(
        windowChanged: Boolean,
        snapshot: UssdTreeSnapshot?,
        pendingDialogKey: String,
        currentDialogKey: String
    ): Boolean {
        // An error response is almost always a re-prompt of the SAME step we just tried to send.
        // Advancing here would inject the next step's value into the wrong popup, so hold back and
        // let the existing retry/timeout handling decide what to do.
        if (currentDialogKey.isNotBlank() &&
            errorKeywords.any { currentDialogKey.contains(it) }
        ) {
            return false
        }
        // STRICT: Must have a valid last sent step to advance from
        if (lastSentStepIndex < 0 || lastSentStepIndex != currentStep) {
            Log.d(TAG, "shouldAdvanceToNextStep: BLOCKED - no valid last sent step (lastSentStepIndex=$lastSentStepIndex, currentStep=$currentStep)")
            return false
        }
        // Transient/loading responses should never trigger advancement
        if (currentDialogKey.isNotBlank() &&
            TRANSIENT_RESPONSE_HINTS.any { currentDialogKey.contains(it) }
        ) {
            return false
        }
        // Prompt signatures exclude EditText values. A blank or identical prompt is never
        // enough to advance, even if the input field was cleared or Android created a new window.
        if (pendingDialogKey.isBlank() || currentDialogKey.isBlank()) return false
        if (pendingDialogKey == currentDialogKey) return false

        val previousMenuKey = pendingStepAdvanceMenuKey
        val currentMenuKey = buildMenuTransitionKey(snapshot)
        val menuChanged = previousMenuKey.isNotBlank() &&
            currentMenuKey.isNotBlank() &&
            previousMenuKey != currentMenuKey

        val similarity = calculateDialogSimilarity(pendingDialogKey, currentDialogKey)
        if (similarity > 0.85 && !menuChanged) {
            Log.d(TAG, "shouldAdvanceToNextStep: blocked likely same carrier prompt (similarity=$similarity)")
            return false
        }

        // Changed carrier content is required. A new window ID is supporting evidence, not a
        // substitute for a new prompt.
        return menuChanged || similarity <= 0.85
    }

    private fun hasSendOrOkButton(node: AccessibilityNodeInfo): Boolean {
        val t = normalizeActionLabel(node.text?.toString())
        val d = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        if (t in SEND_BUTTON_LABELS || d in SEND_BUTTON_LABELS || SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasSendOrOkButton(c); c.recycle()
            if (found) return true
        }
        return false
    }

    private fun hasDismissButton(node: AccessibilityNodeInfo): Boolean {
        val t = normalizeActionLabel(node.text?.toString())
        val d = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        if (t in DISMISS_BUTTON_LABELS || d in DISMISS_BUTTON_LABELS || DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasDismissButton(c); c.recycle()
            if (found) return true
        }
        return false
    }

    /**
     * The input-focused node is the cheapest and most reliable field hint on Android.
     * Carrier dialogs commonly focus their EditText as soon as the popup appears, so
     * use that signal before walking the entire accessibility subtree.
     */
    private fun findFocusedEditableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val startedAt = UssdPerformanceDiagnostics.mark()
        val focused = runCatching {
            node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull() ?: run {
            UssdPerformanceDiagnostics.record("field.focused_lookup", startedAt, "found=false")
            return null
        }

        try {
            val sameWindow = runCatching { focused.windowId == node.windowId }.getOrDefault(false)
            val editable = runCatching { focused.isEditable }.getOrDefault(false) ||
                runCatching { isTextEntryNode(focused) }.getOrDefault(false)
            val visible = runCatching { focused.isVisibleToUser }.getOrDefault(true)
            if (!sameWindow || !editable || !visible) {
                UssdPerformanceDiagnostics.record("field.focused_lookup", startedAt, "found=false")
                return null
            }

            runCatching { focused.viewIdResourceName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { cachedInputFieldId = it }

            UssdPerformanceDiagnostics.record("field.focused_lookup", startedAt, "found=true")
            return AccessibilityNodeInfo.obtain(focused)
        } finally {
            runCatching { focused.recycle() }
        }
    }

    /**
     * Query the cached resource ID once and recycle every returned node. This avoids
     * a full subtree walk and prevents unused AccessibilityNodeInfo instances leaking.
     */
    private fun findCachedEditableField(root: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val matches = runCatching { root.findAccessibilityNodeInfosByViewId(viewId) }.getOrNull() ?: return null
        try {
            val candidate = matches.firstOrNull {
                runCatching { (it.isEditable || isTextEntryNode(it)) && it.isVisibleToUser }.getOrDefault(false)
            } ?: return null
            return AccessibilityNodeInfo.obtain(candidate)
        } finally {
            matches.forEach { runCatching { it.recycle() } }
        }
    }

    private fun findCommonUssdInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val startedAt = UssdPerformanceDiagnostics.mark()
        val rootPkg = root.packageName?.toString().orEmpty()
        val targetIds = UssdNavigationConfig.COMMON_USSD_INPUT_VIEW_IDS.filter { id ->
            id.startsWith("android:") || (rootPkg.isNotBlank() && id.startsWith(rootPkg))
        }
        for (viewId in targetIds) {
            val candidate = findCachedEditableField(root, viewId)
            if (candidate != null) {
                cachedInputFieldId = viewId
                UssdPerformanceDiagnostics.record("field.common_id_lookup", startedAt, "found=true id=$viewId")
                return candidate
            }
        }
        UssdPerformanceDiagnostics.record("field.common_id_lookup", startedAt, "found=false")
        return null
    }

    private fun findEditableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val discoveryStartedAt = UssdPerformanceDiagnostics.mark()
        // Prefer the already-acquired, bounded popup tree. On the target Infinix
        // reports this scan is consistently a few milliseconds, while the cached-ID
        // and findFocus Binder queries can each block for tens of milliseconds.
        findBestEditableField(node) { scoreTextEntryCandidate(it) }?.let {
            UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=tree_scan")
            return it
        }
        // Retain the cached-ID fast fallback for OEM trees whose accessibility scan
        // does not expose a candidate.
        cachedInputFieldId?.let { id ->
            findCachedEditableField(node, id)?.let {
                UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=cached_id id=$id")
                return it
            }
        }
        findFocusedEditableField(node)?.let {
            UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=focused")
            return it
        }
        findCommonUssdInputField(node)?.let {
            UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=common_id id=$cachedInputFieldId")
            return it
        }
        return null
    }

    private fun findEditableFieldForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String,
        allowTreeScan: Boolean = true
    ): AccessibilityNodeInfo? {
        val discoveryStartedAt = UssdPerformanceDiagnostics.mark()
        // When the already-captured popup snapshot confirms an editable field, scan
        // that small, validated dialog tree first. On the target Infinix reports this
        // takes about 1-4ms, while findAccessibilityNodeInfosByViewId can take tens
        // of milliseconds even when the cached ID is correct.
        if (allowTreeScan) {
            findBestEditableField(node) { scoreTextEntryCandidateForStep(it, step, dialogText) }?.let {
                UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=tree_scan")
                return it
            }
        }

        // Keep the cached resource-ID lookup as the first fallback when the popup
        // snapshot could not expose an editable node, or when the ranked scan found
        // no valid candidate. It is still useful for OEM trees with hidden fields.
        cachedInputFieldId?.let { id ->
            findCachedEditableField(node, id)?.let { cached ->
                val score = runCatching { scoreTextEntryCandidateForStep(cached, step, dialogText) }
                    .getOrDefault(Int.MIN_VALUE)
                if (score > 0) {
                    UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=cached_id score=$score")
                    return cached
                }
                runCatching { cached.recycle() }
            }
        }

        // Fallbacks remain available for OEM trees whose editable node is not exposed
        // by the bounded snapshot scan or whose field attaches after the snapshot.
        findFocusedEditableField(node)?.let { focused ->
            val score = runCatching { scoreTextEntryCandidateForStep(focused, step, dialogText) }
                .getOrDefault(Int.MIN_VALUE)
            if (score > 0) {
                UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=focused score=$score")
                return focused
            }
            runCatching { focused.recycle() }
        }
        findCommonUssdInputField(node)?.let {
            UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=common_id id=$cachedInputFieldId")
            return it
        }
        UssdPerformanceDiagnostics.record("field.discovery", discoveryStartedAt, "path=none found=false")
        return null
    }

    /**
     * Collect independent handles for text-entry candidates.
     *
     * Some verification paths need to inspect every candidate rather than only
     * the highest-scoring one. Keep this helper for those exhaustive checks;
     * the normal field-selection path uses the streaming single-winner scan.
     */
    private fun collectTextEntryCandidates(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>
    ) {
        try {
            if (isTextEntryNode(node) || isLooseInputCandidate(node)) {
                runCatching { AccessibilityNodeInfo.obtain(node) }
                    .getOrNull()
                    ?.let { into += it }
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            val boundedCount = minOf(childCount, 16)
            for (index in 0 until boundedCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: break
                try {
                    collectTextEntryCandidates(child, into)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        } catch (_: Exception) {
            // A popup can disappear while its accessibility tree is being queried.
            // Keep candidates already collected; callers own and recycle their handles.
        }
    }

    private fun findBestEditableField(
        node: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int
    ): AccessibilityNodeInfo? {
        // Score candidates as they are visited and retain only the current winner.
        // The previous implementation copied every candidate into a list, then made
        // a second pass to score them, keeping many Binder-backed node handles alive.
        val searchStartedAt = UssdPerformanceDiagnostics.mark()
        var visitedNodes = 0
        var candidateNodes = 0
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        fun visit(current: AccessibilityNodeInfo) {
            if (bestScore >= 800) return
            visitedNodes++
            if (isTextEntryNode(current) || isLooseInputCandidate(current)) {
                candidateNodes++
                val score = runCatching { scorer(current) }.getOrDefault(Int.MIN_VALUE)
                // Strictly greater preserves the first candidate on ties, matching
                // maxByOrNull's stable first-winner behaviour.
                if (score > bestScore) {
                    val replacement = runCatching {
                        AccessibilityNodeInfo.obtain(current)
                    }.getOrNull()
                    if (replacement != null) {
                        bestNode?.let { runCatching { it.recycle() } }
                        bestNode = replacement
                        bestScore = score
                        if (bestScore >= 800) return
                    }
                }
            }

            val childCount = runCatching { current.childCount }.getOrDefault(0)
            for (index in 0 until minOf(childCount, 12)) {
                if (bestScore >= 800) break
                val child = runCatching { current.getChild(index) }.getOrNull() ?: break
                try {
                    visit(child)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }

        visit(node)
        val winner = bestNode
        UssdPerformanceDiagnostics.record(
            "field.tree_scan",
            searchStartedAt,
            "nodes=$visitedNodes candidates=$candidateNodes found=${winner != null} score=$bestScore"
        )
        winner ?: return null
        runCatching { winner.viewIdResourceName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { cachedInputFieldId = it }
        return winner
    }

    private fun scoreTextEntryCandidate(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        var score = 0
        if (try { node.isEditable } catch (_: Exception) { false }) score += 500
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 320
        if (EDITABLE_CLASS_HINTS.any { className.equals(it, ignoreCase = true) || className.contains(it, ignoreCase = true) }) score += 300
        if (className.contains("EditText", ignoreCase = true)) score += 240
        if (className.contains("Text", ignoreCase = true)) score += 90
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 180
        if (INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) || hint.contains(it) }) score += 120
        if (isLooseInputCandidate(node)) score += 110
        if (try { node.isFocused } catch (_: Exception) { false }) score += 90
        if (try { node.isFocusable } catch (_: Exception) { false }) score += 70
        if (try { node.isClickable } catch (_: Exception) { false }) score += 40
        // Candidate ranking only needs the field's own value. readFieldText() can
        // recursively walk nested descendants for OEM wrappers; doing that for every
        // candidate multiplies tree traversal cost. Keep deep extraction for final verify.
        val currentValue = readFieldTextFast(node)?.trim().orEmpty()
        when {
            currentValue.isBlank() -> score += 120
            isLikelyPromptText(currentValue) -> score -= 180
            currentValue.length <= 24 -> score += 30
            else -> score -= 45
        }
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score -= 280
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 280
        if (bounds.right > 0 || bounds.bottom > 0) {
            score += bounds.bottom / 24
            score += bounds.right / 36
        }
        return score
    }

    private fun scoreTextEntryCandidateForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String
    ): Int {
        var score = scoreTextEntryCandidate(node)
        if (!isPhoneOrStringStep(step)) return score

        val lowerDialog = dialogText.lowercase()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val combined = listOf(viewId, label, desc, hint).joinToString(" ")

        if (dialogSuggestsPhoneInput(lowerDialog) || dialogSuggestsTextInput(lowerDialog)) {
            score += 260
        }
        if (PHONE_INPUT_HINTS.any { combined.contains(it) }) score += 260
        if (combined.contains("phone") || combined.contains("mobile") || combined.contains("msisdn") || combined.contains("simu") || combined.contains("namba")) score += 180
        if (combined.contains("recipient") || combined.contains("customer") || combined.contains("subscriber") || combined.contains("mteja")) score += 140
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 90
        return score
    }

    private fun findButtonExact(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (t == normalizeActionLabel(text) || d == normalizeActionLabel(text))
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonExact(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    internal fun findButtonContaining(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val normalizedText = normalizeActionLabel(text)
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (t.contains(normalizedText) || d.contains(normalizedText))
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonContaining(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun findButtonStartingWith(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val normalizedText = normalizeActionLabel(text)
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (t.startsWith(normalizedText) || d.startsWith(normalizedText))
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonStartingWith(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun findButtonByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        try {
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (d == normalizeActionLabel(desc)) return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonByDescription(child, desc); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String =
        StringBuilder().also { appendAllText(node, it) }.toString()

    private fun appendAllText(node: AccessibilityNodeInfo, into: StringBuilder) {
        try {
            node.text?.let { into.append(it).append(' ') }
            node.contentDescription?.let { into.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    appendAllText(child, into)
                } finally {
                    child.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    private class TreeScanAccumulator {
        val textTokens = mutableListOf<String>()
        val promptTokens = mutableListOf<String>()
        var hasEditableField = false
        var cachedEditableFieldText: String? = null
        var hasSendButton = false
        var hasDismissButton = false
        var nodesVisited = 0
        var maxDepthReached = 0
        var childEnumerationNanos = 0L
        var childGetNanos = 0L
        var nullChildCount = 0
        var textExtractionNanos = 0L
        var nodeInspectionNanos = 0L
        var recycleNanos = 0L
    }

    private fun scanNodeSummary(node: AccessibilityNodeInfo): TreeScanAccumulator =
        TreeScanAccumulator().also { collectTreeSnapshot(node, it) }

    private fun captureTreeSnapshot(root: AccessibilityNodeInfo): UssdTreeSnapshot {
        val accumulator = TreeScanAccumulator()
        val traversalStartedAt = UssdPerformanceDiagnostics.mark()
        collectTreeSnapshot(root, accumulator, depth = 0)

        val constructionStartedAt = UssdPerformanceDiagnostics.mark()
        val dialogText = normalizeCollapsedText(accumulator.textTokens.joinToString(" "))
        val promptText = normalizeCollapsedText(accumulator.promptTokens.joinToString(" "))
        val snapshot = UssdTreeSnapshot(
            dialogText = dialogText,
            normalizedDialogText = dialogText,
            promptText = promptText,
            textTokens = accumulator.textTokens.toList(),
            hasEditableField = accumulator.hasEditableField,
            cachedEditableFieldText = accumulator.cachedEditableFieldText,
            hasSendButton = accumulator.hasSendButton,
            hasDismissButton = accumulator.hasDismissButton
        )

        UssdPerformanceDiagnostics.record(
            "snapshot.traversal",
            traversalStartedAt,
            "nodes=${accumulator.nodesVisited} maxDepth=${accumulator.maxDepthReached} nulls=${accumulator.nullChildCount}"
        )
        UssdPerformanceDiagnostics.record(
            "snapshot.child_enumeration",
            startedAtNanos = null,
            detail = "durationMs=" + String.format(Locale.US, "%.2f", accumulator.childEnumerationNanos / 1_000_000.0) +
                " getChildMs=" + String.format(Locale.US, "%.2f", accumulator.childGetNanos / 1_000_000.0) +
                " nullCount=${accumulator.nullChildCount}"
        )
        UssdPerformanceDiagnostics.record(
            "snapshot.text_extraction",
            startedAtNanos = null,
            detail = "durationMs=" + String.format(Locale.US, "%.2f", accumulator.textExtractionNanos / 1_000_000.0)
        )
        UssdPerformanceDiagnostics.record(
            "snapshot.construction",
            constructionStartedAt,
            "tokens=${snapshot.textTokens.size} chars=${snapshot.dialogText.length} recycleMs=" +
                String.format(Locale.US, "%.2f", accumulator.recycleNanos / 1_000_000.0)
        )
        return snapshot
    }

    private fun captureTreeSnapshotStrictDialog(root: AccessibilityNodeInfo): UssdTreeSnapshot? {
        val snapshot = captureTreeSnapshot(root)
        if (snapshot.dialogText.isBlank()) return null
        return snapshot
    }

    private fun findDialogCaptureRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return AccessibilityNodeInfo.obtain(root)
    }

    private fun collectDialogCaptureCandidates(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        into: MutableList<DialogCaptureCandidate>,
        depth: Int
    ) {
        // USSD dialogs are shallow in Android's accessibility hierarchy. Bounding
        // candidate depth prevents repeated subtree scans from becoming O(n²) on
        // notification-heavy screens during signature learning.
        if (depth > 5) return
        val score = scoreDialogCaptureCandidate(node, rootBounds, depth)
        if (score > 0) {
            into += DialogCaptureCandidate(
                node = AccessibilityNodeInfo.obtain(node),
                score = score
            )
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                collectDialogCaptureCandidates(child, rootBounds, into, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun scoreDialogCaptureCandidate(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        depth: Int
    ): Int {
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        if (childCount == 0) return 0

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val nodeArea = bounds.width().toLong() * bounds.height().toLong()
        val rootArea = rootBounds.width().toLong() * rootBounds.height().toLong()
        val areaRatio = if (nodeArea > 0L && rootArea > 0L) nodeArea.toFloat() / rootArea.toFloat() else 1f
        if (nodeArea <= 0L || areaRatio < 0.03f) return 0

        // Fast rejection: candidate must be a layout container or dialog class
        val className = node.className?.toString().orEmpty()
        val isContainer = className.endsWith("Layout") || className.endsWith("ViewGroup") ||
            className.contains("Dialog", ignoreCase = true) ||
            className.contains("Panel", ignoreCase = true) ||
            className.contains("DecorView", ignoreCase = true) ||
            className.contains("FrameLayout", ignoreCase = true)
        if (!isContainer && childCount <= 1) return 0

        val summary = scanNodeSummary(node)
        val textTokens = summary.textTokens
            .map(::normalizeCollapsedText)
            .filter { it.isNotBlank() }
            .distinct()
        if (textTokens.isEmpty()) return 0

        val combinedText = textTokens.joinToString(" ").lowercase()
        if (NON_USSD_DIALOG_HINTS.any { combinedText.contains(it) }) return 0

        val hasAction = summary.hasSendButton || summary.hasDismissButton || summary.hasEditableField
        val structuredMenu = extractStructuredMenuBlock(textTokens)
        val hasMenuOptions = structuredMenu != null
        val structuredMenuOptionCount = structuredMenu?.options?.size ?: 0
        val hasDialogClass = className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true)
        val compactContainer = childCount in 1..8
        val moderatelyCompactContainer = childCount in 1..14
        val hasUssdLanguage = USSD_HINTS.any { combinedText.contains(it) } || errorKeywords.any { combinedText.contains(it) }
        if (!hasAction && !hasMenuOptions) return 0
        if (!(hasUssdLanguage || hasMenuOptions || signatureLearningMode || advancedActive || isForegroundUiActive())) return 0

        val horizontalInset = maxOf(0, bounds.left - rootBounds.left) + maxOf(0, rootBounds.right - bounds.right)
        val verticalInset = maxOf(0, bounds.top - rootBounds.top) + maxOf(0, rootBounds.bottom - bounds.bottom)
        val hasInset = horizontalInset > 0 || verticalInset > 0
        val centerDx = kotlin.math.abs(bounds.centerX() - rootBounds.centerX())
        val centerDy = kotlin.math.abs(bounds.centerY() - rootBounds.centerY())
        val centered = centerDx <= (rootBounds.width() * 0.18f) && centerDy <= (rootBounds.height() * 0.18f)
        val fullScreenDialogLike = areaRatio > 0.96f &&
            (hasDialogClass || (centered && childCount <= 10 && (hasAction || hasMenuOptions)))
        if (areaRatio > 0.995f && !fullScreenDialogLike) return 0

        var score = 0
        if (summary.hasEditableField) score += 380
        if (summary.hasSendButton) score += 260
        if (summary.hasDismissButton) score += 120
        if (hasMenuOptions) score += 260 + (structuredMenuOptionCount * 45)
        if (hasUssdLanguage) score += 220
        if (hasDialogClass) score += 180
        if (compactContainer) score += 140
        else if (moderatelyCompactContainer) score += 70
        else score -= minOf(childCount, 32) * 18
        if (areaRatio in 0.08f..0.86f) score += 180
        else if (fullScreenDialogLike) score += 110
        else if (areaRatio > 0.96f) score -= 220
        if (hasInset) score += 80
        if (centered) score += 70
        score += maxOf(0, 42 - (depth * 4))
        score -= maxOf(0, textTokens.size - 12) * 10
        if (hasMenuOptions) {
            val blockLineCount = structuredMenuOptionCount + (structuredMenu?.titleLines?.size ?: 0)
            score -= maxOf(0, textTokens.size - blockLineCount - 4) * 18
        }
        return score.takeIf { it > 0 } ?: 0
    }

    private fun collectTreeSnapshot(node: AccessibilityNodeInfo, accumulator: TreeScanAccumulator, depth: Int = 0) {
        if (depth > 8 || accumulator.nodesVisited >= 35) return
        accumulator.nodesVisited++
        if (depth > accumulator.maxDepthReached) accumulator.maxDepthReached = depth
        try {
            val nodeInspectStart = SystemClock.elapsedRealtimeNanos()
            val className = runCatching { node.className?.toString() }.getOrNull().orEmpty()
            val viewId = runCatching { node.viewIdResourceName?.lowercase() }.getOrNull().orEmpty()

            // Filter out system UI notifications, status bar, heads-up notifications, shade, etc.
            // Never record text from non-dialog notification subtrees and skip their children entirely.
            if (UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_VIEW_ID_HINTS.any { viewId.contains(it) } ||
                UssdNavigationConfig.SYSTEM_UI_NON_DIALOG_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }
            ) {
                return
            }

            val directText = runCatching { node.text?.toString()?.trim() }.getOrNull()
            val hasDirectText = !directText.isNullOrBlank()
            val isLayoutContainer = !hasDirectText && (className.endsWith("Layout") || className.endsWith("ViewGroup") || className.endsWith("ScrollView") || className.endsWith("ViewStub"))
            val isEditable = if (isLayoutContainer) false else runCatching { node.isEditable }.getOrDefault(false)

            if (!isLayoutContainer) {
                // Input field detection
                val isInputNode = isEditable || isTextEntryNodeFast(node, className, viewId, isEditable)
                // Query hintText only for editable/input fields over Binder IPC
                val hintText = if (isInputNode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { node.hintText?.toString()?.trim() }.getOrNull()
                } else null
                // Query contentDescription only if directText is absent or buttons are still needed
                val contentDesc = if (directText.isNullOrBlank() || isInputNode || (!accumulator.hasSendButton && !accumulator.hasDismissButton)) {
                    runCatching { node.contentDescription?.toString()?.trim() }.getOrNull()
                } else null

                if (isInputNode) {
                    accumulator.hasEditableField = true
                    // directText and viewId were already fetched for this node above.
                    // Capture only the previously validated field; do not log its value.
                    val cachedId = cachedInputFieldId
                    // Preserve an explicitly empty value as a real snapshot result.
                    // A null value still means the OEM did not expose the field text,
                    // in which case the conservative Binder-backed fallback is needed.
                    if (cachedId != null &&
                        viewId.equals(cachedId, ignoreCase = true) &&
                        directText != null
                    ) {
                        accumulator.cachedEditableFieldText = directText
                    }
                    if (!hintText.isNullOrBlank()) {
                        accumulator.textTokens += hintText
                        accumulator.promptTokens += hintText
                    }
                } else {
                    if (!directText.isNullOrBlank()) {
                        accumulator.textTokens += directText
                        accumulator.promptTokens += directText
                    }
                    if (!contentDesc.isNullOrBlank() && contentDesc != directText) {
                        accumulator.textTokens += contentDesc
                        accumulator.promptTokens += contentDesc
                    }
                    if (!hintText.isNullOrBlank() && hintText != directText && hintText != contentDesc) {
                        accumulator.textTokens += hintText
                        accumulator.promptTokens += hintText
                    }
                }

                // Quick button checks: check text/desc/viewId labels without extra IPC
                if (!accumulator.hasSendButton && isSendActionNodeFast(directText, contentDesc, viewId)) {
                    accumulator.hasSendButton = true
                }
                if (!accumulator.hasDismissButton && isDismissActionNodeFast(directText, contentDesc, viewId)) {
                    accumulator.hasDismissButton = true
                }
            }

            val elapsedInspect = SystemClock.elapsedRealtimeNanos() - nodeInspectStart
            accumulator.textExtractionNanos += elapsedInspect
            accumulator.nodeInspectionNanos += elapsedInspect

            val childCountStart = SystemClock.elapsedRealtimeNanos()
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            val count = minOf(childCount, 12)
            accumulator.childEnumerationNanos += (SystemClock.elapsedRealtimeNanos() - childCountStart)

            for (i in 0 until count) {
                if (accumulator.nodesVisited >= 35) break
                // Stop exploring non-dialog subtrees once all essential interactive dialog elements are found
                if (accumulator.hasEditableField && accumulator.hasSendButton && accumulator.hasDismissButton && accumulator.promptTokens.isNotEmpty() && depth >= 3) {
                    break
                }
                val childStart = SystemClock.elapsedRealtimeNanos()
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                val childElapsed = SystemClock.elapsedRealtimeNanos() - childStart
                accumulator.childGetNanos += childElapsed
                accumulator.childEnumerationNanos += childElapsed

                if (child == null) {
                    accumulator.nullChildCount++
                    // In Android AccessibilityNodeInfo, children are indexed contiguously.
                    // If a child is null, the parent or window is detaching. Breaking immediately
                    // avoids accumulating 3x ~24ms Binder IPC timeouts (69-74ms delay).
                    break
                }
                try {
                    collectTreeSnapshot(child, accumulator, depth + 1)
                } finally {
                    val recStart = SystemClock.elapsedRealtimeNanos()
                    runCatching { child.recycle() }
                    accumulator.recycleNanos += (SystemClock.elapsedRealtimeNanos() - recStart)
                }
            }
        } catch (_: Exception) {}
    }

    private fun isSendActionNodeFast(text: String?, desc: String?, viewId: String): Boolean {
        val normText = normalizeActionLabel(text)
        if (normText in SEND_BUTTON_LABELS) return true
        val normDesc = normalizeActionLabel(desc)
        if (normDesc in SEND_BUTTON_LABELS) return true
        if (viewId.isNotBlank() && SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        return false
    }

    private fun isDismissActionNodeFast(text: String?, desc: String?, viewId: String): Boolean {
        val normText = normalizeActionLabel(text)
        if (normText in DISMISS_BUTTON_LABELS) return true
        val normDesc = normalizeActionLabel(desc)
        if (normDesc in DISMISS_BUTTON_LABELS) return true
        if (viewId.isNotBlank() && DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        return false
    }

    private fun isTextEntryNodeFast(
        node: AccessibilityNodeInfo,
        className: String,
        viewId: String,
        isEditable: Boolean
    ): Boolean {
        if (isEditable) return true
        if (EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) return true
        if (className.contains("TextInput", ignoreCase = true)) return true
        if (className.contains("Button", ignoreCase = true)) return false
        if (viewId.isNotBlank() && INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) {
            return runCatching {
                node.isFocusable || ((node.actions and AccessibilityNodeInfo.ACTION_SET_TEXT) != 0)
            }.getOrDefault(false)
        }
        return false
    }

    private fun findActionButton(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        labels.forEach { label ->
            safeFind(root) { findButtonExact(it, label) }?.let { return it }
        }
        labels.forEach { label ->
            safeFind(root) { findButtonStartingWith(it, label) }?.let { return it }
        }
        labels.filter { it.length > 2 }.forEach { label ->
            safeFind(root) { findButtonContaining(it, label) }?.let { return it }
        }
        labels.forEach { label ->
            safeFind(root) { findButtonByDescription(it, label) }?.let { return it }
        }
        return null
    }

    private fun locateMenuButton(
        root: AccessibilityNodeInfo,
        valueToEnter: String,
        selectedMenuLabel: String?
    ): AccessibilityNodeInfo? =
        safeFind(root) { findButtonExact(it, valueToEnter) }
            ?: safeFind(root) { findButtonContaining(it, valueToEnter) }
            ?: safeFind(root) { findButtonStartingWith(it, valueToEnter) }
            ?: selectedMenuLabel?.let { label ->
                safeFind(root) { findButtonExact(it, label) }
                    ?: safeFind(root) { findButtonContaining(it, label) }
                    ?: safeFind(root) { findButtonStartingWith(it, label) }
            }

    internal fun findBestSendActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        val best = candidates
            .asSequence()
            .map { node -> node to scoreSendActionCandidate(node) }
            .maxByOrNull { it.second }
            ?.let { (node, score) -> if (score > 0) node else null }
            ?.let { AccessibilityNodeInfo.obtain(it) }
        candidates.forEach { it.recycle() }
        return best
    }

    private fun findActionButtonByViewIdHints(
        root: AccessibilityNodeInfo,
        hints: List<String>,
        blockedHints: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filter { node ->
                val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
                viewId.isNotBlank() &&
                    hints.any { viewId.contains(it) } &&
                    blockedHints.none { viewId.contains(it) }
            }
            .maxByOrNull { scoreActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    internal fun findPositiveDialogButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filterNot { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
            }
            .maxByOrNull { scoreActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun findSendButtonByLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filter { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label.isNotBlank() &&
                    (SEND_BUTTON_LABELS.any { label.contains(it, ignoreCase = true) } ||
                        desc.isNotBlank() && desc.contains("send", ignoreCase = true))
            }
            .maxByOrNull { scoreActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    internal fun collectActionCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        // SIM chooser/action discovery must remain bounded: some OEM dialer roots expose
        // very large or rapidly changing trees. Keep independent node handles for callers,
        // but cap depth, siblings and total Binder-backed nodes visited.
        var visitedNodes = 0
        fun visit(current: AccessibilityNodeInfo, depth: Int) {
            if (depth > 8 || visitedNodes >= 160) return
            visitedNodes++
            try {
                if (isActionCandidate(current)) {
                    runCatching { AccessibilityNodeInfo.obtain(current) }
                        .getOrNull()
                        ?.let { into += it }
                }
                val childCount = runCatching { current.childCount }.getOrDefault(0)
                for (index in 0 until minOf(childCount, 16)) {
                    if (visitedNodes >= 160) break
                    val child = runCatching { current.getChild(index) }.getOrNull() ?: break
                    try {
                        visit(child, depth + 1)
                    } finally {
                        runCatching { child.recycle() }
                    }
                }
            } catch (_: Exception) {
                // A dialer window can disappear during traversal; retain valid candidates.
            }
        }
        visit(node, 0)
    }

    private fun isActionCandidate(node: AccessibilityNodeInfo): Boolean {
        val actionable = try { node.isClickable || node.isFocusable } catch (_: Exception) { false }
        if (!actionable) return false
        val editable = try { node.isEditable } catch (_: Exception) { false }
        if (editable) return false
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        if (!enabled) return false
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        if (!visible) return false

        val className = node.className?.toString().orEmpty()
        val isButtonLike = className.contains("Button", ignoreCase = true) ||
            className.contains("TextView", ignoreCase = true) ||
            className.contains("ImageView", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("View", ignoreCase = true)
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val hasActionHints = SEND_VIEW_ID_HINTS.any { viewId.contains(it) } ||
            DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
        val hasUsefulLabel = label.isNotBlank() || desc.isNotBlank()
        return isButtonLike || hasActionHints || hasUsefulLabel
    }

    private fun scoreActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        val shortText = (label.ifBlank { desc }).length

        var score = 0
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 500
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 300
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 220
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 260
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 180
        if (label.isNotBlank() || desc.isNotBlank()) score += 80
        if (childCount == 0) score += 70
        if (childCount > 2) score -= childCount * 40
        if (shortText in 1..18) score += 60
        if (shortText > 28) score -= 180
        if (bounds.right > 0 || bounds.bottom > 0) {
            score += bounds.right / 10
            score += bounds.bottom / 20
        }
        return score
    }

    private fun scoreSendActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        var score = scoreActionCandidate(node)
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 420
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 240
        if (SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) }) score += 140
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 260
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 420
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 280
        return score
    }

    internal fun findBottomRightActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filterNot { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
            }
            .maxByOrNull { scoreActionCandidate(it) + 120 }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun triggerInputSubmit(root: AccessibilityNodeInfo, expectedValue: String): Boolean =
        triggerInputSubmit(root, expectedValue, null)

    internal fun submitInputFromRoot(root: AccessibilityNodeInfo, expectedValue: String): Boolean =
        triggerInputSubmit(root, expectedValue, null)

    private fun triggerInputSubmit(
        root: AccessibilityNodeInfo,
        expectedValue: String,
        existingField: AccessibilityNodeInfo?
    ): Boolean {
        val field = existingField ?: findEditableFieldMatchingExpectedInput(root, expectedValue) ?: return false
        return try {
            val currentValue = (readFieldTextFast(field) ?: readFieldText(field))?.trim().orEmpty()
            if (!isVerifiedFieldValue(currentValue, expectedValue)) return false
            rememberVerifiedInput(expectedValue)
            performImeAction(field)
        } finally {
            if (existingField == null) field.recycle()
        }
    }

    /**
     * Use the fastest method previously confirmed for this popup signature.
     * Cold-start preference is IME because it avoids Send-button discovery.
     * Fall back only when an action is not accepted; never submit twice blindly.
     */
    private fun tryAdaptiveSend(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String,
        allowFieldLookup: Boolean
    ): Boolean {
        val contextKey = buildString {
            append(root.packageName?.toString().orEmpty())
            append('|')
            append(root.className?.toString().orEmpty())
            append('|')
            append(field?.className?.toString().orEmpty())
            append('|')
            append(runCatching { field?.viewIdResourceName }.getOrNull().orEmpty())
            // Reuse the prompt already captured for this transaction; no extra tree walk.
            append("|prompt=")
            append(normalizeMenuText(recentUssdSnapshot?.promptText.orEmpty()).take(180))
        }
        val preferred = adaptiveSendMemory.preferredMethod(contextKey)

        fun attemptIme(): Boolean {
            val startedAt = UssdPerformanceDiagnostics.mark()
            if (tryImeEnterSubmit(field)) {
                markAdaptiveSendAccepted("ime", startedAt, contextKey)
                return true
            }
            if (!allowFieldLookup) return false
            val fallbackStartedAt = UssdPerformanceDiagnostics.mark()
            if (triggerInputSubmit(root, expectedValue, field)) {
                markAdaptiveSendAccepted("ime", fallbackStartedAt, contextKey)
                return true
            }
            return false
        }

        fun attemptButton(): Boolean {
            var candidate = field
            var ownedCandidate: AccessibilityNodeInfo? = null
            if (candidate == null && allowFieldLookup) {
                ownedCandidate = findEditableFieldMatchingExpectedInput(root, expectedValue)
                candidate = ownedCandidate
            }
            try {
                val activeField = candidate ?: return false
                val isFresh = hasFreshlyVerifiedInput(expectedValue)
                if (!isFresh) {
                    val currentValue = (readFieldTextFast(activeField) ?: readFieldText(activeField))?.trim().orEmpty()
                    if (!isVerifiedFieldValue(currentValue, expectedValue)) return false
                }
                val startedAt = UssdPerformanceDiagnostics.mark()
                if (!sendClick.validateAndClickSend(root, activeField, expectedValue, preferred)) return false
                markAdaptiveSendAccepted(sendClick.lastSuccessfulPath.ifBlank { "resource_id" }, startedAt, contextKey)
                return true
            } finally {
                ownedCandidate?.recycle()
            }
        }

        return if (preferred == "ime") attemptIme() || attemptButton()
        else attemptButton() || attemptIme()
    }

    private fun markAdaptiveSendAccepted(method: String, startedAtNanos: Long, contextKey: String) {
        pendingAdaptiveSendMethod = method
        pendingAdaptiveSendContext = contextKey
        pendingAdaptiveSendDurationUs =
            ((SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(0L) / 1_000L)
        // Keep IME and button submissions on the same carrier-wait timing path.
        UssdPerformanceDiagnostics.markSubmissionAccepted(method)
        UssdPerformanceDiagnostics.record("send.accepted", startedAtNanos, "method=$method")
        UssdPerformanceDiagnostics.record("send.method_accepted", startedAtNanos, "method=$method")
        if (stepProcessingStartedAtNanos > 0L) {
            UssdPerformanceDiagnostics.record(
                "total.local_processing",
                stepProcessingStartedAtNanos,
                "step=$currentStep method=$method"
            )
            stepProcessingStartedAtNanos = 0L
        }
    }

    private fun confirmAdaptiveSendProgress() {
        val method = pendingAdaptiveSendMethod
        if (method.isBlank()) return
        adaptiveSendMemory.recordConfirmed(method, pendingAdaptiveSendDurationUs, pendingAdaptiveSendContext)
        UssdPerformanceDiagnostics.record(
            "send.learning_confirmed",
            detail = "method=$method local_us=$pendingAdaptiveSendDurationUs"
        )
        pendingAdaptiveSendMethod = ""
        pendingAdaptiveSendDurationUs = 0L
        pendingAdaptiveSendContext = ""
    }

    private fun discardUnconfirmedAdaptiveSend() {
        if (pendingAdaptiveSendMethod.isNotBlank()) {
            UssdPerformanceDiagnostics.record("send.learning_unconfirmed", detail="method=$pendingAdaptiveSendMethod")
        }
        pendingAdaptiveSendMethod = ""
        pendingAdaptiveSendDurationUs = 0L
        pendingAdaptiveSendContext = ""
    }

    private fun tryImmediateVerifiedSend(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String
    ): Boolean {
        // Fast-path: if the field was verified within the last 800ms of the current step,
        // bypass redundant Binder read-back before handing off to adaptive send.
        val verified = if (field != null && hasFreshlyVerifiedInput(expectedValue)) {
            true
        } else {
            val fieldText = field?.let { readFieldTextFast(it) ?: readFieldText(it) }
            when {
                fieldText != null -> isVerifiedFieldValue(fieldText, expectedValue).also {
                    if (it) rememberVerifiedInput(expectedValue)
                }
                else -> verifyExpectedInputFromRoot(
                    root = root,
                    expectedValue = expectedValue,
                    existingField = field
                )
            }
        }
        if (!verified) return false

        return tryAdaptiveSend(
            root = root,
            field = field,
            expectedValue = expectedValue,
            // The adaptive sender must reacquire and verify a current field when
            // this call did not receive a readable field handle.
            allowFieldLookup = true
        )
    }

    private fun isImeEnterUsable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            node.isEditable || node.isFocusable || supportsDirectInput(node)
        } catch (_: Exception) { false }
    }

    private fun tryImeEnterSubmit(field: AccessibilityNodeInfo?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val f = field ?: return false
        val expected = lastInputWriteValue
        val isFreshlyVerified = hasFreshlyVerifiedInput(expected)
        if (!isFreshlyVerified) {
            val fieldText = (readFieldTextFast(f) ?: readFieldText(f))?.trim().orEmpty()
            if (expected.isBlank() || !isVerifiedFieldValue(fieldText, expected)) return false
            rememberVerifiedInput(expected)
        }
        if (!isImeEnterUsable(f)) return false
        return try {
            val imeEnterId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            // Fast direct attempt without querying full actionList over Binder IPC.
            // Modern Android dialers execute the action directly. If unsupported or false, fall back to focus.
            if (runCatching { f.performAction(imeEnterId) }.getOrDefault(false)) {
                return true
            }
            focusInputTarget(f)
            val advertised = runCatching { f.actionList?.any { it.id == imeEnterId } == true }
                .getOrDefault(false)
            if (advertised) {
                runCatching { f.performAction(imeEnterId) }.getOrDefault(false)
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun performImeAction(node: AccessibilityNodeInfo): Boolean {
        val targets = obtainInputTargets(node)
        try {
            targets.forEach { target ->
                focusInputTarget(target)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    val advertised = runCatching { target.actionList?.any { it.id == imeEnter } == true }
                        .getOrDefault(false)
                    if (advertised && runCatching { target.performAction(imeEnter) }.getOrDefault(false)) {
                        return true
                    }
                }
                // NEXT and SEARCH can merely move focus or trigger an unrelated action.
                val actionMatch = try {
                    target.actionList?.firstOrNull { action ->
                        val label = normalizeActionLabel(action.label?.toString())
                        label !in listOf("next", "suivant", "search") &&
                            (label in SEND_BUTTON_LABELS || label in listOf("go", "done", "enter", "submit"))
                    }?.id
                } catch (_: Exception) {
                    null
                }
                if (actionMatch != null && runCatching { target.performAction(actionMatch) }.getOrDefault(false)) {
                    return true
                }
            }
            return false
        } finally {
            targets.forEach { it.recycle() }
        }
    }

    internal fun normalizeActionLabel(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase()
            .replace(MULTI_SPACE_REGEX, " ")

    private fun isSendActionNode(node: AccessibilityNodeInfo): Boolean {
        val text = normalizeActionLabel(node.text?.toString())
        if (text in SEND_BUTTON_LABELS) return true
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        if (desc in SEND_BUTTON_LABELS) return true
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return SEND_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun isDismissActionNode(node: AccessibilityNodeInfo): Boolean {
        val text = normalizeActionLabel(node.text?.toString())
        if (text in DISMISS_BUTTON_LABELS) return true
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        if (desc in DISMISS_BUTTON_LABELS) return true
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun shouldTreatStepAsTextInput(step: String, valueToEnter: String, selectedMenuLabel: String?): Boolean {
        if (isPhoneOrStringStep(step)) return true
        if (isPhoneOrStringStep(valueToEnter)) return true
        if (valueToEnter.isBlank()) return false
        if (!valueToEnter.all(Char::isDigit)) return true
        if (valueToEnter.length >= 3) return true
        return selectedMenuLabel == null && valueToEnter.length > 1
    }

    private fun dialogSuggestsTextInput(allTextLower: String): Boolean =
        allTextLower.contains("enter") ||
            allTextLower.contains("input") ||
            allTextLower.contains("reply") ||
            allTextLower.contains("amount") ||
            allTextLower.contains("pin") ||
            allTextLower.contains("phone") ||
            allTextLower.contains("number") ||
            allTextLower.contains("code") ||
            allTextLower.contains("weka") ||
            allTextLower.contains("ingiza") ||
            allTextLower.contains("jaza") ||
            allTextLower.contains("kiasi") ||
            allTextLower.contains("namba") ||
            allTextLower.contains("nambari") ||
            allTextLower.contains("simu") ||
            allTextLower.contains("recipient") ||
            allTextLower.contains("tuma")

    private fun dialogSuggestsPhoneInput(allTextLower: String): Boolean =
        PHONE_INPUT_HINTS.any { allTextLower.contains(it) } ||
            allTextLower.contains("weka namba") ||
            allTextLower.contains("weka nambari") ||
            allTextLower.contains("ingiza namba") ||
            allTextLower.contains("nambari ya simu") ||
            allTextLower.contains("namba ya simu") ||
            allTextLower.contains("anayepokea") ||
            allTextLower.contains("recipient") ||
            (allTextLower.contains("254") && (allTextLower.contains("phone") || allTextLower.contains("mobile") || allTextLower.contains("simu"))) ||
            (allTextLower.contains("07") && (allTextLower.contains("phone") || allTextLower.contains("number") || allTextLower.contains("simu"))) ||
            (allTextLower.contains("01") && (allTextLower.contains("phone") || allTextLower.contains("number") || allTextLower.contains("simu")))

    private fun hasInputViewHint(viewId: String, hint: String): Boolean =
        INPUT_VIEW_ID_HINTS.any { viewId.contains(it) } ||
            INPUT_FIELD_HINTS.any { hint.contains(it) }

    private fun hasInputLabelHint(label: String, desc: String): Boolean =
        INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) }

    private fun isTextEntryNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val isLayout = className.endsWith("Layout") || className.endsWith("ViewGroup") || className.endsWith("ScrollView")
        val editable = try { node.isEditable } catch (_: Exception) { false }
        if (isLayout && !editable) return false
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        if (looksLikeButton) return false

        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val sendOrDismissLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        if (sendOrDismissLabel) return false

        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        if (!enabled) return false

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val focusable = try { node.isFocusable || node.isFocused } catch (_: Exception) { false }
        val clickable = try { node.isClickable } catch (_: Exception) { false }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                node.isVisibleToUser || runCatching {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    b.width() > 0 && b.height() > 0
                }.getOrDefault(false)
            } catch (_: Exception) { true }
        } else {
            true
        }
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            (className.contains("Text", ignoreCase = true) &&
                supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT))
        val hasViewHint = hasInputViewHint(viewId, hint)
        val hasLabelHint = hasInputLabelHint(label, desc)
        val hasSetTextAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        return enabled && visible && !looksLikeButton && !sendOrDismissLabel && (
            editable ||
                looksLikeInputClass ||
                hasSetTextAction ||
                (hasViewHint && (focusable || clickable)) ||
                (hasLabelHint && (editable || hasSetTextAction))
            )
    }

    private fun looksLikeStructuredUssdMenu(options: LinkedHashMap<String, String>): Boolean {
        if (options.isEmpty()) return false
        val numericKeys = options.keys.mapNotNull { it.toIntOrNull() }
        if (numericKeys.size != options.size) return false

        val meaningfulLabels = options.values.count { label ->
            val normalized = normalizeActionLabel(label)
            normalized.isNotBlank() &&
                normalized !in SEND_BUTTON_LABELS &&
                normalized !in DISMISS_BUTTON_LABELS &&
                normalized.any(Char::isLetterOrDigit)
        }
        return meaningfulLabels >= 1
    }

    private fun isLooseInputCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val focusable = try { node.isFocusable || node.isFocused } catch (_: Exception) { false }
        val clickable = try { node.isClickable || node.isLongClickable } catch (_: Exception) { false }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true)
        val hasWritableAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        val hasViewHint = hasInputViewHint(viewId, hint)
        val sendOrDismissLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        val hasShortText = label.isBlank() || label.length <= 24
        return enabled &&
            visible &&
            !editable &&
            !looksLikeButton &&
            !sendOrDismissLabel &&
            hasShortText &&
            ((hasWritableAction && (looksLikeInputClass || hasViewHint || focusable)) ||
                (hasViewHint && (focusable || clickable)))
    }

    internal fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        try {
            ((node.actions and actionId) != 0) || (node.actionList?.any { it.id == actionId } == true)
        } catch (_: Exception) {
            false
        }

    private fun normalizeInputValue(value: String?): String =
        value.orEmpty()
            .replace(MULTI_SPACE_REGEX, "")
            .trim()

    private fun normalizePhoneComparable(value: String?): String {
        val digits = value.orEmpty().replace(Regex("\\D+"), "")
        if (digits.length < 9) return ""
        return UssdHelper.normalizeRecipientForUssdInput(digits).replace(Regex("\\D+"), "")
    }

    private fun normalizeCollapsedText(value: String?): String =
        value.orEmpty()
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    private fun matchesExpectedInput(actualValue: String?, expectedValue: String): Boolean {
        val actual = normalizeInputValue(actualValue)
        val expected = normalizeInputValue(expectedValue)
        if (actual.isBlank() || expected.isBlank()) return false
        if (actual == expected) return true

        val actualPhone = normalizePhoneComparable(actualValue)
        val expectedPhone = normalizePhoneComparable(expectedValue)
        if (actualPhone.isNotBlank() && expectedPhone.isNotBlank()) {
            return actualPhone == expectedPhone
        }
        return false
    }

    private fun isLikelyPromptText(value: String): Boolean {
        val normalized = normalizeActionLabel(value)
        if (normalized.isBlank()) return false
        if (value.contains('\n')) return true
        if (Regex("""\b\d+\s*[\)\].:\-]\s+\S+""").containsMatchIn(value)) return true
        if (normalized.length > 18 && USSD_HINTS.any { normalized.contains(it) }) return true
        return false
    }

    internal fun rememberInputWrite(value: String) {
        lastInputWriteValue = normalizeInputValue(value)
        lastInputWriteElapsed = SystemClock.elapsedRealtime()
        lastInputWriteStepIndex = currentStep
        inputWriteAttemptCount++
    }

    /** True after at least one ACTION_SET_TEXT attempt for this step. */
    internal fun hasInputWriteAttemptForCurrentStep(expectedValue: String): Boolean {
        val expected = normalizeInputValue(expectedValue)
        return expected.isNotBlank() &&
            lastInputWriteStepIndex == currentStep &&
            lastInputWriteValue == expected &&
            inputWriteAttemptCount >= 1
    }

    /**
     * A few dialers report ACTION_SET_TEXT as accepted, then asynchronously clear
     * their EditText while replacing the USSD prompt. Permit a retry only for the
     * same step/value, only when the field is empty, and only after a short grace.
     */
    internal fun canRetryInputWriteForCurrentStep(
        expectedValue: String,
        currentFieldText: String?
    ): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank() ||
            lastInputWriteStepIndex != currentStep ||
            lastInputWriteValue != expected ||
            inputWriteAttemptCount <= 0 ||
            inputWriteAttemptCount >= UssdNavigationConfig.MAX_SET_TEXT_ATTEMPTS
        ) return false
        if (!currentFieldText.isNullOrBlank()) return false
        return SystemClock.elapsedRealtime() - lastInputWriteElapsed >=
            UssdNavigationConfig.SET_TEXT_RETRY_DELAY_MS
    }

    private fun clearInputWriteMarker() {
        lastInputWriteValue = ""
        lastInputWriteElapsed = 0L
        lastInputWriteStepIndex = -1
        inputWriteAttemptCount = 0
        lastVerifiedInputValue = ""
        lastVerifiedInputElapsed = 0L
        lastVerifiedStepIndex = -1
    }

    internal fun hasRecentExpectedInput(expectedValue: String): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank()) return false
        if (lastInputWriteStepIndex != currentStep) return false
        val current = lastInputWriteValue
        if (current.isBlank()) return false
        if (expected == current) {
            return SystemClock.elapsedRealtime() - lastInputWriteElapsed <= UssdNavigationConfig.RECENT_INPUT_GRACE_MS
        }
        return false
    }

    internal fun rememberVerifiedInput(value: String, stepIndex: Int = currentStep) {
        val normalized = normalizeInputValue(value)
        if (normalized.isBlank()) return
        lastVerifiedInputValue = normalized
        lastVerifiedInputElapsed = SystemClock.elapsedRealtime()
        if (stepIndex >= 0) lastVerifiedStepIndex = stepIndex
    }

    internal fun hasRecentVerifiedInput(expectedValue: String): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank()) return false
        if (lastVerifiedStepIndex != currentStep) return false
        if (lastVerifiedInputValue != expected) return false
        return SystemClock.elapsedRealtime() - lastVerifiedInputElapsed <= UssdNavigationConfig.RECENT_VERIFIED_INPUT_GRACE_MS
    }

    internal fun hasFreshlyVerifiedInput(expectedValue: String, maxAgeMs: Long = 800L): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank()) return false
        if (lastVerifiedStepIndex != currentStep) return false
        if (lastVerifiedInputValue != expected) return false
        return SystemClock.elapsedRealtime() - lastVerifiedInputElapsed <= maxAgeMs
    }

    internal fun isPromptFieldText(value: String): Boolean = isLikelyPromptText(value)

    internal fun isVerifiedFieldValue(fieldText: String?, expectedValue: String): Boolean {
        return matchesExpectedInput(fieldText, expectedValue)
    }

    private fun verifyExpectedInputFromRoot(
        root: AccessibilityNodeInfo,
        expectedValue: String,
        existingField: AccessibilityNodeInfo? = null
    ): Boolean {
        if (existingField != null) {
            val directText = readFieldTextFast(existingField) ?: readFieldText(existingField)
            if (isVerifiedFieldValue(directText, expectedValue)) {
                rememberVerifiedInput(expectedValue)
                return true
            }
        }
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        return try {
            collectTextEntryCandidates(root, candidates)
            val verified = candidates.any { candidate ->
                val candText = readFieldTextFast(candidate) ?: readFieldText(candidate)
                isVerifiedFieldValue(candText, expectedValue)
            }
            if (verified) rememberVerifiedInput(expectedValue)
            verified
        } finally {
            candidates.forEach { candidate ->
                if (candidate !== existingField) {
                    runCatching { candidate.recycle() }
                }
            }
        }
    }

    internal fun readFieldTextFast(node: AccessibilityNodeInfo): String? {
        val directText = runCatching { node.text?.toString() }.getOrNull()
        if (!directText.isNullOrBlank()) return directText
        val descText = runCatching { node.contentDescription?.toString() }.getOrNull()
        return descText?.takeIf { it.isNotBlank() }
    }

    private fun readFieldText(node: AccessibilityNodeInfo): String? {
        val directText = try { node.text?.toString() } catch (_: Exception) { null }
        if (!directText.isNullOrBlank()) return directText
        val descText = try { node.contentDescription?.toString() } catch (_: Exception) { null }
        if (!descText.isNullOrBlank()) return descText
        val nestedText = extractTextTokens(node)
            .filterNot { token -> isLikelyPromptText(token) }
            .minByOrNull { token -> token.length }
            ?.takeIf { token ->
                val normalized = normalizeActionLabel(token)
                normalized.isNotBlank() && INPUT_FIELD_HINTS.none { normalized.contains(it) }
            }
        if (!nestedText.isNullOrBlank()) return nestedText
        return null
    }

    private fun obtainInputTargets(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        collectPreferredInputTargets(node, targets, 0)
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < UssdNavigationConfig.INPUT_TARGET_DEPTH) {
            if (supportsDirectInput(current)) {
                targets += current
            } else {
                current.recycle()
            }
            current = try { current.parent?.let { AccessibilityNodeInfo.obtain(it) } } catch (_: Exception) { null }
            depth++
        }
        return targets
    }

    private fun collectPreferredInputTargets(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 2) return
        try {
            if (supportsDirectInput(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun supportsDirectInput(node: AccessibilityNodeInfo): Boolean =
        isTextEntryNode(node) ||
            isLooseInputCandidate(node) ||
            try { node.isEditable || node.isFocusable } catch (_: Exception) { false }

    private fun focusInputTarget(node: AccessibilityNodeInfo) {
        val alreadyFocused = try { node.isFocused } catch (_: Exception) { false }
        if (alreadyFocused) return
        if (refocusInputTarget(node)) return
        activateInputTarget(node)
    }

    private fun refocusInputTarget(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                }.getOrDefault(false))

    private fun activateInputTarget(node: AccessibilityNodeInfo): Boolean {
        if (!isSafeInputActivationCandidate(node)) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) ||
            performTapGesture(node)
    }

    private fun supportsSilentSetText(node: AccessibilityNodeInfo): Boolean =
        supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)

    private fun isSafeInputActivationCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true)
        return editable ||
            supportsSilentSetText(node) ||
            looksLikeInputClass ||
            hasInputViewHint(viewId, hint)
    }

    private fun findEditableFieldMatchingExpectedInput(
        root: AccessibilityNodeInfo,
        expectedValue: String
    ): AccessibilityNodeInfo? =
        mutableListOf<AccessibilityNodeInfo>().let { candidates ->
            collectTextEntryCandidates(root, candidates)
            val verifiedMatch = candidates.firstOrNull { candidate ->
                val candText = readFieldTextFast(candidate) ?: readFieldText(candidate)
                isVerifiedFieldValue(candText, expectedValue)
            }
            val preferred = verifiedMatch
                ?: candidates.maxByOrNull { candidate ->
                    val candText = readFieldTextFast(candidate) ?: readFieldText(candidate)
                    scoreTextEntryCandidate(candidate) +
                        if (matchesExpectedInput(candText, expectedValue)) 700 else 0
                }
            val result = preferred?.let { AccessibilityNodeInfo.obtain(it) }
            candidates.forEach { it.recycle() }
            result
        }

    private fun obtainActionTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val clickable = try {
                current.isClickable || current.isFocusable
            } catch (_: Exception) {
                false
            }
            if (clickable) return current
            val parent = try { current.parent } catch (_: Exception) { null }
            current.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 12) {
            val activeNode = current
            val clicked = try {
                activeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (_: Exception) {
                false
            }
            if (clicked) {
                activeNode.recycle()
                return true
            }
            if (performTapGesture(activeNode)) {
                activeNode.recycle()
                return true
            }
            val parent = try { activeNode.parent } catch (_: Exception) { null }
            activeNode.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return false
    }

    private fun performTapGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect()
        if (!runCatching { node.getBoundsInScreen(bounds) }.isSuccess) return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        if (x <= 0f || y <= 0f) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, UssdNavigationConfig.TAP_GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        return dispatched
    }

    internal fun findInputField(
        root: AccessibilityNodeInfo,
        allowRankedSearch: Boolean = true
    ): AccessibilityNodeInfo? {
        cachedInputFieldId?.let { id ->
            val matches = runCatching {
                root.findAccessibilityNodeInfosByViewId(id)
            }.getOrNull().orEmpty()
            var cachedMatch: AccessibilityNodeInfo? = null
            try {
                val match = matches.firstOrNull { node ->
                    node.isEditable ||
                        node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                }
                // Keep an independent handle for the caller; always recycle every
                // node returned by findAccessibilityNodeInfosByViewId.
                cachedMatch = match?.let { AccessibilityNodeInfo.obtain(it) }
            } finally {
                matches.forEach { node -> runCatching { node.recycle() } }
            }
            cachedMatch?.let {
                Log.d(TAG, "findInputField: found via cached id=$id")
                return it
            }
        }

        // Fast-path: query common OEM USSD input view IDs directly (~1ms) before falling back to full tree traversal.
        val pkg = root.packageName?.toString().orEmpty()
        val fastIdCandidates = if (pkg.isNotBlank()) {
            listOf("$pkg:id/input", "android:id/input", "android:id/edit", "$pkg:id/dialog_input")
        } else {
            listOf("android:id/input", "android:id/edit")
        }
        for (id in fastIdCandidates) {
            if (id == cachedInputFieldId) continue
            val matches = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            try {
                val match = matches.firstOrNull { node ->
                    node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
                }
                if (match != null) {
                    cachedInputFieldId = id
                    Log.d(TAG, "findInputField: found via common fast ID=$id")
                    return AccessibilityNodeInfo.obtain(match)
                }
            } finally {
                matches.forEach { node -> runCatching { node.recycle() } }
            }
        }

        // Prefer the scored finder when the caller has not already completed a ranked
        // search on this same popup tree.
        if (allowRankedSearch) {
            findEditableField(root)?.let { best ->
                best.viewIdResourceName?.let { id -> cachedInputFieldId = id }
                Log.d(TAG, "findInputField: scored match viewId=$cachedInputFieldId")
                return best
            }
        }

        // Last resort keyword sweep for OEM popups whose field advertises neither writability nor a
        // recognisable class name.
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val visited = mutableSetOf<String>()
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                try {
                    val identity = "${runCatching { node.viewIdResourceName }.getOrNull()}|" +
                        "${runCatching { node.className }.getOrNull()}|" +
                        "${runCatching { node.text }.getOrNull()}"
                    if (!visited.add(identity)) continue

                    val className = node.className?.toString().orEmpty()
                    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
                    val text = node.text?.toString().orEmpty().lowercase()
                    val desc = node.contentDescription?.toString().orEmpty().lowercase()
                    val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        node.hintText?.toString().orEmpty().lowercase()
                    } else {
                        ""
                    }

                    val canTakeText = node.isEditable ||
                        supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                        EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }
                    val isInputCandidate = canTakeText && when {
                        className.contains("EditText", ignoreCase = true) -> true
                        className.contains("AutoCompleteTextView", ignoreCase = true) -> true
                        className.contains("TextInputEditText", ignoreCase = true) -> true
                        className.contains("MultiAutoCompleteTextView", ignoreCase = true) -> true
                        viewId.contains("input") || viewId.contains("edit") || viewId.contains("reply") -> true
                        viewId.contains("phone") || viewId.contains("number") || viewId.contains("amount") -> true
                        text.contains("enter") || text.contains("input") || text.contains("reply") -> true
                        text.contains("phone") || text.contains("number") || text.contains("amount") -> true
                        desc.contains("enter") || desc.contains("input") || desc.contains("reply") -> true
                        desc.contains("phone") || desc.contains("number") || desc.contains("amount") -> true
                        hint.contains("enter") || hint.contains("input") || hint.contains("reply") -> true
                        hint.contains("phone") || hint.contains("number") || hint.contains("amount") -> true
                        else -> false
                    }
                    if (isInputCandidate) {
                        node.viewIdResourceName?.let { cachedInputFieldId = it }
                        Log.d(TAG, "findInputField: keyword match className=$className viewId=$cachedInputFieldId")
                        return AccessibilityNodeInfo.obtain(node)
                    }

                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { child ->
                            queue.add(child)
                        }
                    }
                } finally {
                    runCatching { node.recycle() }
                }
            }
        } finally {
            queue.forEach { queued -> runCatching { queued.recycle() } }
        }
        Log.w(TAG, "findInputField: no input field found in tree")
        return null
    }

    private fun clickSendButton(root: AccessibilityNodeInfo): Boolean {
        return sendClick.clickSendButton(root)
    }

    private fun simulateTextInput(inputField: AccessibilityNodeInfo, value: String): Boolean {
        return tryWriteValueToField(inputField, value)
    }

    private fun waitForInputValue(root: AccessibilityNodeInfo, expectedValue: String): Boolean {
        if (hasRecentVerifiedInput(expectedValue)) return true
        val verified = verifyExpectedInputFromRoot(root = root, expectedValue = expectedValue)
        Log.d(TAG, "waitForInputValue: expected='$expectedValue' verified=$verified")
        return verified
    }

    private fun clickOkButton(root: AccessibilityNodeInfo): Boolean {
        val btn = findActionButton(root, DISMISS_BUTTON_LABELS) ?: findPositiveDialogButton(root)
        return btn?.let { performClick(it) } ?: false
    }

    private fun isUssdLoadingScreen(dialogText: String): Boolean {
        val lower = dialogText.lowercase()
        return TRANSIENT_RESPONSE_HINTS.any { lower.contains(it) }
    }

    private fun captureLearningDialogIfNeeded(
        stepIndex: Int?,
        root: AccessibilityNodeInfo,
        windowPkg: String,
        dialogText: String = ""
    ) {
        if (!signatureLearningMode) return
        val snapshot = if (windowPkg == "com.android.systemui") {
            val dialogRoot = findSignatureLearningDialogRoot(root, windowPkg) ?: run {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(windowPkg, runCatching { root.windowId }.getOrDefault(-1), "unverified_systemui_learning_root")
                return
            }
            try {
                getOrCaptureActiveSnapshot(dialogRoot)
            } finally {
                runCatching { dialogRoot.recycle() }
            }
        } else {
            if (windowPkg != "android" && windowPkg.isNotBlank() && !isPotentialUssdPackage(windowPkg)) {
                UssdPerformanceDiagnostics.recordLearningWindowRejected(windowPkg, runCatching { root.windowId }.getOrDefault(-1), "non_ussd_package")
                return
            }
            getOrCaptureActiveSnapshot(root)
        }
        val lower = snapshot.dialogText.lowercase()
        if (snapshot.dialogText.isBlank() ||
            isTransientResponseText(lower) ||
            isUssdLoadingScreen(snapshot.dialogText)
        ) return
        captureLearningDialog(snapshot)
    }

    private fun findSignatureLearningDialogRoot(
        root: AccessibilityNodeInfo,
        windowPkg: String
    ): AccessibilityNodeInfo? {
        if (windowPkg != "android" &&
            windowPkg != "com.android.systemui" &&
            windowPkg.isNotBlank() &&
            !isPotentialUssdPackage(windowPkg)
        ) return null

        val rootBounds = Rect()
        runCatching { root.getBoundsInScreen(rootBounds) }
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) return null

        // Fast-path: Check if the root itself is already a valid strong signature dialog snapshot.
        // For SystemUI, root is the screen-wide container (including notifications, status bar, etc.).
        // Never return the whole SystemUI root directly; candidate search must isolate the dialog child.
        if (windowPkg != "com.android.systemui") {
            val rootSnapshot = getOrCaptureActiveSnapshot(root)
            if (isStrongSignatureDialogSnapshot(rootSnapshot, root, rootBounds, windowPkg)) {
                return AccessibilityNodeInfo.obtain(root)
            }
        }

        // Search candidates to isolate the inner dialog container (essential for SystemUI)
        val candidates = mutableListOf<DialogCaptureCandidate>()
        try {
            collectDialogCaptureCandidates(root, rootBounds, candidates, depth = 0)
            val best = candidates
                .asSequence()
                .filter { candidate ->
                    val node = candidate.node
                    val snapshot = getOrCaptureActiveSnapshot(node)
                    isStrongSignatureDialogSnapshot(snapshot, node, rootBounds, windowPkg)
                }
                .maxByOrNull { it.score }
            if (best != null) return AccessibilityNodeInfo.obtain(best.node)
        } finally {
            candidates.forEach { candidate -> runCatching { candidate.node.recycle() } }
        }
        return null
    }

    private fun isStrongSignatureDialogSnapshot(
        snapshot: UssdTreeSnapshot,
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        windowPkg: String
    ): Boolean {
        val text = snapshot.dialogText.trim()
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false

        val menuLike = extractStructuredMenuBlock(snapshot.textTokens) != null
        val strongUssdLanguage = SIGNATURE_USSD_HINTS.any { lower.contains(it) } ||
            errorKeywords.any { lower.contains(it) } ||
            USSD_HINTS.any { lower.contains(it) }

        // A real USSD prompt must have a reply/send/dismiss affordance, or be a
        // clearly structured numbered menu or contain USSD language
        val hasInteraction = snapshot.hasEditableField ||
            snapshot.hasSendButton ||
            snapshot.hasDismissButton
        if (!menuLike && !hasInteraction && !strongUssdLanguage) return false

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val rootArea = rootBounds.width().toLong() * rootBounds.height().toLong()
        val candidateArea = bounds.width().toLong() * bounds.height().toLong()
        if (rootArea <= 0L || candidateArea <= 0L) return false

        val className = node.className?.toString().orEmpty()
        val dialogClass = className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true)

        // SystemUI also hosts heads-up SMS/WhatsApp notifications. Accept it only
        // when Android exposes an actual dialog container, never merely arbitrary text.
        if (windowPkg == "com.android.systemui" && !dialogClass) return false

        // For USSD packages and android framework, accept dialog classes, menu layouts,
        // or any interactive container
        return dialogClass || menuLike || hasInteraction || strongUssdLanguage
    }

    private fun handleStepFailure(root: AccessibilityNodeInfo?) {
        if (lastSubmittedStepNumber > 0) {
            finishAmbiguousSubmittedStep("the session stopped while waiting for a later popup")
            return
        }
        Log.e(TAG, "USSD step failure. Full teardown.")

        cancelStepTimeout()
        handler.removeCallbacksAndMessages(null)

        advancedActive = false
        advancedInProgress = false
        advancedSteps = emptyList()
        advancedPhoneNumber = ""
        advancedDialCode = ""
        advancedOfferId = -1
        currentStep = 0
        lastInputStepProcessed = -1
        isProcessing = false
        waitingForNextDialog = false
        isUsdExecutionLocked = false
        retryCount = 0
        stepRetryCount = 0

        if (root != null) {
            try {
                clickOkButton(root)
            } catch (e: Exception) {
                Log.e(TAG, "Could not dismiss broken popup", e)
            }
        }

        onDispatchComplete?.invoke(
            AdvancedDispatchResult(
                finalResponse = lastFinalResponse,
                changeDetected = false,
                autoAdjusted = false
            )
        )
        onDispatchComplete = null

        stopKeepingAppUiVisible()
        updateRunningOverlay()
    }

    private fun processCurrentStep() {
        if (!advancedActive || currentStep >= advancedSteps.size) {
            isProcessing = false
            return
        }
        if (!waitingForNextDialog) {
            Log.d(TAG, "processCurrentStep: blocked - no confirmed new popup")
            return
        }

        processStepJob?.cancel()
        isProcessing = true
        waitingForNextDialog = false

        val stepIndex = currentStep
        val rawStep = advancedSteps[stepIndex]

        val root = getUssdRoot()
        if (root == null) {
            isProcessing = false
            waitingForNextDialog = true
            return
        }

        try {
            val snapshotStartedAt = UssdPerformanceDiagnostics.mark()
            val snapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = false)
            UssdPerformanceDiagnostics.record(
                "step.popup_snapshot",
                snapshotStartedAt,
                "found=${snapshot != null} window=${root.windowId}"
            )
            val menuSignature = snapshot?.let { parseMenuSignature(it) }
            val signatureKey = snapshot?.normalizedDialogText?.take(120).orEmpty()
            val stepValue = if (rawStep == "INPUT_PHONE") {
                val phone = advancedPhoneNumber.ifBlank {
                    if (signatureLearningMode) "0700000000" else ""
                }
                UssdHelper.normalizeRecipientForUssdInput(phone)
            } else if (signatureKey.isNotBlank() && signatureKey == lastResolvedStepSignature && stepIndex == lastResolvedStepIndex && lastResolvedStepValue.isNotBlank()) {
                Log.d(TAG, "processCurrentStep: reusing cached resolved value='$lastResolvedStepValue' for stepIndex=$stepIndex")
                lastResolvedStepValue
            } else {
                val resolved = resolveStepInput(stepIndex, rawStep, menuSignature)
                val resolvedValue = resolved.first
                if (signatureKey.isNotBlank() && resolvedValue.isNotBlank()) {
                    lastResolvedStepSignature = signatureKey
                    lastResolvedStepValue = resolvedValue
                    lastResolvedStepIndex = stepIndex
                }
                resolvedValue
            }

            if (stepValue.isBlank()) {
                currentStep++
                isProcessing = false
                waitingForNextDialog = true
                return
            }

            Log.d(TAG, "processCurrentStep: stepIndex=$stepIndex rawStep=$rawStep resolved=$stepValue signature=$menuSignature")

            Log.d(TAG, "processCurrentStep attempt=0 step=$stepValue")

            // Acquire the editable field once and keep that exact node through
            // verification and submission. Previously this path searched the same
            // accessibility tree up to three times for the same EditText.
            val fieldLookupStartedAt = UssdPerformanceDiagnostics.mark()
            val field = findInputField(root)
            UssdPerformanceDiagnostics.record(
                "step.input_field_lookup",
                fieldLookupStartedAt,
                "found=${field != null} window=${root.windowId}"
            )
            if (field == null) {
                Log.w(TAG, "processCurrentStep: no input field found in validated popup")
                isProcessing = false
                waitingForNextDialog = true
                handleStepFailure(root)
                return
            }

            try {
                // writeValueResult already checks the current field value before
                // attempting ACTION_SET_TEXT. Do not perform a duplicate Binder-backed
                // text read immediately before calling it.
                val writeStartedAt = UssdPerformanceDiagnostics.mark()
                val writeSucceeded = valueWriter.writeValueToNode(field, stepValue)
                UssdPerformanceDiagnostics.record(
                    "step.input_write_verify",
                    writeStartedAt,
                    "success=$writeSucceeded"
                )

                if (!writeSucceeded) {
                    Log.w(TAG, "processCurrentStep: exact input write/read-back failed")
                    isProcessing = false
                    waitingForNextDialog = true
                    handleStepFailure(root)
                    return
                }

                Log.d(TAG, "processCurrentStep: input verified; submitting same field node")
                val sendStartedAt = UssdPerformanceDiagnostics.mark()
                val sent = tryImeEnterSubmit(field) ||
                    clickSendButton(root) ||
                    triggerInputSubmit(root, stepValue, field)
                UssdPerformanceDiagnostics.record(
                    "step.send_action",
                    sendStartedAt,
                    "accepted=$sent"
                )

                if (sent) {
                    Log.d(TAG, "processCurrentStep: send succeeded stepIndex=$stepIndex")
                    lastInputStepProcessed = stepIndex
                    lastDialogInteractionTime = SystemClock.elapsedRealtime()
                    currentStep++
                    isProcessing = false
                    waitingForNextDialog = true
                    startStepTimeout()
                    return
                }

                Log.w(TAG, "processCurrentStep: send failed after verified input")
            } finally {
                runCatching { field.recycle() }
            }

            isProcessing = false
            waitingForNextDialog = true
            handleStepFailure(root)
        } finally {
            root.recycle()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(UssdNavigationConfig.CHANNEL_ID, "USSD Automation", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            piFlags
        )
        return NotificationCompat.Builder(this, UssdNavigationConfig.CHANNEL_ID)
            .setContentTitle("Bingwa Mobile")
            .setContentText("USSD automation active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
