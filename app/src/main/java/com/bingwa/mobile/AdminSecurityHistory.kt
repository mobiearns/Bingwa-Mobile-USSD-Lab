package com.bingwa.mobile

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Owner authorization and a seven-day audit trail for Remote Admin number changes.
 * Audit expiry is deliberately independent from the active admin_phone preference.
 */
object AdminSecurityHistory {
    private const val HISTORY_PREFS = "admin_security_history"
    private const val HISTORY_KEY = "events"
    private const val HISTORY_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    private val lock = Any()

    data class Event(
        val phone: String,
        val action: String,
        val timestamp: Long
    )

    fun isReady(context: Context): Boolean =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("admin_security_ready", false)

    fun enableAfterOwnerAuthentication(context: Context) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("admin_security_ready", true).apply()
        record(context, context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getString("admin_phone", "").orEmpty(), "OWNER_PROTECTION_ENABLED")
    }

    fun recordAdminChange(context: Context, oldPhone: String, newPhone: String) {
        val oldValue = oldPhone.trim()
        val newValue = newPhone.trim()
        if (oldValue == newValue) return
        if (oldValue.isNotBlank()) record(context, oldValue, "ADMIN_REMOVED_OR_REPLACED")
        if (newValue.isNotBlank()) record(context, newValue, "ADMIN_NUMBER_ADDED")
    }

    fun record(context: Context, phone: String, action: String) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val events = readArray(prefs.getString(HISTORY_KEY, null))
            val retained = JSONArray()
            for (i in 0 until events.length()) {
                val item = events.optJSONObject(i) ?: continue
                val timestamp = item.optLong("timestamp", 0L)
                if (timestamp in (now - HISTORY_WINDOW_MS + 1)..now) retained.put(item)
            }
            retained.put(JSONObject().apply {
                put("phone", phone.trim())
                put("action", action)
                put("timestamp", now)
            })
            prefs.edit().putString(HISTORY_KEY, retained.toString()).commit()
        }
    }

    fun load(context: Context): List<Event> = synchronized(lock) {
        val prefs = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val events = readArray(prefs.getString(HISTORY_KEY, null))
        val retained = JSONArray()
        val result = mutableListOf<Event>()
        for (i in 0 until events.length()) {
            val item = events.optJSONObject(i) ?: continue
            val timestamp = item.optLong("timestamp", 0L)
            if (timestamp <= 0L || timestamp < now - HISTORY_WINDOW_MS || timestamp > now) continue
            val event = Event(item.optString("phone"), item.optString("action"), timestamp)
            retained.put(item)
            result += event
        }
        prefs.edit().putString(HISTORY_KEY, retained.toString()).commit()
        result.sortedByDescending { it.timestamp }
    }

    fun pruneExpired(context: Context) {
        load(context)
    }

    fun authenticateOwner(
        context: Context,
        purpose: String,
        attemptedPhone: String = "",
        onResult: (Boolean) -> Unit
    ) {
        val activity = context.findFragmentActivity()
        if (activity == null) {
            Toast.makeText(context, "Owner authentication is unavailable. Reopen Bingwa Mobile and try again.", Toast.LENGTH_LONG).show()
            onResult(false)
            return
        }

        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify phone owner")
            .setSubtitle(purpose)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(authenticators)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    record(context, attemptedPhone, "OWNER_AUTHENTICATION_FAILED")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        record(context, attemptedPhone, "OWNER_AUTHENTICATION_ERROR")
                    }
                    onResult(false)
                }
            }
        )
        try {
            prompt.authenticate(builder.build())
        } catch (error: Exception) {
            Toast.makeText(context, "Set a secure screen lock (PIN, pattern or password) before using Remote Control.", Toast.LENGTH_LONG).show()
            onResult(false)
        }
    }

    private fun readArray(raw: String?): JSONArray =
        runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }

    private fun Context.findFragmentActivity(): FragmentActivity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return current as? FragmentActivity
    }
}
