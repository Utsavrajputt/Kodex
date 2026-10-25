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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
        /** Last 4 chars of each saved token, shown as "····abcd" (never the full token). */
        val tokenHints: Map<String, String> = emptyMap(),
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
        val creds = credentialStore.hosts().associateWith { credentialStore.get(it) }
        val entries = creds.map { (host, cred) ->
            CredentialEntry(host = host, username = cred?.username.orEmpty(), linkedRemotes = emptyList())
        }
        val hints = creds.mapNotNull { (host, cred) -> cred?.token?.takeLast(4)?.let { host to it } }.toMap()
        _ui.update { it.copy(tokens = entries, tokenHints = hints) }
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

    fun saveToken(host: String, username: String, token: String) {
        val normalizedHost = (host).trim().lowercase()
        if (normalizedHost.isEmpty() || token.isBlank()) return
        credentialStore.put(
            GitCredential(host = normalizedHost, username = username.ifBlank { "x-access-token" }, token = token),
        )
        profileRepo.clear(normalizedHost)
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
        val divider = Modifier.padding(vertical = 4.dp)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GitHubHero(
                profile = firstProfile,
                hasToken = ui.tokens.isNotEmpty(),
                imageLoader = avatarLoader,
            )

            // One card for all settings: sections share a surface and are separated by dividers.
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(XIcons.Person, stringResource(R.string.settings_github_identity_title), stringResource(R.string.settings_github_identity_desc))
                    OutlinedTextField(
                        value = ui.identityName,
                        onValueChange = viewModel::onIdentityName,
                        label = { Text(stringResource(R.string.git_identity_name)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ui.identityEmail,
                        onValueChange = viewModel::onIdentityEmail,
                        label = { Text(stringResource(R.string.git_identity_email)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

                    HorizontalDivider(divider, color = MaterialTheme.colorScheme.outlineVariant)

                    SectionHeader(XIcons.Folder, stringResource(R.string.settings_github_default_parent_title), stringResource(R.string.settings_github_default_parent_desc))
                    val pickDefaultParent = rememberFolderPickerLauncher(
                        onPicked = { path -> viewModel.setDefaultParent(path) },
                    )
                    OutlinedTextField(
                        value = ui.defaultParent,
                        onValueChange = viewModel::setDefaultParent,
                        placeholder = { Text(stringResource(R.string.settings_github_default_parent_placeholder)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            IconButton(onClick = pickDefaultParent) {
                                Icon(XIcons.Folder, contentDescription = stringResource(R.string.clone_browse_folder))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider(divider, color = MaterialTheme.colorScheme.outlineVariant)

                    SectionHeader(
                        XIcons.Key,
                        stringResource(R.string.settings_github_tokens_title),
                        stringResource(R.string.settings_github_tokens_desc),
                    )
                    // Same inline style as the AI key section: status row, replace field, Save / Remove.
                    ui.tokens.forEach { entry ->
                        key(entry.host) {
                            HostTokenCard(
                                host = entry.host,
                                login = ui.profiles[entry.host]?.login,
                                hint = ui.tokenHints[entry.host],
                                saved = true,
                                onSave = { _, _, token -> viewModel.saveToken(entry.host, entry.username, token) },
                                onRemove = { viewModel.openDeleteToken(entry.host) },
                            )
                        }
                    }
                    val freeHosts = TOKEN_HOSTS.filter { h -> ui.tokens.none { it.host == h } }
                    if (ui.tokens.isEmpty() || freeHosts.isNotEmpty()) {
                        var addingHost by remember { mutableStateOf(ui.tokens.isEmpty()) }
                        if (addingHost || ui.tokens.isEmpty()) {
                            HostTokenCard(
                                host = freeHosts.firstOrNull() ?: "github.com",
                                login = null,
                                hint = null,
                                saved = false,
                                hostOptions = freeHosts,
                                onSave = { host, user, token -> viewModel.saveToken(host, user, token); addingHost = false },
                                onRemove = null,
                            )
                        } else {
                            FilledTonalButton(onClick = { addingHost = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.settings_github_add_host))
                            }
                        }
                    }
                }
            }
        }
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

/** Account summary at the top: avatar, name, handle, email, connected state and all profile stats. */
@Composable
private fun GitHubHero(
    profile: GitHubProfile?,
    hasToken: Boolean,
    imageLoader: ImageLoader,
) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile != null) {
                    val placeholder = ColorPainter(cs.surfaceVariant)
                    AsyncImage(
                        model = profile.avatarUrl,
                        imageLoader = imageLoader,
                        contentDescription = stringResource(R.string.settings_github_avatar_desc, profile.login),
                        placeholder = placeholder,
                        error = placeholder,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(CircleShape),
                    )
                    Column(Modifier.weight(1f)) {
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
                        profile.email?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HeroChip(stringResource(R.string.settings_github_connected))
                } else {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(cs.onPrimaryContainer.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(XIcons.Code, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(28.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_github_hero_title), style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer)
                        Text(
                            stringResource(if (hasToken) R.string.settings_github_hero_token_only else R.string.settings_github_hero_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                    }
                }
            }
            if (profile != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.onPrimaryContainer.copy(alpha = 0.10f)).padding(vertical = 10.dp),
                ) {
                    HeroStat(profile.publicRepos, stringResource(R.string.settings_github_stat_repos), Modifier.weight(1f))
                    HeroStat(profile.followers, stringResource(R.string.settings_github_stat_followers), Modifier.weight(1f))
                    HeroStat(profile.following, stringResource(R.string.settings_github_stat_following), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: Int, label: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.75f), maxLines = 1)
    }
}

@Composable
private fun HeroChip(text: String) {
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
        )
    }
}

/** Compact section title: small icon, title, one-line description, optional trailing [action]. */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    desc: String,
    action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        action?.invoke()
    }
}

private val TOKEN_HOSTS = listOf("github.com", "gitlab.com", "bitbucket.org")

/**
 * One host's token, laid out like the AI key section: status row (saved hint / none), a masked
 * "Replace token" field with an eye toggle, then Save / Remove. With [hostOptions] (new host) the
 * card starts with a host picker, plus a username field for hosts other than github.com.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostTokenCard(
    host: String,
    login: String?,
    hint: String?,
    saved: Boolean,
    onSave: (host: String, username: String, token: String) -> Unit,
    onRemove: (() -> Unit)?,
    hostOptions: List<String> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    var pickedHost by remember { mutableStateOf(host) }
    var hostExpanded by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val shownHost = if (hostOptions.isEmpty()) host else pickedHost

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainerHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hostOptions.size > 1) {
            ExposedDropdownMenuBox(expanded = hostExpanded, onExpandedChange = { hostExpanded = it }) {
                OutlinedTextField(
                    value = pickedHost,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.git_cred_host_hint)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostExpanded) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                DropdownMenu(expanded = hostExpanded, onDismissRequest = { hostExpanded = false }) {
                    hostOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = { pickedHost = option; hostExpanded = false },
                        )
                    }
                }
            }
        } else {
            Text(shownHost, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (saved) XIcons.CheckCircle else XIcons.ErrorCircle,
                contentDescription = null,
                tint = if (saved) cs.primary else cs.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (saved) {
                    stringResource(R.string.settings_github_token_saved, hint.orEmpty()) +
                        (login?.let { " \u00b7 @$it" } ?: "")
                } else {
                    stringResource(R.string.settings_github_no_token_saved, shownHost)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        }
        if (!saved && shownHost != "github.com") {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.git_cred_user_hint)) },
                placeholder = { Text(stringResource(R.string.git_cred_user_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = {
                Text(stringResource(if (saved) R.string.settings_github_replace_token else R.string.settings_github_paste_token))
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        if (reveal) XIcons.VisibilityOff else XIcons.Visibility,
                        contentDescription = stringResource(if (reveal) R.string.settings_github_hide_token else R.string.settings_github_show_token),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(shownHost, username.trim(), draft.trim()); draft = "" },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.settings_github_save_token)) }
            if (onRemove != null) {
                OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.settings_github_remove)) }
            }
        }
    }
}
