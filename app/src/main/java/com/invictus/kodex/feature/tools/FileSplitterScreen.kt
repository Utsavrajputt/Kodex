package com.invictus.kodex.feature.tools

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import com.invictus.kodex.core.project.RecentProject
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.ui.ClipboardHelper
import com.invictus.kodex.ui.components.rememberFolderPickerLauncher
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

private val SIZE_CHOICES = listOf(10, 20, 25, 30, 50, 100)
private const val MAX_LISTED_PARTS = 8

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FileSplitterScreen(
    onBack: () -> Unit,
    vm: FileSplitterViewModel = viewModel(factory = FileSplitterViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val field = RoundedCornerShape(16.dp)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pickFile) }
    val pickSourceFolder = rememberFolderPickerLauncher(onPicked = vm::pickFolder)
    val recents by vm.recents.collectAsStateWithLifecycle()
    var projectDialog by remember { mutableStateOf(false) }
    val pickDestFolder = rememberFolderPickerLauncher(onPicked = vm::setOutDir)

    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            if (e is FileSplitterViewModel.Effect.Message) scope.launch { snackbar.showSnackbar(e.text.resolve(context)) }
        }
    }

    if (projectDialog) {
        ProjectChooserDialog(
            recents = recents,
            onPick = { path -> projectDialog = false; vm.pickFolder(path) },
            onBrowse = { projectDialog = false; pickSourceFolder() },
            onDismiss = { projectDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_split_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !s.running) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- source ----
            Card(R.string.split_section_source) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = !s.running, modifier = Modifier.weight(1f)) {
                        Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.split_choose_file))
                    }
                    OutlinedButton(onClick = { projectDialog = true }, enabled = !s.running, modifier = Modifier.weight(1f)) {
                        Icon(XIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.split_choose_folder))
                    }
                }
                when (val src = s.source) {
                    null -> Text(stringResource(R.string.split_nothing_chosen), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    is SplitSource.FileSource -> {
                        Text(src.name, style = MaterialTheme.typography.titleSmall)
                        if (src.size >= 0) Text(Formatter.formatFileSize(context, src.size), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                    is SplitSource.FolderSource -> {
                        Text(src.dir.absolutePath, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            when {
                                s.measuring -> stringResource(R.string.split_measuring)
                                s.folderBytes != null -> stringResource(R.string.split_folder_size, Formatter.formatFileSize(context, s.folderBytes!!))
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                        )
                        Text(stringResource(R.string.split_folder_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }

            // ---- part size ----
            Card(R.string.split_section_size) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SIZE_CHOICES.forEach { mb ->
                        FilterChip(
                            selected = !s.custom && s.sizeMb == mb, enabled = !s.running,
                            onClick = { vm.edit { copy(sizeMb = mb, custom = false) } }, label = { Text("$mb") },
                        )
                    }
                    FilterChip(
                        selected = s.custom, enabled = !s.running,
                        onClick = { vm.edit { copy(custom = true) } }, label = { Text(stringResource(R.string.split_custom)) },
                    )
                }
                if (s.custom) {
                    OutlinedTextField(
                        value = s.customText, onValueChange = { t -> vm.edit { copy(customText = t.filter(Char::isDigit).take(4)) } },
                        label = { Text(stringResource(R.string.split_custom_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, shape = field, enabled = !s.running, modifier = Modifier.fillMaxWidth(),
                    )
                }
                s.estimatedParts?.let { n ->
                    Text(
                        if (n == 1) stringResource(R.string.split_estimate_one) else stringResource(R.string.split_estimate, n),
                        style = MaterialTheme.typography.bodyMedium, color = cs.primary,
                    )
                }
            }

            // ---- folder options ----
            if (s.source is SplitSource.FolderSource) {
                Card(R.string.split_section_folder) {
                    ToggleRow(R.string.split_include_git, R.string.split_include_git_desc, s.includeGit, !s.running) { vm.toggleGit(it) }
                    ToggleRow(R.string.split_skip_build, R.string.split_skip_build_desc, s.skipBuild, !s.running) { vm.toggleBuild(it) }
                }
            }

            // ---- destination ----
            Card(R.string.split_section_dest) {
                OutlinedTextField(
                    value = s.outDir, onValueChange = vm::setOutDir, enabled = !s.running,
                    leadingIcon = { Icon(XIcons.Folder, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = pickDestFolder, enabled = !s.running) {
                            Icon(XIcons.FolderOpen, contentDescription = stringResource(R.string.split_browse), tint = cs.primary)
                        }
                    },
                    singleLine = true, shape = field, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.split_dest_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            // ---- run ----
            if (s.running) {
                val total = s.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (s.doneBytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.split_progress, Formatter.formatFileSize(context, s.doneBytes), Formatter.formatFileSize(context, total)),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.split_progress_unknown, Formatter.formatFileSize(context, s.doneBytes)),
                        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.split_cancel)) }
            } else {
                Button(onClick = vm::start, enabled = s.source != null, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(XIcons.Scissors, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.split_button))
                }
            }

            // ---- result ----
            s.result?.let { r ->
                val unixCmd = "cat \"${r.base}\".* > \"${r.base}\""
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.primaryContainer).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(XIcons.CheckCircle, contentDescription = null, tint = cs.onPrimaryContainer)
                        Text(stringResource(R.string.split_done_title, r.parts.size), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.split_done_in, r.dir), style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                    r.parts.take(MAX_LISTED_PARTS).forEach { p ->
                        Text(
                            "${p.name}   ${Formatter.formatFileSize(context, p.size)}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onPrimaryContainer,
                        )
                    }
                    if (r.parts.size > MAX_LISTED_PARTS) {
                        Text("\u2026 +${r.parts.size - MAX_LISTED_PARTS}", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.split_join_title), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer, modifier = Modifier.padding(top = 6.dp))
                    Text(unixCmd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onPrimaryContainer)
                    Text("copy /b \"${r.base}.*\" \"${r.base}\"", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onPrimaryContainer)
                    OutlinedButton(
                        onClick = {
                            ClipboardHelper.copy(context, "join", unixCmd, sensitive = false)
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.split_join_copied)) }
                        },
                    ) { Text(stringResource(R.string.split_copy_join)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Pick a project to split: the recently opened ones, or any folder from storage. */
@Composable
private fun ProjectChooserDialog(recents: List<RecentProject>, onPick: (String) -> Unit, onBrowse: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 560.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_choose_project_title), style = MaterialTheme.typography.titleLarge)
                if (recents.isEmpty()) {
                    Text(stringResource(R.string.split_no_recents), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                } else {
                    Text(stringResource(R.string.split_recent_projects), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(recents, key = { it.file.path }) { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(r.file.path) }.padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(XIcons.Folder, contentDescription = null, tint = cs.primary, modifier = Modifier.size(26.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(r.file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(r.file.parent.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Icon(XIcons.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.split_choose_storage))
                }
            }
        }
    }
}

@Composable
private fun Card(title: Int, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun ToggleRow(title: Int, desc: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
