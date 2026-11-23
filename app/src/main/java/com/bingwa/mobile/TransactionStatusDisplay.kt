package com.bingwa.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Status badge for transaction cards showing transaction state
 */
@Composable
fun TransactionStatusBadge(status: String, modifier: Modifier = Modifier) {
    val (backgroundColor, textColor, displayText) = when (status.lowercase()) {
        "success" -> Triple(C.green, Color.White, "✓ Completed")
        "failed" -> Triple(C.red, Color.White, "✗ Failed")
        "pending" -> Triple(C.amber, C.surface, "⏳ Pending")
        "processing" -> Triple(C.blue, Color.White, "⚙ Processing")
        "undermaintenance" -> Triple(C.orange, Color.White, "🔧 Maintenance")
        "retrying" -> Triple(C.purple, Color.White, "🔄 Retrying")
        "cancelled" -> Triple(C.t2, Color.White, "⊘ Cancelled")
        else -> Triple(C.t2, Color.White, status.uppercase())
    }

    Box(
        modifier = modifier
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = displayText,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Get status color for indicators
 */
fun getStatusColor(status: String): Color = when (status.lowercase()) {
    "success" -> C.green
    "failed" -> C.red
    "pending" -> C.amber
    "processing" -> C.blue
    "undermaintenance" -> C.orange
    "retrying" -> C.purple
    "cancelled" -> C.t2
    else -> C.t2
}
