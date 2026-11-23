package com.bingwa.mobile

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object SignatureActivityStore {
    private const val PREFS = "signature_management"
    private const val KEY_ACTIVITY = "activity"

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVITY, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?.take(50)
            .orEmpty()

    fun record(context: Context, message: String): List<String> {
        val entry = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault()).format(Date()) + " · " + message
        val next = (listOf(entry) + load(context)).take(50)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVITY, next.joinToString("\n"))
            .apply()
        return next
    }
}

internal fun recordSignatureActivity(context: Context, message: String) {
    SignatureActivityStore.record(context, message)
}
