package com.invictus.kodex.github.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Colours for the GitHub screen. In a dark theme it is the navy gradient look; in a light theme the same
 * layout is built from the app's own colour scheme so nothing glows on a white screen.
 */
internal class GhPalette(
    val dark: Boolean,
    val screen: Brush,
    val card: Brush,
    val cardBorder: Color,
    val apkCard: Brush,
    val apkBorder: Color,
    val runCard: Color,
    val onCard: Color,
    val onCardMuted: Color,
    val accent: Color,
    val pickerBorder: Color,
    val installBrush: Brush,
    val onInstall: Color,
    val neutralAction: Color,
    val onNeutralAction: Color,
    val dangerAction: Color,
    val onDangerAction: Color,
    val success: Color,
    val onSuccess: Color,
    val danger: Color,
    val onDanger: Color,
)

@Composable
internal fun ghPalette(): GhPalette {
    val cs = MaterialTheme.colorScheme
    val dark = cs.background.luminance() < 0.5f
    return if (dark) {
        GhPalette(
            dark = true,
            screen = Brush.verticalGradient(listOf(Color(0xFF060B16), Color(0xFF0A1226))),
            card = Brush.linearGradient(listOf(Color(0xFF111B33), Color(0xFF0D162B))),
            cardBorder = Color.White.copy(alpha = 0.07f),
            apkCard = Brush.linearGradient(listOf(Color(0xFF1C3C78), Color(0xFF142A55))),
            apkBorder = Color(0xFF3563B8).copy(alpha = 0.55f),
            runCard = Color(0xFF0D1426),
            onCard = Color(0xFFF2F5FC),
            onCardMuted = Color(0xFF9AA6C2),
            accent = Color(0xFF4F8BFF),
            pickerBorder = Color.White.copy(alpha = 0.14f),
            installBrush = Brush.horizontalGradient(listOf(Color(0xFF5B93FF), Color(0xFF4C86F7))),
            onInstall = Color(0xFF07122B),
            neutralAction = Color(0xFF2B303C),
            onNeutralAction = Color(0xFFE6EAF5),
            dangerAction = Color(0xFFB3202A),
            onDangerAction = Color(0xFFFFE9E9),
            success = Color(0xFF2FBF5B),
            onSuccess = Color(0xFF06210F),
            danger = Color(0xFFFF6B6B),
            onDanger = Color(0xFF2B0505),
        )
    } else {
        GhPalette(
            dark = false,
            screen = Brush.verticalGradient(listOf(cs.background, cs.background)),
            card = Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainerHigh)),
            cardBorder = cs.outlineVariant.copy(alpha = 0.5f),
            apkCard = Brush.linearGradient(listOf(cs.primaryContainer, cs.primaryContainer)),
            apkBorder = cs.primary.copy(alpha = 0.25f),
            runCard = cs.surfaceContainer,
            onCard = cs.onSurface,
            onCardMuted = cs.onSurfaceVariant,
            accent = cs.primary,
            pickerBorder = cs.outline.copy(alpha = 0.5f),
            installBrush = Brush.horizontalGradient(listOf(cs.primary, cs.primary)),
            onInstall = cs.onPrimary,
            neutralAction = cs.secondaryContainer,
            onNeutralAction = cs.onSecondaryContainer,
            dangerAction = cs.errorContainer,
            onDangerAction = cs.onErrorContainer,
            success = Color(0xFF2E7D32),
            onSuccess = Color.White,
            danger = cs.error,
            onDanger = cs.onError,
        )
    }
}

private val CardShape = RoundedCornerShape(24.dp)

/** Rounded gradient card with a hairline border, the base of every card on the GitHub screen. */
@Composable
internal fun GhCard(
    brush: Brush,
    border: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(brush)
            .border(1.dp, border, CardShape),
        content = content,
    )
}

/** Pill button with the blue gradient (Install / Download). */
@Composable
internal fun GhPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val p = ghPalette()
    Row(
        modifier
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(p.installBrush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Round icon-only action (open folder, delete) next to the Install button. */
@Composable
internal fun GhCircleButton(
    icon: ImageVector,
    description: String,
    container: Color,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = tint,
            disabledContainerColor = container.copy(alpha = 0.4f),
            disabledContentColor = tint.copy(alpha = 0.5f),
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(24.dp))
    }
}
