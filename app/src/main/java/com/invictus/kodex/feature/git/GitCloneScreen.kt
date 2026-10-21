package com.invictus.kodex.feature.git

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.core.git.GitCloneLogLine
import com.invictus.kodex.core.git.GitCloneProgress
import com.invictus.kodex.core.git.GitCloneStage
import com.invictus.kodex.core.git.overallFraction
import com.invictus.kodex.core.git.stage
import com.invictus.kodex.github.ui.GhCard
import com.invictus.kodex.github.ui.ghPalette
import com.invictus.kodex.ui.components.rememberFolderPickerLauncher
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitCloneScreen(
    onBack: () -> Unit,
    onOpenGitHubSettings: () -> Unit = {},
    viewModel: GitCloneViewModel = viewModel(factory = GitCloneViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pal = ghPalette()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is GitCloneViewModel.Effect.Message ->
                    scope.launch { snackbarHostState.showSnackbar(effect.text.resolve(context)) }
                GitCloneViewModel.Effect.NavigateBack -> onBack()
                GitCloneViewModel.Effect.OpenGitHubSettings -> onOpenGitHubSettings()
            }
        }
    }

    val fieldsLocked = state.cloning || state.completed

    state.error?.let { details ->
        GitErrorDialog(details = details, onDismiss = { viewModel.onEvent(GitCloneEvent.DismissError) })
    }
    state.privateRepoHost?.let { host ->
        PrivateRepoDialog(
            host = host,
            onConfirm = { username, token ->
                viewModel.onEvent(GitCloneEvent.ConfirmPrivateToken(username, token))
            },
            onCancel = { viewModel.onEvent(GitCloneEvent.DismissPrivateRepoDialog) },
            onConfigure = { viewModel.onEvent(GitCloneEvent.ConfigureCredentials) },
        )
    }

    Scaffold(
        modifier = Modifier.background(pal.screen),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = pal.onCard,
                    navigationIconContentColor = pal.onCard,
                    actionIconContentColor = pal.onCard,
                ),
                title = {
                    Text(
                        stringResource(R.string.clone_title),
                        maxLines = 1,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                },
                // Going back never stops a running clone; it keeps going in the background.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    when {
                        state.completed -> ActionPill(
                            text = stringResource(R.string.clone_finish_button),
                            icon = XIcons.Check,
                            brush = pal.installBrush,
                            content = pal.onInstall,
                            enabled = true,
                        ) { viewModel.onEvent(GitCloneEvent.Done) }
                        state.cloning -> ActionPill(
                            text = stringResource(if (state.cancelling) R.string.clone_cancelling else R.string.clone_cancel),
                            icon = XIcons.Close,
                            brush = SolidColor(pal.dangerAction),
                            content = pal.onDangerAction,
                            enabled = !state.cancelling,
                        ) { viewModel.onEvent(GitCloneEvent.Cancel) }
                        else -> ActionPill(
                            text = stringResource(R.string.clone_start),
                            icon = XIcons.CloudDownload,
                            brush = pal.installBrush,
                            content = pal.onInstall,
                            enabled = state.url.isNotBlank() && state.folderName.isNotBlank(),
                        ) { viewModel.onEvent(GitCloneEvent.Start) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
        ) {
            SourceCard(state = state, locked = fieldsLocked, onEvent = viewModel::onEvent)
            DestinationCard(state = state, locked = fieldsLocked, onEvent = viewModel::onEvent)
            if (state.cloning || state.logLines.isNotEmpty()) {
                ProgressCard(state = state)
            }
        }
    }
}

/** Compact pill in the top bar: Clone / Cancel / Done. */
@Composable
private fun ActionPill(
    text: String,
    icon: ImageVector,
    brush: Brush,
    content: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(end = 10.dp)
            .height(38.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(brush)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun CardTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = ghPalette().onCard,
        modifier = modifier,
    )
}

@Composable
private fun SourceCard(state: GitCloneViewModel.UiState, locked: Boolean, onEvent: (GitCloneEvent) -> Unit) {
    val p = ghPalette()
    GhCard(p.card, p.cardBorder) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(R.string.clone_card_source)
            CloneInput(
                value = state.url,
                onChange = { onEvent(GitCloneEvent.UrlChange(it)) },
                hint = R.string.clone_url_hint,
                leading = XIcons.CloudDownload,
                enabled = !locked,
                keyboardType = KeyboardType.Uri,
            )
            CloneInput(
                value = state.branch,
                onChange = { onEvent(GitCloneEvent.BranchChange(it)) },
                hint = R.string.clone_branch_hint,
                leading = XIcons.Branch,
                enabled = !locked,
            )
        }
    }
}

@Composable
private fun DestinationCard(state: GitCloneViewModel.UiState, locked: Boolean, onEvent: (GitCloneEvent) -> Unit) {
    val p = ghPalette()
    val pickParentFolder = rememberFolderPickerLauncher(
        onPicked = { path -> onEvent(GitCloneEvent.ParentChange(path)) },
    )
    GhCard(p.card, p.cardBorder) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(R.string.clone_card_destination)
            CloneInput(
                value = state.folderName,
                onChange = { onEvent(GitCloneEvent.FolderChange(it)) },
                hint = R.string.clone_folder_hint,
                leading = XIcons.Edit,
                enabled = !locked,
            )
            CloneInput(
                value = state.parentPath,
                onChange = { onEvent(GitCloneEvent.ParentChange(it)) },
                hint = R.string.clone_parent_hint,
                leading = XIcons.Folder,
                enabled = !locked,
                trailing = {
                    IconButton(onClick = pickParentFolder, enabled = !locked, modifier = Modifier.size(36.dp)) {
                        Icon(
                            XIcons.FolderOpen,
                            contentDescription = stringResource(R.string.clone_browse_folder),
                            tint = p.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
        }
    }
}

/** Slim 44dp field in the same tile style as the GitHub workflow pickers. */
@Composable
private fun CloneInput(
    value: String,
    onChange: (String) -> Unit,
    @StringRes hint: Int,
    leading: ImageVector,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val p = ghPalette()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(shape)
            .background(p.tileFill)
            .border(1.dp, p.pickerBorder, shape)
            .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(leading, contentDescription = null, tint = p.onCardMuted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.onCardMuted,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.onCard),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

@Composable
private fun ProgressCard(state: GitCloneViewModel.UiState) {
    val p = ghPalette()
    val progress = GitCloneProgress(state.logLines)
    val stage = progress.stage()
    val fraction = if (state.completed) 1f else progress.overallFraction()
    val animated by animateFloatAsState(targetValue = fraction ?: 0f, label = "clone-progress")

    val status = when {
        state.completed -> stringResource(R.string.clone_status_done)
        state.cancelling -> stringResource(R.string.clone_cancelling)
        state.cloning -> stringResource(
            when (stage) {
                GitCloneStage.Preparing -> R.string.clone_stage_preparing
                GitCloneStage.Receiving -> R.string.clone_stage_receiving
                GitCloneStage.Resolving -> R.string.clone_stage_resolving
            },
        )
        else -> stringResource(R.string.clone_status_stopped)
    }

    GhCard(p.card, p.cardBorder) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CardTitle(R.string.clone_card_progress, Modifier.weight(1f))
                Text(status, style = MaterialTheme.typography.labelLarge, color = p.onCardMuted, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${(animated * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = p.accent,
                    modifier = Modifier.width(54.dp),
                )
                val bar = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50))
                if (state.cloning && fraction == null) {
                    LinearProgressIndicator(modifier = bar, color = p.accent, trackColor = p.tileFill)
                } else {
                    LinearProgressIndicator(progress = { animated }, modifier = bar, color = p.accent, trackColor = p.tileFill)
                }
            }
            CloneSteps(current = stage, completed = state.completed)
            CloneLog(lines = state.logLines, modifier = Modifier.fillMaxWidth().height(150.dp))
            if (state.cloning) {
                Text(
                    stringResource(R.string.clone_background_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onCardMuted,
                )
            }
        }
    }
}

@Composable
private fun CloneSteps(current: GitCloneStage, completed: Boolean) {
    val steps = listOf(
        GitCloneStage.Preparing to R.string.clone_step_prepare,
        GitCloneStage.Receiving to R.string.clone_step_receive,
        GitCloneStage.Resolving to R.string.clone_step_resolve,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        steps.forEach { (stage, label) ->
            CloneStageChip(
                label = stringResource(label),
                done = completed || stage.ordinal < current.ordinal,
                active = !completed && stage == current,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CloneStageChip(label: String, done: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val p = ghPalette()
    val container = if (done) p.accent.copy(alpha = 0.2f) else p.tileFill
    val content = when {
        done -> p.accent
        active -> p.onCard
        else -> p.onCardMuted
    }
    val pill = RoundedCornerShape(50)
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(pill)
            .background(container)
            .border(1.dp, if (active) p.accent else p.pickerBorder, pill)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (done) {
            Icon(XIcons.Check, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/** Termux/native-git style scrolling transcript: dark, monospace, auto-scrolls to the newest line. */
@Composable
private fun CloneLog(lines: List<GitCloneLogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(TerminalBackground),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines, key = { index, line -> "$index-${line.taskName}" }) { _, line ->
                Text(
                    text = line.text,
                    color = if (line.taskName == "Total") TerminalHighlight else TerminalForeground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private val TerminalBackground = Color(0xFF0B0F14)
private val TerminalForeground = Color(0xFFD7DDE3)
private val TerminalHighlight = Color(0xFF6FCF97)
