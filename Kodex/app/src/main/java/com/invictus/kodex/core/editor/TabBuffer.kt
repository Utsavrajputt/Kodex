package com.invictus.kodex.core.editor

import io.github.rosemoe.sora.text.Content
import java.io.File
import java.nio.charset.Charset

/** What, if anything, a tab's on-disk file did behind the app's back. */
enum class ExternalChange {
    None,
    Modified,
    Deleted,
}

/**
 * Everything the editor needs to bring one tab back exactly as the user left it.
 *
 * [content] is Sora's own text object and it owns the undo/redo stack, so handing the same
 * instance to a fresh editor view on tab switch keeps undo history for free.
 *
 * Main thread only, except construction.
 */
class TabBuffer(
    val file: File,
    content: Content,
    val charset: Charset,
    val hasBom: Boolean,
    val lineEnding: LineEnding,
    /** Non-null only for a file opened above [TextFileIo.MAX_BYTES] (M4 "paged large file"). */
    val pagedSession: PagedEditSession? = null,
) {
    /** The page currently loaded, when [pagedSession] is set -- see [goToPage]. */
    var content: Content = content
        private set

    val isPaged: Boolean get() = pagedSession != null

    /** Bumped on every edit; a tab is dirty while it differs from [savedRevision]. */
    var revision: Long = 0
    var savedRevision: Long = 0

    val isDirty: Boolean get() = revision != savedRevision

    /** Search match to flash once the editor view first appears (new tab opened from code search). */
    var pendingFlashColumn: Int = -1
    var pendingFlashLength: Int = 0

    /**
     * 0-based line a code-search hit wants this tab to scroll to once its view is on screen, -1 = none.
     * A tab that is already open may have no view at that moment (the search screen is in front), and a live
     * jump request sent then would be lost: this is what the view picks up when it appears.
     */
    var pendingJumpLine: Int = -1

    var cursorLine: Int = 0
    var cursorColumn: Int = 0

    /** 0 = editor default. Kept so a pinch-zoomed tab stays zoomed. */
    var textSizePx: Float = 0f

    /** Virtual scroll offset in pixels, restored on tab switch alongside cursor + zoom. */
    var scrollX: Int = 0
    var scrollY: Int = 0

    /** Survives close-all/close-others and is placed first in the tab row. */
    var isPinned: Boolean = false

    /** Word tokens of the file as last opened/saved; baseline for what counts as newly typed. Null = unknown. */
    var knownTokens: Set<String>? = null

    /** Hash of the file's content as far as this app instance knows it (set on open + save). */
    var diskContentHash: String = ""

    /** mtime as far as this app instance knows it (set on open + save); resume fallback check. */
    var lastKnownDiskModified: Long = 0L

    /** Set once a background watcher or resume check has seen the file differ; drives the banner. */
    var externalChange: ExternalChange = ExternalChange.None

    /** M5: split-view drag handle ratio (editor pane's share), remembered per tab for the session. */
    var previewSplitRatio: Float = 0.5f

    /** M5: HTML preview's JS toggle, off by default and asked about once per tab per session. */
    var htmlJsEnabled: Boolean = false

    /** Whether the "Enable JavaScript?" dialog has already been shown once for this tab. */
    var htmlJsPromptShown: Boolean = false

    /**
     * Set right before the outgoing page's Compose view tears down (see `key(path, pageIndex)`
     * in EditorScreen), so the routine teardown -> `onViewState` capture -- which would report
     * the *outgoing* page's cursor -- doesn't clobber the *incoming* page's cursor [goToPage]
     * just restored into [cursorLine] etc. Consumed once by `EditorViewModel.onViewState`.
     */
    var suppressNextViewStateCapture: Boolean = false

    /**
     * Commits the current page's live text + cursor into [pagedSession], then swaps [content]
     * for a fresh `Content` holding [index]'s text (own undo stack) and restores that page's
     * last-seen cursor/scroll into [cursorLine]/[cursorColumn]/[scrollX]/[scrollY], the same
     * fields a normal tab switch already reads to place the cursor. No-op when not paged.
     */
    fun goToPage(index: Int, outgoingViewState: PagedEditSession.PageViewState) {
        val session = pagedSession ?: return
        session.commitPage(session.currentPageIndex, content.toString(), outgoingViewState)
        session.selectPage(index)
        content = Content(session.textForPage(session.currentPageIndex))
        val incoming = session.viewStateForPage(session.currentPageIndex)
        cursorLine = incoming.cursorLine
        cursorColumn = incoming.cursorColumn
        scrollX = incoming.scrollX
        scrollY = incoming.scrollY
        suppressNextViewStateCapture = true
    }

    /**
     * The text to write on save: the whole buffer for a normal file, or the paged file's
     * [PagedEditSession.mergedText] (current page committed first) so a save never touches
     * only the page the user happens to be looking at.
     */
    fun snapshotForSave(): String {
        val session = pagedSession ?: return content.toString()
        session.commitCurrentPageText(content.toString())
        return session.mergedText()
    }
}
