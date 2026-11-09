package com.invictus.kodex.feature.archive

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.R
import com.invictus.kodex.core.archive.ArchiveEntry
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * Opens an archive (zip, jar, aar, apk, 7z, rar) like a folder: browse, preview text and images,
 * and extract the selection (or everything) into a new folder next to the archive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveExplorerScreen(
    archive: File,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ArchiveExplorerViewModel = viewModel(
        key = "archive:${archive.absolutePath}",
        factory = viewModelFactory { initializer { ArchiveExplorerViewModel(archive) } },
    )
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Back walks up through the archive's folders before leaving.
    BackHandler(enabled = s.dir.isNotEmpty()) { vm.up() }

    val notice = s.notice
    if (notice != null) {
        val text = if (notice.error != null) {
            stringResource(R.string.archive_failed, notice.error)
        } else {
            stringResource(R.string.archive_extracted, notice.count, notice.folder.orEmpty())
        }
        LaunchedEffect(notice) {
            snackbar.showSnackbar(text)
            vm.noticeShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (!vm.up()) onBack() }) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    Column {
                        Text(archive.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "/" + s.dir,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!s.loading && s.error == null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s.selected.isNotEmpty()) {
                        TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.archive_clear)) }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::extract, enabled = !s.busy) {
                        if (s.busy) {
                            CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (s.selected.isEmpty()) stringResource(R.string.archive_extract_all, s.totalFiles)
                            else stringResource(R.string.archive_extract_selected, s.selected.size),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                s.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                s.error != null -> Text(
                    stringResource(R.string.archive_open_failed, s.error.orEmpty()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                s.rows.isEmpty() -> Text(
                    stringResource(R.string.archive_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(s.rows, key = { it.path }) { e ->
                        EntryRow(
                            e = e,
                            checked = e.path in s.selected,
                            sizeText = if (e.isDirectory) null else Formatter.formatShortFileSize(context, e.size),
                            onClick = { if (e.isDirectory) vm.openDir(e.path) else vm.preview(e) },
                            onToggle = { vm.toggle(e.path) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    s.preview?.let { PreviewDialog(it, onDismiss = vm::dismissPreview) }
}

@Composable
private fun EntryRow(e: ArchiveEntry, checked: Boolean, sizeText: String?, onClick: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (e.isDirectory) XIcons.Folder else XIcons.InsertDriveFile,
            contentDescription = null,
            tint = if (e.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            if (sizeText != null) {
                Text(sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun PreviewDialog(p: ArchiveExplorerViewModel.Preview, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when (p) {
                is ArchiveExplorerViewModel.Preview.Text -> Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(p.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.truncated) {
                        Text(
                            stringResource(R.string.archive_preview_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is ArchiveExplorerViewModel.Preview.Picture -> Image(
                    bitmap = p.bitmap.asImageBitmap(),
                    contentDescription = p.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                )
                is ArchiveExplorerViewModel.Preview.Unsupported ->
                    Text(stringResource(R.string.archive_preview_unsupported))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.archive_close)) } },
    )
}
