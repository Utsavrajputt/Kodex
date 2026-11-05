package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AiEvent
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.tasks.PullRequestTask
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * New pull request AI button. Tap: write title + description with the last chosen style straight into the form
 * (overwrites). Long press: the sheet (style, regenerate, edit before using). A failed tap opens the sheet so the
 * error is visible. [loadChanges] gets the user's AI exclude globs and returns the commits/diff text to describe.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PullRequestAi(
    canGenerate: Boolean,
    loadChanges: suspend (excludeGlobs: String) -> String,
    onFill: (title: String, body: String) -> Unit,
    app: Application,
) {
    var open by remember { mutableStateOf(false) }
    val runtime = remember { AiRuntime(app) }
    val prefs = remember { AiPrefs(app) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var style by remember { mutableStateOf(PullRequestTask.Style.NORMAL) }
    var styleLoaded by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var generatedOnce by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val saved = prefs.prStyle.first()
        PullRequestTask.Style.entries.firstOrNull { it.name == saved }?.let { style = it }
        styleLoaded = true
    }

    /** (title, body) for the current branches; null (with [error] set) when there is nothing to show. */
    suspend fun produce(s: PullRequestTask.Style): Pair<String, String>? {
        val globs = runtime.globs()
        val changes = loadChanges(globs)
        if (changes.isBlank()) {
            error = "No changes between these branches"
            return null
        }
        val (provider, model, key) = runtime.resolve()
        var out = ""
        PullRequestTask(provider, key, model).stream(changes, s).collect { ev ->
            when (ev) {
                is AiEvent.Delta -> out += ev.text
                AiEvent.Done -> Unit
                is AiEvent.Error -> throw AiException(ev.error)
            }
        }
        val parsed = PullRequestTask.parse(out)
        if (parsed.first.isBlank()) {
            error = "The model returned an empty answer — try again"
            return null
        }
        return parsed
    }

    /** [fill] = tap flow: success goes straight into the form, failure opens the sheet with the error. */
    fun generate(fill: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                if (!styleLoaded) {
                    val saved = prefs.prStyle.first()
                    PullRequestTask.Style.entries.firstOrNull { it.name == saved }?.let { style = it }
                    styleLoaded = true
                }
                val result = produce(style)
                if (result != null) {
                    title = result.first
                    body = result.second
                    generatedOnce = true
                    if (fill) onFill(result.first, result.second)
                }
            } catch (e: CancellationException) {
                throw e
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
            .alpha(if (canGenerate) 1f else 0.38f)
            .combinedClickable(
                enabled = canGenerate,
                role = Role.Button,
                onClickLabel = stringResource(R.string.ai_pr_generate),
                onClick = { generate(fill = true) },
                onLongClickLabel = stringResource(R.string.ai_pr_settings),
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
            Icon(XIcons.AutoAwesome, contentDescription = stringResource(R.string.ai_pr_generate_hint))
        }
    }
    if (!open) return

    ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(R.string.ai_pr_generate), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(vertical = 8.dp)) {
                PullRequestTask.Style.entries.forEach { s ->
                    FilterChip(
                        selected = style == s,
                        onClick = {
                            style = s
                            scope.launch { prefs.setPrStyle(s.name) }
                            if (generatedOnce && !busy) generate(fill = false)
                        },
                        label = { Text(s.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Button(onClick = { generate(fill = false) }, enabled = !busy && canGenerate, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (busy) stringResource(R.string.ai_commit_generating)
                    else if (generatedOnce) stringResource(R.string.ai_chat_regenerate)
                    else stringResource(R.string.ai_commit_generate),
                )
            }
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (title.isNotEmpty()) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.ai_pr_title_editable)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = body, onValueChange = { body = it },
                    label = { Text(stringResource(R.string.ai_pr_body_editable)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Button(
                    onClick = { onFill(title.trim(), body.trim()); open = false },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.ai_pr_use)) }
                Text(
                    stringResource(R.string.ai_pr_fills_form_only),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
