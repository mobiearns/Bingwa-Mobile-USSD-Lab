package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

internal object OfferPurchaseAnalytics {
    private const val PREFS = "offer_purchase_analytics"
    private const val KEY_EVENTS = "events"
    private const val KEY_PERIOD = "period"
    private const val KEY_WINDOW = "window_start"
    private const val MAX_EVENTS = 20000

    enum class Period(val label: String, val millis: Long) {
        NEVER("Never", Long.MAX_VALUE),
        DAILY("Daily", 86400000L),
        WEEKLY("Weekly", 604800000L),
        MONTHLY("Every 30 days", 2592000000L)
    }

    data class OfferStats(val offerId: Int, val name: String, val purchases: Int, val clients: Int, val revenue: Double, val customers: List<String>)

    @Synchronized fun record(context: Context, tx: Transaction) {
        if (tx.statusEnum != TransactionStatus.SUCCESS || tx.offerId < 0 || tx.id <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        resetIfDue(prefs)
        val array = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        if ((0 until array.length()).any { array.optJSONObject(it)?.optInt("txId", -1) == tx.id }) return
        val offerName = OfferRepository.findById(context, tx.offerId)?.name ?: tx.description.ifBlank { "Offer" }
        val client = tx.phoneNumber.ifBlank { tx.clientName }.ifBlank { "Unknown client" }
        val amount = tx.amountValue.takeIf { it > 0.0 } ?: tx.amount.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        array.put(JSONObject().put("txId", tx.id).put("offerId", tx.offerId).put("name", offerName)
            .put("client", client).put("amount", amount).put("time", tx.completedAt.takeIf { it > 0L } ?: tx.timestamp))
        while (array.length() > MAX_EVENTS) array.remove(0)
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    @Synchronized fun stats(context: Context): List<OfferStats> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        resetIfDue(prefs)
        val array = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        val rows = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        return rows.groupBy { it.optInt("offerId", -1) }.map { entry ->
            val events = entry.value
            val customers = events.map { it.optString("client", "Unknown client") }.distinct()
            OfferStats(entry.key, events.lastOrNull()?.optString("name").orEmpty().ifBlank { "Offer" },
                events.size, customers.size, events.sumOf { it.optDouble("amount", 0.0) }, customers)
        }.sortedWith(compareByDescending<OfferStats> { it.purchases }.thenByDescending { it.clients })
    }


    data class SalesSnapshot(
        val offers: List<OfferStats>,
        val totalPurchases: Int,
        val uniqueCustomers: Int,
        val totalRevenue: Double,
        val busiestDay: String?,
        val busiestDayPurchases: Int,
        val quietestDay: String?,
        val busiestHour: String?,
        val busiestHourPurchases: Int,
        val dayCounts: List<Pair<String, Int>>,
        val hourCounts: List<Pair<String, Int>>
    )

    @Synchronized fun snapshot(context: Context, catalog: List<OfferItem>): SalesSnapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        resetIfDue(prefs)
        val array = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]")) }.getOrDefault(JSONArray())
        val events = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val byOffer = events.groupBy { it.optInt("offerId", -1) }
        val offers = catalog.map { offer ->
            val purchases = byOffer[offer.id].orEmpty()
            val clients = purchases.map { it.optString("client", "") }
                .filter { it.isNotBlank() && !it.equals("Unknown client", true) }.distinct()
            OfferStats(offer.id, offer.name, purchases.size, clients.size,
                purchases.sumOf { it.optDouble("amount", 0.0) }, clients)
        }.sortedWith(compareByDescending<OfferStats> { it.purchases }.thenByDescending { it.revenue }.thenBy { it.name })

        val dayOrder = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayCounts = dayOrder.map { day ->
            day to events.count { event ->
                val c = Calendar.getInstance().apply { timeInMillis = event.optLong("time", 0L) }
                dayOrder[c.get(Calendar.DAY_OF_WEEK).let { (it + 5) % 7 }] == day
            }
        }
        val hourCounts = (0..23).map { hour ->
            val label = String.format(Locale.getDefault(), "%02d:00", hour)
            label to events.count { event ->
                Calendar.getInstance().apply { timeInMillis = event.optLong("time", 0L) }
                    .get(Calendar.HOUR_OF_DAY) == hour
            }
        }
        val busiestDay = dayCounts.maxByOrNull { it.second }?.takeIf { it.second > 0 }
        val quietestDay = dayCounts.filter { it.second > 0 }.minByOrNull { it.second }
        val busiestHour = hourCounts.maxByOrNull { it.second }?.takeIf { it.second > 0 }
        val uniqueCustomers = events.map { it.optString("client", "") }
            .filter { it.isNotBlank() && !it.equals("Unknown client", true) }.distinct().size
        return SalesSnapshot(
            offers = offers,
            totalPurchases = events.size,
            uniqueCustomers = uniqueCustomers,
            totalRevenue = events.sumOf { it.optDouble("amount", 0.0) },
            busiestDay = busiestDay?.first,
            busiestDayPurchases = busiestDay?.second ?: 0,
            quietestDay = quietestDay?.first,
            busiestHour = busiestHour?.first,
            busiestHourPurchases = busiestHour?.second ?: 0,
            dayCounts = dayCounts,
            hourCounts = hourCounts
        )
    }

    fun period(context: Context): Period = runCatching {
        Period.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PERIOD, "NEVER") ?: "NEVER")
    }.getOrDefault(Period.NEVER)

    @Synchronized fun setPeriod(context: Context, period: Period) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PERIOD, period.name)
            .putLong(KEY_WINDOW, System.currentTimeMillis()).apply()
    }

    @Synchronized fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EVENTS, "[]").putLong(KEY_WINDOW, System.currentTimeMillis()).apply()
    }

    private fun resetIfDue(prefs: android.content.SharedPreferences) {
        val period = runCatching { Period.valueOf(prefs.getString(KEY_PERIOD, "NEVER") ?: "NEVER") }.getOrDefault(Period.NEVER)
        if (period == Period.NEVER) return
        val now = System.currentTimeMillis()
        val start = prefs.getLong(KEY_WINDOW, now)
        if (now - start >= period.millis) {
            val windows = ((now - start) / period.millis).coerceAtLeast(1L)
            prefs.edit().putString(KEY_EVENTS, "[]").putLong(KEY_WINDOW, start + windows * period.millis).apply()
        }
    }
}
