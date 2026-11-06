package com.invictus.kodex.feature.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.signing.RepoSigning
import com.invictus.kodex.github.ui.ClipboardHelper
import com.invictus.kodex.github.ui.GhCard
import com.invictus.kodex.github.ui.ghPalette
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitNewRepoScreen(
    onBack: () -> Unit,
    onStartClone: () -> Unit,
    viewModel: GitNewRepoViewModel = viewModel(factory = GitNewRepoViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val p = ghPalette()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is GitNewRepoViewModel.Effect.Message -> scope.launch { snackbar.showSnackbar(effect.text.resolve(context)) }
                GitNewRepoViewModel.Effect.StartClone -> onStartClone()
                is GitNewRepoViewModel.Effect.Done -> { onBack() }
            }
        }
    }

    state.signingFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.new_repo_signing_failed_title)) },
            text = { Text(stringResource(R.string.new_repo_signing_failed_body, failure.resolve(context))) },
            confirmButton = { TextButton(onClick = viewModel::retrySigning) { Text(stringResource(R.string.gh_retry)) } },
            dismissButton = { TextButton(onClick = viewModel::skipSigning) { Text(stringResource(R.string.new_repo_skip_signing)) } },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = p.onCard,
                    navigationIconContentColor = p.onCard,
                    actionIconContentColor = p.onCard,
                ),
                title = { Text(stringResource(R.string.new_repo_title), maxLines = 1, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.busy) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    ActionPill(
                        text = stringResource(R.string.new_repo_create),
                        icon = XIcons.Add,
                        brush = p.installBrush,
                        content = p.onInstall,
                        enabled = state.name.isNotBlank() && !state.busy,
                    ) { viewModel.create() }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
        ) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent)
                Text(
                    stringResource(
                        when (state.step) {
                            NewRepoStep.Creating -> R.string.new_repo_step_creating
                            NewRepoStep.Keystore -> R.string.new_repo_step_keystore
                            else -> R.string.new_repo_step_secrets
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted,
                )
            }

            GhCard(p.card, p.cardBorder) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardTitle(R.string.new_repo_card_repo)
                    CloneInput(state.name, { viewModel.update { copy(name = it) } }, R.string.new_repo_name_hint, XIcons.Edit, !state.locked)
                    CloneInput(state.description, { viewModel.update { copy(description = it) } }, R.string.new_repo_desc_hint, XIcons.Comment, !state.locked)
                    ToggleRow(R.string.new_repo_private, R.string.new_repo_private_hint, state.isPrivate, !state.locked) { viewModel.update { copy(isPrivate = it) } }
                    ToggleRow(R.string.new_repo_readme, R.string.new_repo_readme_hint, state.readme || state.cloneAfter, !state.locked && !state.cloneAfter) {
                        viewModel.update { copy(readme = it) }
                    }
                    ToggleRow(R.string.new_repo_clone_after, R.string.new_repo_clone_after_hint, state.cloneAfter, !state.locked) { viewModel.update { copy(cloneAfter = it) } }
                }
            }

            GhCard(p.card, p.cardBorder) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CardTitle(R.string.new_repo_card_signing)
                    ToggleRow(R.string.new_repo_signing, R.string.new_repo_signing_hint, state.signing, !state.locked) { viewModel.update { copy(signing = it) } }
                    if (state.signing) {
                        CloneInput(state.alias, { viewModel.update { copy(alias = it) } }, R.string.new_repo_alias_hint, XIcons.Key, !state.locked)
                        CloneInput(state.commonName, { viewModel.update { copy(commonName = it) } }, R.string.new_repo_cn_hint, XIcons.Person, !state.locked)
                        CloneInput(state.org, { viewModel.update { copy(org = it) } }, R.string.new_repo_org_hint, XIcons.Folder, !state.locked)
                        CloneInput(state.country, { viewModel.update { copy(country = it.take(2).uppercase()) } }, R.string.new_repo_country_hint, XIcons.Info, !state.locked)
                        CloneInput(
                            value = state.password, onChange = {}, hint = R.string.new_repo_password_hint, leading = XIcons.Key, enabled = false,
                            trailing = {
                                IconButton(onClick = {
                                    ClipboardHelper.copy(context, "keystore password", state.password)
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.new_repo_password_copied)) }
                                }) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.new_repo_copy_password), tint = p.accent) }
                                IconButton(onClick = viewModel::regeneratePassword, enabled = !state.locked) {
                                    Icon(XIcons.Refresh, contentDescription = stringResource(R.string.new_repo_new_password), tint = p.accent)
                                }
                            },
                        )
                        Text(
                            stringResource(R.string.new_repo_signing_info, RepoSigning.SECRET_NAMES.joinToString(", "), RepoSigning.VALIDITY_YEARS),
                            style = MaterialTheme.typography.bodySmall, color = p.onCardMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: Int, subtitle: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = ghPalette()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = p.onCard, fontWeight = FontWeight.Medium)
            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent),
            modifier = Modifier.size(width = 52.dp, height = 32.dp),
        )
    }
}
