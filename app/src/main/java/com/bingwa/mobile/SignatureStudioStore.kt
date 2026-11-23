package com.bingwa.mobile

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Settings and menu signatures that are intentionally independent of any offer.
 * Per-offer Simple and Advanced signatures remain in OfferRepository.
 */
data class GeneralMenuSignature(
    val id: String,
    val name: String,
    val keywords: String = "",
    val expectedPosition: Int,
    val selectedOptionLabel: String,
    val menuText: String,
    val menuOptions: List<String> = emptyList(),
    val expectedChoices: Int = 0,
    val enabled: Boolean = true,
    val exactMatch: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

object SignatureStudioStore {
    private const val PREFS = "SignatureStudio"
    private const val KEY_LIVE_MENU_CODE = "live_menu_code"
    private const val KEY_LIVE_MENU_SIM = "live_menu_sim"
    private const val KEY_GENERAL_SIGNATURES = "general_menu_signatures"
    private val gson = Gson()

    fun liveMenuCode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LIVE_MENU_CODE, "") ?: ""

    fun setLiveMenuCode(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LIVE_MENU_CODE, value.trim()).apply()
    }

    fun liveMenuSim(context: Context): Int =
        normalizeOfferSimSelection(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIVE_MENU_SIM, OFFER_SIM_USE_GENERAL))

    fun setLiveMenuSim(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIVE_MENU_SIM, normalizeOfferSimSelection(value)).apply()
    }

    fun loadGeneralSignatures(context: Context): List<GeneralMenuSignature> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GENERAL_SIGNATURES, null)
            ?: return emptyList()
        return runCatching {
            gson.fromJson<List<GeneralMenuSignature>>(raw, object : TypeToken<List<GeneralMenuSignature>>() {}.type).orEmpty()
        }.getOrDefault(emptyList())
    }

    fun saveGeneralSignatures(context: Context, values: List<GeneralMenuSignature>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_GENERAL_SIGNATURES, gson.toJson(values)).apply()
    }
}
