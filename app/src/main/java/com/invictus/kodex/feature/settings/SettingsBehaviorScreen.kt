package com.invictus.kodex.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.invictus.kodex.KodexApp
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.icons.XIcons

/** Behavior settings: what to do when a file changes on disk outside the app. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsBehaviorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoReloadExternal by viewModel.autoReloadExternalChanges.collectAsStateWithLifecycle()
    val gitStatusPollingEnabled by viewModel.gitStatusPollingEnabled.collectAsStateWithLifecycle()
    val restoreTabsOnOpen by viewModel.restoreTabsOnOpen.collectAsStateWithLifecycle()
    val searchDefaultStringsOnly by viewModel.searchDefaultStringsOnly.collectAsStateWithLifecycle()
    val fileTreeHScroll by viewModel.fileTreeHScrollLongNames.collectAsStateWithLifecycle()
    val searchExtraExcludes by viewModel.searchExtraExcludes.collectAsStateWithLifecycle()
    val searchMaxFileMb by viewModel.searchMaxFileMb.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var excludesText by remember(searchExtraExcludes) { mutableStateOf(searchExtraExcludes) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_section_behavior)) },
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
                RadioSettingRow(
                    title = stringResource(R.string.settings_external_changes_ask),
                    subtitle = stringResource(R.string.settings_external_changes_ask_desc),
                    selected = !autoReloadExternal,
                    onClick = { viewModel.setAutoReloadExternalChanges(false) },
                )
                SettingsDivider()
                RadioSettingRow(
                    title = stringResource(R.string.settings_external_changes_auto),
                    subtitle = stringResource(R.string.settings_external_changes_auto_desc),
                    selected = autoReloadExternal,
                    onClick = { viewModel.setAutoReloadExternalChanges(true) },
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_restore_tabs),
                    subtitle = stringResource(R.string.settings_restore_tabs_desc),
                    checked = restoreTabsOnOpen,
                    onCheckedChange = viewModel::setRestoreTabsOnOpen,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_tree_hscroll),
                    subtitle = stringResource(R.string.settings_tree_hscroll_desc),
                    checked = fileTreeHScroll,
                    onCheckedChange = viewModel::setFileTreeHScrollLongNames,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_git_status_polling),
                    subtitle = stringResource(R.string.settings_git_status_polling_desc),
                    checked = gitStatusPollingEnabled,
                    onCheckedChange = viewModel::setGitStatusPollingEnabled,
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_ai_enable),
                    subtitle = stringResource(R.string.settings_ai_enable_desc),
                    checked = com.invictus.kodex.ai.AiSwitch.enabled,
                    onCheckedChange = { com.invictus.kodex.ai.AiSwitch.set(appContext, it) },
                )
            }

            Spacer(Modifier.height(16.dp))

            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_search_default_strings),
                    subtitle = stringResource(R.string.settings_search_default_strings_desc),
                    checked = searchDefaultStringsOnly,
                    onCheckedChange = viewModel::setSearchDefaultStringsOnly,
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_search_excludes),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.settings_search_excludes_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = excludesText,
                        onValueChange = {
                            excludesText = it
                            viewModel.setSearchExtraExcludes(it)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        label = { Text(stringResource(R.string.settings_search_excludes_label)) },
                        placeholder = { Text(stringResource(R.string.settings_search_excludes_hint)) },
                        singleLine = true,
                    )
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_search_max_size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.settings_search_max_size_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(1, 2, 5, 10, 25).forEach { mb ->
                            FilterChip(
                                selected = searchMaxFileMb == mb,
                                onClick = { viewModel.setSearchMaxFileMb(mb) },
                                label = { Text("$mb MB") },
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = {
                        val app = appContext as KodexApp
                        scope.launch { app.container.searchHistoryStore.clearAll() }
                    }) { Text(stringResource(R.string.settings_search_clear_history)) }
                    TextButton(onClick = viewModel::clearRecentFiles) {
                        Text(stringResource(R.string.settings_search_clear_recent))
                    }
                }
            }
        }
    }
}
