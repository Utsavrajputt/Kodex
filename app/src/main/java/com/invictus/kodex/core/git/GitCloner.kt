package com.invictus.kodex.core.git

import kotlinx.coroutines.CancellationException
import com.invictus.kodex.core.diagnostics.GitLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import java.io.File

/** Clone has no repo yet, so it lives outside [GitSession]. */
object GitCloner {

    suspend fun clone(
        url: String,
        username: String,
        token: String,
        directory: File,
        branch: String?,
        isCancelled: () -> Boolean = { false },
        onProgress: (GitCloneProgress) -> Unit,
    ): GitResult<File> = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        try {
            val tracker = GitCloneProgressTracker(cancelCheck = isCancelled, onProgress = onProgress)
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(directory)
                .setCredentialsProvider(GitTokenCredentialsProvider(username, token))
                .setProgressMonitor(tracker.monitor)
            if (!branch.isNullOrBlank()) cmd.setBranch(branch.trim())
            val git = GitByteTracker.track(tracker::onBytesRead) { cmd.call() }
            git.close()
            tracker.finish()
            GitLog.record("Git clone", true, (System.nanoTime() - startNs) / 1_000_000, 0, "user", directory.path, branch, null)
            GitResult.Ok(directory)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            GitLog.record("Git clone", false, (System.nanoTime() - startNs) / 1_000_000, 0, "user", directory.path, branch, e)
            GitResult.Err(GitErrorFactory.from(e, "Clone failed") { it.message ?: "Unknown error" })
        }
    }
}
