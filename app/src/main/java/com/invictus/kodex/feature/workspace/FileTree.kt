package com.invictus.kodex.feature.workspace

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.core.git.model.GitPathDecoration
import com.invictus.kodex.core.search.FileSearchEngine
import com.invictus.kodex.feature.search.highlightedName
import com.invictus.kodex.ui.components.expressivePressScale
import com.invictus.kodex.ui.components.FileTypeIcon
import com.invictus.kodex.ui.icons.XIcons
import java.io.File
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * Horizontal-overflow bookkeeping for the tree. Every visible [FileRow] reports the width it
 * needs; the tree scrolls sideways (as one block, so indentation stays aligned) only when the
 * widest visible row is wider than the viewport.
 */
@Stable
private class TreeHScroll {
    var viewportPx by mutableIntStateOf(0)
    private val rowWidths = mutableStateMapOf<String, Int>()
    private val widest by derivedStateOf { rowWidths.values.maxOrNull() ?: 0 }

    val contentPx: Int get() = maxOf(viewportPx, widest)
    val overflowing: Boolean get() = viewportPx > 0 && widest > viewportPx

    fun report(key: String, px: Int) {
        if (rowWidths[key] != px) rowWidths[key] = px
    }

    fun clear(key: String) {
        rowWidths.remove(key)
    }

    fun clearAll() {
        rowWidths.clear()
    }
}

@Composable
fun UiText.asString(): String = stringResource(resId, *args.toTypedArray())

/** The visible tree. Whole-row tap expands/collapses or opens; long-press opens the action menu. */
@Composable
fun FileTree(
    state: FileTreeUiState,
    onEvent: (FileTreeEvent) -> Unit,
    onCopyPath: (File) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    filterQuery: String = "",
    onSearchResultOpened: () -> Unit = {},
) {
    val rootLabel = state.rootName ?: stringResource(R.string.workspace_root_internal)
    val query = filterQuery.trim()

    if (query.isEmpty()) {
        val hScroll = remember { TreeHScroll() }
        val hScrollState = rememberScrollState()
        val density = LocalDensity.current
        val hScrollOn = state.hScrollLongNames
        val overflowing = hScrollOn && hScroll.overflowing
        // Setting turned off: forget every reported width so the tree is plain again.
        LaunchedEffect(hScrollOn) { if (!hScrollOn) hScroll.clearAll() }
        // Back to normal (no sideways scrolling) as soon as nothing overflows any more.
        LaunchedEffect(overflowing) { if (!overflowing) hScrollState.scrollTo(0) }
        Box(modifier = modifier.fillMaxSize().onSizeChanged { hScroll.viewportPx = it.width }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(hScrollState, enabled = overflowing)
                .let { m ->
                    if (hScroll.viewportPx > 0) {
                        m.width(with(density) { hScroll.contentPx.toDp() })
                    } else {
                        m.fillMaxWidth()
                    }
                },
            state = listState,
        ) {
            items(items = state.rows, key = { it.key }) { row ->
                // Expressive: rows spring into place when the tree expands/collapses.
                Box(modifier = Modifier.animateItem()) {
                when (row) {
                    TreeRow.PinnedHeader -> PinnedHeader()
                    TreeRow.Divider -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    is TreeRow.Pinned -> PinnedRow(row = row, onEvent = onEvent, onCopyPath = onCopyPath)
                    is TreeRow.Entry -> FileRow(
                        row = row,
                        displayName = if (row.isRoot) rootLabel else row.file.name,
                        renaming = state.renaming?.takeIf { it.file.path == row.file.path },
                        isCutMarked = state.clipboard?.let { it.isCut && it.file.path == row.file.path } == true,
                        canPaste = state.clipboard != null,
                        isPinned = row.file.path in state.pinnedPaths,
                        decoration = state.gitDecorations[
                            row.file.path.removePrefix(state.root.path).trim('/'),
                        ],
                        onEvent = onEvent,
                        onCopyPath = onCopyPath,
                        trackOverflow = hScrollOn,
                        onWidthNeeded = { hScroll.report(row.key, it) },
                        onWidthGone = { hScroll.clear(row.key) },
                    )
                    is TreeRow.Empty -> EmptyRow(depth = row.depth)
                }
                }
            }
        }
        }
    } else {
        // Andar (collapsed folders) ki files bhi milni chahiye, isliye ab poore project ko
        // recursively scan karte hain — sirf currently-expanded rows ko filter nahi karte.
        val engine = remember { FileSearchEngine() }
        var results by remember { mutableStateOf<List<FileSearchEngine.Result>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        LaunchedEffect(query, state.root, state.showHidden, state.searchDefaultStringsOnly) {
            searching = true
            delay(200) // debounce; naya query aane par purana scan cancel ho jaata hai
            results = engine.search(
                root = state.root,
                query = query,
                showHidden = state.showHidden,
                defaultValuesOnly = state.searchDefaultStringsOnly,
            )
            searching = false
        }
        Box(modifier = modifier.fillMaxSize()) {
            if (searching && results.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_searching),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (results.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_file_matches),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(items = results, key = { it.file.path }) { result ->
                        SearchResultRow(
                            result = result,
                            isPinned = result.file.path in state.pinnedPaths,
                            canPaste = state.clipboard != null,
                            onEvent = onEvent,
                            onCopyPath = onCopyPath,
                            onClick = {
                                onEvent(FileTreeEvent.RowClicked(result.file, isDirectory = false))
                                onSearchResultOpened()
                            },
                            onShowInTree = {
                                onEvent(FileTreeEvent.Reveal(result.file, false))
                                onSearchResultOpened()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One recursive filename-search hit: icon + highlighted name + its folder path under the project. Tap opens; long-press
 * shows the same file menu as the tree (plus "Show in tree").
 */
@Composable
private fun SearchResultRow(
    result: FileSearchEngine.Result,
    isPinned: Boolean,
    canPaste: Boolean,
    onEvent: (FileTreeEvent) -> Unit,
    onCopyPath: (File) -> Unit,
    onClick: () -> Unit,
    onShowInTree: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val menuOffset = with(density) { DpOffset(pressOffset.x.toDp(), (pressOffset.y - rowHeightPx).toDp()) }
    val file = result.file
    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowHeightPx = it.height }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(file.path) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = { position ->
                            pressOffset = position
                            menuOpen = true
                        },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeIcon(name = result.name, isDirectory = false)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(text = highlightedName(result), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = result.relativePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = menuOffset) {
            val close = { menuOpen = false }
            MenuItem(XIcons.MyLocation, R.string.tree_menu_reveal) { close(); onShowInTree() }
            MenuItem(XIcons.Pin, if (isPinned) R.string.tree_menu_unpin else R.string.tree_menu_pin) {
                close(); onEvent(FileTreeEvent.TogglePin(file, false))
            }
            MenuItem(XIcons.ContentCut, R.string.tree_menu_cut) { close(); onEvent(FileTreeEvent.Cut(file)) }
            MenuItem(XIcons.ContentCopy, R.string.tree_menu_copy) { close(); onEvent(FileTreeEvent.Copy(file)) }
            MenuItem(XIcons.Edit, R.string.tree_menu_rename) { close(); onEvent(FileTreeEvent.StartRename(file)) }
            MenuItem(XIcons.FileCopy, R.string.tree_menu_duplicate) { close(); onEvent(FileTreeEvent.Duplicate(file)) }
            if (canPaste) {
                MenuItem(XIcons.ContentPaste, R.string.tree_menu_paste) {
                    close(); onEvent(FileTreeEvent.PasteInto(file.parentFile ?: file))
                }
            }
            MenuItem(XIcons.Link, R.string.tree_menu_copy_path) { close(); onCopyPath(file) }
            HorizontalDivider()
            MenuItem(XIcons.Delete, R.string.tree_menu_delete) { close(); onEvent(FileTreeEvent.RequestDelete(file, false)) }
        }
    }
}

@Composable
private fun FileRow(
    row: TreeRow.Entry,
    displayName: String,
    renaming: RenameState?,
    isCutMarked: Boolean,
    canPaste: Boolean,
    isPinned: Boolean,
    decoration: GitPathDecoration?,
    onEvent: (FileTreeEvent) -> Unit,
    onCopyPath: (File) -> Unit,
    trackOverflow: Boolean,
    onWidthNeeded: (Int) -> Unit,
    onWidthGone: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val isRenaming = renaming != null

    // Row stops reporting when it leaves composition or while the rename field is shown.
    DisposableEffect(Unit) { onDispose(onWidthGone) }
    LaunchedEffect(isRenaming) { if (isRenaming) onWidthGone() }
    // Everything in the row except the name: indent, chevron, icon, gaps, loader, end padding.
    val chromeDp = 8 + row.depth * 16 + 20 + 6 + 22 + 10 + 8 + 4 + if (row.isLoading) 22 else 0
    val chromePx = with(density) { chromeDp.dp.toPx() }

    // DropdownMenu anchors to the bottom-left of its parent, so subtract the row height to
    // make the menu open at the finger instead of below the row.
    val menuOffset = with(density) {
        DpOffset(pressOffset.x.toDp(), (pressOffset.y - rowHeightPx).toDp())
    }

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowHeightPx = it.height }) {
        val stripeColor = decoration?.let {
            when (it.kind) {
                GitPathDecoration.Kind.CONFLICT -> MaterialTheme.colorScheme.error
                GitPathDecoration.Kind.DELETED ->
                    MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                GitPathDecoration.Kind.MODIFIED -> MaterialTheme.colorScheme.primary
                GitPathDecoration.Kind.ADDED -> MaterialTheme.colorScheme.tertiary
                GitPathDecoration.Kind.UNTRACKED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { m ->
                    if (stripeColor != null) {
                        m.drawBehind {
                            drawRect(
                                color = stripeColor,
                                topLeft = Offset(0f, size.height * 0.25f),
                                size = Size(3.dp.toPx(), size.height * 0.5f),
                            )
                        }
                    } else m
                }
                .heightIn(min = 44.dp)
                .expressivePressScale(pressed)
                .pointerInput(row.file.path, isRenaming) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = {
                            if (!isRenaming) onEvent(FileTreeEvent.RowClicked(row.file, row.isDirectory))
                        },
                        onLongPress = { position ->
                            if (!isRenaming) {
                                pressOffset = position
                                menuOpen = true
                            }
                        },
                    )
                }
                .padding(start = (8 + row.depth * 16).dp, end = 8.dp)
                .alpha(if (isCutMarked) 0.5f else 1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.isDirectory) {
                val rotation by animateFloatAsState(
                    targetValue = if (row.isExpanded) 90f else 0f,
                    label = "chevron",
                )
                Icon(
                    imageVector = XIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Spacer(Modifier.width(6.dp))
            FileTypeIcon(name = row.file.name, isDirectory = row.isDirectory)
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (renaming != null) {
                    InlineRenameField(
                        name = row.file.name,
                        error = renaming.error,
                        onCommit = { onEvent(FileTreeEvent.CommitRename(it)) },
                        onCancel = { onEvent(FileTreeEvent.CancelRename) },
                    )
                } else {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        softWrap = !trackOverflow,
                        overflow = if (trackOverflow) TextOverflow.Clip else TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            if (trackOverflow) {
                                onWidthNeeded(ceil(layout.multiParagraph.maxIntrinsicWidth + chromePx).toInt())
                            }
                        },
                    )
                }
            }
            if (row.isLoading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = menuOffset,
        ) {
            val close = { menuOpen = false }
            if (!row.isRoot) {
                val pinRes = if (isPinned) R.string.tree_menu_unpin else R.string.tree_menu_pin
                if (row.isDirectory) {
                    // Folders: Pin / Cut / Copy as a compact icon row on top.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        QuickAction(icon = XIcons.Pin, label = pinRes, highlighted = isPinned) {
                            close()
                            onEvent(FileTreeEvent.TogglePin(row.file, row.isDirectory))
                        }
                        QuickAction(icon = XIcons.ContentCut, label = R.string.tree_menu_cut) {
                            close()
                            onEvent(FileTreeEvent.Cut(row.file))
                        }
                        QuickAction(icon = XIcons.ContentCopy, label = R.string.tree_menu_copy) {
                            close()
                            onEvent(FileTreeEvent.Copy(row.file))
                        }
                    }
                    HorizontalDivider()
                } else {
                    // Files: normal one-per-row list items.
                    MenuItem(XIcons.Pin, pinRes) {
                        close()
                        onEvent(FileTreeEvent.TogglePin(row.file, row.isDirectory))
                    }
                    MenuItem(XIcons.ContentCut, R.string.tree_menu_cut) {
                        close()
                        onEvent(FileTreeEvent.Cut(row.file))
                    }
                    MenuItem(XIcons.ContentCopy, R.string.tree_menu_copy) {
                        close()
                        onEvent(FileTreeEvent.Copy(row.file))
                    }
                }
            }
            if (row.isDirectory) {
                MenuItem(XIcons.NoteAdd, R.string.tree_menu_new_file) {
                    close()
                    onEvent(FileTreeEvent.StartCreate(row.file, isFolder = false))
                }
                MenuItem(XIcons.CreateNewFolder, R.string.tree_menu_new_folder) {
                    close()
                    onEvent(FileTreeEvent.StartCreate(row.file, isFolder = true))
                }
                MenuItem(XIcons.CloudUpload, R.string.tree_menu_upload) {
                    close()
                    onEvent(FileTreeEvent.StartUpload(row.file))
                }
            }
            if (!row.isRoot) {
                MenuItem(XIcons.Edit, R.string.tree_menu_rename) {
                    close()
                    onEvent(FileTreeEvent.StartRename(row.file))
                }
                MenuItem(XIcons.FileCopy, R.string.tree_menu_duplicate) {
                    close()
                    onEvent(FileTreeEvent.Duplicate(row.file))
                }
            }
            if (canPaste) {
                MenuItem(XIcons.ContentPaste, R.string.tree_menu_paste) {
                    close()
                    onEvent(FileTreeEvent.PasteInto(if (row.isDirectory) row.file else row.file.parentFile ?: row.file))
                }
            }
            MenuItem(XIcons.Link, R.string.tree_menu_copy_path) {
                close()
                onCopyPath(row.file)
            }
            if (!row.isRoot) {
                HorizontalDivider()
                MenuItem(XIcons.Delete, R.string.tree_menu_delete) {
                    close()
                    onEvent(FileTreeEvent.RequestDelete(row.file, row.isDirectory))
                }
            }
        }
    }
}

@Composable
private fun PinnedHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = XIcons.Pin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.tree_pinned_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tap opens a pinned file (through the usual binary/large warning) or reveals a pinned folder. */
@Composable
private fun PinnedRow(row: TreeRow.Pinned, onEvent: (FileTreeEvent) -> Unit, onCopyPath: (File) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    var rowHeightPx by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val file = row.item.file
    val menuOffset = with(density) { DpOffset(pressOffset.x.toDp(), (pressOffset.y - rowHeightPx).toDp()) }

    Box(modifier = Modifier.fillMaxWidth().onSizeChanged { rowHeightPx = it.height }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .expressivePressScale(pressed)
                .pointerInput(file.path) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                            }
                        },
                        onTap = {
                            if (row.item.isDirectory) {
                                onEvent(FileTreeEvent.Reveal(file, isDirectory = true))
                            } else {
                                onEvent(FileTreeEvent.RowClicked(file, isDirectory = false))
                            }
                        },
                        onLongPress = { position ->
                            pressOffset = position
                            menuOpen = true
                        },
                    )
                }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeIcon(name = file.name, isDirectory = row.item.isDirectory)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.parentLabel.isNotEmpty()) {
                    Text(
                        text = row.parentLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, offset = menuOffset) {
            MenuItem(XIcons.Pin, R.string.tree_menu_unpin) {
                menuOpen = false
                onEvent(FileTreeEvent.TogglePin(file, row.item.isDirectory))
            }
            MenuItem(XIcons.MyLocation, R.string.tree_menu_reveal) {
                menuOpen = false
                onEvent(FileTreeEvent.Reveal(file, isDirectory = false))
            }
            MenuItem(XIcons.Link, R.string.tree_menu_copy_path) {
                menuOpen = false
                onCopyPath(file)
            }
        }
    }
}

/** Compact menu row: icon + text, no forced 112dp minimum width (that was the empty gap on the right). */
@Composable
private fun QuickAction(icon: ImageVector, @StringRes label: Int, highlighted: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MenuItem(icon: ImageVector, @StringRes textRes: Int, onClick: () -> Unit) {
    val tint = if (textRes == R.string.tree_menu_delete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(start = 14.dp, end = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.bodyLarge, color = tint, maxLines = 1)
    }
}

@Composable
private fun EmptyRow(depth: Int) {
    Text(
        text = stringResource(R.string.tree_empty_folder),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(start = (8 + depth * 16 + 26).dp, end = 8.dp)
            .padding(top = 8.dp),
    )
}

/**
 * Rename in place. Selects the name without its extension, keeps itself scrolled above the
 * keyboard, Done commits, losing focus (or Back) cancels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineRenameField(
    name: String,
    error: UiText?,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val stemEnd = name.lastIndexOf('.').let { if (it > 0) it else name.length }
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, stemEnd))) }
    val focusRequester = remember { FocusRequester() }
    val bringIntoView = remember { BringIntoViewRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Re-run as the keyboard animates in so the row is never left behind it.
    LaunchedEffect(imeBottom) { bringIntoView.bringIntoView() }

    val accent = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView)) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) onCancel()
                },
            decorationBox = { innerTextField ->
                Column {
                    innerTextField()
                    HorizontalDivider(color = accent)
                }
            },
        )
        if (error != null) {
            Text(
                text = error.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
