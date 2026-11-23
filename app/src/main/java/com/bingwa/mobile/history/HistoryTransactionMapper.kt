package com.bingwa.mobile.history

import com.bingwa.mobile.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Maps the app's persisted transaction model into the uploaded History UI model. */
fun Transaction.toHistoryRecord(): HistoryRecord {
    val timestamp = transactionTimestamp(this).takeIf { it > 0L } ?: System.currentTimeMillis()
    val now = System.currentTimeMillis()
    val date = Date(timestamp)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now))
    val recordDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(date)
    val yesterday = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now - 86_400_000L))
    val dayLabel = when (recordDay) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
    }
    val dateLabel = SimpleDateFormat("MMM dd", Locale.getDefault()).format(date)
    val timeLabel = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    val delta = timestamp - now
    val ago = when {
        delta > 60_000L -> "in ${delta / 3_600_000L}h"
        delta > 0L -> "in ${(delta / 60_000L).coerceAtLeast(1L)}m"
        -delta < 60_000L -> "Just now"
        -delta < 3_600_000L -> "${(-delta / 60_000L)}m ago"
        -delta < 86_400_000L -> "${(-delta / 3_600_000L)}h ago"
        else -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(date)
    }
    val scheduled = DailyLimitPolicy.isDailyLimitHold(this) ||
        this.status.equals("Scheduled", ignoreCase = true) ||
        this.status.equals("UnderMaintenance", ignoreCase = true)
    val execStatus = when {
        scheduled -> ExecStatus.Scheduled
        statusEnum == TransactionStatus.SUCCESS -> ExecStatus.Completed
        statusEnum == TransactionStatus.FAILED -> ExecStatus.Failed
        statusEnum == TransactionStatus.CANCELLED -> ExecStatus.Cancelled
        statusEnum == TransactionStatus.RETRYING -> ExecStatus.Delayed
        statusEnum == TransactionStatus.PROCESSING -> ExecStatus.Processing
        else -> ExecStatus.Pending
    }
    val sourceType = when {
        scheduled -> ExecType.Scheduled
        this.source == TX_SOURCE_MANUAL -> ExecType.Manual
        else -> ExecType.Automated
    }
    val amount = amountValue.takeIf { it > 0.0 }
        ?: Regex("""\d+(?:\.\d+)?""").find(this.amount)?.value?.toDoubleOrNull()
        ?: 0.0
    val durationMs = when {
        executionDurationMs > 0L -> executionDurationMs
        completedAt > 0L && timestamp > 0L -> (completedAt - timestamp).coerceAtLeast(0L)
        else -> 0L
    }
    val duration = when {
        durationMs <= 0L -> null
        durationMs < 60_000L -> "${durationMs}ms"
        else -> "${durationMs / 60_000L}m ${(durationMs / 1_000L) % 60L}s"
    }
    val failure = if (execStatus == ExecStatus.Failed || execStatus == ExecStatus.TimedOut) {
        transactionReasonShort(this).takeIf { it.isNotBlank() }
    } else null
    val responseText = buildList {
        if (ussdResponse.isNotBlank()) add(ussdResponse.trim())
        if (ussdTranscript.isNotBlank() && ussdTranscript.trim() != ussdResponse.trim()) add(ussdTranscript.trim())
    }.joinToString("\n\n").takeIf { it.isNotBlank() }
    return HistoryRecord(
        id = id.toLong(),
        name = clientName.ifBlank { description.ifBlank { "Unknown customer" } },
        phone = phoneNumber,
        offer = description.ifBlank { if (this.source == TX_SOURCE_AIRTIME) "Airtime" else "Transaction" },
        amountKes = abs(amount).roundToInt(),
        bundle = if (this.source == TX_SOURCE_AIRTIME) "Airtime" else "Bundle",
        type = sourceType,
        sim = "",
        status = execStatus,
        epochMillis = timestamp,
        dayLabel = dayLabel,
        dateLabel = dateLabel,
        timeLabel = timeLabel,
        ago = ago,
        duration = duration,
        rawMessage = response.takeIf { it.isNotBlank() },
        ussd = ussdCode.takeIf { it.isNotBlank() },
        response = responseText,
        error = failure,
        scheduledFor = if (scheduled) "$dateLabel · $timeLabel" else null
    )
}

/** Scheduled and live executions must survive manual clear/delete operations. */
fun Transaction.isProtectedHistoryRecord(): Boolean =
    isTransactionActivelyExecuting(this) ||
        DailyLimitPolicy.isDailyLimitHold(this) ||
        status.equals("Scheduled", ignoreCase = true) ||
        status.equals("UnderMaintenance", ignoreCase = true)
