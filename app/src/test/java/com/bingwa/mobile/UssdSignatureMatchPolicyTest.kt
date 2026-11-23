package com.bingwa.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UssdSignatureMatchPolicyTest {
    @Test
    fun sameNumericPositionDoesNotOverrideChangedLabel() {
        assertFalse(UssdSignatureMatchPolicy.isExactLabelMatch("Daily Data Bundle", "Weekly Voice Bundle"))
    }

    @Test
    fun exactLabelCanBeRecognizedAfterOptionMoves() {
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch(" Daily   Data Bundle ", "daily data bundle"))
    }

    @Test
    fun punctuationAndFormattingDifferencesDoNotBreakExactMatch() {
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch("Daily-Data (1 GB)!", "daily data 1 gb"))
    }

    @Test
    fun unitAndNumberBoundaryDifferencesDoNotBreakExactMatch() {
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch("Daily 1GB @ 20/=", "daily 1 gb 20"))
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch("100MB 24hrs", "100 mb 24 hrs"))
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch("50mins", "50 mins"))
        assertFalse(UssdSignatureMatchPolicy.isExactLabelMatch("1GB", "2GB"))
    }

    @Test
    fun accentDifferencesDoNotBreakExactMatch() {
        assertTrue(UssdSignatureMatchPolicy.isExactLabelMatch("Crédit airtime", "Credit airtime"))
    }

    @Test
    fun differentBundleSizesNeverBecomeAnExactMatch() {
        assertFalse(UssdSignatureMatchPolicy.isExactLabelMatch("Daily Data 1 GB", "Daily Data 2 GB"))
    }

}
