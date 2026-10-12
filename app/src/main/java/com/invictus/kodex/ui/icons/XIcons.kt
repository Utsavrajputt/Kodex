package com.invictus.kodex.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.roundedfilled.Add
import com.composables.icons.materialsymbols.roundedfilled.Arrow_back
import com.composables.icons.materialsymbols.roundedfilled.Bolt
import com.composables.icons.materialsymbols.roundedfilled.Check
import com.composables.icons.materialsymbols.roundedfilled.Chevron_left
import com.composables.icons.materialsymbols.roundedfilled.Chevron_right
import com.composables.icons.materialsymbols.roundedfilled.Close
import com.composables.icons.materialsymbols.roundedfilled.Edit
import com.composables.icons.materialsymbols.roundedfilled.Folder
import com.composables.icons.materialsymbols.roundedfilled.Folder_open
import com.composables.icons.materialsymbols.roundedfilled.Article
import com.composables.icons.materialsymbols.roundedfilled.Auto_awesome
import com.composables.icons.materialsymbols.roundedfilled.Send
import com.composables.icons.materialsymbols.roundedfilled.Stop_circle
import com.composables.icons.materialsymbols.roundedfilled.Monetization_on
import com.composables.icons.materialsymbols.roundedfilled.Open_in_new
import com.composables.icons.materialsymbols.roundedfilled.Keyboard_arrow_down
import com.composables.icons.materialsymbols.roundedfilled.Keyboard_arrow_up
import com.composables.icons.materialsymbols.roundedfilled.More_vert
import com.composables.icons.materialsymbols.roundedfilled.Palette
import com.composables.icons.materialsymbols.roundedfilled.Push_pin
import com.composables.icons.materialsymbols.roundedfilled.Redo
import com.composables.icons.materialsymbols.roundedfilled.Refresh
import com.composables.icons.materialsymbols.roundedfilled.Save
import com.composables.icons.materialsymbols.roundedfilled.Search
import com.composables.icons.materialsymbols.roundedfilled.Settings
import com.composables.icons.materialsymbols.roundedfilled.Undo

/**
 * App icon set — the same Material Symbols Rounded glyphs xmd uses
 * (com.composables:icons-material-symbols-*-cmp). Member names are unchanged,
 * so every existing call site keeps working while rendering the nicer icons.
 */
object XIcons {
    val Folder: ImageVector = MaterialSymbols.RoundedFilled.Folder
    val FolderOpen: ImageVector = MaterialSymbols.RoundedFilled.Folder_open
    val ChevronRight: ImageVector = MaterialSymbols.RoundedFilled.Chevron_right
    val ChevronLeft: ImageVector = MaterialSymbols.RoundedFilled.Chevron_left
    val MoreVert: ImageVector = MaterialSymbols.RoundedFilled.More_vert
    val Refresh: ImageVector = MaterialSymbols.RoundedFilled.Refresh
    val Pin: ImageVector = MaterialSymbols.RoundedFilled.Push_pin
    val Search: ImageVector = MaterialSymbols.RoundedFilled.Search
    val Close: ImageVector = MaterialSymbols.RoundedFilled.Close
    val ArrowBack: ImageVector = MaterialSymbols.RoundedFilled.Arrow_back
    val Undo: ImageVector = MaterialSymbols.RoundedFilled.Undo
    val Redo: ImageVector = MaterialSymbols.RoundedFilled.Redo
    val Save: ImageVector = MaterialSymbols.RoundedFilled.Save
    val Palette: ImageVector = MaterialSymbols.RoundedFilled.Palette
    val Check: ImageVector = MaterialSymbols.RoundedFilled.Check
    val KeyboardArrowUp: ImageVector = MaterialSymbols.RoundedFilled.Keyboard_arrow_up
    val KeyboardArrowDown: ImageVector = MaterialSymbols.RoundedFilled.Keyboard_arrow_down
    val Edit: ImageVector = MaterialSymbols.RoundedFilled.Edit
    val Bolt: ImageVector = MaterialSymbols.RoundedFilled.Bolt
    val Settings: ImageVector = MaterialSymbols.RoundedFilled.Settings
    val Article: ImageVector = MaterialSymbols.RoundedFilled.Article
    val MonetizationOn: ImageVector = MaterialSymbols.RoundedFilled.Monetization_on
    val OpenInNew: ImageVector = MaterialSymbols.RoundedFilled.Open_in_new

    // Editor tools menu glyphs (Material paths, same hand-drawn pattern as below).
    /** Top-bar tools menu (Material "tune" — sliders), replaces the old bolt. */
    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10z" +
                "M15,9h2V7h4V5h-4V3h-2v6z",
        )
    }
    /** Material "download": tray with a down arrow, for the vector PNG / SVG export buttons. */
    val Download: ImageVector by lazy {
        icon("Download", "M5,20h14v-2H5v2zM19,9h-4V3H9v6H5l7,7 7,-7z")
    }
    val ContentCopy: ImageVector by lazy {
        icon(
            "ContentCopy",
            "M16,1H4C2.9,1 2,1.9 2,3v14h2V3h12V1zM19,5H8C6.9,5 6,5.9 6,7v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
                "C21,5.9 20.1,5 19,5zM19,21H8V7h11V21z",
        )
    }
    /** Material "insert_drive_file": document with a folded corner, the leading tile of an artifact row. */
    val InsertDriveFile: ImageVector by lazy {
        icon("InsertDriveFile", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zM13,9V3.5L18.5,9H13z")
    }
    val Delete: ImageVector by lazy {
        icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }
    val ArrowUpward: ImageVector by lazy {
        icon("ArrowUpward", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    }
    /** Material "image": framed landscape, the Photos entry of the chat's + menu. */
    val Image: ImageVector by lazy {
        icon("Image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    }
    val ArrowDownward: ImageVector by lazy {
        icon("ArrowDownward", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z")
    }
    val Comment: ImageVector by lazy {
        icon("Comment", "M21.99,4c0,-1.1 -0.89,-2 -1.99,-2H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h14l4,4 -0.01,-18z")
    }

    // GitHub screen glyphs (Material paths).
    /** Material "android": the robot head used in the workflow picker. */
    val Android: ImageVector by lazy {
        icon(
            "Android",
            "M17.6,9.48l1.84,-3.18c0.16,-0.31 0.04,-0.69 -0.26,-0.85c-0.29,-0.15 -0.65,-0.06 -0.83,0.22l-1.88,3.24c-2.86,-1.21 -6.08,-1.21 -8.94,0L5.65,5.67c-0.19,-0.29 -0.58,-0.38 -0.87,-0.2C4.5,5.65 4.41,6.01 4.56,6.3L6.4,9.48C3.3,11.25 1.28,14.44 1,18h22C22.72,14.44 20.7,11.25 17.6,9.48zM7,15.25c-0.69,0 -1.25,-0.56 -1.25,-1.25c0,-0.69 0.56,-1.25 1.25,-1.25S8.25,13.31 8.25,14C8.25,14.69 7.69,15.25 7,15.25zM17,15.25c-0.69,0 -1.25,-0.56 -1.25,-1.25c0,-0.69 0.56,-1.25 1.25,-1.25s1.25,0.56 1.25,1.25C18.25,14.69 17.69,15.25 17,15.25z",
        )
    }
    /** Git branch glyph (three nodes joined by a curve), drawn on a 16px grid like the GitHub mark set. */
    val Branch: ImageVector by lazy {
        icon16(
            "Branch",
            "M9.5 3.25 a2.25 2.25 0 1 1 3 2.122 V6 A2.5 2.5 0 0 1 10 8.5 H6 a1 1 0 0 0 -1 1 v1.128 " +
                "a2.251 2.251 0 1 1 -1.5 0 V5.372 a2.25 2.25 0 1 1 1.5 0 v1.836 A2.493 2.493 0 0 1 6 7 h4 " +
                "a1 1 0 0 0 1 -1 v-.628 A2.25 2.25 0 0 1 9.5 3.25 Z m-6 0 a.75 .75 0 1 0 1.5 0 .75 .75 0 0 0 -1.5 0 Z " +
                "m8.25 -.75 a.75 .75 0 1 0 0 1.5 .75 .75 0 0 0 0 -1.5 Z M4.25 12 a.75 .75 0 1 0 0 1.5 .75 .75 0 0 0 0 -1.5 Z",
        )
    }
    /** Material "delete_sweep": bin with sweep lines, for Clean up (close to the old trash, clearly different). */
    val DeleteSweep: ImageVector by lazy {
        icon(
            "DeleteSweep",
            "M15,16h4v2h-4zM15,8h7v2h-7zM15,12h6v2h-6zM3,18c0,1.1 0.9,2 2,2h6c1.1,0 2,-0.9 2,-2L13,8L3,8v10zM14,5h-3l-1,-1L6,4L5,5L2,5v2h12z",
        )
    }

    // M15 GitHub Manager: run status glyphs (Material paths). Status is shown as glyph + text, never colour alone.
    val CheckCircle: ImageVector by lazy {
        icon("CheckCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM10,17l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z")
    }
    val ErrorCircle: ImageVector by lazy {
        icon("ErrorCircle", "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-2h2v2zM13,13h-2L11,7h2v6z")
    }
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }
    val CancelCircle: ImageVector by lazy {
        icon(
            "CancelCircle",
            "M12,2C6.47,2 2,6.47 2,12s4.47,10 10,10 10,-4.47 10,-10S17.53,2 12,2zM17,15.59L15.59,17 12,13.41 8.41,17 7,15.59 10.59,12 7,8.41 8.41,7 12,10.59 15.59,7 17,8.41 13.41,12 17,15.59z",
        )
    }
    val PlayArrow: ImageVector by lazy { icon("PlayArrow", "M8,5v14l11,-7z") }
    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val RemoveCircle: ImageVector by lazy {
        icon("RemoveCircle", "M7,11v2h10v-2H7zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z")
    }

    // Convenience alias used by some newer call sites.
    val Add: ImageVector = MaterialSymbols.RoundedFilled.Add

    // M5 preview feature: not in the Material Symbols cmp artifact's current release,
    // so drawn from path data the same way, rather than risk an unverifiable binding name.
    /** M5 preview toggle, "Editor" state / code-file preview affordance. */
    val Code: ImageVector by lazy {
        icon(
            "Code",
            "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4z" +
                "M14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
        )
    }

    /** M5 preview toggle, "Preview" state. */
    val Visibility: ImageVector by lazy {
        icon(
            "Visibility",
            "M12,4.5C7,4.5 2.73,7.61 1,12c1.73,4.39 6,7.5 11,7.5s9.27,-3.11 11,-7.5c-1.73,-4.39" +
                " -6,-7.5 -11,-7.5z M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z" +
                "M12,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3 3,-1.34 3,-3 -1.34,-3 -3,-3z",
        )
    }

    /** "Show hidden files" toggle, off state (Material Symbols "visibility_off"). */
    val VisibilityOff: ImageVector by lazy {
        icon(
            "VisibilityOff",
            "M12,7c2.76,0 5,2.24 5,5 0,0.65 -0.13,1.26 -0.36,1.83l2.92,2.92c1.51,-1.26 2.7,-2.89 3.44,-4.75" +
                " -1.73,-4.39 -6,-7.5 -11,-7.5 -1.4,0 -2.74,0.25 -3.98,0.7l2.16,2.16C10.74,7.13 11.35,7 12,7z" +
                "M2,4.27l2.28,2.28 0.46,0.46C3.08,8.3 1.78,10.02 1,12c1.73,4.39 6,7.5 11,7.5 1.55,0 3.03,-0.3" +
                " 4.38,-0.84l0.42,0.42L19.73,22 21,20.73 3.27,3 2,4.27z" +
                "M7.53,9.8l1.55,1.55c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.66 1.34,3 3,3 0.22,0 0.44,-0.03" +
                " 0.65,-0.08l1.55,1.55c-0.67,0.33 -1.41,0.53 -2.2,0.53 -2.76,0 -5,-2.24 -5,-5 0,-0.79 0.2,-1.53" +
                " 0.53,-2.2z M11.84,9.02l3.15,3.15 0.02,-0.16c0,-1.66 -1.34,-3 -3,-3l-0.17,0.01z",
        )
    }

    /** M5 preview toggle, "Split" state (editor top, preview bottom). */
    val HorizontalSplit: ImageVector by lazy {
        icon("HorizontalSplit", "M3,19h18v-6H3v6z M3,11h18V9H3v2z M3,5v2h18V5H3z")
    }

    // M6 git feature: these glyphs aren't in the cmp artifact's current release, so
    // they use the same hand-drawn path pattern as the icons above.
    /** Source control entry point (Material Symbols "commit"). */
    val Commit: ImageVector by lazy {
        icon(
            "Commit",
            "M21,12c0,-4.97 -4.03,-9 -9,-9s-9,4.03 -9,9c0,4.63 3.5,8.44 8,8.94v-2.02c-3.59,-0.45" +
                " -6.38,-3.46 -6.38,-7.2 0,-3.98 3.22,-7.2 7.2,-7.2s7.2,3.22 7.2,7.2c0,1.95 -0.78,3.72" +
                " -2.05,5.01l1.41,1.41C20.16,17.44 21,14.91 21,12zM12,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2" +
                " 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }
    val CloudUpload: ImageVector by lazy {
        icon(
            "CloudUpload",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14" +
                "c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM10,17l-3.5,-3.5" +
                " 1.41,-1.41L10,14.17 15.18,9l1.41,1.41L10,17z",
        )
    }
    val CloudDownload: ImageVector by lazy {
        icon(
            "CloudDownload",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14" +
                "c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96zM17,13l-5,5 -5,-5h3V9h4v4h3z",
        )
    }
    /**
     * Clone a repository: a folder with a download arrow cut out of it (remote -> a new folder on
     * the device). The arrow runs the opposite way round the folder outline, so the non-zero fill
     * leaves it transparent. Deliberately not the cloud glyph, which already means Pull.
     */
    val Clone: ImageVector by lazy {
        icon(
            "Clone",
            "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z" +
                "M10.5,9.5h3v3.5h2.2L12,17.2 8.3,13h2.2z",
        )
    }
    val Sync: ImageVector by lazy {
        icon(
            "Sync",
            "M12,4V1L8,5l4,4V6c3.31,0 6,2.69 6,6 0,1.01 -0.25,1.97 -0.7,2.8l1.46,1.46C19.54,15.03 20,13.57" +
                " 20,12c0,-4.42 -3.58,-8 -8,-8zM12,18c-3.31,0 -6,-2.69 -6,-6 0,-1.01 0.25,-1.97 0.7,-2.8L5.24,7.74" +
                "C4.46,8.97 4,10.43 4,12c0,4.42 3.58,8 8,8v3l4,-4 -4,-4v3z",
        )
    }
    val Key: ImageVector by lazy {
        icon(
            "Key",
            "M12.65,10C11.83,7.67 9.61,6 7,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6c2.61,0 4.83,-1.67 5.65,-4H17v4h4" +
                "v-4h2v-4H12.65zM7,14c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2z",
        )
    }
    val Person: ImageVector by lazy {
        icon(
            "Person",
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2" +
                "c0,-2.66 -5.33,-4 -8,-4z",
        )
    }
    /** git rebase entry point: a short commit chain with an arrow moving it up onto a new base (distinct from [Merge]). */
    val Rebase: ImageVector by lazy {
        icon(
            "Rebase",
            "M12,2.5L17.5,8.5H6.5z M11.25,8V21H12.75V8z " +
                "M9.6,13.5a2.4,2.4 0 1,0 4.8,0a2.4,2.4 0 1,0 -4.8,0z " +
                "M9.6,19a2.4,2.4 0 1,0 4.8,0a2.4,2.4 0 1,0 -4.8,0z",
        )
    }
    /** Material "compare": two panels side by side -- used for "Diff". */
    val Diff: ImageVector by lazy {
        icon(
            "Diff",
            "M10,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h5v2h2V1h-2v2zM10,18H5l5,-6v6zM19,3h-5v2h5v13l-5,-6v9h5c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z",
        )
    }
    /** git merge (Material Symbols "merge"): two lines joining into one. */
    val Merge: ImageVector by lazy {
        icon(
            "Merge",
            "M6.41,21L5,19.59l4.83,-4.83c0.75,-0.75 1.17,-1.77 1.17,-2.83V8.83L9.41,10.41L8,9l4,-4 4,4 -1.42,1.41L13,8.83v3.09c0,1.06 0.42,2.08 1.17,2.83L19,19.59 17.59,21 12,15.41 6.41,21z",
        )
    }
    val Archive: ImageVector by lazy {
        icon(
            "Archive",
            "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.15,0.55L3.46,5.23C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27zM12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5zM5.12,5l0.81,-1h12l0.94,1H5.12z",
        )
    }
    val Label: ImageVector by lazy {
        icon("Label", "M17.63,5.84C17.27,5.33 16.67,5 16,5L5,5.01C3.9,5.01 3,5.9 3,7v10c0,1.1 0.9,1.99 2,1.99L16,19c0.67,0 1.27,-0.33 1.63,-0.84L22,12l-4.37,-6.16z")
    }
    val GitHub: ImageVector by lazy {
        icon("GitHub", "M12,0.297c-6.63,0 -12,5.373 -12,12 0,5.303 3.438,9.8 8.205,11.385 0.6,0.113 0.82,-0.258 0.82,-0.577 0,-0.285 -0.01,-1.04 -0.015,-2.04 -3.338,0.724 -4.042,-1.61 -4.042,-1.61C4.422,18.07 3.633,17.7 3.633,17.7c-1.087,-0.744 0.084,-0.729 0.084,-0.729 1.205,0.084 1.838,1.236 1.838,1.236 1.07,1.835 2.809,1.305 3.495,0.998 0.108,-0.776 0.417,-1.305 0.76,-1.605 -2.665,-0.3 -5.466,-1.332 -5.466,-5.93 0,-1.31 0.465,-2.38 1.235,-3.22 -0.135,-0.303 -0.54,-1.523 0.105,-3.176 0,0 1.005,-0.322 3.3,1.23 0.96,-0.267 1.98,-0.399 3,-0.405 1.02,0.006 2.04,0.138 3,0.405 2.28,-1.552 3.285,-1.23 3.285,-1.23 0.645,1.653 0.24,2.873 0.12,3.176 0.765,0.84 1.23,1.91 1.23,3.22 0,4.61 -2.805,5.625 -5.475,5.92 0.42,0.36 0.81,1.096 0.81,2.22 0,1.606 -0.015,2.896 -0.015,3.286 0,0.315 0.21,0.69 0.825,0.57C20.565,22.092 24,17.592 24,12.297c0,-6.627 -5.373,-12 -12,-12")
    }
    val Cloud: ImageVector by lazy {
        icon("Cloud", "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z")
    }
    val Remove: ImageVector by lazy { icon("Remove", "M19,13H5v-2h14v2z") }
    val ClearAll: ImageVector by lazy { icon("ClearAll", "M5,13h14v-2H5v2zM3,17h14v-2H3v2zM7,7v2h14V7H7z") }
    val Tab: ImageVector by lazy {
        icon("Tab", "M21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19H3V5h10v4h8v10z")
    }
    /** "Close others": Material "layers", scaled down so it sits at the same visual size as the other menu icons. */
    val CloseOthers: ImageVector by lazy {
        iconScaled(
            "CloseOthers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
            scale = 0.8f,
        )
    }
    val ContentCut: ImageVector by lazy {
        icon("ContentCut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7V3z")
    }
    val ContentPaste: ImageVector by lazy {
        icon("ContentPaste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z")
    }
    val Link: ImageVector by lazy {
        icon("Link", "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7c-1.71,0 -3.1,-1.39 -3.1,-3.1zM8,13h8v-2H8v2zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5s-2.24,-5 -5,-5z")
    }
    val NoteAdd: ImageVector by lazy {
        icon("NoteAdd", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,14h-3v3h-2v-3H8v-2h3v-3h2v3h3v2zM13,9V3.5L18.5,9H13z")
    }
    val CreateNewFolder: ImageVector by lazy {
        icon("CreateNewFolder", "M20,6h-8l-2,-2H4c-1.11,0 -1.99,0.89 -1.99,2L2,18c0,1.11 0.89,2 2,2h16c1.11,0 2,-0.89 2,-2V8c0,-1.11 -0.89,-2 -2,-2zM19,14h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z")
    }
    val FileCopy: ImageVector by lazy {
        icon("FileCopy", "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM15,5l6,6v10c0,1.1 -0.9,2 -2,2H7.99C6.89,23 6,22.1 6,21l0.01,-14c0,-1.1 0.89,-2 1.99,-2h7zM14,12h5.5L14,6.5V12z")
    }
    val MyLocation: ImageVector by lazy {
        icon("MyLocation", "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94V1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11H1v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94V23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94H23v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    }
    val AccountTree: ImageVector by lazy {
        icon(
            "AccountTree",
            "M22,11V3h-7v3H9V3H2v8h7V7h2v10h4v3h7v-8h-7v3h-2V7h2v4h7z",
        )
    }

    /** Collapse-all entry point (Material Symbols "unfold_less" — chevrons closing together). */
    val UnfoldLess: ImageVector by lazy {
        icon(
            "UnfoldLess",
            "M7.41,18.59L8.83,20 12,16.83 15.17,20l1.41,-1.41L12,14z" +
                "M16.59,5.41L15.17,4 12,7.17 8.83,4 7.41,5.41 12,10z",
        )
    }

    /** Collapse-all (Material "vertical_align_center": arrows folding in to a centre line). */
    val CollapseAll: ImageVector by lazy {
        icon(
            "CollapseAll",
            "M8,19h3v4h2v-4h3l-4,-4 -4,4zM16,5h-3V1h-2v4H8l4,4 4,-4zM4,11v2h16v-2H4z",
        )
    }

    /** git reset entry point (Material Symbols "restore" — clock with a back arrow). */
    val Restore: ImageVector by lazy {
        icon(
            "Restore",
            "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7" +
                " -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0" +
                " 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8z",
        )
    }

    // Onboarding (startup permission stepper): storage/notification steps.
    /** Notification step (Material Symbols "notifications" — a bell). */
    val Notifications: ImageVector by lazy {
        icon(
            "Notifications",
            "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2z" +
                "M18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5s-1.5,0.67 -1.5,1.5v0.68" +
                "C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z",
        )
    }


    /** Info step marker (Material Symbols "info"). */
    val Info: ImageVector by lazy {
        icon(
            "Info",
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
                "M13,17h-2v-6h2v6zM13,9h-2V7h2v2z",
        )
    }

    /** Forward step navigation (Material Symbols "arrow_forward"). */
    val ArrowForward: ImageVector by lazy {
        icon(
            "ArrowForward",
            "M12,4l-1.41,1.41L16.17,11H4v2h12.17l-5.58,5.59L12,20l8,-8z",
        )
    }

    private fun iconScaled(name: String, pathData: String, scale: Float): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
            .addGroup(scaleX = scale, scaleY = scale, pivotX = 12f, pivotY = 12f)
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
            )
            .clearGroup()
            .build()

    /** Same as [icon] for glyphs drawn on a 16 x 16 grid (even-odd fill so the node holes stay open). */
    private fun icon16(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 16f,
            viewportHeight = 16f,
        )
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                pathFillType = PathFillType.EvenOdd,
                fill = SolidColor(Color.Black),
            )
            .build()

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
            .addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black),
            )
            .build()
    // M17 AI Assistant icons.
    val AutoAwesome: ImageVector = MaterialSymbols.RoundedFilled.Auto_awesome
    val Send: ImageVector = MaterialSymbols.RoundedFilled.Send
    val StopCircle: ImageVector = MaterialSymbols.RoundedFilled.Stop_circle
}
