package com.bingwa.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class UssdResponsePatternManagerTest {
    private val manager = UssdResponsePatternManager(null)

    @Test
    fun maintenanceIsNotMisclassifiedByGenericFailureWords() {
        assertEquals("UnderMaintenance", manager.determineResponseStatus(
            "Service is currently under maintenance. Please try again later."
        ))
        assertEquals("UnderMaintenance", manager.determineResponseStatus(
            "The service is temporarily unavailable due to a technical problem."
        ))
    }

    @Test
    fun explicitTransactionFailureWinsOverMaintenanceWording() {
        assertEquals("Failed", manager.determineResponseStatus(
            "Insufficient balance. Service temporarily unavailable."
        ))
    }

    @Test
    fun ordinaryTechnicalErrorRemainsFailed() {
        assertEquals("Failed", manager.determineResponseStatus(
            "Connection error. Please try again."
        ))
    }

    @Test
    fun bonusFailureKeywordsWinEvenWhenResponseContainsSuccessWords() {
        assertEquals("Failed", manager.determineResponseStatus(
            "Your transaction was declined because the balance is too low, although the request was submitted."
        ))
        assertEquals("Failed", manager.determineResponseStatus(
            "The transaction was not successful and could not be processed."
        ))
        assertEquals("Failed", manager.determineResponseStatus(
            "Your request was not processed."
        ))
    }


    @Test
    fun carrierProcessingMessagesRemainPendingRatherThanSuccess() {
        assertEquals("Pending", manager.determineResponseStatus(
            "Kindly wait as we process your request. Thank you."
        ))
        assertEquals("Pending", manager.determineResponseStatus(
            "Your request is being processed. Please wait."
        ))
    }

    @Test
    fun conciseSuccessPhrasesAreRecognizedInsideLongResponses() {
        assertEquals("Success", manager.determineResponseStatus(
            "Dear customer, your payment has been confirmed and successfully processed."
        ))
        assertEquals("Success", manager.determineResponseStatus(
            "Your purchase is complete. Thank you."
        ))
        assertEquals("Success", manager.determineResponseStatus(
            "Your purchase is complete."
        ))
    }
}
