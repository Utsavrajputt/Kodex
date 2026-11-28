package com.invictus.kodex.feature.settings

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.feature.diagnostics.CrashLogsScreen
import com.invictus.kodex.feature.diagnostics.GitLogsScreen
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.adaptive.AdaptiveTwoPaneLayout
import com.invictus.kodex.ui.adaptive.isTabletLayout
import com.invictus.kodex.ui.icons.XIcons

enum class SettingsCategory {
    Appearance,
    Editing,
    Behavior,
    Keybindings,
    GitHub,
    Ai,
    Diagnostics,
    About,
}

private enum class SettingsSubScreen {
    None,
    GitHubAccount,
    CrashLogs,
    GitLogs,
    Libraries,
    Terminal,
}

/** Root of the Settings screen. Order: Appearance, Editor, Workspace, GitHub, AI Assistant, Advanced, About. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRootScreen(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenEditing: () -> Unit,
    onOpenBehavior: () -> Unit,
    onOpenKeybindings: () -> Unit = {},
    onOpenDiagnostics: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenAbout: () -> Unit,
    /** M17: null in the normal flavor hides the row. */
    onOpenAi: (() -> Unit)? = null,
    /** Whether AI is switched on (the switch lives on the AI screen, so the row stays visible when off). */
    aiEnabled: Boolean = true,
    viewModel: EditorViewModel? = null,
    modifier: Modifier = Modifier,
    onOpenTermuxSetup: () -> Unit = {},
) {
    val isTablet = isTabletLayout()
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.Appearance) }
    var activeSubScreen by rememberSaveable { mutableStateOf(SettingsSubScreen.None) }
    val effectiveEditorVm: EditorViewModel = viewModel ?: viewModel(factory = EditorViewModel.Factory)

    val categoryList: @Composable (Boolean) -> Unit = { inTabletMode ->
        Scaffold(
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
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.Appearance && activeSubScreen == SettingsSubScreen.None,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.Appearance
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenAppearance()
                            }
                        },
                    )
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Edit,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.settings_section_editing),
                        subtitle = stringResource(R.string.settings_category_editing_desc),
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.Editing && activeSubScreen == SettingsSubScreen.None,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.Editing
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenEditing()
                            }
                        },
                    )
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Folder,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.settings_section_behavior),
                        subtitle = stringResource(R.string.settings_category_behavior_desc),
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.Behavior && activeSubScreen == SettingsSubScreen.None,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.Behavior
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenBehavior()
                            }
                        },
                    )
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Tune,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.keybindings_title),
                        subtitle = stringResource(R.string.settings_keybindings_desc),
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.Keybindings && activeSubScreen == SettingsSubScreen.None,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.Keybindings
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenKeybindings()
                            }
                        },
                    )
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Commit,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.settings_section_github),
                        subtitle = stringResource(R.string.settings_category_github_desc),
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.GitHub,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.GitHub
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenGitHub()
                            }
                        },
                    )
                    if (onOpenAi != null || (inTabletMode && com.invictus.kodex.ai.AiFlavor.gate != null)) {
                        CategoryRowGap()
                        CategoryRow(
                            icon = XIcons.AutoAwesome,
                            chevron = XIcons.ChevronRight,
                            title = "AI Assistant",
                            subtitle = if (aiEnabled) "Provider, model, memory and privacy" else "Off",
                            isSelected = inTabletMode && selectedCategory == SettingsCategory.Ai,
                            onClick = {
                                if (inTabletMode) {
                                    selectedCategory = SettingsCategory.Ai
                                    activeSubScreen = SettingsSubScreen.None
                                } else {
                                    onOpenAi?.invoke()
                                }
                            },
                        )
                    }
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Bolt,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.settings_section_diagnostics),
                        subtitle = stringResource(R.string.settings_category_diagnostics_desc),
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.Diagnostics,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.Diagnostics
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenDiagnostics()
                            }
                        },
                    )
                    CategoryRowGap()
                    CategoryRow(
                        icon = XIcons.Info,
                        chevron = XIcons.ChevronRight,
                        title = stringResource(R.string.about_title),
                        subtitle = stringResource(R.string.settings_category_about_desc),
                        isLast = true,
                        isSelected = inTabletMode && selectedCategory == SettingsCategory.About,
                        onClick = {
                            if (inTabletMode) {
                                selectedCategory = SettingsCategory.About
                                activeSubScreen = SettingsSubScreen.None
                            } else {
                                onOpenAbout()
                            }
                        },
                    )
                }
            }
        }
    }

    if (isTablet) {
        AdaptiveTwoPaneLayout(
            modifier = modifier,
            isDualPane = true,
            firstPane = { categoryList(true) },
            secondPane = {
                Crossfade(targetState = Pair(selectedCategory, activeSubScreen), label = "settings_detail") { (cat, sub) ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (sub) {
                            SettingsSubScreen.GitHubAccount -> SettingsGitHubAccountScreen(onBack = { activeSubScreen = SettingsSubScreen.None })
                            SettingsSubScreen.CrashLogs -> CrashLogsScreen(onBack = { activeSubScreen = SettingsSubScreen.None })
                            SettingsSubScreen.GitLogs -> GitLogsScreen(onBack = { activeSubScreen = SettingsSubScreen.None })
                            SettingsSubScreen.Libraries -> LibrariesScreen(onBack = { activeSubScreen = SettingsSubScreen.None })
                            SettingsSubScreen.Terminal -> SettingsTerminalScreen(
                                onBack = { activeSubScreen = SettingsSubScreen.None },
                                onOpenTermuxSetup = onOpenTermuxSetup,
                            )
                            SettingsSubScreen.None -> {
                                when (cat) {
                                    SettingsCategory.Appearance -> SettingsAppearanceScreen(
                                        viewModel = effectiveEditorVm,
                                        onBack = onBack,
                                    )
                                    SettingsCategory.Editing -> SettingsEditingScreen(
                                        viewModel = effectiveEditorVm,
                                        onBack = onBack,
                                    )
                                    SettingsCategory.Behavior -> SettingsBehaviorScreen(
                                        viewModel = effectiveEditorVm,
                                        onBack = onBack,
                                    )
                                    SettingsCategory.Keybindings -> SettingsKeybindingsScreen(
                                        viewModel = effectiveEditorVm,
                                        onBack = onBack,
                                    )
                                    SettingsCategory.GitHub -> SettingsGitHubScreen(
                                        onBack = onBack,
                                        onOpenAccount = { activeSubScreen = SettingsSubScreen.GitHubAccount },
                                    )
                                    SettingsCategory.Ai -> {
                                        com.invictus.kodex.ai.AiFlavor.gate?.SettingsAiScreen(onBack = onBack)
                                            ?: Text("AI is not available", modifier = Modifier.padding(24.dp))
                                    }
                                    SettingsCategory.Diagnostics -> SettingsDiagnosticsScreen(
                                        viewModel = effectiveEditorVm,
                                        onBack = onBack,
                                        onOpenCrashLogs = { activeSubScreen = SettingsSubScreen.CrashLogs },
                                        onOpenGitLogs = { activeSubScreen = SettingsSubScreen.GitLogs },
                                        onOpenTerminalSettings = { activeSubScreen = SettingsSubScreen.Terminal },
                                        onOpenTermuxSetup = onOpenTermuxSetup,
                                    )
                                    SettingsCategory.About -> AboutScreen(
                                        onBack = onBack,
                                        onOpenLibraries = { activeSubScreen = SettingsSubScreen.Libraries },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    } else {
        categoryList(false)
    }
}
