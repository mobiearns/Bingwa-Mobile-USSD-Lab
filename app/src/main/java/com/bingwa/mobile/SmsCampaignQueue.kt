package com.bingwa.mobile

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Persistent, per-recipient campaign state. Failed recipients remain first in the next run. */
internal object SmsCampaignQueue {
    private const val PREFS = "sms_campaign_queue"
    private const val KEY = "campaigns"

    fun all(context: Context): List<JSONObject> {
        val array = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun save(context: Context, rows: List<JSONObject>) {
        val array = JSONArray()
        rows.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun create(
        context: Context,
        title: String,
        groupId: String,
        groupName: String,
        recipients: Collection<String>,
        message: String,
        sender: String,
        intervalMillis: Long
    ): String {
        require(title.isNotBlank() && message.isNotBlank() && sender.isNotBlank())
        require(intervalMillis >= TimeUnit.HOURS.toMillis(1))
        val valid = recipients.mapNotNull(::normalize).distinct()
            .filter { SmsNotificationPreferences.consented(context, it) && !SmsNotificationPreferences.optedOut(context, it) }
        require(valid.isNotEmpty()) { "This group has no valid, SMS-consented recipients." }
        val id = java.util.UUID.randomUUID().toString()
        val rows = all(context).toMutableList()
        val priorityPhones = rows.flatMap { oldCampaign ->
            val oldRecipients = oldCampaign.optJSONArray("recipients") ?: JSONArray()
            (0 until oldRecipients.length()).mapNotNull { index ->
                oldRecipients.optJSONObject(index)?.takeIf { recipient ->
                    recipient.optInt("priority") > 0 ||
                        (recipient.optString("state") in setOf("FAILED", "PAUSED_CREDIT") && recipient.optInt("attempts") > 0)
                }?.optString("phone")
            }
        }.toSet()
        rows.add(JSONObject()
            .put("id", id).put("title", title.trim()).put("groupId", groupId)
            .put("groupName", groupName).put("message", message).put("sender", sender)
            .put("interval", intervalMillis).put("nextRun", System.currentTimeMillis())
            .put("active", true).put("created", System.currentTimeMillis())
            .put("recipients", JSONArray(valid.map { phone ->
                JSONObject().put("phone", phone).put("state", "PENDING").put("attempts", 0)
                    .put("priority", if (phone in priorityPhones) 1 else 0)
                    .put("lastError", "").put("lastAttempt", 0L)
            })))
        save(context, rows)
        ensureWorker(context)
        runNow(context)
        return id
    }

    fun retry(context: Context, id: String) {
        val rows = all(context)
        rows.firstOrNull { it.optString("id") == id }?.let { campaign ->
            val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
            val retryable = (0 until recipients.length()).any { index ->
                recipients.optJSONObject(index)?.optString("state") != "ACCEPTED"
            }
            if (!retryable) return@let
            for (i in 0 until recipients.length()) {
                val row = recipients.optJSONObject(i) ?: continue
                if (row.optString("state") != "ACCEPTED") row.put("state", "PENDING")
            }
            campaign.put("active", true).put("nextRun", System.currentTimeMillis())
            save(context, rows)
            ensureWorker(context)
            runNow(context)
        }
    }

    fun setActive(context: Context, id: String, active: Boolean) {
        val rows = all(context)
        rows.firstOrNull { it.optString("id") == id }?.put("active", active)
        save(context, rows)
        if (active) { ensureWorker(context); runNow(context) }
    }

    fun remove(context: Context, id: String) = save(context, all(context).filterNot { it.optString("id") == id })

    fun updateRecipient(context: Context, campaignId: String, phone: String, state: String, error: String = "") {
        val rows = all(context)
        val campaign = rows.firstOrNull { it.optString("id") == campaignId } ?: return
        val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
        for (i in 0 until recipients.length()) {
            val row = recipients.optJSONObject(i) ?: continue
            if (row.optString("phone") == phone) {
                row.put("state", state).put("lastError", error.take(300))
                    .put("lastAttempt", System.currentTimeMillis())
                if (state != "ACCEPTED") row.put("attempts", row.optInt("attempts") + 1)
                row.put("priority", if (state in setOf("FAILED", "PENDING", "PAUSED_CREDIT")) 1 else 0)
                break
            }
        }
        campaign.put("recipients", recipients)
        save(context, rows)
    }

    fun setState(context: Context, id: String, state: String) {
        val rows = all(context)
        rows.firstOrNull { it.optString("id") == id }?.put("state", state)
        save(context, rows)
    }

    fun due(context: Context): List<JSONObject> = all(context).filter {
        it.optBoolean("active", false) && it.optLong("nextRun") <= System.currentTimeMillis()
    }.sortedByDescending { campaign ->
        val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
        (0 until recipients.length()).mapNotNull { recipients.optJSONObject(it) }
            .filter { it.optString("state") !in setOf("ACCEPTED", "SKIPPED") }
            .maxOfOrNull { it.optInt("priority") } ?: 0
    }

    fun startCycle(context: Context, id: String) {
        val rows = all(context)
        val campaign = rows.firstOrNull { it.optString("id") == id } ?: return
        val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
        val previousCycleComplete = recipients.length() > 0 && (0 until recipients.length()).all { index ->
            recipients.optJSONObject(index)?.optString("state") in setOf("ACCEPTED", "SKIPPED")
        }
        if (!previousCycleComplete) return
        for (i in 0 until recipients.length()) {
            recipients.optJSONObject(i)?.apply {
                put("state", "PENDING")
                put("attempts", 0)
                put("lastError", "")
                put("lastAttempt", 0L)
            }
        }
        campaign.put("recipients", recipients)
        save(context, rows)
    }

    fun finishCycle(context: Context, id: String) {
        val rows = all(context)
        val campaign = rows.firstOrNull { it.optString("id") == id } ?: return
        val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
        val hasPending = (0 until recipients.length()).any { i ->
            recipients.optJSONObject(i)?.optString("state") !in setOf("ACCEPTED", "SKIPPED")
        }
        if (hasPending) return
        // Keep the last cycle visible in the dashboard until the next cycle actually starts.
        campaign.put("nextRun", System.currentTimeMillis() + campaign.optLong("interval", TimeUnit.DAYS.toMillis(1)))
        save(context, rows)
    }

    fun ensureWorker(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsCampaignWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "bingwa_sms_campaign_monitor", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SmsCampaignWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "bingwa_sms_campaign_run", ExistingWorkPolicy.APPEND_OR_REPLACE, request
        )
    }

    fun normalize(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        val value = when {
            digits.startsWith("254") -> digits
            digits.startsWith("0") -> "254" + digits.drop(1)
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254$digits"
            else -> return null
        }
        return value.takeIf { it.matches(Regex("^254[17]\\d{8}$")) }
    }
}

internal class SmsCampaignWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = campaignMutex.withLock {
        withContext(Dispatchers.IO) {
        val connection = SmsCredentialVault.load(applicationContext) ?: return@withContext Result.retry()
        val campaigns = SmsCampaignQueue.due(applicationContext)
        var creditBlockedForAccount = false
        for (campaign in campaigns) {
            val id = campaign.optString("id")
            SmsCampaignQueue.startCycle(applicationContext, id)
            SmsCampaignQueue.setState(applicationContext, id, "RUNNING")
            val message = campaign.optString("message")
            val sender = campaign.optString("sender")
            val recipients = campaign.optJSONArray("recipients") ?: JSONArray()
            // Retry missed recipients first; accepted recipients are never duplicated in this cycle.
            val pending = (0 until recipients.length()).mapNotNull { recipients.optJSONObject(it) }
                .filter { it.optString("state") !in setOf("ACCEPTED", "SKIPPED") }
                .sortedWith(compareByDescending<JSONObject> { it.optInt("priority") }
                    .thenByDescending { it.optInt("attempts") }
                    .thenBy { it.optLong("lastAttempt") })
            for (recipient in pending) {
                val phone = recipient.optString("phone")
                if (!SmsNotificationPreferences.consented(applicationContext, phone) ||
                    SmsNotificationPreferences.optedOut(applicationContext, phone)) {
                    SmsCampaignQueue.updateRecipient(applicationContext, id, phone, "SKIPPED", "SMS consent is missing or customer opted out.")
                    continue
                }
                val result = SmsProviderClient.send(connection.copy(senderId = sender), phone, message)
                val accepted = result.getOrNull()?.accepted == true
                if (accepted) {
                    SmsMessageHistory.append(applicationContext, connection.provider, phone, message, result.getOrNull())
                    SmsCampaignQueue.updateRecipient(applicationContext, id, phone, "ACCEPTED")
                } else {
                    val reason = result.exceptionOrNull()?.message ?: result.getOrNull()?.detail ?: "Provider did not accept the message."
                    val creditBlocked = reason.contains("credit", true) || reason.contains("balance", true) ||
                        reason.contains("insufficient", true) || reason.contains("units", true) ||
                        reason.contains("token", true) && reason.contains("limit", true)
                    SmsMessageHistory.append(applicationContext, connection.provider, phone, message, result.getOrNull(), reason)
                    SmsCampaignQueue.updateRecipient(applicationContext, id, phone,
                        if (creditBlocked) "PAUSED_CREDIT" else "FAILED", reason)
                    if (creditBlocked) {
                        creditBlockedForAccount = true
                        break
                    }
                }
            }
            SmsCampaignQueue.finishCycle(applicationContext, id)
            val refreshed = SmsCampaignQueue.all(applicationContext).firstOrNull { it.optString("id") == id }
            val nextRun = refreshed?.optLong("nextRun") ?: 0L
            SmsCampaignQueue.setState(applicationContext, id, when {
                creditBlockedForAccount -> "PAUSED_CREDIT"
                nextRun <= System.currentTimeMillis() -> "RETRY_PENDING"
                else -> "SCHEDULED"
            })
            if (creditBlockedForAccount) break
        }
        Result.success()
        }
    }

    companion object {
        private val campaignMutex = Mutex()
    }
}
