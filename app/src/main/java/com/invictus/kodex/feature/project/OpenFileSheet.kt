package com.invictus.kodex.feature.project

import android.os.Environment
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.feature.home.projectColor
import com.invictus.kodex.feature.home.projectColorsFor
import com.invictus.kodex.ui.components.FileTypeIcon
import com.invictus.kodex.ui.components.expressiveClickable
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

private const val FILE_SHEET_HEIGHT_FRACTION = 0.9f
private const val FILE_SEARCH_LIMIT = 150

/**
 * Open File bottom sheet modeled after [OpenProjectSheet]: search/path field, breadcrumb navigation,
 * and folder/file list so users can pick a file to edit without leaving the workspace or editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenFileSheet(
    root: File,
    onOpen: (File) -> Unit,
    onDismiss: () -> Unit,
    showHidden: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val storageRoot = remember { Environment.getExternalStorageDirectory() }
    var currentDirPath by rememberSaveable(root.path) { mutableStateOf(root.path) }
    val currentDir = remember(currentDirPath) { File(currentDirPath) }
    var query by rememberSaveable { mutableStateOf("") }

    val trimmed = query.trim()
    val typedPath = trimmed.takeIf { it.startsWith("/") }
    val needle = if (typedPath == null) trimmed else ""

    val typedTarget by produceState<File?>(null, typedPath) {
        value = typedPath?.let { path ->
            withContext(Dispatchers.IO) {
                File(path).takeIf { (it.isFile || it.isDirectory) && it.canRead() }
            }
        }
    }

    val dirEntries by produceState<Pair<List<File>, List<File>>>(emptyList<File>() to emptyList(), currentDirPath, showHidden) {
        value = withContext(Dispatchers.IO) {
            val all = currentDir.listFiles()?.asSequence()
                ?.filter { showHidden || !it.name.startsWith(".") }
                ?.toList()
                .orEmpty()
            val dirs = all.filter { it.isDirectory && it.canRead() }.sortedBy { it.name.lowercase() }
            val files = all.filter { it.isFile && it.canRead() }.sortedBy { it.name.lowercase() }
            dirs to files
        }
    }

    var searchResults by remember { mutableStateOf<List<File>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(needle, currentDirPath, showHidden) {
        if (needle.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(80)
        searchResults = withContext(Dispatchers.IO) {
            searchFilesUnder(currentDir, needle, showHidden, FILE_SEARCH_LIMIT)
        }
        searching = false
    }

    val dirs = dirEntries.first
    val files = dirEntries.second
    val dirColors = dirs.map { it.path }.zip(projectColorsFor(dirs.map { it.name })).toMap()

    val hostFocus = LocalFocusManager.current
    val hostKeyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) {
        onDispose {
            hostFocus.clearFocus(force = true)
            hostKeyboard?.hide()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler(enabled = query.isNotEmpty() || currentDirPath != root.path) {
            if (query.isNotEmpty()) {
                query = ""
            } else {
                val parent = currentDir.parentFile
                if (parent != null && parent.canRead()) {
                    currentDirPath = parent.path
                } else {
                    onDismiss()
                }
            }
        }

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

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(FILE_SHEET_HEIGHT_FRACTION)
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            XIcons.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.menu_open_file),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.file_sheet_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.file_sheet_search_hint)) },
                    leadingIcon = { Icon(XIcons.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(XIcons.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = typedTarget ?: searchResults.firstOrNull()
                            if (target != null) {
                                if (target.isDirectory) {
                                    currentDirPath = target.path
                                    query = ""
                                } else {
                                    onOpen(target)
                                }
                            }
                        },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (needle.isEmpty()) {
                    FileBreadcrumbBar(
                        dir = currentDir,
                        storageRoot = storageRoot,
                        onJump = { currentDirPath = it.path },
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).keepScrollInside(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    if (typedPath != null) {
                        item(key = "typed") {
                            TypedFilePathRow(
                                path = typedPath,
                                target = typedTarget,
                                onOpen = { target ->
                                    if (target.isDirectory) {
                                        currentDirPath = target.path
                                        query = ""
                                    } else {
                                        onOpen(target)
                                    }
                                },
                            )
                        }
                    }

                    if (needle.isNotEmpty()) {
                        if (searching && searchResults.isEmpty()) {
                            item(key = "file-search-loading") {
                                FileSheetHint(stringResource(R.string.search_searching))
                            }
                        } else if (searchResults.isEmpty()) {
                            item(key = "file-search-empty") {
                                FileSheetHint(stringResource(R.string.file_sheet_empty))
                            }
                        }
                        items(items = searchResults, key = { "sf:" + it.path }) { file ->
                            val rel = file.path.removePrefix(currentDir.path).trimStart('/')
                            FileSheetRow(
                                file = file,
                                subtitle = rel.ifEmpty { file.path },
                                onClick = { onOpen(file) },
                            )
                        }
                    } else {
                        if (dirs.isEmpty() && files.isEmpty()) {
                            item(key = "file-browse-empty") {
                                FileSheetHint(stringResource(R.string.file_sheet_empty))
                            }
                        }
                        items(items = dirs, key = { "fd:" + it.path }) { dir ->
                            FileSheetFolderRow(
                                name = dir.name,
                                tileColor = dirColors[dir.path],
                                onClick = { currentDirPath = dir.path },
                            )
                        }
                        items(items = files, key = { "ff:" + it.path }) { file ->
                            val context = LocalContext.current
                            val sizeLabel = remember(file.path, file.length()) {
                                Formatter.formatShortFileSize(context, file.length())
                            }
                            FileSheetRow(
                                file = file,
                                subtitle = sizeLabel,
                                onClick = { onOpen(file) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun searchFilesUnder(root: File, query: String, showHidden: Boolean, limit: Int): List<File> {
    val results = ArrayList<File>(minOf(limit, 32))
    val queue = ArrayDeque<File>()
    queue.add(root)
    while (queue.isNotEmpty() && results.size < limit) {
        val dir = queue.removeFirst()
        val children = dir.listFiles() ?: continue
        for (child in children) {
            if (!showHidden && child.name.startsWith(".")) continue
            if (!child.canRead()) continue
            if (child.isDirectory) {
                if (child.name != ".git" && child.name != "node_modules" && child.name != "build") {
                    queue.add(child)
                }
            } else if (child.isFile && child.name.contains(query, ignoreCase = true)) {
                results.add(child)
                if (results.size >= limit) break
            }
        }
    }
    return results.sortedBy { it.name.lowercase() }
}

@Composable
private fun Modifier.keepScrollInside(): Modifier = nestedScroll(
    remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) available else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y < 0f) available else Velocity.Zero
        }
    },
)

@Composable
private fun FileSheetHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun TypedFilePathRow(path: String, target: File?, onOpen: (File) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .expressiveClickable(enabled = target != null, onClick = { target?.let(onOpen) })
            .alpha(if (target == null) 0.6f else 1f)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (target?.isDirectory == true) XIcons.FolderOpen else XIcons.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sheet_open_path),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (target == null) stringResource(R.string.sheet_path_missing) else path,
                style = MaterialTheme.typography.bodySmall,
                color = if (target == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private data class FileCrumb(val label: String, val file: File)

private fun fileCrumbsFor(dir: File, storageRoot: File, rootLabel: String): List<FileCrumb> {
    val out = ArrayList<FileCrumb>()
    val rootPath = storageRoot.path
    var cur: File
    val parts: List<String>
    if (dir.path == rootPath || dir.path.startsWith(rootPath + "/")) {
        out += FileCrumb(rootLabel, storageRoot)
        cur = storageRoot
        parts = dir.path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }
    } else {
        cur = File("/")
        parts = dir.path.split('/').filter { it.isNotEmpty() }
    }
    for (part in parts) {
        cur = File(cur, part)
        out += FileCrumb(part, cur)
    }
    return out
}

@Composable
private fun FileBreadcrumbBar(dir: File, storageRoot: File, onJump: (File) -> Unit) {
    val rootLabel = stringResource(R.string.workspace_root_internal)
    val crumbs = remember(dir.path) { fileCrumbsFor(dir, storageRoot, rootLabel) }
    val scroll = rememberScrollState()
    LaunchedEffect(dir.path) { scroll.scrollTo(0) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scroll, reverseScrolling = true)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { index, crumb ->
                val isCurrent = index == crumbs.lastIndex
                Text(
                    text = crumb.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .expressiveClickable(enabled = !isCurrent, onClick = { onJump(crumb.file) })
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (!isCurrent) {
                    Icon(
                        XIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FileSheetFolderRow(
    name: String,
    tileColor: Color?,
    onClick: () -> Unit,
) {
    val accent = tileColor ?: projectColor(name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveClickable(onClick = onClick)
            .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(XIcons.Folder, contentDescription = null, tint = accent)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            XIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun FileSheetRow(
    file: File,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .expressiveClickable(onClick = onClick)
            .padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            FileTypeIcon(name = file.name, isDirectory = false, size = 24.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
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
    }
}
