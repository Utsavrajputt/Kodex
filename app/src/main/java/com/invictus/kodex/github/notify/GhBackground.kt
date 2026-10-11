package com.invictus.kodex.github.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.invictus.kodex.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide scope for long GitHub jobs (download, cleanup, dispatch) so they keep running after the
 * screen is left, plus a counter that keeps [GhWorkService] (foreground, so Android does not kill the
 * process mid-download) alive while at least one job runs.
 */
object GhBackground {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var active = 0

    @Synchronized
    fun begin(context: Context) {
        if (active++ == 0) {
            try {
                ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, GhWorkService::class.java))
            } catch (_: Exception) {
                // Not allowed to start a foreground service right now: the job still runs, just unprotected.
            }
        }
    }

    @Synchronized
    fun end(context: Context) {
        if (active > 0 && --active == 0) {
            val app = context.applicationContext
            app.stopService(Intent(app, GhWorkService::class.java))
            GhNotifier(app).clearLive()
        }
    }
}

class GhWorkService : Service() {
    override fun onCreate() {
        super.onCreate()
        val n = GhNotifier(this).live(getString(R.string.gh_notif_working_title), getString(R.string.gh_notif_working_text), null)
        try {
            ServiceCompat.startForeground(this, GhNotifier.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
