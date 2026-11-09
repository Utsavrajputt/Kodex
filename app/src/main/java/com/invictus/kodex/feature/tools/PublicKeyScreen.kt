package com.invictus.kodex.feature.tools

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.crypto.DerivedKey
import com.invictus.kodex.core.crypto.KeyParseException
import com.invictus.kodex.core.crypto.PublicKeyDeriver
import com.invictus.kodex.github.ui.ClipboardHelper
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paste a private key (PEM, OpenSSH, base64 or hex; RSA or Ed25519) and get its public key. Works fully offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicKeyScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Deliberately plain `remember`, not saveable: a private key must never end up in saved instance state.
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<DerivedKey?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }

    fun derive() {
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { PublicKeyDeriver.derive(input) } }
            r.onSuccess { result = it; error = null }
            r.onFailure { e ->
                result = null
                error = when ((e as? KeyParseException)?.reason) {
                    KeyParseException.Reason.Empty -> R.string.pk_err_empty
                    KeyParseException.Reason.Encrypted -> R.string.pk_err_encrypted
                    KeyParseException.Reason.Unsupported -> R.string.pk_err_unsupported
                    else -> R.string.pk_err_unrecognized
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_pubkey_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.pk_intro), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            OutlinedTextField(
                value = input, onValueChange = { input = it; error = null },
                label = { Text(stringResource(R.string.pk_input_label)) },
                minLines = 5, maxLines = 12, shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                isError = error != null, modifier = Modifier.fillMaxWidth(),
                supportingText = error?.let { code -> { Text(stringResource(code)) } },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let { input = it; error = null } }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pk_paste))
                }
                OutlinedButton(onClick = { input = ""; result = null; error = null }, enabled = input.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools_clear))
                }
            }
            Button(onClick = { derive() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(XIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.pk_show))
            }

            result?.let { k ->
                Text(stringResource(R.string.pk_found, k.kind), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                KeyBlock(R.string.pk_fmt_base64, k.base64) { copy(context, k.base64, snackbar, scope) }
                k.hex?.let { h -> KeyBlock(R.string.pk_fmt_hex, h) { copy(context, h, snackbar, scope) } }
                KeyBlock(R.string.pk_fmt_pem, k.pem) { copy(context, k.pem, snackbar, scope) }
                k.openssh?.let { o -> KeyBlock(R.string.pk_fmt_openssh, o) { copy(context, o, snackbar, scope) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun copy(context: android.content.Context, text: String, snackbar: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    ClipboardHelper.copy(context, "public key", text, sensitive = false)
    scope.launch { snackbar.showSnackbar(context.getString(R.string.tools_public_copied)) }
}

@Composable
private fun KeyBlock(label: Int, value: String, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.surfaceContainer).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.pk_copy), tint = cs.primary) }
        }
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
