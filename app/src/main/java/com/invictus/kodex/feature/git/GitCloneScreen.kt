package com.invictus.kodex.feature.git

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    var showStopDialog by remember { mutableStateOf(false) }

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

    // Leaving mid-clone would silently throw away the download, so ask first.
    BackHandler(enabled = state.cloning) { showStopDialog = true }
    val requestBack: () -> Unit = { if (state.cloning) showStopDialog = true else onBack() }

    val fieldsLocked = state.cloning || state.completed

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.clone_stop_title)) },
            text = { Text(stringResource(R.string.clone_stop_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopDialog = false
                        viewModel.onEvent(GitCloneEvent.Cancel)
                        onBack()
                    },
                ) { Text(stringResource(R.string.clone_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) { Text(stringResource(R.string.clone_stop_keep)) }
            },
        )
    }
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
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.clone_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CloneForm(state = state, locked = fieldsLocked, onEvent = viewModel::onEvent)

            if (state.cloning || state.logLines.isNotEmpty()) {
                CloneProgressCard(state = state)
            }

            val buttonModifier = Modifier.fillMaxWidth().height(56.dp)
            when {
                state.completed -> Button(
                    onClick = { viewModel.onEvent(GitCloneEvent.Done) },
                    modifier = buttonModifier,
                ) {
                    Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clone_finish_button))
                }
                state.cloning -> OutlinedButton(
                    onClick = { viewModel.onEvent(GitCloneEvent.Cancel) },
                    enabled = !state.cancelling,
                    modifier = buttonModifier,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = if (state.cancelling) 0.4f else 1f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(XIcons.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (state.cancelling) R.string.clone_cancelling else R.string.clone_cancel))
                }
                else -> Button(
                    onClick = { viewModel.onEvent(GitCloneEvent.Start) },
                    enabled = state.url.isNotBlank() && state.folderName.isNotBlank(),
                    modifier = buttonModifier,
                ) {
                    Icon(XIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.clone_start))
                }
            }
        }
    }
}

@Composable
private fun CloneForm(
    state: GitCloneViewModel.UiState,
    locked: Boolean,
    onEvent: (GitCloneEvent) -> Unit,
) {
    val pickParentFolder = rememberFolderPickerLauncher(
        onPicked = { path -> onEvent(GitCloneEvent.ParentChange(path)) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CloneField(
            value = state.url,
            onChange = { onEvent(GitCloneEvent.UrlChange(it)) },
            label = R.string.clone_url_hint,
            enabled = !locked,
            leading = XIcons.CloudDownload,
            keyboardType = KeyboardType.Uri,
        )
        CloneField(
            value = state.folderName,
            onChange = { onEvent(GitCloneEvent.FolderChange(it)) },
            label = R.string.clone_folder_hint,
            enabled = !locked,
            leading = XIcons.Folder,
        )
        CloneField(
            value = state.parentPath,
            onChange = { onEvent(GitCloneEvent.ParentChange(it)) },
            label = R.string.clone_parent_hint,
            enabled = !locked,
            trailing = {
                IconButton(onClick = pickParentFolder, enabled = !locked) {
                    Icon(XIcons.FolderOpen, contentDescription = stringResource(R.string.clone_browse_folder))
                }
            },
        )
        CloneField(
            value = state.branch,
            onChange = { onEvent(GitCloneEvent.BranchChange(it)) },
            label = R.string.clone_branch_hint,
            enabled = !locked,
            leading = XIcons.Branch,
        )
    }
}

@Composable
private fun CloneField(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    TextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(label)) },
        leadingIcon = leading?.let { icon -> { Icon(icon, contentDescription = null) } },
        trailingIcon = trailing,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container.copy(alpha = 0.55f),
        ),
    )
}

@Composable
private fun CloneProgressCard(state: GitCloneViewModel.UiState) {
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

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${(animated * 100).roundToInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val barModifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
            if (state.cloning && fraction == null) {
                LinearProgressIndicator(modifier = barModifier)
            } else {
                LinearProgressIndicator(progress = { animated }, modifier = barModifier)
            }
            CloneSteps(current = stage, completed = state.completed)
            Text(
                text = stringResource(R.string.clone_log_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CloneLog(lines = state.logLines, modifier = Modifier.fillMaxWidth().height(180.dp))
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
    val scheme = MaterialTheme.colorScheme
    val container = when {
        done -> scheme.primaryContainer
        active -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val content = when {
        done -> scheme.onPrimaryContainer
        active -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    val pill = RoundedCornerShape(50)
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(pill)
            .background(container)
            .then(if (active) Modifier.border(1.5.dp, scheme.primary, pill) else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
            .clip(RoundedCornerShape(16.dp))
            .background(TerminalBackground),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(12.dp),
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
