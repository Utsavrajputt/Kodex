package com.invictus.kodex.feature.git

import androidx.compose.foundation.layout.Column
import com.invictus.kodex.ui.icons.XIcons
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSessionRegistry
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitStashInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GitStashViewModel(
    private val projectPath: String,
    globalIdentityFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val session = GitSessionRegistry.acquire(File(projectPath), globalIdentityFile, io)

    override fun onCleared() {
        GitSessionRegistry.release(File(projectPath))
    }

    data class UiState(
        val loading: Boolean = true,
        val stashes: List<GitStashInfo> = emptyList(),
        val showCreate: Boolean = false,
        val createMessage: String = "",
        val dropTarget: GitStashInfo? = null,
        val error: GitErrorDetails? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init { load() }
    fun messageHandled() { _messages.value = null }

    fun load() {
        viewModelScope.launch(io) {
            _ui.update { it.copy(loading = it.stashes.isEmpty()) }
            when (val r = session.stashList()) {
                is GitResult.Ok -> _ui.update { it.copy(loading = false, stashes = r.value) }
                is GitResult.Err -> _ui.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    fun openCreate() = _ui.update { it.copy(showCreate = true) }
    fun onCreateMessage(v: String) = _ui.update { it.copy(createMessage = v) }
    fun dismissCreate() = _ui.update { it.copy(showCreate = false, createMessage = "") }

    fun create() {
        viewModelScope.launch(io) {
            when (val r = session.stashCreate(_ui.value.createMessage)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(showCreate = false, createMessage = "") }
                    _messages.value = "Stash created"
                    load()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun apply(ref: String, pop: Boolean) {
        viewModelScope.launch(io) {
            when (val r = session.stashApply(ref, pop)) {
                is GitResult.Ok -> { _messages.value = "Stash applied"; load() }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun openDrop(s: GitStashInfo) = _ui.update { it.copy(dropTarget = s) }
    fun dismissDrop() = _ui.update { it.copy(dropTarget = null) }

    fun drop() {
        val target = _ui.value.dropTarget ?: return
        viewModelScope.launch(io) {
            when (val r = session.stashDrop(target.ref)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(dropTarget = null) }
                    _messages.value = "Stash dropped"
                    load()
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    companion object {
        fun factory(projectPath: String) = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                GitStashViewModel(projectPath, app.container.gitGlobalIdentityFile)
            }
        }
    }
}

private val stashTimeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitStashScreen(projectPath: String, onOpenReflog: (() -> Unit)? = null, onBack: () -> Unit) {
    val vm: GitStashViewModel = viewModel(
        key = "stash:$projectPath",
        factory = GitStashViewModel.factory(projectPath),
    )
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); vm.messageHandled() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.git_stash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (onOpenReflog != null) {
                        IconButton(onClick = onOpenReflog) {
                            Icon(XIcons.Schedule, contentDescription = stringResource(R.string.git_reflog_open))
                        }
                    }
                    TextButton(onClick = { vm.openCreate() }) {
                        Text(stringResource(R.string.git_stash_create))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(ui.stashes, key = { it.ref }) { s ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.message,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stashTimeFormat.format(Date(s.timeMs)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            TextButton(onClick = { vm.apply(s.ref, pop = false) }) {
                                Text(stringResource(R.string.git_stash_apply))
                            }
                            TextButton(onClick = { vm.apply(s.ref, pop = true) }) {
                                Text(stringResource(R.string.git_stash_apply_pop))
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.openDrop(s) }) {
                                Text(
                                    stringResource(R.string.git_stash_drop),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            if (!ui.loading && ui.stashes.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.git_stash_empty),
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (ui.showCreate) {
        GitFieldsDialog(
            title = stringResource(R.string.git_stash_create),
            fields = listOf(stringResource(R.string.git_stash_message_hint) to ui.createMessage),
            confirmLabel = stringResource(R.string.git_stash_create),
            onConfirm = { v, _ -> vm.onCreateMessage(v[0]); vm.create() },
            onDismiss = vm::dismissCreate,
        )
    }
    ui.dropTarget?.let { s ->
        GitConfirmDialog(
            title = stringResource(R.string.git_stash_drop),
            text = stringResource(R.string.git_stash_drop_confirm, s.ref),
            confirmLabel = stringResource(R.string.git_stash_drop),
            danger = true,
            onConfirm = vm::drop,
            onDismiss = vm::dismissDrop,
        )
    }
    ui.error?.let { GitErrorDialog(details = it, onDismiss = vm::dismissError) }
}
