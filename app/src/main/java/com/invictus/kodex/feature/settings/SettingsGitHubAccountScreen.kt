package com.invictus.kodex.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.feature.git.GitConfirmDialog
import com.invictus.kodex.feature.git.GitErrorDialog
import com.invictus.kodex.ui.icons.XIcons

private const val HOST = "github.com"

/** Opens GitHub's new-token page with the scopes Kodex needs (clone/push, workflow files, create repos, secrets) already ticked. */
private const val NEW_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow,read:user&description=Kodex"

/**
 * Name, email and GitHub access token in one place: heading, the three fields, sign-in status, a shortcut to create
 * a token, the "include in settings export" switch, and Remove / Save at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsGitHubAccountScreen(
    onBack: () -> Unit,
    viewModel: SettingsGitHubViewModel = viewModel(key = "gh_account", factory = SettingsGitHubViewModel.Factory),
) {
    val ui by viewModel.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme

    var draft by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg -> msg?.let { snackbar.showSnackbar(it); viewModel.messageHandled() } }
    }

    val saved = ui.tokens.any { it.host == HOST }
    val login = ui.profiles[HOST]?.login
    val hint = ui.tokenHints[HOST]
    val identityComplete = ui.identityName.isNotBlank() && ui.identityEmail.isNotBlank()
    val identityDirty = ui.identityName.trim() != ui.savedName || ui.identityEmail.trim() != ui.savedEmail
    val canSave = draft.isNotBlank() || (identityDirty && identityComplete)
    val fieldShape = RoundedCornerShape(24.dp)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.gh_acct_heading),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.gh_acct_subtitle),
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )

            OutlinedTextField(
                value = ui.identityName,
                onValueChange = viewModel::onIdentityName,
                label = { Text(stringResource(R.string.git_identity_name)) },
                leadingIcon = { Icon(XIcons.Person, contentDescription = null) },
                singleLine = true,
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.identityEmail,
                onValueChange = viewModel::onIdentityEmail,
                label = { Text(stringResource(R.string.git_identity_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = true),
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )
            if ((ui.identityName.isBlank() || ui.identityEmail.isBlank()) && ui.profiles.isNotEmpty()) {
                TextButton(onClick = viewModel::autofillFromGitHub, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.settings_github_autofill))
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.gh_acct_pat_label)) },
                placeholder = { if (saved) Text("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" + hint.orEmpty()) },
                leadingIcon = { Icon(XIcons.Key, contentDescription = null) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) XIcons.VisibilityOff else XIcons.Visibility,
                                contentDescription = stringResource(if (reveal) R.string.settings_github_hide_token else R.string.settings_github_show_token),
                            )
                        }
                        if (draft.isNotEmpty()) {
                            IconButton(onClick = { draft = "" }) { Icon(XIcons.Close, contentDescription = stringResource(R.string.gh_acct_clear)) }
                        }
                    }
                },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                shape = fieldShape,
                modifier = Modifier.fillMaxWidth(),
            )

            // Status: signed in (green) / token saved / nothing yet.
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (saved) XIcons.CheckCircle else XIcons.ErrorCircle,
                    contentDescription = null,
                    tint = if (saved) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    when {
                        saved && login != null -> stringResource(R.string.gh_acct_signed_in, login)
                        saved -> stringResource(R.string.settings_github_token_saved, hint.orEmpty())
                        else -> stringResource(R.string.settings_github_no_token_saved, HOST)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (saved) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(cs.surfaceContainer)
                    .clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NEW_TOKEN_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(XIcons.GitHub, contentDescription = null, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gh_acct_create_token), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.gh_acct_create_token_desc), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Icon(XIcons.OpenInNew, contentDescription = null, tint = cs.primary)
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(XIcons.Upload, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.gh_acct_export), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.gh_acct_export_desc), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                Switch(checked = ui.includeInExport, onCheckedChange = viewModel::setIncludeInExport)
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { viewModel.openDeleteToken(HOST) },
                    enabled = saved,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(XIcons.Delete, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_github_remove))
                }
                Button(
                    onClick = { viewModel.saveAccount(draft); draft = "" },
                    enabled = canSave && !ui.savingIdentity,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Icon(XIcons.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_save))
                }
            }
            Spacer(Modifier.height(24.dp))
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
