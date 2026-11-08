package com.invictus.kodex.feature.tools

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.di.AppContainer
import com.invictus.kodex.github.api.GhRepoItem
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.signing.GeneratedKeystore
import com.invictus.kodex.github.signing.KeystoreSpec
import com.invictus.kodex.github.signing.KeystoreTool
import com.invictus.kodex.github.signing.RepoSigning
import com.invictus.kodex.github.signing.SecretNames
import com.invictus.kodex.github.ui.GhText
import com.invictus.kodex.github.ui.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExtraSecret(val id: Long, val name: String = "", val value: String = "")

enum class ApplyStatus { Pending, Working, Done, Failed }

data class ApplyRow(val repo: String, val status: ApplyStatus, val error: GhText? = null)

/**
 * Signing keystore generator: makes a keystore with every setting exposed, optionally stores it as Actions secrets in
 * the repositories picked under "Apply to" (right after generating), and can save the .p12 file.
 */
class KeystoreToolViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {

    data class UiState(
        val alias: String = "key0",
        val password: String = RepoSigning.newPassword(),
        val keySize: Int = 2048,
        val years: Int = 25,
        val commonName: String = "",
        val orgUnit: String = "",
        val org: String = "",
        val locality: String = "",
        val province: String = "",
        val country: String = "",
        val names: SecretNames = SecretNames(),
        val extras: List<ExtraSecret> = emptyList(),
        val moreOpen: Boolean = false,
        val repos: List<GhRepoItem> = emptyList(),
        val reposLoading: Boolean = true,
        val reposError: GhText? = null,
        val query: String = "",
        val selected: Set<String> = emptySet(),
        val generating: Boolean = false,
        val result: GeneratedKeystore? = null,
        val applying: List<ApplyRow> = emptyList(),
        val applyBusy: Boolean = false,
    ) {
        val busy: Boolean get() = generating || applyBusy
        val visibleRepos: List<GhRepoItem>
            get() = if (query.isBlank()) repos else repos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
    }

    sealed interface Effect {
        data class Message(val text: GhText) : Effect
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var extraId = 0L

    init {
        container.gitHubProfileRepository.cached(GitHubTokenProvider.HOST)?.let { p ->
            _state.update { it.copy(commonName = p.name?.takeIf { n -> n.isNotBlank() } ?: p.login) }
        }
        loadRepos()
    }

    fun edit(block: UiState.() -> UiState) = _state.update { if (it.busy) it else it.block() }

    fun regeneratePassword() = edit { copy(password = RepoSigning.newPassword()) }

    fun addExtra() = edit { copy(extras = extras + ExtraSecret(++extraId), moreOpen = true) }
    fun updateExtra(id: Long, name: String? = null, value: String? = null) = edit {
        copy(extras = extras.map { if (it.id == id) it.copy(name = name ?: it.name, value = value ?: it.value) else it })
    }
    fun removeExtra(id: Long) = edit { copy(extras = extras.filterNot { it.id == id }) }

    // ---- repositories ------------------------------------------------------------------------

    fun loadRepos() {
        _state.update { it.copy(reposLoading = true, reposError = null) }
        viewModelScope.launch {
            try {
                val list = container.gitHubAccountApi().listRepos().filter { it.canPush && !it.archived }
                _state.update { st -> st.copy(repos = list, reposLoading = false, selected = st.selected.filter { s -> list.any { it.fullName == s } }.toSet()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                _state.update { it.copy(reposLoading = false, reposError = describe(e)) }
            }
        }
    }

    fun toggleRepo(fullName: String) = _state.update {
        if (it.busy) it else it.copy(selected = if (fullName in it.selected) it.selected - fullName else it.selected + fullName)
    }

    fun selectAllVisible() = _state.update { if (it.busy) it else it.copy(selected = it.selected + it.visibleRepos.map { r -> r.fullName }) }
    fun clearSelection() = _state.update { if (it.busy) it else it.copy(selected = emptySet()) }

    // ---- generate / apply --------------------------------------------------------------------

    private fun validate(s: UiState): Int? {
        if (s.alias.isBlank()) return R.string.tools_err_alias
        if (s.password.length < 6) return R.string.tools_err_password
        if (s.commonName.isBlank()) return R.string.tools_err_cn
        if (s.country.isNotBlank() && s.country.trim().length != 2) return R.string.tools_err_country
        val used = HashSet<String>()
        for (n in s.names.all()) {
            if (!KeystoreTool.validSecretName(n.trim())) return R.string.tools_err_secret_name
            if (!used.add(n.trim().uppercase())) return R.string.tools_err_secret_dupe
        }
        for (e in s.extras.filterNot { it.name.isBlank() && it.value.isBlank() }) {
            if (!KeystoreTool.validSecretName(e.name.trim()) || e.value.isBlank()) return R.string.tools_err_extra
            if (!used.add(e.name.trim().uppercase())) return R.string.tools_err_secret_dupe
        }
        return null
    }

    /** Generates the keystore; if repositories are picked, stores the secrets in each of them straight away. */
    fun generate() {
        val s = _state.value
        if (s.busy) return
        validate(s)?.let { return message(GhText.Res(it)) }
        _state.update { it.copy(generating = true, result = null, applying = emptyList()) }
        viewModelScope.launch {
            try {
                val result = KeystoreTool.generate(
                    KeystoreSpec(
                        alias = s.alias.trim(), password = s.password, keySize = s.keySize, validityYears = s.years,
                        commonName = s.commonName.trim(), orgUnit = s.orgUnit.trim(), org = s.org.trim(),
                        locality = s.locality.trim(), state = s.province.trim(), country = s.country.trim(),
                    ),
                )
                _state.update { it.copy(generating = false, result = result) }
                if (_state.value.selected.isNotEmpty()) applyTo(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(generating = false) }
                message(GhText.Raw(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Stores the current keystore in the picked repositories again (e.g. after picking more). */
    fun applyToSelected() {
        val r = _state.value.result ?: return
        if (_state.value.busy || _state.value.selected.isEmpty()) return
        viewModelScope.launch { applyTo(r) }
    }

    private suspend fun applyTo(result: GeneratedKeystore) {
        val s = _state.value
        val secrets = LinkedHashMap(result.secrets(SecretNames(s.names.keystore.trim(), s.names.storePassword.trim(), s.names.alias.trim(), s.names.keyPassword.trim())))
        s.extras.filterNot { it.name.isBlank() && it.value.isBlank() }.forEach { secrets[it.name.trim()] = it.value }
        val targets = s.repos.filter { it.fullName in s.selected }
        _state.update { it.copy(applyBusy = true, applying = targets.map { r -> ApplyRow(r.fullName, ApplyStatus.Pending) }) }
        for ((i, r) in targets.withIndex()) {
            setRow(i, ApplyStatus.Working, null)
            try {
                container.gitHubRepository(GhRepoRef(r.owner, r.name)).setActionsSecrets(secrets)
                setRow(i, ApplyStatus.Done, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                setRow(i, ApplyStatus.Failed, describe(e))
            } catch (e: Exception) {
                setRow(i, ApplyStatus.Failed, GhText.Raw(e.message ?: e.javaClass.simpleName))
            }
        }
        _state.update { it.copy(applyBusy = false) }
    }

    private fun setRow(i: Int, status: ApplyStatus, error: GhText?) = _state.update { st ->
        st.copy(applying = st.applying.mapIndexed { idx, row -> if (idx == i) row.copy(status = status, error = error) else row })
    }

    fun save(uri: Uri) {
        val r = _state.value.result ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(r.bytes) } != null }.getOrDefault(false)
            }
            message(GhText.Res(if (ok) R.string.tools_saved else R.string.tools_save_failed))
        }
    }

    private fun message(text: GhText) { _effects.trySend(Effect.Message(text)) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                KeystoreToolViewModel(app, app.container)
            }
        }
    }
}
