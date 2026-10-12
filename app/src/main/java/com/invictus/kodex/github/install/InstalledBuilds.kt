package com.invictus.kodex.github.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.invictus.kodex.BuildConfig
import java.io.File

/**
 * The commit of every app installed through Kodex (package -> sha). Written when the system
 * installer reports success, so cancelled or failed installs never count. The install time is kept
 * too: if the app is later reinstalled or updated from elsewhere the record no longer applies.
 */
class InstalledBuilds(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Commit installed for [pkg], or null when unknown or the app changed since (by [lastUpdateTime]). */
    fun shaOf(pkg: String, lastUpdateTime: Long): String? {
        if (prefs.getLong(TIME + pkg, -1L) != lastUpdateTime) return null
        return prefs.getString(SHA + pkg, null)?.takeIf { it.isNotBlank() }
    }

    /** An install of [pkg] built from [sha] is about to start; recorded once the installer succeeds. */
    fun begin(pkg: String, sha: String?) {
        prefs.edit().putString(PENDING_PKG, pkg).putString(PENDING_SHA, sha.orEmpty()).apply()
    }

    /** Installer success for [installedPkg] (null = unknown): stores the pending commit if it is that package. */
    fun commit(context: Context, installedPkg: String?) {
        val pkg = prefs.getString(PENDING_PKG, null) ?: return
        if (installedPkg != null && installedPkg != pkg) return
        val sha = prefs.getString(PENDING_SHA, null).orEmpty()
        val time = InstalledApps(context).info(pkg)?.lastUpdateTime
        val e = prefs.edit().remove(PENDING_PKG).remove(PENDING_SHA)
        if (sha.isBlank() || time == null) e.remove(SHA + pkg).remove(TIME + pkg)
        else e.putString(SHA + pkg, sha).putLong(TIME + pkg, time)
        e.commit()
    }

    private companion object {
        const val FILE = "installed_builds"
        const val SHA = "sha:"
        const val TIME = "time:"
        const val PENDING_PKG = "pending_pkg"
        const val PENDING_SHA = "pending_sha"
    }
}

/** What the card needs for one APK: the action, and the package to open. */
data class ApkInstallInfo(val action: ApkAction, val packageName: String?)

/** Installed-package lookups and launching. Needs the launcher `<queries>` entry on Android 11+. */
class InstalledApps(private val context: Context) {
    private val pm get() = context.packageManager

    fun info(pkg: String): PackageInfo? = try {
        pm.getPackageInfo(pkg, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Starts the app's launcher activity. False when it is not installed or has none. */
    fun open(pkg: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Install / Update / Open for the downloaded [apk] built from [buildSha]. This app compares
     * against the commit baked into its own BuildConfig; every other app against [builds].
     */
    fun resolve(apk: File, buildSha: String?, builds: InstalledBuilds): ApkInstallInfo {
        val pkg = pm.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName
            ?: return ApkInstallInfo(ApkAction.Install, null)
        val installed = info(pkg)
        val installedSha = when {
            installed == null -> null
            pkg == context.packageName -> BuildConfig.GIT_SHA
            else -> builds.shaOf(pkg, installed.lastUpdateTime)
        }
        return ApkInstallInfo(BuildSha.actionFor(installed != null, installedSha, buildSha), pkg)
    }
}
