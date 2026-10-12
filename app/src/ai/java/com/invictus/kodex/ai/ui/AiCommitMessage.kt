package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.tasks.CommitMessageTask
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

private const val MAX_DIFF_CHARS = 60_000

/** M20 entry point (Git stage CommitCard): generates an editable commit message, never commits. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitMessageAi(projectPath: String, hasChanges: Boolean, onFill: (String) -> Unit, app: Application) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = hasChanges) {
        Icon(XIcons.AutoAwesome, contentDescription = "Generate commit message with AI")
    }
    if (!open) return

    val runtime = remember { AiRuntime(app) }
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(CommitMessageTask.Style.NORMAL) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedOnce by remember { mutableStateOf(false) }

    fun generate() {
        scope.launch {
            busy = true
            error = null
            message = ""
            try {
                val globs = runtime.globs()
                val diffText = withGitSession(projectPath) { session ->
                    val staged = session.stagedDiffText()
                    if (staged is GitResult.Ok && staged.value.isNotBlank()) {
                        staged.value.take(MAX_DIFF_CHARS)
                    } else {
                        val wt = session.workTreeDiffText()
                        if (wt is GitResult.Ok) wt.value.take(MAX_DIFF_CHARS) else ""
                    }
                }.let { Redactor.scrubDiff(it, globs) }
                if (diffText.isBlank()) {
                    error = "No changes to describe"
                    return@launch
                }
                val (provider, model, key) = runtime.resolve()
                var out = ""
                CommitMessageTask(provider, key, model).stream(diffText, style).collect { ev ->
                    when (ev) {
                        is AiEvent.Delta -> out += ev.text
                        AiEvent.Done -> Unit
                        is AiEvent.Error -> throw AiException(ev.error)
                    }
                }
                message = out.trim().removePrefix("```").removeSuffix("```").trim()
                generatedOnce = true
                if (message.isBlank()) error = "The model returned an empty message — try again"
            } catch (e: AiException) {
                error = e.error.message
            } catch (e: Exception) {
                error = e.message ?: "Failed to generate"
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Generate commit message", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(vertical = 8.dp)) {
                CommitMessageTask.Style.entries.forEach { s ->
                    FilterChip(
                        selected = style == s,
                        onClick = { style = s; if (generatedOnce && !busy) generate() },
                        label = { Text(s.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Button(onClick = { generate() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (generatedOnce) "Regenerate" else "Generate")
            }
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (message.isNotEmpty()) {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Commit message (editable)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Button(
                    onClick = { onFill(message); open = false },
                    enabled = message.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Use message") }
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Fills the commit box only — committing stays your action.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
