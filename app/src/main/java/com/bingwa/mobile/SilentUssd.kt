package com.bingwa.mobile

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Legacy entry point unified with [SilentUssdOptimized] so all silent USSD callers
 * share a single native TelephonyManager.sendUssdRequest() pipeline and single-flight lock.
 */
object SilentUssd {

    fun execute(
        context: Context,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
        preserveRawResponse: Boolean = false
    ): Boolean = SilentUssdOptimized.execute(
        context = context,
        ussdCode = ussdCode,
        onSuccess = onSuccess,
        onFailure = onFailure,
        preserveRawResponse = preserveRawResponse
    )

    fun execute(
        telephonyManager: TelephonyManager,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
        preserveRawResponse: Boolean = false
    ): Boolean = SilentUssdOptimized.execute(
        telephonyManager = telephonyManager,
        ussdCode = ussdCode,
        onSuccess = onSuccess,
        onFailure = onFailure,
        preserveRawResponse = preserveRawResponse
    )

    fun isExecutionInProgress(): Boolean = SilentUssdOptimized.isExecutionInProgress()

    fun isSilentUssdSupported(context: Context): Boolean = SilentUssdOptimized.isSilentUssdSupported(context)
}
