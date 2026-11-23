package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class CustomerProfile(
    val group: String = "Regular",
    val notes: List<CustomerNote> = emptyList()
)

internal data class CustomerNote(
    val text: String,
    val createdAt: Long = System.currentTimeMillis()
)

internal object CustomerProfileStore {
    private const val PREFS = "customer_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_BLOCK_REASONS = "block_reasons"
    private val lock = Any()

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun phoneKey(phone: String): String =
        SmsCommandHandler.normalizePhone(phone)

    fun load(context: Context, phone: String): CustomerProfile = synchronized(lock) {
        val key = phoneKey(phone)
        val raw = preferences(context).getString(KEY_PROFILES, "{}") ?: "{}"
        runCatching {
            val item = JSONObject(raw).optJSONObject(key) ?: return@synchronized CustomerProfile()
            val notesJson = item.optJSONArray("notes") ?: JSONArray()
            val notes = (0 until notesJson.length()).mapNotNull { index ->
                val note = notesJson.optJSONObject(index) ?: return@mapNotNull null
                val text = note.optString("text").trim()
                if (text.isBlank()) null else CustomerNote(text, note.optLong("createdAt", 0L))
            }
            CustomerProfile(
                group = item.optString("group", "Regular").takeIf { it in GROUPS } ?: "Regular",
                notes = notes
            )
        }.getOrDefault(CustomerProfile())
    }

    fun save(context: Context, phone: String, profile: CustomerProfile) = synchronized(lock) {
        val key = phoneKey(phone)
        if (!key.matches(Regex("^0\\d{9}$"))) return@synchronized
        val prefs = preferences(context)
        val root = runCatching { JSONObject(prefs.getString(KEY_PROFILES, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        val notes = JSONArray().apply {
            profile.notes.takeLast(100).forEach { note ->
                put(JSONObject().put("text", note.text).put("createdAt", note.createdAt))
            }
        }
        root.put(key, JSONObject().put("group", profile.group.takeIf { it in GROUPS } ?: "Regular").put("notes", notes))
        prefs.edit().putString(KEY_PROFILES, root.toString()).apply()
    }

    fun addNote(context: Context, phone: String, text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val current = load(context, phone)
        save(context, phone, current.copy(notes = current.notes + CustomerNote(clean)))
    }

    fun blockReason(context: Context, phone: String): String {
        val key = phoneKey(phone)
        return runCatching {
            JSONObject(preferences(context).getString(KEY_BLOCK_REASONS, "{}") ?: "{}")
                .optString(key, "")
        }.getOrDefault("")
    }

    fun saveBlockReason(context: Context, phone: String, reason: String) = synchronized(lock) {
        val key = phoneKey(phone)
        if (!key.matches(Regex("^0\\d{9}$"))) return@synchronized
        val prefs = preferences(context)
        val root = runCatching { JSONObject(prefs.getString(KEY_BLOCK_REASONS, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        if (reason.isBlank()) root.remove(key) else root.put(key, reason.trim().take(240))
        prefs.edit().putString(KEY_BLOCK_REASONS, root.toString()).apply()
    }

    fun mergeProfiles(context: Context, sourcePhone: String, targetPhone: String) {
        val source = load(context, sourcePhone)
        val target = load(context, targetPhone)
        val mergedNotes = (target.notes + source.notes)
            .distinctBy { it.createdAt to it.text }
            .sortedBy { it.createdAt }
            .takeLast(100)
        save(context, targetPhone, target.copy(
            group = if (target.group != "Regular") target.group else source.group,
            notes = mergedNotes
        ))
    }

    val GROUPS = listOf("Regular", "VIP", "New", "Wholesale")
}
