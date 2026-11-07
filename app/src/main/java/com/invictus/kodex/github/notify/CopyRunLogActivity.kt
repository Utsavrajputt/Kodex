package com.invictus.kodex.github.notify

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.invictus.kodex.KodexApp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhRepoRef
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.data.LogFiles
import com.invictus.kodex.github.ui.ClipboardHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Target of the failed-build notification's "Copy error" / "Copy log" buttons. Fetches the run's log, puts the text on
 * the clipboard, says so in a toast and closes at once. No UI of its own (see-through, not kept in recents).
 */
class CopyRunLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = intent.getStringExtra(EXTRA_OWNER)
        val name = intent.getStringExtra(EXTRA_REPO)
        val runId = intent.getLongExtra(EXTRA_RUN_ID, 0L)
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ERROR
        if (owner == null || name == null || runId == 0L) { finish(); return }

        toast(getString(R.string.gh_copy_working))
        lifecycleScope.launch {
            try {
                val repo = (application as KodexApp).container.gitHubRepository(GhRepoRef(owner, name))
                val run = repo.run(runId)
                val text = if (mode == MODE_LOG) fullRunLog(repo, run) else errorText(repo, run)
                if (text.isBlank()) { toast(getString(R.string.gh_copy_nothing)); return@launch }
                // A very large clip can be refused by the system: retry smaller before giving up.
                val ok = ClipboardHelper.copy(this@CopyRunLogActivity, "Build #$number", text)
                    || ClipboardHelper.copy(this@CopyRunLogActivity, "Build #$number", text.takeLast(100_000))
                toast(getString(if (ok) (if (mode == MODE_LOG) R.string.gh_copy_log_done else R.string.gh_copy_error_done) else R.string.gh_notif_copy_failed))
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubError) {
                toast(getString(R.string.gh_copy_fetch_failed))
            } catch (e: Exception) {
                toast(getString(R.string.gh_notif_copy_failed))
            } finally {
                finish()
            }
        }
    }

    /** The failed step's error lines with a little context (the same excerpt the log screen shows). */
    private suspend fun errorText(repo: com.invictus.kodex.github.data.GitHubRepository, run: com.invictus.kodex.github.api.GhRun): String {
        val log = repo.loadRunLog(run, run.name)
        log.file?.delete()
        return log.excerpt
    }

    /** Every job's log, one after the other under a heading, the whole text capped for the clipboard. */
    private suspend fun fullRunLog(repo: com.invictus.kodex.github.data.GitHubRepository, run: com.invictus.kodex.github.api.GhRun): String {
        val jobs = repo.jobs(run.id).take(MAX_JOBS)
        if (jobs.isEmpty()) return repo.loadRunLog(run, run.name).also { it.file?.delete() }.excerpt
        // Failed jobs first, each gets an equal share of the budget; when over, the START of a job is dropped (the end has the error).
        val ordered = jobs.sortedByDescending { it.isFailed }
        val share = (TOTAL_CHARS / ordered.size).coerceAtLeast(20_000)
        val out = StringBuilder()
        for (job in ordered) {
            val log = repo.loadRunLog(run, run.name, job.id)
            val file = log.file ?: continue
            val (text, cut) = LogFiles.clipboardText(file)
            file.delete()
            out.append("===== ").append(job.name).append(if (job.isFailed) "  (failed)" else "").append(" =====\n")
            if (cut || text.length > share) out.append("[start of this log omitted]\n")
            out.append(text.takeLast(share)).append("\n\n")
        }
        return out.toString().trim()
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_MODE = "mode"
        const val MODE_ERROR = "error"
        const val MODE_LOG = "log"
        private const val MAX_JOBS = 8
        private const val TOTAL_CHARS = 600_000
    }
}
