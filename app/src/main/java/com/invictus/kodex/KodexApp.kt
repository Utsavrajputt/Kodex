package com.invictus.kodex

import android.app.Application
import com.invictus.kodex.core.diagnostics.GitLog
import com.invictus.kodex.core.diagnostics.CrashHandler
import com.invictus.kodex.core.git.installGitByteCounting
import com.invictus.kodex.core.git.installGitPerformanceTuning
import com.invictus.kodex.di.AppContainer
import com.invictus.kodex.github.notify.GhBackground
import com.invictus.kodex.github.notify.GhNotifier
import com.invictus.kodex.github.notify.GitPushEvents
import com.invictus.kodex.github.notify.RunWatchStarter
import kotlinx.coroutines.launch
import com.invictus.kodex.ui.theme.ThemeSettings

class KodexApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.invictus.kodex.ai.AiSwitch.init(this)
        com.invictus.kodex.ai.AiFlavor.init(this)
        com.invictus.kodex.ai.usage.UsageStore.init(this)
        CrashHandler.install(this)
        GitLog.init(this)
        ThemeSettings.init(this)
        installGitByteCounting()
        installGitPerformanceTuning()
        container = AppContainer(this)
        // A new process has no download / watch running, so a "Downloading ..." notification left behind by a
        // killed or replaced (self-installed) process is stale: remove it.
        GhNotifier(this).clearLive()
        // A push may start a build: follow it in the background so its end is announced even from recents.
        GhBackground.scope.launch { GitPushEvents.pushed.collect { RunWatchStarter.afterPush(this@KodexApp, it) } }
    }
}
