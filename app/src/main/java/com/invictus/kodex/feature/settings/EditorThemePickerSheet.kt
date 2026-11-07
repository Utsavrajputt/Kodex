package com.invictus.kodex.feature.settings

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorTheme
import com.invictus.kodex.core.editor.EditorThemeFamily
import com.invictus.kodex.core.editor.EditorThemePreviewColors
import com.invictus.kodex.core.editor.EditorThemes
import com.invictus.kodex.ui.theme.ExpressiveMotion
import com.invictus.kodex.ui.theme.ThemeMode
import com.invictus.kodex.ui.theme.ThemeSettings

/**
 * Editor colour-theme picker, styled like [com.invictus.kodex.ui.theme.ThemePickerSheet]: a
 * bottom sheet with a horizontal strip of preview cards -- except each card is a mock code
 * snippet drawn in that theme's own syntax colours, since a phone-UI mock-up doesn't tell you
 * anything about how your code will actually look.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorThemePickerSheet(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                stringResource(R.string.editor_theme_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            val dark = when (ThemeSettings.mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                FilterChip(
                    selected = !dark,
                    onClick = { ThemeSettings.updateMode(ThemeMode.LIGHT) },
                    label = { Text("Light") },
                )
                FilterChip(
                    selected = dark,
                    onClick = { ThemeSettings.updateMode(ThemeMode.DARK) },
                    label = { Text("Dark") },
                )
            }
            Spacer(Modifier.height(20.dp))
            EditorThemeStrip(
                dark = dark,
                selected = selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun EditorThemeStrip(
    dark: Boolean,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val entries = remember { listOf<EditorThemeFamily?>(null) + EditorThemes.FAMILIES }
    val selectedFamily = EditorThemes.familyOf(selected)?.id

    LaunchedEffect(Unit) {
        val index = entries.indexOfFirst { it?.id == selectedFamily || (it == null && selected == EditorThemes.SYSTEM_DEFAULT) }
        if (index >= 0) {
            listState.animateScrollToItem(maxOf(0, index - 1))
        }
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it?.id ?: EditorThemes.SYSTEM_DEFAULT }) { family ->
            if (family == null) {
                SystemDefaultPreviewCard(
                    selected = selected == EditorThemes.SYSTEM_DEFAULT,
                    onSelect = { onSelect(EditorThemes.SYSTEM_DEFAULT) },
                )
            } else {
                EditorThemePreviewCard(
                    theme = family.variant(dark),
                    name = family.name,
                    selected = selectedFamily == family.id,
                    onSelect = { onSelect(family.id) },
                )
            }
        }
    }
}

/** "System default" card: follows the app's light/dark, so it mirrors the surface colours instead of a fixed palette. */
@Composable
private fun SystemDefaultPreviewCard(selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    CodePreviewCard(
        name = stringResource(R.string.editor_theme_system_default),
        background = scheme.surface,
        foreground = scheme.onSurface,
        comment = scheme.onSurfaceVariant,
        string = scheme.tertiary,
        keyword = scheme.primary,
        function = scheme.secondary,
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun EditorThemePreviewCard(
    theme: EditorTheme,
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val p: EditorThemePreviewColors = theme.preview
    CodePreviewCard(
        name = name,
        background = Color(p.background),
        foreground = Color(p.foreground),
        comment = Color(p.comment),
        string = Color(p.string),
        keyword = Color(p.keyword),
        function = Color(p.function),
        selected = selected,
        onSelect = onSelect,
    )
}

/** Mini code mock-up card: a few "lines" of coloured pills standing in for keyword/function/string/comment tokens. */
@Composable
private fun CodePreviewCard(
    name: String,
    background: Color,
    foreground: Color,
    comment: Color,
    string: Color,
    keyword: Color,
    function: Color,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val selectionColor = MaterialTheme.colorScheme.primary
    val borderWidth = if (selected) 3.dp else 1.dp
    val borderColor = if (selected) selectionColor else foreground.copy(alpha = 0.15f)

    Column(
        modifier = Modifier
            .width(100.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .pointerInput(name) {
                // Select on a real tap (release without moving), not on touch-down, so scrolling the strip never picks a theme.
                detectTapGestures(
                    onPress = {
                        val down = scope.launch { scale.animateTo(0.94f, animationSpec = ExpressiveMotion.FastEffectsTween) }
                        tryAwaitRelease()
                        down.cancel()
                        scope.launch { scale.animateTo(1f, animationSpec = ExpressiveMotion.DefaultEffectsSpring) }
                    },
                    onTap = { onSelect() },
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
                .background(background)
                .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(12.dp))
                .padding(if (selected) 3.dp else 1.dp)
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                TokenLine(0.55f, keyword)
                TokenLine(0.75f, foreground.copy(alpha = 0.85f))
                TokenLine(0.4f, function)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TokenPill(0.3f, string)
                    TokenPill(0.2f, keyword)
                }
                TokenLine(0.65f, foreground.copy(alpha = 0.85f))
                TokenLine(0.85f, comment)
                TokenLine(0.35f, foreground.copy(alpha = 0.85f))
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = name,
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

/** A single coloured bar standing in for a line of code, [widthFraction] of the card's inner width. */
@Composable
private fun TokenLine(widthFraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

/** A short coloured pill, used for inline tokens (e.g. a string literal next to a keyword). */
@Composable
private fun TokenPill(widthFraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}
