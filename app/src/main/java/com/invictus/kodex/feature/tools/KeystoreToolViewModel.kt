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
        /** Chips: keystore on by default; presets and custom are opt-in. */
        val keystoreOn: Boolean = true,
        val presetsOn: Set<String> = emptySet(),
        /** Values of preset secrets, by secret name. */
        val presetValues: Map<String, String> = emptyMap(),
        val customOn: Boolean = false,
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

    // The chips are single-choice: picking one switches to it and turns the others off. Values typed into the
    // others are kept, so switching back does not lose anything.
    fun toggleKeystore() = edit { copy(keystoreOn = true, presetsOn = emptySet(), customOn = false) }
    fun togglePreset(id: String) = edit { copy(keystoreOn = false, presetsOn = setOf(id), customOn = false) }
    fun toggleCustom() = edit {
        copy(
            keystoreOn = false, presetsOn = emptySet(), customOn = true,
            extras = if (extras.isEmpty()) listOf(ExtraSecret(++extraId)) else extras,
        )
    }
    /** Long-press on a chip: adds it to what is chosen (or takes it off again), but never leaves nothing chosen. */
    fun toggleAlso(id: String) = edit {
        val next = when (id) {
            "keystore" -> copy(keystoreOn = !keystoreOn)
            "custom" -> copy(
                customOn = !customOn,
                extras = if (!customOn && extras.isEmpty()) listOf(ExtraSecret(++extraId)) else extras,
            )
            else -> copy(presetsOn = if (id in presetsOn) presetsOn - id else presetsOn + id)
        }
        if (!next.keystoreOn && next.presetsOn.isEmpty() && !next.customOn) this else next
    }

    fun setPresetValue(name: String, value: String) = edit { copy(presetValues = presetValues + (name to value)) }

    /** Fills a preset field from a text file (a service-account or google-services JSON). */
    fun loadFromFile(name: String, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (text == null || text.length > 40_000) message(GhText.Res(R.string.tools_file_read_failed)) else setPresetValue(name, text.trim())
        }
    }

    fun addExtra() = edit { copy(extras = extras + ExtraSecret(++extraId)) }
    fun updateExtra(id: Long, name: String? = null, value: String? = null) = edit {
        copy(extras = extras.map { if (it.id == id) it.copy(name = name ?: it.name, value = value ?: it.value) else it })
    }
    fun removeExtra(id: Long) = edit {
        val left = extras.filterNot { it.id == id }
        // Removing the last custom row leaves nothing chosen, so fall back to the keystore chip.
        if (left.isEmpty()) copy(extras = left, customOn = false, keystoreOn = true) else copy(extras = left)
    }

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

    private fun customRows(s: UiState) = if (s.customOn) s.extras.filterNot { it.name.isBlank() && it.value.isBlank() } else emptyList()
    private fun activePresets(s: UiState) = SECRET_PRESETS.filter { it.id in s.presetsOn }

    private fun validate(s: UiState): Int? {
        val extraCount = activePresets(s).size + customRows(s).size
        if (!s.keystoreOn && extraCount == 0) return R.string.tools_err_nothing
        if (!s.keystoreOn && s.selected.isEmpty()) return R.string.tools_err_pick_repos
        val used = HashSet<String>()
        if (s.keystoreOn) {
            if (s.alias.isBlank()) return R.string.tools_err_alias
            if (s.password.length < 6) return R.string.tools_err_password
            if (s.commonName.isBlank()) return R.string.tools_err_cn
            if (s.country.isNotBlank() && s.country.trim().length != 2) return R.string.tools_err_country
            for (n in s.names.all()) {
                if (!KeystoreTool.validSecretName(n.trim())) return R.string.tools_err_secret_name
                if (!used.add(n.trim().uppercase())) return R.string.tools_err_secret_dupe
            }
        }
        for (p in activePresets(s)) for (f in p.fields) {
            if (s.presetValues[f.name].orEmpty().isBlank()) return R.string.tools_err_preset_empty
            if (!used.add(f.name.uppercase())) return R.string.tools_err_secret_dupe
        }
        for (e in customRows(s)) {
            if (!KeystoreTool.validSecretName(e.name.trim()) || e.value.isBlank()) return R.string.tools_err_extra
            if (!used.add(e.name.trim().uppercase())) return R.string.tools_err_secret_dupe
        }
        return null
    }

    /** Every secret to store: the keystore ones (when generated), the chosen presets and the custom rows. */
    private fun collectSecrets(s: UiState, result: GeneratedKeystore?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (result != null) {
            out.putAll(result.secrets(SecretNames(s.names.keystore.trim(), s.names.storePassword.trim(), s.names.alias.trim(), s.names.keyPassword.trim())))
        }
        for (p in activePresets(s)) for (f in p.fields) out[f.name] = s.presetValues[f.name].orEmpty().trim()
        for (e in customRows(s)) out[e.name.trim()] = e.value
        return out
    }

    /** Main button: generates the keystore when its chip is on, then stores everything in the picked repositories. */
    fun generate() {
        val s = _state.value
        if (s.busy) return
        validate(s)?.let { return message(GhText.Res(it)) }
        _state.update { it.copy(generating = s.keystoreOn, applyBusy = !s.keystoreOn, result = null, applying = emptyList()) }
        viewModelScope.launch {
            try {
                val result = if (s.keystoreOn) {
                    KeystoreTool.generate(
                        KeystoreSpec(
                            alias = s.alias.trim(), password = s.password, keySize = s.keySize, validityYears = s.years,
                            commonName = s.commonName.trim(), orgUnit = s.orgUnit.trim(), org = s.org.trim(),
                            locality = s.locality.trim(), state = s.province.trim(), country = s.country.trim(),
                        ),
                    )
                } else null
                _state.update { it.copy(generating = false, result = result) }
                if (_state.value.selected.isNotEmpty()) applyTo(collectSecrets(s, result))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(generating = false, applyBusy = false) }
                message(GhText.Raw(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Stores the current keystore and values in the picked repositories again (e.g. after picking more). */
    fun applyToSelected() {
        val s = _state.value
        if (s.busy || s.selected.isEmpty()) return
        // The keystore itself is already made; only the other secrets can still be invalid.
        val err = validate(s.copy(keystoreOn = false))
        if (err != null && err != R.string.tools_err_nothing) return message(GhText.Res(err))
        viewModelScope.launch { applyTo(collectSecrets(s, s.result)) }
    }

    private suspend fun applyTo(secrets: Map<String, String>) {
        val s = _state.value
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
