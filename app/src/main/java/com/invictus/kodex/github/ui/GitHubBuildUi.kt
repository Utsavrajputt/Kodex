package com.invictus.kodex.github.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.github.api.RunDisplayStatus
import com.invictus.kodex.ui.icons.XIcons

/** Colour of a step / job state: green done, blue running, red failed, muted otherwise. */
@Composable
internal fun stepTint(state: StepState, p: GhPalette): Color = when (state) {
    StepState.Done -> p.success
    StepState.Current -> p.accent
    StepState.Failed -> p.danger
    StepState.Pending, StepState.Skipped -> p.onCardMuted
}

/** Round state marker: filled check / cross, spinning arc for the running step, empty ring for what is still to come. */
@Composable
internal fun StepStateIcon(state: StepState, p: GhPalette, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val tint = stepTint(state, p)
    when (state) {
        StepState.Done, StepState.Failed -> Box(
            Modifier.size(size).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (state == StepState.Done) XIcons.Check else XIcons.Close,
                contentDescription = null,
                tint = if (state == StepState.Done) p.onSuccess else p.onDanger,
                modifier = Modifier.size(size * 0.7f),
            )
        }
        // Spinning arc: the running step is visibly alive instead of a frozen dot.
        StepState.Current -> CircularProgressIndicator(
            modifier = Modifier.size(size),
            color = tint,
            trackColor = tint.copy(alpha = 0.25f),
            strokeWidth = if (size >= 20.dp) 2.5.dp else 2.dp,
        )
        StepState.Pending -> Box(Modifier.size(size).border(1.5.dp, tint.copy(alpha = 0.55f), CircleShape))
        StepState.Skipped -> Box(
            Modifier.size(size).border(1.5.dp, tint.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(size * 0.4f).height(1.5.dp).background(tint.copy(alpha = 0.7f))) }
    }
}

/** Progress bar with the percentage on its right, then the step chips (Checkout | Setup | Build ...). */
@Composable
internal fun ProgressBlock(progress: RunProgress, p: GhPalette, maxJobRows: Int = Int.MAX_VALUE) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (progress.hasSteps) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    color = p.accent,
                    trackColor = p.onCard.copy(alpha = 0.12f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.gh_percent, progress.percent),
                    style = MaterialTheme.typography.labelLarge, color = p.onCardMuted,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                    color = p.accent,
                    trackColor = p.onCard.copy(alpha = 0.12f),
                )
            }
        }
        when {
            // Parallel jobs: one row each (own bar + current step) instead of one merged chip strip.
            progress.jobs.size > 1 -> JobProgressRows(progress.jobs, p, maxJobRows)
            progress.hasSteps -> StepChips(progress, p)
        }
    }
}

/** Parallel jobs at a glance: running first, then failed, queued and finished. Capped at [maxRows] with a "+N more" line. */
@Composable
private fun JobProgressRows(jobs: List<JobProgressUi>, p: GhPalette, maxRows: Int) {
    val ordered = jobs.sortedBy {
        when (it.state) {
            StepState.Current -> 0
            StepState.Failed -> 1
            StepState.Pending -> 2
            else -> 3
        }
    }
    val shown = ordered.take(maxRows)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { JobProgressRow(it, p) }
        val hidden = ordered.size - shown.size
        if (hidden > 0) {
            Text(
                stringResource(R.string.gh_jobs_more, hidden),
                style = MaterialTheme.typography.labelMedium, color = p.onCardMuted,
            )
        }
    }
}

@Composable
private fun JobProgressRow(job: JobProgressUi, p: GhPalette) {
    val tint = stepTint(job.state, p)
    val shown by animateFloatAsState(job.percent / 100f, label = "job_progress")
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepStateIcon(job.state, p, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (job.state == StepState.Current) FontWeight.Bold else FontWeight.Normal,
                    color = if (job.state == StepState.Pending) p.onCardMuted else p.onCard,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.gh_percent, job.percent), style = MaterialTheme.typography.labelMedium, color = p.onCardMuted)
            }
            LinearProgressIndicator(
                progress = { shown },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = tint,
                trackColor = p.onCard.copy(alpha = 0.12f),
            )
            val sub = when (job.state) {
                StepState.Current -> job.currentStep
                StepState.Pending -> stringResource(R.string.gh_build_queued)
                else -> null
            }
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.labelSmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StepChips(progress: RunProgress, p: GhPalette) {
    val scroll = rememberScrollState()
    // Keep the running step in view as the build moves along.
    LaunchedEffect(progress.currentIndex, progress.total) {
        if (progress.total > 1 && scroll.maxValue > 0) {
            scroll.animateScrollTo(scroll.maxValue * progress.currentIndex / (progress.total - 1))
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        progress.steps.forEachIndexed { i, step ->
            if (i > 0) {
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(16.dp).background(p.onCard.copy(alpha = 0.14f)))
            }
            StepStateIcon(step.state, p, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                step.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (step.state == StepState.Current) FontWeight.Bold else FontWeight.Normal,
                color = when (step.state) {
                    StepState.Pending, StepState.Skipped -> p.onCardMuted
                    StepState.Failed -> p.danger
                    else -> p.onCard
                },
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
        }
    }
}

/** The "Building APK…" card on the GitHub screen: live status, Cancel, progress and steps. Tap opens the run. */
@Composable
internal fun BuildingCard(live: LiveBuild, onOpen: () -> Unit, onCancel: () -> Unit) {
    val p = ghPalette()
    val run = live.run
    val queued = run.display == RunDisplayStatus.Queued
    GhCard(p.card, p.cardBorder, onClick = onOpen) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    color = p.accent, trackColor = p.accent.copy(alpha = 0.2f), strokeWidth = 3.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (queued) R.string.gh_build_queued else R.string.gh_build_building),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = p.onCard, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val started = (run.startedAt ?: run.createdAt)?.let {
                        DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    }
                    Text(
                        listOfNotNull("#${run.runNumber}", started?.let { stringResource(R.string.gh_build_started, it) }).joinToString(" \u2022 "),
                        style = MaterialTheme.typography.bodySmall, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, p.pickerBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = p.onCard),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(36.dp),
                ) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge) }
            }
            ProgressBlock(live.progress, p, maxJobRows = 4)
        }
    }
}
