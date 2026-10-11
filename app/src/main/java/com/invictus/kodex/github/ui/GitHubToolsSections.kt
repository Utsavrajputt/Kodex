package com.invictus.kodex.github.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhArtifact
import com.invictus.kodex.github.api.GhDeployment
import com.invictus.kodex.github.api.GhWorkflow
import com.invictus.kodex.github.install.ArtifactZip
import com.invictus.kodex.github.install.InstallDecision
import com.invictus.kodex.github.install.InstallKind
import com.invictus.kodex.ui.icons.XIcons
import java.time.Instant

private const val COLLAPSED_COUNT = 5

// ---- Run Workflow row (plan 6.1 card 2 / 8.2) ---------------------------------------------

@Composable
fun RunWorkflowCard(
    state: RunWorkflowState,
    workflows: List<GhWorkflow>,
    defaultBranch: String?,
    tools: GitHubToolsController,
) {
    val workflow = tools.effectiveWorkflow(workflows, state.selectedWorkflowId)
    val ref = state.selectedBranch ?: defaultBranch
    LaunchedEffect(workflow?.id, ref) {
        if (workflow != null && ref != null) tools.ensureDispatchInfo(workflow, ref)
    }
    val info = state.info
    val supported = (info as? DispatchInfoState.Loaded)?.info?.supported
    val canRun = workflow != null && ref != null && !state.dispatching &&
        (supported == true || info is DispatchInfoState.Failed)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.gh_run_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerButton(
                    label = workflow?.name ?: stringResource(R.string.gh_run_pick_workflow),
                    enabled = workflows.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { close ->
                    workflows.forEach { w ->
                        DropdownMenuItem(
                            text = { Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { close(); tools.selectWorkflow(w.id) },
                        )
                    }
                }
                PickerButton(
                    label = ref ?: stringResource(R.string.gh_run_pick_branch),
                    enabled = ref != null || state.branches.isNotEmpty(),
                    modifier = Modifier.weight(0.8f),
                ) { close ->
                    val names = state.branches.ifEmpty { listOfNotNull(defaultBranch) }
                    names.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { close(); tools.selectBranch(b) },
                        )
                    }
                    if (state.branchesHasMore) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gh_load_more)) },
                            onClick = { tools.loadMoreBranches() }, // keep the menu open
                        )
                    }
                }
                FilledIconButton(
                    onClick = { if (workflow != null && ref != null) tools.onRunPressed(workflow, ref) },
                    enabled = canRun,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (state.dispatching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(XIcons.PlayArrow, contentDescription = stringResource(R.string.gh_run_action))
                    }
                }
            }
            val note = when {
                workflow == null -> null
                info is DispatchInfoState.Loading -> R.string.gh_run_checking
                supported == false -> R.string.gh_run_no_manual_trigger_note
                info is DispatchInfoState.Failed -> R.string.gh_run_inputs_unreadable
                else -> null
            }
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(40.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

// ---- Latest APK card (plan 6.1 card 3 / 8.3 / 8.4) ----------------------------------------------

@Composable
fun ApkCard(
    state: GitHubToolsState,
    tools: GitHubToolsController,
    onRunShortcut: () -> Unit,
    projectPath: String,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // Only ONE APK is ever shown: the latest successful run's artifact. If it is already on the device the
    // card offers Install / Save / Delete, otherwise Download. An older downloaded file is only a fallback when
    // no run artifact is available any more (expired / none / error).
    val apk = state.apk
    val found = apk as? ApkCardState.Found
    val latestId = found?.candidate?.artifact?.id
    val local = if (latestId != null) state.downloaded.firstOrNull { it.artifactId == latestId } else state.downloaded.firstOrNull()
    val busy = state.transfer != null || state.installing || state.pendingSave != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer, contentColor = cs.onPrimaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val title = when {
                found != null -> apkBuildName(found.candidate.artifact.name, found.candidate.artifact.shortSha)
                local != null -> apkTitle(local.label)
                else -> stringResource(R.string.gh_apk_title)
            }
            val facts = when {
                found != null -> {
                    val a = found.candidate.artifact
                    listOf(
                        Formatter.formatShortFileSize(context, a.sizeBytes),
                        a.createdAt?.let { timeAgoOrDate(it) }.orEmpty(),
                    )
                }
                local != null -> listOf(
                    Formatter.formatShortFileSize(context, local.sizeBytes),
                    timeAgoOrDate(Instant.ofEpochMilli(local.modifiedAt)),
                )
                else -> emptyList()
            }.filter { it.isNotBlank() }.joinToString(" \u2022 ")

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectIcon(projectPath, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (facts.isNotBlank()) {
                        Text(
                            facts, style = MaterialTheme.typography.bodySmall,
                            color = cs.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            when {
                found != null -> {
                    val a = found.candidate.artifact
                    val transfer = state.transfer?.takeIf { it.fromApkCard && it.artifactId == a.id }
                    val ready = state.ready?.takeIf { it.fromApkCard && it.artifactId == a.id }
                    // Freshly downloaded but not yet listed: same buttons, no details panel.
                    val justDownloaded = ready?.let { DownloadedApk(it.artifactId, it.artifactName, it.file, it.file.length(), System.currentTimeMillis()) }
                    when {
                        transfer != null -> TransferRow(transfer, onCancel = tools::cancelTransfer)
                        local != null -> ApkActions(local, state.installing, busy, tools)
                        justDownloaded != null -> ApkActions(justDownloaded, state.installing, busy, tools)
                        else -> Button(
                            onClick = { tools.downloadApk(found.candidate) },
                            enabled = state.transfer == null,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Icon(XIcons.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.gh_apk_download), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                local != null -> ApkActions(local, state.installing, busy, tools)
                apk == ApkCardState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    val msg = when (apk) {
                        is ApkCardState.Error -> apk.message.resolve(context)
                        is ApkCardState.AllExpired -> apk.newestAt?.let { stringResource(R.string.gh_apk_expired_on, dateOf(it)) }
                            ?: stringResource(R.string.gh_apk_expired)
                        else -> stringResource(R.string.gh_apk_none)
                    }
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (apk is ApkCardState.Error) cs.error else cs.onPrimaryContainer)
                    FilledTonalButton(
                        onClick = if (apk is ApkCardState.Error) tools::loadArtifactsAndApk else onRunShortcut,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text(stringResource(if (apk is ApkCardState.Error) R.string.gh_retry else R.string.gh_run_action_label))
                    }
                }
            }
        }
    }
}

/** APK already on the device: big Install, plus Save-to-folder and a clearly separate Delete. */
@Composable
private fun ApkActions(d: DownloadedApk, installing: Boolean, busy: Boolean, tools: GitHubToolsController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { tools.installDownloaded(d) },
            enabled = !busy,
            modifier = Modifier.weight(1f).height(44.dp),
        ) {
            Icon(XIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.gh_install), style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalIconButton(
            onClick = { tools.saveDownloaded(d) },
            enabled = !busy,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(XIcons.Folder, contentDescription = stringResource(R.string.gh_save_to_folder))
        }
        FilledTonalIconButton(
            onClick = { tools.deleteDownloaded(d) },
            enabled = !installing,
            modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Icon(XIcons.Delete, contentDescription = stringResource(R.string.action_delete))
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(if (t.purpose == TransferPurpose.Install) R.string.gh_transfer_downloading else R.string.gh_transfer_preparing_save),
                style = MaterialTheme.typography.bodySmall,
            )
            val p = t.progress
            if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/** Verified APK waiting for the user's tap (plan 8.4): facts, warnings, Install / Save / Close. */
@Composable
fun ReadyApkBody(
    ready: ReadyApk,
    installing: Boolean,
    onInstall: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val r = ready.inspect
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.gh_ready_version, r.info.versionName.ifBlank { "?" }, r.info.versionCode),
            style = MaterialTheme.typography.titleSmall,
        )
        Muted(r.info.packageName)
        Text(
            stringResource(
                when (r.kind) {
                    InstallKind.Install -> R.string.gh_ready_install
                    InstallKind.Update -> R.string.gh_ready_update
                    InstallKind.Reinstall -> R.string.gh_ready_reinstall
                    InstallKind.Downgrade -> R.string.gh_ready_downgrade
                    InstallKind.SignatureMismatch -> R.string.gh_ready_signature
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (r.kind == InstallKind.Downgrade || r.kind == InstallKind.SignatureMismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (!r.abiCompatible) {
            Text(stringResource(R.string.gh_ready_abi_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (r.isSelf) {
            Muted(stringResource(R.string.gh_ready_self_note))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onInstall, enabled = !installing && !InstallDecision.isBlocked(r.kind)) {
                if (installing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.gh_install))
            }
            OutlinedButton(onClick = onSave) { Text(stringResource(R.string.gh_save_to_folder)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.gh_close)) }
        }
    }
}

// ---- Deployments section (plan 6.1 card 5 / 7.4) -----------------------------------------------

fun LazyListScope.deploymentsSection(
    state: GitHubToolsState,
    showAll: Boolean,
    onShowAll: () -> Unit,
    tools: GitHubToolsController,
    onDelete: (GhDeployment) -> Unit,
) {
    // Only shown when the repo actually has deployments.
    if (state.deployments.isEmpty()) return
    item(key = "deployments_header") {
        SectionHeader(
            title = stringResource(R.string.gh_deployments_title),
            expanded = state.deploymentsExpanded,
            onToggle = { tools.setDeploymentsExpanded(!state.deploymentsExpanded) },
        )
    }
    if (!state.deploymentsExpanded) return
    when {
        state.deploymentsLoading -> item(key = "deployments_loading") {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        }
        state.deploymentsError != null && state.deployments.isEmpty() -> item(key = "deployments_error") {
            ErrorBlock(state.deploymentsError.resolve(LocalContext.current), tools::loadDeployments)
        }
        state.deployments.isEmpty() -> item(key = "deployments_empty") {
            Muted(stringResource(R.string.gh_deployments_empty), Modifier.padding(16.dp))
        }
        else -> {
            val visible = if (showAll) state.deployments else state.deployments.take(COLLAPSED_COUNT)
            items(visible, key = { "deployment_${it.id}" }) { d ->
                DeploymentRow(d, busy = d.id in state.busyDeploymentIds, onDelete = { onDelete(d) })
            }
            if (!showAll && state.deployments.size > COLLAPSED_COUNT) {
                item(key = "deployments_more") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = onShowAll) { Text(stringResource(R.string.gh_show_all, state.deployments.size)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeploymentRow(d: GhDeployment, busy: Boolean, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.environment.ifBlank { "deployment" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(
                    listOf(d.ref, d.shortSha, d.latestState.orEmpty(), d.createdAt?.let { relative(it) }.orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" • "),
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDelete) {
                    Icon(
                        XIcons.Delete,
                        contentDescription = stringResource(R.string.gh_deployment_delete_desc, d.environment),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ---- shared small pieces ------------------------------------------------------------------------

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

internal fun dateOf(i: Instant): String =
    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(i.toEpochMilli()))

/** "Just now" / "8 minutes ago" / "3 hours ago" for the first day, then the plain date. */
internal fun timeAgoOrDate(i: Instant): String {
    val age = System.currentTimeMillis() - i.toEpochMilli()
    return when {
        age < DateUtils.MINUTE_IN_MILLIS -> "Just now"
        age < DateUtils.DAY_IN_MILLIS ->
            DateUtils.getRelativeTimeSpanString(i.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        else -> dateOf(i)
    }
}

internal fun relative(i: Instant): String =
    DateUtils.getRelativeTimeSpanString(i.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
