package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import kotlin.math.ceil

internal enum class SmsEncoding { GSM_7, UNICODE }

internal data class SmsSegmentEstimate(
    val characters: Int,
    val encodedUnits: Int,
    val encoding: SmsEncoding,
    val segments: Int
)

internal data class SmsCostEstimate(
    val recipients: Int,
    val segmentsPerRecipient: Int,
    val totalSegments: Int,
    val ratePerSegment: Double?,
    val totalKes: Double?,
    val encoding: SmsEncoding,
    val characters: Int,
    val encodedUnits: Int
)

internal data class SmsMarketingTemplate(
    val id: String,
    val category: String,
    val title: String,
    val body: String,
    val usesOffers: Boolean = false,
    val usesTill: Boolean = false
)

internal object SmsMarketingTemplates {
    private const val PREFS = "sms_marketing_costs"
    private const val BASIC_GSM = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val EXTENDED_GSM = "^{}\\[~]|€"

    val all = listOf(
        SmsMarketingTemplate("offer_1", "Offer campaign", "Today's Bingwa offer", "Don't miss today's Bingwa offer: {offers}. Pay via Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("offer_2", "Offer campaign", "Bingwa offer today", "Bingwa offer today only: {offers}. Pay via Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("offer_3", "Offer campaign", "Today's Bingwa deal", "Today's Bingwa deal: {offers}. Buy via Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("offer_4", "Offer campaign", "Limited offer today", "Limited offer today: Bingwa {offers}. Pay Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("offer_5", "Offer campaign", "Grab today's bundle offer", "Grab today's Bingwa bundle offer: {offers}. Till {till}. Normal prices resume tomorrow. Help {help}", true, true),

        SmsMarketingTemplate("announce_1", "Offer announcement", "Offer announcement", "Announcement: Bingwa has an offer today only. {offers}. Pay via Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("announce_2", "Offer announcement", "Today only", "Today only: Bingwa bundles are on offer. {offers}. Buy via Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("announce_3", "Offer announcement", "Bingwa customer update", "Bingwa customers: today there is a bundle offer. {offers}. Till {till}. Normal prices tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("announce_4", "Offer announcement", "Offer alert today", "Offer alert today: Bingwa {offers}. Pay Till {till}. Normal prices resume tomorrow. Help {help}", true, true),
        SmsMarketingTemplate("announce_5", "Offer announcement", "Heads up - offer today", "Heads up: Bingwa offer is valid today only. {offers}. Till {till}. Normal prices resume tomorrow. Help {help}", true, true),

        SmsMarketingTemplate("service_1", "Service improvement", "Faster service update", "Good news! Our Bingwa services have improved. No more delays. Enjoy faster bundles. Help {help}"),
        SmsMarketingTemplate("service_2", "Service improvement", "Personal service update", "Hi {first_name}, we've improved our Bingwa services. No more delays - enjoy faster bundles today. Help {help}"),
        SmsMarketingTemplate("service_3", "Service improvement", "Bingwa service update", "Bingwa update: our services are now faster with no delays. Thank you for your patience. Help {help}"),
        SmsMarketingTemplate("service_4", "Service improvement", "Delivery improvement", "We've upgraded our Bingwa services. Expect faster delivery with no more delays. Thank you for staying with us. Help {help}"),
        SmsMarketingTemplate("service_5", "Service improvement", "Thank you for your patience", "Thank you for your patience. Our Bingwa services are now faster with no delays. Enjoy! Help {help}"),

        SmsMarketingTemplate("combined_1", "Service + offers", "Faster service and today's offer", "Our Bingwa services are now faster with no delays! Offer: {offers}. Till {till}. Help {help}", true, true),
        SmsMarketingTemplate("combined_2", "Service + offers", "Improved service and offer", "Our Bingwa services improved, no more delays! Offer: {offers}. Till {till}. Help {help}", true, true),
        SmsMarketingTemplate("combined_3", "Service + offers", "Faster bundles and offers", "Our Bingwa services are faster now, no delays. Offer: {offers}. Till {till}. Help {help}", true, true),
        SmsMarketingTemplate("combined_4", "Service + offers", "Personalized service and offer", "Hi {first_name}, our Bingwa services are faster with no delays! Offer: {offers}. Till {till}. Help {help}", true, true),
        SmsMarketingTemplate("combined_5", "Service + offers", "Today's offer and faster service", "Our Bingwa services are now faster, no delays! Today only: {offers}. Till {till}. Normal prices tomorrow. Help {help}", true, true)
    )

    private const val CUSTOM_KEY = "custom_sms_templates"

    /** User-created templates are stored separately so built-in campaign templates remain intact. */
    fun custom(context: Context): List<SmsMarketingTemplate> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CUSTOM_KEY, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val title = item.optString("title")
                val body = item.optString("body")
                if (id.isBlank() || title.isBlank() || body.isBlank()) null
                else SmsMarketingTemplate(id, "My templates", title, body)
            }
        }.getOrDefault(emptyList())
    }

    fun saveCustom(context: Context, template: SmsMarketingTemplate) {
        require(template.id.startsWith("custom_")) { "Only custom templates can be edited here." }
        require(template.title.isNotBlank() && template.body.isNotBlank()) { "Enter a title and message." }
        val values = custom(context).filterNot { it.id == template.id } + template.copy(
            category = "My templates",
            usesOffers = false,
            usesTill = false
        )
        val array = JSONArray()
        values.forEach { value ->
            array.put(org.json.JSONObject()
                .put("id", value.id)
                .put("title", value.title)
                .put("body", value.body))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CUSTOM_KEY, array.toString()).apply()
    }

    fun deleteCustom(context: Context, id: String) {
        val array = JSONArray()
        custom(context).filterNot { it.id == id }.forEach { value ->
            array.put(org.json.JSONObject()
                .put("id", value.id)
                .put("title", value.title)
                .put("body", value.body))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CUSTOM_KEY, array.toString()).apply()
    }

    fun find(context: Context, id: String?): SmsMarketingTemplate? {
        if (id.isNullOrBlank()) return null
        return (all + custom(context)).firstOrNull { it.id == id }
    }

    fun estimate(message: String): SmsSegmentEstimate {
        val characters = message.codePointCount(0, message.length)
        if (message.isEmpty()) return SmsSegmentEstimate(0, 0, SmsEncoding.GSM_7, 0)
        val gsm = message.all { it in BASIC_GSM || it in EXTENDED_GSM }
        if (gsm) {
            val units = message.fold(0) { total, character -> total + if (character in EXTENDED_GSM) 2 else 1 }
            // Concatenated GSM-7 messages carry 153 septets per segment.
            val segments = if (units <= 160) 1 else ceil(units / 153.0).toInt()
            return SmsSegmentEstimate(characters, units, SmsEncoding.GSM_7, segments)
        }
        val units = message.length
        // Concatenated Unicode messages carry 67 UTF-16 code units per segment.
        val segments = if (units <= 70) 1 else ceil(units / 67.0).toInt()
        return SmsSegmentEstimate(characters, units, SmsEncoding.UNICODE, segments)
    }

    fun cost(message: String, recipients: Int, provider: SmsProvider?, context: Context): SmsCostEstimate {
        val segment = estimate(message)
        val count = recipients.coerceAtLeast(0)
        val rate = provider?.let { rate(context, it) }
        val totalSegments = segment.segments * count
        return SmsCostEstimate(
            count, segment.segments, totalSegments, rate,
            rate?.let { it * totalSegments }, segment.encoding, segment.characters, segment.encodedUnits
        )
    }

    fun rate(context: Context, provider: SmsProvider): Double? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configured = prefs.getString("rate_${provider.name}", null)?.toDoubleOrNull()
        if (configured != null && configured > 0.0) return configured
        val learned = prefs.getString("learned_rate_${provider.name}", null)?.toDoubleOrNull()?.takeIf { it > 0.0 }
        if (learned != null) return learned
        if (provider == SmsProvider.TALKSASA) return 0.35
        return null
    }

    fun rateSource(context: Context, provider: SmsProvider): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("rate_${provider.name}", null)?.toDoubleOrNull()?.let { it > 0 } == true) return "Configured rate"
        if (prefs.getString("learned_rate_${provider.name}", null)?.toDoubleOrNull()?.let { it > 0 } == true) return "Learned from invoice/history"
        if (provider == SmsProvider.TALKSASA) return "TalkSasa default rate"
        return "Rate not configured"
    }

    fun saveRate(context: Context, provider: SmsProvider, rate: Double) {
        require(rate > 0.0 && rate <= 1000.0) { "Enter a rate greater than zero." }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rate_${provider.name}", rate.toString()).apply()
    }

    fun clearRate(context: Context, provider: SmsProvider) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("rate_${provider.name}").apply()
    }

    /** Learn an estimated KES/segment rate from an entered provider invoice and accepted message history. */
    fun calibrateFromInvoice(context: Context, provider: SmsProvider, invoiceKes: Double): Pair<Double, Int> {
        require(invoiceKes > 0.0) { "Enter the provider's billed amount in KES." }
        val accepted = SmsMessageHistory.load(context).filter {
            it.optString("provider") == provider.name && it.optString("status") == "Accepted"
        }
        val segments = accepted.sumOf { estimate(it.optString("text")).segments }
        require(segments > 0) { "No accepted messages for this provider are recorded yet." }
        val effectiveRate = invoiceKes / segments.toDouble()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("learned_rate_${provider.name}", effectiveRate.toString())
            .putLong("learned_segments_${provider.name}", segments.toLong())
            .putLong("learned_at_${provider.name}", System.currentTimeMillis())
            .apply()
        return effectiveRate to segments
    }

    fun render(template: SmsMarketingTemplate, context: Context, till: String, help: String, firstName: String = ""): String {
        val activeOffers = OfferRepository.load(context)
            .filter { it.enabled }
            .sortedBy { it.price }
        val offerText = activeOffers.joinToString(", ") { offer ->
            "${offer.price}=${offer.bundleSize.ifBlank { offer.name }}"
        }.ifBlank { "Check available Bingwa offers" }
        val businessName = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("business_name", "Bingwa Sokoni").orEmpty().ifBlank { "Bingwa Sokoni" }
        return template.body
            .replace("Bingwa Sokoni", "{business}")
            .replace("Bingwa", "{business}")
            .replace("{business}", businessName)
            .replace("{offers}", offerText)
            .replace("{till}", till.trim())
            .replace("{help}", help.trim())
            .replace("{first_name}", firstName.trim().ifBlank { "customer" })
    }

    fun businessDetails(context: Context): Triple<String, String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Triple(prefs.getString("business_name", "Bingwa Sokoni").orEmpty(),
            prefs.getString("till", "").orEmpty(), prefs.getString("help", "").orEmpty())
    }

    fun saveBusinessDetails(context: Context, businessName: String, till: String, help: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("business_name", businessName.trim().take(60))
            .putString("till", till.trim().take(24)).putString("help", help.trim().take(24)).apply()
    }
}
