package com.invictus.kodex.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorThemes
import com.invictus.kodex.feature.editor.DEFAULT_TEXT_SIZE_SP
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.feature.editor.SymbolBarCustomizeDialog
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.feature.editor.MAX_PINCH_TEXT_SIZE_PX
import com.invictus.kodex.feature.editor.MIN_PINCH_TEXT_SIZE_PX
import com.invictus.kodex.ui.icons.XIcons
import com.invictus.kodex.ui.theme.ThemePickerState
import com.invictus.kodex.ui.theme.ThemeSettings

/** Appearance settings: app theme, editor theme, font size. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAppearanceScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val fontSizePx by viewModel.fontSizePx.collectAsStateWithLifecycle()

    var showThemePicker by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val builtInDefaultPx = with(density) { DEFAULT_TEXT_SIZE_SP.sp.toPx() }
    val effectiveFontSizePx = if (fontSizePx > 0f) fontSizePx else builtInDefaultPx

    val selectedThemeName = if (themeId == EditorThemes.SYSTEM_DEFAULT) {
        stringResource(R.string.editor_theme_system_default)
    } else {
        EditorThemes.familyOf(themeId)?.name ?: stringResource(R.string.editor_theme_system_default)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_section_appearance)) },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsSectionCard {
                ClickableSettingRow(
                    title = stringResource(R.string.settings_app_theme),
                    subtitle = ThemeSettings.theme.title,
                    chevron = XIcons.ChevronRight,
                    onClick = { ThemePickerState.visible = true },
                )
                SettingsDivider()
                ClickableSettingRow(
                    title = stringResource(R.string.settings_editor_theme),
                    subtitle = selectedThemeName,
                    chevron = XIcons.ChevronRight,
                    onClick = { showThemePicker = true },
                )
                SettingsDivider()
                FontSizeRow(
                    label = stringResource(R.string.settings_font_size),
                    rawValuePx = effectiveFontSizePx,
                    onValueChange = { viewModel.setFontSizePx(it) },
                )
            }
        }
    }

    if (showThemePicker) {
        EditorThemePickerSheet(
            selected = themeId,
            onSelect = { viewModel.setTheme(it) },
            onDismiss = { showThemePicker = false },
        )
    }
}

@Composable
private fun FontSizeRow(label: String, rawValuePx: Float, onValueChange: (Float) -> Unit) {
    val density = LocalDensity.current
    val valuePx = rawValuePx.coerceIn(MIN_PINCH_TEXT_SIZE_PX, MAX_PINCH_TEXT_SIZE_PX)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = valuePx,
                onValueChange = onValueChange,
                valueRange = MIN_PINCH_TEXT_SIZE_PX..MAX_PINCH_TEXT_SIZE_PX,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.size(width = 44.dp, height = 1.dp))
            Text(
                text = "${valuePx.toInt()}px",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_font_size_preview),
            fontFamily = FontFamily.Monospace,
            fontSize = with(density) { valuePx.toSp() },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
