package com.invictus.kodex.github.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import com.invictus.kodex.MainActivity
import com.invictus.kodex.R

/**
 * Status notifications for every GitHub Manager process (download, install, save, cleanup, dispatch).
 * One "live" notification ([LIVE_ID]) shows the process that is running right now (it is also the
 * foreground-service notification); finished processes post their own, tappable, auto-cancel result.
 */
class GhNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)
    private val accent = ContextCompat.getColor(ctx, R.color.notif_accent)
    private val errorTint = ContextCompat.getColor(ctx, R.color.notif_error)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.gh_notif_channel), NotificationManager.IMPORTANCE_LOW)
            val nmSys = ctx.getSystemService(NotificationManager::class.java)
            nmSys?.createNotificationChannel(ch)
            nmSys?.createNotificationChannel(
                NotificationChannel(CHANNEL_BUILDS, ctx.getString(R.string.gh_notif_channel_builds), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /**
     * Opens the app. With a [projectPath] the tap lands on that project's GitHub screen: an explicit intent to
     * MainActivity (singleTask) so the extra reaches onNewIntent even when the app is only sitting in recents.
     */
    private fun openApp(projectPath: String? = null, runId: Long = 0L): PendingIntent? {
        val intent = if (projectPath != null) {
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_OPEN_GITHUB, projectPath)
                .apply { if (runId > 0L) putExtra(EXTRA_OPEN_RUN, runId) }
        } else {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        }
        val code = if (projectPath != null) projectPath.hashCode() * 31 + runId.hashCode() else 0
        return PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * "Copy error" / "Copy log": a see-through activity does the work, because Android only lets an app that is on
     * screen write the clipboard dependably (a plain receiver is refused on many versions).
     */
    private fun copyIntent(runId: Long, run: Triple<String, String, Int>, mode: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, (runId.hashCode() * 31 + mode.hashCode()),
            Intent(ctx, CopyRunLogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                .putExtra(CopyRunLogActivity.EXTRA_OWNER, run.first)
                .putExtra(CopyRunLogActivity.EXTRA_REPO, run.second)
                .putExtra(CopyRunLogActivity.EXTRA_NUMBER, run.third)
                .putExtra(CopyRunLogActivity.EXTRA_RUN_ID, runId)
                .putExtra(CopyRunLogActivity.EXTRA_MODE, mode),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** "Stop" on the build notification: ends the background watch (and with it the notification, on every Android version). */
    private fun stopWatchingIntent(): PendingIntent =
        PendingIntent.getService(
            ctx, 1,
            Intent(ctx, GhWorkService::class.java).setAction(GhWorkService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** What the live notification currently says; also what the foreground service shows the moment it starts. */
    data class LiveSpec(
        val title: String,
        val text: String,
        val progress: Int?,
        /** Where a tap goes: this project's GitHub screen, and the run's details when [runId] is set. */
        val projectPath: String? = null,
        val runId: Long = 0L,
        /** Adds a Stop button (ends the background build watch). */
        val stoppable: Boolean = false,
        /** Epoch ms the process started; when set the notification shows a live elapsed-time chronometer. */
        val startedAtMs: Long? = null,
        /** Non-zero while an artifact download runs: adds Pause and Cancel buttons for it. */
        val downloadId: Long = 0L,
    )

    /**
     * The live notification. [LiveSpec.progress] 0..100, or null for an indeterminate bar. Not marked ongoing, so
     * it can be swiped away on Android 14+ (older versions keep a foreground notification: use the Stop button).
     */
    fun live(spec: LiveSpec): Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(accent)
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(spec.projectPath, spec.runId))
            .setProgress(100, spec.progress ?: 0, spec.progress == null)
        // BigTextStyle drops the text under the progress bar when expanded. A short text with a real bar (the "34%"
        // of a download) is left in the standard layout so it stays above the bar; longer texts still expand.
        if (spec.progress == null || spec.text.length > 40) b.setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
        spec.startedAtMs?.let { b.setWhen(it.coerceAtMost(System.currentTimeMillis())).setShowWhen(true).setUsesChronometer(true) }
        if (spec.stoppable) b.addAction(0, ctx.getString(R.string.gh_notif_stop_watching), stopWatchingIntent())
        if (spec.downloadId != 0L) {
            b.addAction(0, ctx.getString(R.string.gh_notif_pause), downloadAction(DownloadActionReceiver.ACTION_PAUSE, spec.downloadId))
            b.addAction(0, ctx.getString(R.string.gh_notif_cancel), downloadAction(DownloadActionReceiver.ACTION_CANCEL, spec.downloadId))
        }
        return b.build()
    }

    fun live(title: String, text: String, progress: Int?): Notification = live(LiveSpec(title, text, progress))

    fun updateLive(
        title: String,
        text: String,
        progress: Int?,
        projectPath: String? = null,
        runId: Long = 0L,
        stoppable: Boolean = false,
        startedAtMs: Long? = null,
        downloadId: Long = 0L,
    ) {
        val spec = LiveSpec(title, text, progress, projectPath, runId, stoppable, startedAtMs, downloadId)
        lastLive = spec
        post(LIVE_ID, live(spec))
    }

    private fun downloadAction(action: String, artifactId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (artifactId.hashCode() * 31 + action.hashCode()),
            Intent(ctx, DownloadActionReceiver::class.java).setAction(action).putExtra(DownloadActionReceiver.EXTRA_ID, artifactId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A paused download stays on screen with Resume / Cancel (the live notification ends with the download job). */
    fun showPaused(artifactId: Long, title: String, percent: Int?) {
        val text = if (percent != null) ctx.getString(R.string.gh_notif_paused_at, percent) else ctx.getString(R.string.gh_notif_paused)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(null, 0L))
            .addAction(0, ctx.getString(R.string.gh_notif_resume), downloadAction(DownloadActionReceiver.ACTION_RESUME, artifactId))
            .addAction(0, ctx.getString(R.string.gh_notif_cancel), downloadAction(DownloadActionReceiver.ACTION_CANCEL, artifactId))
            .apply { if (percent != null) setProgress(100, percent, false) }
            .build()
        post(PAUSED_ID, n)
    }

    fun clearPaused() {
        try { nm.cancel(PAUSED_ID) } catch (_: SecurityException) { }
    }

    fun clearLive() {
        lastLive = null
        try { nm.cancel(LIVE_ID) } catch (_: SecurityException) { }
    }

    /** A finished process: stays until the user dismisses it. [kind] keeps one result per process type. */
    fun result(kind: Int, title: String, text: String, ok: Boolean = true) {
        // No "download complete" / "app installed" notifications; failures are still shown.
        if (ok && (kind == KIND_DOWNLOAD || kind == KIND_INSTALL)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (ok) R.drawable.ic_stat_check else R.drawable.ic_stat_error)
            .setColor(if (ok) accent else errorTint)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        post(RESULT_BASE + kind, n)
    }

    /** A new ARM64 build exists (audible channel: this is the one the user asked to hear about). */
    fun newBuild(title: String, text: String, projectPath: String? = null) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUILDS)
            .setSmallIcon(R.drawable.ic_stat_package)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(projectPath))
            .build()
        post(RESULT_BASE + KIND_NEW_BUILD, n)
    }

    /** A watched build finished (audible channel). One notification per run. */
    fun buildDone(
        runId: Long, title: String, text: String, ok: Boolean, projectPath: String? = null,
        /** With a failed run: adds "Copy error" and "Copy log" buttons (owner, repo name, run number). */
        copyFor: Triple<String, String, Int>? = null,
    ) {
        val builder = NotificationCompat.Builder(ctx, CHANNEL_BUILDS)
            .setSmallIcon(if (ok) R.drawable.ic_stat_check else R.drawable.ic_stat_error)
            .setColor(if (ok) accent else errorTint)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(projectPath))
        if (!ok && copyFor != null) {
            builder.addAction(0, ctx.getString(R.string.gh_notif_copy_error), copyIntent(runId, copyFor, CopyRunLogActivity.MODE_ERROR))
            builder.addAction(0, ctx.getString(R.string.gh_notif_copy_log), copyIntent(runId, copyFor, CopyRunLogActivity.MODE_LOG))
        }
        post(RESULT_BASE + 100 + (runId % 1000).toInt(), builder.build())
    }

    /**
     * The system installer's confirmation, held back while the app is not on screen: tapping this opens it in
     * the foreground (like a browser download), instead of the installer popping up over whatever the user is doing.
     */
    fun installReady(confirm: Intent) {
        val pi = PendingIntent.getActivity(
            ctx, RESULT_BASE + KIND_INSTALL_READY, confirm,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = ctx.getString(R.string.gh_notif_install_ready_title)
        val text = ctx.getString(R.string.gh_notif_install_ready_text)
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUILDS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        post(RESULT_BASE + KIND_INSTALL_READY, n)
    }

    fun clearInstallReady() {
        try { nm.cancel(RESULT_BASE + KIND_INSTALL_READY) } catch (_: SecurityException) { }
        clearDownloadComplete()
    }

    /**
     * "Download complete" for an APK that is now on the device (audible channel). Tapping it opens the system
     * installer for that file directly (ACTION_VIEW + FileProvider); the system still asks for its own confirmation.
     */
    fun downloadComplete(apk: File, name: String) {
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.ghfiles", apk)
        } catch (_: IllegalArgumentException) {
            return
        }
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, RESULT_BASE + KIND_DOWNLOAD, view,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = ctx.getString(R.string.gh_notif_download_done_text, name)
        val n = NotificationCompat.Builder(ctx, CHANNEL_BUILDS)
            .setSmallIcon(R.drawable.ic_stat_check)
            .setColor(accent)
            .setContentTitle(ctx.getString(R.string.gh_notif_download_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        post(RESULT_BASE + KIND_DOWNLOAD, n)
    }

    fun clearDownloadComplete() {
        try { nm.cancel(RESULT_BASE + KIND_DOWNLOAD) } catch (_: SecurityException) { }
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    companion object {
        /** Intent extra: project whose GitHub screen the notification should open. */
        const val EXTRA_OPEN_GITHUB = "com.invictus.kodex.extra.OPEN_GITHUB"

        /** Intent extra: run whose details to open on top of the GitHub screen. */
        const val EXTRA_OPEN_RUN = "com.invictus.kodex.extra.OPEN_RUN"

        @Volatile
        internal var lastLive: LiveSpec? = null
        const val CHANNEL = "gh_manager"
        const val CHANNEL_BUILDS = "gh_new_builds"
        const val LIVE_ID = 7100
        private const val PAUSED_ID = 7101
        private const val RESULT_BASE = 7200
        const val KIND_DOWNLOAD = 1
        const val KIND_INSTALL = 2
        const val KIND_SAVE = 3
        const val KIND_CLEANUP = 4
        const val KIND_DISPATCH = 5
        const val KIND_NEW_BUILD = 6
        const val KIND_INSTALL_READY = 7
    }
}
