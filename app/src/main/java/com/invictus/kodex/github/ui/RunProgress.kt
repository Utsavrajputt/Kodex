package com.invictus.kodex.github.ui

import com.invictus.kodex.github.api.GhJob
import com.invictus.kodex.github.api.GhRun
import com.invictus.kodex.github.api.GhStep

enum class StepState { Done, Current, Pending, Failed, Skipped }

data class StepUi(val name: String, val state: StepState)

/** Steps of every job of a run, boiled down to what the progress bar and the step chips show. */
data class RunProgress(val steps: List<StepUi>) {
    val total: Int get() = steps.size
    val hasSteps: Boolean get() = steps.isNotEmpty()
    private val finished: Int
        get() = steps.count { it.state == StepState.Done || it.state == StepState.Failed || it.state == StepState.Skipped }
    val percent: Int get() = if (steps.isEmpty()) 0 else (finished * 100) / steps.size
    val currentIndex: Int get() = steps.indexOfFirst { it.state == StepState.Current }.takeIf { it >= 0 } ?: finished.coerceAtMost((steps.size - 1).coerceAtLeast(0))

    companion object {
        val Empty = RunProgress(emptyList())

        fun of(jobs: List<GhJob>): RunProgress = RunProgress(
            jobs.flatMap { it.steps }
                .filterNot { isHousekeepingStep(it) }
                .map { StepUi(prettyStepName(it.name), stepState(it.status, it.conclusion)) },
        )
    }
}

/** A run that is live right now, as shown in the building card. */
data class LiveBuild(val run: GhRun, val progress: RunProgress)

internal fun stepState(status: String, conclusion: String?): StepState = when (status) {
    "completed" -> when (conclusion) {
        "failure", "timed_out" -> StepState.Failed
        "cancelled", "skipped" -> StepState.Skipped
        else -> StepState.Done
    }
    "in_progress" -> StepState.Current
    else -> StepState.Pending
}

internal fun jobState(job: GhJob): StepState = stepState(job.status, job.conclusion)

/** GitHub's own bookkeeping steps: noise in a progress view, unless they are the one that failed. */
internal fun isHousekeepingStep(step: GhStep): Boolean {
    if (step.conclusion == "failure") return false
    val n = step.name
    return n == "Set up job" || n == "Complete job" || n.startsWith("Post ")
}

internal fun prettyStepName(name: String): String = when {
    name.startsWith("Run actions/checkout") -> "Checkout"
    name.startsWith("Run actions/setup-") -> "Setup"
    else -> name
}
