package com.invictus.kodex.feature.git

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.GitCloneLogLine
import com.invictus.kodex.core.git.GitCloneManager
import com.invictus.kodex.core.git.GitCloneOutcome
import com.invictus.kodex.core.git.GitCloneRequest
import com.invictus.kodex.core.git.GitCloneRun
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.core.git.normalizeHost
import com.invictus.kodex.feature.workspace.UiText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Form state + glue only. The clone itself lives in [GitCloneManager], so leaving this screen
 * (or minimizing the app) never interrupts it; reopening the screen picks the live run back up.
 */
class GitCloneViewModel(
    private val credentialStore: GitCredentialStore,
    private val manager: GitCloneManager,
    private val defaultParent: File,
) : ViewModel() {

    data class UiState(
        val url: String = "",
        val parentPath: String = "",
        val folderName: String = "",
        val branch: String = "",
        val cloning: Boolean = false,
        /** Cancel was requested; JGit is still unwinding and the partial folder is being removed. */
        val cancelling: Boolean = false,
        val logLines: List<GitCloneLogLine> = emptyList(),
        /** True once a clone has finished successfully; the person confirms with Done. */
        val completed: Boolean = false,
        val error: GitErrorDetails? = null,
        /** Host that just rejected an unauthenticated clone — drives the private-repo dialog. */
        val privateRepoHost: String? = null,
    )

    sealed interface Effect {
        data class Message(val text: UiText) : Effect
        data object NavigateBack : Effect
        data object OpenGitHubSettings : Effect
    }

    private data class Form(
        val url: String = "",
        val parentPath: String = "",
        val folderName: String = "",
        val branch: String = "",
    )

    // A run still alive (or finished while the screen was closed) repopulates the form from its request.
    private val form = MutableStateFlow(
        manager.run.value.request
            ?.let { Form(it.url, it.parent, it.folder, it.branch) }
            ?: Form(parentPath = defaultParent.absolutePath),
    )

    private fun build(f: Form, run: GitCloneRun, outcome: GitCloneOutcome?) = UiState(
        url = f.url,
        parentPath = f.parentPath,
        folderName = f.folderName,
        branch = f.branch,
        cloning = run.cloning,
        cancelling = run.cancelling,
        logLines = run.logLines,
        completed = run.completed,
        error = (outcome as? GitCloneOutcome.Failed)?.error,
        privateRepoHost = (outcome as? GitCloneOutcome.NeedsToken)?.host,
    )

    val uiState: StateFlow<UiState> = combine(form, manager.run, manager.outcome) { f, run, outcome -> build(f, run, outcome) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(form.value, manager.run.value, manager.outcome.value))

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch { manager.cancelled.collect { message(R.string.clone_cancelled) } }
    }

    fun onEvent(event: GitCloneEvent) {
        when (event) {
            is GitCloneEvent.UrlChange -> form.update {
                it.copy(
                    url = event.value,
                    folderName = if (it.folderName.isBlank()) deriveFolder(event.value) else it.folderName,
                )
            }
            is GitCloneEvent.ParentChange -> form.update { it.copy(parentPath = event.value) }
            is GitCloneEvent.FolderChange -> form.update { it.copy(folderName = event.value) }
            is GitCloneEvent.BranchChange -> form.update { it.copy(branch = event.value) }
            GitCloneEvent.Start -> startClone()
            GitCloneEvent.Cancel -> manager.cancel()
            GitCloneEvent.DismissError -> manager.consumeOutcome()
            GitCloneEvent.Done -> {
                manager.reset()
                _effects.trySend(Effect.NavigateBack)
            }
            is GitCloneEvent.ConfirmPrivateToken -> confirmPrivateToken(event.username, event.token)
            GitCloneEvent.DismissPrivateRepoDialog -> manager.consumeOutcome()
            GitCloneEvent.ConfigureCredentials -> {
                manager.consumeOutcome()
                _effects.trySend(Effect.OpenGitHubSettings)
            }
        }
    }

    private fun deriveFolder(url: String): String =
        url.trim().removeSuffix("/").substringAfterLast('/').removeSuffix(".git")

    private fun startClone() {
        val f = form.value
        val url = f.url.trim()
        val folder = f.folderName.trim()
        when {
            !url.startsWith("https://") && !url.startsWith("http://") ->
                return message(R.string.clone_invalid_url)
            folder.isEmpty() -> return message(R.string.clone_empty_folder)
        }
        val target = File(f.parentPath.trim(), folder)
        if (target.exists()) return message(R.string.clone_folder_exists, folder)
        // Try with whatever's already saved for this host (often nothing, for a public repo).
        val host = runCatching { normalizeHost(url) }.getOrNull()
        val credential = host?.let { credentialStore.get(it) }
        begin(f, target, credential?.username ?: DEFAULT_USERNAME, credential?.token.orEmpty())
    }

    private fun confirmPrivateToken(username: String, token: String) {
        val f = form.value
        manager.consumeOutcome()
        begin(f, File(f.parentPath.trim(), f.folderName.trim()), username.ifBlank { DEFAULT_USERNAME }, token)
    }

    private fun begin(f: Form, target: File, username: String, token: String) {
        manager.start(
            request = GitCloneRequest(f.url.trim(), f.parentPath.trim(), f.folderName.trim(), f.branch.trim()),
            target = target,
            username = username,
            token = token,
        )
    }

    private fun message(res: Int, vararg args: Any) {
        _effects.trySend(Effect.Message(UiText(res, args.toList())))
    }

    override fun onCleared() {
        // Leaving a finished/stopped screen forgets it; a clone still running keeps going untouched.
        manager.reset()
        super.onCleared()
    }

    companion object {
        private const val DEFAULT_USERNAME = "x-access-token"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                val savedParent = runBlocking { app.container.editorSettingsStore.cloneDefaultParent.first() }
                val defaultParent = savedParent.takeIf { it.isNotBlank() }?.let(::File)
                    ?: Environment.getExternalStorageDirectory()
                GitCloneViewModel(
                    credentialStore = app.container.gitCredentialStore,
                    manager = app.container.gitCloneManager,
                    defaultParent = defaultParent,
                )
            }
        }
    }
}
