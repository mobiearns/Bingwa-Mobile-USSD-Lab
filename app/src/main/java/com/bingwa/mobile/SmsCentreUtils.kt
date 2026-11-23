package com.bingwa.mobile

import android.content.Context
import android.provider.ContactsContract

/**
 * SMS Centre data formatting and Kenyan contact/phone utilities.
 * These functions deliberately avoid logging provider values or contact details.
 */
internal fun findSmsMetric(response: org.json.JSONObject, aliases: List<String>): String? {
    val wanted = aliases.map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }.toSet()
    fun scalar(value: Any?): String? = when (value) {
        null, org.json.JSONObject.NULL -> null
        is Number -> value.toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
    fun search(value: Any?, depth: Int): String? {
        if (depth > 5) return null
        when (value) {
            is org.json.JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (normalized in wanted) scalar(value.opt(key))?.let { return it }
                }
                val nestedPriority = listOf("data", "account", "wallet", "credits", "units", "balance", "result", "details", "sms")
                for (key in nestedPriority) search(value.opt(key), depth + 1)?.let { return it }
                val names = value.keys()
                while (names.hasNext()) search(value.opt(names.next()), depth + 1)?.let { return it }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) search(value.opt(index), depth + 1)?.let { return it }
            }
        }
        return null
    }
    return search(response, 0)
}

internal fun findSmsBalance(response: org.json.JSONObject): Pair<String, String>? {
    val preferredKeys = listOf(
        "remaining_units" to "Remaining SMS units",
        "sms_units_remaining" to "Remaining SMS units",
        "available_units" to "Available SMS units",
        "remaining_sms" to "Remaining SMS units",
        "sms_balance" to "SMS balance",
        "available_balance" to "Available credit",
        "available_credits" to "Available SMS credits",
        "available_credit" to "Available credit",
        "account_credit" to "Account credit",
        "credit_balance" to "Credit balance",
        "wallet_balance" to "Wallet balance",
        "account_balance" to "Account balance",
        "balance" to "Account balance",
        "credits" to "Available credits",
        "credit" to "Available credit",
        "remaining_balance" to "Remaining balance",
        "provider_balance" to "Remaining balance",
        "remaining" to "Remaining balance",
        "total_units" to "Total SMS units",
        "used_units" to "Used SMS units",
        "units" to "Available units"
    )
    val normalizedKeys = preferredKeys.associateBy { it.first.lowercase().replace(Regex("[^a-z0-9]"), "") }
    fun scalar(value: Any?): String? = when (value) {
        null, org.json.JSONObject.NULL -> null
        is Number -> value.toString()
        is String -> value.takeIf { it.isNotBlank() }
        else -> null
    }
    fun search(value: Any?, depth: Int): Pair<String, String>? {
        if (depth > 6) return null
        when (value) {
            is org.json.JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val entry = normalizedKeys[key.lowercase().replace(Regex("[^a-z0-9]"), "")] ?: continue
                    scalar(value.opt(key))?.let { return entry.second to it }
                }
                val priority = listOf("data", "units", "account", "wallet", "credits", "balance", "result", "details", "profile", "sms")
                for (key in priority) search(value.opt(key), depth + 1)?.let { return it }
                val names = value.keys()
                while (names.hasNext()) search(value.opt(names.next()), depth + 1)?.let { return it }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) search(value.opt(index), depth + 1)?.let { return it }
            }
        }
        return null
    }
    return search(response, 0)
}


internal fun formatTalkSasaUnits(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return "Balance unavailable"

    // TalkSasa reports SMS credits as units, never as Kenyan shillings.
    // Strip any currency label accidentally included by a provider wrapper.
    val withoutCurrency = trimmed
        .replace(Regex("(?i)\\b(?:ksh|kes)\\b\\s*"), "")
        .replace(Regex("(?i)\\s*\\b(?:ksh|kes)\\b"), "")
        .trim()

    val number = Regex("-?\\d+(?:[,.]\\d+)?").find(withoutCurrency)?.value
        ?.replace(",", "")
        ?.toDoubleOrNull()
    val display = if (number != null && number.isFinite()) {
        if (number % 1.0 == 0.0) number.toLong().toString()
        else java.text.DecimalFormat("#,##0.##").format(number)
    } else withoutCurrency

    return if (display.contains("unit", ignoreCase = true)) display else "$display units"
}

internal fun lookupSmsContactName(context: android.content.Context, normalizedNumber: String): String? = runCatching {
    val target = normalizedNumber.filter(Char::isDigit).takeLast(9)
    if (target.isBlank()) return@runCatching null
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null
    )
    cursor?.use {
        while (it.moveToNext()) {
            val name = it.getString(0).orEmpty().trim()
            val digits = it.getString(1).orEmpty().filter(Char::isDigit)
            if (name.isNotBlank() && digits.takeLast(9) == target) return@runCatching name
        }
    }
    null
}.getOrNull()

internal fun readSmsPhoneContacts(context: android.content.Context): List<SavedContact> {
    val rows = mutableListOf<SavedContact>()
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )
    cursor?.use {
        while (it.moveToNext()) {
            val name = it.getString(0).orEmpty()
            val digits = it.getString(1).orEmpty().filter(Char::isDigit)
            val phone = when {
                digits.startsWith("254") -> "0" + digits.drop(3)
                digits.startsWith("0") -> digits
                digits.length == 9 -> "0" + digits
                else -> ""
            }
            if (phone.matches(Regex("^0[17]\\d{8}$"))) rows += SavedContact(name, phone)
        }
    }
    return rows.distinctBy { it.phone }
}

/** Split a manual recipient entry into individual phone numbers. Commas, semicolons and new lines are supported. */
internal fun splitSmsRecipientInput(raw: String): List<String> =
    raw.split(Regex("[,;\\n\\r]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { normalizeKenyanSmsNumber(it) ?: it.filter(Char::isDigit).ifBlank { it.lowercase() } }

/** Normalize Kenyan mobile numbers into the international format expected by SMS providers. */
internal fun normalizeKenyanSmsNumber(raw: String): String? {
    val digits = raw.filter(Char::isDigit)
    val normalized = when {
        digits.startsWith("254") -> digits
        digits.startsWith("0") -> "254" + digits.drop(1)
        digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254$digits"
        else -> return null
    }
    return normalized.takeIf { it.matches(Regex("^254[17]\\d{8}$")) }
}
