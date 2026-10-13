package com.invictus.kodex.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.feature.editor.SymbolBarCustomizeDialog
import com.invictus.kodex.ui.icons.XIcons

/** Editor settings: typing aids, symbol bar and preview. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsEditingScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autocompleteEnabled by viewModel.autocompleteEnabled.collectAsStateWithLifecycle()
    val pairCursorEnabled by viewModel.pairCursorEnabled.collectAsStateWithLifecycle()
    val symbolBar by viewModel.symbolBar.collectAsStateWithLifecycle()
    val symbolBarVisible by viewModel.symbolBarVisible.collectAsStateWithLifecycle()
    val autoPreviewEnabled by viewModel.autoPreviewEnabled.collectAsStateWithLifecycle()
    var showSymbolCustomize by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_section_editing)) },
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
            SettingsSectionHeader(stringResource(R.string.settings_group_typing))
            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_autocomplete),
                    subtitle = stringResource(R.string.settings_autocomplete_desc),
                    checked = autocompleteEnabled,
                    onCheckedChange = { viewModel.setAutocompleteEnabled(it) },
                )
                SettingsDivider()
                SwitchSettingRow(
                    title = stringResource(R.string.settings_pair_cursor),
                    subtitle = stringResource(R.string.settings_pair_cursor_desc),
                    checked = pairCursorEnabled,
                    onCheckedChange = { viewModel.setPairCursorEnabled(it) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_symbol_bar))
            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_symbol_bar_visible),
                    subtitle = stringResource(R.string.settings_symbol_bar_visible_desc),
                    checked = symbolBarVisible,
                    onCheckedChange = { viewModel.setSymbolBarVisible(it) },
                )
                SettingsDivider()
                ClickableSettingRow(
                    title = stringResource(R.string.settings_symbol_bar),
                    subtitle = symbolBar.joinToString(" "),
                    chevron = XIcons.ChevronRight,
                    onClick = { showSymbolCustomize = true },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_preview))
            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_auto_preview),
                    subtitle = stringResource(R.string.settings_auto_preview_desc),
                    checked = autoPreviewEnabled,
                    onCheckedChange = { viewModel.setAutoPreviewEnabled(it) },
                )
            }
        }
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
