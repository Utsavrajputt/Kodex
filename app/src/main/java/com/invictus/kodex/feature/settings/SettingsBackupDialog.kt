package com.invictus.kodex.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.invictus.kodex.R
import com.invictus.kodex.core.backup.BackupCategory
import com.invictus.kodex.core.backup.BackupContents
import com.invictus.kodex.core.backup.InvalidBackupException
import com.invictus.kodex.core.backup.SettingsBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private data class BackupStatus(val text: String, val isError: Boolean)

@Composable
private fun categoryTitle(c: BackupCategory): String = stringResource(categoryTitleRes(c))

@Composable
private fun categorySubtitle(c: BackupCategory): String = stringResource(
    when (c) {
        BackupCategory.Appearance -> R.string.backup_cat_appearance_desc
        BackupCategory.Editor -> R.string.backup_cat_editor_desc
        BackupCategory.Behavior -> R.string.backup_cat_behavior_desc
        BackupCategory.Ai -> R.string.backup_cat_ai_desc
        BackupCategory.GitHub -> R.string.backup_cat_github_desc
        BackupCategory.AiChats -> R.string.backup_cat_chats_desc
    },
)

/**
 * Export / import of the app's settings, Morphe-style: tick the categories, then Export (writes a .zip) or
 * Import (reads one, then asks Replace or Merge). Failures are shown in red inside the dialog.
 */
@Composable
fun SettingsBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { SettingsBackup(context) }
    var selected by remember { mutableStateOf(BackupCategory.entries.toSet()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<BackupStatus?>(null) }
    var pending by remember { mutableStateOf<BackupContents?>(null) }

    val msgNoSelection = stringResource(R.string.backup_error_none_selected)
    val msgInvalid = stringResource(R.string.backup_error_invalid)
    val msgNothing = stringResource(R.string.backup_error_nothing)

    fun fail(prefixRes: Int, e: Throwable) {
        val detail = when (e) {
            is InvalidBackupException -> msgInvalid
            else -> e.message ?: e.javaClass.simpleName
        }
        status = BackupStatus(context.getString(prefixRes, detail), true)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val chosen = selected
        scope.launch {
            busy = true
            runCatching {
                val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open file")
                backup.export(out, chosen)
            }.onSuccess { chats ->
                status = BackupStatus(context.getString(R.string.backup_export_done, chosen.size, chats), false)
            }.onFailure { fail(R.string.backup_error_export, it) }
            busy = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
                input.use { backup.read(it) }
            }.onSuccess { found ->
                if (selected.intersect(found.categories).isEmpty()) status = BackupStatus(msgNothing, true)
                else pending = found
            }.onFailure { fail(R.string.backup_error_import, it) }
            busy = false
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.backup_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.backup_secrets_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                ) {
                    BackupCategory.entries.forEach { c ->
                        val on = c in selected
                        Surface(
                            onClick = { selected = if (on) selected - c else selected + c },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ),
                            enabled = !busy,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Checkbox(checked = on, onCheckedChange = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(categoryTitle(c), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        categorySubtitle(c),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                status?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            if (selected.isEmpty()) status = BackupStatus(msgNoSelection, true)
                            else { status = null; importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.backup_import)) }
                    FilledTonalButton(
                        onClick = {
                            if (selected.isEmpty()) status = BackupStatus(msgNoSelection, true)
                            else {
                                status = null
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                exportLauncher.launch("kodex-settings-$stamp.zip")
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.backup_export)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_close_dialog))
                }
            }
        }
    }

    pending?.let { contents ->
        val applicable = selected.intersect(contents.categories)
        fun apply(replace: Boolean) {
            pending = null
            scope.launch {
                busy = true
                runCatching { backup.import(contents, selected, replace) }
                    .onSuccess { r ->
                        val names = BackupCategory.entries.filter { it in r.categories }
                            .joinToString(", ") { c -> context.getString(categoryTitleRes(c)) }
                        val chats = if (BackupCategory.AiChats in r.categories) {
                            " " + context.getString(R.string.backup_import_chats, r.chatsAdded, r.chatsSkipped)
                        } else ""
                        status = BackupStatus(context.getString(R.string.backup_import_done, names) + chats, false)
                    }
                    .onFailure { fail(R.string.backup_error_import, it) }
                busy = false
            }
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_mode_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.backup_mode_body,
                        BackupCategory.entries.filter { it in applicable }.joinToString(", ") { c -> stringResource(categoryTitleRes(c)) },
                    ),
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { apply(replace = false) }) { Text(stringResource(R.string.backup_merge)) }
                    Button(onClick = { apply(replace = true) }) { Text(stringResource(R.string.backup_replace)) }
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private fun categoryTitleRes(c: BackupCategory): Int = when (c) {
    BackupCategory.Appearance -> R.string.backup_cat_appearance
    BackupCategory.Editor -> R.string.backup_cat_editor
    BackupCategory.Behavior -> R.string.backup_cat_behavior
    BackupCategory.Ai -> R.string.backup_cat_ai
    BackupCategory.GitHub -> R.string.backup_cat_github
    BackupCategory.AiChats -> R.string.backup_cat_chats
}
