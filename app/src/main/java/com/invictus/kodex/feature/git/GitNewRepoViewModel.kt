package com.invictus.kodex.feature.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.ClonePrefill
import com.invictus.kodex.di.AppContainer
import com.invictus.kodex.github.api.GhNewRepo
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.signing.RepoSigning
import com.invictus.kodex.github.ui.GhText
import com.invictus.kodex.github.ui.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NewRepoStep { Creating, Keystore, Secrets }

/**
 * "New repository": creates the repo on GitHub, optionally generates an app-signing keystore and stores it as the
 * repo's Actions secrets (nothing is kept on the phone), then hands the new repo to the Clone screen.
 */
class GitNewRepoViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val name: String = "",
        val description: String = "",
        val isPrivate: Boolean = true,
        val readme: Boolean = true,
        val cloneAfter: Boolean = true,
        val signing: Boolean = true,
        val alias: String = "key0",
        val commonName: String = "",
        val orgUnit: String = "",
        val org: String = "",
        val country: String = "",
        val password: String = RepoSigning.newPassword(),
        val step: NewRepoStep? = null,
        /** The repo exists on GitHub already (a later step failed): the form is frozen and Create resumes. */
        val repoCreated: Boolean = false,
        val signingFailure: GhText? = null,
    ) {
        val busy: Boolean get() = step != null
        val locked: Boolean get() = busy || repoCreated
    }

    sealed interface Effect {
        data class Message(val text: GhText) : Effect
        data object StartClone : Effect
        data class Done(val text: GhText) : Effect
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var created: GhNewRepo? = null

    init {
        // Certificate name defaults to the signed-in GitHub account.
        container.gitHubProfileRepository.cached(GitHubTokenProvider.HOST)?.let { p ->
            _state.update { it.copy(commonName = p.name?.takeIf { n -> n.isNotBlank() } ?: p.login) }
        }
    }

    fun update(block: UiState.() -> UiState) = _state.update { if (it.locked) it else it.block() }

    fun regeneratePassword() = update { copy(password = RepoSigning.newPassword()) }

    fun create() {
        val s = _state.value
        if (s.busy) return
        if (created == null) {
            val err = validate(s)
            if (err != null) return message(GhText.Res(err))
        }
        viewModelScope.launch {
            try {
                val repo = created ?: run {
                    _state.update { it.copy(step = NewRepoStep.Creating) }
                    container.gitHubAccountApi().createRepo(s.name.trim(), s.description.trim(), s.isPrivate, s.readme || s.cloneAfter)
                }.also {
                    created = it
                    _state.update { st -> st.copy(repoCreated = true) }
                }
                if (s.signing && !setupSigning(repo, _state.value)) return@launch
                finish(repo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(step = null) }
                message(describe(e))
            }
        }
    }

    private fun validate(s: UiState): Int? = when {
        !NAME.matches(s.name.trim()) || s.name.trim().all { it == '.' } -> R.string.new_repo_err_name
        s.signing && s.alias.isBlank() -> R.string.new_repo_err_alias
        s.signing && s.commonName.isBlank() -> R.string.new_repo_err_cn
        s.signing && s.country.isNotBlank() && s.country.trim().length != 2 -> R.string.new_repo_err_country
        else -> null
    }

    /** Returns true when the secrets are stored; on failure the dialog (retry / skip) is shown instead. */
    private suspend fun setupSigning(repo: GhNewRepo, s: UiState): Boolean {
        try {
            _state.update { it.copy(step = NewRepoStep.Keystore) }
            val secrets = RepoSigning.secrets(
                RepoSigning.Identity(s.alias.trim(), s.commonName.trim(), s.orgUnit, s.org, s.country),
                s.password,
            )
            _state.update { it.copy(step = NewRepoStep.Secrets) }
            container.gitHubRepository(GhRepoRef(repo.owner, repo.name)).setActionsSecrets(secrets)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubError) {
            _state.update { it.copy(step = null, signingFailure = describe(e)) }
        } catch (e: Exception) {
            _state.update { it.copy(step = null, signingFailure = GhText.Raw(e.message ?: e.javaClass.simpleName)) }
        }
        return false
    }

    fun retrySigning() {
        _state.update { it.copy(signingFailure = null) }
        create()
    }

    /** Continue without signing secrets (the repo exists already). */
    fun skipSigning() {
        _state.update { it.copy(signingFailure = null) }
        created?.let(::finish)
    }

    private fun finish(repo: GhNewRepo) {
        _state.update { it.copy(step = null) }
        if (_state.value.cloneAfter) {
            container.pendingClone.set(ClonePrefill(repo.cloneUrl, repo.name))
            _effects.trySend(Effect.StartClone)
        } else {
            _effects.trySend(Effect.Done(GhText.Res(R.string.new_repo_created, listOf("${repo.owner}/${repo.name}"))))
        }
    }

    private fun message(text: GhText) { _effects.trySend(Effect.Message(text)) }

    companion object {
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        val Factory = viewModelFactory {
            initializer { GitNewRepoViewModel((this[APPLICATION_KEY] as KodexApp).container) }
        }
    }
}
