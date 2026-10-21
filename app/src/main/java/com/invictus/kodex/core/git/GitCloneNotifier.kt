package com.invictus.kodex.core.git

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.ai.notify.AppVisibility

/**
 * Live clone notification: an ongoing progress bar with the current phase + percent and a Cancel
 * action, replaced by a result notification (only while the app is in the background) when the
 * clone ends. No foreground service: the clone lives and dies with the app process.
 */
class GitCloneNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    private var lastPostMs = 0L
    private var lastPercent = -2
    private var lastStage: GitCloneStage? = null

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = ctx.getSystemService(NotificationManager::class.java)
            sys?.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, ctx.getString(R.string.clone_notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
            sys?.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, ctx.getString(R.string.clone_notif_channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    /** Clone began: show an indeterminate bar right away. */
    fun start(repo: String) {
        lastPostMs = 0L
        lastPercent = -2
        lastStage = null
        nm.cancel(RESULT_ID)
        post(PROGRESS_ID, progressNotification(repo, GitCloneStage.Preparing, null))
    }

    /** Called for every JGit progress tick; throttled so the system never rate-limits us. */
    fun progress(repo: String, stage: GitCloneStage, fraction: Float?) {
        val percent = fraction?.let { (it * 100).toInt().coerceIn(0, 100) }
        val changed = percent != lastPercent || stage != lastStage
        val now = SystemClock.elapsedRealtime()
        if (!changed || now - lastPostMs < MIN_INTERVAL_MS) return
        lastPercent = percent ?: -1
        lastStage = stage
        lastPostMs = now
        post(PROGRESS_ID, progressNotification(repo, stage, percent))
    }

    /** The notification the foreground service shows (replaced in place by [progress] updates). */
    fun foregroundNotification(repo: String): Notification =
        progressNotification(repo, lastStage ?: GitCloneStage.Preparing, lastPercent.takeIf { it >= 0 })

    /** Removes the live notification (success, failure, cancel, or screen closed). */
    fun dismiss() = nm.cancel(PROGRESS_ID)

    fun finished(repo: String, path: String) {
        if (AppVisibility.visible) return
        post(
            RESULT_ID,
            resultNotification(
                title = ctx.getString(R.string.clone_notif_done_title),
                text = ctx.getString(R.string.clone_notif_done_text, repo, path),
                ok = true,
            ),
        )
    }

    fun failed(repo: String, message: String) {
        if (AppVisibility.visible) return
        post(
            RESULT_ID,
            resultNotification(
                title = ctx.getString(R.string.clone_notif_failed_title),
                text = "$repo · ${message.trim().replace(Regex("\\s+"), " ").take(160)}",
                ok = false,
            ),
        )
    }

    private fun progressNotification(repo: String, stage: GitCloneStage, percent: Int?): Notification {
        val stageText = ctx.getString(
            when (stage) {
                GitCloneStage.Preparing -> R.string.clone_stage_preparing
                GitCloneStage.Receiving -> R.string.clone_stage_receiving
                GitCloneStage.Resolving -> R.string.clone_stage_resolving
            },
        )
        val text = if (percent != null) ctx.getString(R.string.clone_notif_text, stageText, percent) else stageText
        return NotificationCompat.Builder(ctx, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(ContextCompat.getColor(ctx, R.color.notif_accent))
            .setContentTitle(ctx.getString(R.string.clone_notif_title, repo))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, ctx.getString(R.string.clone_cancel), cancelIntent())
            .build()
    }

    private fun resultNotification(title: String, text: String, ok: Boolean): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_RESULT)
            .setSmallIcon(if (ok) R.drawable.ic_stat_check else R.drawable.ic_stat_error)
            .setColor(ContextCompat.getColor(ctx, if (ok) R.color.notif_accent else R.color.notif_error))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, GitCloneCancelReceiver::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    companion object {
        const val ACTION_CANCEL = "com.invictus.kodex.action.CLONE_CANCEL"
        private const val CHANNEL_PROGRESS = "git_clone_progress"
        private const val CHANNEL_RESULT = "git_clone_result"
        const val PROGRESS_ID = 7400
        private const val RESULT_ID = 7401
        private const val MIN_INTERVAL_MS = 400L
    }
}

class GitCloneCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as? KodexApp)?.container?.gitCloneManager
        if (manager != null && manager.run.value.cloning) {
            manager.cancel()
        } else {
            // Process was recreated: no clone left to cancel, so never leave a stale bar behind.
            GitCloneNotifier(context).dismiss()
        }
    }
}
