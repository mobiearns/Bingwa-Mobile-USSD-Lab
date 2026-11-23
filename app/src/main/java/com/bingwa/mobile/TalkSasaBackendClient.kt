package com.bingwa.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.util.UUID

internal object TalkSasaBackendClient {
    private fun request(baseUrl: String, adminKey: String, path: String, method: String = "GET", body: JSONObject? = null, idempotencyKey: String? = null): Pair<Int, JSONObject> {
        require(baseUrl.startsWith("https://")) { "Backend URL must use HTTPS." }
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 12000
            c.readTimeout = 20000
            c.setRequestProperty("Authorization", "Bearer $adminKey")
            c.setRequestProperty("Accept", "application/json")
            if (idempotencyKey != null) c.setRequestProperty("Idempotency-Key", idempotencyKey)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val data = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("error", raw.take(500)) }
            if (code !in 200..299) throw IllegalStateException(data.optString("error").ifBlank { data.optString("message").ifBlank { "Backend HTTP $code" } })
            return code to data
        } catch (error: UnknownHostException) {
            val host = runCatching { URL(baseUrl).host }.getOrDefault("the configured backend")
            throw IllegalStateException("Cannot resolve backend host '$host'. Check the backend URL, DNS, and that the backend is deployed and publicly reachable.", error)
        } finally { c.disconnect() }
    }

    suspend fun verify(baseUrl: String, adminKey: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            request(baseUrl, adminKey, "/v1/sms/me")
            val balance = request(baseUrl, adminKey, "/v1/sms/balance").second
            "TalkSasa backend connected · balance retrieved"
        }
    }

    suspend fun send(baseUrl: String, adminKey: String, sender: String, to: String, message: String, idempotencyKey: String = UUID.randomUUID().toString(), hashed: Boolean = false): Result<SmsSendResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("recipient", to).put("sender_id", sender).put("type", if (hashed) "hashed" else "plain").put("message", message)
            val (code, data) = request(baseUrl, adminKey, "/v1/sms/send", "POST", body, idempotencyKey)
            val status = data.optString("status", "accepted")
            val uid = data.optString("uid").ifBlank { data.optJSONObject("response")?.optString("uid").orEmpty() }
            SmsSendResult(status in setOf("accepted", "queued", "sent", "delivered"), uid, "TalkSasa backend: $status (HTTP $code)")
        }
    }

    suspend fun balance(baseUrl: String, adminKey: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching { request(baseUrl, adminKey, "/v1/sms/balance").second } }
    suspend fun dashboard(baseUrl: String, adminKey: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching { request(baseUrl, adminKey, "/v1/sms/dashboard").second } }
    suspend fun history(baseUrl: String, adminKey: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching { request(baseUrl, adminKey, "/v1/sms/history").second } }
    suspend fun refreshStatuses(baseUrl: String, adminKey: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching { request(baseUrl, adminKey, "/v1/sms/refresh-status", "POST", JSONObject()).second } }
    suspend fun campaign(baseUrl: String, adminKey: String, groupId: String, sender: String, message: String, idempotencyKey: String = UUID.randomUUID().toString()): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching { request(baseUrl, adminKey, "/v1/sms/campaign", "POST", JSONObject().put("contact_list_id", groupId).put("sender_id", sender).put("message", message), idempotencyKey).second }
    }
    suspend fun contacts(baseUrl: String, adminKey: String): Result<JSONObject> = withContext(Dispatchers.IO) { runCatching { request(baseUrl, adminKey, "/v1/sms/contacts").second } }
}
