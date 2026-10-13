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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.R
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.icons.XIcons

/** Advanced: performance switches and the diagnostics logs (crash logs, git logs). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDiagnosticsScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onOpenCrashLogs: () -> Unit,
    onOpenGitLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gitStatusPollingEnabled by viewModel.gitStatusPollingEnabled.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_section_diagnostics)) },
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
            SettingsSectionHeader(stringResource(R.string.settings_group_performance))
            SettingsSectionCard {
                SwitchSettingRow(
                    title = stringResource(R.string.settings_git_status_polling),
                    subtitle = stringResource(R.string.settings_git_status_polling_desc),
                    checked = gitStatusPollingEnabled,
                    onCheckedChange = viewModel::setGitStatusPollingEnabled,
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_group_logs))
            SettingsSectionCard {
                CategoryRow(
                    icon = XIcons.Bolt,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_crash_logs),
                    subtitle = stringResource(R.string.settings_category_crash_logs_desc),
                    onClick = onOpenCrashLogs,
                )
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Commit,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_git_logs),
                    subtitle = stringResource(R.string.settings_category_git_logs_desc),
                    isLast = true,
                    onClick = onOpenGitLogs,
                )
            }
        }
    }
}
