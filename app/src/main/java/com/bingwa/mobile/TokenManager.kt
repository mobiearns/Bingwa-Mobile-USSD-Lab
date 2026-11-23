package com.bingwa.mobile

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class TokenManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TokenStore", Context.MODE_PRIVATE)

    fun getBalance(): Int = synchronized(LOCK) {
        if (prefs.safeGetBoolean(KEY_TAMPERED, false)) {
            return 0
        }
        val raw = prefs.safeGetInt(KEY_BALANCE, 0).coerceAtLeast(0)
        val fromRelay = prefs.safeGetBoolean(KEY_TEMPORARY_RELAY_BALANCE, false)
        val storedSig = prefs.safeGetString(KEY_SIGNATURE, null)

        if (storedSig == null) {
            // First run or initial migration: establish authentic cryptographic signature
            val initialSig = computeSignature(raw, fromRelay)
            prefs.edit().putString(KEY_SIGNATURE, initialSig).apply()
            return raw
        }

        val expectedSig = computeSignature(raw, fromRelay)
        if (storedSig != expectedSig) {
            Log.e(TAG, "SECURITY ALERT: Token balance was tampered with! Resetting to 0.")
            prefs.edit()
                .putInt(KEY_BALANCE, 0)
                .putBoolean(KEY_TAMPERED, true)
                .putString(KEY_SIGNATURE, computeSignature(0, fromRelay = false))
                .apply()
            tokenBalanceListener?.invoke(0)
            return 0
        }
        return raw
    }

    fun addTokens(amount: Int) {
        if (amount <= 0) return
        val newBalance = synchronized(LOCK) {
            val cur = getBalance()
            val safeAdd = (cur.toLong() + amount.toLong()).coerceAtMost(MAX_TOKENS.toLong()).toInt()
            setBalance(safeAdd, fromRelay = false)
            safeAdd
        }
        // Token recovery is event-driven: retry only the transactions blocked by a zero balance.
        AutomaticTransactionRecovery.onTokensAvailable(context.applicationContext, newBalance)
    }

    fun spendTokens(amount: Int): Boolean = synchronized(LOCK) {
        if (amount <= 0) return false
        val cur = getBalance()
        if (cur < amount) return false
        setBalance(cur - amount, fromRelay = false)
        return true
    }

    fun clearBalance(clearUnlimited: Boolean = true) = synchronized(LOCK) {
        setBalance(0, fromRelay = false, notify = true)
        if (clearUnlimited) {
            UnlimitedManager(context).clear()
        }
    }

    fun setBalanceFromRelay(amount: Int) {
        val newBalance = synchronized(LOCK) {
            val clean = amount.coerceAtLeast(0)
            setBalance(clean, fromRelay = true)
            clean
        }
        AutomaticTransactionRecovery.onTokensAvailable(context.applicationContext, newBalance)
    }

    fun clearTemporaryRelayBalance(notify: Boolean = true) = synchronized(LOCK) {
        if (!prefs.safeGetBoolean(KEY_TEMPORARY_RELAY_BALANCE, false)) return
        val sig = computeSignature(0, fromRelay = false)
        prefs.edit()
            .putInt(KEY_BALANCE, 0)
            .putBoolean(KEY_TEMPORARY_RELAY_BALANCE, false)
            .putString(KEY_SIGNATURE, sig)
            .apply()
        if (notify) tokenBalanceListener?.invoke(0)
    }

    private fun setBalance(amount: Int, fromRelay: Boolean, notify: Boolean = true) {
        val clean = amount.coerceIn(0, MAX_TOKENS)
        val sig = computeSignature(clean, fromRelay)
        prefs.edit()
            .putInt(KEY_BALANCE, clean)
            .putBoolean(KEY_TEMPORARY_RELAY_BALANCE, fromRelay)
            .putBoolean(KEY_TAMPERED, false)
            .putString(KEY_SIGNATURE, sig)
            .apply()
        if (notify) tokenBalanceListener?.invoke(clean)
        BalanceAlarmController.evaluateTokens(context.applicationContext, clean)
        if (!fromRelay) RelayManager.syncTokenBalance(context, clean)
    }

    private fun computeSignature(balance: Int, fromRelay: Boolean): String {
        return try {
            val deviceId = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "default_device"
            } catch (_: Exception) {
                "default_device"
            }
            val payload = "bingwa_token:$balance:$fromRelay:$deviceId:${Build.BOARD}"
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(SECRET_SALT.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val hash = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "HMAC signature calculation failed", e)
            ""
        }
    }

    companion object {
        private const val TAG = "TokenManager"
        private const val KEY_BALANCE = "balance"
        private const val KEY_SIGNATURE = "token_integrity_sig"
        private const val KEY_TAMPERED = "token_tampered"
        private const val KEY_TEMPORARY_RELAY_BALANCE = "temporary_relay_balance"
        private const val SECRET_SALT = "B1NGW4_M0B1L3_T0K3N_S3CUR1TY_K3Y_v2"
        private const val MAX_TOKENS = 1_000_000
        private val LOCK = Any()

        var tokenBalanceListener: ((Int) -> Unit)? = null

        fun convertAmountToTokens(ksh: Int): Int = when (ksh) {
            15 -> 105
            25 -> 255
            55 -> 605
            100 -> 1200
            else -> ksh
        }

        fun hasActiveAccess(context: Context): Boolean {
            if (UnlimitedManager(context).isActive()) return true
            return TokenManager(context).getBalance() > 0
        }
    }
}
