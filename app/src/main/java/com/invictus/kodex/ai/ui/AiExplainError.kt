package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.diff.DiffEngine
import com.invictus.kodex.ai.edit.EditApplier
import com.invictus.kodex.ai.edit.EditParser
import com.invictus.kodex.ai.tasks.ExplainErrorTask
import com.invictus.kodex.ui.icons.XIcons
import java.io.File
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

private val PATH_LINE = Regex("""([\w./-]+\.[A-Za-z0-9]{1,8}):(\d+)""")

/** Resolves [rel] under [root]; null if it escapes the project (e.g. a `..` segment in log text). */
private fun insideProject(root: File, rel: String): File? {
    val rootCanon = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val f = runCatching { File(rootCanon, rel).canonicalFile }.getOrNull() ?: return null
    return f.takeIf { it.path == rootCanon.path || it.path.startsWith(rootCanon.path + File.separator) }
}

/** Attaches ±30 lines around the first path:line that exists under [projectRoot]. */
private fun buildSnippet(projectRoot: File, errorText: String, globs: String): String? {
    for (m in PATH_LINE.findAll(errorText.take(20_000))) {
        val rel = m.groupValues[1]
        val line = m.groupValues[2].toIntOrNull() ?: continue
        if (Redactor.isExcluded(rel, globs)) continue
        val f = insideProject(projectRoot, rel) ?: continue
        if (!f.isFile) continue
        val lines = runCatching { f.readLines() }.getOrNull() ?: continue
        if (line !in 1..lines.size) continue
        val from = (line - 31).coerceAtLeast(0)
        val to = (line + 30).coerceAtMost(lines.size)
        return buildString {
            append("// ").append(rel).append(':').append(line).append('\n')
            for (i in from until to) append(i + 1).append(": ").append(lines[i]).append('\n')
        }
    }
    return null
}

/**
 * M20 entry point (Run log, Diagnostics): explains an error; an optional model fix
 * goes through the M19 EditParser + DiffReviewSheet + SHA-256 stale check.
 */
@Composable
fun ExplainErrorAi(errorText: String, projectPath: String?, app: Application) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = errorText.isNotBlank()) {
        Icon(XIcons.AutoAwesome, contentDescription = stringResource(R.string.ai_explain_explain_error_with_ai))
    }
    if (!open) return

    val runtime = remember { AiRuntime(app) }
    var review by remember { mutableStateOf<Triple<String, String, String>?>(null) } // path, base, new
    var applyError by remember { mutableStateOf<String?>(null) }

    AiStreamSheet(
        title = stringResource(R.string.ai_explain_explain_error),
        runtime = runtime,
        onClose = { open = false },
        stream = {
            val globs = globs()
            val snippet = projectPath?.let { buildSnippet(File(it), errorText, globs) }
            val (provider, model, key) = resolve()
            ExplainErrorTask(provider, key, model).stream(errorText.take(40_000), snippet)
        },
        footer = { finalText ->
            if (projectPath != null && EditParser.extractJson(finalText) != null) {
                TextButton(onClick = {
                    val proposal = EditParser.parse(finalText) ?: return@TextButton
                    val rel = PATH_LINE.find(errorText)?.groupValues?.get(1) ?: return@TextButton
                    val f = insideProject(File(projectPath), rel) ?: return@TextButton
                    val base = f.takeIf { it.isFile }?.readText() ?: return@TextButton
                    var text = base
                    var conflict: String? = null
                    for (edit in proposal.edits) {
                        when (val r = EditApplier.apply(text, edit)) {
                            is EditApplier.Outcome.Applied -> text = r.newText
                            is EditApplier.Outcome.Conflict -> {
                                conflict = r.reason
                                break
                            }
                        }
                    }
                    if (conflict != null) applyError = conflict
                    else review = Triple(f.absolutePath, base, text)
                }) { Text(stringResource(R.string.ai_explain_review_fix_patch)) }
                applyError?.let { Text(it) }
            }
        },
    )

    review?.let { (path, base, new) ->
        DiffReviewSheet(
            explanation = "Proposed fix from the error explanation",
            fileName = File(path).name,
            hunks = remember(new) { DiffEngine.diff(base, new) },
            applyError = applyError,
            onApply = { selected ->
                val cur = File(path).takeIf { it.isFile }?.readText()
                if (cur == null || EditApplier.sha256(cur) != EditApplier.sha256(base)) {
                    applyError = "File changed since the fix was proposed — regenerate the explanation"
                } else {
                    File(path).writeText(DiffEngine.applySelected(base, selected))
                    review = null
                }
            },
            onReject = { review = null },
            onRegenerate = { review = null },
            onDismiss = { review = null },
        )
    }
}
