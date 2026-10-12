package com.invictus.kodex.ai.notify

import android.Manifest
import android.app.Activity
import android.app.Application
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
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps an AI chat turn alive while the app is minimized: a foreground service (so Android does not kill the
 * process mid-answer) with a "Kodex AI is working" notification, counted so several chats share one service.
 */
object AiBackground {
    private var active = 0
    private var lastProject = ""

    fun isRunning(): Boolean = synchronized(this) { active > 0 }

    fun workingText(): String = synchronized(this) {
        if (active > 1) "Working on $active chats…" else "Working on $lastProject…"
    }

    fun begin(context: Context, project: String) {
        val app = context.applicationContext
        val first = synchronized(this) { lastProject = project; active++ == 0 }
        if (first) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, AiWorkService::class.java))
            } catch (_: Exception) {
                // Not allowed right now: the turn still runs, just unprotected.
            }
        } else {
            AiNotifier(app).showLive()
        }
    }

    fun end(context: Context) {
        val app = context.applicationContext
        val left = synchronized(this) { if (active > 0) --active else 0 }
        if (left == 0) app.stopService(Intent(app, AiWorkService::class.java))
        else AiNotifier(app).showLive()
    }
}

/** Whether any activity of the app is visible; used to post "replied" notifications only when it is not. */
object AppVisibility {
    @Volatile private var started = 0
    val visible: Boolean get() = started > 0

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) { if (started > 0) started-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

class AiNotifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = NotificationManagerCompat.from(ctx)

    init {
        if (Build.VERSION.SDK_INT >= 26) {
            val sys = ctx.getSystemService(NotificationManager::class.java)
            sys?.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, "Kodex AI activity", NotificationManager.IMPORTANCE_LOW))
            sys?.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Kodex AI replies", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openApp(): PendingIntent? {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The foreground-service notification. */
    fun live(): Notification = NotificationCompat.Builder(ctx, CHANNEL_LIVE)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("Kodex AI")
        .setContentText(AiBackground.workingText())
        .setOngoing(false)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openApp())
        .setProgress(0, 0, true)
        .build()

    fun showLive() = post(LIVE_ID, live())

    /** A finished answer while the app is not visible. Tapping opens the app. */
    fun done(project: String, text: String, ok: Boolean) {
        val title = if (ok) "Kodex AI replied" else "Kodex AI couldn't finish"
        val body = text.trim().replace(Regex("\\s+"), " ").take(160).ifEmpty { project }
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_notify_chat else android.R.drawable.stat_notify_error)
            .setContentTitle(if (project.isBlank()) title else "$title · $project")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        post(DONE_ID, n)
    }

    private fun post(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try { nm.notify(id, n) } catch (_: SecurityException) { }
    }

    companion object {
        const val CHANNEL_LIVE = "ai_agent_live"
        const val CHANNEL_DONE = "ai_agent_done"
        const val LIVE_ID = 7300
        const val DONE_ID = 7301
    }
}

class AiWorkService : Service() {
    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(this, AiNotifier.LIVE_ID, AiNotifier(this).live(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
