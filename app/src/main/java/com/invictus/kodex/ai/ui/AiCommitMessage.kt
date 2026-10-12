package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import com.invictus.kodex.ai.prefs.AiPrefs
import kotlinx.coroutines.flow.first
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

/**
 * Git stage CommitCard AI button. Tap: generate with the last chosen style and put the message straight into the
 * commit box (never commits). Long press: the sheet (style, regenerate, edit before using). A failed tap opens
 * the sheet so the error is visible.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommitMessageAi(projectPath: String, hasChanges: Boolean, onFill: (String) -> Unit, app: Application) {
    var open by remember { mutableStateOf(false) }
    val runtime = remember { AiRuntime(app) }
    val prefs = remember { AiPrefs(app) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var style by remember { mutableStateOf(CommitMessageTask.Style.NORMAL) }
    var styleLoaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = prefs.commitStyle.first()
        CommitMessageTask.Style.entries.firstOrNull { it.name == saved }?.let { style = it }
        styleLoaded = true
    }

    /** Streams a message for the current diff; null (with [error] set) when there is nothing to show. */
    suspend fun produce(s: CommitMessageTask.Style): String? {
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
            return null
        }
        val (provider, model, key) = runtime.resolve()
        var out = ""
        CommitMessageTask(provider, key, model).stream(diffText, s).collect { ev ->
            when (ev) {
                is AiEvent.Delta -> out += ev.text
                AiEvent.Done -> Unit
                is AiEvent.Error -> throw AiException(ev.error)
            }
        }
        val text = out.trim().removePrefix("```").removeSuffix("```").trim()
        if (text.isBlank()) {
            error = "The model returned an empty message — try again"
            return null
        }
        return text
    }

    /** [fill] = tap flow: success goes straight to the commit box, failure opens the sheet with the error. */
    fun generate(fill: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            if (!fill) message = ""
            try {
                if (!styleLoaded) {
                    val saved = prefs.commitStyle.first()
                    CommitMessageTask.Style.entries.firstOrNull { it.name == saved }?.let { style = it }
                    styleLoaded = true
                }
                val result = produce(style)
                if (result != null) {
                    message = result
                    generatedOnce = true
                    if (fill) onFill(result)
                }
            } catch (e: AiException) {
                error = e.error.message
            } catch (e: Exception) {
                error = e.message ?: "Failed to generate"
            } finally {
                busy = false
                if (fill && error != null) open = true
            }
        }
    }

    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .alpha(if (hasChanges) 1f else 0.38f)
            .combinedClickable(
                enabled = hasChanges,
                role = Role.Button,
                onClickLabel = "Generate commit message",
                onClick = { generate(fill = true) },
                onLongClickLabel = "AI commit message settings",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    open = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (busy && !open) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Icon(XIcons.AutoAwesome, contentDescription = "Generate commit message with AI (long press for options)")
        }
    }
    if (!open) return

    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Generate commit message", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(vertical = 8.dp)) {
                CommitMessageTask.Style.entries.forEach { s ->
                    FilterChip(
                        selected = style == s,
                        onClick = {
                            style = s
                            scope.launch { prefs.setCommitStyle(s.name) }
                            if (generatedOnce && !busy) generate(fill = false)
                        },
                        label = { Text(s.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Button(onClick = { generate(fill = false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Generating…" else if (generatedOnce) "Regenerate" else "Generate")
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
