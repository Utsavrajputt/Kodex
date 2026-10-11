package com.invictus.kodex.ui.navigation

import androidx.compose.animation.core.tween
import com.invictus.kodex.feature.diagnostics.GitLogsScreen
import com.invictus.kodex.feature.settings.SettingsDiagnosticsScreen
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.invictus.kodex.feature.editor.EditorEvent
import com.invictus.kodex.feature.editor.EditorScreen
import com.invictus.kodex.feature.editor.EditorViewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.invictus.kodex.github.ui.AllApksScreen
import com.invictus.kodex.github.ui.GitHubManagerScreen
import com.invictus.kodex.github.ui.GitHubRunsScreen
import com.invictus.kodex.github.ui.RunLogScreen
import com.invictus.kodex.feature.git.GitBranchesScreen
import com.invictus.kodex.feature.git.GitCloneScreen
import com.invictus.kodex.feature.git.GitCredentialsScreen
import com.invictus.kodex.feature.git.GitHistoryScreen
import com.invictus.kodex.feature.git.GitOnboardingScreen
import com.invictus.kodex.feature.git.GitRemotesScreen
import com.invictus.kodex.feature.git.GitScreen
import com.invictus.kodex.feature.git.GitStashScreen
import com.invictus.kodex.feature.git.GitTagsScreen
import com.invictus.kodex.feature.home.AllProjectsScreen
import com.invictus.kodex.feature.home.HomeScreen
import com.invictus.kodex.feature.permission.OnboardingScreen
import com.invictus.kodex.feature.preview.MediaPreviewScreen
import com.invictus.kodex.feature.diagnostics.CrashLogsScreen
import com.invictus.kodex.feature.settings.AboutScreen
import com.invictus.kodex.feature.settings.LibrariesScreen
import com.invictus.kodex.feature.settings.SettingsAppearanceScreen
import com.invictus.kodex.feature.settings.SettingsBehaviorScreen
import com.invictus.kodex.feature.settings.SettingsEditingScreen
import com.invictus.kodex.feature.settings.SettingsGitHubScreen
import com.invictus.kodex.feature.search.CodeSearchScreen
import com.invictus.kodex.feature.search.SearchBus
import com.invictus.kodex.feature.settings.SettingsRootScreen
import com.invictus.kodex.feature.workspace.WorkspaceScreen

/** Shared-axis-X (slide + fade) screen transition, tween-based — cheap on low-end devices. */
private const val NavMotionDurationMs = 260
private const val NavFadeDurationMs = 180
private const val NavSlideFraction = 4

/**
 * Permission gate + app screens. Starts on the onboarding stepper (storage /
 * notifications) when All files access is missing or onboarding hasn't
 * been finished yet, and moves between the two as storage access is granted/revoked.
 */
@Composable
fun KodexNavHost(
    storageGranted: Boolean,
    notificationGranted: Boolean,
    onboardingCompleted: Boolean,
    onGrantStorage: () -> Unit,
    onRequestNotification: () -> Unit,
    onFinishOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    /** File handed in by "Open with Kodex" (ACTION_VIEW/EDIT); opened in the editor once permissions allow. */
    externalFile: java.io.File? = null,
    onExternalFileHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    // Activity-scoped on purpose: open tabs outlive the trip back to the file tree.
    val editorViewModel: EditorViewModel = viewModel(factory = EditorViewModel.Factory)
    val startDestination = remember {
        if (storageGranted && onboardingCompleted) Routes.HOME else Routes.PERMISSION
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            slideInHorizontally(tween(NavMotionDurationMs)) { it / NavSlideFraction } +
                fadeIn(tween(NavFadeDurationMs))
        },
        exitTransition = {
            slideOutHorizontally(tween(NavMotionDurationMs)) { -it / NavSlideFraction } +
                fadeOut(tween(NavFadeDurationMs))
        },
        popEnterTransition = {
            slideInHorizontally(tween(NavMotionDurationMs)) { -it / NavSlideFraction } +
                fadeIn(tween(NavFadeDurationMs))
        },
        popExitTransition = {
            slideOutHorizontally(tween(NavMotionDurationMs)) { it / NavSlideFraction } +
                fadeOut(tween(NavFadeDurationMs))
        },
    ) {
        composable(Routes.PERMISSION) {
            OnboardingScreen(
                hasStoragePermission = storageGranted,
                hasNotificationPermission = notificationGranted,
                onGrantStoragePermission = onGrantStorage,
                onRequestNotificationPermission = onRequestNotification,
                onFinishOnboarding = onFinishOnboarding,
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onProjectOpened = { navController.navigate(Routes.WORKSPACE) },
                onClone = { navController.navigate(Routes.GIT_CLONE) },
                onOpenApks = { navController.navigate(Routes.ALL_APKS) },
                onViewAll = { navController.navigate(Routes.ALL_PROJECTS) },
            )
        }
        composable(Routes.ALL_APKS) {
            AllApksScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.ALL_PROJECTS) {
            AllProjectsScreen(
                onBack = { navController.popBackStack() },
                onProjectOpened = { navController.navigate(Routes.WORKSPACE) },
            )
        }
        composable(Routes.WORKSPACE) {
            WorkspaceScreen(
                onOpenFile = { editorViewModel.onEvent(EditorEvent.Open(it)) },
                externalMessages = editorViewModel.messages,
                onProjectRoot = { editorViewModel.onProjectOpened(it.path) },
                onOpenGit = { root -> navController.navigate(Routes.git(root.path)) },
                onGitSetup = { root -> navController.navigate(Routes.gitOnboarding(root.path)) },
                onOpenCodeSearch = { root ->
                    navController.navigate(Routes.codeSearch(root.path))
                },
                onOpenGitHub = { root -> navController.navigate(Routes.github(root.path)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.EDITOR) {
            EditorScreen(
                viewModel = editorViewModel,
                onBack = {
                    if (navController.currentBackStackEntry?.destination?.route == Routes.EDITOR) {
                        navController.popBackStack()
                    }
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRootScreen(
                onBack = { navController.popBackStack() },
                onOpenAppearance = { navController.navigate(Routes.SETTINGS_APPEARANCE) },
                onOpenEditing = { navController.navigate(Routes.SETTINGS_EDITING) },
                onOpenBehavior = { navController.navigate(Routes.SETTINGS_BEHAVIOR) },
                onOpenDiagnostics = { navController.navigate(Routes.SETTINGS_DIAGNOSTICS) },
                onOpenGitHub = { navController.navigate(Routes.SETTINGS_GITHUB) },
                onOpenAbout = { navController.navigate(Routes.SETTINGS_ABOUT) },
            )
        }
        composable(Routes.SETTINGS_ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() },
                onOpenLibraries = { navController.navigate(Routes.SETTINGS_LIBRARIES) },
            )
        }
        composable(Routes.SETTINGS_LIBRARIES) {
            LibrariesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_GITHUB) {
            SettingsGitHubScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_DIAGNOSTICS) {
            SettingsDiagnosticsScreen(
                onBack = { navController.popBackStack() },
                onOpenCrashLogs = { navController.navigate(Routes.SETTINGS_CRASH_LOGS) },
                onOpenGitLogs = { navController.navigate(Routes.SETTINGS_GIT_LOGS) },
            )
        }
        composable(Routes.SETTINGS_CRASH_LOGS) {
            CrashLogsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_GIT_LOGS) {
            GitLogsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS_APPEARANCE) {
            SettingsAppearanceScreen(
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_EDITING) {
            SettingsEditingScreen(
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_BEHAVIOR) {
            SettingsBehaviorScreen(
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MEDIA_PREVIEW) {
            val file by editorViewModel.mediaPreviewFile.collectAsStateWithLifecycle()
            file?.let {
                MediaPreviewScreen(
                    file = it,
                    onBack = {
                        if (navController.currentBackStackEntry?.destination?.route == Routes.MEDIA_PREVIEW) {
                            navController.popBackStack()
                        }
                    },
                )
            }
        }
        composable(Routes.GIT) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitScreen(
                    projectPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenRoute = { route -> navController.navigate(route) },
                    onOpenFile = { file ->
                        // Navigation happens via showEditor once the tab actually exists; navigating
                        // now would hit EditorScreen with zero tabs and bounce straight back.
                        editorViewModel.onEvent(EditorEvent.Open(file))
                    },
                )
            }
        }
        composable(Routes.GIT_CLONE) {
            GitCloneScreen(
                onBack = { navController.popBackStack() },
                onOpenGitHubSettings = { navController.navigate(Routes.SETTINGS_GITHUB) },
            )
        }
        composable(Routes.GIT_BRANCHES) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitBranchesScreen(projectPath = path, onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = Routes.GIT_HISTORY,
            arguments = listOf(navArgument("path") { defaultValue = "" }),
        ) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitHistoryScreen(
                    projectPath = path,
                    filePath = entry.arguments?.getString("path")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.GIT_STASH) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitStashScreen(projectPath = path, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.GIT_TAGS) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitTagsScreen(projectPath = path, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.GIT_REMOTES) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitRemotesScreen(
                    projectPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenCredentials = {
                        navController.navigate(Routes.gitCredentials(path))
                    },
                )
            }
        }
        composable(Routes.GIT_CREDENTIALS) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitCredentialsScreen(projectPath = path, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.GIT_ONBOARDING) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitOnboardingScreen(
                    projectPath = path,
                    onDone = { navController.popBackStack() },
                )
            }
        }
        composable(Routes.GITHUB) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitHubManagerScreen(
                    projectPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenRunLog = { runId -> navController.navigate(Routes.githubRunLog(path, runId)) },
                    onOpenAllRuns = { navController.navigate(Routes.githubRuns(path)) },
                    onOpenRemotes = { navController.navigate(Routes.gitRemotes(path)) },
                    onGitSetup = { navController.navigate(Routes.gitOnboarding(path)) },
                    onOpenGitHubSettings = { navController.navigate(Routes.SETTINGS_GITHUB) },
                )
            }
        }
        composable(Routes.GITHUB_RUNS) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                GitHubRunsScreen(
                    projectPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenRunLog = { runId -> navController.navigate(Routes.githubRunLog(path, runId)) },
                )
            }
        }
        composable(
            route = Routes.GITHUB_RUN_LOG,
            arguments = listOf(navArgument("runId") { type = NavType.LongType }),
        ) { entry ->
            val path = entry.arguments?.getString("projectPath")
            val runId = entry.arguments?.getLong("runId")
            if (path != null && runId != null) {
                RunLogScreen(projectPath = path, runId = runId, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.CODE_SEARCH) { entry ->
            val path = entry.arguments?.getString("projectPath")
            if (path != null) {
                CodeSearchScreen(
                    projectPath = path,
                    onBack = { navController.popBackStack() },
                    onOpenMatch = { file, line, column, length ->
                        // See GIT onOpenFile: showEditor navigates once the tab is loaded.
                        editorViewModel.onEvent(EditorEvent.OpenAtLine(file, line, column, length))
                    },
                    onLocateInTree = { file ->
                        SearchBus.requestReveal(file)
                        navController.popBackStack(Routes.WORKSPACE, inclusive = false)
                    },
                )
            }
        }
    }

    // An external open may come from any screen (home, settings...), not only the workspace.
    var externalNavPending by remember { mutableStateOf(false) }
    val externalScope = rememberCoroutineScope()

    LaunchedEffect(editorViewModel) {
        editorViewModel.showEditor.collect {
            val route = navController.currentBackStackEntry?.destination?.route
            if (route == Routes.WORKSPACE || route == Routes.CODE_SEARCH || route == Routes.GIT) {
                navController.navigate(Routes.EDITOR) { launchSingleTop = true }
            } else if (externalNavPending && route != null && route != Routes.EDITOR &&
                route != Routes.PERMISSION && route != Routes.MEDIA_PREVIEW
            ) {
                navController.navigate(Routes.EDITOR) { launchSingleTop = true }
            }
            externalNavPending = false
        }
    }

    LaunchedEffect(externalFile, storageGranted, onboardingCompleted) {
        val file = externalFile ?: return@LaunchedEffect
        // Wait for storage access + onboarding; the file stays pending until then.
        if (!storageGranted || !onboardingCompleted) return@LaunchedEffect
        externalNavPending = true
        editorViewModel.onEvent(EditorEvent.Open(file))
        onExternalFileHandled()
        // If the open fails (unreadable / too big) no editor event comes: don't leave the flag set.
        // Own scope: onExternalFileHandled() changes this effect's key and would cancel a delay here.
        externalScope.launch {
            kotlinx.coroutines.delay(8_000)
            externalNavPending = false
        }
    }

    LaunchedEffect(editorViewModel) {
        editorViewModel.openMediaPreview.collect {
            navController.navigate(Routes.MEDIA_PREVIEW) { launchSingleTop = true }
        }
    }

    LaunchedEffect(storageGranted, onboardingCompleted) {
        val route = navController.currentBackStackEntry?.destination?.route
        when {
            storageGranted && onboardingCompleted && route == Routes.PERMISSION ->
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.PERMISSION) { inclusive = true }
                }
            !storageGranted && (route == Routes.HOME || route == Routes.WORKSPACE || route == Routes.EDITOR) ->
                navController.navigate(Routes.PERMISSION) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
        }
    }
}
