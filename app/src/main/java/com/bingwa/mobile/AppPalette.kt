package com.bingwa.mobile

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Bybit-inspired neutral dark palette for Bingwa.
 *
 * Neutral charcoal surfaces carry the interface. Yellow is the sole brand
 * accent; semantic colours are reserved for meaningful state/status feedback.
 */
internal object C {
    var bg by mutableStateOf(Color(0xFF0B0B0D))
    var surface by mutableStateOf(Color(0xFF141417))
    var card by mutableStateOf(Color(0xFF1B1B1F))
    var cardHi by mutableStateOf(Color(0xFF242429))
    var border by mutableStateOf(Color(0xFF303036))
    var borderHi by mutableStateOf(Color(0xFF414149))

    // Brand accent: use yellow for selection, primary actions and key emphasis.
    var cyan by mutableStateOf(Color(0xFFF7A600))
    var cyanDim by mutableStateOf(Color(0xFF49330A))
    var cyanGlow by mutableStateOf(Color(0x29F7A600))
    var orange by mutableStateOf(Color(0xFFF0B90B))
    var orangeDim by mutableStateOf(Color(0xFF493B0B))
    var purple by mutableStateOf(Color(0xFFA78BFA))
    var purpleDim by mutableStateOf(Color(0xFF30264B))

    // Semantic state colours: success, failure, warning, and information.
    var green by mutableStateOf(Color(0xFF16C784))
    var greenDim by mutableStateOf(Color(0xFF103B2D))
    var greenGlow by mutableStateOf(Color(0x2916C784))
    var red by mutableStateOf(Color(0xFFEA3943))
    var redDim by mutableStateOf(Color(0xFF451F23))
    var amber by mutableStateOf(Color(0xFFF0B90B))
    var amberDim by mutableStateOf(Color(0xFF493B0B))
    var blue by mutableStateOf(Color(0xFF3B82F6))
    var blueDim by mutableStateOf(Color(0xFF1C2E4D))

    var t1 by mutableStateOf(Color(0xFFF5F5F6))
    var t2 by mutableStateOf(Color(0xFFB8B8BF))
    var t3 by mutableStateOf(Color(0xFF85858E))
    // Opaque neutral fills retained for legacy components.
    var w12 by mutableStateOf(Color(0xFF29292E))
    var w08 by mutableStateOf(Color(0xFF202025))
    var w04 by mutableStateOf(Color(0xFF18181C))

    // Compatibility aliases retained for existing feature screens.
    val cyanLight: Color get() = Color(0xFFFFC247)
    val greenSoft: Color get() = greenDim
    val cardMuted: Color get() = cardHi
    val t1Soft: Color get() = t2
    val borderStrong: Color get() = borderHi
    val redSoft: Color get() = redDim
}

internal enum class ThemeMode { LIGHT, DARK }

internal enum class ThemeAccent(val label: String) {
    BYBIT("Bingwa Dark")
}

internal fun themeAccentFromName(value: String?): ThemeAccent = ThemeAccent.BYBIT
internal fun themeAccentOptions(): List<ThemeAccent> = ThemeAccent.values().toList()
internal fun themeAccentLabel(accent: ThemeAccent): String = accent.label

internal fun buildAppColorScheme(accent: ThemeAccent, dark: Boolean): ColorScheme =
    darkColorScheme(
        primary = Color(0xFFF7A600),
        onPrimary = Color(0xFF17130A),
        primaryContainer = Color(0xFF49330A),
        onPrimaryContainer = Color(0xFFFFE5B0),
        secondary = Color(0xFFB8B8BF),
        onSecondary = Color(0xFF171719),
        secondaryContainer = Color(0xFF303036),
        onSecondaryContainer = Color(0xFFE5E5E8),
        tertiary = Color(0xFF85858E),
        onTertiary = Color(0xFF171719),
        tertiaryContainer = Color(0xFF303036),
        onTertiaryContainer = Color(0xFFE5E5E8),
        background = Color(0xFF0B0B0D),
        onBackground = Color(0xFFF5F5F6),
        surface = Color(0xFF141417),
        onSurface = Color(0xFFF5F5F6),
        surfaceVariant = Color(0xFF242429),
        onSurfaceVariant = Color(0xFFB8B8BF),
        outline = Color(0xFF66666F),
        outlineVariant = Color(0xFF303036),
        error = Color(0xFFEA3943),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFF451F23),
        onErrorContainer = Color(0xFFFFDADD),
        surfaceTint = Color.Transparent
    )

internal object AppTheme {
    var mode by mutableStateOf(ThemeMode.DARK)
    var useDynamicColors by mutableStateOf(false)
    var accent by mutableStateOf(ThemeAccent.BYBIT)

    fun load(context: Context) {
        // Bingwa uses a consistent dark neutral palette across Android devices.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        mode = ThemeMode.DARK
        useDynamicColors = false
        accent = ThemeAccent.BYBIT
    }
}

/** Synchronises legacy C tokens with the active Material 3 dark scheme. */
internal fun applyVolcanicPaletteFromScheme(s: ColorScheme, dark: Boolean) {
    C.bg = Color(0xFF0B0B0D)
    C.surface = Color(0xFF141417)
    C.card = Color(0xFF1B1B1F)
    C.cardHi = Color(0xFF242429)
    C.border = Color(0xFF303036)
    C.borderHi = Color(0xFF414149)

    C.cyan = Color(0xFFF7A600)
    C.cyanDim = Color(0xFF49330A)
    C.cyanGlow = Color(0x29F7A600)
    C.orange = Color(0xFFF0B90B)
    C.orangeDim = Color(0xFF493B0B)
    C.purple = Color(0xFFA78BFA)
    C.purpleDim = Color(0xFF30264B)

    C.green = Color(0xFF16C784)
    C.greenDim = Color(0xFF103B2D)
    C.greenGlow = Color(0x2916C784)
    C.red = Color(0xFFEA3943)
    C.redDim = Color(0xFF451F23)
    C.amber = Color(0xFFF0B90B)
    C.amberDim = Color(0xFF493B0B)
    C.blue = Color(0xFF3B82F6)
    C.blueDim = Color(0xFF1C2E4D)

    C.t1 = Color(0xFFF5F5F6)
    C.t2 = Color(0xFFB8B8BF)
    C.t3 = Color(0xFF85858E)

    // Solid neutral legacy fills: no pale green or translucent white wash.
    C.w12 = Color(0xFF29292E)
    C.w08 = Color(0xFF202025)
    C.w04 = Color(0xFF18181C)
}

internal fun surfaceGradient(): Brush =
    Brush.linearGradient(listOf(C.cardHi, C.card, C.surface))

internal fun accentSurfaceGradient(accent: Color): Brush =
    Brush.linearGradient(listOf(C.cardHi, C.cardHi, C.card))
