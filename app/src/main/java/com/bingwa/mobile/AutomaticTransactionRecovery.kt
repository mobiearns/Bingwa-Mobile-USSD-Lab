package com.bingwa.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Event-driven recovery for the two user-configured cases:
 * 1) insufficient airtime -> retry after a real airtime top-up SMS;
 * 2) zero tokens -> retry after tokens are credited.
 *
 * Each retry reuses the original transaction ID. Alarms are per transaction and per recovery
 * reason, and an in-flight lease prevents duplicate SMS/token events from dispatching twice.
 */
internal object AutomaticTransactionRecovery {
    private const val TAG = "AutoTxRecovery"
    private const val PREFS = "automatic_transaction_recovery"
    private const val AIRTIME_DELAY_MS = 8_000L
    private const val TOKEN_DELAY_MS = 8_000L
    private const val RECENT_TOPUP_WINDOW_MS = 10 * 60_000L
    private const val KEY_LAST_TOPUP_AT = "last_airtime_topup_detected_at"
    private const val IN_FLIGHT_LEASE_MS = 5 * 60_000L
    private val lock = Any()

    private const val KEY_AIRTIME_ENABLED = "auto_retry_insufficient_airtime"
    private const val KEY_TOKENS_ENABLED = "auto_retry_zero_tokens"

    fun onAirtimeTopUpDetected(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_TOPUP_AT, System.currentTimeMillis())
            .apply()
        if (!enabled(app, KEY_AIRTIME_ENABLED)) return
        TransactionStore.load(app).asSequence()
            .filter { it.statusEnum == TransactionStatus.FAILED }
            .filter(::isRetryableCommercialTransaction)
            .filter { isInsufficientAirtimeResponse(it.ussdResponse) }
            .forEach { schedule(app, it, AutomationService.ACTION_RETRY_AIRTIME_TOPUP, true, AIRTIME_DELAY_MS) }
    }

    fun onZeroTokenBlocked(context: Context, txId: Int) {
        val app = context.applicationContext
        if (!enabled(app, KEY_TOKENS_ENABLED)) return
        if (!UnlimitedManager(app).isActive() && TokenManager(app).getBalance() <= 0) return
        val tx = TransactionStore.findById(app, txId) ?: return
        if (tx.statusEnum == TransactionStatus.FAILED && isInsufficientTokensResponse(tx.ussdResponse)) {
            schedule(app, tx, AutomationService.ACTION_RETRY_ZERO_TOKENS, false, TOKEN_DELAY_MS)
        }
    }

    fun onTransactionFailed(context: Context, txId: Int, response: String) {
        val app = context.applicationContext
        if (!enabled(app, KEY_AIRTIME_ENABLED) || !isInsufficientAirtimeResponse(response)) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val topupAt = prefs.getLong(KEY_LAST_TOPUP_AT, 0L)
        if (topupAt <= 0L || System.currentTimeMillis() - topupAt !in 0L..RECENT_TOPUP_WINDOW_MS) return
        val tx = TransactionStore.findById(app, txId) ?: return
        if (tx.statusEnum == TransactionStatus.FAILED) {
            schedule(app, tx, AutomationService.ACTION_RETRY_AIRTIME_TOPUP, true, AIRTIME_DELAY_MS)
        }
    }

    fun onTokensAvailable(context: Context, newBalance: Int) {
        val app = context.applicationContext
        if (newBalance <= 0 || !enabled(app, KEY_TOKENS_ENABLED)) return
        TransactionStore.load(app).asSequence()
            .filter { it.statusEnum == TransactionStatus.FAILED }
            .filter(::isRetryableCommercialTransaction)
            .filter { isInsufficientTokensResponse(it.ussdResponse) }
            .forEach { schedule(app, it, AutomationService.ACTION_RETRY_ZERO_TOKENS, false, TOKEN_DELAY_MS) }
    }

    fun begin(context: Context, txId: Int, action: String): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val airtimeRetry = action == AutomationService.ACTION_RETRY_AIRTIME_TOPUP
        val tokensRetry = action == AutomationService.ACTION_RETRY_ZERO_TOKENS
        if (!airtimeRetry && !tokensRetry) return false
        if (!app.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getBoolean("automation_enabled", true)
        ) return false
        val pendingType = if (airtimeRetry) "airtime" else "tokens"
        prefs.edit().remove("tx_${txId}_${pendingType}_scheduled").apply()
        val tx = TransactionStore.findById(app, txId) ?: return false
        if (tx.statusEnum != TransactionStatus.FAILED) return false
        if (!isRetryableCommercialTransaction(tx)) return false
        if (airtimeRetry) {
            if (!enabled(app, KEY_AIRTIME_ENABLED) ||
                !isInsufficientAirtimeResponse(tx.ussdResponse)
            ) return false
        }
        if (tokensRetry) {
            if (!enabled(app, KEY_TOKENS_ENABLED) ||
                !isInsufficientTokensResponse(tx.ussdResponse)
            ) return false
            if (!UnlimitedManager(app).isActive() && TokenManager(app).getBalance() <= 0) return false
        }

        val inFlightKey = "tx_${txId}_in_flight_at"
        synchronized(lock) {
            val inFlightAt = prefs.getLong(inFlightKey, 0L)
            if (inFlightAt > 0L && System.currentTimeMillis() - inFlightAt < IN_FLIGHT_LEASE_MS) {
                Log.d(TAG, "Skipping duplicate recovery dispatch txId=$txId")
                return false
            }
            prefs.edit()
                .putLong(inFlightKey, System.currentTimeMillis())
                .remove("tx_${txId}_airtime_scheduled")
                .remove("tx_${txId}_tokens_scheduled")
                .apply()
        }

        val phone = SmsCommandHandler.normalizePhone(tx.phoneNumber).ifBlank { tx.phoneNumber.trim() }
        val offer = resolveOffer(app, tx)
        val code = UssdHelper.normalizeUssdCode(tx.ussdCode.ifBlank { offer?.ussdCode.orEmpty() }, phone)
        if (phone.isBlank() || code.isBlank()) {
            release(app, txId)
            return false
        }

        val restarted = TransactionStore.restartExisting(
            context = app,
            txId = txId,
            description = offer?.name ?: tx.description,
            amount = tx.amount,
            phone = phone,
            ussd = code,
            clientName = tx.clientName,
            status = TransactionStatus.PROCESSING.value,
            source = tx.source,
            showInRecent = tx.showInRecent,
            offerId = offer?.id ?: tx.offerId,
            response = "Automatic retry started."
        )
        if (!restarted) {
            release(app, txId)
            return false
        }
        app.sendBroadcast(
            Intent("com.bingwa.mobile.TX_UPDATED")
                .setPackage(app.packageName)
                .putExtra("txId", txId)
                .putExtra("status", TransactionStatus.PROCESSING.value)
        )
        return true
    }

    fun reschedulePending(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val transactions = TransactionStore.load(app)
        transactions.forEach { tx ->
            if (tx.statusEnum != TransactionStatus.FAILED || !isRetryableCommercialTransaction(tx)) return@forEach
            val airtimeKey = "tx_${tx.id}_airtime_scheduled"
            if (prefs.getBoolean(airtimeKey, false)) {
                prefs.edit().remove(airtimeKey).apply()
                if (enabled(app, KEY_AIRTIME_ENABLED) &&
                    isInsufficientAirtimeResponse(tx.ussdResponse)
                ) {
                    schedule(app, tx, AutomationService.ACTION_RETRY_AIRTIME_TOPUP, true, AIRTIME_DELAY_MS)
                }
            }
            val tokensKey = "tx_${tx.id}_tokens_scheduled"
            if (prefs.getBoolean(tokensKey, false)) {
                prefs.edit().remove(tokensKey).apply()
                if (enabled(app, KEY_TOKENS_ENABLED) &&
                    (UnlimitedManager(app).isActive() || TokenManager(app).getBalance() > 0) &&
                    isInsufficientTokensResponse(tx.ussdResponse)
                ) {
                    schedule(app, tx, AutomationService.ACTION_RETRY_ZERO_TOKENS, false, TOKEN_DELAY_MS)
                }
            }
        }
    }

    fun onAttemptFinished(context: Context, txId: Int, status: String) {
        if (txId < 0) return
        val app = context.applicationContext
        release(app, txId)
        if (status.equals(TransactionStatus.SUCCESS.value, ignoreCase = true)) {
            cancelPending(app, txId, AutomationService.ACTION_RETRY_AIRTIME_TOPUP)
            cancelPending(app, txId, AutomationService.ACTION_RETRY_ZERO_TOKENS)
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("tx_${txId}_airtime_scheduled")
                .remove("tx_${txId}_tokens_scheduled")
                .apply()
        }
    }

    private fun schedule(
        context: Context,
        tx: Transaction,
        action: String,
        tokenPreDeducted: Boolean,
        delayMs: Long
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val type = if (action == AutomationService.ACTION_RETRY_AIRTIME_TOPUP) "airtime" else "tokens"
        val scheduledKey = "tx_${tx.id}_${type}_scheduled"
        synchronized(lock) {
            if (prefs.getBoolean(scheduledKey, false)) return
            val offer = resolveOffer(context, tx)
            val phone = SmsCommandHandler.normalizePhone(tx.phoneNumber).ifBlank { tx.phoneNumber.trim() }
            val code = UssdHelper.normalizeUssdCode(tx.ussdCode.ifBlank { offer?.ussdCode.orEmpty() }, phone)
            if (phone.isBlank() || code.isBlank()) return
            val requestCode = 31 * tx.id + if (type == "airtime") 1 else 2
            val intent = Intent(context, AutomationService::class.java).apply {
                this.action = action
                putExtra("mode", offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE)
                putExtra("code", code)
                putExtra("phoneNumber", phone)
                putExtra("txId", tx.id)
                putExtra("offerId", offer?.id ?: tx.offerId)
                putExtra("offerName", offer?.name ?: tx.description)
                putExtra("simSelection", offer?.simSelection ?: currentUssdSimSelection(context))
                putExtra("signatureEnabled", offer?.signatureDetectionEnabled == true)
                putExtra("signatureMode", offer?.signatureAction ?: "STOP")
                putExtra("signatureLearning", false)
                putExtra("returnToAppAggressively", true)
                putExtra("tokenPreDeducted", tokenPreDeducted)
            }
            val pendingIntent = PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val scheduled = AlarmCompat.scheduleRtcWakeup(
                context = context,
                triggerAtMillis = System.currentTimeMillis() + delayMs,
                pendingIntent = pendingIntent,
                preferExact = true,
                allowWhileIdle = true
            )
            if (scheduled) {
                prefs.edit().putBoolean(scheduledKey, true).apply()
                Log.i(TAG, "Scheduled $type recovery txId=${tx.id} in ${delayMs}ms")
            } else {
                Log.w(TAG, "Could not schedule $type recovery txId=${tx.id}")
            }
        }
    }

    private fun cancelPending(context: Context, txId: Int, action: String) {
        val type = if (action == AutomationService.ACTION_RETRY_AIRTIME_TOPUP) "airtime" else "tokens"
        val requestCode = 31 * txId + if (type == "airtime") 1 else 2
        runCatching {
            val intent = Intent(context, AutomationService::class.java).apply { this.action = action }
            val pendingIntent = PendingIntent.getService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
            ) ?: return
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarm?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun release(context: Context, txId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("tx_${txId}_in_flight_at")
            .apply()
    }

    private fun enabled(context: Context, key: String): Boolean =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).getBoolean(key, false)

    private fun isRetryableCommercialTransaction(tx: Transaction): Boolean =
        tx.phoneNumber.isNotBlank() &&
            tx.ussdCode.isNotBlank() &&
            tx.source != TX_SOURCE_AIRTIME &&
            tx.source != TX_SOURCE_SYSTEM

    private fun resolveOffer(context: Context, tx: Transaction): OfferItem? {
        if (tx.offerId >= 0) OfferRepository.findById(context, tx.offerId)?.let { return it }
        OfferRepository.findByName(context, tx.description)?.let { return it }
        return OfferRepository.load(context).firstOrNull { offer ->
            UssdHelper.normalizeUssdCode(offer.ussdCode, tx.phoneNumber) ==
                UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
        }
    }

    internal fun isInsufficientAirtimeFailure(value: String): Boolean =
        isInsufficientAirtimeResponse(value)

    private fun isInsufficientAirtimeResponse(value: String): Boolean {
        val text = value.lowercase().replace(Regex("\\s+"), " ").trim()
        return listOf(
            "insufficient airtime", "insufficient account balance", "insufficient balance",
            "balance is insufficient", "not enough balance", "not enough airtime",
            "airtime balance is too low", "airtime balance insufficient", "airtime credit insufficient",
            "balance is too low", "you have insufficient credit", "huna salio la kutosha",
            "huna salio la kutosha kununua", "salio lako halitoshi", "salio halitoshi",
            "you do not have enough airtime", "you don't have enough airtime", "low airtime balance"
        ).any(text::contains)
    }

    private fun isInsufficientTokensResponse(value: String): Boolean {
        val text = value.lowercase().replace(Regex("\\s+"), " ").trim()
        return text.contains("insufficient tokens") ||
            text.contains("not enough tokens") ||
            text.contains("token balance is zero") ||
            text.contains("no tokens available")
    }
}
