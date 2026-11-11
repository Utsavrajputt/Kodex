package com.invictus.kodex.github.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.invictus.kodex.github.install.ActiveDownloads
import com.invictus.kodex.github.install.ApkDownloader
import kotlin.concurrent.thread

/** Pause / Resume / Cancel buttons on the download notification. Works on the process-wide [ActiveDownloads]. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, 0L)
        if (id == 0L) return
        when (intent.action) {
            ACTION_PAUSE -> ActiveDownloads.pause(id)
            ACTION_RESUME -> if (!ActiveDownloads.resumePaused(id)) GhNotifier(context).clearPaused()
            ACTION_CANCEL -> {
                if (ActiveDownloads.isActive(id)) {
                    ActiveDownloads.cancel(id)
                } else if (!ActiveDownloads.cancelPaused(id)) {
                    // Nobody owns it any more (screen gone): just drop the partial file and the notification.
                    val app = context.applicationContext
                    thread { ApkDownloader(app.filesDir).discardPartial(id) }
                }
                GhNotifier(context).clearPaused()
            }
        }
    }

    companion object {
        const val EXTRA_ID = "artifact_id"
        const val ACTION_PAUSE = "com.invictus.kodex.github.DL_PAUSE"
        const val ACTION_RESUME = "com.invictus.kodex.github.DL_RESUME"
        const val ACTION_CANCEL = "com.invictus.kodex.github.DL_CANCEL"
    }
}
