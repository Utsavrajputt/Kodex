package com.invictus.kodex.feature.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.invictus.kodex.ui.components.expressiveClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import com.invictus.kodex.feature.project.OpenProjectSheet
import com.invictus.kodex.feature.search.FileSearchOverlay
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
    onOpenFile: ((File) -> Unit)? = null,
    externalMessages: Flow<UiText>? = null,
    onProjectRoot: (File) -> Unit = {},
    onOpenGit: (File) -> Unit = {},
    onGitSetup: (File) -> Unit = {},
    onOpenCodeSearch: (File) -> Unit = {},
    onOpenGitHub: (File) -> Unit = {},
    onOpenAi: ((File) -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.root) { onProjectRoot(state.root) }
    val projectsState by projectsViewModel.uiState.collectAsStateWithLifecycle()
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

    val copyPath: (File) -> Unit = { file ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, file.path))
        // Android 13+ shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.msg_path_copied)) }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            WorkspaceTopBar(
                state = state,
                onEvent = viewModel::onEvent,
                onOpenProjectSheet = { showProjectSheet = true },
                onBackup = { projectsViewModel.onEvent(ProjectsEvent.Backup(state.root)) },
                onOpenGit = if (isGitRepo) ({ onOpenGit(state.root) }) else null,
                onOpenCodeSearch = { onOpenCodeSearch(state.root) },
                onOpenGitHub = { onOpenGitHub(state.root) },
                onOpenAi = onOpenAi?.let { open -> { open(state.root) } },
                onOpenSettings = onOpenSettings,
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

    TreeDialogHost(dialog = state.dialog, onEvent = viewModel::onEvent)

    if (showFileSearch) {
        FileSearchOverlay(
            root = state.root,
            onOpen = { file ->
                showFileSearch = false
                onOpenFile?.invoke(file)
            },
            onDismiss = { showFileSearch = false },
            onLocateInTree = { file ->
                showFileSearch = false
                SearchBus.requestReveal(file)
            },
        )
        androidx.activity.compose.BackHandler { showFileSearch = false }
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
            // Inside a project there's no Home recents list visible, so keep it here.
            showRecent = true,
            // Change-project sheet inside the workspace: just Recents -- no search, no
            // folder browser, no clone (Home's Open Project sheet keeps all of that).
            showSearch = false,
            showBrowse = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTopBar(
    state: FileTreeUiState,
    onEvent: (FileTreeEvent) -> Unit,
    onOpenProjectSheet: () -> Unit,
    onBackup: () -> Unit,
    onOpenGit: (() -> Unit)? = null,
    onOpenCodeSearch: () -> Unit = {},
    onOpenGitHub: () -> Unit = {},
    onOpenAi: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
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
                    if (state.rootName != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_backup_project)) },
                            leadingIcon = { Icon(XIcons.CloudUpload, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onBackup()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_collapse_all)) },
                        leadingIcon = { Icon(XIcons.CollapseAll, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEvent(FileTreeEvent.CollapseAll)
                        },
                    )
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
