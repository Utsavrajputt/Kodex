package com.invictus.kodex.github.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/** Every ARM64 build of the recent projects, newest to oldest, ten at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllApksScreen(
    onBack: () -> Unit,
    viewModel: AllApksViewModel = viewModel(factory = AllApksViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var expanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is AllApksEvent.Snack -> {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(context.getString(e.message, *e.args.toTypedArray()))
                }
                is AllApksEvent.OpenSettings -> runCatching { context.startActivity(e.intent) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.apks_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.loading) {
                        Icon(XIcons.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            state.rows.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(state.error ?: R.string.apks_empty), style = MaterialTheme.typography.bodyLarge)
                if (state.error == R.string.apks_load_failed) TextButton(onClick = viewModel::load) { Text(stringResource(R.string.gh_retry)) }
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Latest build first; "View more" reveals the older ones.
                items(if (expanded) state.shown else state.rows.take(1), key = { it.artifact.id }) { row ->
                    ApkRowCard(
                        row = row,
                        busy = state.busyId == row.artifact.id,
                        downloaded = row.artifact.id in state.downloaded,
                        progress = state.progress,
                        anyBusy = state.busyId != null,
                        onDownload = { viewModel.download(row) },
                        onInstall = { viewModel.install(row) },
                        onDelete = { viewModel.delete(row) },
                        onCancel = viewModel::cancel,
                    )
                }
                if (!expanded && state.rows.size > 1) {
                    item(key = "view_more") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.apks_show_more)) }
                        }
                    }
                } else if (expanded && state.hasMore) {
                    item(key = "more") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = viewModel::showMore) { Text(stringResource(R.string.apks_show_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkRowCard(
    row: ApkRow,
    busy: Boolean,
    downloaded: Boolean,
    progress: Float?,
    anyBusy: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val a = row.artifact
    Card(
        modifier = Modifier.fillMaxWidth().alpha(if (row.expired && !downloaded) 0.55f else 1f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectIcon(row.projectPath, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        apkBuildName(a.name, a.shortSha), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(
                            Formatter.formatShortFileSize(context, a.sizeBytes),
                            a.createdAt?.let { timeAgoOrDate(it) }.orEmpty(),
                            if (row.expired && !downloaded) stringResource(R.string.gh_artifact_expired_label) else "",
                        ).filter { it.isNotBlank() }.joinToString(" \u2022 "),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                // Not on the device yet: a Download button (not expired builds only).
                if (!downloaded && !busy && !row.expired) {
                    FilledIconButton(onClick = onDownload, enabled = !anyBusy, modifier = Modifier.size(44.dp)) {
                        Icon(XIcons.Download, contentDescription = stringResource(R.string.gh_apk_download))
                    }
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (progress == null) LinearProgressIndicator(Modifier.weight(1f))
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                }
            } else if (downloaded) {
                // On the device: Install, plus Delete.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onInstall, enabled = !anyBusy, modifier = Modifier.weight(1f).height(44.dp)) {
                        Text(stringResource(R.string.gh_install), style = MaterialTheme.typography.labelLarge)
                    }
                    FilledTonalIconButton(
                        onClick = onDelete, enabled = !anyBusy, modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = cs.errorContainer, contentColor = cs.onErrorContainer,
                        ),
                    ) { Icon(XIcons.Delete, contentDescription = stringResource(R.string.action_delete)) }
                }
            }
        }
    }
}
