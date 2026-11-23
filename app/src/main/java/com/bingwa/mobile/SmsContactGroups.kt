package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class SmsContactGroup(
    val id: String,
    val name: String,
    val phones: Set<String>,
    val templateId: String? = null,
)

internal object SmsContactGroupStore {
    private const val PREFS = "sms_contact_groups"
    private const val KEY = "groups"

    fun load(context: Context): List<SmsContactGroup> {
        val array = runCatching {
            JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]")
        }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val phones = obj.optJSONArray("phones") ?: JSONArray()
            SmsContactGroup(
                obj.optString("id"),
                obj.optString("name"),
                (0 until phones.length()).map { phones.optString(it) }.filter { it.isNotBlank() }.toSet(),
                obj.optString("templateId").takeIf { it.isNotBlank() }
            )
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    fun save(context: Context, groups: List<SmsContactGroup>) {
        val array = JSONArray()
        groups.forEach { group ->
            array.put(JSONObject().put("id", group.id).put("name", group.name)
                .put("phones", JSONArray(group.phones.toList()))
                .put("templateId", group.templateId.orEmpty()))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun create(
        context: Context,
        name: String,
        phones: Set<String> = emptySet(),
        templateId: String? = null,
    ): Boolean {
        val value = name.trim()
        if (value.isBlank()) return false
        val groups = load(context)
        if (groups.any { it.name.equals(value, true) }) return false
        val members = phones.mapNotNull(::normalize).toSet()
        save(context, groups + SmsContactGroup(java.util.UUID.randomUUID().toString(), value, members, templateId))
        return true
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        val value = name.trim()
        if (value.isBlank()) return false
        val groups = load(context)
        if (groups.any { it.id != id && it.name.equals(value, true) }) return false
        if (groups.none { it.id == id }) return false
        save(context, groups.map { if (it.id == id) it.copy(name = value) else it })
        return true
    }

    fun delete(context: Context, id: String) = save(context, load(context).filterNot { it.id == id })

    fun setMember(context: Context, id: String, phone: String, included: Boolean) {
        val normalized = normalize(phone) ?: return
        save(context, load(context).map { group ->
            if (group.id != id) group else group.copy(
                phones = if (included) group.phones + normalized else group.phones - normalized
            )
        })
    }

    fun recipients(context: Context, ids: Set<String>): Set<String> =
        load(context).filter { it.id in ids }.flatMap { it.phones }.toSet()

    private fun normalize(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        val normalized = when {
            digits.startsWith("254") -> digits
            digits.startsWith("0") -> "254" + digits.drop(1)
            digits.startsWith("7") || digits.startsWith("1") -> "254" + digits
            else -> return null
        }
        return normalized.takeIf { it.matches(Regex("^254[17]\\d{8}$")) }
    }
}

/** Customer SMS settings share the same source as the main Customer Notifications screen. */
internal object SmsNotificationPreferences {
    private const val PREFS = "app_settings"

    fun template(context: Context, type: String): String {
        val success = type != "failure"
        val key = if (success) "sms_tpl_success" else "sms_tpl_failed"
        val fallback = if (success) DEFAULT_TPL_SUCCESS else DEFAULT_TPL_FAILED
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, fallback) ?: fallback
    }

    fun setTemplate(context: Context, type: String, value: String) {
        val key = if (type == "failure") "sms_tpl_failed" else "sms_tpl_success"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, value.take(500)).apply()
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("notify_success", true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("notify_success", value).apply()
    }

    fun sendFailures(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("notify_failed", true)

    fun setSendFailures(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("notify_failed", value).apply()
    }

    fun consented(context: Context, phone: String) =
        context.getSharedPreferences("sms_notification_preferences", Context.MODE_PRIVATE)
            .getStringSet("consented", emptySet())?.contains(phone) == true

    fun setConsent(context: Context, phone: String, consent: Boolean) {
        val prefs = context.getSharedPreferences("sms_notification_preferences", Context.MODE_PRIVATE)
        val values = prefs.getStringSet("consented", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (consent) values.add(phone) else values.remove(phone)
        prefs.edit().putStringSet("consented", values).apply()
    }

    fun optedOut(context: Context, phone: String) =
        context.getSharedPreferences("sms_notification_preferences", Context.MODE_PRIVATE)
            .getStringSet("opted_out", emptySet())?.contains(phone) == true

    fun setOptedOut(context: Context, phone: String, value: Boolean) {
        val prefs = context.getSharedPreferences("sms_notification_preferences", Context.MODE_PRIVATE)
        val values = prefs.getStringSet("opted_out", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (value) values.add(phone) else values.remove(phone)
        prefs.edit().putStringSet("opted_out", values).apply()
    }
}

internal object SmsTransactionNotificationDispatcher {
    private const val PREFS = "sms_notification_dispatch"
    fun onOutcome(context: Context, tx: Transaction) {
        val status = tx.statusEnum
        val isSuccess = status == TransactionStatus.SUCCESS
        val isFailure = status == TransactionStatus.FAILED
        if (isSuccess && !SmsNotificationPreferences.enabled(context)) return
        if (isFailure && !SmsNotificationPreferences.sendFailures(context)) return
        if (!isSuccess && !isFailure) return
        val phone = normalize(tx.phoneNumber) ?: return
        if (!SmsNotificationPreferences.consented(context, phone) || SmsNotificationPreferences.optedOut(context, phone)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = if (isSuccess) "success" else "failure"
        val key = "${tx.id}:$type"
        val sent = prefs.getStringSet("sent", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (key in sent) return
        val body = buildSmsMessage(
            context,
            if (isSuccess) "success" else "failure",
            tx.clientName.ifBlank { "customer" },
            tx.description,
            tx.amount,
            phone,
            tx.id.toString()
        )
        val connection = SmsCredentialVault.load(context) ?: return
        if (connection.senderId.isBlank()) return
        sent.add(key)
        prefs.edit().putStringSet("sent", sent).apply()
        val work = androidx.work.OneTimeWorkRequestBuilder<SmsNotificationWorker>()
            .setInputData(androidx.work.workDataOf(
                SmsNotificationWorker.KEY_PHONE to phone,
                SmsNotificationWorker.KEY_BODY to body,
                SmsNotificationWorker.KEY_SENDER to connection.senderId,
                SmsNotificationWorker.KEY_IDEMPOTENCY to key
            ))
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "bingwa_tx_sms_" + key.replace(":", "_"),
            androidx.work.ExistingWorkPolicy.KEEP,
            work
        )
    }

    private fun normalize(raw: String): String? {
        val digits = raw.filter(Char::isDigit)
        val value = when {
            digits.startsWith("254") -> digits
            digits.startsWith("0") -> "254" + digits.drop(1)
            else -> return null
        }
        return value.takeIf { it.matches(Regex("^254[17]\\d{8}$")) }
    }
}
