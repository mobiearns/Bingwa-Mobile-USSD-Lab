package com.bingwa.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class SmsProvider { SMSHUB, TALKSASA }
internal data class SmsConnection(val provider: SmsProvider, val token: String, val senderId: String)
internal data class SmsSendResult(val accepted: Boolean, val messageId: String = "", val detail: String = "")

internal object SmsCredentialVault {
    private const val PREFS = "sms_centre_secure"
    private const val KEY_ALIAS = "bingwa_sms_centre_v1"
    fun save(context: Context, provider: SmsProvider, token: String, sender: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", encrypt(token.trim())).putString("provider", provider.name)
            .putString("sender", sender.trim()).apply()
    }
    fun load(context: Context): SmsConnection? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = p.getString("token", null) ?: return null
        val provider = runCatching { SmsProvider.valueOf(p.getString("provider", "") ?: "") }.getOrNull() ?: return null
        val token = runCatching { decrypt(encrypted) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return SmsConnection(provider, token, p.getString("sender", "") ?: "")
    }
    fun updateSender(context: Context, sender: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("sender", sender.trim()).apply()
    }
    fun saveBackend(context: Context, baseUrl: String, adminKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("backend_url", encrypt(baseUrl.trim().trimEnd('/')))
            .putString("backend_admin", encrypt(adminKey.trim())).apply()
    }
    fun backend(context: Context): Pair<String, String>? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString("backend_url", null) ?: return null
        val key = p.getString("backend_admin", null) ?: return null
        return runCatching { decrypt(url) to decrypt(key) }.getOrNull()
    }
    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
}

internal object SmsProviderDetector {
    fun normalize(token: String): String = token.trim().removePrefix("\uFEFF").trim()
        .replace(Regex("(?i)^Bearer\\s+"), "").trim().trim('"').trim('\'')

    fun detect(token: String): SmsProvider? {
        val value = normalize(token)
        if (value.startsWith("smshub_", true)) return SmsProvider.SMSHUB
        // TalkSasa's published integration uses opaque bearer keys, without a documented prefix.
        return value.takeIf { it.length >= 12 }?.let { SmsProvider.TALKSASA }
    }
}

internal object SmsProviderClient {
    // SMSHub East Africa customer API documented at bulksms.bingwastore.com.
    private const val HUB = "https://bulksms.bingwastore.com"
    private const val TALK = "https://bulksms.talksasa.com/api/v3"

    suspend fun verify(connection: SmsConnection): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (connection.provider == SmsProvider.SMSHUB) {
                val account = request(HUB + "/api/me", connection.token, "GET", null)
                val balance = account.opt("balance")?.toString()
                val email = account.optString("email")
                "SMSHub connected" + (if (email.isNotBlank()) " as $email" else "") +
                    (if (!balance.isNullOrBlank()) " · $balance units available" else "")
            } else {
                val profile = request(TALK + "/me", connection.token, "GET", null)
                val units = request(TALK + "/balance", connection.token, "GET", null)
                "TalkSasa API token verified" +
                    profile.optString("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
                    findTalkSasaUnits(units)?.let { " · $it SMS units remaining" }.orEmpty()
            }
        }
    }

    suspend fun verifySender(connection: SmsConnection, senderId: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val candidate = senderId.trim()
                require(candidate.matches(Regex("^[A-Za-z0-9_-]{1,11}$"))) {
                    "Sender ID must be 1–11 letters, numbers, hyphens or underscores."
                }
                val profile = if (connection.provider == SmsProvider.SMSHUB) {
                    request(HUB + "/api/me", connection.token, "GET", null)
                } else {
                    request(TALK + "/me", connection.token, "GET", null)
                }
                val approved = findSenderIds(profile)
                if (approved.isEmpty()) {
                    throw IllegalStateException(
                        "The provider account response did not include an approved-sender list. Bingwa cannot verify '$candidate' yet. Check the sender ID in your provider dashboard; it has not been saved as verified."
                    )
                }
                if (approved.none { it.equals(candidate, ignoreCase = true) }) {
                    throw IllegalArgumentException(
                        "'$candidate' is not listed as approved by your provider. Available sender IDs: ${approved.joinToString()}."
                    )
                }
                "${connection.provider.name} confirmed '$candidate' as an approved sender ID."
            }
        }

    private fun findSenderIds(source: JSONObject): List<String> {
        val senderKeys = setOf("senderids", "approvedsenderids", "sendernames", "alphanumericsenders", "senders")
        val found = linkedSetOf<String>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 7) return
            when (value) {
                is JSONObject -> {
                    val iterator = value.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val item = value.opt(key)
                        val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
                        if (normalized in senderKeys) {
                            when (item) {
                                is org.json.JSONArray -> for (i in 0 until item.length()) {
                                    item.opt(i)?.toString()?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{1,11}$")) }?.let(found::add)
                                }
                                is String -> item.split(',', ';').map(String::trim)
                                    .filter { it.matches(Regex("^[A-Za-z0-9_-]{1,11}$")) }.forEach(found::add)
                            }
                        } else walk(item, depth + 1)
                    }
                }
                is org.json.JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), depth + 1)
            }
        }
        walk(source, 0)
        return found.toList()
    }

    suspend fun send(connection: SmsConnection, to: String, message: String): Result<SmsSendResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(to.matches(Regex("^254[17]\\d{8}$"))) { "Use Kenyan international format: 2547XXXXXXXX." }
                require(message.isNotBlank()) { "Message cannot be empty." }
                require(connection.senderId.isNotBlank()) { "Enter an approved sender ID." }
                if (connection.provider == SmsProvider.SMSHUB) {
                    val recipient = if (to.startsWith("+")) to else "+$to"
                    val body = JSONObject()
                        .put("sender_id", connection.senderId)
                        .put("recipients", org.json.JSONArray().put(recipient))
                        .put("message", message)
                    val data = request(HUB + "/api/sms/send", connection.token, "POST", body)
                    val state = data.optString("status").lowercase()
                    val providerReason = listOf("message", "error", "detail", "reason", "description")
                        .firstNotNullOfOrNull { key -> data.optString(key).takeIf { it.isNotBlank() } }.orEmpty()
                    SmsSendResult(
                        state !in setOf("failed", "rejected", "error"),
                        data.optString("campaign_id").ifBlank { data.optString("id") },
                        "SMSHub campaign response" + if (state.isBlank()) "" else " · $state" +
                            if (providerReason.isBlank()) "" else " · $providerReason"
                    )
                } else {
                    val body = JSONObject()
                        .put("recipient", to)
                        .put("sender_id", connection.senderId)
                        .put("type", "plain")
                        .put("message", message)
                    val data = request(TALK + "/sms/send", connection.token, "POST", body)
                    val state = data.optString("status").lowercase()
                    val providerReason = listOf("message", "error", "detail", "reason", "description")
                        .firstNotNullOfOrNull { key -> data.optString(key).takeIf { it.isNotBlank() } }.orEmpty()
                    SmsSendResult(
                        state !in setOf("failed", "rejected", "error"),
                        data.optString("uid").ifBlank { data.optString("message_uid").ifBlank { data.optString("id") } },
                        "TalkSasa response received" + if (state.isBlank()) "" else " · $state" +
                            if (providerReason.isBlank()) "" else " · $providerReason"
                    )
                }
            }
        }

    /** Appends live provider checks to Setup Doctor's existing copy report. */
    suspend fun buildDiagnosticReport(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("SMS CENTRE DIAGNOSTICS")
            val connection = SmsCredentialVault.load(context)
            if (connection == null) {
                appendLine("Provider connection: NOT CONNECTED (no saved provider credential)")
                appendLine("Balance: NOT TESTED")
                appendLine("Sender ID: NOT TESTED")
                return@withContext toString()
            }
            val providerName = if (connection.provider == SmsProvider.SMSHUB) "SMSHub" else "TalkSasa"
            val base = if (connection.provider == SmsProvider.SMSHUB) HUB else TALK
            val balancePath = if (connection.provider == SmsProvider.SMSHUB) "/api/balance" else "/balance"
            val profilePath = if (connection.provider == SmsProvider.SMSHUB) "/api/me" else "/me"
            appendLine("Provider: $providerName")
            appendLine("Credential: saved (value hidden)")
            appendLine("Balance endpoint: $base$balancePath")
            appendLine("Profile/sender metadata endpoint: $base$profilePath")
            appendLine("Selected sender ID: " + connection.senderId.ifBlank { "(none saved)" })
            val balanceResult = balance(connection)
            if (balanceResult.isSuccess) {
                val payload = balanceResult.getOrNull() ?: JSONObject()
                appendLine("Balance request: SUCCESS")
                appendLine("Balance response keys: " + payload.keys().asSequence().sorted().joinToString().ifBlank { "(none)" })
                listOf("credits", "balance", "remaining_units", "total_units", "used_units", "unit_type").forEach { key ->
                    if (payload.has(key)) appendLine("  $key = " + payload.opt(key))
                }
                if (connection.provider == SmsProvider.TALKSASA) {
                    payload.optJSONObject("units")?.let { appendLine("TalkSasa /balance keys: " + it.keys().asSequence().sorted().joinToString()) }
                }
                val hasDisplayBalance = payload.has("credits") || payload.has("balance") ||
                    payload.has("remaining_units") || payload.has("provider_balance") ||
                    payload.has("remaining_balance") ||
                    payload.optJSONObject("units")?.let { it.has("remaining_balance") || it.has("remaining_units") || it.has("balance") } == true
                if (!hasDisplayBalance) {
                    appendLine("Balance interpretation: response received but no recognized display field found.")
                } else if (payload.has("provider_balance") || payload.has("remaining_balance")) {
                    appendLine("Balance interpretation: provider-reported balance available; unit type not specified.")
                }
            } else {
                appendLine("Balance request: FAILED")
                appendLine("Balance error: " + (balanceResult.exceptionOrNull()?.message ?: "Unknown provider error"))
            }
            val profileResult = runCatching { request(base + profilePath, connection.token, "GET", null) }
            val profile = profileResult.getOrNull()
            if (profile != null) {
                appendLine("Profile request: SUCCESS")
                appendLine("Profile response keys: " + profile.keys().asSequence().sorted().joinToString().ifBlank { "(none)" })
                appendLine("Sender IDs discovered: " + findSenderIds(profile).joinToString().ifBlank { "(none in profile response)" })
                val check = if (connection.senderId.isBlank()) null else verifySender(connection, connection.senderId)
                appendLine("Selected sender verification: " + when {
                    connection.senderId.isBlank() -> "NOT TESTED (no sender selected)"
                    check?.isSuccess == true -> "APPROVED — " + check.getOrNull()
                    else -> "NOT CONFIRMED — " + (check?.exceptionOrNull()?.message ?: "No verification result")
                })
            } else {
                appendLine("Profile request: FAILED")
                appendLine("Profile error: " + (profileResult.exceptionOrNull()?.message ?: "Unknown provider error"))
                appendLine("Sender IDs discovered: unavailable because profile request failed")
            }
            appendLine("Security: API token and Authorization header are intentionally excluded.")
        }
    }

    suspend fun balance(connection: SmsConnection): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            if (connection.provider == SmsProvider.SMSHUB) {
                val result = request(HUB + "/api/balance", connection.token, "GET", null)
                // Profile retrieval is supplementary: a profile endpoint outage must
                // never hide an otherwise valid live balance.
                runCatching { request(HUB + "/api/me", connection.token, "GET", null) }
                    .getOrNull()?.let { result.put("profile", it) }
                result.put("provider", "SMSHub")
                result
            } else {
                // /balance supplies SMS units; /me supplies account identity/details.
                // Keep profile retrieval optional so balance display remains resilient.
                val units = request(TALK + "/balance", connection.token, "GET", null)
                val profile = runCatching {
                    request(TALK + "/me", connection.token, "GET", null)
                }.getOrNull()

                val result = JSONObject()
                    .put("provider", "TalkSasa")
                    .put("units", units)
                if (profile != null) result.put("profile", profile)

                fun findScalar(source: JSONObject, aliases: Set<String>): Any? {
                    fun walk(value: Any?, depth: Int): Any? {
                        if (depth > 8) return null
                        when (value) {
                            is JSONObject -> {
                                val keys = value.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    val normalized = key.lowercase().replace(Regex("[^a-z0-9]"), "")
                                    val item = value.opt(key)
                                    if (normalized in aliases && item != JSONObject.NULL &&
                                        (item is Number || item is String) &&
                                        item.toString().isNotBlank()
                                    ) return item
                                }
                                val priority = listOf(
                                    "data", "balance", "units", "sms", "result",
                                    "details", "account", "profile"
                                )
                                for (key in priority) {
                                    walk(value.opt(key), depth + 1)?.let { return it }
                                }
                                val names = value.keys()
                                while (names.hasNext()) {
                                    walk(value.opt(names.next()), depth + 1)?.let { return it }
                                }
                            }
                            is org.json.JSONArray -> {
                                for (index in 0 until value.length()) {
                                    walk(value.opt(index), depth + 1)?.let { return it }
                                }
                            }
                        }
                        return null
                    }
                    return walk(source, 0)
                }

                // The published TalkSasa SDK expects total_units, used_units and
                // remaining_units. Accept additional live-account aliases as well.
                val unitFields = mapOf(
                    "total_units" to setOf(
                        "totalunits", "smsunitstotal", "purchasedunits", "totalsms",
                        "totalcredits", "total"
                    ),
                    "used_units" to setOf(
                        "usedunits", "smsunitsused", "consumedunits", "usedsms",
                        "usedcredits", "creditsused"
                    ),
                    "remaining_units" to setOf(
                        "remainingunits", "smsunitsremaining", "availableunits",
                        "remainingsms", "smscredits", "unitsremaining", "smsbalance",
                        "remaining", "available", "balance", "units", "credits", "credit",
                        "unitbalance", "smsunit", "smsunits", "smsunitbalance", "smscredit",
                        "smscreditbalance", "availablecredits", "creditsremaining",
                        "remainingcredits", "availablesms", "currentsmsbalance",
                        "currentunits", "balanceunits", "smsunitsbalance"
                    )
                )
                unitFields.forEach { (target, aliases) ->
                    findScalar(units, aliases)?.let { result.put(target, it) }
                }

                if (!result.has("remaining_units") &&
                    result.has("total_units") && result.has("used_units")
                ) {
                    val total = result.optDouble("total_units", Double.NaN)
                    val used = result.optDouble("used_units", Double.NaN)
                    if (total.isFinite() && used.isFinite()) {
                        result.put("remaining_units", (total - used).coerceAtLeast(0.0))
                    }
                }

                // Some TalkSasa accounts return a generic remaining_balance field.
                // Preserve it as a provider-reported balance: its name does not establish
                // that the value is an SMS-unit count, so never map it to remaining_units.
                findScalar(units, setOf("remainingbalance", "providerbalance"))
                    ?.let { result.put("provider_balance", it) }

                findScalar(units, setOf("unittype", "smsunittype"))
                    ?.let { result.put("unit_type", it) }
                findScalar(units, setOf("lastupdated", "lastupdatedat", "updatedat"))
                    ?.let { result.put("last_updated", it) }

                if (!result.has("remaining_units") &&
                    !result.has("total_units") && !result.has("used_units") &&
                    !result.has("provider_balance")
                ) {
                    val providerMessage = units.optString("message").ifBlank {
                        units.optString("error").ifBlank {
                            "The /balance response did not contain a recognized SMS balance value."
                        }
                    }
                    throw IllegalStateException(
                        "TalkSasa was reached, but its documented /balance endpoint did not return a readable SMS balance. " +
                            providerMessage + " Response field structure (values hidden): " + describeJsonShape(units) + ". Check the TalkSasa balance response contract."
                    )
                }
                result

            }
        }
    }

    /** Returns JSON field names and container types only; never includes provider values or credentials. */
    private fun describeJsonShape(value: Any?, depth: Int = 0): String {
        if (depth > 4) return "…"
        return when (value) {
            is JSONObject -> {
                val entries = value.keys().asSequence().take(16).map { key ->
                    val child = value.opt(key)
                    key + when (child) {
                        is JSONObject -> describeJsonShape(child, depth + 1)
                        is org.json.JSONArray -> "[${child.length()}]"
                        null, JSONObject.NULL -> ":null"
                        is Number -> ":number"
                        is Boolean -> ":boolean"
                        else -> ":string"
                    }
                }.toList()
                "{" + entries.joinToString(", ") + "}"
            }
            is org.json.JSONArray -> "[${value.length()} items]"
            else -> value?.javaClass?.simpleName ?: "null"
        }
    }
    private fun findTalkSasaUnits(response: JSONObject): String? {
        val aliases = setOf(
            "remaining_units", "sms_units_remaining", "available_units",
            "remaining_sms", "sms_credits", "units_remaining", "sms_balance",
            "remaining", "available", "balance", "units"
        ).map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }.toSet()
        fun scalar(value: Any?): String? = when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toString()
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }
        fun search(value: Any?, depth: Int): String? {
            if (depth > 6) return null
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.lowercase().replace(Regex("[^a-z0-9]"), "") in aliases) {
                            scalar(value.opt(key))?.let { return it }
                        }
                    }
                    val names = value.keys()
                    while (names.hasNext()) search(value.opt(names.next()), depth + 1)?.let { return it }
                }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        search(value.opt(index), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }
        return search(response, 0)
    }

    private fun request(url: String, token: String, method: String, body: JSONObject?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            val credential = token.trim().removePrefix("\uFEFF").trim()
                .replace(Regex("(?i)^Bearer\\s+"), "").trim().trim('"').trim('\'')
            c.setRequestProperty("Authorization", "Bearer " + credential)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("User-Agent", "Bingwa-Mobile-SMS/1.0")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse {
                runCatching { JSONObject().put("data", org.json.JSONArray(raw)) }
                    .getOrElse { JSONObject().put("message", raw.take(300)) }
            }
            if (code !in 200..299) {
                val providerError = json.optString("error").ifBlank {
                    json.optString("message").ifBlank { "Provider returned HTTP $code" }
                }
                val errorCode = json.optString("code")
                val message = if (url.startsWith(HUB, ignoreCase = true) && (code == 401 || code == 403)) {
                    "SMSHub authentication/permission error (HTTP $code" +
                        (if (errorCode.isNotBlank()) ", $errorCode" else "") +
                        "): $providerError. Create a Personal Access Token at SMSHub → API tokens (not the login password). Enable balance:read to connect/check balance and sms:send to send messages. Paste the complete smshub_ token only; do not include 'Bearer'."
                } else if (code == 401 || code == 403) {
                    "TalkSasa rejected this API token (HTTP $code): $providerError"
                } else {
                    providerError + if (errorCode.isNotBlank()) " ($errorCode)" else ""
                }
                throw IllegalStateException(message)
            }
            if (json.optString("status").equals("error", ignoreCase = true)) {
                val providerMessage = json.optString("message")
                    .ifBlank { json.optString("error") }
                    .ifBlank { "The provider returned an unsuccessful balance response." }
                throw IllegalStateException(providerMessage)
            }
            val payload = json.opt("data")
            return when (payload) {
                is JSONObject -> payload
                is org.json.JSONArray -> JSONObject().put("data", payload)
                else -> json
            }
        } catch (error: UnknownHostException) {
            val host = runCatching { URL(url).host }.getOrDefault("the SMS provider")
            throw IllegalStateException("Cannot resolve provider host '$host'. Check your internet connection, DNS settings, or whether the provider domain is reachable.", error)
        } finally { c.disconnect() }
    }
}

internal object SmsMessageHistory {
    private const val PREFS = "sms_centre_history"
    fun append(
        context: Context,
        provider: SmsProvider,
        to: String,
        text: String,
        result: SmsSendResult?,
        error: String = "",
        recipientName: String = ""
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rows = runCatching { org.json.JSONArray(prefs.getString("messages", "[]") ?: "[]") }.getOrDefault(org.json.JSONArray())
        rows.put(JSONObject().put("provider", provider.name).put("to", to)
            .put("recipient_name", recipientName.trim()).put("text", text)
            .put("status", if (result?.accepted == true) "Accepted" else "Failed")
            .put("id", result?.messageId.orEmpty()).put("detail", error.ifBlank { result?.detail.orEmpty() })
            .put("at", System.currentTimeMillis()))
        val bounded = org.json.JSONArray()
        for (i in (rows.length() - 1000).coerceAtLeast(0) until rows.length()) bounded.put(rows.getJSONObject(i))
        prefs.edit().putString("messages", bounded.toString()).apply()
    }
    fun load(context: Context): List<JSONObject> {
        val rows = runCatching { org.json.JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("messages", "[]") ?: "[]") }
            .getOrDefault(org.json.JSONArray())
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.reversed()
    }
}
