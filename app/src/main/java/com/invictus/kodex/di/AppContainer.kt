package com.invictus.kodex.di

import android.content.Context
import com.invictus.kodex.core.editor.EditorSessionStore
import com.invictus.kodex.core.editor.EditorSettingsStore
import com.invictus.kodex.core.editor.TextMateSupport
import com.invictus.kodex.core.fs.FileOpenPolicy
import com.invictus.kodex.core.fs.FileOps
import com.invictus.kodex.core.data.AppDatabase
import com.invictus.kodex.core.git.GitOnboardingPrefs
import com.invictus.kodex.core.github.AvatarImageLoader
import com.invictus.kodex.core.github.GitHubProfileRepository
import com.invictus.kodex.core.fs.StoragePermission
import com.invictus.kodex.core.permission.NotificationPermission
import com.invictus.kodex.core.permission.OnboardingPrefs
import com.invictus.kodex.core.project.ProjectBackup
import com.invictus.kodex.core.project.ProjectRepository
import com.invictus.kodex.core.search.CodeSearchEngine
import com.invictus.kodex.core.search.FileSearchEngine
import com.invictus.kodex.core.search.SearchHistoryStore
import com.invictus.kodex.core.git.GitCloneManager
import com.invictus.kodex.core.security.GitCredentialStore
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubApi
import com.invictus.kodex.github.api.GitHubHttp
import com.invictus.kodex.github.auth.GitHubTokenProvider
import com.invictus.kodex.github.data.GitHubProjectResolver
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.github.prefs.GitHubPrefs
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.io.File

/**
 * Manual dependency container (no Hilt/Koin, same approach as xmd).
 * Add each new app-wide dependency here as a `val` (use `by lazy` for heavy ones);
 * ViewModels pull what they need from it through their Factory.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val storagePermission: StoragePermission by lazy { StoragePermission(appContext) }

    val notificationPermission: NotificationPermission by lazy { NotificationPermission(appContext) }


    val onboardingPrefs: OnboardingPrefs by lazy { OnboardingPrefs(appContext) }

    val fileOps: FileOps by lazy { FileOps() }

    val fileOpenPolicy: FileOpenPolicy by lazy { FileOpenPolicy() }

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val projectRepository: ProjectRepository by lazy { ProjectRepository(database) }

    val projectBackup: ProjectBackup by lazy { ProjectBackup() }

    val textMate: TextMateSupport by lazy { TextMateSupport(appContext) }

    val editorSessionStore: EditorSessionStore by lazy { EditorSessionStore(appContext) }

    val editorSettingsStore: EditorSettingsStore by lazy { EditorSettingsStore(appContext) }

    val gitCredentialStore: GitCredentialStore by lazy { GitCredentialStore(appContext) }

    /** Owns the running git clone so it outlives the Clone screen. */
    val gitCloneManager: GitCloneManager by lazy { GitCloneManager(appContext, gitCredentialStore, editorSettingsStore) }

    val gitOnboardingPrefs: GitOnboardingPrefs by lazy { GitOnboardingPrefs(appContext) }

    // GitHub profile on token cards: live /user fetch (24h refresh) + avatar loader (72h disk cache).
    val gitHubProfileRepository: GitHubProfileRepository by lazy { GitHubProfileRepository(appContext) }
    val avatarImageLoader: coil.ImageLoader by lazy { AvatarImageLoader.create(appContext) }

    // M15 GitHub Manager: reuses the git token (no second store); one shared HTTP stack.
    val gitHubTokenProvider: GitHubTokenProvider by lazy { GitHubTokenProvider(gitCredentialStore) }
    val gitHubPrefs: GitHubPrefs by lazy { GitHubPrefs(appContext) }
    val gitHubProjectResolver: GitHubProjectResolver by lazy { GitHubProjectResolver(gitHubTokenProvider) }
    private val gitHubOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Fresh repository (own ETag cache) bound to one project's repo. */
    fun gitHubRepository(repo: GhRepoRef): GitHubRepository {
        val http = GitHubHttp(gitHubOkHttp, token = { gitHubTokenProvider.token() })
        return GitHubRepository(GitHubApi(http, repo), repo, java.io.File(appContext.cacheDir, "gh_logs"))
    }

    // M11 search: fuzzy file search + workspace grep + Room-backed search history.
    val fileSearchEngine: FileSearchEngine by lazy { FileSearchEngine() }
    val codeSearchEngine: CodeSearchEngine by lazy { CodeSearchEngine() }
    val searchHistoryStore: SearchHistoryStore by lazy { SearchHistoryStore(database) }

    /** App-level (global) git identity file holding user.name / user.email. */
    val gitGlobalIdentityFile: File by lazy { File(appContext.filesDir, "git_identity") }
}
