package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal enum class CustomerHistoryRetention(val label: String, val days: Long) {
    Weekly("Weekly", 7L),
    Monthly("Monthly", 30L),
    Yearly("Yearly", 365L),
    Never("Never", Long.MAX_VALUE);

    companion object {
        fun from(raw: String?): CustomerHistoryRetention =
            entries.firstOrNull { it.name == raw } ?: Weekly
    }
}

internal data class CustomerPurchaseRecord(
    val transactionId: Int,
    val phone: String,
    val customerName: String,
    val offer: String,
    val amount: String,
    val purchasedAt: Long,
    val status: String
)

/**
 * Customer-specific purchase history is opt-in and separate from the app's transaction ledger.
 * Clearing a customer's history never removes their saved contact or the main transaction record.
 */
internal object CustomerPurchaseHistoryStore {
    private const val PREFS = "customer_purchase_history"
    private const val KEY_ENABLED_PREFIX = "enabled_"
    private const val KEY_RETENTION_PREFIX = "retention_"
    private const val KEY_CLEARED_PREFIX = "cleared_"
    private const val KEY_RECORDS_PREFIX = "records_"
    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun phoneKey(raw: String): String =
        SmsCommandHandler.normalizePhone(raw).takeIf { it.matches(Regex("^0[17]\\d{8}$")) }.orEmpty()

    fun isEnabled(context: Context, phone: String): Boolean {
        val key = phoneKey(phone)
        return key.isNotBlank() && prefs(context).getLong(KEY_ENABLED_PREFIX + key, 0L) > 0L
    }

    fun enable(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        prefs(context).edit()
            .putLong(KEY_ENABLED_PREFIX + key, System.currentTimeMillis())
            .putLong(KEY_CLEARED_PREFIX + key, 0L)
            .apply()
    }

    fun disable(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) {
            prefs(context).edit()
                .remove(KEY_ENABLED_PREFIX + key)
                .remove(KEY_RECORDS_PREFIX + key)
                .putLong(KEY_CLEARED_PREFIX + key, System.currentTimeMillis())
                .apply()
        }
    }

    fun retention(context: Context, phone: String): CustomerHistoryRetention {
        val key = phoneKey(phone)
        return CustomerHistoryRetention.from(prefs(context).getString(KEY_RETENTION_PREFIX + key, "Weekly"))
    }

    fun setRetention(context: Context, phone: String, retention: CustomerHistoryRetention) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        prefs(context).edit().putString(KEY_RETENTION_PREFIX + key, retention.name).apply()
        prune(context, key)
    }

    fun clear(context: Context, phone: String) {
        val key = phoneKey(phone)
        if (key.isBlank()) return
        synchronized(lock) {
            prefs(context).edit()
                .remove(KEY_RECORDS_PREFIX + key)
                .putLong(KEY_CLEARED_PREFIX + key, System.currentTimeMillis())
                .apply()
        }
    }

    fun load(context: Context, phone: String): List<CustomerPurchaseRecord> {
        val key = phoneKey(phone)
        if (key.isBlank() || !isEnabled(context, key)) return emptyList()
        synchronized(lock) {
            val raw = prefs(context).getString(KEY_RECORDS_PREFIX + key, "[]") ?: "[]"
            val rows = runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    CustomerPurchaseRecord(
                        transactionId = item.optInt("id", -1),
                        phone = key,
                        customerName = item.optString("name"),
                        offer = item.optString("offer"),
                        amount = item.optString("amount"),
                        purchasedAt = item.optLong("at", 0L),
                        status = item.optString("status")
                    ).takeIf { it.transactionId >= 0 && it.purchasedAt > 0L }
                }
            }.getOrDefault(emptyList())
            val cleaned = filterRetained(context, key, rows)
            if (cleaned.size != rows.size) persist(context, key, cleaned)
            return cleaned.sortedByDescending { it.purchasedAt }
        }
    }

    /** Capture only completed purchases, and only after the customer explicitly enables tracking. */
    fun capture(context: Context, transaction: Transaction) {
        if (transaction.statusEnum != TransactionStatus.SUCCESS) return
        val key = phoneKey(transaction.phoneNumber)
        if (key.isBlank()) return
        val enabledAt = prefs(context).getLong(KEY_ENABLED_PREFIX + key, 0L)
        val at = transaction.completedAt.takeIf { it > 0L } ?: transaction.timestamp
        if (enabledAt <= 0L || at < enabledAt || transaction.id < 0) return
        synchronized(lock) {
            val current = load(context, key).toMutableList()
            val record = CustomerPurchaseRecord(
                transactionId = transaction.id,
                phone = key,
                customerName = transaction.clientName,
                offer = transaction.description.ifBlank { "Purchase" },
                amount = transaction.amount.ifBlank { "Amount not recorded" },
                purchasedAt = at,
                status = transaction.status
            )
            val index = current.indexOfFirst { it.transactionId == record.transactionId }
            if (index >= 0) current[index] = record else current += record
            persist(context, key, filterRetained(context, key, current))
        }
    }

    private fun prune(context: Context, key: String) {
        synchronized(lock) {
            persist(context, key, filterRetained(context, key, load(context, key)))
        }
    }

    private fun filterRetained(
        context: Context,
        key: String,
        records: List<CustomerPurchaseRecord>
    ): List<CustomerPurchaseRecord> {
        val p = prefs(context)
        val enabledAt = p.getLong(KEY_ENABLED_PREFIX + key, 0L)
        val clearedAt = p.getLong(KEY_CLEARED_PREFIX + key, 0L)
        val retention = CustomerHistoryRetention.from(p.getString(KEY_RETENTION_PREFIX + key, "Weekly"))
        val cutoff = if (retention == CustomerHistoryRetention.Never) 0L
            else System.currentTimeMillis() - retention.days * 86_400_000L
        return records.filter { it.purchasedAt >= enabledAt && it.purchasedAt > clearedAt && it.purchasedAt >= cutoff }
    }

    private fun persist(context: Context, key: String, records: List<CustomerPurchaseRecord>) {
        val array = JSONArray()
        records.distinctBy { it.transactionId }.takeLast(250).forEach { row ->
            array.put(JSONObject()
                .put("id", row.transactionId)
                .put("name", row.customerName)
                .put("offer", row.offer)
                .put("amount", row.amount)
                .put("at", row.purchasedAt)
                .put("status", row.status))
        }
        prefs(context).edit().putString(KEY_RECORDS_PREFIX + key, array.toString()).apply()
    }
}
