package com.invictus.kodex.feature.git

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.model.GitAuthFailureType
import com.invictus.kodex.core.git.model.GitErrorDetails

@Composable
fun IdentityDialog(
    state: GitViewModel.IdentityDialogState,
    onSave: (name: String, email: String, isLocal: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(state) { mutableStateOf(state.name) }
    var email by remember(state) { mutableStateOf(state.email) }
    var isLocal by remember(state) { mutableStateOf(state.isLocal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_identity)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.git_identity_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.git_identity_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.git_identity_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = true),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isLocal,
                        onClick = { isLocal = true },
                        label = { Text(stringResource(R.string.git_identity_scope_repo)) },
                    )
                    FilterChip(
                        selected = !isLocal,
                        onClick = { isLocal = false },
                        label = { Text(stringResource(R.string.git_identity_scope_global)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), email.trim(), isLocal) },
                enabled = name.isNotBlank() && email.isNotBlank(),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun TokenDialog(
    state: GitViewModel.TokenDialogState,
    onSave: (host: String, username: String, token: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember(state) { mutableStateOf(state.username) }
    var token by remember(state) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.git_token_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.git_token_body, state.host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.git_username_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.git_token_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(state.host, username.trim(), token) },
                enabled = token.isNotBlank(),
            ) {
                Text(stringResource(R.string.git_token_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
fun PrivateRepoDialog(
    host: String,
    onConfirm: (username: String, token: String) -> Unit,
    onCancel: () -> Unit,
    onConfigure: () -> Unit,
) {
    var username by remember(host) { mutableStateOf("") }
    var token by remember(host) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.clone_private_repo_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.clone_private_repo_body, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.git_username_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.git_token_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(username.trim(), token) },
                enabled = token.isNotBlank(),
            ) {
                Text(stringResource(R.string.git_token_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onConfigure) {
                    Text(stringResource(R.string.clone_private_repo_configure))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
fun GitErrorDialog(details: GitErrorDetails, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(details.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(details.message, style = MaterialTheme.typography.bodyMedium)
                if (details.authFailure != GitAuthFailureType.NONE &&
                    details.authFailure != GitAuthFailureType.NETWORK_ERROR
                ) {
                    Text(
                        text = stringResource(R.string.git_error_auth_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SelectionContainer {
                    Text(
                        text = details.exceptionClass,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(details.toCopyableText())) }) {
                Text(stringResource(R.string.git_error_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close_dialog))
            }
        },
    )
}
