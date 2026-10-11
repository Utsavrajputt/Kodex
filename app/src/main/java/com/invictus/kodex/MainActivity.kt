package com.invictus.kodex

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.invictus.kodex.core.external.ExternalFileResolver
import com.invictus.kodex.github.notify.GhNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.kodex.ui.MainUiEvent
import com.invictus.kodex.ui.MainViewModel
import com.invictus.kodex.ui.navigation.KodexNavHost
import com.invictus.kodex.ui.theme.ThemePickerSheet
import com.invictus.kodex.ui.theme.ThemePickerState
import com.invictus.kodex.ui.theme.ThemeTransitionController
import com.invictus.kodex.ui.theme.ThemeTransitionOverlay
import com.invictus.kodex.ui.theme.KodexTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val transitionController = ThemeTransitionController()

    /** File from an "Open with Kodex" intent, waiting for the nav host to open it in the editor. */
    private var externalFile by mutableStateOf<File?>(null)

    /** Project whose GitHub screen a build notification asked for; consumed by the nav host. */
    private var openGitHub by mutableStateOf<String?>(null)
    private var openGitHubRun by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleOpenIntent(intent)
            handleGitHubIntent(intent)
        }

        val container = (application as KodexApp).container
        val storagePermission = container.storagePermission
        val notificationPermission = container.notificationPermission

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            // POST_NOTIFICATIONS is a runtime permission on API 33+ only; below that,
            // declaring it in the manifest is enough, so there's nothing to launch.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                viewModel.onEvent(MainUiEvent.RefreshPermission)
            }

            KodexTheme {
                Box(Modifier.fillMaxSize()) {
                    KodexNavHost(
                        storageGranted = uiState.storageGranted,
                        notificationGranted = uiState.notificationGranted,
                        onboardingCompleted = uiState.onboardingCompleted,
                        onGrantStorage = { storagePermission.openSettings(this@MainActivity) },
                        onRequestNotification = {
                            val permission = notificationPermission.manifestPermission
                            if (permission != null && !notificationPermission.isGranted()) {
                                notificationPermissionLauncher.launch(permission)
                            } else {
                                // Already declined once (Android won't show the system prompt
                                // again) or on API < 33 where notifications are just disabled
                                // in system settings -- send the user there instead.
                                notificationPermission.openSettings(this@MainActivity)
                            }
                        },
                        onFinishOnboarding = { viewModel.onEvent(MainUiEvent.CompleteOnboarding) },
                        externalFile = externalFile,
                        onExternalFileHandled = { externalFile = null },
                        openGitHubProject = openGitHub,
                        openGitHubRunId = openGitHubRun,
                        onOpenGitHubHandled = { openGitHub = null; openGitHubRun = 0L },
                    )
                    ThemeTransitionOverlay(transitionController)
                    if (ThemePickerState.visible) {
                        ThemePickerSheet(
                            onDismiss = { ThemePickerState.visible = false },
                            transitionController = transitionController,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)
        handleGitHubIntent(intent)
    }

    private fun handleGitHubIntent(intent: Intent?) {
        val open = intent ?: return
        val path = open.getStringExtra(GhNotifier.EXTRA_OPEN_GITHUB) ?: return
        val runId = open.getLongExtra(GhNotifier.EXTRA_OPEN_RUN, 0L)
        open.removeExtra(GhNotifier.EXTRA_OPEN_GITHUB)
        open.removeExtra(GhNotifier.EXTRA_OPEN_RUN)
        openGitHubRun = runId
        openGitHub = path
    }

    /** ACTION_VIEW / ACTION_EDIT from a file manager or another app -> resolve off-main, then hand to the nav host. */
    private fun handleOpenIntent(intent: Intent?) {
        if (!ExternalFileResolver.isOpenIntent(intent)) return
        val open = intent ?: return
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { ExternalFileResolver.resolve(applicationContext, open) }
            if (file != null) externalFile = file
        }
    }

    override fun onResume() {
        super.onResume()
        // User may have just flipped a switch in system settings (or revoked one).
        viewModel.onEvent(MainUiEvent.RefreshPermission)
    }
}
