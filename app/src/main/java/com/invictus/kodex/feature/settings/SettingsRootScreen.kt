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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/** Root of the Settings screen: category cards, styled after xmd's SettingsRootScreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenEditing: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenAbout: () -> Unit,
    /** M17: null in the normal flavor hides the row. */
    onOpenAi: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
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
                CategoryRow(
                    icon = XIcons.Palette,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_appearance),
                    subtitle = stringResource(R.string.settings_category_appearance_desc),
                    isFirst = true,
                    onClick = onOpenAppearance,
                )
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Edit,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_editing),
                    subtitle = stringResource(R.string.settings_category_editing_desc),
                    onClick = onOpenEditing,
                )
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Refresh,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_behavior),
                    subtitle = stringResource(R.string.settings_category_behavior_desc),
                    onClick = onOpenBehavior,
                )
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Bolt,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_diagnostics),
                    subtitle = stringResource(R.string.settings_category_diagnostics_desc),
                    onClick = onOpenDiagnostics,
                )
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Commit,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.settings_section_github),
                    subtitle = stringResource(R.string.settings_category_github_desc),
                    onClick = onOpenGitHub,
                )
                if (onOpenAi != null) {
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.AutoAwesome,
                        chevron = XIcons.ChevronRight,
                        title = "AI Assistant",
                        subtitle = "Provider, model and API key",
                        onClick = onOpenAi,
                    )
                }
                CategoryRowGap()
                CategoryRow(
                    icon = XIcons.Info,
                    chevron = XIcons.ChevronRight,
                    title = stringResource(R.string.about_title),
                    subtitle = stringResource(R.string.settings_category_about_desc),
                    isLast = true,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
