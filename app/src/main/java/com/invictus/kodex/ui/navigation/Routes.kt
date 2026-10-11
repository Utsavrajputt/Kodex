package com.invictus.kodex.ui.navigation

object Routes {
    const val PERMISSION = "permission"
    const val HOME = "home"
    const val WORKSPACE = "workspace"
    const val ALL_PROJECTS = "all_projects"
    const val ALL_APKS = "all_apks"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings/appearance"
    const val SETTINGS_EDITING = "settings/editing"
    const val SETTINGS_BEHAVIOR = "settings/behavior"
    const val SETTINGS_DIAGNOSTICS = "settings/diagnostics"
    const val SETTINGS_GIT_LOGS = "settings/git_logs"
    const val SETTINGS_CRASH_LOGS = "settings/crash_logs"
    const val SETTINGS_GITHUB = "settings/github"
    const val SETTINGS_ABOUT = "settings/about"
    const val SETTINGS_LIBRARIES = "settings/libraries"
    const val MEDIA_PREVIEW = "media_preview"
    const val GIT = "git/{projectPath}"
    const val GIT_CLONE = "git_clone"
    const val GIT_BRANCHES = "git_branches/{projectPath}"
    const val GIT_HISTORY = "git_history/{projectPath}?path={path}"
    const val GIT_STASH = "git_stash/{projectPath}"
    const val GIT_TAGS = "git_tags/{projectPath}"
    const val GIT_REMOTES = "git_remotes/{projectPath}"
    const val GIT_CREDENTIALS = "git_credentials/{projectPath}"
    const val GIT_ONBOARDING = "git_onboarding/{projectPath}"
    const val CODE_SEARCH = "code_search/{projectPath}"
    const val GITHUB = "github/{projectPath}"
    const val GITHUB_RUN_LOG = "github_run_log/{projectPath}/{runId}"

    /** Encoded deep-link style route for a project's Source Control screen. */
    fun git(projectPath: String): String =
        "git/${android.net.Uri.encode(projectPath)}"

    fun gitBranches(p: String) = "git_branches/${android.net.Uri.encode(p)}"

    fun gitHistory(p: String, path: String? = null) =
        "git_history/${android.net.Uri.encode(p)}" +
            (path?.let { "?path=${android.net.Uri.encode(it)}" } ?: "")

    fun gitStash(p: String) = "git_stash/${android.net.Uri.encode(p)}"
    fun gitTags(p: String) = "git_tags/${android.net.Uri.encode(p)}"
    fun gitRemotes(p: String) = "git_remotes/${android.net.Uri.encode(p)}"
    fun gitCredentials(p: String) = "git_credentials/${android.net.Uri.encode(p)}"
    fun gitOnboarding(p: String) = "git_onboarding/${android.net.Uri.encode(p)}"

    fun codeSearch(p: String) = "code_search/${android.net.Uri.encode(p)}"

    fun github(p: String) = "github/${android.net.Uri.encode(p)}"
    fun githubRunLog(p: String, runId: Long) = "github_run_log/${android.net.Uri.encode(p)}/$runId"
}
