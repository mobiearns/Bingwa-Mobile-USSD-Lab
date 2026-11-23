package com.bingwa.mobile

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared layout scale for the Bingwa Mobile Compose UI.
 *
 * Keep screen gutters, card rhythm and touch targets predictable across
 * feature screens. Feature-specific compact layouts may intentionally opt
 * into the compact values below.
 */
internal object UiDimens {
    // Screen frame
    val ScreenHorizontal = 16.dp
    // Backwards-compatible alias for screens not yet migrated to the shared name.
    val ScreenPaddingHorizontal = ScreenHorizontal
    val ScreenVertical = 12.dp
    val ScreenHorizontalCompact = 12.dp
    val ScreenContentMaxWidth = 720.dp
    val AppContentGutter = 16.dp
    val CompactContentGutter = 12.dp
    val PageHeaderMinHeight = 56.dp
    val AppContentMaxWidth = ScreenContentMaxWidth
    val SettingsContentMaxWidth = 430.dp
    val ScreenPadding = PaddingValues(horizontal = ScreenHorizontal, vertical = ScreenVertical)

    // Surfaces and controls
    val CardPadding = 14.dp
    val CardPaddingCompact = 11.dp
    val CardRadius = 18.dp
    val CardRadiusLarge = 20.dp
    val CardRadiusCompact = 14.dp
    val CardSpacing = 10.dp
    val SectionSpacing = 16.dp
    val SectionSpacingCompact = 12.dp
    val FormFieldSpacing = 12.dp
    val ListItemMinHeight = 60.dp
    val IconButtonSize = 48.dp
    val ControlMinHeight = 48.dp
    val IconTouchTarget = 48.dp
    val NavigationItemMinWidth = 48.dp
    val NavigationItemMinHeight = 48.dp
    val NavigationBarRadius = 26.dp
    val NavigationBarHorizontalInset = 10.dp
    val NavigationBarVerticalInset = 6.dp
    val ButtonRadius = 14.dp
    val FieldRadius = 14.dp
    val DialogRadius = 20.dp
    val DialogPadding = 20.dp

    // Consistent 4dp-based spacing scale
    val SpacingXs = 4.dp
    val SpacingSm = 8.dp
    val SpacingMd = 12.dp
    val SpacingLg = 16.dp
    val SpacingXl = 20.dp
    val Spacing2xl = 24.dp
    val Spacing3xl = 32.dp

    // Shared type scale for page-level hierarchy and supporting copy.
    val PageTitleSize = 20.sp
    val SectionTitleSize = 16.sp
    val BodyTextSize = 14.sp
    val SupportingTextSize = 12.sp
    val CaptionTextSize = 10.sp
}
