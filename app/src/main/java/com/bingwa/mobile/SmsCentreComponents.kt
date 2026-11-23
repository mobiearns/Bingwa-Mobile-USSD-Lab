package com.bingwa.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared visual building blocks for the SMS Centre workspaces.
 * Keep sizing, corner radii, typography and interaction states consistent here.
 */
@Composable
internal fun SmsSectionHeading(
    title: String,
    subtitle: String? = null,
    accent: Color = C.amber,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiDimens.SpacingSm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (subtitle == null) title.uppercase() else title,
                color = if (subtitle == null) C.t2 else C.t1,
                fontSize = if (subtitle == null) 11.sp else 18.sp,
                letterSpacing = if (subtitle == null) 0.8.sp else 0.sp,
                fontWeight = FontWeight.Bold,
                maxLines = if (subtitle == null) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = C.t2,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
internal fun SmsHeroCard(
    modifier: Modifier = Modifier,
    accent: Color = C.amber,
    borderColor: Color = C.borderStrong,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiDimens.CardRadiusCompact))
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = 0.11f),
                        C.cardHi,
                        C.card,
                    )
                )
            )
           .border(1.dp, borderColor, RoundedCornerShape(UiDimens.CardRadiusCompact))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun SmsTintedStatTile(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(UiDimens.FieldRadius))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(UiDimens.FieldRadius),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.24f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                value,
                color = accent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                label,
                color = C.t2,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun SmsStepHeader(
    step: String,
    title: String,
    accent: Color = C.amber,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UiDimens.SpacingSm),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = accent.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        ) {
            Text(
                step,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = C.t1,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
internal fun smsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = C.surface,
    unfocusedContainerColor = C.surface,
    disabledContainerColor = C.surface.copy(alpha = 0.55f),
    focusedBorderColor = C.amber,
    unfocusedBorderColor = C.border,
    disabledBorderColor = C.border.copy(alpha = 0.55f),
    focusedLabelColor = C.amber,
    unfocusedLabelColor = C.t2,
    focusedTextColor = C.t1,
    unfocusedTextColor = C.t1,
    disabledTextColor = C.t3,
    cursorColor = C.amber,
    focusedPlaceholderColor = C.t3,
    unfocusedPlaceholderColor = C.t3,
    focusedSupportingTextColor = C.t2,
    unfocusedSupportingTextColor = C.t3,
)

@Composable
internal fun SmsCard(
    modifier: Modifier = Modifier,
    borderColor: Color = C.border,
    containerColor: Color = C.card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
internal fun SmsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    loading: Boolean = false,
    minHeight: androidx.compose.ui.unit.Dp = UiDimens.ControlMinHeight,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(UiDimens.ButtonRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = C.amber,
            contentColor = C.bg,
            disabledContainerColor = C.cardHi,
            disabledContentColor = C.t3,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = C.bg)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SmsSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    minHeight: androidx.compose.ui.unit.Dp = UiDimens.ControlMinHeight,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (enabled) C.amber.copy(alpha = 0.55f) else C.border),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = C.amber,
            disabledContentColor = C.t3,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SmsTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    minHeight: androidx.compose.ui.unit.Dp = UiDimens.ControlMinHeight,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = minHeight),
        shape = RoundedCornerShape(UiDimens.ButtonRadius),
        border = BorderStroke(1.dp, C.border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = C.cardHi,
            contentColor = C.t1,
            disabledContentColor = C.t3,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SmsCompactToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C.t1),
        contentPadding = PaddingValues(2.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SmsEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = C.t1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                description,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = C.t3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp,
            )
    }
}

@Composable
internal fun SmsBalanceMetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
