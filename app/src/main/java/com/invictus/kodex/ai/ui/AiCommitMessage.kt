package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material3.Switch
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
import com.invictus.kodex.ai.tasks.CommitDiffCompactor
import com.invictus.kodex.ai.tasks.CommitMessageTask
import com.invictus.kodex.core.git.GitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

/**
 * Git stage CommitCard AI button. Tap: generate with the last chosen style and put the message straight into the
 * commit box (never commits): it replaces the box text, or is added after it when the sheet's Replace switch is
 * off. Long press: the sheet (style, Replace switch, regenerate, edit before using). A failed tap opens the sheet
 * so the error is visible. Works on staged changes, or on the unstaged ones (incl. new files) when nothing is staged.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommitMessageAi(
    projectPath: String,
    hasChanges: Boolean,
    onFill: (String) -> Unit,
    onReplace: (String) -> Unit,
    amend: Boolean,
    app: Application,
) {
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
    var replace by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val saved = prefs.commitStyle.first()
        CommitMessageTask.Style.entries.firstOrNull { it.name == saved }?.let { style = it }
        replace = prefs.commitReplace.first()
        styleLoaded = true
    }

    /** Into the commit box: swaps its text, or adds after it, per the Replace switch. */
    fun put(text: String) = if (replace) onReplace(text) else onFill(text)

    /** Streams a message for the current diff; null (with [error] set) when there is nothing to show. */
    suspend fun produce(s: CommitMessageTask.Style): String? {
        val globs = runtime.globs()
        // Compacting (file list + trimmed hunks) is CPU work: keep it off the main thread.
        val changes = withContext(Dispatchers.Default) {
            val set = withGitSession(projectPath) { it.commitChangeSet() }
            if (set is GitResult.Ok) {
                CommitDiffCompactor.compact(
                    diff = Redactor.scrubDiff(set.value.diff, globs),
                    untracked = set.value.untracked
                        .filterNot { Redactor.isExcluded(it.path, globs) }
                        .map { CommitDiffCompactor.Untracked(it.path, it.head) },
                ).let { Redactor.scrub(it) }
            } else {
                ""
            }
        }
        if (changes.isBlank()) {
            error = "No changes to describe"
            return null
        }
        val (provider, model, key) = runtime.resolve()
        var out = ""
        CommitMessageTask(provider, key, model).stream(changes, s).collect { ev ->
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
                    replace = prefs.commitReplace.first()
                    styleLoaded = true
                }
                val result = produce(style)
                if (result != null) {
                    message = result
                    generatedOnce = true
                    if (fill) put(result)
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
                onClickLabel = stringResource(R.string.ai_commit_generate_commit_message),
                onClick = { generate(fill = true) },
                onLongClickLabel = stringResource(R.string.ai_commit_ai_commit_message_settings),
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
            Icon(XIcons.AutoAwesome, contentDescription = stringResource(R.string.ai_commit_generate_commit_message_with_ai))
        }
    }
    if (!open) return

    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Title + the "replace the commit box" switch share one line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.ai_commit_generate_commit_message),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.ai_commit_replace_short),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                // Amend ON: the message is always added after the current one, so the switch is parked (off, disabled).
                Switch(
                    checked = replace && !amend,
                    enabled = !amend,
                    onCheckedChange = { replace = it; scope.launch { prefs.setCommitReplace(it) } },
                )
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CommitMessageTask.Style.entries.forEach { s ->
                    FilterChip(
                        selected = style == s,
                        onClick = {
                            style = s
                            scope.launch { prefs.setCommitStyle(s.name) }
                            if (generatedOnce && !busy) generate(fill = false)
                        },
                        label = { Text(s.label) },
                    )
                }
            }
            // The message is always on screen (empty until generated) so the sheet never jumps around.
            Column {
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text(stringResource(R.string.ai_commit_commit_message_editable)) },
                    placeholder = { Text(if (busy) stringResource(R.string.ai_commit_generating) else stringResource(R.string.ai_commit_generate)) },
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    minLines = 4,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp).clip(CircleShape))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { generate(fill = false) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (generatedOnce) R.string.ai_chat_regenerate else R.string.ai_commit_generate))
                }
                Button(
                    onClick = { put(message); open = false },
                    enabled = message.isNotBlank() && !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.ai_commit_use_message)) }
            }
            Text(
                stringResource(
                    when {
                        amend -> R.string.ai_commit_replace_amend
                        replace -> R.string.ai_commit_replace_on
                        else -> R.string.ai_commit_replace_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
