package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsMarketingTemplatesTest {
    @Test fun gsmSevenUsesSingleSegmentThrough160Units() {
        val result = SmsMarketingTemplates.estimate("A".repeat(160))
        assertEquals(SmsEncoding.GSM_7, result.encoding)
        assertEquals(160, result.encodedUnits)
        assertEquals(1, result.segments)
    }

    @Test fun gsmSevenUses153UnitConcatenatedSegments() {
        assertEquals(2, SmsMarketingTemplates.estimate("A".repeat(161)).segments)
        assertEquals(2, SmsMarketingTemplates.estimate("A".repeat(306)).segments)
        assertEquals(3, SmsMarketingTemplates.estimate("A".repeat(307)).segments)
    }

    @Test fun gsmExtensionCharactersConsumeTwoSeptets() {
        val result = SmsMarketingTemplates.estimate("A".repeat(159) + "^")
        assertEquals(SmsEncoding.GSM_7, result.encoding)
        assertEquals(161, result.encodedUnits)
        assertEquals(2, result.segments)
    }

    @Test fun unicodeUses70And67CodeUnitBoundaries() {
        assertEquals(1, SmsMarketingTemplates.estimate("あ".repeat(70)).segments)
        assertEquals(2, SmsMarketingTemplates.estimate("あ".repeat(71)).segments)
        assertEquals(2, SmsMarketingTemplates.estimate("あ".repeat(134)).segments)
        assertEquals(3, SmsMarketingTemplates.estimate("あ".repeat(135)).segments)
        assertEquals(SmsEncoding.UNICODE, SmsMarketingTemplates.estimate("Hello 😊").encoding)
    }

    @Test fun manualRecipientsSplitCommaSemicolonAndNewlineSeparatedNumbers() {
        assertEquals(
            listOf("0712345678", "0723456789", "254701234567"),
            splitSmsRecipientInput("0712345678, 0723456789;\n254701234567")
        )
    }

    @Test fun manualRecipientsIgnoreDuplicateNumbersWithDifferentFormatting() {
        assertEquals(
            listOf("0712345678"),
            splitSmsRecipientInput("0712345678, +254 712 345 678")
        )
    }

    @Test fun libraryContainsTwentyTemplatesAcrossFourCategories() {
        assertEquals(20, SmsMarketingTemplates.all.size)
        assertEquals(4, SmsMarketingTemplates.all.map { it.category }.distinct().size)
        assertTrue(SmsMarketingTemplates.all.any { it.body.contains("{offers}") })
        assertTrue(SmsMarketingTemplates.all.any { it.body.contains("{till}") })
        assertTrue(SmsMarketingTemplates.all.any { it.body.contains("{help}") })
    }
}
