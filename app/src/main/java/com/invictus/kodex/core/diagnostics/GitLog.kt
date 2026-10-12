package com.invictus.kodex.core.diagnostics

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent log of every git operation (name, total / lock-wait / run time, trigger, branch,
 * repo, and the error + top stack frames on failure), so slow or failing git work can be
 * inspected from Settings without adb. Same idea as [CrashHandler]: plain files under
 * `filesDir`, survives restarts.
 *
 * One line per operation, appended to `git_logs/git.log`; when it passes [MAX_BYTES] it is
 * rotated to `git.log.1` (older rotation is dropped), so total size stays around 1 MB.
 * Recording can be switched off from Settings ([setEnabled]); default is off (a saved choice is kept).
 */
object GitLog {

    private const val PREFS = "git_log"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_ENABLED = false
    private const val DIR = "git_logs"
    private const val FILE = "git.log"
    private const val FILE_OLD = "git.log.1"
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_ERROR_CHARS = 600
    private const val EXPORT_MAX_ENTRIES = 1000

    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val totalRegex = Regex("total=(\\d+)ms")

    @Volatile private var appContext: Context? = null

    @Volatile
    var enabled: Boolean = DEFAULT_ENABLED
        private set

    data class Entry(val raw: String, val ok: Boolean, val totalMs: Long)

    /** Call once from [android.app.Application.onCreate]. */
    fun init(context: Context) {
        appContext = context.applicationContext
        enabled = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun record(
        op: String,
        ok: Boolean,
        totalMs: Long,
        waitMs: Long,
        trigger: String,
        repo: String,
        branch: String?,
        error: Throwable?,
    ) {
        if (!enabled) return
        val ctx = appContext ?: return
        runCatching {
            synchronized(lock) {
                val line = buildString {
                    append(stamp.format(Date()))
                    append(" | ").append(if (ok) "OK" else "FAIL")
                    append(" | ").append(op)
                    append(" | total=").append(totalMs).append("ms")
                    append(" wait=").append(waitMs).append("ms")
                    append(" run=").append((totalMs - waitMs).coerceAtLeast(0)).append("ms")
                    append(" | trigger=").append(trigger)
                    append(" | branch=").append(branch ?: "-")
                    append(" | ").append(repo)
                    if (error != null) append(" | error=").append(describe(error))
                }
                append(ctx, line)
            }
        }
    }

    /** Newest first. */
    fun entries(context: Context): List<Entry> = synchronized(lock) {
        val dir = dir(context)
        val lines = ArrayList<String>()
        for (name in listOf(FILE_OLD, FILE)) {
            val f = File(dir, name)
            if (f.isFile) lines += f.readLines()
        }
        lines.asReversed()
            .filter { it.isNotBlank() }
            .map { raw ->
                Entry(
                    raw = raw,
                    ok = !raw.contains(" | FAIL | "),
                    totalMs = totalRegex.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                )
            }
    }

    /** Newest first, capped so it stays comfortably under Binder/clipboard size limits. */
    fun exportText(context: Context): String =
        entries(context).take(EXPORT_MAX_ENTRIES).joinToString("\n") { it.raw }

    fun clear(context: Context) {
        synchronized(lock) {
            val dir = dir(context)
            File(dir, FILE).delete()
            File(dir, FILE_OLD).delete()
        }
    }

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** M20 redaction audit: AI/git tokens must never persist in diagnostics logs. */
    private val sensitive = listOf(
        Regex("""sk-[A-Za-z0-9]{16,}"""),
        Regex("""AIza[0-9A-Za-z_-]{20,}"""),
        Regex("""Bearer\s+[A-Za-z0-9._-]{10,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""),
    )

    private fun redact(text: String): String =
        sensitive.fold(text) { acc, re -> acc.replace(re, "«redacted»") }

    private fun append(context: Context, line: String) {
        val dir = dir(context)
        val f = File(dir, FILE)
        if (f.length() > MAX_BYTES) {
            val old = File(dir, FILE_OLD)
            old.delete()
            f.renameTo(old)
        }
        f.appendText(redact(line) + "\n")
    }

    /** Exception chain (up to 4 causes) plus the top stack frames, flattened to one line. */
    private fun describe(t: Throwable): String {
        val chain = ArrayList<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 4) {
            chain += "${cur.javaClass.simpleName}: ${cur.message.orEmpty()}".trim()
            val next = cur.cause
            cur = if (next === cur) null else next
            depth++
        }
        val frames = t.stackTrace.take(6).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        return (chain.joinToString(" <- caused by ") + " @ " + frames)
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(MAX_ERROR_CHARS)
    }
}
