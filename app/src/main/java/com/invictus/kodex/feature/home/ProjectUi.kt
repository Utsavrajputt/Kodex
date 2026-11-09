package com.invictus.kodex.feature.home

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.feature.project.RecentItem
import com.invictus.kodex.feature.workspace.FileTreeEvent
import com.invictus.kodex.feature.workspace.FileTreeViewModel
import com.invictus.kodex.ui.components.expressiveClickable
import com.invictus.kodex.ui.icons.XIcons
import java.io.File
import kotlinx.coroutines.launch

/** Fixed palette for project folder tiles; a project's color comes from a hash of its name. */
private val ProjectPalette = listOf(
    Color(0xFF7C6CFF), // purple
    Color(0xFF4FC3F7), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFFFB74D), // amber
    Color(0xFFF06292), // pink
    Color(0xFF26C6A9), // teal
)

/** Stable per-name color: same project always gets the same tile, independent of list order. */
internal fun projectColor(name: String): Color =
    ProjectPalette[(name.hashCode() and Int.MAX_VALUE) % ProjectPalette.size]

/** Case-insensitive match on project name or path. */
internal fun RecentItem.matches(query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || file.name.contains(q, ignoreCase = true) || file.path.contains(q, ignoreCase = true)
}

/**
 * Returns a function that opens a project and navigates to the workspace only once the project is the
 * tree's root and saved as the latest recent: the workspace (a fresh ViewModel there) restores the latest
 * recent, so navigating earlier could open the previously opened project instead.
 */
@Composable
internal fun rememberProjectOpener(
    onProjectOpened: () -> Unit,
    fileTreeViewModel: FileTreeViewModel = viewModel(factory = FileTreeViewModel.Factory),
    beforeOpen: () -> Unit = {},
): (File) -> Unit {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    val latestOnOpened by rememberUpdatedState(onProjectOpened)
    return { dir ->
        if (!opening) {
            beforeOpen()
            opening = true
            scope.launch {
                try {
                    if (fileTreeViewModel.openProjectAndWait(dir)) latestOnOpened()
                } finally {
                    opening = false
                }
            }
        }
    }
}

/** Copies a path to the clipboard; below Android 13 (no system toast) confirms via the snackbar. */
@Composable
internal fun rememberCopyPath(snackbarHostState: SnackbarHostState): (File) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { file ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(file.name, file.path))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.msg_path_copied)) }
        }
    }
}

/**
 * Card row for one recent project: colored folder tile, name, path and an actions menu
 * (pin, info, copy path, back up, rename, delete, remove from recents).
 */
@Composable
internal fun ProjectCardRow(
    item: RecentItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onInfo: () -> Unit,
    onCopyPath: () -> Unit,
    onBackup: () -> Unit,
    isBackingUp: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    val accent = if (item.exists) projectColor(item.file.name) else MaterialTheme.colorScheme.outline
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .expressiveClickable(onClick = onOpen)
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XIcons.Folder, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            XIcons.Pin,
                            contentDescription = stringResource(R.string.tree_menu_unpin),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    text = item.file.path,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(XIcons.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    val close = { menuOpen = false }
                    val canModify = item.exists && !item.isDeviceStorage
                    val canDelete = item.exists && !item.isProtected
                    ProjectMenuItem(
                        label = stringResource(if (item.pinned) R.string.tree_menu_unpin else R.string.tree_menu_pin),
                        icon = XIcons.Pin,
                        onClick = { close(); onTogglePin() },
                    )
                    if (item.exists) {
                        ProjectMenuItem(
                            label = stringResource(R.string.recent_menu_info),
                            icon = XIcons.Info,
                            onClick = { close(); onInfo() },
                        )
                    }
                    ProjectMenuItem(
                        label = stringResource(R.string.tree_menu_copy_path),
                        icon = XIcons.ContentCopy,
                        onClick = { close(); onCopyPath() },
                    )
                    if (canModify) {
                        ProjectMenuItem(
                            label = stringResource(R.string.recent_menu_backup),
                            icon = XIcons.CloudUpload,
                            enabled = !isBackingUp,
                            onClick = { close(); onBackup() },
                        )
                        ProjectMenuItem(
                            label = stringResource(R.string.tree_menu_rename),
                            icon = XIcons.Edit,
                            onClick = { close(); onRename() },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    if (canDelete) {
                        ProjectMenuItem(
                            label = stringResource(R.string.tree_menu_delete),
                            icon = XIcons.Delete,
                            danger = true,
                            onClick = { close(); onDelete() },
                        )
                    }
                    ProjectMenuItem(
                        label = stringResource(R.string.recent_menu_remove),
                        icon = XIcons.Close,
                        onClick = { close(); onRemove() },
                    )
                }
            }
        }
    }
}

/** One compact (44dp) row of the project card menu: leading icon + label, red when [danger]. */
@Composable
private fun ProjectMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    DropdownMenuItem(
        text = {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.height(44.dp),
    )
}
