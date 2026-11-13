package com.invictus.kodex.core.git

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.ai.notify.AppVisibility
import com.invictus.kodex.core.git.model.GitAuthFailureType
import com.invictus.kodex.core.git.model.GitErrorDetails
import com.invictus.kodex.core.git.model.GitErrorKind
import com.invictus.kodex.core.git.model.isAuthError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Which network operation a background notification is about. */
enum class GitWorkOp { PUSH, PULL, FETCH }

/** The one-tap fix offered on a failure notification. */
enum class GitRetryKind { PULL_THEN_PUSH, FETCH }

/**
 * Runs push / pull / fetch outside any screen. A ViewModel's scope dies the moment you leave the Git screen, and
 * a minimized app is frozen by Android, so both used to stop the operation half way. Here the work lives in an
 * app-wide scope, holds its own reference to the repository, and keeps a foreground service (with a small
 * notification) up for as long as something is running.
 */
object GitRemoteWork {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var appContext: Context? = null
    private var nextId = 0
    private val active = LinkedHashMap<Int, String>()

    const val NOTIF_ID = 7410
    private const val RESULT_ID = 7411
    private const val CHANNEL = "git_remote_work"
    private const val CHANNEL_RESULT = "git_remote_result"

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Starts [block] (a network operation on the repository at [workTree]) so it outlives the caller.
     * [label] is a string taking the project name, e.g. "Pushing %1$s".
     */
    fun launch(@StringRes label: Int, workTree: File, block: suspend () -> Unit): Job {
        val ctx = appContext
        val text = ctx?.getString(label, workTree.name) ?: workTree.name
        val id = synchronized(lock) {
            val i = ++nextId
            active[i] = text
            if (active.size == 1) startService(ctx) else refreshNotification(ctx)
            i
        }
        // The screen's own reference may be released while this runs; this one keeps the repository open.
        GitSessionRegistry.acquire(workTree)
        return scope.launch {
            try {
                block()
            } finally {
                GitSessionRegistry.release(workTree)
                val idle = synchronized(lock) {
                    active.remove(id)
                    if (active.isNotEmpty()) refreshNotification(ctx)
                    active.isEmpty()
                }
                if (idle) {
                    // Stopping a service that hasn't called startForeground yet crashes: give it a moment.
                    delay(STOP_DELAY_MS)
                    if (synchronized(lock) { active.isEmpty() }) ctx?.stopService(Intent(ctx, GitWorkService::class.java))
                }
            }
        }
    }

    internal fun isIdle(): Boolean = synchronized(lock) { active.isEmpty() }

    /** A short result for an operation that finished while the app was not on screen: a toast on success, a notification on failure. */
    fun notifyDone(@StringRes text: Int, project: String, ok: Boolean) {
        val ctx = appContext ?: return
        if (AppVisibility.visible) return
        if (ok) {
            // Success is just a toast; only a failure deserves a notification that stays.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, ctx.getString(text, project), android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(if (ok) R.drawable.ic_stat_check else R.drawable.ic_stat_error)
            .setColor(ContextCompat.getColor(ctx, if (ok) R.color.notif_accent else R.color.notif_error))
            .setContentTitle(ctx.getString(text, project))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        post(ctx, RESULT_ID, n)
    }

    /**
     * A push / pull / fetch that stopped, said in words: what it was, and why when that is known (the remote has
     * newer commits -> pull first; an unfetched remote -> fetch first; sign-in). Those two get a button that fixes
     * it in the background. Skipped while the app is on screen (it already shows the error itself).
     */
    fun notifyFailure(
        op: GitWorkOp,
        error: GitErrorDetails,
        workTree: File,
        remote: String,
        branch: String?,
    ) {
        val ctx = appContext ?: return
        if (AppVisibility.visible) return
        val project = workTree.name
        val auth = error.authFailure.isAuthError()
        val text: String
        var retry: GitRetryKind? = null
        when {
            auth -> text = ctx.getString(R.string.git_work_failed_auth, project)
            op == GitWorkOp.PUSH && error.kind == GitErrorKind.PUSH_REJECTED -> {
                text = ctx.getString(R.string.git_work_failed_push_rejected, project)
                retry = GitRetryKind.PULL_THEN_PUSH
            }
            op == GitWorkOp.PUSH && error.kind == GitErrorKind.UNFETCHED_COMMITS -> {
                text = ctx.getString(R.string.git_work_failed_fetch_first, project, remote)
                retry = GitRetryKind.FETCH
            }
            else -> text = ctx.getString(
                when (op) {
                    GitWorkOp.PUSH -> R.string.git_work_failed_push
                    GitWorkOp.PULL -> R.string.git_work_failed_pull
                    GitWorkOp.FETCH -> R.string.git_work_failed_fetch
                },
                project,
            )
        }
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setColor(ContextCompat.getColor(ctx, R.color.notif_error))
            .setContentTitle(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        if (retry != null) {
            b.addAction(
                R.drawable.ic_stat_download,
                ctx.getString(
                    if (retry == GitRetryKind.PULL_THEN_PUSH) R.string.git_work_action_pull_push else R.string.git_work_action_fetch,
                ),
                retryIntent(ctx, retry, workTree, remote, branch),
            )
        }
        post(ctx, RESULT_ID, b.build())
    }

    /**
     * The notification's Pull & push / Fetch button: runs it in the background like the screen would (credentials
     * from the token store), and reports the outcome the same way (toast on success, notification on failure).
     */
    fun retry(kind: GitRetryKind, workTree: File, remote: String, branch: String?) {
        val ctx = appContext ?: return
        val container = (ctx as? KodexApp)?.container ?: return
        NotificationManagerCompat.from(ctx).cancel(RESULT_ID)
        // Opened with the app-wide identity first, so a pull's merge commit is authored properly.
        val session = GitSessionRegistry.acquire(workTree, container.gitGlobalIdentityFile)
        launch(if (kind == GitRetryKind.PULL_THEN_PUSH) R.string.git_work_pull else R.string.git_work_fetch, workTree) {
            try {
                val host = session.remoteUrl(remote)?.let(::normalizeHost)
                val credential = host?.let { container.gitCredentialStore.get(it) }
                if (host != null && credential == null) {
                    notifyFailure(
                        GitWorkOp.PULL,
                        GitErrorDetails("", "", "", "", authFailure = GitAuthFailureType.AUTH_REQUIRED),
                        workTree, remote, branch,
                    )
                    return@launch
                }
                val cp = credential?.let { GitTokenCredentialsProvider(it.username, it.token) }
                when (kind) {
                    GitRetryKind.FETCH -> when (val r = session.fetch(remote, cp)) {
                        is GitResult.Ok -> notifyDone(R.string.git_work_done_fetch, workTree.name, ok = true)
                        is GitResult.Err -> notifyFailure(GitWorkOp.FETCH, r.error, workTree, remote, branch)
                    }
                    GitRetryKind.PULL_THEN_PUSH -> when (val pulled = session.pull(remote, branch, cp, rebase = false)) {
                        is GitResult.Err -> notifyFailure(GitWorkOp.PULL, pulled.error, workTree, remote, branch)
                        is GitResult.Ok -> when (val pushed = session.push(remote, cp)) {
                            is GitResult.Ok -> notifyDone(R.string.git_work_done_pull_push, workTree.name, ok = true)
                            is GitResult.Err -> notifyFailure(GitWorkOp.PUSH, pushed.error, workTree, remote, branch)
                        }
                    }
                }
            } finally {
                GitSessionRegistry.release(workTree)
            }
        }
    }

    private fun retryIntent(ctx: Context, kind: GitRetryKind, workTree: File, remote: String, branch: String?): PendingIntent {
        val intent = Intent(ctx, GitRetryReceiver::class.java)
            .setAction("com.invictus.kodex.git.RETRY_${kind.name}")
            .putExtra(GitRetryReceiver.EXTRA_KIND, kind.name)
            .putExtra(GitRetryReceiver.EXTRA_PATH, workTree.absolutePath)
            .putExtra(GitRetryReceiver.EXTRA_REMOTE, remote)
            .putExtra(GitRetryReceiver.EXTRA_BRANCH, branch)
        return PendingIntent.getBroadcast(
            ctx, 100 + kind.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    internal fun notification(ctx: Context): Notification {
        ensureChannels(ctx)
        val text = synchronized(lock) {
            when (active.size) {
                0 -> ""
                1 -> active.values.first()
                else -> ctx.getString(R.string.git_work_multi, active.size)
            }
        }
        val title = ctx.getString(R.string.git_work_title)
        // Own body so the progress bar sits above the text (the stock progress style always draws it below).
        val body = RemoteViews(ctx.packageName, R.layout.notif_git_progress).apply {
            setTextViewText(R.id.git_title, title)
            setTextViewText(R.id.git_text, text)
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(ContextCompat.getColor(ctx, R.color.notif_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(body)
            .setCustomBigContentView(body)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(ctx))
            .build()
    }

    private fun startService(ctx: Context?) {
        ctx ?: return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, GitWorkService::class.java))
        } catch (_: Exception) {
            // Not allowed right now: the work still runs, just without process protection.
        }
    }

    private fun refreshNotification(ctx: Context?) {
        ctx ?: return
        post(ctx, NOTIF_ID, notification(ctx))
    }

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.git_work_channel), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, ctx.getString(R.string.git_work_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    private fun openApp(ctx: Context): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val STOP_DELAY_MS = 1_500L
}

/** Keeps the process alive while [GitRemoteWork] has something running, even when the app is minimized. */
class GitWorkService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                GitRemoteWork.NOTIF_ID,
                GitRemoteWork.notification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Everything already finished before the service came up: it has nothing to protect.
        if (GitRemoteWork.isIdle()) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** The failure notification's Pull & push / Fetch button. Hands over to [GitRemoteWork], which runs it app-wide. */
class GitRetryReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { runCatching { GitRetryKind.valueOf(it) }.getOrNull() } ?: return
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val remote = intent.getStringExtra(EXTRA_REMOTE) ?: return
        GitRemoteWork.retry(kind, File(path), remote, intent.getStringExtra(EXTRA_BRANCH))
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_PATH = "path"
        const val EXTRA_REMOTE = "remote"
        const val EXTRA_BRANCH = "branch"
    }
}
