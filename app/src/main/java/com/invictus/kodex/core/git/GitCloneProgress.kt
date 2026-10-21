package com.invictus.kodex.core.git

import org.eclipse.jgit.lib.BatchingProgressMonitor
import java.util.Locale
import kotlin.math.max

/** One phase of a clone, formatted the way native git / Termux prints it. */
data class GitCloneLogLine(
    val taskName: String,
    val text: String,
    val done: Boolean,
    /** 0..100 when this phase reports a known total (e.g. "Counting objects"); null otherwise. */
    val percent: Int? = null,
)

/** Full clone transcript so far: every phase seen, oldest first. The last entry may still be live. */
data class GitCloneProgress(val lines: List<GitCloneLogLine> = emptyList())

private const val TASK_RECEIVING_OBJECTS = "Receiving objects"
private const val TASK_RESOLVING_DELTAS = "Resolving deltas"

/**
 * Turns JGit's [BatchingProgressMonitor] callbacks into a live [GitCloneProgress] transcript.
 *
 * JGit already parses the server's "remote: Enumerating/Counting/Compressing objects: NN% (a/b)"
 * sideband progress text and replays it through this same monitor, prefixed with "remote: " —
 * see JGit's SideBandInputStream/PacketLineIn progress scraping — so no separate plumbing is
 * needed to surface those lines here; they arrive as ordinary tasks named e.g.
 * "remote: Counting objects". "Receiving objects" and "Resolving deltas" are the two purely
 * local, client-side phases and arrive the same way.
 *
 * The exact native-git "Total N (delta M), reused X, pack-reused Y" summary line is emitted by
 * the server as a raw (non-progress) sideband message that isn't reachable through JGit's
 * high-level CloneCommand/ProgressMonitor API. [finish] appends an equivalent summary built from
 * the object/delta totals this monitor already saw instead.
 */
internal class GitCloneProgressTracker(
    /** Polled by JGit between work units; returning true aborts the clone. */
    private val cancelCheck: () -> Boolean = { false },
    private val onProgress: (GitCloneProgress) -> Unit,
) {
    private val lines = mutableListOf<GitCloneLogLine>()
    private var receivingStartedAtNanos = 0L
    private var receivingBytes = 0L
    private var receivedTotal = 0
    private var deltaTotal = 0

    /** Call from [GitByteTracker.track] so byte reads land in the "Receiving objects" line. */
    fun onBytesRead(totalBytes: Long) {
        receivingBytes = totalBytes
    }

    val monitor: BatchingProgressMonitor = object : BatchingProgressMonitor() {
        override fun isCancelled(): Boolean = cancelCheck()

        override fun onUpdate(taskName: String?, workCurr: Int) =
            emit(taskName.orEmpty(), workCurr, 0, 0, done = false)

        override fun onEndTask(taskName: String?, workCurr: Int) =
            emit(taskName.orEmpty(), workCurr, 0, 0, done = true)

        override fun onUpdate(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName.orEmpty(), workCurr, workTotal, percentDone, done = false)

        override fun onEndTask(taskName: String?, workCurr: Int, workTotal: Int, percentDone: Int) =
            emit(taskName.orEmpty(), workCurr, workTotal, percentDone, done = true)
    }

    private fun emit(taskName: String, workCurr: Int, workTotal: Int, percentDone: Int, done: Boolean) {
        if (taskName.isBlank()) return
        val isNewLine = lines.isEmpty() || lines.last().taskName != taskName || lines.last().done
        if (taskName == TASK_RECEIVING_OBJECTS && isNewLine) {
            receivingStartedAtNanos = System.nanoTime()
            receivingBytes = 0L
        }
        if (taskName == TASK_RECEIVING_OBJECTS) receivedTotal = max(receivedTotal, workTotal)
        if (taskName == TASK_RESOLVING_DELTAS) deltaTotal = max(deltaTotal, workTotal)

        val body = if (workTotal > 0) "$taskName: $percentDone% ($workCurr/$workTotal)" else "$taskName: $workCurr"
        val suffix = if (taskName == TASK_RECEIVING_OBJECTS && receivingBytes > 0) {
            val elapsedSec = ((System.nanoTime() - receivingStartedAtNanos) / 1_000_000_000.0).coerceAtLeast(0.05)
            ", ${formatBytes(receivingBytes)} | ${formatBytes((receivingBytes / elapsedSec).toLong())}/s"
        } else {
            ""
        }
        val text = body + suffix + if (done) ", done." else ""
        val line = GitCloneLogLine(taskName, text, done, percent = if (workTotal > 0) percentDone else null)
        if (isNewLine) lines.add(line) else lines[lines.lastIndex] = line
        onProgress(GitCloneProgress(lines.toList()))
    }

    /** Call once the clone has finished successfully, to append a git-style totals line. */
    fun finish() {
        if (receivedTotal <= 0) return
        val deltaPart = if (deltaTotal > 0) ", $deltaTotal deltas resolved" else ""
        lines.add(GitCloneLogLine("Total", "Total $receivedTotal objects$deltaPart", done = true))
        onProgress(GitCloneProgress(lines.toList()))
    }
}

private fun formatBytes(bytes: Long): String {
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.2f KiB", kib)
    return String.format(Locale.US, "%.2f MiB", kib / 1024.0)
}

/** Coarse clone phases, in the order a clone moves through them. */
enum class GitCloneStage { Preparing, Receiving, Resolving }

fun GitCloneProgress.stage(): GitCloneStage = when {
    lines.any { it.taskName == TASK_RESOLVING_DELTAS } -> GitCloneStage.Resolving
    lines.any { it.taskName == TASK_RECEIVING_OBJECTS } -> GitCloneStage.Receiving
    else -> GitCloneStage.Preparing
}

/**
 * One 0..1 number for the whole clone: receiving objects is ~70% of it, resolving deltas the
 * remaining ~30%. Null while the server is still preparing the pack (nothing measurable yet).
 */
fun GitCloneProgress.overallFraction(): Float? {
    val receiving = lines.lastOrNull { it.taskName == TASK_RECEIVING_OBJECTS }
    val resolving = lines.lastOrNull { it.taskName == TASK_RESOLVING_DELTAS }
    fun GitCloneLogLine.fraction(): Float = (if (done) 100 else percent ?: 0) / 100f
    return when {
        resolving != null -> 0.7f + 0.3f * resolving.fraction()
        receiving != null -> 0.7f * receiving.fraction()
        else -> null
    }
}
