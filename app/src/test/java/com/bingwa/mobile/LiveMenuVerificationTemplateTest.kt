package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMenuVerificationTemplateTest {
    @Test fun liveMenuHasFixedSafaricomVerificationTemplateWithoutAnOffer() {
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate(""))
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate("   "))
    }

    @Test fun savedWorkspaceTemplateOverridesTheBuiltInDefault() {
        assertEquals("*444*5*1*pn#", resolveLiveMenuVerificationTemplate("  *444*5*1*pn#  "))
    }

    @Test fun genericAndroidFailureExplainsKnownAndroid12GsmCausesWithoutInventingCarrierText() {
        val message = describeSilentUssdFailureCode(android.telephony.TelephonyManager.USSD_RETURN_FAILURE)
        assertTrue(message.contains("pending", ignoreCase = true))
        assertTrue(message.contains("FDN", ignoreCase = true))
        assertTrue(message.contains("no carrier response text", ignoreCase = true))
    }
}
