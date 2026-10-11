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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.invictus.kodex.github.data.ApkCandidate
import com.invictus.kodex.github.data.ApkResolver
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
    val p = ghPalette()
    val workflow = tools.effectiveWorkflow(workflows, state.selectedWorkflowId)
    val ref = state.selectedBranch ?: defaultBranch
    LaunchedEffect(workflow?.id, ref) {
        if (workflow != null && ref != null) tools.ensureDispatchInfo(workflow, ref)
    }
    val info = state.info
    val supported = (info as? DispatchInfoState.Loaded)?.info?.supported
    val canRun = workflow != null && ref != null && !state.dispatching &&
        (supported == true || info is DispatchInfoState.Failed)

    GhCard(p.card, p.cardBorder) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.gh_run_title),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.onCard,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerButton(
                    label = workflow?.name ?: stringResource(R.string.gh_run_pick_workflow),
                    leading = XIcons.Android,
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
                    leading = XIcons.Branch,
                    enabled = ref != null || state.branches.isNotEmpty(),
                    modifier = Modifier.weight(0.7f),
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
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = p.accent,
                        contentColor = p.onInstall,
                        disabledContainerColor = p.accent.copy(alpha = 0.35f),
                        disabledContentColor = p.onInstall.copy(alpha = 0.6f),
                    ),
                ) {
                    if (state.dispatching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.onInstall)
                    } else {
                        Icon(XIcons.PlayArrow, contentDescription = stringResource(R.string.gh_run_action), modifier = Modifier.size(26.dp))
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
                Spacer(Modifier.height(6.dp))
                Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = p.onCardMuted)
            }
        }
    }
}

@Composable
private fun PickerButton(
    label: String,
    leading: ImageVector,
    enabled: Boolean,
    modifier: Modifier,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val p = ghPalette()
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            shape = CircleShape,
            border = BorderStroke(1.dp, p.pickerBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = p.onCard,
                disabledContentColor = p.onCard.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth().height(42.dp),
            contentPadding = PaddingValues(horizontal = 10.dp),
        ) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp), tint = p.onCardMuted)
            Spacer(Modifier.width(6.dp))
            Text(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Icon(XIcons.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

// ---- Latest APK card (plan 6.1 card 3 / 8.3 / 8.4) ----------------------------------------------

/** The card is only worth showing with a build to install, a downloaded APK, or an error with Retry; "none yet" / expired / loading hide it. */
internal fun apkCardVisible(state: GitHubToolsState): Boolean {
    if (state.apk is ApkCardState.Found || state.apk is ApkCardState.Error) return true
    val repoArtifactIds = state.artifacts.mapTo(HashSet()) { it.id }
    return state.downloaded.any { it.artifactId in repoArtifactIds }
}

@Composable
fun ApkCard(
    state: GitHubToolsState,
    tools: GitHubToolsController,
    onRunShortcut: () -> Unit,
    projectPath: String,
    ownerLogin: String? = null,
) {
    val p = ghPalette()
    val context = LocalContext.current
    // The latest successful build: one block per variant (Light, Full, ...), each with its own Download /
    // progress / Install / Delete. An older downloaded file of this repo is only a fallback when no run
    // artifact is available any more (expired / none / error).
    val apk = state.apk
    val found = apk as? ApkCardState.Found
    // The APK cache is shared by every project, so the fallback only considers files whose artifact belongs to THIS repo.
    val repoArtifactIds = state.artifacts.mapTo(HashSet()) { it.id }
    val fallbackLocal = if (found == null) state.downloaded.firstOrNull { it.artifactId in repoArtifactIds } else null
    val busy = state.transfer?.paused == false || state.installing || state.pendingSave != null

    GhCard(p.apkCard, p.apkBorder) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                found != null -> found.variants.forEachIndexed { index, a ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(p.onCard.copy(alpha = 0.18f)))
                    ApkVariant(a, found.candidate.universal, state, tools, projectPath, ownerLogin, busy)
                }
                fallbackLocal != null -> {
                    ApkHeader(
                        projectPath, ownerLogin, apkTitle(fallbackLocal.label),
                        listOf(
                            Formatter.formatShortFileSize(context, fallbackLocal.sizeBytes),
                            timeAgoOrDate(Instant.ofEpochMilli(fallbackLocal.modifiedAt)),
                        ).filter { it.isNotBlank() }.joinToString(" \u2022 "),
                    )
                    ApkActions(fallbackLocal, state.installing, busy, tools)
                }
                else -> {
                    ApkHeader(projectPath, ownerLogin, stringResource(R.string.gh_apk_title), "")
                    if (apk == ApkCardState.Loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = p.accent, trackColor = p.onCard.copy(alpha = 0.12f))
                    } else {
                        val msg = when (apk) {
                            is ApkCardState.Error -> apk.message.resolve(context)
                            is ApkCardState.AllExpired -> apk.newestAt?.let { stringResource(R.string.gh_apk_expired_on, dateOf(it)) }
                                ?: stringResource(R.string.gh_apk_expired)
                            else -> stringResource(R.string.gh_apk_none)
                        }
                        Text(msg, style = MaterialTheme.typography.bodyMedium, color = if (apk is ApkCardState.Error) p.danger else p.onCard)
                        FilledTonalButton(
                            onClick = if (apk is ApkCardState.Error) tools::loadArtifactsAndApk else onRunShortcut,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = p.neutralAction, contentColor = p.onNeutralAction),
                        ) {
                            Text(stringResource(if (apk is ApkCardState.Error) R.string.gh_retry else R.string.gh_run_action_label))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApkHeader(projectPath: String, ownerLogin: String?, title: String, facts: String) {
    val p = ghPalette()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProjectIcon(projectPath, size = 48.dp, corner = 14.dp, ownerLogin = ownerLogin)
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (facts.isNotBlank()) {
                Text(
                    facts, style = MaterialTheme.typography.bodySmall,
                    color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One variant (e.g. "Xmd-full-arm64-e84d595") of the latest build with its own action row. */
@Composable
private fun ApkVariant(
    a: GhArtifact,
    universal: Boolean,
    state: GitHubToolsState,
    tools: GitHubToolsController,
    projectPath: String,
    ownerLogin: String?,
    busy: Boolean,
) {
    val p = ghPalette()
    val context = LocalContext.current
    val local = state.downloaded.firstOrNull { it.artifactId == a.id }
    val transfer = state.transfer?.takeIf { it.fromApkCard && it.artifactId == a.id }
    val ready = state.ready?.takeIf { it.fromApkCard && it.artifactId == a.id }
    // Freshly downloaded but not yet listed: same buttons, no details panel.
    val justDownloaded = ready?.let { DownloadedApk(it.artifactId, it.artifactName, it.file, it.file.length(), System.currentTimeMillis()) }
    val facts = listOf(Formatter.formatShortFileSize(context, a.sizeBytes), a.createdAt?.let { timeAgoOrDate(it) }.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" \u2022 ")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ApkHeader(projectPath, ownerLogin, apkBuildName(a.name, a.shortSha), facts)
        when {
            transfer != null -> TransferStatus(
                downloaded = transfer.downloaded, total = transfer.total, etaSeconds = transfer.etaSeconds, paused = transfer.paused,
                onPauseResume = if (transfer.paused) tools::resumeTransfer else tools::pauseTransfer,
                onCancel = tools::cancelTransfer,
                accent = p.accent, track = p.onCard.copy(alpha = 0.12f), textColor = p.onCardMuted,
                canPause = (transfer.progress ?: 0f) < 1f,
            )
            local != null -> ApkActions(local, state.installing, busy, tools)
            justDownloaded != null -> ApkActions(justDownloaded, state.installing, busy, tools)
            else -> GhPrimaryButton(
                onClick = { tools.downloadApk(ApkCandidate(a, universal)) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(XIcons.Download, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.onInstall)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.gh_apk_download),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.onInstall,
                )
            }
        }
    }
}

/** APK already on the device: big Install, plus open-folder (save) and a clearly separate Delete. */
@Composable
private fun ApkActions(d: DownloadedApk, installing: Boolean, busy: Boolean, tools: GitHubToolsController) {
    val p = ghPalette()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        GhPrimaryButton(
            onClick = { tools.installDownloaded(d) },
            enabled = !busy,
            modifier = Modifier.weight(1f),
        ) {
            if (installing) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = p.onInstall)
            } else {
                Icon(XIcons.Download, contentDescription = null, modifier = Modifier.size(22.dp), tint = p.onInstall)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.gh_install),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.onInstall,
                )
            }
        }
        GhTileButton(
            icon = XIcons.Folder,
            description = stringResource(R.string.gh_save_to_folder),
            container = p.neutralAction, tint = p.onNeutralAction,
            enabled = !busy,
            onClick = { tools.saveDownloaded(d) },
        )
        GhTileButton(
            icon = XIcons.Delete,
            description = stringResource(R.string.action_delete),
            container = p.dangerAction, tint = p.onDangerAction,
            enabled = !installing,
            onClick = { tools.deleteDownloaded(d) },
        )
    }
}

/** Verified APK waiting for the user's tap (plan 8.4): facts, warnings, Install / Save (Close is the dialog button). */
@Composable
fun ReadyApkBody(
    ready: ReadyApk,
    installing: Boolean,
    onInstall: () -> Unit,
    onSave: () -> Unit,
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
        Spacer(Modifier.height(8.dp))
        // Equal-width pills on one row; Close lives in the dialog's own button slot so nothing wraps.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onInstall,
                enabled = !installing && !InstallDecision.isBlocked(r.kind),
                modifier = Modifier.weight(1f).height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (installing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.gh_install), maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onSave,
                modifier = Modifier.weight(1f).height(44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(stringResource(R.string.gh_save_to_folder), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---- Artifacts section: the rest of the latest build (the ARM64 ones are in the APK card) -------

/** Live artifacts of the latest build that the APK card does not already show (armeabi, universal, ...). */
internal fun extraArtifacts(state: GitHubToolsState, now: Instant = Instant.now()): List<GhArtifact> {
    val found = state.apk as? ApkCardState.Found
    val build = found?.let { ApkResolver.buildOf(state.artifacts, it.candidate.artifact) }
        ?: ApkResolver.newestBuild(state.artifacts, now)
    val cardIds: Set<Long> = found?.variants?.map { it.id }?.toSet() ?: emptySet()
    return build
        .filter { !it.isExpiredAt(now) && it.id !in cardIds && !ApkResolver.isArm64(it.name) }
        .sortedBy { it.name.lowercase() }
}

fun LazyListScope.artifactsSection(
    state: GitHubToolsState,
    tools: GitHubToolsController,
    onDelete: (GhArtifact) -> Unit,
) {
    val extras = extraArtifacts(state)
    if (extras.isEmpty()) return
    item(key = "artifacts_header") {
        SectionHeader(
            title = stringResource(R.string.gh_artifacts_title),
            expanded = state.artifactsExpanded,
            onToggle = { tools.setArtifactsExpanded(!state.artifactsExpanded) },
        )
    }
    if (!state.artifactsExpanded) return
    items(extras, key = { "artifact_${it.id}" }) { a ->
        ArtifactRow(a, state, tools, onDelete = { onDelete(a) })
    }
}

/** Row: file tile + name / size \u2022 time, then Download (or Install once on the device) and Delete as rounded tiles. */
@Composable
private fun ArtifactRow(a: GhArtifact, state: GitHubToolsState, tools: GitHubToolsController, onDelete: () -> Unit) {
    val p = ghPalette()
    val context = LocalContext.current
    val local = state.downloaded.firstOrNull { it.artifactId == a.id }
    val transfer = state.transfer?.takeIf { !it.fromApkCard && it.artifactId == a.id }
    val busy = state.transfer?.paused == false || state.installing || state.pendingSave != null
    val deleting = a.id in state.busyArtifactIds
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.runCard)
            .border(1.dp, p.cardBorder, shape)
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.onCard.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XIcons.InsertDriveFile, contentDescription = null, tint = p.onCard, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    a.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(Formatter.formatShortFileSize(context, a.sizeBytes), a.createdAt?.let { timeAgoOrDate(it) }.orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" \u2022 "),
                    style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1,
                )
            }
            if (deleting) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (local != null) {
                        Row(
                            Modifier
                                .height(40.dp)
                                .alpha(if (busy) 0.45f else 1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(p.installBrush)
                                .clickable(enabled = !busy, role = Role.Button) { tools.installDownloaded(local) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.gh_install),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.onInstall,
                            )
                        }
                    } else if (transfer == null) {
                        GhTileButton(
                            icon = XIcons.Download,
                            description = stringResource(R.string.gh_apk_download),
                            container = p.tileFill, tint = p.accent,
                            enabled = !busy, size = 40.dp, iconSize = 22.dp,
                            onClick = { if (ApkResolver.isApkArtifact(a.name)) tools.installFromArtifact(a) else tools.saveArtifact(a) },
                        )
                    }
                    GhTileButton(
                        icon = XIcons.Delete,
                        description = stringResource(R.string.action_delete),
                        container = p.dangerAction, tint = p.onDangerAction,
                        enabled = !busy, size = 40.dp, iconSize = 22.dp,
                        onClick = onDelete,
                    )
                }
            }
        }
        if (transfer != null) {
            TransferStatus(
                downloaded = transfer.downloaded, total = transfer.total, etaSeconds = transfer.etaSeconds, paused = transfer.paused,
                onPauseResume = if (transfer.paused) tools::resumeTransfer else tools::pauseTransfer,
                onCancel = tools::cancelTransfer,
                accent = p.accent, track = p.onCard.copy(alpha = 0.12f), textColor = p.onCardMuted,
                modifier = Modifier.padding(top = 8.dp),
                canPause = (transfer.progress ?: 0f) < 1f,
            )
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
