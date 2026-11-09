package com.invictus.kodex.github.install

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide record of the artifact downloads that are running right now.
 *
 * A download outlives the screen that started it (it runs in the background scope), but the screen's
 * ViewModel does not. Without this registry a screen opened again knows nothing about the running download:
 * it shows a plain Download button, a second tap starts a second writer on the same `.part` file and the
 * file ends up corrupt. With it, a download is started once per artifact, and a new screen picks up the
 * live progress and can pause / cancel it.
 */
object ActiveDownloads {
    /** [owner] says which screen may show it (a project path, or [OWNER_APKS] for the Builds screen). */
    data class Live(
        val artifactId: Long,
        val owner: String,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        val etaSeconds: Long? = null,
        val speedBps: Long? = null,
        val save: Boolean = false,
        val fromApkCard: Boolean = false,
    )

    const val OWNER_APKS = "apks"

    private val _live = MutableStateFlow<Map<Long, Live>>(emptyMap())
    val live: StateFlow<Map<Long, Live>> = _live.asStateFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val pauses: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun isActive(artifactId: Long): Boolean = _live.value.containsKey(artifactId)

    /** Takes the right to download [Live.artifactId]; false when it is already being downloaded. */
    @Synchronized
    fun claim(entry: Live): Boolean {
        if (_live.value.containsKey(entry.artifactId)) return false
        _live.update { it + (entry.artifactId to entry) }
        return true
    }

    /** Links the running coroutine so any screen can pause / cancel it. */
    fun attach(artifactId: Long, job: Job) {
        if (isActive(artifactId) && !job.isCompleted) jobs[artifactId] = job
    }

    fun progress(artifactId: Long, downloaded: Long, total: Long, etaSeconds: Long?, speedBps: Long?) {
        _live.update { m ->
            val cur = m[artifactId] ?: return@update m
            m + (artifactId to cur.copy(downloaded = downloaded, total = total, etaSeconds = etaSeconds, speedBps = speedBps))
        }
    }

    /** Called by the download itself when it ends (done, failed, paused or cancelled). */
    fun release(artifactId: Long) {
        jobs.remove(artifactId)
        _live.update { it - artifactId }
    }

    /** Stops the download but keeps its partial file (the job's handler sees [takePause]). */
    fun pause(artifactId: Long) {
        pauses.add(artifactId)
        jobs[artifactId]?.cancel()
    }

    /** Stops the download; its handler deletes the partial file. */
    fun cancel(artifactId: Long) {
        pauses.remove(artifactId)
        jobs[artifactId]?.cancel()
    }

    /** True once when the stop that is being handled was a pause rather than a cancel. */
    fun takePause(artifactId: Long): Boolean = pauses.remove(artifactId)
}
