package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleSignatureVerifierTest {
    private fun offer(position: String = "1", label: String = "1GB Daily Ksh 20") = OfferItem(
        id = 1,
        name = "Daily 1GB",
        price = 20,
        ussdCode = "*544*pn*1#",
        simpleSignatureEnabled = true,
        simpleLearnedSignature = listOf(
            UssdSignatureStep(
                stepIndex = 0,
                expectedInput = position,
                selectedOptionLabel = label,
                menuOptionsSnapshot = listOf(label)
            )
        )
    )

    @Test fun previewUsesConfiguredPhoneNumberForPnVerification() {
        assertEquals("*544*0790993046#", SimpleSignatureVerifier.previewCode("*544*pn*1#"))
        assertEquals("*544*0790993046#", SimpleSignatureVerifier.previewCode("*544*[pn]*1#"))
        assertEquals("*180*5*2*0790993046#", SimpleSignatureVerifier.previewCode("*180*5*2*pn*5*1#"))
        assertEquals("*180*5*2*0790993046#", SimpleSignatureVerifier.previewCode("*180*5*2*0790993046#"))
        assertEquals("*180*5*2*0790993046#", SimpleSignatureVerifier.previewCode("*180*5*2*0712345678#"))
        assertEquals("*180*5*2*0790993046#", SimpleSignatureVerifier.previewCode("*180*5*2*0790993046*5*1#"))
    }


    @Test fun safaricomLiveMenuUsesCorrectChainedOrderAndStopsAtPhonePrompt() {
        assertEquals(
            "*180*5*2*0790993046#",
            SimpleSignatureVerifier.previewCode("*180*2*5*pn*1*2#")
        )
        assertEquals(
            "*180*5*2*0790993046#",
            SimpleSignatureVerifier.previewCode("*180*5*2*pn*1*2#")
        )
    }

    @Test fun previewStripsFormattingAndRejectsDecoratedDialStrings() {
        assertEquals("*180*5*2*0790993046#", SimpleSignatureVerifier.previewCode("  *180 * 5 * 2 * pn #  "))
        assertEquals("*544*0790993046#", SimpleSignatureVerifier.previewCode("*544*pn%23"))
        assertNull(SimpleSignatureVerifier.previewCode("Dial *180*2*5*pn# now"))
        assertNull(SimpleSignatureVerifier.previewCode("*180*2*5*pn# extra"))
        assertNull(SimpleSignatureVerifier.previewCode("*180#"))
    }

    @Test fun nativeBoundaryAcceptsOnlyAlreadyCanonicalDialStrings() {
        assertEquals("*180*5*2*0790993046#", canonicalSilentUssdDialString("*180*5*2*0790993046#"))
        assertNull(canonicalSilentUssdDialString(" *180*5*2*0790993046#"))
        assertNull(canonicalSilentUssdDialString("*180 *5*2*0790993046#"))
        assertNull(canonicalSilentUssdDialString("*180*5*2*0790993046"))
        assertNull(canonicalSilentUssdDialString("*180*5*2*pn#"))
        assertNull(canonicalSilentUssdDialString("tel:*180*5*2*0790993046#"))
    }
    @Test fun identifiesSafaricomSubscriberRegistrationServiceResponse() {
        val response = "Dear Agent, the Subscriber Registration function has been deactivated. Kindly use the Jiandikishe App for Sim Activation"
        assertEquals(true, SimpleSignatureVerifier.isSafaricomServiceErrorResponse(response))
        assertEquals(false, SimpleSignatureVerifier.isSafaricomServiceErrorResponse("1. 1GB Daily\\n2. 250MB 24 Hours"))
    }

    @Test fun positionMarkerKeepsPrefixOnlyPreview() {
        assertEquals("*544#", SimpleSignatureVerifier.previewCode("*544*position*1#"))
        assertNull(SimpleSignatureVerifier.previewCode("*544*1#"))
    }

    @Test fun parsesDiverseSafaricomMenuFormatsAndIgnoresNonMenuLines() {
        val raw = """
            Bingwa Sokoni Bundles for 0712345678:
            Valid on 23-11-2026 at 12:30 PM
            1. 1.25GB @ Ksh 55
            2) 250MB 24hrs
            3 - 1GB + 100mins
            4: All in One Bundle
            5 Airtime Topup
            6 1GB Daily
            1.5GB Extra not an option
            20 Kshs minimum balance
            24 hours validity
            0712345678
            Reply with option 1-7
            98. More
        """.trimIndent()

        val parsed = SimpleSignatureVerifier.menuOptions(raw)
        assertEquals(
            listOf(
                1 to "1.25GB @ Ksh 55",
                2 to "250MB 24hrs",
                3 to "1GB + 100mins",
                4 to "All in One Bundle",
                5 to "Airtime Topup",
                6 to "1GB Daily",
                98 to "More"
            ),
            parsed
        )
    }

    @Test fun readsFlattenedFirstScreenMenuWithoutSendingAnySelection() {
        val raw = "Welcome 1. 1GB Daily @ Ksh 20 2. 250MB valid for 24 hours 3. Airtime"
        assertEquals(
            listOf(
                1 to "1GB Daily @ Ksh 20",
                2 to "250MB valid for 24 hours",
                3 to "Airtime"
            ),
            SimpleSignatureVerifier.firstScreenMenuOptions(raw)
        )
    }

    @Test fun firstScreenReaderExcludesNextScreenPositions() {
        val raw = "1. 1GB Daily\\n2. 250MB 24hrs\\n7. Weekly\\n8. More bundles"
        assertEquals(
            listOf(1 to "1GB Daily", 2 to "250MB 24hrs", 7 to "Weekly"),
            SimpleSignatureVerifier.firstScreenMenuOptions(raw)
        )
    }

    @Test fun carrierRetryLimitResponseReturnsZeroMenuOptionsAndUnavailableStatus() {
        val raw = "Max Number of Menu Retries is Reached"
        assertEquals(true, SimpleSignatureVerifier.isCarrierRetryLimitResponse(raw))
        assertEquals(emptyList<Pair<Int, String>>(), SimpleSignatureVerifier.menuOptions(raw))
        val result = SimpleSignatureVerifier.verify(offer(), "*180*5*2*pn*5*1#", raw)
        assertEquals(SimpleSignatureVerifier.Status.UNAVAILABLE, result.status)
    }

    @Test fun verifiesSameOfferEvenWhenDisplayedPriceChanges() {
        val result = SimpleSignatureVerifier.verify(
            offer(),
            "*544*pn*1#",
            "Welcome\n1. 1 GB Daily Ksh 30\n2. Weekly Bundle"
        )
        assertEquals(SimpleSignatureVerifier.Status.VERIFIED, result.status)
        assertEquals(1, result.expectedPosition)
    }

    @Test fun changedPositionIdentityFailsClosed() {
        val result = SimpleSignatureVerifier.verify(
            offer(),
            "*544*pn*1#",
            "Welcome\n1. Weekly Bundle\n2. 1 GB Daily"
        )
        assertEquals(SimpleSignatureVerifier.Status.MISMATCH, result.status)
    }

    @Test fun unsupportedPositionIsUnavailable() {
        val result = SimpleSignatureVerifier.verify(offer(position = "8"), "*544*pn*1#", "1. 1 GB Daily")
        assertEquals(SimpleSignatureVerifier.Status.UNAVAILABLE, result.status)
    }

    @Test fun missingSavedSignatureIsUnavailable() {
        val result = SimpleSignatureVerifier.verify(offer().copy(simpleLearnedSignature = emptyList()), "*544*pn*1#", "1. 1 GB Daily")
        assertEquals(SimpleSignatureVerifier.Status.UNAVAILABLE, result.status)
    }

    @Test fun unreadableFirstScreenIsUnavailable() {
        val result = SimpleSignatureVerifier.verify(offer(), "*544*pn*1#", "Welcome to bundles")
        assertEquals(SimpleSignatureVerifier.Status.UNAVAILABLE, result.status)
    }

    @Test fun aliasesMatchOfferIdentityWithoutUsingPrice() {
        val configured = offer(label = "1GB Daily Ksh 20").copy(simpleSignatureAliases = "1GB, Daily, 1 hour")
        val result = SimpleSignatureVerifier.verify(
            configured,
            "*544*pn*1#",
            "Welcome\n1. Daily 1GB · 1 hour Ksh 35\n2. 250MB · 24 hours"
        )
        assertEquals(SimpleSignatureVerifier.Status.VERIFIED, result.status)
    }

    @Test fun unrelatedOfferDoesNotMatchAlias() {
        val configured = offer(label = "1GB Daily").copy(simpleSignatureAliases = "1GB, Daily, 1 hour")
        val result = SimpleSignatureVerifier.verify(
            configured,
            "*544*pn*1#",
            "Welcome\n1. 250MB · 24 hours\n2. 1GB Daily"
        )
        assertEquals(SimpleSignatureVerifier.Status.MISMATCH, result.status)
    }

    @Test fun strictMatchingRequiresExactIdentityText() {
        val configured = offer(label = "1GB Daily").copy(simpleSignatureSensitivity = "STRICT")
        val result = SimpleSignatureVerifier.verify(
            configured,
            "*544*pn*1#",
            "Welcome\n1. 1GB Daily bundle\n2. Weekly"
        )
        assertEquals(SimpleSignatureVerifier.Status.MISMATCH, result.status)
    }

    @Test fun extracts132CharPreviewSafelyAcrossLengthBoundariesWhileKeepingFullParsing() {
        assertEquals("", extractSilentUssdPreview(null))
        assertEquals("", extractSilentUssdPreview(""))
        assertEquals("", extractSilentUssdPreview("   \n\r\n  "))
        assertEquals(emptyList<Pair<Int, String>>(), SimpleSignatureVerifier.menuOptions(null))
        assertEquals(emptyList<Pair<Int, String>>(), SimpleSignatureVerifier.firstScreenMenuOptions(null))
        assertEquals(emptyList<Pair<Int, String>>(), SimpleSignatureVerifier.firstScreenMenuOptions(""))

        val shortText = "1. 1GB Daily\n2. 250MB 24hrs"
        assertEquals(shortText, extractSilentUssdPreview(shortText))

        val exact132 = "1. 1GB Daily @20\n" + "A".repeat(132 - "1. 1GB Daily @20\n".length)
        assertEquals(132, exact132.length)
        assertEquals(exact132, extractSilentUssdPreview(exact132))

        val longResponse = buildString {
            appendLine("Select Bingwa offer:")
            appendLine("1. 1GB Daily Valid 24hrs @ Ksh 20")
            appendLine("2. 250MB Valid 24hrs @ Ksh 20")
            appendLine("3. 1.25GB Valid Till Midnight @ Ksh 55")
            appendLine("4. 2GB Weekly Data @ Ksh 100")
            appendLine("5. 350MB Valid 7 Days @ Ksh 49")
            appendLine("6. All In One Hourly Bundle @ Ksh 19")
            appendLine("7. Unlimited Night Pass @ Ksh 50")
            append("8. More")
        }
        val preview = extractSilentUssdPreview(longResponse)
        assertEquals(132, preview.length)
        assertTrue(preview.startsWith("Select Bingwa offer:\n1. 1GB Daily Valid 24hrs @ Ksh 20\n2. 250MB"))
        // Underlying full response remains intact for menu parsing past character 132.
        val parsed = SimpleSignatureVerifier.firstScreenMenuOptions(longResponse)
        assertEquals(7, parsed.size)
        assertEquals(7 to "Unlimited Night Pass @ Ksh 50", parsed.last())
    }

    @Test fun normalizesRealAndEscapedNewlinesWithoutIndexMismatchOnFlattenedResponses() {
        val escapedCrlf = "Bingwa\\r\\n1. 1GB Daily\\r\\n2. 250MB 24hrs\\r\\n3. Weekly bundles"
        val realCrlf = "Bingwa\r\n1. 1GB Daily\r\n2. 250MB 24hrs\r\n3. Weekly bundles"
        val escapedLf = "Bingwa\\n1. 1GB Daily\\n2. 250MB 24hrs\\n3. Weekly bundles"

        val expectedNormalized = "Bingwa\n1. 1GB Daily\n2. 250MB 24hrs\n3. Weekly bundles"
        assertEquals(expectedNormalized, normalizeSilentUssdResponse(escapedCrlf))
        assertEquals(expectedNormalized, normalizeSilentUssdResponse(realCrlf))
        assertEquals(expectedNormalized, normalizeSilentUssdResponse(escapedLf))

        val expectedOptions = listOf(1 to "1GB Daily", 2 to "250MB 24hrs", 3 to "Weekly bundles")
        assertEquals(expectedOptions, SimpleSignatureVerifier.firstScreenMenuOptions(escapedCrlf))
        assertEquals(expectedOptions, SimpleSignatureVerifier.firstScreenMenuOptions(realCrlf))
        assertEquals(expectedOptions, SimpleSignatureVerifier.firstScreenMenuOptions(escapedLf))

        // Regression: escaped sequences shortening the string must never cause index shift/out-of-bounds
        // when the flattened regex branch matches on normalizedResponse.
        val flattenedWithEscapes = "Welcome\\\\n\\r\\n\\r\\n1. 1GB Daily @20 2. 250MB 24hrs 3. 1 hour bundle"
        assertEquals(
            listOf(1 to "1GB Daily @20", 2 to "250MB 24hrs", 3 to "1 hour bundle"),
            SimpleSignatureVerifier.firstScreenMenuOptions(flattenedWithEscapes)
        )
    }

    @Test fun duplicateMenuPositionsKeepFirstOccurrenceDeterministically() {
        val duplicateRaw = "1. 1GB Daily\n2. 250MB 24hrs\n1. Duplicate Should Not Overwrite"
        assertEquals(
            listOf(1 to "1GB Daily", 2 to "250MB 24hrs"),
            SimpleSignatureVerifier.firstScreenMenuOptions(duplicateRaw)
        )
    }
}
