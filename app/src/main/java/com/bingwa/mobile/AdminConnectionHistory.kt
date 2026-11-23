package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable history of trusted Remote Control admin-phone registrations.
 *
 * This is intentionally separate from AdminSecurityHistory's seven-day security
 * audit. Connection sessions are retained until app data is cleared.
 */
object AdminConnectionHistory {
    private const val PREFS = "admin_connection_history"
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_MIGRATED = "security_audit_migrated"
    private val lock = Any()

    data class Session(
        val phone: String,
        val connectedAt: Long,
        val disconnectedAt: Long
    )

    fun ensureCurrent(context: Context, currentPhone: String): List<Session> = synchronized(lock) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessions = readSessions(prefs.getString(KEY_SESSIONS, null)).toMutableList()
        var changed = false

        // Recover dated registrations still present in the seven-day audit.
        // Older registrations are retained with an unknown start date rather than
        // assigning a fabricated timestamp.
        if (!prefs.getBoolean(KEY_MIGRATED, false)) {
            AdminSecurityHistory.load(context)
                .sortedBy { it.timestamp }
                .forEach { event ->
                    val phone = normalize(event.phone)
                    if (phone.isBlank()) return@forEach
                    when (event.action) {
                        "ADMIN_NUMBER_ADDED" -> {
                            sessions += Session(phone, event.timestamp, 0L)
                            changed = true
                        }
                        "ADMIN_REMOVED_OR_REPLACED" -> {
                            val activeIndex = sessions.indexOfLast {
                                normalize(it.phone) == phone && it.disconnectedAt == 0L
                            }
                            if (activeIndex >= 0) {
                                sessions[activeIndex] = sessions[activeIndex].copy(disconnectedAt = event.timestamp)
                            } else {
                                sessions += Session(phone, 0L, event.timestamp)
                            }
                            changed = true
                        }
                    }
                }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        }

        val current = normalize(currentPhone)
        if (current.isNotBlank() && sessions.none {
                normalize(it.phone) == current && it.disconnectedAt == 0L
            }) {
            // Existing installation: we know this number is currently registered,
            // but its original registration date may predate tracking.
            sessions += Session(current, 0L, 0L)
            changed = true
        }

        if (changed) save(prefs, sessions)
        sessions.sortedByDescending { maxOf(it.connectedAt, it.disconnectedAt) }
    }

    fun recordChange(context: Context, oldPhone: String, newPhone: String) = synchronized(lock) {
        val oldValue = normalize(oldPhone)
        val newValue = normalize(newPhone)
        if (oldValue == newValue) return@synchronized

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessions = readSessions(prefs.getString(KEY_SESSIONS, null)).toMutableList()
        val now = System.currentTimeMillis()

        if (oldValue.isNotBlank()) {
            val activeIndex = sessions.indexOfLast {
                normalize(it.phone) == oldValue && it.disconnectedAt == 0L
            }
            if (activeIndex >= 0) {
                sessions[activeIndex] = sessions[activeIndex].copy(disconnectedAt = now)
            } else {
                // The old number existed before connection tracking was introduced.
                sessions += Session(oldValue, 0L, now)
            }
        }

        if (newValue.isNotBlank()) {
            sessions += Session(newValue, now, 0L)
        }

        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        save(prefs, sessions)
    }

    fun currentFor(sessions: List<Session>, phone: String): Session? {
        val normalized = normalize(phone)
        if (normalized.isBlank()) return null
        return sessions.lastOrNull {
            normalize(it.phone) == normalized && it.disconnectedAt == 0L
        }
    }

    private fun normalize(phone: String): String =
        phone.trim().takeIf { it.isNotBlank() }?.let(SmsCommandHandler::normalizePhone).orEmpty()

    private fun readSessions(raw: String?): List<Session> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val phone = item.optString("phone").trim()
                if (phone.isBlank()) continue
                add(
                    Session(
                        phone = phone,
                        connectedAt = item.optLong("connectedAt", 0L).coerceAtLeast(0L),
                        disconnectedAt = item.optLong("disconnectedAt", 0L).coerceAtLeast(0L)
                    )
                )
            }
        }
    }

    private fun save(
        prefs: android.content.SharedPreferences,
        sessions: List<Session>
    ) {
        val array = JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject().apply {
                    put("phone", session.phone)
                    put("connectedAt", session.connectedAt)
                    put("disconnectedAt", session.disconnectedAt)
                }
            )
        }
        prefs.edit().putString(KEY_SESSIONS, array.toString()).commit()
    }
}
