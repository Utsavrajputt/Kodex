package com.invictus.kodex.feature.settings

import androidx.compose.foundation.layout.Arrangement
import coil.compose.AsyncImage
import coil.ImageLoader
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.KodexApp
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.git.GitCredential
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.core.git.GitSession
import com.invictus.kodex.core.git.normalizeHost
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.github.GitHubProfile
import com.invictus.kodex.core.github.GitHubProfileRepository
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.feature.git.CredentialEntry
import com.invictus.kodex.feature.git.GitConfirmDialog
import com.invictus.kodex.feature.git.GitErrorDialog
import com.invictus.kodex.feature.git.GitHostTokenDialog
import com.invictus.kodex.ui.components.rememberFolderPickerLauncher
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * App-wide GitHub settings: default commit identity (name/email, written to the
 * global gitconfig) and the encrypted per-host access tokens used to clone and
 * sync private repositories, independent of any single open project.
 */
class SettingsGitHubViewModel(
    private val credentialStore: GitCredentialStore,
    private val settingsStore: EditorSettingsStore,
    private val profileRepo: GitHubProfileRepository,
    globalIdentityFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    // setIdentity/getIdentity(local = false) only ever touch the global config file below,
    // never the work tree, so a real repo path isn't needed here.
    private val session = GitSession(globalIdentityFile.parentFile ?: globalIdentityFile, globalIdentityFile, io)

    data class UiState(
        val loading: Boolean = true,
        val identityName: String = "",
        val identityEmail: String = "",
        val savingIdentity: Boolean = false,
        /** Last identity read from / written to the global gitconfig: Save is enabled only when the fields differ. */
        val savedName: String = "",
        val savedEmail: String = "",
        val tokens: List<CredentialEntry> = emptyList(),
        val showTokenEditor: Boolean = false,
        val editHost: String? = null,     // null -> add
        val editorHost: String = "",
        val editorUser: String = "",
        val editorToken: String = "",
        val deleteTarget: String? = null,
        val defaultParent: String = "",
        /** GitHub profile per host (github.com only), from cache first then live refresh. */
        val profiles: Map<String, GitHubProfile> = emptyMap(),
        val error: GitErrorDetails? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        load()
        viewModelScope.launch { settingsStore.cloneDefaultParent.collect { path -> _ui.update { it.copy(defaultParent = path) } } }
    }

    fun setDefaultParent(path: String) {
        viewModelScope.launch(io) { settingsStore.setCloneDefaultParent(path) }
    }

    fun messageHandled() { _messages.value = null }

    fun load() {
        loadTokens()
        viewModelScope.launch(io) {
            when (val r = session.getIdentity(local = false)) {
                is GitResult.Ok -> _ui.update {
                    it.copy(
                        loading = false,
                        identityName = r.value?.name.orEmpty(),
                        identityEmail = r.value?.email.orEmpty(),
                        savedName = r.value?.name.orEmpty(),
                        savedEmail = r.value?.email.orEmpty(),
                    )
                }
                is GitResult.Err -> _ui.update { it.copy(loading = false, error = r.error) }
            }
        }
    }

    private fun loadTokens() {
        val entries = credentialStore.hosts().map { host ->
            val cred = credentialStore.get(host)
            CredentialEntry(host = host, username = cred?.username.orEmpty(), linkedRemotes = emptyList())
        }
        _ui.update { it.copy(tokens = entries) }
        refreshProfiles(entries.map { it.host })
    }

    /** Shows cached profiles immediately, then refetches any that are missing or older than 24h. */
    private fun refreshProfiles(hosts: List<String>) {
        val githubHosts = hosts.filter { profileRepo.supports(it) }
        viewModelScope.launch(io) {
            val cached = githubHosts.mapNotNull { h -> profileRepo.cached(h)?.let { h to it } }.toMap()
            _ui.update { it.copy(profiles = cached) }
            githubHosts.forEach { host ->
                val c = cached[host]
                if (c != null && !profileRepo.isStale(c)) return@forEach
                val token = credentialStore.get(host)?.token ?: return@forEach
                launch {
                    val fresh = profileRepo.fetch(host, token)
                    _ui.update { s ->
                        s.copy(profiles = if (fresh != null) s.profiles + (host to fresh) else s.profiles - host)
                    }
                }
            }
        }
    }

    /** Fills only the blank identity fields from the GitHub profile; a private email stays empty. */
    fun autofillFromGitHub() {
        val profile = _ui.value.profiles.values.firstOrNull() ?: return
        val fillName = _ui.value.identityName.isBlank()
        val email = profile.email?.takeIf { _ui.value.identityEmail.isBlank() }
        _ui.update { s ->
            s.copy(
                identityName = if (fillName) profile.autofillName else s.identityName,
                identityEmail = email ?: s.identityEmail,
            )
        }
        if (!fillName && email == null) {
            _messages.value = "Nothing to fill: email is private on your GitHub profile"
        } else if (email == null) {
            _messages.value = "Name filled. Email is private on GitHub, enter it manually"
        }
    }

    fun onIdentityName(v: String) = _ui.update { it.copy(identityName = v) }
    fun onIdentityEmail(v: String) = _ui.update { it.copy(identityEmail = v) }

    fun saveIdentity() {
        val name = _ui.value.identityName.trim()
        val email = _ui.value.identityEmail.trim()
        if (name.isEmpty() || email.isEmpty()) return
        viewModelScope.launch(io) {
            _ui.update { it.copy(savingIdentity = true) }
            when (val r = session.setIdentity(name, email, local = false)) {
                is GitResult.Ok -> {
                    _ui.update { it.copy(savedName = name, savedEmail = email) }
                    _messages.value = "Identity saved"
                }
                is GitResult.Err -> _ui.update { it.copy(error = r.error) }
            }
            _ui.update { it.copy(savingIdentity = false) }
        }
    }

    fun openAddToken() = _ui.update {
        it.copy(showTokenEditor = true, editHost = null, editorHost = "github.com", editorUser = "", editorToken = "")
    }

    fun openEditToken(entry: CredentialEntry) = _ui.update {
        it.copy(
            showTokenEditor = true, editHost = entry.host, editorHost = entry.host,
            editorUser = entry.username, editorToken = "",
        )
    }

    fun dismissTokenEditor() = _ui.update { it.copy(showTokenEditor = false) }

    fun saveToken(host: String, username: String, token: String) {
        val normalizedHost = (host).trim().lowercase()
        if (normalizedHost.isEmpty() || token.isBlank()) return
        credentialStore.put(
            GitCredential(host = normalizedHost, username = username.ifBlank { "x-access-token" }, token = token),
        )
        profileRepo.clear(normalizedHost)
        _ui.update { it.copy(showTokenEditor = false, editHost = null) }
        _messages.value = "Token saved for $normalizedHost"
        loadTokens()
    }

    fun openDeleteToken(host: String) = _ui.update { it.copy(deleteTarget = host) }
    fun dismissDeleteToken() = _ui.update { it.copy(deleteTarget = null) }

    fun deleteToken() {
        val target = _ui.value.deleteTarget ?: return
        credentialStore.remove(target)
        profileRepo.clear(target)
        _ui.update { it.copy(deleteTarget = null) }
        _messages.value = "Token removed for $target"
        loadTokens()
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KodexApp
                SettingsGitHubViewModel(
                    credentialStore = app.container.gitCredentialStore,
                    settingsStore = app.container.editorSettingsStore,
                    profileRepo = app.container.gitHubProfileRepository,
                    globalIdentityFile = app.container.gitGlobalIdentityFile,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGitHubScreen(
    onBack: () -> Unit,
    viewModel: SettingsGitHubViewModel = viewModel(factory = SettingsGitHubViewModel.Factory),
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val avatarLoader = (LocalContext.current.applicationContext as KodexApp).container.avatarImageLoader

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); viewModel.messageHandled() } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_github_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val firstProfile = ui.tokens.firstNotNullOfOrNull { ui.profiles[it.host] }
        val identityDirty = ui.identityName.trim() != ui.savedName || ui.identityEmail.trim() != ui.savedEmail
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GitHubHero(
                profile = firstProfile,
                hasToken = ui.tokens.isNotEmpty(),
                imageLoader = avatarLoader,
                onAddToken = viewModel::openAddToken,
            )

            SettingsSectionCard(
                icon = XIcons.Person,
                title = stringResource(R.string.settings_github_identity_title),
                desc = stringResource(R.string.settings_github_identity_desc),
            ) {
                OutlinedTextField(
                    value = ui.identityName,
                    onValueChange = viewModel::onIdentityName,
                    label = { Text(stringResource(R.string.git_identity_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.identityEmail,
                    onValueChange = viewModel::onIdentityEmail,
                    label = { Text(stringResource(R.string.git_identity_email)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if ((ui.identityName.isBlank() || ui.identityEmail.isBlank()) && ui.profiles.isNotEmpty()) {
                        TextButton(onClick = viewModel::autofillFromGitHub) {
                            Text(stringResource(R.string.settings_github_autofill))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = viewModel::saveIdentity,
                        enabled = !ui.savingIdentity && identityDirty &&
                            ui.identityName.isNotBlank() && ui.identityEmail.isNotBlank(),
                    ) {
                        Icon(XIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_save))
                    }
                }
            }

            SettingsSectionCard(
                icon = XIcons.Folder,
                title = stringResource(R.string.settings_github_default_parent_title),
                desc = stringResource(R.string.settings_github_default_parent_desc),
            ) {
                val pickDefaultParent = rememberFolderPickerLauncher(
                    onPicked = { path -> viewModel.setDefaultParent(path) },
                )
                OutlinedTextField(
                    value = ui.defaultParent,
                    onValueChange = viewModel::setDefaultParent,
                    placeholder = { Text(stringResource(R.string.settings_github_default_parent_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    trailingIcon = {
                        IconButton(onClick = pickDefaultParent) {
                            Icon(XIcons.Folder, contentDescription = stringResource(R.string.clone_browse_folder))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsSectionCard(
                icon = XIcons.Key,
                title = stringResource(R.string.settings_github_tokens_title),
                desc = stringResource(R.string.settings_github_tokens_desc),
                action = {
                    FilledTonalButton(onClick = viewModel::openAddToken, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.git_cred_add))
                    }
                },
            ) {
                if (ui.tokens.isEmpty()) {
                    Text(
                        stringResource(R.string.git_credentials_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ui.tokens.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TokenRow(
                            entry = entry,
                            profile = ui.profiles[entry.host],
                            imageLoader = avatarLoader,
                            onEdit = { viewModel.openEditToken(entry) },
                            onDelete = { viewModel.openDeleteToken(entry.host) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (ui.showTokenEditor) {
        GitHostTokenDialog(
            title = stringResource(if (ui.editHost == null) R.string.git_cred_add else R.string.git_cred_edit),
            initialHost = ui.editorHost,
            initialUsername = ui.editorUser,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { host, username, token -> viewModel.saveToken(host, username, token) },
            onDismiss = viewModel::dismissTokenEditor,
        )
    }
    ui.deleteTarget?.let { target ->
        GitConfirmDialog(
            title = stringResource(R.string.git_cred_delete),
            text = stringResource(R.string.git_cred_delete_confirm, target),
            confirmLabel = stringResource(R.string.git_cred_delete),
            danger = true,
            onConfirm = viewModel::deleteToken,
            onDismiss = viewModel::dismissDeleteToken,
        )
    }
    ui.error?.let { GitErrorDialog(details = it, onDismiss = viewModel::dismissError) }
}

/** Account summary at the top: the connected GitHub profile, or a prompt to add a token. */
@Composable
private fun GitHubHero(
    profile: GitHubProfile?,
    hasToken: Boolean,
    imageLoader: ImageLoader,
    onAddToken: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (profile != null) {
                val placeholder = ColorPainter(cs.surfaceVariant)
                AsyncImage(
                    model = profile.avatarUrl,
                    imageLoader = imageLoader,
                    contentDescription = stringResource(R.string.settings_github_avatar_desc, profile.login),
                    placeholder = placeholder,
                    error = placeholder,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(CircleShape),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        profile.name?.takeIf { it.isNotBlank() } ?: profile.login,
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${profile.login}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    StatChip(stringResource(R.string.settings_github_connected), highlight = true)
                }
            } else {
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(cs.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(XIcons.Code, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.settings_github_hero_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = cs.onPrimaryContainer,
                    )
                    Text(
                        stringResource(if (hasToken) R.string.settings_github_hero_token_only else R.string.settings_github_hero_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    if (!hasToken) {
                        TextButton(onClick = onAddToken, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(R.string.git_cred_add))
                        }
                    }
                }
            }
        }
    }
}

/** Titled rounded card: icon badge + title/description (+ optional trailing [action]), then [content]. */
@Composable
private fun SettingsSectionCard(
    icon: ImageVector,
    title: String,
    desc: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = cs.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(cs.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = cs.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                action?.invoke()
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenRow(
    entry: CredentialEntry,
    profile: GitHubProfile?,
    imageLoader: ImageLoader,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (profile != null) {
            val placeholder = ColorPainter(cs.surfaceVariant)
            AsyncImage(
                model = profile.avatarUrlSmall,
                imageLoader = imageLoader,
                contentDescription = stringResource(R.string.settings_github_avatar_desc, profile.login),
                placeholder = placeholder,
                error = placeholder,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XIcons.Key, contentDescription = null, tint = cs.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                profile?.let { it.name?.takeIf { n -> n.isNotBlank() } ?: it.login } ?: entry.host,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (profile != null) "@${profile.login} · ${entry.host}" else entry.username,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatChip(stringResource(R.string.settings_github_stat_repos, profile.publicRepos))
                    StatChip(stringResource(R.string.settings_github_stat_followers, profile.followers))
                    StatChip(stringResource(R.string.settings_github_stat_following, profile.following))
                }
            }
        }
        Column {
            IconButton(onClick = onEdit) {
                Icon(XIcons.Edit, contentDescription = stringResource(R.string.git_cred_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(XIcons.Delete, contentDescription = stringResource(R.string.git_cred_delete), tint = cs.error)
            }
        }
    }
}

@Composable
private fun StatChip(text: String, highlight: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) cs.onPrimaryContainer.copy(alpha = 0.14f) else cs.surfaceContainerHighest,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlight) cs.onPrimaryContainer else cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}
