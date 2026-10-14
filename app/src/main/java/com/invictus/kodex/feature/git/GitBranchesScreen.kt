package com.invictus.kodex.feature.git

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.invictus.kodex.core.git.GitTrigger
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.git.GitCredential
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.GitTokenCredentialsProvider
import com.invictus.kodex.core.git.model.GitAuthFailureType
import com.invictus.kodex.core.git.model.GitBranchDetail
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitErrorKind
import com.invictus.kodex.core.git.model.GitRemoteInfo
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class GitBranchesViewModel(
    private val projectPath: String,
    private val credentialStore: GitCredentialStore,
    globalIdentityFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = GitSessionRegistry.acquire(File(projectPath), globalIdentityFile, io)

    override fun onCleared() {
        GitSessionRegistry.release(File(projectPath))
    }

    data class UiState(
        val loading: Boolean = true,
        val branches: List<GitBranchDetail> = emptyList(),
        val remotes: List<GitRemoteInfo> = emptyList(),
        val busy: Boolean = false,
        val progressTask: String = "",
        val showCreate: Boolean = false,
        val createName: String = "",
        val renameTarget: String? = null,
        val renameName: String = "",
        val deleteTarget: String? = null,
        val deleteAlsoRemote: Boolean = false,
        /** Full remote ref, e.g. "origin/feature", waiting for the delete-on-remote confirmation. */
        val remoteDeleteTarget: String? = null,
        /** Remote ref ("origin/dev") waiting for the make-default-on-GitHub confirmation. */
        val makeDefaultTarget: String? = null,
        val forcePushConfirm: Boolean = false,
        /** Remote name when a force push was refused because it has unfetched commits. */
        val fetchPromptRemote: String? = null,
        val tokenHost: String? = null,
        val error: GitErrorDetails? = null,
        /** Background fetch of every remote is running (screen open), so remote branches stay current. */
        val fetching: Boolean = false,
        val query: String = "",
        val filter: BranchFilter = BranchFilter.LOCAL,
        /** Every remote-tracking ref ("origin/main"), for the "create from" picker. */
        val remoteBranches: List<String> = emptyList(),
        /** null = branch off the current HEAD; otherwise the remote chosen in the New branch dialog. */
        val createRemote: String? = null,
        val createBase: String? = null,
    )

    enum class BranchFilter { LOCAL, REMOTE }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        load("screen-open")
        fetchAllQuietly()
    }

    fun onQueryChange(v: String) = _ui.update { it.copy(query = v) }
    fun setFilter(f: BranchFilter) = _ui.update { it.copy(filter = f) }

    /**
     * Fetches every remote with the saved token (no prompts, failures ignored) and reloads, so
     * branches that exist on GitHub but were never fetched here actually show up.
     */
    private fun fetchAllQuietly() {
        viewModelScope.launch(io + GitTrigger("screen-open-fetch")) {
            val remotes = (session.listRemotes() as? GitResult.Ok)?.value.orEmpty()
            if (remotes.isEmpty()) return@launch
            _ui.update { it.copy(fetching = true) }
            var fetchedAny = false
            val failed = mutableListOf<String>()
            for (r in remotes) {
                val host = session.remoteUrl(r.name)?.let { com.invictus.kodex.core.git.normalizeHost(it) }
                val cred = host?.let { credentialStore.get(it) }
                val provider = cred?.let { GitTokenCredentialsProvider(it.username, it.token) }
                // Private remote without a saved token: skip silently (public ones fetch anonymously).
                if (session.fetch(r.name, provider) is GitResult.Ok) fetchedAny = true
                else failed += r.name
            }
            if (failed.isNotEmpty()) {
                _messages.value = "Couldn't fetch ${failed.joinToString()} — remote branches may be out of date"
            }
            _ui.update { it.copy(fetching = false) }
            if (fetchedAny) load("after-fetch")
        }
    }

    fun messageHandled() { _messages.value = null }

    private var divergenceJob: Job? = null

    fun load(trigger: String = "after-op") {
        viewModelScope.launch(io + GitTrigger(trigger)) {
            _ui.update { it.copy(loading = it.branches.isEmpty()) }
            val remotes = (session.listRemotes() as? GitResult.Ok)?.value.orEmpty()
            val remoteRefs = (session.listRemoteBranches() as? GitResult.Ok)?.value.orEmpty()
            when (val r = session.listBranchesDetailed()) {
                is GitResult.Ok -> {
                    // Keep counts we already have for a branch so a reload doesn't flicker them away.
                    val prev = _ui.value.branches.associateBy { it.name }
                    val branches = r.value.map { b ->
                        prev[b.name]?.let { b.copy(ahead = it.ahead, behind = it.behind) } ?: b
                    }
                    _ui.update {
                        it.copy(loading = false, branches = branches, remotes = remotes, remoteBranches = remoteRefs)
                    }
                    fillDivergence(branches)
                }
                is GitResult.Err -> _ui.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    /** Background pass: ahead/behind per tracked branch, current branch first; the list is already on screen. */
    private fun fillDivergence(branches: List<GitBranchDetail>) {
        divergenceJob?.cancel()
        divergenceJob = viewModelScope.launch(io) {
            branches.filter { it.upstream != null && !it.isRemote }
                .sortedByDescending { it.isCurrent }
                .forEach { b ->
                    val counts = (session.branchDivergence(b.name) as? GitResult.Ok)?.value ?: return@forEach
                    _ui.update { st ->
                        st.copy(branches = st.branches.map {
                            if (it.name == b.name) it.copy(ahead = counts.first, behind = counts.second) else it
                        })
                    }
                }
        }
    }

    fun openCreate(name: String = "") =
        _ui.update { it.copy(showCreate = true, createName = name, createRemote = null, createBase = null) }

    /** null = current HEAD; a remote name = branch off one of that remote's branches (main/master first). */
    fun onCreateRemoteChange(remote: String?) = _ui.update { st ->
        val base = remote?.let { r ->
            val mine = st.remoteBranches.filter { it.startsWith("$r/") }
            mine.firstOrNull { it == "$r/master" } ?: mine.firstOrNull { it == "$r/main" } ?: mine.firstOrNull()
        }
        st.copy(createRemote = remote, createBase = base)
    }
    fun onCreateBaseChange(ref: String) = _ui.update { it.copy(createBase = ref) }
    fun onCreateNameChange(v: String) = _ui.update { it.copy(createName = v) }
    fun dismissCreate() = _ui.update { it.copy(showCreate = false) }

    fun create(checkout: Boolean) {
        val name = _ui.value.createName.trim()
        if (name.isEmpty()) return
        val start = _ui.value.takeIf { it.createRemote != null }?.createBase
        if (_ui.value.createRemote != null && start == null) {
            _messages.value = "No branches fetched for '${_ui.value.createRemote}' yet"
            return
        }
        viewModelScope.launch(io) {
            when (val r = session.createBranch(name, checkout, start)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(showCreate = false, createName = "") }
                    _messages.value = "Branch '$name' created"
                    load()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun checkout(b: GitBranchDetail) {
        val name = b.name
        if (_ui.value.busy) return
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            val result = try {
                if (b.isRemote) session.checkoutRemoteBranch(name)
                else session.checkoutBranch(name, create = false)
            } finally {
                _ui.update { it.copy(busy = false, progressTask = "") }
            }
            when (val r = result) {
                is GitResult.Ok -> {
                    _messages.value = "Switched to '${if (b.isRemote) name.substringAfter('/') else name}'"
                    load()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun openRename(target: String) =
        _ui.update { it.copy(renameTarget = target, renameName = target) }
    fun onRenameChange(v: String) = _ui.update { it.copy(renameName = v) }
    fun dismissRename() = _ui.update { it.copy(renameTarget = null) }

    fun rename() {
        val target = _ui.value.renameTarget ?: return
        val name = _ui.value.renameName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch(io) {
            when (val r = session.renameBranch(target, name)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(renameTarget = null) }
                    _messages.value = "Branch renamed to '$name'"
                    load()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun openDelete(name: String) = _ui.update { it.copy(deleteTarget = name, deleteAlsoRemote = false) }
    fun dismissDelete() = _ui.update { it.copy(deleteTarget = null, deleteAlsoRemote = false) }
    fun setDeleteAlsoRemote(v: Boolean) = _ui.update { it.copy(deleteAlsoRemote = v) }

    fun delete() {
        val target = _ui.value.deleteTarget ?: return
        val upstream = _ui.value.branches.firstOrNull { it.name == target && !it.isRemote }?.upstream
        val alsoRemote = _ui.value.deleteAlsoRemote && upstream != null
        viewModelScope.launch(io) {
            // Not-merged branches throw; retry with force once.
            val first = session.deleteBranch(target, force = false)
            val r = if (first is GitResult.Err) session.deleteBranch(target, force = true) else first
            when (r) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(deleteTarget = null, deleteAlsoRemote = false) }
                    _messages.value = "Branch '$target' deleted"
                    load()
                    if (alsoRemote && upstream != null) {
                        deleteRemoteBranch(upstream.substringBefore('/'), upstream.substringAfter('/'))
                    }
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    // ---- make default branch on GitHub ---------------------------------------------

    fun askMakeDefault(b: GitBranchDetail) {
        val ref = if (b.isRemote) b.name else b.upstream
        if (ref == null) {
            _messages.value = "Push '${b.name}' to GitHub first"
            return
        }
        _ui.update { it.copy(makeDefaultTarget = ref) }
    }

    fun dismissMakeDefault() = _ui.update { it.copy(makeDefaultTarget = null) }

    fun confirmMakeDefault() {
        val ref = _ui.value.makeDefaultTarget ?: return
        _ui.update { it.copy(makeDefaultTarget = null) }
        makeDefault(ref.substringBefore('/'), ref.substringAfter('/'))
    }

    private fun makeDefault(remote: String, branch: String) {
        val url = session.remoteUrl(remote)
        val repo = url?.let { com.invictus.kodex.core.github.GitHubRepoApi.parseRepo(it) }
        if (repo == null) {
            _messages.value = "'$remote' isn't a GitHub remote"
            return
        }
        val cred = credentialStore.get("github.com")
        if (cred == null) {
            askToken("github.com") { makeDefault(remote, branch) }
            return
        }
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            try {
                com.invictus.kodex.core.github.GitHubRepoApi.setDefaultBranch(repo.first, repo.second, branch, cred.token)
                _messages.value = "'$branch' is now the default branch of ${repo.first}/${repo.second}"
            } catch (e: Exception) {
                _ui.update {
                    it.copy(
                        error = com.invictus.kodex.core.git.GitErrorFactory.from(e, "GitHub") { t ->
                            t.message ?: "Request failed"
                        },
                    )
                }
            }
            _ui.update { it.copy(busy = false, progressTask = "") }
        }
    }

    // ---- delete on the remote ---------------------------------------------------

    fun openDeleteRemote(ref: String) = _ui.update { it.copy(remoteDeleteTarget = ref) }
    fun dismissDeleteRemote() = _ui.update { it.copy(remoteDeleteTarget = null) }

    fun confirmDeleteRemote() {
        val ref = _ui.value.remoteDeleteTarget ?: return
        _ui.update { it.copy(remoteDeleteTarget = null) }
        deleteRemoteBranch(ref.substringBefore('/'), ref.substringAfter('/'))
    }

    private fun deleteRemoteBranch(remote: String, branch: String) {
        val host = session.remoteUrl(remote)?.let { com.invictus.kodex.core.git.normalizeHost(it) }
        val cred = host?.let { credentialStore.get(it) }
        if (host != null && cred == null) {
            askToken(host) { deleteRemoteBranch(remote, branch) }
            return
        }
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            val provider = cred?.let { GitTokenCredentialsProvider(it.username, it.token) }
            when (val r = session.deleteRemoteBranch(remote, branch, provider)) {
                is GitResult.Ok -> { _messages.value = "Branch '$branch' deleted from $remote"; load() }
                is GitResult.Err -> {
                    if (host != null && r.error.authFailure.isAuthError()) {
                        askToken(host) { deleteRemoteBranch(remote, branch) }
                    } else {
                        _ui.update { it.copy(error = r.error) }
                    }
                }
            }
            _ui.update { it.copy(busy = false, progressTask = "") }
        }
    }

    // ---- force push (lease-checked in GitSession) -----------------------------

    fun askForcePush() = _ui.update { it.copy(forcePushConfirm = true) }
    fun dismissForcePush() = _ui.update { it.copy(forcePushConfirm = false) }

    fun forcePush() {
        _ui.update { it.copy(forcePushConfirm = false) }
        val current = _ui.value.branches.firstOrNull { it.isCurrent } ?: return
        val remote = current.upstream?.substringBefore('/')
            ?: _ui.value.remotes.firstOrNull()?.name
            ?: run { _messages.value = "No remote configured"; return }
        val host = session.remoteUrl(remote)?.let { com.invictus.kodex.core.git.normalizeHost(it) }
        val cred = host?.let { credentialStore.get(it) }
        if (host != null && cred == null) {
            askToken(host) { forcePush() }
            return
        }
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            val provider = cred?.let { GitTokenCredentialsProvider(it.username, it.token) }
            when (val r = session.push(remote, provider, force = true) { p ->
                _ui.update { s -> s.copy(progressTask = p.task) }
            }) {
                is GitResult.Ok -> { _messages.value = "Force pushed to $remote"; load() }
                is GitResult.Err -> {
                    if (host != null && r.error.authFailure.isAuthError()) {
                        askToken(host) { forcePush() }
                    } else if (r.error.kind == GitErrorKind.UNFETCHED_COMMITS) {
                        _ui.update { it.copy(fetchPromptRemote = remote) }
                    } else {
                        _ui.update { it.copy(error = r.error) }
                    }
                }
            }
            _ui.update { it.copy(busy = false, progressTask = "") }
        }
    }

    // ---- pull (current branch) ---------------------------------------------------

    fun pull(rebase: Boolean) {
        val current = _ui.value.branches.firstOrNull { it.isCurrent } ?: return
        val remote = current.upstream?.substringBefore('/')
            ?: _ui.value.remotes.firstOrNull { it.name == "origin" }?.name
            ?: _ui.value.remotes.firstOrNull()?.name
            ?: run { _messages.value = "No remote configured"; return }
        val branch = current.upstream?.substringAfter('/') ?: current.name
        val host = session.remoteUrl(remote)?.let { com.invictus.kodex.core.git.normalizeHost(it) }
        val cred = host?.let { credentialStore.get(it) }
        if (host != null && cred == null) {
            askToken(host) { pull(rebase) }
            return
        }
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            val provider = cred?.let { GitTokenCredentialsProvider(it.username, it.token) }
            when (val r = session.pull(remote, branch, provider, rebase) { p ->
                _ui.update { s -> s.copy(progressTask = p.task) }
            }) {
                is GitResult.Ok -> { _messages.value = "Pulled $remote/$branch"; load() }
                is GitResult.Err -> {
                    if (host != null && r.error.authFailure.isAuthError()) {
                        askToken(host) { pull(rebase) }
                    } else {
                        _ui.update { it.copy(error = r.error) }
                    }
                }
            }
            _ui.update { it.copy(busy = false, progressTask = "") }
        }
    }

    fun dismissFetchPrompt() = _ui.update { it.copy(fetchPromptRemote = null) }

    /** "Fetch" in the force-push dialog: bring in what's new on the remote, then let the user decide. */
    fun fetchFromPrompt() {
        val remote = _ui.value.fetchPromptRemote ?: return
        _ui.update { it.copy(fetchPromptRemote = null) }
        val host = session.remoteUrl(remote)?.let { com.invictus.kodex.core.git.normalizeHost(it) }
        val cred = host?.let { credentialStore.get(it) }
        if (host != null && cred == null) {
            askToken(host) { _ui.update { it.copy(fetchPromptRemote = remote) }; fetchFromPrompt() }
            return
        }
        viewModelScope.launch(io) {
            _ui.update { it.copy(busy = true, progressTask = "") }
            val provider = cred?.let { GitTokenCredentialsProvider(it.username, it.token) }
            when (val r = session.fetch(remote, provider) { p -> _ui.update { s -> s.copy(progressTask = p.task) } }) {
                is GitResult.Ok -> {
                    _messages.value = "Fetched $remote — check ahead/behind, then force push again"
                    load()
                }
                is GitResult.Err -> {
                    if (host != null && r.error.authFailure.isAuthError()) {
                        askToken(host) { _ui.update { it.copy(fetchPromptRemote = remote) }; fetchFromPrompt() }
                    } else {
                        _ui.update { it.copy(error = r.error) }
                    }
                }
            }
            _ui.update { it.copy(busy = false, progressTask = "") }
        }
    }

    /** What to run again once the user has entered a token (each network action sets its own). */
    private var afterToken: (() -> Unit)? = null

    private fun askToken(host: String, resume: () -> Unit) {
        afterToken = resume
        _ui.update { it.copy(tokenHost = host) }
    }

    fun saveToken(host: String, username: String, token: String) {
        credentialStore.put(GitCredential(host, username.ifBlank { DEFAULT_USER }, token))
        _ui.update { it.copy(tokenHost = null) }
        val resume = afterToken
        afterToken = null
        resume?.invoke()
    }

    fun dismissToken() {
        afterToken = null
        _ui.update { it.copy(tokenHost = null) }
    }
    fun dismissError() = _ui.update { it.copy(error = null) }

    private fun GitAuthFailureType.isAuthError(): Boolean =
        this == GitAuthFailureType.AUTH_REQUIRED ||
            this == GitAuthFailureType.INVALID_CREDENTIALS ||
            this == GitAuthFailureType.EXPIRED_TOKEN ||
            this == GitAuthFailureType.PERMISSION_DENIED

    companion object {
        private const val DEFAULT_USER = "x-access-token"
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                GitBranchesViewModel(
                    projectPath = projectPath,
                    credentialStore = app.container.gitCredentialStore,
                    globalIdentityFile = app.container.gitGlobalIdentityFile,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitBranchesScreen(projectPath: String, onBack: () -> Unit) {
    val vm: GitBranchesViewModel = viewModel(
        key = "branches:$projectPath",
        factory = GitBranchesViewModel.factory(projectPath),
    )
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); vm.messageHandled() } }
    }

    val visible = remember(ui.branches, ui.filter) {
        ui.branches
            .filter { b ->
                when (ui.filter) {
                    GitBranchesViewModel.BranchFilter.LOCAL -> !b.isRemote
                    GitBranchesViewModel.BranchFilter.REMOTE -> b.isRemote
                }
            }
            .sortedWith(compareBy({ it.isRemote }, { !it.isCurrent }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_branches_title)) },
                // Plain arrow: no container / highlight behind it.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    Button(
                        onClick = { vm.openCreate() },
                        contentPadding = PaddingValues(start = 10.dp, end = 14.dp),
                        modifier = Modifier.padding(end = 12.dp).height(34.dp),
                    ) {
                        Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.git_branch_create))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ui.busy && ui.progressTask.isNotBlank()) {
                    Text(
                        ui.progressTask,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val localCount = ui.branches.count { !it.isRemote }
                val remoteCount = ui.branches.count { it.isRemote }
                listOf(
                    Triple(GitBranchesViewModel.BranchFilter.LOCAL, R.string.git_branch_filter_local, localCount),
                    Triple(GitBranchesViewModel.BranchFilter.REMOTE, R.string.git_branch_filter_remote, remoteCount),
                ).forEach { (f, label, count) ->
                    FilterChip(
                        selected = ui.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(stringResource(label) + " · " + count) },
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { (if (it.isRemote) "r:" else "l:") + it.name }) { b ->
                    BranchCard(
                        b = b,
                        onForcePush = vm::askForcePush,
                        onCheckout = { vm.checkout(b) },
                        onRename = { vm.openRename(b.name) },
                        onDelete = { vm.openDelete(b.name) },
                        onDeleteRemote = { vm.openDeleteRemote(b.name) },
                        onMakeDefault = { vm.askMakeDefault(b) },
                    )
                }
            }
        }
    }

    if (ui.showCreate) {
        BranchCreateDialog(
            ui = ui,
            onRemoteChange = vm::onCreateRemoteChange,
            onBaseChange = vm::onCreateBaseChange,
            onConfirm = { name, checkout ->
                vm.onCreateNameChange(name)
                vm.create(checkout)
            },
            onDismiss = vm::dismissCreate,
        )
    }
    ui.renameTarget?.let {
        GitFieldsDialog(
            title = stringResource(R.string.git_branch_rename),
            fields = listOf(stringResource(R.string.git_branch_rename_hint) to ui.renameName),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { values, _ ->
                vm.onRenameChange(values[0])
                vm.rename()
            },
            onDismiss = vm::dismissRename,
        )
    }
    ui.deleteTarget?.let { target ->
        val upstream = ui.branches.firstOrNull { it.name == target && !it.isRemote }?.upstream
        if (upstream == null) {
            GitConfirmDialog(
                title = stringResource(R.string.git_branch_delete),
                text = stringResource(R.string.git_branch_delete_confirm, target),
                confirmLabel = stringResource(R.string.git_branch_delete),
                danger = true,
                onConfirm = vm::delete,
                onDismiss = vm::dismissDelete,
            )
        } else {
            AlertDialog(
                onDismissRequest = vm::dismissDelete,
                title = { Text(stringResource(R.string.git_branch_delete)) },
                text = {
                    Column {
                        Text(stringResource(R.string.git_branch_delete_confirm, target))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { vm.setDeleteAlsoRemote(!ui.deleteAlsoRemote) },
                        ) {
                            Checkbox(checked = ui.deleteAlsoRemote, onCheckedChange = vm::setDeleteAlsoRemote)
                            Text(stringResource(R.string.git_branch_delete_also_remote, upstream))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::delete) {
                        Text(stringResource(R.string.git_branch_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissDelete) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }
    }
    ui.makeDefaultTarget?.let { ref ->
        GitConfirmDialog(
            title = stringResource(R.string.git_branch_make_default),
            text = stringResource(R.string.git_branch_make_default_confirm, ref.substringAfter('/')),
            confirmLabel = stringResource(R.string.git_branch_make_default_action),
            danger = false,
            onConfirm = vm::confirmMakeDefault,
            onDismiss = vm::dismissMakeDefault,
        )
    }
    ui.remoteDeleteTarget?.let { ref ->
        GitConfirmDialog(
            title = stringResource(R.string.git_branch_delete_remote),
            text = stringResource(R.string.git_branch_delete_remote_confirm, ref),
            confirmLabel = stringResource(R.string.git_branch_delete),
            danger = true,
            onConfirm = vm::confirmDeleteRemote,
            onDismiss = vm::dismissDeleteRemote,
        )
    }
    if (ui.forcePushConfirm) {
        GitConfirmDialog(
            title = stringResource(R.string.git_force_push),
            text = stringResource(R.string.git_force_push_confirm_text),
            confirmLabel = stringResource(R.string.git_force_push),
            danger = true,
            onConfirm = vm::forcePush,
            onDismiss = vm::dismissForcePush,
        )
    }
    ui.fetchPromptRemote?.let { remote ->
        GitFetchFirstDialog(remote = remote, onFetch = vm::fetchFromPrompt, onDismiss = vm::dismissFetchPrompt)
    }
    ui.tokenHost?.let { host ->
        GitFieldsDialog(
            title = "Token: $host",
            fields = listOf("Username" to "x-access-token", "Token" to ""),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { v, _ -> vm.saveToken(host, v[0], v[1]) },
            onDismiss = vm::dismissToken,
        )
    }
    ui.error?.let { GitErrorDialog(details = it, onDismiss = vm::dismissError) }
}


@Composable
private fun BranchCard(
    b: GitBranchDetail,
    onForcePush: () -> Unit,
    onCheckout: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDeleteRemote: () -> Unit,
    onMakeDefault: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(
                    if (b.isRemote) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (b.isRemote) XIcons.Cloud else XIcons.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (b.isRemote) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    b.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (b.isRemote) {
                    Text(
                        stringResource(R.string.git_branch_remote_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                } else {
                    // Same left edge as the branch name (no pill padding pushing it in).
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            b.upstream ?: stringResource(R.string.git_upstream_none),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (b.upstream != null) FontFamily.Monospace else null,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (b.upstream != null && b.ahead != null && b.behind != null) {
                            Text(
                                "  ↑${b.ahead} ↓${b.behind}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = if (b.isCurrent) onForcePush else onCheckout,
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    stringResource(if (b.isCurrent) R.string.git_force_push else R.string.git_branch_checkout),
                    maxLines = 1,
                )
            }
            if (b.isRemote) {
                var remoteMenuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { remoteMenuOpen = true }) {
                        Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.git_branch_more))
                    }
                    DropdownMenu(expanded = remoteMenuOpen, onDismissRequest = { remoteMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.git_branch_make_default)) },
                            onClick = { remoteMenuOpen = false; onMakeDefault() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.git_branch_delete_remote),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { remoteMenuOpen = false; onDeleteRemote() },
                        )
                    }
                }
            } else {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(XIcons.MoreVert, contentDescription = stringResource(R.string.git_branch_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.git_branch_make_default)) },
                            onClick = { menuOpen = false; onMakeDefault() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.git_branch_rename)) },
                            onClick = { menuOpen = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.git_branch_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/** New branch: name + where it starts from (current HEAD, or a branch of a chosen remote). */
@Composable
private fun BranchCreateDialog(
    ui: GitBranchesViewModel.UiState,
    onRemoteChange: (String?) -> Unit,
    onBaseChange: (String) -> Unit,
    onConfirm: (name: String, checkout: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(ui.createName) }
    var checkout by remember { mutableStateOf(true) }
    var baseMenu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val remote = ui.createRemote
    val remoteBranches = remember(remote, ui.remoteBranches) {
        if (remote == null) emptyList() else ui.remoteBranches.filter { it.startsWith("$remote/") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_branch_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.git_branch_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    stringResource(R.string.git_branch_create_from),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = remote == null,
                        onClick = { onRemoteChange(null) },
                        label = { Text(stringResource(R.string.git_branch_from_head)) },
                    )
                    ui.remotes.forEach { r ->
                        FilterChip(
                            selected = remote == r.name,
                            onClick = { onRemoteChange(r.name) },
                            label = { Text(r.name) },
                        )
                    }
                }
                if (remote != null) {
                    Box {
                        OutlinedButton(
                            onClick = { baseMenu = true },
                            enabled = remoteBranches.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                ui.createBase ?: stringResource(R.string.git_branch_from_none),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(XIcons.KeyboardArrowDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = baseMenu, onDismissRequest = { baseMenu = false }) {
                            remoteBranches.forEach { ref ->
                                DropdownMenuItem(
                                    text = { Text(ref) },
                                    onClick = { baseMenu = false; onBaseChange(ref) },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checkout, onCheckedChange = { checkout = it })
                    Text(stringResource(R.string.git_branch_checkout))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, checkout) },
                enabled = name.isNotBlank() && (remote == null || ui.createBase != null),
            ) { Text(stringResource(R.string.git_branch_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
