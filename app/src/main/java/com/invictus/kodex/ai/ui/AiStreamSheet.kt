package com.invictus.kodex.ai.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiException
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

/** Generic streamed-AI bottom sheet shared by all M20 explainers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiStreamSheet(
    title: String,
    runtime: AiRuntime,
    onClose: () -> Unit,
    footer: (@Composable (String) -> Unit)? = null,
    stream: suspend AiRuntime.() -> Flow<AiEvent>,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        try {
            with(runtime) { stream() }.collect { ev ->
                when (ev) {
                    is AiEvent.Delta -> text += ev.text
                    AiEvent.Done -> Unit
                    is AiEvent.Error -> throw AiException(ev.error)
                }
            }
        } catch (e: AiException) {
            error = e.error.message
        } catch (e: Exception) {
            error = e.message ?: "Request failed"
        } finally {
            busy = false
        }
    }

    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (text.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text(stringResource(R.string.ai_chat_copy)) }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.ai_stream_close)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            )
            if (!busy) footer?.invoke(text)
        }
    }
}
