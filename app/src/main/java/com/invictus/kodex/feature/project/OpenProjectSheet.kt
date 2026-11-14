package com.invictus.kodex.feature.project

import android.os.Environment
import com.invictus.kodex.ui.components.expressiveClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import com.invictus.kodex.feature.home.projectColor
import com.invictus.kodex.feature.home.projectColorsFor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.editor.ShortcutPref
import com.invictus.kodex.core.search.FolderSearchEngine
import com.invictus.kodex.feature.workspace.asString
import com.invictus.kodex.ui.components.FileTypeIcon
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Open Project bottom sheet: one field that both filters and accepts a typed path, the recent
 * workspaces, and a folder browser. The search field is a fixed header, so the lists scroll
 * underneath it and never overlap it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenProjectSheet(
    state: ProjectsUiState,
    currentRoot: File,
    snackbarHostState: SnackbarHostState,
    onEvent: (ProjectsEvent) -> Unit,
    onOpen: (File) -> Unit,
    onCopyPath: (File) -> Unit,
    onDismiss: () -> Unit,
    showRecent: Boolean = true,
    // Workspace's in-project "change project" sheet trims this down to just Recents --
    // Home's pre-workspace "Open Project" sheet keeps the full search/browse flow.
    showSearch: Boolean = true,
    showBrowse: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { onEvent(ProjectsEvent.SheetOpened) }
    var editingShortcuts by remember { mutableStateOf(false) }

    val trimmed = query.trim()
    val typedPath = trimmed.takeIf { it.startsWith("/") }
    val needle = if (typedPath == null) trimmed else ""
    val pathIsFolder by produceState<Boolean?>(null, typedPath) {
        value = typedPath?.let { path ->
            withContext(Dispatchers.IO) { File(path).let { it.isDirectory && it.canRead() } }
        }
    }

    val recents = state.recents.filter { matches(it.file, needle) }
    val shortcuts = state.shortcuts.filter { matches(it.file, needle) }
    val entries = state.browseEntries.filter { matches(it, needle) }
    // Neighbouring rows never share a tile color.
    val recentColors = recents.map { it.file.path }.zip(projectColorsFor(recents.map { it.file.name })).toMap()
    val shortcutColors = shortcuts.map { it.file.path }
        .zip(projectColorsFor(shortcuts.map { it.label?.asString() ?: it.file.name })).toMap()
    val entryColors = entries.map { it.path }.zip(projectColorsFor(entries.map { it.name })).toMap()

    // Query ke saath, jo bhi shortcut/subfolder khula ho, hamesha poore Internal Storage root se
    // recursively folders dhoondte hain — sirf currently-browsed folder ke direct children tak
    // limited nahi rehte (M2 Part 2 ka wahi "andar ki files nahi milti" bug, yahan folders ke liye).
    val folderSearchEngine = remember { FolderSearchEngine() }
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var folderResults by remember { mutableStateOf<List<FolderSearchEngine.Result>>(emptyList()) }
    var folderSearching by remember { mutableStateOf(false) }
    LaunchedEffect(needle, state.showHidden) {
        if (needle.isEmpty()) {
            folderResults = emptyList()
            folderSearching = false
            return@LaunchedEffect
        }
        folderSearching = true
        delay(200) // debounce; naya query aane par purana scan cancel ho jaata hai
        folderResults = folderSearchEngine.search(root = storageRoot, query = needle, showHidden = state.showHidden)
        folderSearching = false
    }

    // Fixed 90% height only while browsing into a folder or searching (dynamic, long lists);
    // a plain recents list wraps to its content so there's no blank space above/below it.
    val expandFull = state.browseDir != null || needle.isNotEmpty()
    val heightModifier = if (expandFull) Modifier.fillMaxHeight(SHEET_HEIGHT_FRACTION) else Modifier

    // Swiping/backing out of the sheet while the search field is focused left the keyboard to
    // hide and then pop back up over Home. Drop focus + keyboard in the sheet's own window as soon
    // as it starts closing, and once more in the host window when the sheet leaves composition.
    val hostFocus = LocalFocusManager.current
    val hostKeyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose {
            hostFocus.clearFocus(force = true)
            hostKeyboard?.hide()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val sheetFocus = LocalFocusManager.current
        val sheetKeyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.targetValue }.collect { target ->
                if (target == SheetValue.Hidden) {
                    sheetFocus.clearFocus(force = true)
                    sheetKeyboard?.hide()
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth().then(heightModifier).imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        XIcons.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (showBrowse) {
                        Text(
                            text = stringResource(R.string.sheet_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showBrowse) {
                    FilledTonalIconButton(
                        onClick = { editingShortcuts = true },
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Icon(
                            imageVector = XIcons.Tune,
                            contentDescription = stringResource(R.string.sheet_browse_settings),
                        )
                    }
                }
            }
            if (showSearch) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    onGo = { if (typedPath != null && pathIsFolder == true) onOpen(File(typedPath)) },
                )
            }
            val listModifier = if (expandFull) Modifier.weight(1f) else Modifier
            LazyColumn(modifier = listModifier, contentPadding = PaddingValues(bottom = 16.dp)) {
                if (typedPath != null) {
                    item(key = "typed") {
                        TypedPathRow(path = typedPath, isFolder = pathIsFolder, onOpen = { onOpen(File(typedPath)) })
                    }
                }
                if (recents.isNotEmpty() && showRecent && (showBrowse || state.browseDir == null)) {
                    items(items = recents, key = { "r:" + it.file.path }) { item ->
                        RecentRow(
                            tileColor = recentColors[item.file.path],
                            item = item,
                            isCurrent = item.file.path == currentRoot.path,
                            isBackingUp = item.file.path in state.backingUp,
                            onOpen = { onOpen(item.file) },
                            onEvent = onEvent,
                            onCopyPath = onCopyPath,
                        )
                    }
                }
                if (showBrowse) {
                item(key = "h-browse") { SectionLabel(R.string.sheet_browse) }
                if (needle.isNotEmpty()) {
                    // Search active: poore storage se recursive results, browseDir/shortcuts ignore.
                    if (folderSearching && folderResults.isEmpty()) {
                        item(key = "folder-search-loading") { HintText(stringResource(R.string.search_searching)) }
                    } else if (folderResults.isEmpty()) {
                        item(key = "folder-search-empty") { HintText(stringResource(R.string.search_no_folder_matches)) }
                    }
                    items(items = folderResults, key = { "fs:" + it.file.path }) { result ->
                        FolderSearchResultRow(
                            result = result,
                            onEnter = {
                                onEvent(ProjectsEvent.Browse(result.file))
                                query = ""
                            },
                            onOpen = { onOpen(result.file) },
                            onCopyPath = onCopyPath,
                        )
                    }
                } else {
                    val browseDir = state.browseDir
                    if (browseDir == null) {
                        items(items = shortcuts, key = { "s:" + it.file.path }) { shortcut ->
                            FolderRow(
                                tileColor = shortcutColors[shortcut.file.path],
                                name = shortcut.label?.asString() ?: shortcut.file.name,
                                file = shortcut.file,
                                onEnter = { onEvent(ProjectsEvent.Browse(shortcut.file)) },
                                onOpen = { onOpen(shortcut.file) },
                                onCopyPath = onCopyPath,
                            )
                        }
                    } else {
                        item(key = "browse-head") {
                            BrowseHeader(
                                dir = browseDir,
                                onUp = { onEvent(ProjectsEvent.BrowseUp) },
                                onOpen = { onOpen(browseDir) },
                            )
                        }
                        val error = state.browseError
                        if (error != null) {
                            item(key = "browse-error") { HintText(error.asString(), isError = true) }
                        } else if (entries.isEmpty()) {
                            item(key = "browse-empty") { HintText(stringResource(R.string.sheet_no_subfolders)) }
                        }
                        items(items = entries, key = { "b:" + it.path }) { dir ->
                            FolderRow(
                                tileColor = entryColors[dir.path],
                                name = dir.name,
                                file = dir,
                                onEnter = { onEvent(ProjectsEvent.Browse(dir)) },
                                onOpen = { onOpen(dir) },
                                onCopyPath = onCopyPath,
                            )
                        }
                    }
                }
                }
            }
            SnackbarHost(snackbarHostState)
        }
    }

    state.renaming?.let { RenameProjectDialog(it, onEvent) }

    if (editingShortcuts) {
        EditShortcutsDialog(
            entries = state.shortcutEntries,
            showHidden = state.showHidden,
            onToggleHidden = { onEvent(ProjectsEvent.ToggleShowHidden) },
            onSave = { prefs ->
                onEvent(ProjectsEvent.SaveShortcuts(prefs))
                editingShortcuts = false
            },
            onDismiss = { editingShortcuts = false },
        )
    }
}

/** Show/hide, reorder (up/down) and add/remove custom folders for the Browse shortcut list. */
@Composable
private fun EditShortcutsDialog(
    entries: List<ShortcutEntry>,
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onSave: (List<ShortcutPref>) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember { mutableStateListOf<ShortcutPref>().apply { addAll(entries.map { ShortcutPref(it.shortcut.file.path, it.enabled) }) } }
    val builtIn = remember { entries.filter { !it.isCustom }.map { it.shortcut.file.path }.toSet() }
    val names = entries.associate { it.shortcut.file.path to (it.shortcut.label?.asString() ?: it.shortcut.file.name) }
    var newPath by remember { mutableStateOf("") }
    var pathError by remember { mutableStateOf(false) }

    val addFolder: () -> Unit = {
        val f = File(newPath.trim())
        if (newPath.isNotBlank() && f.isDirectory && f.canRead()) {
            if (items.none { it.path == f.path }) items.add(ShortcutPref(f.path, true))
            newPath = ""
            pathError = false
        } else {
            pathError = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sheet_browse_settings)) },
        text = {
            Column {
                // Applies immediately (not part of Save): it only changes what the browser lists.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .expressiveClickable(onClick = onToggleHidden),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.menu_show_hidden),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = showHidden, onCheckedChange = { onToggleHidden() })
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.sheet_browse_edit),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    itemsIndexed(items = items, key = { _, pref -> pref.path }) { index, pref ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = pref.enabled,
                                onCheckedChange = { items[index] = pref.copy(enabled = it) },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = names[pref.path] ?: File(pref.path).name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = { val t = items[index]; items[index] = items[index - 1]; items[index - 1] = t },
                            ) { Icon(XIcons.KeyboardArrowUp, contentDescription = null) }
                            IconButton(
                                enabled = index < items.lastIndex,
                                onClick = { val t = items[index]; items[index] = items[index + 1]; items[index + 1] = t },
                            ) { Icon(XIcons.KeyboardArrowDown, contentDescription = null) }
                            if (pref.path !in builtIn) {
                                IconButton(onClick = { items.removeAt(index) }) {
                                    Icon(XIcons.Close, contentDescription = null)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newPath,
                    onValueChange = { newPath = it; pathError = false },
                    singleLine = true,
                    isError = pathError,
                    label = { Text(stringResource(R.string.sheet_browse_add_hint)) },
                    supportingText = if (pathError) ({ Text(stringResource(R.string.sheet_browse_invalid_path)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addFolder() }),
                    trailingIcon = {
                        IconButton(onClick = { addFolder() }) { Icon(XIcons.Add, contentDescription = stringResource(R.string.sheet_browse_add)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(items.toList()) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val SHEET_HEIGHT_FRACTION = 0.9f

private fun matches(file: File, needle: String): Boolean =
    needle.isEmpty() || file.name.contains(needle, ignoreCase = true) || file.path.contains(needle, ignoreCase = true)

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.sheet_search_hint)) },
        leadingIcon = { Icon(XIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(XIcons.Close, contentDescription = stringResource(R.string.action_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SectionLabel(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun HintText(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun TypedPathRow(path: String, isFolder: Boolean?, onOpen: () -> Unit) {
    val enabled = isFolder == true
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .expressiveClickable(enabled = enabled, onClick = onOpen)
            .alpha(if (isFolder == false) 0.6f else 1f)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XIcons.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sheet_open_path),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (isFolder == false) stringResource(R.string.sheet_path_missing) else path,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFolder == false) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Address-bar style header while browsing inside a folder: up button, current path, Open. */
@Composable
private fun BrowseHeader(dir: File, onUp: () -> Unit, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUp) {
                Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                text = dir.path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            OpenPill(onClick = onOpen)
        }
    }
}

/** Small tonal "Open" button shared by the browse rows. */
@Composable
private fun OpenPill(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(stringResource(R.string.action_open_folder)) }
}

/** Recursive folder-search hit: name + its path under storage; tap enters, Open opens it. */
@Composable
private fun FolderSearchResultRow(
    result: FolderSearchEngine.Result,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
    onCopyPath: (File) -> Unit,
) {
    FolderEntryRow(
        name = result.name,
        file = result.file,
        subtitle = result.relativePath,
        onEnter = onEnter,
        onOpen = onOpen,
        onCopyPath = onCopyPath,
    )
}

/** Tap enters the folder; the trailing button opens it as the workspace. */
@Composable
private fun FolderRow(
    name: String,
    file: File,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
    onCopyPath: (File) -> Unit,
    tileColor: Color? = null,
) {
    FolderEntryRow(
        name = name, file = file, subtitle = file.path, onEnter = onEnter, onOpen = onOpen,
        onCopyPath = onCopyPath, tileColor = tileColor,
    )
}

/** Colored folder tile, name + path, tonal Open button and a overflow menu (same look as Home). */
@Composable
private fun FolderEntryRow(
    name: String,
    file: File,
    subtitle: String,
    onEnter: () -> Unit,
    onOpen: () -> Unit,
    onCopyPath: (File) -> Unit,
    tileColor: Color? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = tileColor ?: projectColor(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveClickable(onClick = onEnter)
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(XIcons.Folder, contentDescription = null, tint = accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        OpenPill(onClick = onOpen)
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(XIcons.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tree_menu_copy_path)) },
                    onClick = {
                        menuOpen = false
                        onCopyPath(file)
                    },
                )
            }
        }
    }
}

/** Tap opens; long-press opens the action menu at the finger (same pattern as the file tree). */
@Composable
private fun RecentRow(
    item: RecentItem,
    isCurrent: Boolean,
    isBackingUp: Boolean,
    onOpen: () -> Unit,
    onEvent: (ProjectsEvent) -> Unit,
    onCopyPath: (File) -> Unit,
    tileColor: Color? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val menuOffset = with(density) { DpOffset(pressOffset.x.toDp(), (pressOffset.y - rowHeightPx).toDp()) }
    val file = item.file

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowHeightPx = it.height }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .pointerInput(file.path, item.exists) {
                    detectTapGestures(
                        onTap = { if (item.exists) onOpen() },
                        onLongPress = { position ->
                            pressOffset = position
                            menuOpen = true
                        },
                    )
                }
                .alpha(if (item.exists) 1f else 0.5f)
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val accent = if (item.exists) tileColor ?: projectColor(file.name) else MaterialTheme.colorScheme.outline
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(XIcons.Folder, contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.isDeviceStorage) stringResource(R.string.workspace_root_internal) else file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (item.exists) file.path else stringResource(R.string.sheet_folder_missing, file.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
            when {
                isBackingUp -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                isCurrent -> Icon(
                    XIcons.Check,
                    contentDescription = stringResource(R.string.sheet_current),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = menuOffset) {
            val close = { menuOpen = false }
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

@Composable
internal fun RenameProjectDialog(state: RenameProject, onEvent: (ProjectsEvent) -> Unit) {
    var text by rememberSaveable(state.file.path) { mutableStateOf(state.file.name) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val error = state.error

    AlertDialog(
        onDismissRequest = { onEvent(ProjectsEvent.DismissRename) },
        title = { Text(stringResource(R.string.dialog_rename_project_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error?.asString() ?: stringResource(R.string.dialog_rename_project_hint))
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onEvent(ProjectsEvent.ConfirmRename(text)) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(ProjectsEvent.ConfirmRename(text)) }) {
                Text(stringResource(R.string.tree_menu_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(ProjectsEvent.DismissRename) }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
