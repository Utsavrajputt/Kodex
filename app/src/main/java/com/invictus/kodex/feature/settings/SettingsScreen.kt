package com.invictus.kodex.feature.settings

import com.invictus.kodex.ui.components.expressiveClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.editor.EditorThemes
import com.invictus.kodex.feature.editor.DEFAULT_TEXT_SIZE_SP
import com.invictus.kodex.feature.editor.MAX_PINCH_TEXT_SIZE_PX
import com.invictus.kodex.feature.editor.MIN_PINCH_TEXT_SIZE_PX
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.feature.editor.SymbolBarCustomizeDialog
import com.invictus.kodex.ui.icons.XIcons
import com.invictus.kodex.ui.theme.ThemePickerState
import com.invictus.kodex.ui.theme.ThemeSettings

/**
 * Plan 3.2 "settings screen with search". Reuses the same [EditorViewModel] the editor screen
 * already has (activity-scoped), so a change here is visible in the editor the moment the user
 * navigates back -- no separate settings view-model or store round trip needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val themeId by viewModel.themeId.collectAsStateWithLifecycle()
    val fontSizePx by viewModel.fontSizePx.collectAsStateWithLifecycle()
    val symbolBar by viewModel.symbolBar.collectAsStateWithLifecycle()
    val symbolBarVisible by viewModel.symbolBarVisible.collectAsStateWithLifecycle()
    val autocompleteEnabled by viewModel.autocompleteEnabled.collectAsStateWithLifecycle()
    val pairCursorEnabled by viewModel.pairCursorEnabled.collectAsStateWithLifecycle()
    val magnifierEnabled by viewModel.magnifierEnabled.collectAsStateWithLifecycle()
    val autoReloadExternal by viewModel.autoReloadExternalChanges.collectAsStateWithLifecycle()

    var showThemePicker by remember { mutableStateOf(false) }
    var showSymbolCustomize by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val density = LocalDensity.current
    // Only informational (where the slider starts before the user has ever picked a size);
    // the stored/slider value itself stays in the same raw px domain CodeEditorView already
    // uses everywhere else (pinch-zoom included) -- see EditorSettingsStore.fontSizePx.
    val builtInDefaultPx = with(density) { DEFAULT_TEXT_SIZE_SP.sp.toPx() }
    val effectiveFontSizePx = if (fontSizePx > 0f) fontSizePx else builtInDefaultPx

    val selectedThemeName = if (themeId == EditorThemes.SYSTEM_DEFAULT) {
        stringResource(R.string.editor_theme_system_default)
    } else {
        EditorThemes.familyOf(themeId)?.name ?: stringResource(R.string.editor_theme_system_default)
    }
    val symbolBarPreview = symbolBar.joinToString(" ")

    val appThemeLabel = stringResource(R.string.settings_app_theme)
    val themeLabel = stringResource(R.string.settings_editor_theme)
    val fontSizeLabel = stringResource(R.string.settings_font_size)
    val symbolBarLabel = stringResource(R.string.settings_symbol_bar)
    val symbolBarVisibleLabel = stringResource(R.string.settings_symbol_bar_visible)
    val autocompleteLabel = stringResource(R.string.settings_autocomplete)
    val pairCursorLabel = stringResource(R.string.settings_pair_cursor)
    val magnifierLabel = stringResource(R.string.settings_magnifier)
    val externalChangesLabel = stringResource(R.string.settings_external_changes)

    val sections = listOf(
        stringResource(R.string.settings_section_appearance) to listOf(
            SettingRow(appThemeLabel) {
                SettingsClickRow(
                    title = appThemeLabel,
                    subtitle = ThemeSettings.theme.title,
                    onClick = { ThemePickerState.visible = true },
                )
            },
            SettingRow(themeLabel) {
                SettingsClickRow(
                    title = themeLabel,
                    subtitle = selectedThemeName,
                    onClick = { showThemePicker = true },
                )
            },
            SettingRow(fontSizeLabel) {
                FontSizeRow(
                    label = fontSizeLabel,
                    valuePx = effectiveFontSizePx,
                    onValueChange = { viewModel.setFontSizePx(it) },
                )
            },
            SettingRow(symbolBarVisibleLabel) {
                SettingsSwitchRow(
                    title = symbolBarVisibleLabel,
                    subtitle = stringResource(R.string.settings_symbol_bar_visible_desc),
                    checked = symbolBarVisible,
                    onCheckedChange = { viewModel.setSymbolBarVisible(it) },
                )
            },
            SettingRow(symbolBarLabel) {
                SettingsClickRow(
                    title = symbolBarLabel,
                    subtitle = symbolBarPreview,
                    onClick = { showSymbolCustomize = true },
                )
            },
        ),
        stringResource(R.string.settings_section_editing) to listOf(
            SettingRow(autocompleteLabel) {
                SettingsSwitchRow(
                    title = autocompleteLabel,
                    subtitle = stringResource(R.string.settings_autocomplete_desc),
                    checked = autocompleteEnabled,
                    onCheckedChange = { viewModel.setAutocompleteEnabled(it) },
                )
            },
            SettingRow(pairCursorLabel) {
                SettingsSwitchRow(
                    title = pairCursorLabel,
                    subtitle = stringResource(R.string.settings_pair_cursor_desc),
                    checked = pairCursorEnabled,
                    onCheckedChange = { viewModel.setPairCursorEnabled(it) },
                )
            },
            SettingRow(magnifierLabel) {
                SettingsSwitchRow(
                    title = magnifierLabel,
                    subtitle = stringResource(R.string.settings_magnifier_desc),
                    checked = magnifierEnabled,
                    onCheckedChange = { viewModel.setMagnifierEnabled(it) },
                )
            },
        ),
        stringResource(R.string.settings_section_behavior) to listOf(
            SettingRow(externalChangesLabel) {
                ExternalChangesRow(
                    autoReload = autoReloadExternal,
                    onSelect = { viewModel.setAutoReloadExternalChanges(it) },
                )
            },
        ),
    )

    val filteredSections = sections.mapNotNull { (header, rows) ->
        val matching = if (query.isBlank()) rows else rows.filter { it.label.contains(query, ignoreCase = true) }
        if (matching.isEmpty()) null else header to matching
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
                title = {
                    if (searchExpanded) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.settings_title))
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (searchExpanded) query = ""
                            searchExpanded = !searchExpanded
                        },
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) XIcons.Close else XIcons.Search,
                            contentDescription = stringResource(
                                if (searchExpanded) R.string.action_close_dialog else R.string.find_action,
                            ),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        if (filteredSections.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                filteredSections.forEach { (header, rows) ->
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                    rows.forEach { it.content() }
                }
            }
        }
    }

    if (showThemePicker) {
        ThemePickerDialog(
            selected = themeId,
            onSelect = { viewModel.setTheme(it) },
            onDismiss = { showThemePicker = false },
        )
    }
    if (showSymbolCustomize) {
        SymbolBarCustomizeDialog(
            current = symbolBar,
            onSave = { viewModel.setSymbolBar(it); showSymbolCustomize = false },
            onResetDefault = { viewModel.setSymbolBar(EditorSettingsStore.DEFAULT_SYMBOLS); showSymbolCustomize = false },
            onDismiss = { showSymbolCustomize = false },
        )
    }
}

/** One filterable entry in the settings list: [label] is matched against the search query. */
private class SettingRow(val label: String, val content: @Composable () -> Unit)

@Composable
private fun SettingsClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .expressiveClickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FontSizeRow(label: String, valuePx: Float, onValueChange: (Float) -> Unit) {
    val density = LocalDensity.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = valuePx.coerceIn(MIN_PINCH_TEXT_SIZE_PX, MAX_PINCH_TEXT_SIZE_PX),
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

@Composable
private fun ExternalChangesRow(autoReload: Boolean, onSelect: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text = stringResource(R.string.settings_external_changes), style = MaterialTheme.typography.bodyLarge)
        ExternalChangeOption(
            title = stringResource(R.string.settings_external_changes_ask),
            subtitle = stringResource(R.string.settings_external_changes_ask_desc),
            selected = !autoReload,
            onClick = { onSelect(false) },
        )
        ExternalChangeOption(
            title = stringResource(R.string.settings_external_changes_auto),
            subtitle = stringResource(R.string.settings_external_changes_auto_desc),
            selected = autoReload,
            onClick = { onSelect(true) },
        )
    }
}

@Composable
private fun ExternalChangeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().expressiveClickable(onClick = onClick).padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemePickerDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_theme_picker_title)) },
        text = {
            Column {
                ThemeRow(
                    name = stringResource(R.string.editor_theme_system_default),
                    isSelected = selected == EditorThemes.SYSTEM_DEFAULT,
                    onClick = { onSelect(EditorThemes.SYSTEM_DEFAULT) },
                )
                EditorThemes.FAMILIES.forEach { family ->
                    ThemeRow(
                        name = family.name,
                        isSelected = EditorThemes.familyOf(selected)?.id == family.id,
                        onClick = { onSelect(family.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close_dialog)) }
        },
    )
}

@Composable
private fun ThemeRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().expressiveClickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Box(modifier = Modifier.size(24.dp)) {
            if (isSelected) Icon(XIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(text = name, modifier = Modifier.padding(start = 12.dp))
    }
}
