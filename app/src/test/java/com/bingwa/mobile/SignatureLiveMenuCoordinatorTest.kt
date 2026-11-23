package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureLiveMenuCoordinatorTest {

    private fun sampleValidState(nowMs: Long = 1_000_000L): SignatureLiveMenuState {
        val raw = "1. 1GB Daily @20\n2. 250MB 24hrs @20\n3. Airtime"
        val options = SimpleSignatureVerifier.menuOptions(raw)
        val started = reduceLiveMenuStart(
            previous = SignatureLiveMenuState(),
            startedAtWallMs = nowMs - 400L,
            simDescription = "SIM slot 1 (subscription ID redacted)",
            telephonyReadiness = "SIM slot: 1; SIM state: READY; CALL_PHONE=granted",
            carrierConfig = "TelephonyManager USSD API: allowed",
            sourceOfferName = "Daily 1GB"
        )
        return reduceLiveMenuSuccess(
            previous = started,
            rawResponse = raw,
            parsedOptions = options,
            elapsedMs = 400L,
            completedAtWallMs = nowMs
        )
    }

    @Test fun validMenuSuccessStoresMenuAndClearsStaleAndErrorState() {
        val state = sampleValidState()
        assertFalse(state.loading)
        assertFalse(state.stale)
        assertFalse(state.isCarrierRetryLimit)
        assertFalse(state.autoRefreshPausedByCarrierLimit)
        assertEquals(3, state.validOptions.size)
        assertEquals(3, state.lastParsedOptionCount)
        assertEquals("CALLBACK SUCCESS · VALID MENU", state.lastAttemptOutcome)
        assertTrue(state.rawResponseReceived)
        assertEquals("", state.lastAttemptError)
    }

    @Test fun carrierRetryLimitPreservesLastValidMenuMarksStaleAndPausesAutoRefresh() {
        val prior = sampleValidState(nowMs = 1_000_000L)
        val started = reduceLiveMenuStart(
            previous = prior,
            startedAtWallMs = 1_720_000L,
            simDescription = "SIM slot 1 (subscription ID redacted)",
            telephonyReadiness = "READY",
            carrierConfig = "allowed",
            sourceOfferName = "Daily 1GB"
        )
        assertTrue(started.loading)
        assertEquals(prior.validOptions, started.validOptions)

        val afterRetryLimit = reduceLiveMenuSuccess(
            previous = started,
            rawResponse = "Max Number of Menu Retries is Reached",
            parsedOptions = emptyList(),
            elapsedMs = 540L,
            completedAtWallMs = 1_720_540L
        )

        assertFalse(afterRetryLimit.loading)
        assertTrue(afterRetryLimit.stale)
        assertTrue(afterRetryLimit.previousMenuRetained)
        assertTrue(afterRetryLimit.isCarrierRetryLimit)
        assertTrue(afterRetryLimit.autoRefreshPausedByCarrierLimit)
        assertTrue(afterRetryLimit.rawResponseReceived)
        assertEquals("Max Number of Menu Retries is Reached", afterRetryLimit.lastAttemptRawResponse)
        assertEquals(0, afterRetryLimit.lastParsedOptionCount)
        assertEquals("CALLBACK SUCCESS · CARRIER RETRY LIMIT", afterRetryLimit.lastAttemptOutcome)

        // Last successfully captured menu must remain completely intact.
        assertEquals(prior.validResponse, afterRetryLimit.validResponse)
        assertEquals(prior.validOptions, afterRetryLimit.validOptions)
        assertEquals(prior.lastSuccessAt, afterRetryLimit.lastSuccessAt)
    }

    @Test fun failureCallbackWithoutResponseTextRetainsPriorMenuAndReportsHonestly() {
        val prior = sampleValidState(nowMs = 1_000_000L)
        val started = reduceLiveMenuStart(
            previous = prior,
            startedAtWallMs = 1_800_000L,
            simDescription = "SIM slot 1 (subscription ID redacted)",
            telephonyReadiness = "READY",
            carrierConfig = "allowed",
            sourceOfferName = "Daily 1GB"
        )
        val failed = reduceLiveMenuFailure(
            previous = started,
            error = "USSD_RETURN_FAILURE (-1): Android supplied no carrier response text.",
            elapsedMs = 620L,
            requestStarted = true,
            completedAtWallMs = 1_800_620L
        )

        assertFalse(failed.loading)
        assertTrue(failed.stale)
        assertTrue(failed.previousMenuRetained)
        assertFalse(failed.rawResponseReceived)
        assertEquals("", failed.lastAttemptRawResponse)
        assertEquals("CALLBACK FAILURE", failed.lastAttemptOutcome)
        assertEquals(prior.validOptions, failed.validOptions)
    }

    @Test fun overlappingBlockedRequestReportsBlockedAndRetainsValidMenu() {
        val prior = sampleValidState()
        val blocked = reduceLiveMenuBlocked(
            previous = prior,
            reason = "USSD execution already in progress"
        )

        assertTrue(blocked.blockedBecauseRunning)
        assertTrue(blocked.previousMenuRetained)
        assertEquals("BLOCKED · REQUEST ALREADY RUNNING", blocked.lastAttemptOutcome)
        assertEquals(prior.validOptions, blocked.validOptions)
    }

    @Test fun twelveMinuteAutoRefreshHonorsCooldownAndCarrierRetryPause() {
        val empty = SignatureLiveMenuState()
        assertTrue(shouldAutoRefreshLiveMenu(empty, nowMs = 5_000L))

        val recent = sampleValidState(nowMs = 1_000_000L)
        assertFalse(shouldAutoRefreshLiveMenu(recent, nowMs = 1_000_000L + 5 * 60_000L))
        assertEquals(7 * 60, secondsUntilNextAutoRefresh(recent, nowMs = 1_000_000L + 5 * 60_000L))

        assertTrue(shouldAutoRefreshLiveMenu(recent, nowMs = 1_000_000L + LIVE_MENU_AUTO_REFRESH_INTERVAL_MS))

        val pausedByRetryLimit = reduceLiveMenuSuccess(
            previous = recent,
            rawResponse = "Max Number of Menu Retries is Reached",
            parsedOptions = emptyList(),
            elapsedMs = 540L,
            completedAtWallMs = 1_000_540L
        )
        assertFalse(
            shouldAutoRefreshLiveMenu(
                pausedByRetryLimit,
                nowMs = 1_000_540L + LIVE_MENU_AUTO_REFRESH_INTERVAL_MS * 3
            )
        )
        assertEquals(0, secondsUntilNextAutoRefresh(pausedByRetryLimit, nowMs = 1_500_000L))
    }

    @Test fun diagnosticSummaryContainsAllRequiredEvidenceFields() {
        val prior = sampleValidState(nowMs = 1_000_000L)
        val retryState = reduceLiveMenuSuccess(
            previous = prior,
            rawResponse = "Max Number of Menu Retries is Reached",
            parsedOptions = emptyList(),
            elapsedMs = 540L,
            completedAtWallMs = 1_000_540L
        )
        val report = formatLiveMenuDiagnosticsSummary(retryState)
        assertTrue(report.contains("Request method: Android TelephonyManager.sendUssdRequest"))
        assertTrue(report.contains("Menu source (redacted):"))
        assertTrue(report.contains("Selected SIM slot & status: SIM slot 1"))
        assertTrue(report.contains("Request duration: 540 ms"))
        assertTrue(report.contains("Callback outcome: CALLBACK SUCCESS · CARRIER RETRY LIMIT"))
        assertTrue(report.contains("Raw response text received: Yes (37 chars)"))
        assertTrue(report.contains("Parsed menu options: 0"))
        assertTrue(report.contains("Carrier retry-limit response: Yes"))
        assertTrue(report.contains("Previous valid menu retained: Yes (3 options)"))
        assertTrue(report.contains("Blocked due to running request: No"))
        assertTrue(report.contains("Max Number of Menu Retries is Reached"))
    }

    @Test fun selectedAdvancedOfferCannotOverrideSimpleLiveMenuSource() {
        val advancedSms = OfferItem(
            id = 0,
            name = "20 SMS Daily",
            price = 5,
            ussdCode = "*188*10*1*1*pn*1*2#",
            category = OFFER_CATEGORY_SMS,
            executionMode = OFFER_EXECUTION_MODE_ADVANCED
        )
        val simpleData = OfferItem(
            id = 1,
            name = "1GB 1 Hour",
            price = 19,
            ussdCode = "*180*5*2*pn*5*1#",
            category = OFFER_CATEGORY_DATA,
            executionMode = OFFER_EXECUTION_MODE_SIMPLE
        )

        val picked = selectLiveMenuSourceOffer(listOf(advancedSms, simpleData), selectedOfferId = 0)
        assertEquals(1, picked?.id)
        assertEquals("*180*5*2*0712345678#", SimpleSignatureVerifier.previewCode(picked?.ussdCode.orEmpty()))
    }

    @Test fun liveMenuDoesNotFallBackToAdvancedOfferWhenNoSimpleOfferExists() {
        val advancedSms = OfferItem(
            id = 0,
            name = "20 SMS Daily",
            price = 5,
            ussdCode = "*188*10*1*1*pn*1*2#",
            category = OFFER_CATEGORY_SMS,
            executionMode = OFFER_EXECUTION_MODE_ADVANCED
        )

        assertEquals(null, selectLiveMenuSourceOffer(listOf(advancedSms), selectedOfferId = 0))
    }

    @Test fun liveMenuDefaultsToSimpleOfferInsteadOfCheaperAdvancedOffer() {
        val advancedSms = OfferItem(
            id = 0,
            name = "20 SMS Daily",
            price = 5,
            ussdCode = "*188*10*1*1*pn*1*2#",
            category = OFFER_CATEGORY_SMS,
            executionMode = OFFER_EXECUTION_MODE_ADVANCED
        )
        val simpleData = OfferItem(
            id = 1,
            name = "1GB 1 Hour",
            price = 19,
            ussdCode = "*180*5*2*pn*5*1#",
            category = OFFER_CATEGORY_DATA,
            executionMode = OFFER_EXECUTION_MODE_SIMPLE
        )

        val picked = selectLiveMenuSourceOffer(listOf(advancedSms, simpleData), selectedOfferId = -1)
        assertEquals(1, picked?.id)
        assertEquals("*180*2*5*[pn]#", redactPreviewPattern(SimpleSignatureVerifier.previewCode(simpleData.ussdCode).orEmpty()))
    }

    @Test fun liveMenuPrioritizesEnabledStar180SimpleOffer() {
        val disabledSimple = OfferItem(
            id = 10,
            name = "Disabled Flex",
            price = 15,
            ussdCode = "*444*5*1*3*pn*2*1#",
            category = OFFER_CATEGORY_DATA,
            executionMode = OFFER_EXECUTION_MODE_SIMPLE,
            enabled = false
        )
        val enabledNon180 = OfferItem(
            id = 11,
            name = "Enabled Flex",
            price = 21,
            ussdCode = "*444*5*1*3*pn*2*1#",
            category = OFFER_CATEGORY_DATA,
            executionMode = OFFER_EXECUTION_MODE_SIMPLE,
            enabled = true
        )
        val enabled180 = OfferItem(
            id = 12,
            name = "1GB 1 Hour",
            price = 19,
            ussdCode = "*180*5*2*pn*5*1#",
            category = OFFER_CATEGORY_DATA,
            executionMode = OFFER_EXECUTION_MODE_SIMPLE,
            enabled = true
        )

        val picked = selectLiveMenuSourceOffer(
            listOf(disabledSimple, enabledNon180, enabled180),
            selectedOfferId = -1
        )
        assertEquals(12, picked?.id)
    }
}
