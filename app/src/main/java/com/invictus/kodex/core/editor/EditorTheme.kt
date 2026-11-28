package com.invictus.kodex.core.editor

/**
 * One selectable TextMate colour scheme. [assetName] matches `assets/textmate/<assetName>.json`.
 *
 * [preview] carries a handful of representative token colours (background, comment, string,
 * keyword, function-name) pulled from that same JSON, used to draw a mini code mock-up in the
 * theme picker without having to parse the TextMate JSON at UI time.
 */
data class EditorTheme(
    val id: String,
    val displayName: String,
    val assetName: String,
    val isDark: Boolean,
    val preview: EditorThemePreviewColors,
)

/** Representative syntax colours for a theme, used to render its picker preview card. */
data class EditorThemePreviewColors(
    val background: Long,
    val foreground: Long,
    val comment: Long,
    val string: Long,
    val keyword: Long,
    val function: Long,
)

/** One theme with both a dark and a light variant; the picker shows a single card per family. */
data class EditorThemeFamily(
    val id: String,
    val name: String,
    val dark: EditorTheme,
    val light: EditorTheme,
) {
    fun variant(dark: Boolean): EditorTheme = if (dark) this.dark else this.light
}

/**
 * Editor colour themes. Each family has a dark and a light variant; the variant used follows the
 * app's light/dark mode, so the picker only shows one card per family (Light/Dark chips on top
 * just switch which variant the cards preview).
 */
object EditorThemes {
    /** Not a real theme id: means "follow the app's own light/dark", the pre-M3-part-3 behavior. */
    const val SYSTEM_DEFAULT = "system"

    /** Theme family used until the user picks one (fresh installs / nothing saved yet). */
    const val DEFAULT_ID = "github"

    private fun c(bg: Long, fg: Long, comment: Long, string: Long, keyword: Long, function: Long) =
        EditorThemePreviewColors(bg, fg, comment, string, keyword, function)

    private val DARCULA = EditorTheme("darcula", "Darcula", "darcula", true,
        c(0xFF2B2B2B, 0xFFA9B7C6, 0xFF808080, 0xFF6A8759, 0xFFCC7832, 0xFFFFC66D))
    private val INTELLIJ_LIGHT = EditorTheme("darcula-light", "IntelliJ Light", "intellij-light", false,
        c(0xFFFFFFFF, 0xFF080808, 0xFF8C8C8C, 0xFF067D17, 0xFF0033B3, 0xFF00627A))
    private val ECLIPSE = EditorTheme("eclipse", "Eclipse", "eclipse", false,
        c(0xFFFFFFFF, 0xFF000000, 0xFF3F7F5F, 0xFF2A00FF, 0xFF7F0055, 0xFF000000))
    private val ECLIPSE_DARK = EditorTheme("eclipse-dark", "Eclipse Dark", "eclipse-dark", true,
        c(0xFF2F2F2F, 0xFFE6E6E6, 0xFF6E9F82, 0xFF8CB4FF, 0xFFD896C8, 0xFFE6E6E6))
    private val AYU_DARK = EditorTheme("ayu-dark", "Ayu Dark", "ayu-dark", true,
        c(0xFF0A0E14, 0xFFB3B1AD, 0xFF5C6773, 0xFFC2D94C, 0xFFFF8F40, 0xFFFFB454))
    private val AYU_LIGHT = EditorTheme("ayu-light", "Ayu Light", "ayu-light", false,
        c(0xFFFAFAFA, 0xFF5C6166, 0xFFABB0B6, 0xFF86B300, 0xFFFA8D3E, 0xFFF2AE49))
    private val MONOKAI = EditorTheme("monokai", "Monokai", "monokai", true,
        c(0xFF272822, 0xFFF8F8F2, 0xFF75715E, 0xFFE6DB74, 0xFFF92672, 0xFFA6E22E))
    private val MONOKAI_LIGHT = EditorTheme("monokai-light", "Monokai Light", "monokai-light", false,
        c(0xFFFAFAFA, 0xFF272822, 0xFF8F8B72, 0xFFA08E00, 0xFFD5165E, 0xFF5F9A00))
    private val SOLARIZED_DARK = EditorTheme("solarized-dark", "Solarized Dark", "solarized-dark", true,
        c(0xFF002B36, 0xFF839496, 0xFF586E75, 0xFF2AA198, 0xFF859900, 0xFF268BD2))
    private val SOLARIZED_LIGHT = EditorTheme("solarized-light", "Solarized Light", "solarized-light", false,
        c(0xFFFDF6E3, 0xFF657B83, 0xFF93A1A1, 0xFF2AA198, 0xFF859900, 0xFF268BD2))
    private val GITHUB_DARK = EditorTheme("github-dark", "GitHub Dark", "github-dark", true,
        c(0xFF0D1117, 0xFFC9D1D9, 0xFF8B949E, 0xFFA5D6FF, 0xFFFF7B72, 0xFFD2A8FF))
    private val GITHUB_LIGHT = EditorTheme("github-light", "GitHub Light", "github-light", false,
        c(0xFFFFFFFF, 0xFF24292E, 0xFF6A737D, 0xFF032F62, 0xFFD73A49, 0xFF6F42C1))
    private val VSCODE_DARK = EditorTheme("vscode-dark", "VS Code Dark+", "vscode-dark", true,
        c(0xFF1E1E1E, 0xFFD4D4D4, 0xFF6A9955, 0xFFCE9178, 0xFF569CD6, 0xFFDCDCAA))
    private val VSCODE_LIGHT = EditorTheme("vscode-light", "VS Code Light+", "vscode-light", false,
        c(0xFFFFFFFF, 0xFF000000, 0xFF008000, 0xFFA31515, 0xFF0000FF, 0xFF795E26))
    private val ONE_DARK = EditorTheme("one-dark-pro", "One Dark Pro", "one-dark-pro", true,
        c(0xFF282C34, 0xFFABB2BF, 0xFF7F848E, 0xFF98C379, 0xFFC678DD, 0xFF61AFEF))
    private val ONE_LIGHT = EditorTheme("one-light", "One Light", "one-light", false,
        c(0xFFFAFAFA, 0xFF383A42, 0xFFA0A1A7, 0xFF50A14F, 0xFFA626A4, 0xFF4078F2))
    private val CATPPUCCIN_MOCHA = EditorTheme("catppuccin-mocha", "Catppuccin Mocha", "catppuccin-mocha", true,
        c(0xFF1E1E2E, 0xFFCDD6F4, 0xFF9399B2, 0xFFA6E3A1, 0xFFCBA6F7, 0xFF89B4FA))
    private val CATPPUCCIN_LATTE = EditorTheme("catppuccin-latte", "Catppuccin Latte", "catppuccin-latte", false,
        c(0xFFEFF1F5, 0xFF4C4F69, 0xFF7C7F93, 0xFF40A02B, 0xFF8839EF, 0xFF1E66F5))
    private val ROSE_PINE = EditorTheme("rose-pine", "Rosé Pine", "rose-pine", true,
        c(0xFF191724, 0xFFE0DEF4, 0xFF6E6A86, 0xFFF6C177, 0xFF31748F, 0xFFEBBCBA))
    private val ROSE_PINE_DAWN = EditorTheme("rose-pine-dawn", "Rosé Pine Dawn", "rose-pine-dawn", false,
        c(0xFFFAF4ED, 0xFF575279, 0xFF9893A5, 0xFFEA9D34, 0xFF286983, 0xFFD7827E))
    private val TOKYO_NIGHT = EditorTheme("tokyo-night", "Tokyo Night", "tokyo-night", true,
        c(0xFF1A1B26, 0xFFC0CAF5, 0xFF565F89, 0xFF9ECE6A, 0xFFBB9AF7, 0xFF7AA2F7))
    private val TOKYO_NIGHT_DAY = EditorTheme("tokyo-night-day", "Tokyo Night Day", "tokyo-night-day", false,
        c(0xFFE1E2E7, 0xFF3760BF, 0xFF848CB5, 0xFF587539, 0xFF9854F1, 0xFF2E7DE9))
    private val GRUVBOX_DARK = EditorTheme("gruvbox-dark", "Gruvbox Dark", "gruvbox-dark", true,
        c(0xFF282828, 0xFFEBDBB2, 0xFF928374, 0xFFB8BB26, 0xFFFB4934, 0xFFB8BB26))
    private val GRUVBOX_LIGHT = EditorTheme("gruvbox-light", "Gruvbox Light", "gruvbox-light", false,
        c(0xFFFBF1C7, 0xFF3C3836, 0xFF928374, 0xFF79740E, 0xFF9D0006, 0xFF79740E))

    // Picker order: System default (not listed here) -> GitHub -> VS Code -> the rest.
    val FAMILIES: List<EditorThemeFamily> = listOf(
        EditorThemeFamily("github", "GitHub", GITHUB_DARK, GITHUB_LIGHT),
        EditorThemeFamily("vscode", "VS Code", VSCODE_DARK, VSCODE_LIGHT),
        EditorThemeFamily("one-dark-pro", "One Dark Pro", ONE_DARK, ONE_LIGHT),
        EditorThemeFamily("catppuccin", "Catppuccin", CATPPUCCIN_MOCHA, CATPPUCCIN_LATTE),
        EditorThemeFamily("rose-pine", "Rosé Pine", ROSE_PINE, ROSE_PINE_DAWN),
        EditorThemeFamily("tokyo-night", "Tokyo Night", TOKYO_NIGHT, TOKYO_NIGHT_DAY),
        EditorThemeFamily("gruvbox", "Gruvbox", GRUVBOX_DARK, GRUVBOX_LIGHT),
        EditorThemeFamily("darcula", "Darcula", DARCULA, INTELLIJ_LIGHT),
        EditorThemeFamily("eclipse", "Eclipse", ECLIPSE_DARK, ECLIPSE),
        EditorThemeFamily("ayu", "Ayu", AYU_DARK, AYU_LIGHT),
        EditorThemeFamily("monokai", "Monokai", MONOKAI, MONOKAI_LIGHT),
        EditorThemeFamily("solarized", "Solarized", SOLARIZED_DARK, SOLARIZED_LIGHT),
    )

    /** Every variant; TextMate loads all of them up front. */
    val ALL: List<EditorTheme> = FAMILIES.flatMap { listOf(it.dark, it.light) }

    fun find(id: String): EditorTheme? = ALL.firstOrNull { it.id == id }

    /** Family for a family id or for any legacy per-variant id saved by an older build. */
    fun familyOf(id: String): EditorThemeFamily? =
        FAMILIES.firstOrNull { it.id == id || it.dark.id == id || it.light.id == id }

    /** The variant to actually render for [id] given the app's current light/dark mode. */
    fun resolve(id: String, dark: Boolean): EditorTheme? = familyOf(id)?.variant(dark)
}
