package com.invictus.kodex.github.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.invictus.kodex.R

/**
 * Status notifications for every GitHub Manager process (download, install, save, cleanup, dispatch).
 * One "live" notification ([LIVE_ID]) shows the process that is running right now (it is also the
 * foreground-service notification); finished processes post their own, tappable, auto-cancel result.
 */
class GhNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.gh_notif_channel), NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun openApp(): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The ongoing notification. [progress] 0..100, or null for an indeterminate bar. */
    fun live(title: String, text: String, progress: Int?): Notification =
        NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .setProgress(100, progress ?: 0, progress == null)
            .build()

    fun updateLive(title: String, text: String, progress: Int?) = post(LIVE_ID, live(title, text, progress))

    fun clearLive() = try { nm.cancel(LIVE_ID) } catch (_: SecurityException) { }

    /** A finished process: stays until the user dismisses it. [kind] keeps one result per process type. */
    fun result(kind: Int, title: String, text: String, ok: Boolean = true) {
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        post(RESULT_BASE + kind, n)
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    companion object {
        const val CHANNEL = "gh_manager"
        const val LIVE_ID = 7100
        private const val RESULT_BASE = 7200
        const val KIND_DOWNLOAD = 1
        const val KIND_INSTALL = 2
        const val KIND_SAVE = 3
        const val KIND_CLEANUP = 4
        const val KIND_DISPATCH = 5
    }
}
