package com.invictus.kodex.github.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.invictus.kodex.R
import com.invictus.kodex.ai.notify.AppVisibility
import com.invictus.kodex.github.notify.GhNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Outcome reported by the system installer (plan 8.4). */
sealed interface InstallResult {
    data object Success : InstallResult
    data object Cancelled : InstallResult
    data class Failed(val message: String?) : InstallResult
}

/** Process-wide bridge from [InstallResultReceiver] to whoever started the install. */
object InstallEvents {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 4)
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()
    internal fun emit(r: InstallResult) { _results.tryEmit(r) }
}

/**
 * Installs through the PackageInstaller session API (update-in-place, status callbacks). Never
 * installs on its own: [install] is only called after the user taps Install, and the system
 * still shows its own confirmation. Falls back to ACTION_VIEW + FileProvider if a session
 * cannot be created.
 */
class ApkInstaller(private val context: Context) {

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows this app to install packages. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        try {
            installWithSession(apk)
        } catch (e: IOException) {
            installWithIntent(apk)
        } catch (e: SecurityException) {
            installWithIntent(apk)
        }
    }

    private fun installWithSession(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        // Android 12+ may otherwise apply an update silently, with no installer screen at all.
        // Always ask, so the install happens in front of the user.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        var session: PackageInstaller.Session? = null
        try {
            val s = installer.openSession(id)
            session = s
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(pi.intentSender)
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            if (session == null) runCatching { installer.abandonSession(id) }
            throw if (e is IOException || e is SecurityException) e else IOException(e)
        } finally {
            session?.close()
        }
    }

    private fun installWithIntent(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ghfiles", apk)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(view)
    }
}

/** Receives the PackageInstaller status; launches the system confirmation when asked to. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (AppVisibility.visible) {
                        // App is on screen: show the installer right now, in the foreground.
                        runCatching { context.startActivity(it) }
                    } else {
                        // App is in the background: never start the installer behind the user's back.
                        // A tap on this notification opens it in the foreground instead.
                        GhNotifier(context).installReady(it)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                GhNotifier(context).clearInstallReady()
                // Remember which commit now runs, before anyone reacts to the event.
                InstalledBuilds(context).commit(context, intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
                InstallEvents.emit(InstallResult.Success)
                GhNotifier(context).result(
                    GhNotifier.KIND_INSTALL, context.getString(R.string.gh_notif_install_ok_title),
                    context.getString(R.string.gh_notif_install_ok_text),
                )
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                GhNotifier(context).clearInstallReady()
                InstallEvents.emit(InstallResult.Cancelled)
            }
            else -> {
                GhNotifier(context).clearInstallReady()
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                InstallEvents.emit(InstallResult.Failed(message))
                GhNotifier(context).result(
                    GhNotifier.KIND_INSTALL, context.getString(R.string.gh_notif_install_failed_title),
                    message ?: context.getString(R.string.gh_notif_install_failed_title), ok = false,
                )
            }
        }
    }
}
