package com.bingwa.mobile

/**
 * Fail-closed verifier for Simple mode. It compares only the saved menu identity
 * at the saved position; price is intentionally excluded.
 */
internal object SimpleSignatureVerifier {
    enum class Status { VERIFIED, MISMATCH, UNAVAILABLE }

    data class Result(
        val status: Status,
        val message: String,
        val previewCode: String = "",
        val expectedPosition: Int? = null
    )

    // Fixed recipient used only for the silent Simple-signature preflight request.
    // It is never substituted into the real purchase request.
    private const val VERIFICATION_PHONE_NUMBER = "0712345678"

    private val delimitedOptionLine = Regex("""^\s*([1-9]\d?)(\s*)([\).:\-])(\s*)(.+?)\s*$""")
    private val spaceOptionLine = Regex("""^\s*([1-9]\d?)\s+(.+?)\s*$""")
    private val timeLikeRemainder = Regex("""^\d{2}(?:\s*(?:am|pm))?(?:\s|$).*""", RegexOption.IGNORE_CASE)
    private val rangePriceRemainder = Regex("""^\d+(?:[.,]\d+)?\s*(?:kshs?|kes|sh|/=|/-)\b.*""", RegexOption.IGNORE_CASE)
    private val spaceRejectedStart = Regex(
        """^(?:kshs?|kes|sh|bob|usd|eur|/=|/-|kb|mb|gb|tb|hr|hrs|hour|hours|min|mins|minute|minutes|sec|secs|second|seconds|day|days|wk|wks|week|weeks|mo|mos|month|months|yr|yrs|year|years|jan|january|feb|february|mar|march|apr|april|may|jun|june|jul|july|aug|august|sep|sept|september|oct|october|nov|november|dec|december|of|for|per|to|by|x|times|people|users|offers)(?:\b|\s|$).*""",
        RegexOption.IGNORE_CASE
    )
    private val dateLikeLine = Regex("""^\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}(?:\s.*)?$""")
    private val currencyAmount = Regex("""(?i)\b(?:kshs?|kes)\s*[:.]?\s*[0-9][0-9,.]*\b|(?<!\w)@[ ]*[0-9][0-9,.]*""")

    fun isSafaricomServiceErrorResponse(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("subscriber registration function") ||
            (lower.contains("has been deactivated") && lower.contains("jiandikishe")) ||
            (lower.contains("sim activation") && lower.contains("jiandikishe"))
    }

    fun isCarrierRetryLimitResponse(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("max number of menu retries is reached") ||
            lower.contains("maximum number of menu retries") ||
            lower.contains("menu retries is reached") ||
            lower.contains("max menu retries")
    }

    fun previewCode(fullCode: String): String? {
        // Saved templates may contain visual whitespace, but the result returned
        // from this function is always a pure, numeric Android dial string.
        val normalized = fullCode.trim().filterNot(Char::isWhitespace).replace("%23", "#", ignoreCase = true)
        if (!Regex("""^\\*[A-Za-z0-9\\[\\]]+(?:\\*[A-Za-z0-9\\[\\]]+)*#?$""").matches(normalized)) return null
        val parts = normalized.removePrefix("*").removeSuffix("#").split("*")
        if (parts.isEmpty() || parts.any(String::isBlank)) return null

        fun isPhoneMarker(value: String): Boolean =
            value.equals("pn", true) || value.equals("[pn]", true) || value == VERIFICATION_PHONE_NUMBER
        fun isPositionMarker(value: String): Boolean =
            value.equals("position", true) || value.equals("[position]", true)

        // Exactly one placeholder/phone marker is allowed. Text or displaced
        // menu steps before the marker are rejected rather than guessed at.
        val markerIndices = parts.indices.filter { isPhoneMarker(parts[it]) || isPositionMarker(parts[it]) }
        if (markerIndices.size != 1) return null
        val markerIndex = markerIndices.single()
        val prefix = parts.take(markerIndex)
        val suffix = parts.drop(markerIndex + 1)
        if (prefix.isEmpty() || prefix.any { !it.all(Char::isDigit) }) return null
        if (suffix.any { !it.all(Char::isDigit) }) return null

        if (prefix.first() == "180") {
            // Accept only the current Safaricom route (2 -> 5) and the known
            // legacy ordering (5 -> 2), then always emit the canonical 2 -> 5.
            if (prefix != listOf("180", "2", "5") && prefix != listOf("180", "5", "2")) return null
            if (!isPhoneMarker(parts[markerIndex])) return null
            return "*180*2*5*$VERIFICATION_PHONE_NUMBER#"
        }

        val prefixCode = "*${prefix.joinToString("*")}"
        val code = when {
            isPositionMarker(parts[markerIndex]) -> "$prefixCode#"
            else -> "$prefixCode*$VERIFICATION_PHONE_NUMBER#"
        }
        return code.takeIf { Regex("""^\\*\\d+(?:\\*\\d+)*#$""").matches(it) }
    }

    fun menuOptions(response: String): List<Pair<Int, String>> {
        if (response.isBlank() || isCarrierRetryLimitResponse(response)) return emptyList()
        return response.lineSequence().mapNotNull { rawLine ->
            parseOptionLine(rawLine)
        }.distinctBy { it.first }.toList()
    }

    private fun parseOptionLine(rawLine: String): Pair<Int, String>? {
        val line = rawLine.trim()
        if (line.isBlank()) return null
        if (dateLikeLine.matches(line)) return null

        val delimited = delimitedOptionLine.matchEntire(line)
        if (delimited != null) {
            val number = delimited.groupValues[1].toIntOrNull() ?: return null
            val preSpace = delimited.groupValues[2]
            val delim = delimited.groupValues[3]
            val postSpace = delimited.groupValues[4]
            val label = delimited.groupValues[5].trim()
            if (label.isBlank() || !label.any { it.isLetter() }) return null

            when (delim) {
                "." -> {
                    // Reject decimals/dates such as "1.5GB" or "10.50 Ksh" (dot directly before digit).
                    if (postSpace.isEmpty() && label.first().isDigit()) return null
                }
                ":" -> {
                    // Reject clock times such as "12:30" or "12:30 PM".
                    if (postSpace.isEmpty() && timeLikeRemainder.matches(label)) return null
                }
                "-" -> {
                    // Reject hyphenated compounds ("1-hour"), ranges ("1-7 days"), and dates ("23-11-2026").
                    if (preSpace.isEmpty() && postSpace.isEmpty()) return null
                    if (rangePriceRemainder.matches(label)) return null
                }
            }
            return number to label
        }

        val spaced = spaceOptionLine.matchEntire(line) ?: return null
        val number = spaced.groupValues[1].toIntOrNull() ?: return null
        val label = spaced.groupValues[2].trim()
        if (label.isBlank() || !label.any { it.isLetter() }) return null
        if (spaceRejectedStart.matches(label)) return null
        if (label.startsWith("/=") || label.startsWith("/-")) return null
        val firstChar = label.first()
        val dataBundleToken = Regex("""^\d+(?:\.\d+)?\s*(?:kb|mb|gb|tb|mins?|sms)\b.*""", RegexOption.IGNORE_CASE)
        if (!firstChar.isLetter() && !dataBundleToken.matches(label)) return null
        return number to label
    }

    fun verify(offer: OfferItem?, fullCode: String, response: String): Result {
        val preview = previewCode(fullCode)
            ?: return Result(Status.UNAVAILABLE, "Signature not available: saved USSD code has no pn position marker.")
        val actualOffer = offer
            ?: return Result(Status.UNAVAILABLE, "Signature not available: offer could not be loaded.", preview)
        val step = actualOffer.simpleLearnedSignature.firstOrNull { it.stepIndex == 0 }
            ?: actualOffer.simpleLearnedSignature.firstOrNull()
            ?: return Result(Status.UNAVAILABLE, "Signature not available: no Simple first-screen signature is saved.", preview)

        val position = step.expectedInput.trim().toIntOrNull()
            ?: return Result(Status.UNAVAILABLE, "Signature not available: saved menu position is invalid.", preview)
        if (position !in 1..7) {
            return Result(Status.UNAVAILABLE, "Signature not available: supported menu positions are 1–7.", preview, position)
        }
        if (response.isBlank()) {
            return Result(Status.UNAVAILABLE, "Signature not available: network returned no first-screen menu.", preview, position)
        }
        if (isCarrierRetryLimitResponse(response)) {
            return Result(
                Status.UNAVAILABLE,
                "Signature not available: carrier returned retry limit ('Max Number of Menu Retries is Reached').",
                preview,
                position
            )
        }
        val options = menuOptions(response).toMap()
        if (options.isEmpty()) {
            return Result(Status.UNAVAILABLE, "Signature not available: first-screen response has no readable numbered options.", preview, position)
        }
        val liveLabel = options[position]
            ?: return Result(Status.MISMATCH, "Signature mismatch: expected menu position $position is not present.", preview, position)
        val expectedLabel = step.selectedOptionLabel.ifBlank {
            step.menuOptionsSnapshot.getOrNull(position - 1).orEmpty()
        }
        if (expectedLabel.isBlank()) {
            return Result(Status.UNAVAILABLE, "Signature not available: saved offer identity is incomplete.", preview, position)
        }
        val liveIdentity = normalizeIdentity(liveLabel)
        val identityCandidates = buildList {
            add(expectedLabel)
            add(actualOffer.name)
            addAll(actualOffer.simpleSignatureAliases.split(Regex("[,;\\n]")).map(String::trim).filter(String::isNotBlank))
        }.map(::normalizeIdentity).filter { it.isNotBlank() }.distinct()
        val liveTokens = liveIdentity.split(' ').filter(String::isNotBlank).toSet()
        val matched = identityCandidates.any { candidate ->
            val candidateTokens = candidate.split(' ').filter(String::isNotBlank).toSet()
            when (actualOffer.simpleSignatureSensitivity.uppercase()) {
                "STRICT" -> candidate == liveIdentity
                "FLEXIBLE" -> {
                    val overlap = candidateTokens.intersect(liveTokens).size
                    candidate == liveIdentity || (candidateTokens.size >= 2 && overlap >= maxOf(2, kotlin.math.ceil(candidateTokens.size * 0.7).toInt()))
                }
                else -> candidate == liveIdentity || (candidateTokens.size >= 2 && liveTokens.containsAll(tokensOrEmpty(candidateTokens)))
            }
        }
        if (liveIdentity.isBlank() || !matched) {
            return Result(Status.MISMATCH, "Signature mismatch: the offer identity at position $position has changed.", preview, position)
        }
        return Result(Status.VERIFIED, "Signature verified at menu position $position.", preview, position)
    }

    private fun tokensOrEmpty(tokens: Set<String>): Set<String> = tokens

    private fun normalizeIdentity(value: String): String =
        UssdSignatureMatchPolicy.normalize(currencyAmount.replace(value, " "))
}
