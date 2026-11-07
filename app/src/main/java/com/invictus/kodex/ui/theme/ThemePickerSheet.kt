package com.invictus.kodex.ui.theme

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Theme-changing UI: mode chips, AMOLED switch, and a horizontal strip of theme previews. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePickerSheet(
    onDismiss: () -> Unit,
    transitionController: ThemeTransitionController,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (ThemeSettings.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Appearance",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                ThemeMode.values().forEach { m ->
                    FilterChip(
                        selected = ThemeSettings.mode == m,
                        onClick = { ThemeSettings.updateMode(m) },
                        label = {
                            Text(
                                when (m) {
                                    ThemeMode.SYSTEM -> "System"
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                },
                            )
                        },
                    )
                }
            }

            if (dark) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Pure black (AMOLED)", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = ThemeSettings.amoled,
                        onCheckedChange = { ThemeSettings.updateAmoled(it) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            ThemeStrip(
                dark = dark,
                onSelect = { theme, tapPosition ->
                    transitionController.begin(view, tapPosition) {
                        ThemeSettings.updateTheme(theme)
                    }
                },
            )
        }
    }
}

/** Horizontal strip of mini phone-mockup theme previews, xmd-style. */
@Composable
private fun ThemeStrip(
    dark: Boolean,
    onSelect: (AppTheme, Offset) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val index = AppTheme.values().indexOf(ThemeSettings.theme)
        if (index >= 0) {
            listState.animateScrollToItem(maxOf(0, index - 1))
        }
    }

    Text(
        text = "Theme",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, bottom = 10.dp),
    )

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(AppTheme.values().toList(), key = { it.name }) { theme ->
            ThemePreviewCard(
                theme = theme,
                dark = dark,
                selected = ThemeSettings.theme == theme,
                onSelect = { position -> onSelect(theme, position) },
            )
        }
    }
}

@Composable
private fun ThemePreviewCard(
    theme: AppTheme,
    dark: Boolean,
    selected: Boolean,
    onSelect: (Offset) -> Unit,
) {
    val scheme = remember(theme, dark) {
        if (dark) theme.getDarkColorScheme() else theme.getLightColorScheme()
    }
    // Expressive press-to-scale bounce.
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val selectionColor = MaterialTheme.colorScheme.primary
    val borderWidth = if (selected) 3.dp else 1.dp
    val borderColor = if (selected) selectionColor else scheme.outlineVariant

    Column(
        modifier = Modifier
            .width(100.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .pointerInput(theme) {
                // Selecting happens on a real TAP (finger lifted without moving), never on touch-down: otherwise
                // putting a finger on a card to scroll the strip would pick that theme. The press scale is cosmetic only.
                detectTapGestures(
                    onPress = {
                        val down = scope.launch { scale.animateTo(0.94f, animationSpec = ExpressiveMotion.FastEffectsTween) }
                        tryAwaitRelease()
                        down.cancel()
                        scope.launch { scale.animateTo(1f, animationSpec = ExpressiveMotion.DefaultEffectsSpring) }
                    },
                    onTap = { offset -> onSelect(offset) },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(width = 90.dp, height = 140.dp)
                .shadow(
                    elevation = if (selected) 8.dp else 2.dp,
                    shape = RoundedCornerShape(12.dp),
                    ambientColor = if (selected) selectionColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f),
                    spotColor = if (selected) selectionColor.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f),
                )
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface)
                .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(12.dp)),
        ) {
            // Fake phone-screen mockup: status bar, toolbar (primary pill + tertiary dot), content strip, dot.
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .padding(if (selected) 3.dp else 1.dp)
                    .clip(RoundedCornerShape(if (selected) 9.dp else 11.dp))
                    .background(scheme.background)
                    .padding(8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.surfaceVariant),
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    color = scheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 24.dp, height = 12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(scheme.primary),
                        )
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape).background(scheme.tertiary),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(scheme.surfaceVariant),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(scheme.secondary))
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = theme.title,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
