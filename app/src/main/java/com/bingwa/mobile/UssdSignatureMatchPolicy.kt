package com.bingwa.mobile

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative policy for comparing learned USSD menu labels.
 *
 * Carrier menus vary in punctuation, spacing and accent rendering across SIMs,
 * Android dialers and locale settings. Canonicalize those presentation differences
 * before comparing labels, while preserving every letter and digit so amounts,
 * bundle sizes and option names remain distinguishable.
 */
internal object UssdSignatureMatchPolicy {
    private val whitespace = Regex("\\s+")
    private val nonAlphaNumeric = Regex("[^\\p{L}\\p{N}]+")
    private val combiningMarks = Regex("\\p{M}+")
    private val letterDigitBoundary = Regex("(\\p{L})(\\p{N})")
    private val digitLetterBoundary = Regex("(\\p{N})(\\p{L})")

    fun isExactLabelMatch(expectedLabel: String, currentLabel: String): Boolean {
        if (expectedLabel.isBlank() || currentLabel.isBlank()) return false
        return normalize(expectedLabel) == normalize(currentLabel)
    }

    fun normalize(value: String): String {
        val compatibilityNormalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
        val spaced = compatibilityNormalized
            .replace(letterDigitBoundary, "$1 $2")
            .replace(digitLetterBoundary, "$1 $2")
        return spaced
            .replace(nonAlphaNumeric, " ")
            .replace(whitespace, " ")
            .trim()
    }
}
