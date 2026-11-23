package com.bingwa.mobile

import android.content.Context
import android.util.Log

class UssdResponsePatternManager(private val context: Context?) {

    companion object {
        private const val TAG = "UssdPatternManager"
        private const val DEFAULT_MAINTENANCE_RETRY_DELAY_MS = 5_000L
        private const val DEFAULT_MAX_MAINTENANCE_RETRIES = 3
        private val WHITESPACE_REGEX = Regex("\\s+")

        val DEFAULT_SUCCESS_PATTERNS: List<String> = listOf(
            "You have successfully purchased",
            "You have successfuly purchased",
            "Kindly wait as we process your request. Thank you",
            "Kindly wait while we process your request",
            "Keep selling",
            "Submitted successfully",
            "Keep selling!!",
            "Keep selling!! Be a Bingwa Sokoni Champion",
            "Kindly wait",
            "successful",
            "success",
            "processed",
            "delivered",
            "activated",
            "confirmed",
            "bundle activated",
            "transaction successful",
            "transaction completed successfully",
            "completed successfully",
            "successfully processed",
            "payment confirmed",
            "purchase complete",
            "purchase is complete",
            "request successful",
            "request completed successfully",
            "payment processed successfully",
            "confirmed successfully",
            "bundle sent successfully",
            "thank you",
            "confirmed. Thank you"
        )
        val DEFAULT_FAILED_PATTERNS: List<String> = listOf(
            "USSD failure",
            "insufficient airtime",
            "insufficient balance",
            "Okoa Jahazi and cannot receive bundles",
            "Dear Partner",
            "Recommendation failed",
            "failed",
            "not allowed",
            "invalid",
            "error",
            "rejected",
            "samahani",
            "sorry",
            "unable",
            "cannot",
            "not eligible",
            "ineligible",
            "hawezi",
            "hajaruhusiwa",
            "huna salio",
            "insufficient",
            "not enough",
            "no airtime",
            "low balance",
            "balance is too low",
            "insufficient credit",
            "not successful",
            "unsuccessful",
            "unable to process",
            "cannot process",
            "request rejected",
            "request denied",
            "transaction declined",
            "payment declined",
            "transaction unsuccessful",
            "not processed",
            "not completed",
            "failed to process",
            "could not process",
            "could not complete",
            "insufficient funds",
            "no funds",
            "salio halitoshi",
            "salio lako halitoshi",
            "limit reached",
            "limit exceeded",
            "try again",
            "decline",
            "cancelled",
            "canceled",
            "not active",
            "wrong",
            "unavailable",
            "does not exist",
            "hakuna",
            "not registered",
            "hajasajiliwa"
        )
        private val EXPLICIT_FAILURE_PATTERNS: List<String> = listOf(
            "insufficient airtime", "insufficient balance", "insufficient credit",
            "not enough", "no airtime", "low balance", "balance is too low",
            "not successful", "unsuccessful", "unable to process", "cannot process",
            "request rejected", "request denied", "transaction declined", "payment declined",
            "transaction unsuccessful", "not processed", "not completed", "failed to process",
            "could not process", "could not complete", "insufficient funds",
            "no funds", "salio halitoshi", "salio lako halitoshi",
            "failed", "failure", "not allowed", "invalid", "rejected", "samahani",
            "sorry", "unable", "cannot", "not eligible", "ineligible", "huna salio",
            "limit reached", "limit exceeded", "decline", "cancelled", "canceled",
            "not active", "wrong", "does not exist", "hakuna", "not registered",
            "hajasajiliwa"
        )

        val DEFAULT_MAINTENANCE_PATTERNS: List<String> = listOf(
            "Service is currently under maintenance",
            "under maintenance",
            "under maintainance",
            "please try again later",
            "temporarily unavailable",
            "service unavailable",
            "technical problem"
        )
        val DEFAULT_RETRIABLE_FINAL_PATTERNS: List<String> = listOf(
            "connection problem",
            "invalid mmi",
            "mmi code",
            "network error",
            "temporary error",
            "request timeout",
            "timeout",
            "service unavailable",
            "temporarily unavailable",
            "technical problem",
            "under maintenance",
            "under maintainance",
            "maintenance",
            "maintainance",
            "session expired",
            "error"
        )
        val DEFAULT_ALREADY_RECOMMENDED_PATTERNS: List<String> = listOf(
            "has already been recommended the same product today",
            "has already been recommended",
            "Failed. 254 has already been recommended today",
            "has already been recommended today",
            "already been recommended",
            "already purchased",
            "already received",
            "same product today",
            "once per day"
        )
        val DEFAULT_FAILED_RETRY_PATTERNS: List<String> = listOf("*1#", "***#")

        /**
         * Only these phrases are treated as a *confirmed* completed purchase. Anything vaguer
         * ("thank you", "kindly wait", "processed") is deliberately excluded so a purchase can never
         * be credited on a response that merely reads like a courtesy line.
         */
        val CONFIRMED_PURCHASE_PATTERNS: List<String> = listOf(
            "you have successfully purchased",
            "you have successfuly purchased",
            "you have purchased",
            "purchase successful",
            "purchase was successful",
            "purchase complete",
            "transaction successful",
            "transaction completed",
            "you have transferred",
            "transfer successful",
            "transferred successfully",
            "transfer complete",
            "you have received",
            "bundle activated",
            "activated successfully",
            "successfully activated",
            "delivered successfully",
            "submitted successfully",
            "keep selling"
        )

        /** Any of these means the network did not sell, regardless of any other wording. */
        val CONFIRMED_PURCHASE_FAILURES: List<String> = listOf(
            "insufficient",
            "not enough",
            "no airtime",
            "balance is too low",
            "low balance",
            "failed",
            "failure",
            "unsuccessful",
            "not successful",
            "could not",
            "cannot be",
            "unable to",
            "not allowed",
            "not permitted",
            "not subscribed",
            "invalid",
            "rejected",
            "declined",
            "denied",
            "error",
            "expired",
            "cancelled",
            "canceled",
            "already purchased",
            "already received",
            "already been",
            "already activated",
            "already been recommended",
            "limit reached",
            "exceeded your",
            "out of",
            "not registered",
            "unavailable",
            "under maintenance",
            "under maintainance",
            "try again later",
            "no such",
            "not found"
        )

        /** Intermediate carrier states. Never credit on these: the sale is not confirmed yet. */
        val CONFIRMED_PURCHASE_PENDING: List<String> = listOf(
            "kindly wait",
            "please wait",
            "one moment",
            "stand by",
            "processing",
            "in progress",
            "requesting",
            "fetching",
            "loading",
            "sending",
            "working on it"
        )

        /**
         * Failure-first verdict used for anything with a financial outcome (token purchases).
         * A purchase is only confirmed when the carrier explicitly says so and nothing in the
         * response suggests a rejection, a pending state or no response at all.
         */
        fun confirmedPurchaseSuccess(response: CharSequence?): Boolean {
            val normalized = normalizeStatic(response)
            if (normalized.isBlank()) return false
            if (isPurchasePendingResponse(normalized)) return false
            if (isPurchaseFailureResponse(normalized)) return false
            return CONFIRMED_PURCHASE_PATTERNS.any { normalized.contains(normalizeStatic(it)) }
        }

        fun isPurchaseFailureResponse(response: CharSequence?): Boolean {
            val normalized = normalizeStatic(response)
            if (normalized.isBlank()) return false
            return CONFIRMED_PURCHASE_FAILURES.any { normalized.contains(normalizeStatic(it)) }
        }

        fun isPurchasePendingResponse(response: CharSequence?): Boolean {
            val normalized = normalizeStatic(response)
            if (normalized.isBlank()) return false
            return CONFIRMED_PURCHASE_PENDING.any { normalized.contains(normalizeStatic(it)) }
        }

        private fun normalizeStatic(value: CharSequence?): String =
            value?.toString()
                ?.lowercase()
                ?.replace(WHITESPACE_REGEX, " ")
                ?.trim()
                .orEmpty()
    }

    fun determineResponseStatus(response: CharSequence?): String {
        val normalized = normalize(response)
        if (normalized.isBlank()) return "Failed"
        return when {
            matchesFailedRetryPattern(normalized) -> "Failed"
            matchesAlreadyRecommendedPattern(normalized) -> "Pending"
            matchAny(normalized, EXPLICIT_FAILURE_PATTERNS) -> "Failed"
            matchesMaintenancePattern(normalized) -> "UnderMaintenance"
            isPurchasePendingResponse(normalized) -> "Pending"
            matchesFailedPattern(normalized) -> "Failed"
            matchesSuccessPattern(normalized) -> "Success"
            else -> "Failed"
        }
    }

    /**
     * Check if the response looks like a valid USSD response (not empty/truncated content)
     */
    private fun looksLikeValidResponse(normalized: String): Boolean {
        if (normalized.length < 3) return false
        // If it has actual message content that's not just punctuation, treat it as valid
        val hasContent = normalized.any { it.isLetterOrDigit() }
        val hasMultipleWords = normalized.split("\\s+".toRegex()).size > 2
        return hasContent && hasMultipleWords
    }

    fun getSuccessPatterns() = DEFAULT_SUCCESS_PATTERNS
    fun getFailedPatterns() = DEFAULT_FAILED_PATTERNS
    fun getMaintenancePatterns() = DEFAULT_MAINTENANCE_PATTERNS
    fun getRetriableFinalPatterns() = DEFAULT_RETRIABLE_FINAL_PATTERNS
    fun getAlreadyRecommendedPatterns() = DEFAULT_ALREADY_RECOMMENDED_PATTERNS
    fun getFailedRetryPatterns() = DEFAULT_FAILED_RETRY_PATTERNS
    fun getMaxMaintenanceRetries() = DEFAULT_MAX_MAINTENANCE_RETRIES
    fun getMaintenanceRetryDelayMs() = DEFAULT_MAINTENANCE_RETRY_DELAY_MS

    fun matchesSuccessPattern(response: CharSequence?) = matchAny(response, getSuccessPatterns())
    fun matchesFailedPattern(response: CharSequence?) = matchAny(response, getFailedPatterns())
    fun matchesMaintenancePattern(response: CharSequence?) = matchAny(response, getMaintenancePatterns())
    fun matchesRetriableFinalPattern(response: CharSequence?) = matchAny(response, getRetriableFinalPatterns())
    fun matchesAlreadyRecommendedPattern(response: CharSequence?) = matchAny(response, getAlreadyRecommendedPatterns())
    fun matchesFailedRetryPattern(response: CharSequence?) = matchAny(response, getFailedRetryPatterns())

    fun saveSuccessPatterns(patterns: List<String>) { Log.w(TAG, "saveSuccessPatterns ignored; using built-in patterns") }
    fun saveFailedPatterns(patterns: List<String>) { Log.w(TAG, "saveFailedPatterns ignored; using built-in patterns") }
    fun saveMaintenancePatterns(patterns: List<String>) { Log.w(TAG, "saveMaintenancePatterns ignored; using built-in patterns") }
    fun saveAlreadyRecommendedPatterns(patterns: List<String>) { Log.w(TAG, "saveAlreadyRecommendedPatterns ignored; using built-in patterns") }
    fun saveFailedRetryPatterns(patterns: List<String>) { Log.w(TAG, "saveFailedRetryPatterns ignored; using built-in patterns") }
    fun saveMaxMaintenanceRetries(max: Int) { Log.w(TAG, "saveMaxMaintenanceRetries ignored; using built-in value") }
    fun saveMaintenanceRetryDelayMs(delayMs: Long) { Log.w(TAG, "saveMaintenanceRetryDelayMs ignored; using built-in value") }

    fun resetToDefaults() { Log.i(TAG, "resetToDefaults ignored; using built-in patterns") }

    private fun matchAny(response: CharSequence?, patterns: List<String>): Boolean {
        val normalizedResponse = normalize(response)
        if (normalizedResponse.isBlank()) return false
        return patterns.any { normalizedResponse.contains(normalize(it)) }
    }

    private fun normalize(value: CharSequence?): String =
        value?.toString()
            ?.lowercase()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            .orEmpty()
}
