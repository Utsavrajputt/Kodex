package com.invictus.kodex.github.ui

import android.text.format.Formatter
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.install.ApkAction
import com.invictus.kodex.github.install.ApkInstallInfo
import com.invictus.kodex.ui.icons.XIcons

/** The latest build of every recent project's repo with all its ARM64 variants. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllApksScreen(
    onBack: () -> Unit,
    viewModel: AllApksViewModel = viewModel(factory = AllApksViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Back from the installer or another app: an install / update may have happened there.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshDownloaded() }

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
            state.builds.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(state.error ?: R.string.apks_empty), style = MaterialTheme.typography.bodyLarge)
                if (state.error == R.string.apks_load_failed) TextButton(onClick = viewModel::load) { Text(stringResource(R.string.gh_retry)) }
            }
            else -> LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(state.builds, key = { it.key }) { build ->
                    BuildCard(
                        build = build,
                        transfer = state.transfer,
                        installingId = state.installingId,
                        installPhase = state.installPhase,
                        anyBusy = state.anyBusy,
                        downloaded = state.downloaded,
                        installInfo = state.installInfo,
                        onDownload = viewModel::download,
                        onInstall = viewModel::install,
                        onOpen = viewModel::open,
                        onDelete = viewModel::delete,
                        onPauseResume = { if (state.transfer?.paused == true) viewModel.resume() else viewModel.pause() },
                        onCancel = viewModel::cancel,
                    )
                }
            }
        }
    }
}

/** One repo's latest build: a block per variant (Light, Full, ...), each with its own actions. */
@Composable
private fun BuildCard(
    build: RepoBuild,
    transfer: RowTransfer?,
    installingId: Long?,
    installPhase: InstallPhase,
    anyBusy: Boolean,
    downloaded: Set<Long>,
    installInfo: Map<Long, ApkInstallInfo>,
    onDownload: (ApkRow) -> Unit,
    onInstall: (ApkRow) -> Unit,
    onOpen: (ApkRow) -> Unit,
    onDelete: (ApkRow) -> Unit,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
        border = BorderStroke(1.dp, cs.onPrimaryContainer.copy(alpha = 0.22f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            build.variants.forEachIndexed { index, row ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(cs.onPrimaryContainer.copy(alpha = 0.2f)))
                ApkVariantBlock(
                    row = row,
                    transfer = transfer?.takeIf { it.artifactId == row.artifact.id },
                    installing = installingId == row.artifact.id,
                    installPhase = installPhase,
                    downloaded = row.artifact.id in downloaded,
                    action = installInfo[row.artifact.id]?.action ?: ApkAction.Install,
                    anyBusy = anyBusy,
                    onDownload = { onDownload(row) },
                    onInstall = { onInstall(row) },
                    onOpen = { onOpen(row) },
                    onDelete = { onDelete(row) },
                    onPauseResume = onPauseResume,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun ApkVariantBlock(
    row: ApkRow,
    transfer: RowTransfer?,
    installing: Boolean,
    installPhase: InstallPhase,
    downloaded: Boolean,
    action: ApkAction,
    anyBusy: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val a = row.artifact
    Column(
        modifier = Modifier.fillMaxWidth().alpha(if (row.expired && !downloaded) 0.55f else 1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProjectIcon(row.projectPath, size = 44.dp, ownerLogin = row.repo.owner)
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
            if (!downloaded && transfer == null && !row.expired) {
                FilledIconButton(onClick = onDownload, enabled = !anyBusy, modifier = Modifier.size(44.dp)) {
                    Icon(XIcons.Download, contentDescription = stringResource(R.string.gh_apk_download))
                }
            }
        }
        if (transfer != null) {
            TransferStatus(
                downloaded = transfer.downloaded, total = transfer.total, etaSeconds = transfer.etaSeconds, speedBytesPerSec = transfer.speedBps, paused = transfer.paused,
                onPauseResume = onPauseResume, onCancel = onCancel,
                accent = cs.primary, track = cs.onPrimaryContainer.copy(alpha = 0.15f), textColor = cs.onPrimaryContainer.copy(alpha = 0.75f),
                canPause = transfer.total <= 0 || transfer.downloaded < transfer.total,
            )
        } else if (downloaded || installing) {
            // On the device: Install / Update / Open, plus Delete.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = if (action == ApkAction.Open) onOpen else onInstall,
                    enabled = !anyBusy && !installing, modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    if (installing) {
                        if (installPhase == InstallPhase.Installed) {
                            Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Crossfade(targetState = installPhase, label = "install_phase") { ph ->
                            Text(stringResource(installPhaseLabel(ph)), style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Text(stringResource(apkActionLabel(action)), style = MaterialTheme.typography.labelLarge)
                    }
                }
                FilledTonalIconButton(
                    onClick = onDelete, enabled = !anyBusy && !installing, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = cs.errorContainer, contentColor = cs.onErrorContainer,
                    ),
                ) { Icon(XIcons.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
        }
    }
}
