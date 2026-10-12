package com.invictus.kodex.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * There was previously no way to retrieve a crash's stack trace without adb/Android Studio.
 * [install] wires a global [Thread.UncaughtExceptionHandler] that writes the full trace to
 * [logDir] before handing off to the previous handler (so normal crash/process-death behavior
 * is unchanged) -- and [logCaught] lets a call site record a non-fatal exception it recovered
 * from, using the same store, so the Diagnostics screen shows both kinds of problem.
 */
object CrashHandler {

    private const val DIR_NAME = "crash_logs"
    private const val MAX_LOGS = 25
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val displayStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Call once from [android.app.Application.onCreate], as early as possible. */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread.name, throwable, fatal = true) }
            previous?.uncaughtException(thread, throwable)
                ?: run {
                    // No previous handler (shouldn't normally happen) -- still end the
                    // process the way an uncaught exception normally would.
                    Runtime.getRuntime().exit(10)
                }
        }
    }

    /** Record a caught exception the app recovered from, e.g. a guard that swallowed a crash. */
    fun logCaught(context: Context, tag: String, throwable: Throwable) {
        runCatching { write(context.applicationContext, tag, throwable, fatal = false) }
    }

    data class LogEntry(val file: File, val label: String, val fatal: Boolean)

    /** Newest first. */
    fun listLogs(context: Context): List<LogEntry> {
        val dir = logDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            LogEntry(
                file = f,
                label = displayStamp.format(Date(f.lastModified())),
                fatal = !f.name.contains("_caught_"),
            )
        }
    }

    fun clearLogs(context: Context) {
        logDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun logDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** M20 redaction audit: AI/git tokens must never persist in crash logs. */
    private val sensitive = listOf(
        Regex("""sk-[A-Za-z0-9]{16,}"""),
        Regex("""AIza[0-9A-Za-z_-]{20,}"""),
        Regex("""Bearer\s+[A-Za-z0-9._-]{10,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
    )

    private fun redact(text: String): String =
        sensitive.fold(text) { acc, re -> acc.replace(re, "«redacted»") }

    private fun write(context: Context, source: String, throwable: Throwable, fatal: Boolean) {
        val dir = logDir(context)
        val now = Date()
        val kind = if (fatal) "crash" else "caught"
        val name = "${fileStamp.format(now)}_${kind}_${source.take(20).replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt"
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("Time: ${displayStamp.format(now)}")
            pw.println("Fatal: $fatal")
            pw.println("Thread/tag: $source")
            pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            pw.println()
            throwable.printStackTrace(pw)
        }
        File(dir, name).writeText(redact(sw.toString()))
        prune(dir)
    }

    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_LOGS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_LOGS)
            .forEach { it.delete() }
    }
}
