package com.invictus.kodex.feature.project

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.feature.home.projectColor
import com.invictus.kodex.ui.icons.XIcons
import java.io.File

/**
 * "Recent projects" sheet of the workspace (the in-project "change project" switcher). Home keeps using
 * [OpenProjectSheet]; this one is only recents: the open project as a hero card, the others as rounded cards.
 * Tap opens, long-press (or the ⋮ button) has Back up / Copy path / Rename / Remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceProjectSheet(
    state: ProjectsUiState,
    currentRoot: File,
    snackbarHostState: SnackbarHostState,
    onEvent: (ProjectsEvent) -> Unit,
    onOpen: (File) -> Unit,
    onCopyPath: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { onEvent(ProjectsEvent.SheetOpened) }

    // The open project may not be in the recents list (yet): still show it as the hero.
    val current = state.recents.firstOrNull { it.file.path == currentRoot.path }
        ?: RecentItem(currentRoot, exists = true, isDeviceStorage = currentRoot.path == StorageRootPath)
    val others = state.recents.filter { it.file.path != currentRoot.path }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp)) {
                Text(
                    text = stringResource(R.string.sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.ws_sheet_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "current") {
                    CurrentProjectCard(
                        item = current,
                        isBackingUp = current.file.path in state.backingUp,
                        onOpen = { onOpen(current.file) },
                        onEvent = onEvent,
                        onCopyPath = onCopyPath,
                    )
                }
                if (others.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.ws_sheet_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    item(key = "others-label") {
                        Text(
                            text = stringResource(R.string.ws_sheet_others),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp),
                        )
                    }
                    items(items = others, key = { "r:" + it.file.path }) { item ->
                        ProjectCard(
                            item = item,
                            isBackingUp = item.file.path in state.backingUp,
                            onOpen = { onOpen(item.file) },
                            onEvent = onEvent,
                            onCopyPath = onCopyPath,
                        )
                    }
                }
            }
            SnackbarHost(snackbarHostState)
        }
    }

    state.renaming?.let { RenameProjectDialog(it, onEvent) }
}

private val StorageRootPath: String = Environment.getExternalStorageDirectory().path

/** "Documents › .Modxzone › Projects": where the project lives, without the long /storage/emulated/0 prefix. */
private fun parentLabel(item: RecentItem, internalLabel: String): String {
    if (item.isDeviceStorage) return item.file.path
    val parent = item.file.parent ?: return item.file.path
    val relative = parent.removePrefix(StorageRootPath).trim('/')
    return if (relative.isEmpty()) internalLabel else relative.replace("/", " › ")
}

@Composable
private fun CurrentProjectCard(
    item: RecentItem,
    isBackingUp: Boolean,
    onOpen: () -> Unit,
    onEvent: (ProjectsEvent) -> Unit,
    onCopyPath: (File) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val file = item.file
    val accent = projectColor(file.name)
    val onCard = MaterialTheme.colorScheme.onPrimaryContainer
    // Same height as the other cards; the tint and the "Open now" pill are what set it apart.
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderTile(accent = accent, size = 44, iconSize = 24, corner = 14)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (item.isDeviceStorage) stringResource(R.string.workspace_root_internal) else file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onCard,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = CircleShape, color = onCard.copy(alpha = 0.14f)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(XIcons.Check, contentDescription = null, tint = onCard, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.sheet_current),
                                style = MaterialTheme.typography.labelSmall,
                                color = onCard,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Text(
                    text = parentLabel(item, stringResource(R.string.workspace_root_internal)),
                    style = MaterialTheme.typography.bodySmall,
                    color = onCard.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            MoreButton(
                item = item,
                isBackingUp = isBackingUp,
                menuOpen = menuOpen,
                onMenuChange = { menuOpen = it },
                tint = onCard,
                onEvent = onEvent,
                onCopyPath = onCopyPath,
            )
        }
    }
}

@Composable
private fun ProjectCard(
    item: RecentItem,
    isBackingUp: Boolean,
    onOpen: () -> Unit,
    onEvent: (ProjectsEvent) -> Unit,
    onCopyPath: (File) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val file = item.file
    val accent = if (item.exists) projectColor(file.name) else MaterialTheme.colorScheme.outline
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().alpha(if (item.exists) 1f else 0.6f),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = { if (item.exists) onOpen() }, onLongClick = { menuOpen = true })
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderTile(accent = accent, size = 44, iconSize = 24, corner = 14)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.isDeviceStorage) stringResource(R.string.workspace_root_internal) else file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.exists) {
                        parentLabel(item, stringResource(R.string.workspace_root_internal))
                    } else {
                        stringResource(R.string.sheet_folder_missing, file.path)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.exists) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            MoreButton(
                item = item,
                isBackingUp = isBackingUp,
                menuOpen = menuOpen,
                onMenuChange = { menuOpen = it },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onEvent = onEvent,
                onCopyPath = onCopyPath,
            )
        }
    }
}

@Composable
private fun FolderTile(accent: Color, size: Int, iconSize: Int, corner: Int) {
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(accent.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(XIcons.Folder, contentDescription = null, tint = accent, modifier = Modifier.size(iconSize.dp))
    }
}

/** ⋮ button (or the backup spinner) with the project's action menu; long-press on the card opens the same menu. */
@Composable
private fun MoreButton(
    item: RecentItem,
    isBackingUp: Boolean,
    menuOpen: Boolean,
    onMenuChange: (Boolean) -> Unit,
    tint: Color,
    onEvent: (ProjectsEvent) -> Unit,
    onCopyPath: (File) -> Unit,
) {
    val file = item.file
    Box(contentAlignment = Alignment.Center) {
        if (isBackingUp) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { onMenuChange(true) }) {
                Icon(XIcons.MoreVert, contentDescription = null, tint = tint)
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuChange(false) }) {
            val close = { onMenuChange(false) }
            val canModify = item.exists && !item.isDeviceStorage
            if (canModify) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.recent_menu_backup)) },
                    enabled = !isBackingUp,
                    onClick = {
                        close()
                        onEvent(ProjectsEvent.Backup(file))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tree_menu_copy_path)) },
                onClick = {
                    close()
                    onCopyPath(file)
                },
            )
            if (canModify) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tree_menu_rename)) },
                    onClick = {
                        close()
                        onEvent(ProjectsEvent.StartRename(file))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.recent_menu_remove)) },
                onClick = {
                    close()
                    onEvent(ProjectsEvent.RemoveRecent(file.path))
                },
            )
        }
    }
}
