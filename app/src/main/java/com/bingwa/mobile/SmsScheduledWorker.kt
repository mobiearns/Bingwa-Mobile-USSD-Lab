package com.bingwa.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object SmsScheduledQueue {
    private const val PREFS = "sms_scheduled_queue"
    private const val KEY = "items"

    fun all(context: Context): List<JSONObject> {
        val a = runCatching { JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun save(context: Context, rows: List<JSONObject>) {
        val a = JSONArray()
        rows.forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    fun enqueue(context: Context, to: String, message: String, senderId: String, delayMinutes: Long): String {
        require(to.matches(Regex("^254[17]\\d{8}$"))) { "Invalid Kenyan phone number" }
        require(message.isNotBlank()) { "Message cannot be empty" }
        require(delayMinutes >= 1) { "Schedule at least one minute ahead" }
        val id = java.util.UUID.randomUUID().toString()
        save(context, all(context) + JSONObject().put("id", id).put("to", to).put("message", message)
            .put("sender", senderId).put("created", System.currentTimeMillis())
            .put("scheduled", System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(delayMinutes)))
        val request = OneTimeWorkRequestBuilder<SmsScheduledWorker>()
            .setInputData(workDataOf(SmsScheduledWorker.KEY_ID to id))
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("bingwa_sms_$id", ExistingWorkPolicy.KEEP, request)
        return id
    }

    fun find(context: Context, id: String): JSONObject? = all(context).firstOrNull { it.optString("id") == id }
    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.optString("id") == id })
}

internal class SmsScheduledWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val item = SmsScheduledQueue.find(applicationContext, id) ?: return Result.success()
        val connection = SmsCredentialVault.load(applicationContext) ?: return Result.failure()
        if (connection.senderId.isBlank()) return Result.failure()

        // Keep WorkManager's Result type separate from kotlin.Result returned by provider clients.
        val sendResult: kotlin.Result<SmsSendResult> = if (connection.provider == SmsProvider.TALKSASA) {
            val backend = SmsCredentialVault.backend(applicationContext) ?: return Result.retry()
            TalkSasaBackendClient.send(
                backend.first, backend.second, item.optString("sender"), item.optString("to"),
                item.optString("message"), id, hashed = true
            )
        } else {
            SmsProviderClient.send(
                connection.copy(senderId = item.optString("sender")),
                item.optString("to"),
                item.optString("message")
            )
        }

        val accepted = sendResult.getOrNull()?.accepted == true
        if (sendResult.isSuccess && accepted) {
            SmsMessageHistory.append(applicationContext, connection.provider, item.optString("to"), item.optString("message"), sendResult.getOrNull())
            SmsScheduledQueue.remove(applicationContext, id)
            return Result.success()
        }

        if (runAttemptCount >= 5) {
            SmsMessageHistory.append(
                applicationContext, connection.provider, item.optString("to"), item.optString("message"), null,
                sendResult.exceptionOrNull()?.message ?: "Scheduled message was not accepted"
            )
            SmsScheduledQueue.remove(applicationContext, id)
            return Result.failure()
        }
        return Result.retry()
    }

    companion object { const val KEY_ID = "scheduled_sms_id" }
}

internal class SmsNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val phone = inputData.getString(KEY_PHONE) ?: return Result.failure()
        val body = inputData.getString(KEY_BODY) ?: return Result.failure()
        val sender = inputData.getString(KEY_SENDER) ?: return Result.failure()
        val idempotency = inputData.getString(KEY_IDEMPOTENCY) ?: return Result.failure()
        val connection = SmsCredentialVault.load(applicationContext) ?: return Result.retry()

        // Return WorkManager retry directly when backend configuration is missing; do not mix
        // androidx.work.ListenableWorker.Result with kotlin.Result<SmsSendResult>.
        val result: kotlin.Result<SmsSendResult> = if (connection.provider == SmsProvider.TALKSASA) {
            val backend = SmsCredentialVault.backend(applicationContext) ?: return Result.retry()
            TalkSasaBackendClient.send(backend.first, backend.second, sender, phone, body, idempotency, hashed = true)
        } else {
            SmsProviderClient.send(connection.copy(senderId = sender), phone, body)
        }

        if (result.isSuccess && result.getOrNull()?.accepted == true) {
            SmsMessageHistory.append(applicationContext, connection.provider, phone, body, result.getOrNull())
            return Result.success()
        }
        if (runAttemptCount < 5) return Result.retry()

        SmsMessageHistory.append(
            applicationContext, connection.provider, phone, body, null,
            result.exceptionOrNull()?.message ?: "Automatic SMS failed after retries"
        )
        applicationContext.getSharedPreferences("sms_notification_dispatch", Context.MODE_PRIVATE)
            .getStringSet("sent", emptySet())?.toMutableSet()?.let { keys ->
                keys.remove(idempotency)
                applicationContext.getSharedPreferences("sms_notification_dispatch", Context.MODE_PRIVATE).edit().putStringSet("sent", keys).apply()
            }
        return Result.failure()
    }

    companion object {
        const val KEY_PHONE = "notification_phone"
        const val KEY_BODY = "notification_body"
        const val KEY_SENDER = "notification_sender"
        const val KEY_IDEMPOTENCY = "notification_idempotency"
    }
}
