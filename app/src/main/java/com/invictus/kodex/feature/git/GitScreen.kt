package com.invictus.kodex.feature.git

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.git.model.GitFileDiffResult
import com.invictus.kodex.core.git.model.GitPathChange
import com.invictus.kodex.core.git.model.GitRepoSnapshot
import com.invictus.kodex.core.git.model.GitStageState
import com.invictus.kodex.core.git.model.GitWorkingState
import com.invictus.kodex.ui.icons.XIcons
import com.invictus.kodex.ai.AiGate
import com.invictus.kodex.ui.navigation.Routes
import kotlinx.coroutines.launch

private const val REMOVE_PREVIEW_MAX = 15

/**
 * Source Control screen (M6): branch + tracking header, commit box with amend,
 * push/pull/fetch, and stage/unstage lists. The M7 drawer wraps the same
 * [GitViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    projectPath: String,
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit = {},
    onOpenFile: (java.io.File) -> Unit = {},
    viewModel: GitViewModel = viewModel(
        key = "git:$projectPath",
        factory = GitViewModel.factory(projectPath),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var overflowOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // External edits (Termux, another git client) show up when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(GitEvent.ResumeRefresh) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is GitViewModel.Effect.Message ->
                    scope.launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                is GitViewModel.Effect.OpenFile -> onOpenFile(effect.file)
            }
        }
    }

    val canSync = state.snapshot?.hasCommits == true && state.remotes.isNotEmpty()

    state.identity?.let { identity ->
        IdentityDialog(
            state = identity,
            onSave = { name, email, local ->
                viewModel.onEvent(GitEvent.SaveIdentity(name, email, local))
            },
            onDismiss = { viewModel.onEvent(GitEvent.DismissIdentity) },
        )
    }
    state.tokenDialog?.let { token ->
        TokenDialog(
            state = token,
            onSave = { host, user, pass -> viewModel.onEvent(GitEvent.SaveToken(host, user, pass)) },
            onDismiss = { viewModel.onEvent(GitEvent.DismissToken) },
        )
    }
    state.error?.let { details ->
        GitErrorDialog(details = details, onDismiss = { viewModel.onEvent(GitEvent.DismissError) })
    }
    state.discardConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(GitEvent.DismissDiscard) },
            title = { Text(stringResource(R.string.git_discard_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (target.isUntracked) {
                            R.string.git_discard_confirm_delete_message
                        } else {
                            R.string.git_discard_confirm_message
                        },
                        target.path,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onEvent(GitEvent.ConfirmDiscard) }) {
                    Text(
                        stringResource(R.string.git_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(GitEvent.DismissDiscard) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    state.removeConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(GitEvent.DismissRemove) },
            title = { Text(stringResource(R.string.git_remove_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_remove_confirm_message))
                    Spacer(Modifier.height(8.dp))
                    target.paths.take(REMOVE_PREVIEW_MAX).forEach { path ->
                        Text(path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (target.paths.size > REMOVE_PREVIEW_MAX) {
                        Text(
                            stringResource(R.string.git_remove_confirm_more, target.paths.size - REMOVE_PREVIEW_MAX),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.onEvent(GitEvent.ConfirmRemove) }) {
                    Text(
                        stringResource(
                            if (target.willCommit) R.string.git_remove_confirm_delete_commit else R.string.git_remove_confirm_delete,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.onEvent(GitEvent.DismissRemove) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (state.resetSheetOpen) {
        GitResetSheet(
            state = state,
            onEvent = viewModel::onEvent,
            onDismiss = { viewModel.onEvent(GitEvent.DismissReset) },
        )
    }
    state.resetHardConfirm?.let { pending ->
        GitConfirmDialog(
            title = stringResource(R.string.git_reset_hard_confirm_title),
            text = stringResource(R.string.git_reset_hard_confirm_text, pending.label),
            confirmLabel = stringResource(R.string.git_reset_confirm),
            danger = true,
            onConfirm = { viewModel.onEvent(GitEvent.ConfirmHardReset) },
            onDismiss = { viewModel.onEvent(GitEvent.DismissHardResetConfirm) },
        )
    }
    GitMergeDialogs(state = state, onEvent = viewModel::onEvent)
    GitRebaseDialogs(state = state, onEvent = viewModel::onEvent)
    if (state.pushRejected) {
        GitConfirmDialog(
            title = stringResource(R.string.git_push_rejected_title),
            text = stringResource(R.string.git_push_rejected_body),
            confirmLabel = stringResource(R.string.git_push_rejected_pull),
            danger = false,
            onConfirm = { viewModel.onEvent(GitEvent.PullThenRetryPush) },
            onDismiss = { viewModel.onEvent(GitEvent.DismissPushRejected) },
        )
    }
    state.fetchFirstRemote?.let { remote ->
        GitFetchFirstDialog(
            remote = remote,
            onFetch = {
                viewModel.onEvent(GitEvent.DismissFetchFirst)
                viewModel.onEvent(GitEvent.Fetch)
            },
            onDismiss = { viewModel.onEvent(GitEvent.DismissFetchFirst) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.snapshot?.headName ?: stringResource(R.string.git_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // GitHub Manager (runs, APKs, artifacts) lives to the left of Fetch.
                    // The GitHub mark fills its whole 24dp box, so it is drawn smaller to match the other glyphs.
                    IconButton(onClick = { onOpenRoute(Routes.github(projectPath)) }) {
                        Icon(
                            XIcons.GitHub,
                            contentDescription = stringResource(R.string.gh_menu_github),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewModel.onEvent(GitEvent.Fetch) },
                        enabled = canSync && state.networkOp == null,
                    ) {
                        Icon(XIcons.Sync, contentDescription = stringResource(R.string.git_fetch))
                    }
                    IconButton(
                        onClick = { viewModel.onEvent(GitEvent.OpenReset) },
                        enabled = state.snapshot?.hasCommits == true,
                    ) {
                        Icon(XIcons.Restore, contentDescription = stringResource(R.string.git_reset_title))
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_identity)) },
                                leadingIcon = { Icon(XIcons.Person, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    viewModel.onEvent(GitEvent.OpenIdentity)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_history_title)) },
                                leadingIcon = { Icon(XIcons.Restore, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onOpenRoute(Routes.gitHistory(projectPath))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_stash_title)) },
                                leadingIcon = { Icon(XIcons.Archive, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onOpenRoute(Routes.gitStash(projectPath))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_tags_title)) },
                                leadingIcon = { Icon(XIcons.Label, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onOpenRoute(Routes.gitTags(projectPath))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.git_remotes_title)) },
                                leadingIcon = { Icon(XIcons.Cloud, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onOpenRoute(Routes.gitRemotes(projectPath))
                                },
                            )
                            if (state.notARepo) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.git_setup_title)) },
                                leadingIcon = { Icon(XIcons.Settings, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onOpenRoute(Routes.gitOnboarding(projectPath))
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            state.networkOp?.let { op ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = state.progressTask.ifBlank { op.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.notARepo -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.git_not_a_repo),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { onOpenRoute(Routes.gitOnboarding(projectPath)) }) {
                            Text(stringResource(R.string.git_setup_title))
                        }
                    }
                }
                else -> GitContent(
                    projectPath = projectPath,
                    state = state,
                    canSync = canSync,
                    onEvent = viewModel::onEvent,
                    onBranchClick = { onOpenRoute(Routes.gitBranches(projectPath)) },
                )
            }
        }
    }
}

@Composable
private fun GitContent(
    projectPath: String,
    state: GitViewModel.UiState,
    canSync: Boolean,
    onEvent: (GitEvent) -> Unit,
    onBranchClick: () -> Unit,
) {
    val snapshot = state.snapshot ?: return
    val status = state.status
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Breathing room under the last row so it never sits flush on the gesture bar.
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "branch") {
            BranchCard(snapshot, state, onEvent, onClick = onBranchClick)
        }
        item(key = "commit") { CommitCard(
                state, onEvent,
                aiGenerator = AiGate?.let { g ->
                    {
                        g.CommitMessageAi(
                            projectPath = projectPath,
                            // Staged diff is preferred; the generator falls back to the work-tree diff, so any change counts.
                            hasChanges = state.status?.isClean == false,
                            onFill = { msg -> onEvent(GitEvent.PasteCommitText(msg)) },
                            app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
                        )
                    }
                },
            ) }
        item(key = "sync") { SyncRow(canSync, snapshot, onEvent) }
        if (status != null && status.isClean) {
            item(key = "clean") {
                Text(
                    text = stringResource(R.string.git_clean),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
        if (snapshot.mergeInProgress || snapshot.rebaseInProgress) {
            item(key = "op-banner") { GitOperationBanner(state, onEvent) }
        }
        status?.let { st ->
            conflictSection(st.conflicts, state.diffs, state.diffLoading, onEvent)
            changeSection(
                keyPrefix = "changes",
                titleRes = R.string.git_section_changes,
                changes = st.unstaged.filter { it.unstaged != GitWorkingState.UNTRACKED && !it.isConflict() } + st.untracked,
                headerActionLabelRes = R.string.git_stage_all,
                isStagedSection = false,
                state = state,
                onHeaderAction = { onEvent(GitEvent.StageAll) },
                onEvent = onEvent,
            )
            changeSection(
                keyPrefix = "staged",
                titleRes = R.string.git_section_staged,
                changes = st.staged.filterNot { it.isConflict() },
                headerActionLabelRes = R.string.git_unstage_all,
                isStagedSection = true,
                state = state,
                onHeaderAction = { onEvent(GitEvent.UnstageAll) },
                onEvent = onEvent,
            )
        }
    }
}

private fun LazyListScope.changeSection(
    keyPrefix: String,
    titleRes: Int,
    changes: List<GitPathChange>,
    headerActionLabelRes: Int,
    isStagedSection: Boolean,
    state: GitViewModel.UiState,
    onHeaderAction: () -> Unit,
    onEvent: (GitEvent) -> Unit,
) {
    if (changes.isEmpty()) return
    val duplicateNames = state.duplicateChangeNames()
    item(key = "$keyPrefix:header") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onHeaderAction) { Text(stringResource(headerActionLabelRes)) }
        }
    }
    // Index bhi key me shamil — agar kabhi upstream se same repoRelativePath do baar
    // aa jaaye (kisi bhi wajah se), tab bhi LazyColumn crash nahi karega.
    itemsIndexed(changes, key = { index, change -> "$keyPrefix:${change.repoRelativePath}:$index" }) { _, change ->
        ChangeRow(
            change = change,
            duplicateNames = duplicateNames,
            isStagedSection = isStagedSection,
            diff = state.diffs[change.repoRelativePath],
            diffLoading = change.repoRelativePath in state.diffLoading,
            onEvent = onEvent,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChangeRow(
    change: GitPathChange,
    duplicateNames: Set<String>,
    isStagedSection: Boolean,
    diff: GitFileDiffResult?,
    diffLoading: Boolean,
    onEvent: (GitEvent) -> Unit,
) {
    val path = change.repoRelativePath
    val (color, labelRes) = when {
        change.staged == GitStageState.CONFLICT || change.unstaged == GitWorkingState.CONFLICT ->
            MaterialTheme.colorScheme.error to R.string.git_state_conflict
        change.staged == GitStageState.ADDED || change.unstaged == GitWorkingState.UNTRACKED ->
            MaterialTheme.colorScheme.tertiary to R.string.git_state_added
        change.staged == GitStageState.DELETED || change.unstaged == GitWorkingState.DELETED ->
            MaterialTheme.colorScheme.error to R.string.git_state_deleted
        else -> MaterialTheme.colorScheme.primary to R.string.git_state_modified
    }
    var menuOpen by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    val toggleStage = { onEvent(if (isStagedSection) GitEvent.Unstage(path) else GitEvent.Stage(path)) }
    val isUntracked = change.unstaged == GitWorkingState.UNTRACKED

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        showDiff = true
                        onEvent(GitEvent.LoadDiff(path))
                    },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 16.dp, vertical = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(20.dp)
                    .clickable(onClick = toggleStage),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
            }
            ChangePathText(
                path = path,
                duplicateNames = duplicateNames,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            Box {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.git_open_in_editor)) },
                        leadingIcon = { Icon(XIcons.FolderOpen, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEvent(GitEvent.OpenFile(path))
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (isStagedSection) R.string.git_unstage else R.string.git_stage,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(if (isStagedSection) XIcons.Remove else XIcons.Add, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            toggleStage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.git_diff)) },
                        leadingIcon = { Icon(XIcons.Diff, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            showDiff = true
                            onEvent(GitEvent.LoadDiff(path))
                        },
                    )
                    if (!isStagedSection) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.git_discard),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            leadingIcon = {
                                Icon(XIcons.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuOpen = false
                                onEvent(GitEvent.RequestDiscard(path, isUntracked))
                            },
                        )
                    }
                }
            }
        }
        if (showDiff) {
            GitFileDiffDialog(
                name = path.substringAfterLast('/'),
                dir = path.substringBeforeLast('/', ""),
                diff = diff,
                loading = diffLoading,
                onClose = {
                    showDiff = false
                    onEvent(GitEvent.CloseDiff(path))
                },
                onSaved = {
                    onEvent(GitEvent.CloseDiff(path))
                    onEvent(GitEvent.LoadDiff(path))
                },
            )
        }
    }
}

@Composable
private fun BranchCard(
    snapshot: GitRepoSnapshot,
    state: GitViewModel.UiState,
    onEvent: (GitEvent) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    ) {
        Icon(XIcons.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = snapshot.headName ?: stringResource(R.string.git_no_commits),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tracking = snapshot.trackingInfo
            Text(
                text = when {
                    !snapshot.hasCommits -> stringResource(R.string.git_no_commits)
                    tracking == null -> stringResource(R.string.git_no_upstream)
                    else -> stringResource(R.string.git_tracking, tracking.remote, tracking.branch) +
                        "  " + stringResource(R.string.git_ahead_behind, tracking.ahead, tracking.behind)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = { onEvent(GitEvent.OpenRebasePicker) },
            enabled = snapshot.hasCommits && !snapshot.rebaseInProgress && !snapshot.mergeInProgress,
        ) {
            Icon(XIcons.Rebase, contentDescription = stringResource(R.string.git_rebase_action), modifier = Modifier.graphicsLayer { scaleY = -1f })
        }
        IconButton(
            onClick = { onEvent(GitEvent.OpenMerge) },
            enabled = snapshot.hasCommits && !snapshot.rebaseInProgress && !snapshot.mergeInProgress && !state.merging,
        ) {
            Icon(XIcons.Merge, contentDescription = stringResource(R.string.git_merge), modifier = Modifier.graphicsLayer { scaleY = -1f })
        }
        Box {
            IconButton(onClick = { onEvent(GitEvent.OpenBranchMenu) }) {
                Icon(XIcons.KeyboardArrowDown, contentDescription = stringResource(R.string.git_branch_checkout))
            }
            DropdownMenu(
                expanded = state.branchMenuOpen,
                onDismissRequest = { onEvent(GitEvent.DismissBranchMenu) },
            ) {
                state.branchMenuItems.forEach { name ->
                    val current = name == snapshot.headName
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = { onEvent(GitEvent.CheckoutBranch(name)) },
                        enabled = !current,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommitCard(
    state: GitViewModel.UiState,
    onEvent: (GitEvent) -> Unit,
    aiGenerator: (@Composable () -> Unit)? = null,   // M20
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.commitMessage,
            onValueChange = { onEvent(GitEvent.CommitMessageChange(it)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text(stringResource(R.string.git_commit_hint)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = state.amend,
                onClick = { onEvent(GitEvent.ToggleAmend) },
                label = { Text(stringResource(R.string.git_amend)) },
            )
            Spacer(Modifier.weight(1f))
            // Borderless on purpose: an outlined circle next to the Amend chip and the filled
            // Commit button read as a third competing control.
            aiGenerator?.invoke()   // AI icon sits to the left of Paste
            IconButton(
                onClick = {
                    clipboard.getText()?.text?.takeIf { it.isNotEmpty() }
                        ?.let { onEvent(GitEvent.PasteCommitText(it)) }
                },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    XIcons.ContentPaste,
                    contentDescription = stringResource(R.string.git_paste),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onEvent(GitEvent.Commit) },
                enabled = (state.commitMessage.isNotBlank() || state.amend) &&
                    state.snapshot != null && !state.committing,
            ) {
                Text(stringResource(R.string.git_commit))
            }
        }
    }
}

@Composable
private fun SyncRow(
    canSync: Boolean,
    snapshot: GitRepoSnapshot,
    onEvent: (GitEvent) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        SplitSyncButton(
            label = stringResource(R.string.git_push),
            icon = XIcons.CloudUpload,
            enabled = canSync,
            menuContentDescription = stringResource(R.string.git_push_more),
            menuItemLabel = stringResource(R.string.git_force_push_lease),
            onClick = { onEvent(GitEvent.Push) },
            onMenuItemClick = { onEvent(GitEvent.ForcePushWithLease) },
            modifier = Modifier.weight(1f),
        )
        SplitSyncButton(
            label = stringResource(R.string.git_pull),
            icon = XIcons.CloudDownload,
            enabled = canSync && snapshot.trackingInfo != null,
            menuContentDescription = stringResource(R.string.git_pull_more),
            menuItemLabel = stringResource(R.string.git_pull_rebase),
            onClick = { onEvent(GitEvent.Pull) },
            onMenuItemClick = { onEvent(GitEvent.PullRebase) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Push/Pull as a split button: tapping the main area runs [onClick] directly (plain
 * push/pull), the chevron opens a single-item menu whose entry executes [onMenuItemClick]
 * immediately on tap — no further confirm step.
 */
@Composable
private fun SplitSyncButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    menuContentDescription: String,
    menuItemLabel: String,
    onClick: () -> Unit,
    onMenuItemClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                Icon(XIcons.KeyboardArrowDown, contentDescription = menuContentDescription)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(menuItemLabel) },
                    onClick = {
                        menuOpen = false
                        onMenuItemClick()
                    },
                )
            }
        }
    }
}
