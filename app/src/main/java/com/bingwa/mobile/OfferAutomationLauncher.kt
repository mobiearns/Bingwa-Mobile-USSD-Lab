package com.bingwa.mobile

import android.content.Context
import android.content.Intent

fun Context.startOfferAutomation(
    offer: OfferItem?,
    phoneNumber: String,
    txId: Int,
    finalCode: String,
    mode: String = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
    signatureLearning: Boolean = false,
    executionPriority: String = USSD_EXECUTION_PRIORITY_SPECIAL,
    returnToAppAggressively: Boolean = true,
    tokenPreDeducted: Boolean = false
) {
    val requestedMode = mode.ifBlank { offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE }
    // A stored signature is data, not consent to enforce it. The per-offer switch is authoritative.
    val protectionEnabled = offer?.signatureDetectionEnabled == true && !signatureLearning
    val simpleProtectionEnabled = requestedMode.equals(OFFER_EXECUTION_MODE_SIMPLE, ignoreCase = true) &&
        offer?.simpleSignatureEnabled == true && !signatureLearning
    val advancedProtectionEnabled = protectionEnabled && !simpleProtectionEnabled
    // Manual protection/learning stays on the existing Advanced navigator. Simple is silent preflight only.
    val effectiveMode = if (signatureLearning || advancedProtectionEnabled) {
        OFFER_EXECUTION_MODE_ADVANCED
    } else {
        requestedMode
    }
    val effectiveSignatureEnabled = advancedProtectionEnabled && !signatureLearning

    // INSTANT EXECUTION FAST-PATH:
    // When executing an ADVANCED flow (guided USSD with steps/signatures), arm UssdNavigationService
    // and trigger the dialer immediately. This ensures sub-frame execution latency (<5ms)
    // and eliminates foreground-service startup delays and Android 12 background launch restrictions.
    var instantDialStarted = false
    if (effectiveMode.equals(OFFER_EXECUTION_MODE_ADVANCED, ignoreCase = true)) {
        instantDialStarted = UssdNavigationService.armAndDialImmediately(
            context = this,
            code = finalCode,
            phoneNumber = phoneNumber,
            txId = txId,
            offer = offer,
            signatureEnabled = effectiveSignatureEnabled,
            signatureMode = offer?.signatureAction ?: "STOP",
            signatureLearning = signatureLearning,
            returnToAppAggressively = returnToAppAggressively
        )
    }

    ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
        putExtra("mode", effectiveMode)
        putExtra("code", finalCode)
        putExtra("phoneNumber", phoneNumber)
        putExtra("txId", txId)
        putExtra("signatureLearning", signatureLearning)
        putExtra("executionPriority", executionPriority)
        putExtra("returnToAppAggressively", returnToAppAggressively)
        putExtra("tokenPreDeducted", tokenPreDeducted)
        putExtra("instantDialStarted", instantDialStarted)
        if (offer != null) {
            putExtra("offerId", offer.id)
            putExtra("offerName", offer.name)
            putExtra("simSelection", offer.simSelection)
            putExtra("signatureEnabled", effectiveSignatureEnabled)
            putExtra("simpleSignatureEnabled", simpleProtectionEnabled)
            putExtra("signatureMode", offer.signatureAction)
        }
    })
}
