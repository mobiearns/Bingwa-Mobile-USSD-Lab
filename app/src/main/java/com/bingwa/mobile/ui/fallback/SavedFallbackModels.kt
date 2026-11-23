package com.bingwa.mobile.ui.fallback

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bingwa.mobile.C

data class OfferUi(
    val id: String,
    val name: String,
    val priceKsh: Double,
    val provider: String,
    val validity: String,
    val subtitle: String
)

data class SavedFallbackUi(
    val primary: OfferUi,
    val backups: List<OfferUi>,
    val enabled: Boolean,
    val triggerOfferUnavailable: Boolean,
    val triggerAlreadyRecommended: Boolean
)

object FbColors {
    val Background get() = C.bg
    val Card get() = C.card
    val CardHigh get() = C.cardHi
    val TextPrimary get() = C.t1
    val TextSecondary get() = C.t2
    val Amber get() = C.amber
    val Cyan get() = C.cyan
    val Danger get() = C.red
}

fun formatKsh(value: Double): String {
    val amount = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
    return "KES $amount"
}

@Composable
fun BingwaFallbackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = FbColors.Background,
            surface = FbColors.Card,
            primary = FbColors.Amber,
            secondary = FbColors.Cyan,
            onBackground = FbColors.TextPrimary,
            onSurface = FbColors.TextPrimary
        ),
        content = content
    )
}
