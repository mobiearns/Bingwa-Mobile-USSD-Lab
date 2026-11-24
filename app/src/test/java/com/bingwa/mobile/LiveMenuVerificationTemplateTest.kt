package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMenuVerificationTemplateTest {
    @Test fun liveMenuAlwaysUsesCompleteSafaricomRequestNeverShort180() {
        assertEquals("*180*5*2*0790993046#", resolveLiveMenuUssdRequest())
        assertEquals("*180*5*2*0790993046#", LIVE_MENU_FULL_USSD_CODE)
        assertNotEquals("*180#", resolveLiveMenuUssdRequest())
        assertNull(SimpleSignatureVerifier.previewCode("*180#"))
    }

    @Test fun liveMenuVerificationTemplateCannotBeOverriddenWithCustomOrShortCodes() {
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate(""))
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate("   "))
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate("*180#"))
        assertEquals("*180*5*2*pn#", resolveLiveMenuVerificationTemplate("  *444*5*1*pn#  "))
    }

    @Test fun genericAndroidFailureExplainsKnownAndroid12GsmCausesWithoutInventingCarrierText() {
        val message = describeSilentUssdFailureCode(android.telephony.TelephonyManager.USSD_RETURN_FAILURE)
        assertTrue(message.contains("pending", ignoreCase = true))
        assertTrue(message.contains("FDN", ignoreCase = true))
        assertTrue(message.contains("no carrier response text", ignoreCase = true))
    }

    @Test fun multiSecondOverTheAirFailureIdentifiesAndroid12InteractiveUssdModeRequestLimit() {
        val message = describeSilentUssdFailureCode(
            android.telephony.TelephonyManager.USSD_RETURN_FAILURE,
            elapsedMs = 5_098L
        )
        assertTrue(message.contains("5098ms", ignoreCase = true))
        assertTrue(message.contains("USSD_MODE_REQUEST", ignoreCase = true))
        assertTrue(message.contains("USSD_MODE_NOTIFY", ignoreCase = true))
        assertTrue(message.contains("no carrier response text", ignoreCase = true))
    }
}
