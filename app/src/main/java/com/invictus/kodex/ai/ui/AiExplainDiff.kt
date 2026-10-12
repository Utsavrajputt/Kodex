package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.tasks.ExplainDiffTask
import com.invictus.kodex.core.git.GitResult
import com.invictus.kodex.ui.icons.XIcons

private const val MAX_FILES = 30

/** Renders side-by-side diff rows back to unified text for the explanation prompt. */
private fun rowsToUnified(rows: List<com.invictus.kodex.core.git.model.GitDiffRow>): String =
    buildString {
        rows.forEach { r ->
            when {
                r.leftText != null && r.rightText != null -> append(' ').append(r.leftText).append('\n')
                r.leftText != null -> append('-').append(r.leftText).append('\n')
                r.rightText != null -> append('+').append(r.rightText).append('\n')
            }
        }
    }

/** M20 entry point (commit detail): aggregates per-file commit diffs, then streams the explanation. */
@Composable
fun ExplainDiffAi(projectPath: String, commitId: String, filePaths: List<String>, app: Application) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = filePaths.isNotEmpty()) {
        Icon(XIcons.AutoAwesome, contentDescription = "Explain changes with AI")
    }
    if (!open) return

    val runtime = remember { AiRuntime(app) }

    AiStreamSheet(
        title = "Explain changes",
        runtime = runtime,
        onClose = { open = false },
    ) {
        val globs = globs()
        val diffText = withGitSession(projectPath) { session ->
            buildString {
                filePaths.filterNot { Redactor.isExcluded(it, globs) }.take(MAX_FILES).forEach { path ->
                    when (val r = session.commitFileDiff(commitId, path)) {
                        is GitResult.Ok -> {
                            append("--- a/").append(path).append('\n')
                            append("+++ b/").append(path).append('\n')
                            append(rowsToUnified(r.value.rows).take(8_000)).append('\n')
                        }
                        is GitResult.Err -> append("--- ").append(path).append(" (diff unavailable)\n\n")
                    }
                }
            }.take(60_000)
        }
        val (provider, model, key) = resolve()
        ExplainDiffTask(provider, key, model).stream(diffText)
    }
}
