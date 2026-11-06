package com.invictus.kodex.ai.ui

import android.app.Application
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.invictus.kodex.R
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.AiSummaryKind
import com.invictus.kodex.ai.tasks.ListSummaryTask
import com.invictus.kodex.ui.icons.XIcons

/** Sparkle button: streams a short digest of [itemsText] (pull requests, issues or one thread) into a sheet. Read-only. */
@Composable
fun ListSummaryAi(kind: AiSummaryKind, itemsText: String, app: Application) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = itemsText.isNotBlank()) {
        Icon(XIcons.AutoAwesome, contentDescription = stringResource(R.string.ai_summary_hint))
    }
    if (!open) return
    val runtime = remember { AiRuntime(app) }
    AiStreamSheet(
        title = stringResource(
            when (kind) {
                AiSummaryKind.PULLS -> R.string.ai_summary_title_pulls
                AiSummaryKind.ISSUES -> R.string.ai_summary_title_issues
                AiSummaryKind.THREAD -> R.string.ai_summary_title_thread
            },
        ),
        runtime = runtime,
        onClose = { open = false },
        stream = {
            val (provider, model, key) = resolve()
            ListSummaryTask(provider, key, model).stream(kind, itemsText.take(14_000))
        },
    )
}
