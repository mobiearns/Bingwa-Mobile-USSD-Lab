package com.bingwa.mobile.experimental

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import com.bingwa.mobile.LIVE_MENU_FULL_USSD_CODE
import com.bingwa.mobile.LiveMenuSimCandidate
import com.bingwa.mobile.OFFER_SIM_USE_GENERAL
import com.bingwa.mobile.SilentUssdOptimized
import com.bingwa.mobile.SimpleSignatureVerifier
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_1
import com.bingwa.mobile.USSD_SIM_SELECTION_SLOT_2
import com.bingwa.mobile.UssdHelper
import com.bingwa.mobile.UssdNavigationService
import com.bingwa.mobile.getAvailableSims
import com.bingwa.mobile.isConfirmedNonSafaricomOperator
import com.bingwa.mobile.isSafaricomOperator
import com.bingwa.mobile.normalizeSilentUssdResponse
import com.bingwa.mobile.resolveLiveMenuSimCandidates
import com.bingwa.mobile.resolveLiveMenuSimRouting
import com.bingwa.mobile.resolveUssdSimTargets
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal const val EXPERIMENTAL_LIVE_MENU_USSD: String = LIVE_MENU_FULL_USSD_CODE
internal const val EXPERIMENTAL_USSD_RESPONSE_KEY: String = "USSD_RESPONSE"
internal const val EXPERIMENTAL_TIMEOUT_MS: Long = 20_000L

internal const val ADB_CHECK_HIDDEN_API_CMD: String =
    "adb shell settings get global hidden_api_policy"
internal const val ADB_RELAX_HIDDEN_API_CMD: String =
    "adb shell settings put global hidden_api_policy 1 && adb shell am force-stop com.bingwa.mobile"
internal const val ADB_REVERT_HIDDEN_API_CMD: String =
    "adb shell settings delete global hidden_api_policy && adb shell am force-stop com.bingwa.mobile"
internal const val ADB_RADIO_LOGCAT_CMD: String =
    "adb logcat -v time -b radio -b main -s Telephony:V GsmCdmaPhone:V GsmMmiCode:V PhoneInterfaceManager:V *:S"

internal data class HiddenApiPolicyStatus(
    val globalPolicy: Int?,
    val prePPolicy: Int?,
    val pPolicy: Int?,
    val enforcementLikelyActive: Boolean,
    val summary: String
)

internal data class ExtractedUssdPayload(
    val bundleKeys: List<String>,
    val bundleSummary: String,
    val rawUssdResponseClass: String?,
    val ussdRequestText: String?,
    val returnMessageText: String?,
    val parcelFallbackRequest: String?,
    val parcelFallbackMessage: String?,
    val extraCharSequences: Map<String, String>,
    val bestExtractedText: String,
    val extractionNotes: List<String>
)

internal data class ExperimentalProbeReport(
    val timestampMs: Long,
    val deviceSummary: String,
    val callPhoneGranted: Boolean,
    val readPhoneStateGranted: Boolean,
    val hiddenApiPolicy: HiddenApiPolicyStatus,
    val simDescription: String,
    val resolvedSubId: Int?,
    val resolvedSlotIndex: Int?,
    val simAdvice: String,
    val carrierConfigSummary: String,
    val readinessSummary: String,
    val getITelephonyStatus: String,
    val serviceManagerStatus: String,
    val discoveredOverloads: List<String>,
    val ussdResponseInspection: String,
    val overallVerdict: String,
    val formattedReport: String
)

internal data class ExperimentalExecutionResult(
    val executedAtMs: Long,
    val requestCode: String,
    val simDescription: String,
    val resolvedSubId: Int?,
    val hiddenApiPolicySummary: String,
    val acquisitionPathUsed: String,
    val overloadInvoked: String,
    val requestStarted: Boolean,
    val elapsedMs: Long,
    val timedOut: Boolean,
    val resultCode: Int?,
    val resultCodeLabel: String,
    val extractedPayload: ExtractedUssdPayload?,
    val parsedMenuOptions: List<Pair<Int, String>>,
    val isCarrierRetryLimit: Boolean,
    val exceptionType: String?,
    val exceptionMessage: String?,
    val causeChain: String?,
    val rawDiagnosticReport: String
)

internal fun describeHiddenApiPolicy(
    globalPolicy: Int?,
    prePPolicy: Int?,
    pPolicy: Int?,
    targetSdkVersion: Int,
    sdkInt: Int
): HiddenApiPolicyStatus {
    val active = sdkInt >= Build.VERSION_CODES.P &&
        targetSdkVersion >= Build.VERSION_CODES.Q &&
        globalPolicy != 1
    val globalLabel = when (globalPolicy) {
        null, -1 -> "unset (default enforcement active)"
        0 -> "0 (disabled/strict enforcement)"
        1 -> "1 (relaxed / allowlist-exempt for debug & testing)"
        2 -> "2 (deny all non-SDK APIs)"
        else -> "$globalPolicy (custom)"
    }
    val summary = buildString {
        append("SDK=$sdkInt, targetSdk=$targetSdkVersion; ")
        append("hidden_api_policy=$globalLabel")
        if (prePPolicy != null && prePPolicy != -1) append("; pre_p=$prePPolicy")
        if (pPolicy != null && pPolicy != -1) append("; p=$pPolicy")
        append(
            if (active) " -> ART hidden-API blocking expected on TelephonyManager.getITelephony()"
            else " -> hidden-API policy relaxed or not enforcing"
        )
    }
    return HiddenApiPolicyStatus(
        globalPolicy = globalPolicy,
        prePPolicy = prePPolicy,
        pPolicy = pPolicy,
        enforcementLikelyActive = active,
        summary = summary
    )
}

internal fun describeResultCode(code: Int?): String = when (code) {
    null -> "NONE (no ResultReceiver callback)"
    100 -> "100 (USSD_RETURN_SUCCESS)"
    0 -> "0 (framework result 0)"
    TelephonyManager.USSD_RETURN_FAILURE -> "-1 (USSD_RETURN_FAILURE)"
    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "-2 (USSD_ERROR_SERVICE_UNAVAIL)"
    -3 -> "-3 (USSD_ERROR_NOT_ALLOWED / disallowed by carrier or permission)"
    else -> "$code (unclassified framework code)"
}

internal fun selectBestExtractedUssdText(
    returnMessageText: String?,
    parcelFallbackMessage: String?,
    extraCharSequences: Map<String, String>
): String {
    val candidates = buildList {
        if (!returnMessageText.isNullOrBlank()) add(returnMessageText)
        if (!parcelFallbackMessage.isNullOrBlank()) add(parcelFallbackMessage)
        extraCharSequences.entries
            .sortedBy { if (it.key.contains("message", ignoreCase = true) || it.key.contains("ussd", ignoreCase = true)) 0 else 1 }
            .forEach { if (it.value.isNotBlank()) add(it.value) }
    }
    val first = candidates.firstOrNull().orEmpty()
    return normalizeSilentUssdResponse(first).ifBlank { first.trim() }
}

internal fun enforceReadOnlyExperimentalRequest(candidate: String): String {
    require(candidate == EXPERIMENTAL_LIVE_MENU_USSD) {
        "Experimental harness only executes read-only $EXPERIMENTAL_LIVE_MENU_USSD"
    }
    return EXPERIMENTAL_LIVE_MENU_USSD
}

internal object InternalUssdHandleExperiment {
    private val inFlight = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = inFlight.get()

    fun buildSimChoices(context: Context): List<Pair<Int, String>> {
        val sims = getAvailableSims(context, forceRefresh = false)
        val candidates = resolveLiveMenuSimCandidates(context, sims)
        val hasSafaricom = candidates.any { isSafaricomOperator(it.simOperatorNumeric, it.carrierLabel) }
        return buildList {
            add(OFFER_SIM_USE_GENERAL to if (hasSafaricom) "Auto (Safaricom)" else "App default SIM")
            val slot1 = candidates.firstOrNull { it.slotIndex == 0 }
            val slot2 = candidates.firstOrNull { it.slotIndex == 1 }
            add(USSD_SIM_SELECTION_SLOT_1 to formatSlotLabel(1, slot1, hasSafaricom))
            add(USSD_SIM_SELECTION_SLOT_2 to formatSlotLabel(2, slot2, hasSafaricom))
        }
    }

    private fun formatSlotLabel(
        slotNumber: Int,
        candidate: LiveMenuSimCandidate?,
        hasSafaricom: Boolean
    ): String = buildString {
        append("SIM $slotNumber")
        candidate?.carrierLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        if (candidate != null && hasSafaricom &&
            isConfirmedNonSafaricomOperator(candidate.simOperatorNumeric, candidate.carrierLabel)
        ) {
            append(" (non-Safaricom)")
        }
    }

    private fun readHiddenApiPolicy(context: Context): HiddenApiPolicyStatus {
        val cr = context.contentResolver
        val globalVal = runCatching { Settings.Global.getInt(cr, "hidden_api_policy", -1) }.getOrNull()
        val prePVal = runCatching { Settings.Global.getInt(cr, "hidden_api_policy_pre_p_apps", -1) }.getOrNull()
        val pVal = runCatching { Settings.Global.getInt(cr, "hidden_api_policy_p_apps", -1) }.getOrNull()
        return describeHiddenApiPolicy(
            globalPolicy = globalVal,
            prePPolicy = prePVal,
            pPolicy = pVal,
            targetSdkVersion = context.applicationInfo.targetSdkVersion,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    private fun resolveTargetSim(
        context: Context,
        requestedSimSelection: Int
    ): Triple<String, Int?, Int?> {
        val sims = getAvailableSims(context, forceRefresh = true)
        val candidates = resolveLiveMenuSimCandidates(context, sims)
        val routing = resolveLiveMenuSimRouting(candidates, requestedSimSelection)
        val effectiveSelection = routing.chosen?.slotSelection
            ?: requestedSimSelection.takeUnless { it == OFFER_SIM_USE_GENERAL }
        val resolvedTarget = resolveUssdSimTargets(
            context,
            selectionOverride = effectiveSelection,
            forceRefresh = true
        ).firstOrNull()
        val carrier = routing.chosen?.carrierLabel?.takeIf { it.isNotBlank() }
            ?: resolvedTarget?.let { target ->
                sims.firstOrNull { it.subscriptionId == target.subId }
                    ?.let { it.carrierName?.toString()?.trim()?.ifBlank { null } ?: it.displayName?.toString()?.trim() }
            }
        val desc = resolvedTarget?.let {
            val suffix = carrier?.let { c -> " · $c" }.orEmpty()
            "SIM slot ${it.slotIndex + 1}$suffix (subId=${it.subId})"
        } ?: "No active SIM resolved"
        return Triple(desc, resolvedTarget?.subId, resolvedTarget?.slotIndex)
    }

    private fun resolveSimAdvice(context: Context, requestedSimSelection: Int): String {
        val sims = getAvailableSims(context, forceRefresh = false)
        val candidates = resolveLiveMenuSimCandidates(context, sims)
        return resolveLiveMenuSimRouting(candidates, requestedSimSelection).simAdvice
    }

    private data class ITelephonyAcquisition(
        val proxy: Any?,
        val pathLabel: String,
        val statusLog: String,
        val error: Throwable?
    )

    private fun tryAcquireViaTelephonyManager(
        baseTm: TelephonyManager?,
        boundTm: TelephonyManager?
    ): ITelephonyAcquisition {
        val targets = buildList {
            if (boundTm != null) add("bound TelephonyManager" to boundTm)
            if (baseTm != null && baseTm !== boundTm) add("base TelephonyManager" to baseTm)
        }
        if (targets.isEmpty()) {
            return ITelephonyAcquisition(
                proxy = null,
                pathLabel = "TelephonyManager.getITelephony()",
                statusLog = "TelephonyManager system service is null",
                error = IllegalStateException("TelephonyManager unavailable")
            )
        }
        var lastError: Throwable? = null
        val logs = mutableListOf<String>()
        for ((label, tm) in targets) {
            val attempt = runCatching {
                val method = TelephonyManager::class.java.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                method.invoke(tm)
            }
            attempt.onSuccess { proxy ->
                if (proxy != null) {
                    return ITelephonyAcquisition(
                        proxy = proxy,
                        pathLabel = "$label.getITelephony()",
                        statusLog = "OK via $label.getITelephony() -> ${proxy.javaClass.name}",
                        error = null
                    )
                } else {
                    logs += "$label.getITelephony() returned null"
                }
            }.onFailure { err ->
                val root = unwrapThrowable(err)
                lastError = root
                logs += "$label failed: ${root.javaClass.simpleName}: ${root.message}"
            }
        }
        return ITelephonyAcquisition(
            proxy = null,
            pathLabel = "TelephonyManager.getITelephony()",
            statusLog = logs.joinToString(" | ").ifBlank { "Failed" },
            error = lastError
        )
    }

    @SuppressLint("PrivateApi")
    private fun tryAcquireViaServiceManager(): ITelephonyAcquisition {
        return try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            getService.isAccessible = true
            val binder = getService.invoke(null, Context.TELEPHONY_SERVICE) as? IBinder
                ?: return ITelephonyAcquisition(
                    proxy = null,
                    pathLabel = "ServiceManager(\"phone\") -> ITelephony\$Stub",
                    statusLog = "ServiceManager.getService(\"phone\") returned null",
                    error = IllegalStateException("ServiceManager(\"phone\") binder is null")
                )
            val stubClass = Class.forName("com.android.internal.telephony.ITelephony\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.isAccessible = true
            val proxy = asInterface.invoke(null, binder)
                ?: return ITelephonyAcquisition(
                    proxy = null,
                    pathLabel = "ServiceManager(\"phone\") -> ITelephony\$Stub",
                    statusLog = "ITelephony\$Stub.asInterface returned null",
                    error = IllegalStateException("ITelephony\$Stub.asInterface returned null")
                )
            ITelephonyAcquisition(
                proxy = proxy,
                pathLabel = "ServiceManager(\"phone\") -> ITelephony\$Stub.asInterface()",
                statusLog = "OK -> ${proxy.javaClass.name} (binder=${binder.interfaceDescriptor ?: "unknown"})",
                error = null
            )
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            ITelephonyAcquisition(
                proxy = null,
                pathLabel = "ServiceManager(\"phone\") -> ITelephony\$Stub",
                statusLog = "Blocked/Failed: ${root.javaClass.simpleName}: ${root.message}",
                error = root
            )
        }
    }

    private fun discoverHandleUssdMethods(proxy: Any?): List<Method> {
        if (proxy == null) return emptyList()
        val collected = linkedMapOf<String, Method>()
        fun addMatching(methods: Array<Method>) {
            methods.filter { it.name == "handleUssdRequest" }.forEach { m ->
                val key = m.parameterTypes.joinToString(",") { it.name }
                collected.putIfAbsent(key, m)
            }
        }
        runCatching { addMatching(proxy.javaClass.methods) }
        runCatching { addMatching(proxy.javaClass.declaredMethods) }
        runCatching {
            val iface = Class.forName("com.android.internal.telephony.ITelephony")
            addMatching(iface.methods)
            addMatching(iface.declaredMethods)
        }
        return collected.values.toList()
    }

    private fun formatMethodSignature(method: Method): String {
        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
        return "${method.declaringClass.simpleName}.${method.name}($params): ${method.returnType.simpleName}"
    }

    @SuppressLint("PrivateApi")
    private fun inspectUssdResponseReflection(): String = buildList {
        val keyVal = runCatching {
            val f = TelephonyManager::class.java.getDeclaredField("USSD_RESPONSE")
            f.isAccessible = true
            f.get(null)?.toString()
        }.getOrElse { "inaccessible (${unwrapThrowable(it).javaClass.simpleName})" }
        add("TelephonyManager.USSD_RESPONSE=$keyVal")

        runCatching {
            val cls = Class.forName("android.telephony.UssdResponse")
            val mReq = runCatching { cls.getMethod("getUssdRequest") }.isSuccess
            val mMsg = runCatching { cls.getMethod("getReturnMessage") }.isSuccess
            val fReq = runCatching { cls.getDeclaredField("mUssdRequest") }.isSuccess
            val fMsg = runCatching { cls.getDeclaredField("mReturnMessage") }.isSuccess
            add("UssdResponse(getters=$mReq/$mMsg, fields=$fReq/$fMsg, Parcelable=${Parcelable::class.java.isAssignableFrom(cls)})")
        }.onFailure {
            add("UssdResponse class unavailable (${unwrapThrowable(it).javaClass.simpleName})")
        }
    }.joinToString(" | ")

    fun probeEnvironment(
        context: Context,
        requestedSimSelection: Int
    ): ExperimentalProbeReport {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val callGranted = UssdHelper.canPlaceCalls(appContext)
        val phoneStateGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.READ_PHONE_STATE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hiddenPolicy = readHiddenApiPolicy(appContext)
        val (simDescription, subId, slotIndex) = resolveTargetSim(appContext, requestedSimSelection)
        val simAdvice = resolveSimAdvice(appContext, requestedSimSelection)
        val carrierConfig = UssdHelper.describeSilentUssdCarrierConfig(appContext, subId)
        val readiness = UssdHelper.describeSilentUssdReadiness(appContext, subId, slotIndex)

        val baseTm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val boundTm = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { baseTm?.createForSubscriptionId(subId) }.getOrNull()
        } else null

        val getITel = tryAcquireViaTelephonyManager(baseTm, boundTm)
        val svcMgr = tryAcquireViaServiceManager()
        val usableProxy = getITel.proxy ?: svcMgr.proxy
        val methods = discoverHandleUssdMethods(usableProxy).map(::formatMethodSignature)
        val ussdResponseInfo = inspectUssdResponseReflection()

        val verdict = when {
            !callGranted ->
                "BLOCKED BY PERMISSION: Grant CALL_PHONE permission first."
            usableProxy == null && hiddenPolicy.enforcementLikelyActive ->
                "BLOCKED BY ANDROID 12 HIDDEN-API ENFORCEMENT: Both getITelephony() and ServiceManager->ITelephony\$Stub are restricted at targetSdk=${appContext.applicationInfo.targetSdkVersion}. Relax hidden_api_policy via non-root ADB and re-probe."
            usableProxy == null ->
                "UNAVAILABLE: Could not acquire ITelephony binder even with current hidden-API policy."
            methods.isEmpty() ->
                "BINDER ACQUIRED, NO handleUssdRequest: ITelephony obtained, but no handleUssdRequest method is exposed on this OEM firmware."
            else ->
                "READY TO TEST: ITelephony acquired (${if (getITel.proxy != null) getITel.pathLabel else svcMgr.pathLabel}) with ${methods.size} handleUssdRequest overload(s)."
        }

        val deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · OEM ${Build.DISPLAY} · targetSdk ${appContext.applicationInfo.targetSdkVersion}"

        val formatted = buildString {
            appendLine("=== EXPERIMENTAL ITelephony.handleUssdRequest PROBE ===")
            appendLine("Timestamp: ${formatTimestamp(now)}")
            appendLine("Device: $deviceSummary")
            appendLine("Fixed read-only code: $EXPERIMENTAL_LIVE_MENU_USSD")
            appendLine("Permissions: CALL_PHONE=${if (callGranted) "GRANTED" else "MISSING"}, READ_PHONE_STATE=${if (phoneStateGranted) "GRANTED" else "MISSING"}")
            appendLine("Hidden API policy: ${hiddenPolicy.summary}")
            appendLine("Target SIM: $simDescription")
            if (simAdvice.isNotBlank()) appendLine("SIM routing advice: $simAdvice")
            appendLine("Readiness: $readiness")
            appendLine("Carrier config: $carrierConfig")
            appendLine("Path A (TelephonyManager.getITelephony): ${getITel.statusLog}")
            appendLine("Path B (ServiceManager -> ITelephony\$Stub): ${svcMgr.statusLog}")
            appendLine("Discovered handleUssdRequest overloads (${methods.size}): ${methods.ifEmpty { listOf("none") }.joinToString(" ; ")}")
            appendLine("UssdResponse inspection: $ussdResponseInfo")
            appendLine("Verdict: $verdict")
        }.trimEnd()

        return ExperimentalProbeReport(
            timestampMs = now,
            deviceSummary = deviceSummary,
            callPhoneGranted = callGranted,
            readPhoneStateGranted = phoneStateGranted,
            hiddenApiPolicy = hiddenPolicy,
            simDescription = simDescription,
            resolvedSubId = subId,
            resolvedSlotIndex = slotIndex,
            simAdvice = simAdvice,
            carrierConfigSummary = carrierConfig,
            readinessSummary = readiness,
            getITelephonyStatus = getITel.statusLog,
            serviceManagerStatus = svcMgr.statusLog,
            discoveredOverloads = methods,
            ussdResponseInspection = ussdResponseInfo,
            overallVerdict = verdict,
            formattedReport = formatted
        )
    }

    fun executeReadOnlyHandleUssdRequest(
        context: Context,
        requestedSimSelection: Int,
        onComplete: (ExperimentalExecutionResult) -> Unit
    ): Boolean {
        val appContext = context.applicationContext
        val requestCode = enforceReadOnlyExperimentalRequest(EXPERIMENTAL_LIVE_MENU_USSD)
        val executedAt = System.currentTimeMillis()
        val hiddenPolicy = readHiddenApiPolicy(appContext)
        val (simDescription, subId, _) = resolveTargetSim(appContext, requestedSimSelection)

        if (!UssdHelper.canPlaceCalls(appContext)) {
            onComplete(
                buildSyncFailureResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicy = hiddenPolicy.summary,
                    acquisitionPath = "Preflight",
                    overloadInvoked = "none",
                    error = SecurityException("CALL_PHONE permission is required to call ITelephony.handleUssdRequest")
                )
            )
            return false
        }

        if (SilentUssdOptimized.isExecutionInProgress() || UssdNavigationService.isBusyForBalanceCheck()) {
            onComplete(
                buildSyncFailureResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicy = hiddenPolicy.summary,
                    acquisitionPath = "Preflight collision guard",
                    overloadInvoked = "none",
                    error = IllegalStateException("Production USSD engine is currently busy; wait for it to finish before running this experiment.")
                )
            )
            return false
        }

        if (!inFlight.compareAndSet(false, true)) {
            onComplete(
                buildSyncFailureResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicy = hiddenPolicy.summary,
                    acquisitionPath = "Experimental single-flight guard",
                    overloadInvoked = "none",
                    error = IllegalStateException("An experimental handleUssdRequest call is already awaiting ResultReceiver callback or timeout.")
                )
            )
            return false
        }

        val startedElapsed = SystemClock.elapsedRealtime()
        val callbackHandled = AtomicBoolean(false)

        val baseTm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val boundTm = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { baseTm?.createForSubscriptionId(subId) }.getOrNull()
        } else null

        val getITel = tryAcquireViaTelephonyManager(baseTm, boundTm)
        val svcMgr = if (getITel.proxy == null) tryAcquireViaServiceManager() else null
        val activeAcq = if (getITel.proxy != null) getITel else (svcMgr ?: getITel)
        val proxy = activeAcq.proxy

        if (proxy == null) {
            inFlight.set(false)
            onComplete(
                buildSyncFailureResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicy = hiddenPolicy.summary,
                    acquisitionPath = "${getITel.statusLog} || ${svcMgr?.statusLog ?: "skipped"}",
                    overloadInvoked = "none",
                    error = activeAcq.error ?: IllegalStateException("Unable to acquire ITelephony binder")
                )
            )
            return false
        }

        val methods = discoverHandleUssdMethods(proxy)
        val targetMethod = pickBestHandleUssdMethod(methods, subId != null)
        if (targetMethod == null) {
            inFlight.set(false)
            onComplete(
                buildSyncFailureResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicy = hiddenPolicy.summary,
                    acquisitionPath = activeAcq.pathLabel,
                    overloadInvoked = "none (discovered: ${methods.joinToString { formatMethodSignature(it) }})",
                    error = NoSuchMethodException("No compatible ITelephony.handleUssdRequest overload found")
                )
            )
            return false
        }

        val overloadLabel = formatMethodSignature(targetMethod)
        lateinit var timeoutRunnable: Runnable

        val receiver = object : ResultReceiver(handler) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                if (!callbackHandled.compareAndSet(false, true)) return
                handler.removeCallbacks(timeoutRunnable)
                inFlight.set(false)
                val elapsed = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
                val decoded = decodeResultReceiverBundle(resultData, TelephonyManager::class.java.classLoader)
                val parsed = SimpleSignatureVerifier.firstScreenMenuOptions(decoded.bestExtractedText)
                val retryLimit = SimpleSignatureVerifier.isCarrierRetryLimitResponse(decoded.bestExtractedText)
                val resultLabel = describeResultCode(resultCode)
                val report = formatExecutionReport(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    subId = subId,
                    hiddenPolicySummary = hiddenPolicy.summary,
                    acquisitionPath = activeAcq.pathLabel,
                    overloadInvoked = overloadLabel,
                    requestStarted = true,
                    elapsedMs = elapsed,
                    timedOut = false,
                    resultCode = resultCode,
                    resultCodeLabel = resultLabel,
                    payload = decoded,
                    parsedOptions = parsed,
                    isCarrierRetryLimit = retryLimit,
                    exceptionType = null,
                    exceptionMessage = null,
                    causeChain = null
                )
                onComplete(
                    ExperimentalExecutionResult(
                        executedAtMs = executedAt,
                        requestCode = requestCode,
                        simDescription = simDescription,
                        resolvedSubId = subId,
                        hiddenApiPolicySummary = hiddenPolicy.summary,
                        acquisitionPathUsed = activeAcq.pathLabel,
                        overloadInvoked = overloadLabel,
                        requestStarted = true,
                        elapsedMs = elapsed,
                        timedOut = false,
                        resultCode = resultCode,
                        resultCodeLabel = resultLabel,
                        extractedPayload = decoded,
                        parsedMenuOptions = parsed,
                        isCarrierRetryLimit = retryLimit,
                        exceptionType = null,
                        exceptionMessage = null,
                        causeChain = null,
                        rawDiagnosticReport = report
                    )
                )
            }
        }

        timeoutRunnable = Runnable {
            if (!callbackHandled.compareAndSet(false, true)) return@Runnable
            inFlight.set(false)
            val elapsed = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
            val report = formatExecutionReport(
                executedAtMs = executedAt,
                requestCode = requestCode,
                simDescription = simDescription,
                subId = subId,
                hiddenPolicySummary = hiddenPolicy.summary,
                acquisitionPath = activeAcq.pathLabel,
                overloadInvoked = overloadLabel,
                requestStarted = true,
                elapsedMs = elapsed,
                timedOut = true,
                resultCode = null,
                resultCodeLabel = "TIMEOUT (${EXPERIMENTAL_TIMEOUT_MS}ms)",
                payload = null,
                parsedOptions = emptyList(),
                isCarrierRetryLimit = false,
                exceptionType = "Timeout",
                exceptionMessage = "No ResultReceiver callback arrived within ${EXPERIMENTAL_TIMEOUT_MS}ms",
                causeChain = null
            )
            onComplete(
                ExperimentalExecutionResult(
                    executedAtMs = executedAt,
                    requestCode = requestCode,
                    simDescription = simDescription,
                    resolvedSubId = subId,
                    hiddenApiPolicySummary = hiddenPolicy.summary,
                    acquisitionPathUsed = activeAcq.pathLabel,
                    overloadInvoked = overloadLabel,
                    requestStarted = true,
                    elapsedMs = elapsed,
                    timedOut = true,
                    resultCode = null,
                    resultCodeLabel = "TIMEOUT (${EXPERIMENTAL_TIMEOUT_MS}ms)",
                    extractedPayload = null,
                    parsedMenuOptions = emptyList(),
                    isCarrierRetryLimit = false,
                    exceptionType = "Timeout",
                    exceptionMessage = "No ResultReceiver callback arrived within ${EXPERIMENTAL_TIMEOUT_MS}ms",
                    causeChain = null,
                    rawDiagnosticReport = report
                )
            )
        }

        handler.postDelayed(timeoutRunnable, EXPERIMENTAL_TIMEOUT_MS)

        return try {
            targetMethod.isAccessible = true
            val invokeResult = invokeHandleUssdMethod(targetMethod, proxy, subId, requestCode, receiver)
            if (invokeResult is Boolean && !invokeResult) {
                if (callbackHandled.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutRunnable)
                    inFlight.set(false)
                    onComplete(
                        buildSyncFailureResult(
                            executedAtMs = executedAt,
                            requestCode = requestCode,
                            simDescription = simDescription,
                            subId = subId,
                            hiddenPolicy = hiddenPolicy.summary,
                            acquisitionPath = activeAcq.pathLabel,
                            overloadInvoked = overloadLabel,
                            error = IllegalStateException("ITelephony.handleUssdRequest synchronously returned false")
                        )
                    )
                }
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            if (callbackHandled.compareAndSet(false, true)) {
                handler.removeCallbacks(timeoutRunnable)
                inFlight.set(false)
                val root = unwrapThrowable(t)
                onComplete(
                    buildSyncFailureResult(
                        executedAtMs = executedAt,
                        requestCode = requestCode,
                        simDescription = simDescription,
                        subId = subId,
                        hiddenPolicy = hiddenPolicy.summary,
                        acquisitionPath = activeAcq.pathLabel,
                        overloadInvoked = overloadLabel,
                        error = root
                    )
                )
            }
            false
        }
    }

    private fun pickBestHandleUssdMethod(methods: List<Method>, hasSubId: Boolean): Method? {
        if (methods.isEmpty()) return null
        if (hasSubId) {
            methods.firstOrNull { m ->
                val p = m.parameterTypes
                p.size == 3 &&
                    (p[0] == Int::class.javaPrimitiveType || p[0] == Int::class.javaObjectType) &&
                    p[1] == String::class.java &&
                    ResultReceiver::class.java.isAssignableFrom(p[2])
            }?.let { return it }
        }
        methods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 2 &&
                p[0] == String::class.java &&
                ResultReceiver::class.java.isAssignableFrom(p[1])
        }?.let { return it }

        return methods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 &&
                (p[0] == Int::class.javaPrimitiveType || p[0] == Int::class.javaObjectType) &&
                p[1] == String::class.java
        }
    }

    private fun invokeHandleUssdMethod(
        method: Method,
        proxy: Any,
        subId: Int?,
        requestCode: String,
        receiver: ResultReceiver
    ): Any? {
        val params = method.parameterTypes
        val fallbackSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
        } else -1
        return when (params.size) {
            3 -> method.invoke(proxy, subId ?: fallbackSubId, requestCode, receiver)
            2 -> method.invoke(proxy, requestCode, receiver)
            else -> throw NoSuchMethodException("Unsupported parameter arity ${params.size} on ${method.name}")
        }
    }

    @Suppress("DEPRECATION")
    internal fun decodeResultReceiverBundle(
        resultData: Bundle?,
        frameworkClassLoader: ClassLoader? = null
    ): ExtractedUssdPayload {
        if (resultData == null) {
            return ExtractedUssdPayload(
                bundleKeys = emptyList(),
                bundleSummary = "null Bundle",
                rawUssdResponseClass = null,
                ussdRequestText = null,
                returnMessageText = null,
                parcelFallbackRequest = null,
                parcelFallbackMessage = null,
                extraCharSequences = emptyMap(),
                bestExtractedText = "",
                extractionNotes = listOf("ResultReceiver delivered resultData=null")
            )
        }

        val notes = mutableListOf<String>()
        val working = Bundle(resultData)
        if (frameworkClassLoader != null) {
            runCatching { working.classLoader = frameworkClassLoader }
                .onFailure { notes += "Failed to set framework ClassLoader: ${it.javaClass.simpleName}" }
        }

        val keys = runCatching { working.keySet()?.toList().orEmpty() }
            .getOrElse {
                notes += "Bundle.keySet() threw ${unwrapThrowable(it).javaClass.simpleName}: ${unwrapThrowable(it).message}"
                emptyList()
            }

        val keySummaries = mutableListOf<String>()
        val extraCharSequences = linkedMapOf<String, String>()
        var ussdResponseObj: Any? = null

        for (key in keys) {
            val valueResult = runCatching { working.get(key) }
            valueResult.onSuccess { value ->
                keySummaries += "$key=${value?.javaClass?.simpleName ?: "null"}"
                if (value is CharSequence) {
                    extraCharSequences[key] = value.toString()
                }
                if (key == EXPERIMENTAL_USSD_RESPONSE_KEY ||
                    value?.javaClass?.name?.contains("UssdResponse", ignoreCase = true) == true
                ) {
                    ussdResponseObj = value
                }
            }.onFailure { err ->
                val root = unwrapThrowable(err)
                keySummaries += "$key=<threw ${root.javaClass.simpleName}>"
                notes += "Reading key '$key' threw ${root.javaClass.simpleName}: ${root.message}"
            }
        }

        if (ussdResponseObj == null && keys.contains(EXPERIMENTAL_USSD_RESPONSE_KEY)) {
            runCatching { working.getParcelable<Parcelable>(EXPERIMENTAL_USSD_RESPONSE_KEY) }
                .onSuccess { ussdResponseObj = it }
                .onFailure { notes += "getParcelable($EXPERIMENTAL_USSD_RESPONSE_KEY) threw ${unwrapThrowable(it).javaClass.simpleName}" }
        }

        var rawClass: String? = ussdResponseObj?.javaClass?.name
        var reqText: String? = null
        var msgText: String? = null
        var parcelReq: String? = null
        var parcelMsg: String? = null

        val responseTarget = ussdResponseObj
        if (responseTarget != null) {
            reqText = tryReflectStringOrCharSequenceGetter(responseTarget, "getUssdRequest", notes)
                ?: tryReflectFieldStringOrCharSequence(responseTarget, "mUssdRequest", notes)
            msgText = tryReflectStringOrCharSequenceGetter(responseTarget, "getReturnMessage", notes)
                ?: tryReflectFieldStringOrCharSequence(responseTarget, "mReturnMessage", notes)

            if (responseTarget is Parcelable) {
                val parcelPair = extractParcelableUssdResponse(responseTarget, notes)
                parcelReq = parcelPair.first
                parcelMsg = parcelPair.second
            }
        }

        val bestText = selectBestExtractedUssdText(
            returnMessageText = msgText,
            parcelFallbackMessage = parcelMsg,
            extraCharSequences = extraCharSequences
        )

        return ExtractedUssdPayload(
            bundleKeys = keys,
            bundleSummary = if (keySummaries.isEmpty()) "empty Bundle" else keySummaries.joinToString(", "),
            rawUssdResponseClass = rawClass,
            ussdRequestText = reqText,
            returnMessageText = msgText,
            parcelFallbackRequest = parcelReq,
            parcelFallbackMessage = parcelMsg,
            extraCharSequences = extraCharSequences,
            bestExtractedText = bestText,
            extractionNotes = notes
        )
    }

    private fun tryReflectStringOrCharSequenceGetter(
        target: Any,
        methodName: String,
        notes: MutableList<String>
    ): String? = runCatching {
        val m = target.javaClass.getMethod(methodName)
        m.isAccessible = true
        (m.invoke(target) as? CharSequence)?.toString()
    }.onFailure {
        val root = unwrapThrowable(it)
        notes += "${target.javaClass.simpleName}.$methodName() failed: ${root.javaClass.simpleName}: ${root.message}"
    }.getOrNull()

    private fun tryReflectFieldStringOrCharSequence(
        target: Any,
        fieldName: String,
        notes: MutableList<String>
    ): String? = runCatching {
        val f = target.javaClass.getDeclaredField(fieldName)
        f.isAccessible = true
        (f.get(target) as? CharSequence)?.toString()
    }.onFailure {
        val root = unwrapThrowable(it)
        notes += "${target.javaClass.simpleName}.$fieldName failed: ${root.javaClass.simpleName}: ${root.message}"
    }.getOrNull()

    internal fun extractParcelableUssdResponse(
        parcelable: Parcelable,
        notes: MutableList<String> = mutableListOf()
    ): Pair<String?, String?> {
        val parcel = Parcel.obtain()
        return try {
            parcelable.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            readUssdResponseFromParcel(parcel, notes)
        } catch (t: Throwable) {
            val root = unwrapThrowable(t)
            notes += "Parcel fallback failed: ${root.javaClass.simpleName}: ${root.message}"
            null to null
        } finally {
            parcel.recycle()
        }
    }

    internal fun readUssdResponseFromParcel(
        parcel: Parcel,
        notes: MutableList<String> = mutableListOf()
    ): Pair<String?, String?> {
        val req = runCatching { parcel.readString() }.getOrNull()
        val msgPos = parcel.dataPosition()
        val msgFromCharSeq = runCatching {
            TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel)?.toString()
        }.getOrNull()
        if (!msgFromCharSeq.isNullOrBlank()) {
            return req to msgFromCharSeq
        }
        parcel.setDataPosition(msgPos)
        val msgFromString = runCatching { parcel.readString() }.getOrNull()
        if (!msgFromString.isNullOrBlank()) {
            return req to msgFromString
        }
        if (msgFromCharSeq == null && msgFromString == null) {
            notes += "Parcel fallback read null for UssdResponse returnMessage"
        }
        return req to (msgFromCharSeq ?: msgFromString)
    }

    private fun buildSyncFailureResult(
        executedAtMs: Long,
        requestCode: String,
        simDescription: String,
        subId: Int?,
        hiddenPolicy: String,
        acquisitionPath: String,
        overloadInvoked: String,
        error: Throwable
    ): ExperimentalExecutionResult {
        val chain = formatCauseChain(error)
        val report = formatExecutionReport(
            executedAtMs = executedAtMs,
            requestCode = requestCode,
            simDescription = simDescription,
            subId = subId,
            hiddenPolicySummary = hiddenPolicy,
            acquisitionPath = acquisitionPath,
            overloadInvoked = overloadInvoked,
            requestStarted = false,
            elapsedMs = 0L,
            timedOut = false,
            resultCode = null,
            resultCodeLabel = "NOT SENT (synchronous failure)",
            payload = null,
            parsedOptions = emptyList(),
            isCarrierRetryLimit = false,
            exceptionType = error.javaClass.name,
            exceptionMessage = error.message,
            causeChain = chain
        )
        return ExperimentalExecutionResult(
            executedAtMs = executedAtMs,
            requestCode = requestCode,
            simDescription = simDescription,
            resolvedSubId = subId,
            hiddenApiPolicySummary = hiddenPolicy,
            acquisitionPathUsed = acquisitionPath,
            overloadInvoked = overloadInvoked,
            requestStarted = false,
            elapsedMs = 0L,
            timedOut = false,
            resultCode = null,
            resultCodeLabel = "NOT SENT (synchronous failure)",
            extractedPayload = null,
            parsedMenuOptions = emptyList(),
            isCarrierRetryLimit = false,
            exceptionType = error.javaClass.name,
            exceptionMessage = error.message,
            causeChain = chain,
            rawDiagnosticReport = report
        )
    }

    private fun formatExecutionReport(
        executedAtMs: Long,
        requestCode: String,
        simDescription: String,
        subId: Int?,
        hiddenPolicySummary: String,
        acquisitionPath: String,
        overloadInvoked: String,
        requestStarted: Boolean,
        elapsedMs: Long,
        timedOut: Boolean,
        resultCode: Int?,
        resultCodeLabel: String,
        payload: ExtractedUssdPayload?,
        parsedOptions: List<Pair<Int, String>>,
        isCarrierRetryLimit: Boolean,
        exceptionType: String?,
        exceptionMessage: String?,
        causeChain: String?
    ): String = buildString {
        appendLine("=== EXPERIMENTAL ITelephony.handleUssdRequest EXECUTION REPORT ===")
        appendLine("Timestamp: ${formatTimestamp(executedAtMs)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}")
        appendLine("Request (read-only): $requestCode")
        appendLine("SIM: $simDescription (subId=${subId ?: "null"})")
        appendLine("Hidden API policy: $hiddenPolicySummary")
        appendLine("Binder path: $acquisitionPath")
        appendLine("Overload invoked: $overloadInvoked")
        appendLine("Request dispatched: $requestStarted")
        appendLine("Elapsed: ${elapsedMs}ms (timedOut=$timedOut)")
        appendLine("ResultReceiver resultCode: ${resultCode ?: "none"} -> $resultCodeLabel")
        if (exceptionType != null) {
            appendLine("Exception: $exceptionType: ${exceptionMessage ?: "no message"}")
            if (!causeChain.isNullOrBlank()) appendLine("Cause chain: $causeChain")
        }
        if (payload != null) {
            appendLine("Bundle keys (${payload.bundleKeys.size}): ${payload.bundleSummary}")
            appendLine("UssdResponse class: ${payload.rawUssdResponseClass ?: "absent"}")
            appendLine("UssdResponse.getUssdRequest(): ${payload.ussdRequestText ?: "null"}")
            appendLine("UssdResponse.getReturnMessage(): ${payload.returnMessageText ?: "null"}")
            appendLine("Parcel fallback request: ${payload.parcelFallbackRequest ?: "null"}")
            appendLine("Parcel fallback message: ${payload.parcelFallbackMessage ?: "null"}")
            if (payload.extraCharSequences.isNotEmpty()) {
                appendLine("Extra CharSequence/String values: ${payload.extraCharSequences}")
            }
            if (payload.extractionNotes.isNotEmpty()) {
                appendLine("Decoder notes: ${payload.extractionNotes.joinToString(" ; ")}")
            }
            appendLine("Carrier retry limit detected: $isCarrierRetryLimit")
            appendLine("Parsed menu options (${parsedOptions.size}):")
            if (parsedOptions.isEmpty()) {
                appendLine("  [none]")
            } else {
                parsedOptions.forEach { (num, label) -> appendLine("  $num. $label") }
            }
            appendLine("--- RAW EXTRACTED RESPONSE TEXT (${payload.bestExtractedText.length} chars) ---")
            appendLine(payload.bestExtractedText.ifBlank { "[EMPTY — Android delivered no message text for this ResultReceiver callback]" })
        }
    }.trimEnd()

    private fun unwrapThrowable(t: Throwable): Throwable {
        var cur = t
        while (cur is InvocationTargetException && cur.targetException != null) {
            cur = cur.targetException!!
        }
        return cur
    }

    private fun formatCauseChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            parts += "${cur.javaClass.simpleName}: ${cur.message ?: ""}".trim()
            cur = cur.cause
            depth++
        }
        return parts.joinToString(" <- ")
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
}
