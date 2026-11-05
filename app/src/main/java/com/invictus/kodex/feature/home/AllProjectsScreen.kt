package com.invictus.kodex.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.feature.project.ProjectsEffect
import com.invictus.kodex.feature.project.ProjectsEvent
import com.invictus.kodex.feature.project.RecentItem
import com.invictus.kodex.feature.workspace.FileTreeEvent
import com.invictus.kodex.feature.workspace.FileTreeViewModel
import com.invictus.kodex.feature.project.ProjectsViewModel
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

private enum class ProjectSort(val labelRes: Int) {
    Recent(R.string.home_sort_recent),
    NameAsc(R.string.home_sort_name_az),
    NameDesc(R.string.home_sort_name_za),
}

/** Full recent-projects list with search and sort. Recents arrive newest-first from the DB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllProjectsScreen(
    onBack: () -> Unit,
    onProjectOpened: () -> Unit,
    modifier: Modifier = Modifier,
    projectsViewModel: ProjectsViewModel = viewModel(factory = ProjectsViewModel.Factory),
    fileTreeViewModel: FileTreeViewModel = viewModel(factory = FileTreeViewModel.Factory),
) {
    val state by projectsViewModel.uiState.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sort = ProjectSort.entries[sortIndex]
    val openProject = rememberProjectOpener(onProjectOpened = onProjectOpened, fileTreeViewModel = fileTreeViewModel)
    val snackbarHostState = remember { SnackbarHostState() }
    val copyPath = rememberCopyPath(snackbarHostState)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(projectsViewModel) {
        projectsViewModel.effects.collect { effect ->
            when (effect) {
                is ProjectsEffect.Message ->
                    scope.launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is ProjectsEffect.ProjectMoved ->
                    fileTreeViewModel.onEvent(FileTreeEvent.ProjectMoved(effect.old, effect.new))
                is ProjectsEffect.ProjectDeleted ->
                    fileTreeViewModel.onEvent(FileTreeEvent.ProjectDeleted(effect.dir))
                // Only Home creates projects.
                is ProjectsEffect.ProjectCreated -> Unit
            }
        }
    }

    val closeSearch = {
        searching = false
        query = ""
    }
    BackHandler(enabled = searching) { closeSearch() }

    val shown = remember(state.recents, query, sort) {
        val matching = state.recents.filter { it.matches(query) }
        // Pinned projects stay on top whichever sort is picked.
        val pinnedFirst = compareByDescending<RecentItem> { it.pinned }
        when (sort) {
            ProjectSort.Recent -> matching
            ProjectSort.NameAsc -> matching.sortedWith(pinnedFirst.thenBy { it.file.name.lowercase() })
            ProjectSort.NameDesc -> matching.sortedWith(pinnedFirst.thenByDescending { it.file.name.lowercase() })
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = { if (searching) closeSearch() else onBack() }) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    if (searching) {
                        InlineSearchField(query = query, onQueryChange = { query = it })
                    } else {
                        Text(stringResource(R.string.home_all_projects))
                    }
                },
                actions = {
                    if (!searching) {
                        IconButton(onClick = { searching = true }) {
                            Icon(XIcons.Search, contentDescription = stringResource(R.string.home_search))
                        }
                    } else if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(XIcons.Close, contentDescription = stringResource(R.string.action_close_dialog))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "sort") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { sortMenuOpen = true }) { Text(stringResource(sort.labelRes)) }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        ProjectSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(option.labelRes)) },
                                onClick = {
                                    sortIndex = option.ordinal
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(if (state.recents.isEmpty()) R.string.home_no_recents else R.string.home_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(items = shown, key = { "r:" + it.file.path }) { item ->
                    ProjectCardRow(
                        item = item,
                        onOpen = { openProject(item.file) },
                        onRemove = { projectsViewModel.onEvent(ProjectsEvent.RemoveRecent(item.file.path)) },
                        onTogglePin = { projectsViewModel.onEvent(ProjectsEvent.TogglePin(item.file.path, !item.pinned)) },
                        onRename = { projectsViewModel.onEvent(ProjectsEvent.StartRename(item.file)) },
                        onDelete = { projectsViewModel.onEvent(ProjectsEvent.StartDelete(item.file)) },
                        onInfo = { projectsViewModel.onEvent(ProjectsEvent.ShowInfo(item.file)) },
                        onCopyPath = { copyPath(item.file) },
                        onBackup = { projectsViewModel.onEvent(ProjectsEvent.Backup(item.file)) },
                        isBackingUp = item.file.path in state.backingUp,
                    )
                }
            }
        }
    }

    ProjectActionDialogs(state = state, onEvent = projectsViewModel::onEvent)
}
