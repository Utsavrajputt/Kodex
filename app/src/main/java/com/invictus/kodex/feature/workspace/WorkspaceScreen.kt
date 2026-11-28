package com.invictus.kodex.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.invictus.kodex.core.external.ExternalProjectOpen
import com.invictus.kodex.core.external.TermuxLauncher
import com.invictus.kodex.core.external.TermuxSetupPrefs
import com.invictus.kodex.ui.components.expressiveClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.invictus.kodex.feature.editor.EditorEvent
import com.invictus.kodex.feature.editor.EditorScreen
import com.invictus.kodex.feature.editor.EditorViewModel
import com.invictus.kodex.ui.adaptive.AdaptiveTwoPaneLayout
import com.invictus.kodex.ui.adaptive.isTabletLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.git.GitOnboardingPrefs
import com.invictus.kodex.feature.project.OpenFileSheet
import com.invictus.kodex.feature.project.OpenProjectSheet
import com.invictus.kodex.feature.search.SearchBus
import com.invictus.kodex.feature.project.ProjectsEffect
import com.invictus.kodex.feature.project.ProjectsEvent
import com.invictus.kodex.feature.project.ProjectsViewModel
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Workspace home: the file tree. [onOpenFile] hands a tapped file to the editor;
 * [externalMessages] carries errors from it (e.g. a file that failed to open).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    modifier: Modifier = Modifier,
    viewModel: FileTreeViewModel = viewModel(factory = FileTreeViewModel.Factory),
    projectsViewModel: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory),
    editorViewModel: EditorViewModel? = null,
    onOpenFile: ((File) -> Unit)? = null,
    externalMessages: Flow<UiText>? = null,
    onProjectRoot: (File) -> Unit = {},
    onOpenGit: (File) -> Unit = {},
    onGitSetup: (File) -> Unit = {},
    onOpenCodeSearch: (File) -> Unit = {},
    onOpenGitHub: (File) -> Unit = {},
    onOpenAi: ((File) -> Unit)? = null,
    onOpenTermuxSetup: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.root) { onProjectRoot(state.root) }
    val projectsState by projectsViewModel.uiState.collectAsStateWithLifecycle()
    val isTablet = isTabletLayout()
    var sidebarCollapsed by rememberSaveable { mutableStateOf(false) }
    var sidebarOnRight by rememberSaveable { mutableStateOf(false) }
    var sidebarWidthDp by rememberSaveable { mutableFloatStateOf(320f) }
    val isGitRepo by produceState(false, state.root) {
        value = withContext(Dispatchers.IO) { File(state.root, ".git").isDirectory }
    }
    var showProjectSheet by rememberSaveable { mutableStateOf(false) }
    var showFileSearch by rememberSaveable { mutableStateOf(false) }
    var filterQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // M9: offer the Git setup wizard once when this project is not a repository yet.
    val gitOnboardingPrefs = remember {
        GitOnboardingPrefs((context.applicationContext as KodexApp).applicationContext)
    }
    LaunchedEffect(state.root) {
        if (File(state.root, ".git").isDirectory) return@LaunchedEffect
        if (gitOnboardingPrefs.enabled.first() && !gitOnboardingPrefs.isDone(state.root.path)) {
            onGitSetup(state.root)
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Tree menu "Upload": system picker -> copy into the chosen folder -> (zip) extract dialog.
    var uploadDir by remember { mutableStateOf<File?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = uploadDir
        uploadDir = null
        if (dir != null && uris.isNotEmpty()) {
            scope.launch {
                val files = withContext(Dispatchers.IO) { UploadOps.copyUris(context.contentResolver, uris, dir) }
                viewModel.onEvent(FileTreeEvent.UploadFinished(dir, files))
            }
        }
    }

    // Changes made outside the app (or missed by the watcher) show up when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(FileTreeEvent.Refresh) }

    BackHandler(enabled = state.renaming != null) { viewModel.onEvent(FileTreeEvent.CancelRename) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FileTreeEffect.Message ->
                    scope.launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is FileTreeEffect.PickUploadFiles -> {
                    uploadDir = effect.dir
                    uploadPicker.launch(arrayOf("*/*"))
                }
                is FileTreeEffect.ScrollTo -> {
                    val index = viewModel.uiState.value.rows
                        .indexOfFirst { it is TreeRow.Entry && it.file.path == effect.path }
                    if (index >= 0) listState.animateScrollToItem(index)
                }
                is FileTreeEffect.OpenFile ->
                    if (onOpenFile != null) {
                        onOpenFile(effect.file)
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.workspace_open_placeholder, effect.file.name),
                            )
                        }
                    }
            }
        }
    }

    LaunchedEffect(externalMessages) {
        externalMessages?.collect { text ->
            scope.launch { snackbarHostState.showSnackbar(text.resolve(context)) }
        }
    }

    // M11: code search ka "Locate in file tree" -> existing Reveal/ScrollTo pipeline.
    LaunchedEffect(Unit) {
        SearchBus.revealRequest.collect { file ->
            if (file != null) {
                viewModel.onEvent(FileTreeEvent.Reveal(file, file.isDirectory))
                SearchBus.consumeReveal()
            }
        }
    }

    LaunchedEffect(projectsViewModel) {
        projectsViewModel.effects.collect { effect ->
            when (effect) {
                is ProjectsEffect.Message ->
                    scope.launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is ProjectsEffect.ProjectMoved ->
                    viewModel.onEvent(FileTreeEvent.ProjectMoved(effect.old, effect.new))
                is ProjectsEffect.ProjectDeleted ->
                    viewModel.onEvent(FileTreeEvent.ProjectDeleted(effect.dir))
                // Only the home screen creates projects.
                is ProjectsEffect.ProjectCreated -> Unit
            }
        }
    }

    // Workspace 3-dot menu "Terminal": hands the project folder to Termux (no terminal bundled in the APK).
    // Never forces the setup screen: a problem shows a snackbar with a "Setup" action instead.
    val termuxProblem: () -> Unit = {
        scope.launch {
            val msg = if (TermuxLauncher.isPlayStoreBuild(context)) {
                context.getString(R.string.terminal_err_playstore_unsupported)
            } else {
                context.getString(R.string.termux_not_ready)
            }
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = context.getString(R.string.termux_setup_action),
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenTermuxSetup()
        }
    }
    val openTermux: () -> Unit = openTermux@{
        com.invictus.kodex.terminal.TerminalPrefs.ensure(context)
        if (com.invictus.kodex.terminal.TerminalPrefs.inAppActive) {
            com.invictus.kodex.terminal.TerminalHub.open(context, state.root, inSplit = isTablet && editorViewModel != null)
            return@openTermux
        }
        if (TermuxLauncher.openSession(context, state.root)) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.termux_opening_hint),
                    actionLabel = context.getString(R.string.termux_setup_help),
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenTermuxSetup()
            }
        } else {
            termuxProblem()
        }
    }
    var afterTermuxPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val termuxPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterTermuxPermission
        afterTermuxPermission = null
        if (granted) action?.invoke() else termuxProblem()
    }
    /** Runs [action] once Termux is installed and Kodex may call it. */
    val withTermux: (() -> Unit) -> Unit = { action ->
        when {
            !TermuxLauncher.isInstalled(context) -> termuxProblem()
            !TermuxLauncher.hasPermission(context) -> {
                afterTermuxPermission = action
                termuxPermission.launch(TermuxLauncher.PERMISSION)
            }
            else -> action()
        }
    }
    val openSshTerminal: () -> Unit = {
        val sshMgr = (context.applicationContext as KodexApp).container.sshConnectionManager
        val conn = sshMgr.currentConnected()
        if (conn != null) {
            com.invictus.kodex.terminal.TerminalHub.openSsh(
                context,
                conn.host,
                sshMgr,
                conn.remoteRoot,
                inSplit = isTablet && editorViewModel != null,
            )
        }
    }
    val onOpenTerminal: () -> Unit = {
        if (state.sshSession != null) openSshTerminal() else withTermux(openTermux)
    }

    // `kodex <folder>` in Termux asks for this folder to become the project.
    val pendingProjectDir by ExternalProjectOpen.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingProjectDir) {
        val dir = pendingProjectDir ?: return@LaunchedEffect
        viewModel.onEvent(FileTreeEvent.OpenProject(dir))
        ExternalProjectOpen.consume(dir)
    }
    val copyPath: (File) -> Unit = { file ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, file.path))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.msg_path_copied)) }
        }
    }

    val fileTreeContent: @Composable (
        onCollapseSidebar: (() -> Unit)?,
        onToggleSidebarSide: (() -> Unit)?,
        sidebarOnRight: Boolean,
    ) -> Unit = { onCollapse, onToggleSide, onRight ->
        Scaffold(
            modifier = modifier,
            topBar = {
                WorkspaceTopBar(
                    state = state,
                    onEvent = viewModel::onEvent,
                    onOpenProjectSheet = { showProjectSheet = true },
                    onOpenFile = { showFileSearch = true },
                    onOpenGit = if (isGitRepo) ({ onOpenGit(state.root) }) else null,
                    onOpenCodeSearch = { onOpenCodeSearch(state.root) },
                    onOpenGitHub = { onOpenGitHub(state.root) },
                    onOpenAi = onOpenAi?.let { open -> { open(state.root) } },
                    onOpenTerminal = onOpenTerminal,
                    terminalEnabled = state.sshSession != null || TermuxSetupPrefs.enabled(context).value,
                    onOpenSettings = onOpenSettings,
                    onCollapseSidebar = onCollapse,
                    onToggleSidebarSide = onToggleSide,
                    sidebarOnRight = onRight,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                val clipboard = state.clipboard
                if (clipboard != null) {
                    ClipboardBar(clipboard = clipboard, onClear = { viewModel.onEvent(FileTreeEvent.ClearClipboard) })
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                val rootError = state.rootError
                if (rootError != null) {
                    Text(
                        text = rootError.asString(),
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        state.sshSession?.let { ssh ->
                            SshWorkspaceBanner(
                                session = ssh,
                                onOpenTerminal = openSshTerminal,
                                onReconnect = { viewModel.onEvent(FileTreeEvent.ReconnectSsh) },
                                onDisconnect = { viewModel.onEvent(FileTreeEvent.DisconnectSsh) },
                            )
                        }
                        FileFilterBar(
                            query = filterQuery,
                            onQueryChange = { filterQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        FileTree(
                            state = state,
                            onEvent = viewModel::onEvent,
                            onCopyPath = copyPath,
                            listState = listState,
                            filterQuery = filterQuery,
                            onSearchResultOpened = { filterQuery = "" },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (isTablet && editorViewModel != null) {
        val editorState by editorViewModel.uiState.collectAsStateWithLifecycle()
        var showEmptyTerminalSplit by rememberSaveable { mutableStateOf(false) }
        var isEmptyVerticalSplit by rememberSaveable { mutableStateOf(false) }
        var emptyTerminalSplitRatio by rememberSaveable { mutableFloatStateOf(0.6f) }
        LaunchedEffect(editorState.tabs.isEmpty()) {
            if (editorState.tabs.isEmpty()) {
                com.invictus.kodex.terminal.TerminalHub.splitEvents.collect {
                    showEmptyTerminalSplit = true
                }
            }
        }
        val emptyTerminalEnabled = state.sshSession != null || TermuxSetupPrefs.enabled(context).value
        val emptyPaneWithOptionalTerminal: @Composable ((@Composable () -> Unit)?) -> Unit = { navIcon ->
            val emptyPane: @Composable (Modifier) -> Unit = { m ->
                Box(m) {
                    WorkspaceEmptyEditorPane(
                        projectRoot = state.root,
                        onOpenFolder = { showProjectSheet = true },
                        onOpenFile = { showFileSearch = true },
                        onOpenSearch = { onOpenCodeSearch(state.root) },
                        onOpenGit = if (isGitRepo) ({ onOpenGit(state.root) }) else null,
                        onOpenSettings = onOpenSettings,
                        onToggleTerminal = if (emptyTerminalEnabled) {
                            {
                                if (showEmptyTerminalSplit) showEmptyTerminalSplit = false
                                else onOpenTerminal()
                            }
                        } else null,
                        terminalActive = showEmptyTerminalSplit,
                        navigationIcon = navIcon,
                        sidebarOnRight = sidebarOnRight,
                    )
                }
            }
            if (showEmptyTerminalSplit) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val terminalArea: @Composable (Modifier) -> Unit = { m ->
                    com.invictus.kodex.feature.terminal.TerminalScreen(
                        onBack = { showEmptyTerminalSplit = false },
                        onOpenSetup = onOpenTermuxSetup,
                        isSplit = true,
                        isVerticalSplit = isEmptyVerticalSplit,
                        onToggleSplitOrientation = { isEmptyVerticalSplit = !isEmptyVerticalSplit },
                        onCloseSplit = { showEmptyTerminalSplit = false },
                        modifier = m,
                    )
                }
                if (!isEmptyVerticalSplit) {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val totalHeightPx = with(density) { maxHeight.toPx() }
                        Column(Modifier.fillMaxSize()) {
                            emptyPane(Modifier.weight(emptyTerminalSplitRatio).fillMaxWidth())
                            com.invictus.kodex.ui.components.HorizontalSplitHandle(
                                onDragDeltaPx = { deltaY ->
                                    if (totalHeightPx > 0f) {
                                        emptyTerminalSplitRatio = (emptyTerminalSplitRatio + deltaY / totalHeightPx).coerceIn(0.15f, 0.85f)
                                    }
                                },
                            )
                            terminalArea(Modifier.weight(1f - emptyTerminalSplitRatio).fillMaxWidth())
                        }
                    }
                } else {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val totalWidthPx = with(density) { maxWidth.toPx() }
                        Row(Modifier.fillMaxSize()) {
                            emptyPane(Modifier.weight(emptyTerminalSplitRatio).fillMaxHeight())
                            com.invictus.kodex.ui.components.VerticalSplitHandle(
                                onDragDeltaPx = { deltaX ->
                                    if (totalWidthPx > 0f) {
                                        emptyTerminalSplitRatio = (emptyTerminalSplitRatio + deltaX / totalWidthPx).coerceIn(0.15f, 0.85f)
                                    }
                                },
                            )
                            terminalArea(Modifier.weight(1f - emptyTerminalSplitRatio).fillMaxHeight())
                        }
                    }
                }
            } else {
                emptyPane(Modifier.fillMaxSize())
            }
        }
        val showSidebarButton: @Composable () -> Unit = {
            IconButton(onClick = { sidebarCollapsed = false }) {
                Icon(
                    XIcons.Menu,
                    contentDescription = "Expand explorer",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (sidebarCollapsed) {
            Box(Modifier.fillMaxSize()) {
                if (editorState.tabs.isNotEmpty()) {
                    EditorScreen(
                        viewModel = editorViewModel,
                        onBack = {
                            editorState.activePath?.let { editorViewModel.onEvent(EditorEvent.CloseTab(it)) }
                        },
                        onOpenSettings = onOpenSettings,
                        navigationIcon = if (!sidebarOnRight) showSidebarButton else null,
                        trailingAction = if (sidebarOnRight) showSidebarButton else null,
                        onOpenTermuxSetup = onOpenTermuxSetup,
                        onOpenFolder = { showProjectSheet = true },
                        onToggleSidebar = { sidebarCollapsed = !sidebarCollapsed },
                        onOpenCodeSearch = { onOpenCodeSearch(state.root) },
                    )
                } else {
                    emptyPaneWithOptionalTerminal(showSidebarButton)
                }
            }
        } else {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val screenWidth = configuration.screenWidthDp.toFloat()
            val maxSidebarWidth = (screenWidth * 0.65f).coerceAtLeast(260f)
            val density = androidx.compose.ui.platform.LocalDensity.current

            val sidebarPane = @Composable {
                fileTreeContent(
                    { sidebarCollapsed = true },
                    { sidebarOnRight = !sidebarOnRight },
                    sidebarOnRight,
                )
            }
            val editorPane = @Composable {
                if (editorState.tabs.isNotEmpty()) {
                    EditorScreen(
                        viewModel = editorViewModel,
                        onBack = {
                            editorState.activePath?.let { editorViewModel.onEvent(EditorEvent.CloseTab(it)) }
                        },
                        onOpenSettings = onOpenSettings,
                        navigationIcon = null,
                        onOpenTermuxSetup = onOpenTermuxSetup,
                        onOpenFolder = { showProjectSheet = true },
                        onToggleSidebar = { sidebarCollapsed = !sidebarCollapsed },
                        onOpenCodeSearch = { onOpenCodeSearch(state.root) },
                    )
                } else {
                    emptyPaneWithOptionalTerminal(null)
                }
            }

            Row(Modifier.fillMaxSize()) {
                if (!sidebarOnRight) {
                    Box(Modifier.width(sidebarWidthDp.dp).fillMaxHeight()) {
                        sidebarPane()
                    }
                    com.invictus.kodex.ui.components.VerticalSplitHandle(
                        onDragDeltaPx = { deltaPx ->
                            val deltaDp = with(density) { deltaPx.toDp() }
                            sidebarWidthDp = (sidebarWidthDp + deltaDp.value).coerceIn(200f, maxSidebarWidth)
                        }
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        editorPane()
                    }
                } else {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        editorPane()
                    }
                    com.invictus.kodex.ui.components.VerticalSplitHandle(
                        onDragDeltaPx = { deltaPx ->
                            val deltaDp = with(density) { deltaPx.toDp() }
                            sidebarWidthDp = (sidebarWidthDp - deltaDp.value).coerceIn(200f, maxSidebarWidth)
                        }
                    )
                    Box(Modifier.width(sidebarWidthDp.dp).fillMaxHeight()) {
                        sidebarPane()
                    }
                }
            }
        }
    } else {
        fileTreeContent(null, null, false)
    }

    TreeDialogHost(dialog = state.dialog, onEvent = viewModel::onEvent)

    if (showFileSearch) {
        OpenFileSheet(
            root = state.root,
            showHidden = state.showHidden,
            onOpen = { file ->
                showFileSearch = false
                onOpenFile?.invoke(file)
            },
            onDismiss = { showFileSearch = false },
        )
    }

    if (showProjectSheet) {
        OpenProjectSheet(
            state = projectsState,
            currentRoot = state.root,
            snackbarHostState = snackbarHostState,
            onEvent = projectsViewModel::onEvent,
            onOpen = { dir ->
                showProjectSheet = false
                viewModel.onEvent(FileTreeEvent.OpenProject(dir))
            },
            onCopyPath = copyPath,
            onDismiss = { showProjectSheet = false },
        )
    }
}

@Composable
private fun SshWorkspaceBanner(
    session: SshSessionUi,
    onOpenTerminal: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        color = if (session.error != null) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (session.reconnecting) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (session.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                        ),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SSH: ${session.hostLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        session.reconnecting -> "Reconnecting…"
                        session.error != null -> session.error
                        else -> session.remoteRoot
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (session.error != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (session.error != null) {
                TextButton(
                    onClick = onReconnect,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ssh_quick_connect_action),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                IconButton(
                    onClick = onOpenTerminal,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        XIcons.Terminal,
                        contentDescription = stringResource(R.string.ssh_open_terminal),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            TextButton(
                onClick = onDisconnect,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.ssh_disconnect),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    state: FileTreeUiState,
    onEvent: (FileTreeEvent) -> Unit,
    onOpenProjectSheet: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenGit: (() -> Unit)? = null,
    onOpenCodeSearch: () -> Unit = {},
    onOpenGitHub: () -> Unit = {},
    onOpenAi: (() -> Unit)? = null,
    onOpenTerminal: () -> Unit = {},
    onRunCommand: () -> Unit = {},
    terminalEnabled: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onCollapseSidebar: (() -> Unit)? = null,
    onToggleSidebarSide: (() -> Unit)? = null,
    sidebarOnRight: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            if (onCollapseSidebar != null) {
                IconButton(onClick = onCollapseSidebar) {
                    Icon(XIcons.MenuOpen, contentDescription = "Collapse explorer")
                }
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .expressiveClickable(onClick = onOpenProjectSheet)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = state.rootName ?: stringResource(R.string.workspace_root_internal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    XIcons.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.menu_open_project),
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        actions = {
            // AI sits where Refresh used to be; Refresh now lives in the 3-dot menu.
            if (onOpenAi != null) {
                IconButton(onClick = onOpenAi) {
                    Icon(XIcons.AutoAwesome, contentDescription = "AI Agent")
                }
            }
            IconButton(onClick = onOpenCodeSearch) {
                Icon(XIcons.Search, contentDescription = stringResource(R.string.search_code_title))
            }
            if (onOpenGit != null) {
                IconButton(onClick = onOpenGit) {
                    Icon(XIcons.Commit, contentDescription = stringResource(R.string.git_title))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_open_folder)) },
                        leadingIcon = { Icon(XIcons.FolderOpen, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenProjectSheet()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_open_file)) },
                        leadingIcon = { Icon(XIcons.InsertDriveFile, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenFile()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        leadingIcon = { Icon(XIcons.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEvent(FileTreeEvent.Refresh)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_show_hidden)) },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.showHidden) XIcons.Visibility else XIcons.VisibilityOff,
                                contentDescription = null,
                            )
                        },
                        onClick = { onEvent(FileTreeEvent.SetShowHidden(!state.showHidden)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_collapse_all)) },
                        leadingIcon = { Icon(XIcons.CollapseAll, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEvent(FileTreeEvent.CollapseAll)
                        },
                    )
                    if (onToggleSidebarSide != null) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (sidebarOnRight) R.string.sidebar_move_left
                                        else R.string.sidebar_move_right
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (sidebarOnRight) XIcons.DockLeft else XIcons.DockRight,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleSidebarSide()
                            },
                        )
                    }
                    if (terminalEnabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_terminal)) },
                            leadingIcon = { Icon(XIcons.Terminal, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenTerminal()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_settings)) },
                        leadingIcon = { Icon(XIcons.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )
}

/** Inline filename filter shown just above the file tree, like a regular file-manager search bar. */
@Composable
private fun FileFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XIcons.Search,
            contentDescription = stringResource(R.string.search_files_hint),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_files_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(20.dp)) {
                Icon(
                    XIcons.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ClipboardBar(clipboard: Clipboard, onClear: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (clipboard.isCut) R.string.clipboard_bar_cut else R.string.clipboard_bar_copied,
                    clipboard.file.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
        }
    }
}

@Composable
private fun WorkspaceEmptyEditorPane(
    projectRoot: File,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenGit: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onToggleTerminal: (() -> Unit)? = null,
    terminalActive: Boolean = false,
    navigationIcon: (@Composable () -> Unit)? = null,
    sidebarOnRight: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp)
                .widthIn(max = 520.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    XIcons.Code,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = projectRoot.name.ifEmpty { stringResource(R.string.app_name) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.workspace_tablet_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onOpenFolder,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(XIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.menu_open_folder))
                }
                FilledTonalButton(
                    onClick = onOpenFile,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(XIcons.InsertDriveFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.menu_open_file))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onOpenSearch,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(XIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_code_title))
                }
                if (onOpenGit != null) {
                    FilledTonalButton(
                        onClick = onOpenGit,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(XIcons.Commit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.git_title))
                    }
                }
            }
        }
        if (navigationIcon != null || onToggleTerminal != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!sidebarOnRight) {
                    navigationIcon?.invoke()
                    Spacer(Modifier.weight(1f))
                    onToggleTerminal?.let { toggle ->
                        IconButton(onClick = toggle) {
                            Icon(
                                XIcons.Terminal,
                                contentDescription = stringResource(R.string.terminal_title),
                                tint = if (terminalActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                    onToggleTerminal?.let { toggle ->
                        IconButton(onClick = toggle) {
                            Icon(
                                XIcons.Terminal,
                                contentDescription = stringResource(R.string.terminal_title),
                                tint = if (terminalActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    navigationIcon?.invoke()
                }
            }
        }
    }
}
