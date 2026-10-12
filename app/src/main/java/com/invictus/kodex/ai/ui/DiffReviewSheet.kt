package com.invictus.kodex.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.diff.DiffHunk
import com.invictus.kodex.ai.diff.LineType
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R

/**
 * Diff review (plan 9.5): nothing is written until Apply. Per-hunk accept/reject
 * (default all selected), +/- glyphs AND colours (accessibility), Regenerate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffReviewSheet(
    explanation: String,
    fileName: String,
    hunks: List<DiffHunk>,
    applyError: String?,
    onApply: (List<DiffHunk>) -> Unit,
    onReject: () -> Unit,
    onRegenerate: (String?) -> Unit,
    onDismiss: () -> Unit,
    onExplain: (() -> Unit)? = null,   // M20
) {
    val included = remember(hunks) { hunks.map { it.included }.toMutableStateList() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.ai_diff_review_changes, fileName), style = MaterialTheme.typography.titleMedium)
            if (explanation.isNotBlank()) {
                Text(explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
            }
            applyError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(Modifier.heightIn(max = 360.dp).fillMaxWidth().padding(vertical = 8.dp)) {
                items(hunks.size) { i ->
                    val h = hunks[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = included[i], onCheckedChange = { v -> included[i] = v })
                        Text(stringResource(R.string.ai_diff_hunk, i + 1), style = MaterialTheme.typography.labelMedium)
                    }
                    h.lines.forEach { l ->
                        val (glyph, color) = when (l.type) {
                            LineType.ADD -> "+" to MaterialTheme.colorScheme.primaryContainer
                            LineType.REMOVE -> "−" to MaterialTheme.colorScheme.errorContainer
                            LineType.CONTEXT -> " " to MaterialTheme.colorScheme.surface
                        }
                        Text(
                            "$glyph ${l.text}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onExplain != null) TextButton(onClick = onExplain) { Text(stringResource(R.string.ai_diff_explain)) }
                TextButton(onClick = onReject) { Text(stringResource(R.string.ai_diff_reject)) }
                TextButton(onClick = { onRegenerate(null) }) { Text(stringResource(R.string.ai_chat_regenerate)) }
                Row(Modifier.weight(1f)) {}
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_cancel)) }
                Button(
                    onClick = { onApply(hunks.mapIndexed { i, h -> h.copy(included = included[i]) }) },
                    enabled = included.any { it },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.ai_diff_apply)) }
            }
        }
    }
}
