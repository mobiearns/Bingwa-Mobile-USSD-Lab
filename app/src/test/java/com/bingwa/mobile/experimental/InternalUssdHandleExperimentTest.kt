package com.bingwa.mobile.experimental

import android.os.Build
import android.os.Bundle
import com.bingwa.mobile.LIVE_MENU_FULL_USSD_CODE
import com.bingwa.mobile.SimpleSignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalUssdHandleExperimentTest {

    @Test
    fun fixedReadOnlyRequestMatchesSafaricomLiveMenuCode() {
        assertEquals("*180*5*2*0790993046#", EXPERIMENTAL_LIVE_MENU_USSD)
        assertEquals(LIVE_MENU_FULL_USSD_CODE, enforceReadOnlyExperimentalRequest(EXPERIMENTAL_LIVE_MENU_USSD))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonCanonicalOrReplyInputIsRejected() {
        enforceReadOnlyExperimentalRequest("*180#")
    }

    @Test
    fun hiddenApiPolicyClassificationReflectsAndroid12TargetSdk36Enforcement() {
        val blockedDefault = describeHiddenApiPolicy(
            globalPolicy = -1,
            prePPolicy = -1,
            pPolicy = -1,
            targetSdkVersion = 36,
            sdkInt = Build.VERSION_CODES.S
        )
        assertTrue(blockedDefault.enforcementLikelyActive)
        assertTrue(blockedDefault.summary.contains("blocking expected"))

        val relaxed = describeHiddenApiPolicy(
            globalPolicy = 1,
            prePPolicy = -1,
            pPolicy = -1,
            targetSdkVersion = 36,
            sdkInt = Build.VERSION_CODES.S
        )
        assertFalse(relaxed.enforcementLikelyActive)
        assertTrue(relaxed.summary.contains("relaxed"))
    }

    @Test
    fun selectBestExtractedUssdTextPrefersReturnMessageThenParcelThenExtrasAndParsesMenu() {
        val sampleMenu = "Tunukiwa\n1. Data Deals\n2. Daily Bundles\n3. Hot Minutes"
        val selected = selectBestExtractedUssdText(
            returnMessageText = null,
            parcelFallbackMessage = sampleMenu,
            extraCharSequences = mapOf("other" to "ignored")
        )
        assertEquals(sampleMenu, selected)
        val parsed = SimpleSignatureVerifier.firstScreenMenuOptions(selected)
        assertEquals(
            listOf(1 to "Data Deals", 2 to "Daily Bundles", 3 to "Hot Minutes"),
            parsed
        )
    }

    @Test
    fun resultCodeLabelsCoverMinusOneAndSuccess() {
        assertTrue(describeResultCode(-1).contains("USSD_RETURN_FAILURE"))
        assertTrue(describeResultCode(100).contains("USSD_RETURN_SUCCESS"))
        assertTrue(describeResultCode(null).contains("NONE"))
    }

    @Test
    fun decodeNullBundleReturnsSafeEmptyPayload() {
        val decoded = InternalUssdHandleExperiment.decodeResultReceiverBundle(null as Bundle?)
        assertTrue(decoded.bundleKeys.isEmpty())
        assertEquals("", decoded.bestExtractedText)
        assertTrue(decoded.extractionNotes.isNotEmpty())
    }
}
