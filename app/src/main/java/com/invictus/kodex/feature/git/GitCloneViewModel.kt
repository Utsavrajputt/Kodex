package com.invictus.kodex.feature.git

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.git.GitCloneCancelBus
import com.invictus.kodex.core.git.GitCloneLogLine
import com.invictus.kodex.core.git.GitCloneNotifier
import com.invictus.kodex.core.git.GitCloner
import com.invictus.kodex.core.git.GitCredential
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.normalizeHost
import com.invictus.kodex.core.git.overallFraction
import com.invictus.kodex.core.git.stage
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.isAuthError
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.feature.workspace.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class GitCloneViewModel(
    private val credentialStore: GitCredentialStore,
    private val settingsStore: EditorSettingsStore,
    private val notifier: GitCloneNotifier,
    private val defaultParent: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
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

    private val _uiState = MutableStateFlow(UiState(parentPath = defaultParent.absolutePath))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile private var cancelRequested = false

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onEvent(event: GitCloneEvent) {
        when (event) {
            is GitCloneEvent.UrlChange -> _uiState.update {
                it.copy(
                    url = event.value,
                    folderName = if (it.folderName.isBlank()) deriveFolder(event.value) else it.folderName,
                )
            }
            is GitCloneEvent.ParentChange -> _uiState.update { it.copy(parentPath = event.value) }
            is GitCloneEvent.FolderChange -> _uiState.update { it.copy(folderName = event.value) }
            is GitCloneEvent.BranchChange -> _uiState.update { it.copy(branch = event.value) }
            GitCloneEvent.Start -> startClone()
            GitCloneEvent.Cancel -> requestCancel()
            GitCloneEvent.DismissError -> _uiState.update { it.copy(error = null) }
            GitCloneEvent.Done -> _effects.trySend(Effect.NavigateBack)
            is GitCloneEvent.ConfirmPrivateToken -> confirmPrivateToken(event.username, event.token)
            GitCloneEvent.DismissPrivateRepoDialog -> _uiState.update { it.copy(privateRepoHost = null) }
            GitCloneEvent.ConfigureCredentials -> {
                _uiState.update { it.copy(privateRepoHost = null) }
                _effects.trySend(Effect.OpenGitHubSettings)
            }
        }
    }

    private fun deriveFolder(url: String): String =
        url.trim().removeSuffix("/").substringAfterLast('/').removeSuffix(".git")

    private fun startClone() {
        val s = _uiState.value
        val url = s.url.trim()
        val folder = s.folderName.trim()
        when {
            !url.startsWith("https://") && !url.startsWith("http://") ->
                return message(R.string.clone_invalid_url)
            folder.isEmpty() -> return message(R.string.clone_empty_folder)
        }
        val target = File(s.parentPath.trim(), folder)
        if (target.exists()) return message(R.string.clone_folder_exists, folder)
        // Try with whatever's already saved for this host (often nothing, for a public repo).
        val host = runCatching { normalizeHost(url) }.getOrNull()
        val credential = host?.let { credentialStore.get(it) }
        performClone(url, target, credential?.username ?: DEFAULT_USERNAME, credential?.token.orEmpty())
    }

    private fun confirmPrivateToken(username: String, token: String) {
        val s = _uiState.value
        val folder = s.folderName.trim()
        val target = File(s.parentPath.trim(), folder)
        _uiState.update { it.copy(privateRepoHost = null) }
        performClone(s.url.trim(), target, username.ifBlank { DEFAULT_USERNAME }, token)
    }

    private fun rememberParent(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch(io) { runCatching { settingsStore.setCloneDefaultParent(clean) } }
    }

    /** Safe from any thread: called by the Cancel button and by the notification's Cancel action. */
    private fun requestCancel() {
        if (!_uiState.value.cloning) return
        cancelRequested = true
        _uiState.update { it.copy(cancelling = true) }
    }

    private fun performClone(url: String, target: File, username: String, token: String) {
        viewModelScope.launch(io) {
            val repoName = target.name
            cancelRequested = false
            GitCloneCancelBus.handler = this@GitCloneViewModel::requestCancel
            _uiState.update { it.copy(cloning = true, cancelling = false, completed = false, logLines = emptyList()) }
            notifier.start(repoName)
            try {
                val result = GitCloner.clone(
                    url = url,
                    username = username,
                    token = token,
                    directory = target,
                    branch = _uiState.value.branch.trim().ifBlank { null },
                    isCancelled = { cancelRequested },
                ) { progress ->
                    _uiState.update { it.copy(logLines = progress.lines) }
                    notifier.progress(repoName, progress.stage(), progress.overallFraction())
                }
                when (result) {
                    is GitResult.Ok -> {
                        if (token.isNotBlank()) {
                            credentialStore.put(GitCredential(normalizeHost(url), username, token))
                        }
                        // Only a folder that just worked becomes next time's default.
                        rememberParent(_uiState.value.parentPath)
                        message(R.string.clone_done, target.absolutePath)
                        notifier.finished(repoName, target.absolutePath)
                        // Stay on screen with the full transcript; the person taps Done to leave.
                        _uiState.update { it.copy(completed = true) }
                    }
                    is GitResult.Err -> {
                        // Don't leave a half-downloaded repo behind.
                        if (target.exists()) target.deleteRecursively()
                        val host = runCatching { normalizeHost(url) }.getOrNull()
                        when {
                            cancelRequested -> {
                                _uiState.update { it.copy(logLines = emptyList()) }
                                message(R.string.clone_cancelled)
                            }
                            host != null && result.error.authFailure.isAuthError() ->
                                _uiState.update { it.copy(privateRepoHost = host) }
                            else -> {
                                notifier.failed(repoName, result.error.message)
                                _uiState.update { it.copy(error = result.error) }
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Screen closed mid-clone: JGit was told to stop, so remove what it left.
                if (target.exists()) target.deleteRecursively()
                throw e
            } finally {
                GitCloneCancelBus.handler = null
                notifier.dismiss()
                _uiState.update { it.copy(cloning = false, cancelling = false) }
            }
        }
    }

    override fun onCleared() {
        // Leaving the screen mid-clone stops it (the UI asks for confirmation first).
        if (_uiState.value.cloning) cancelRequested = true
        GitCloneCancelBus.handler = null
        notifier.dismiss()
        super.onCleared()
    }

    private fun message(res: Int, vararg args: Any) {
        _effects.trySend(Effect.Message(UiText(res, args.toList())))
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
                    settingsStore = app.container.editorSettingsStore,
                    notifier = GitCloneNotifier(app),
                    defaultParent = defaultParent,
                )
            }
        }
    }
}
