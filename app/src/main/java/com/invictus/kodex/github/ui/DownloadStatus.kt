package com.invictus.kodex.github.ui

import android.text.format.Formatter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/** Progress UI refresh cadence besides the 1% steps, so speed/ETA keep moving on slow downloads. */
internal const val UI_TICK_MS = 500L

/**
 * Speed and ETA of the current run (it starts over after a resume). Sampling begins with the first progress
 * report, so a partial file that is re-downloaded from zero cannot skew the numbers.
 *
 * Feed it on EVERY progress report (cheap): the speed is the byte rate over the last [WINDOW_MS], so it follows
 * the real rate whatever the UI update cadence is. The ETA stays the average since the run started.
 */
class DownloadMeter {
    private var startBytes = -1L
    private var startMs = 0L
    private val samples = ArrayDeque<LongArray>() // [timeMs, bytes], oldest first

    /** Byte rate over the last few seconds in bytes/s (null until there is enough to measure). Updated by [etaSeconds]. */
    var speedBytesPerSec: Long? = null
        private set

    fun etaSeconds(bytes: Long, total: Long, nowMs: Long = System.currentTimeMillis()): Long? {
        if (startBytes < 0) {
            startBytes = bytes; startMs = nowMs
            samples.addLast(longArrayOf(nowMs, bytes))
            return null
        }
        samples.addLast(longArrayOf(nowMs, bytes))
        while (samples.size > 2 && nowMs - samples.first()[0] > WINDOW_MS) samples.removeFirst()
        val oldest = samples.first()
        val dt = nowMs - oldest[0]
        if (dt >= MIN_SPAN_MS) {
            val rate = (bytes - oldest[1]) * 1000L / dt
            speedBytesPerSec = rate.takeIf { it > 0L }
        }
        if (total <= 0 || bytes >= total) return null
        val elapsed = nowMs - startMs
        val got = bytes - startBytes
        if (elapsed < 1500 || got <= 0) return null
        val bytesPerMs = got.toDouble() / elapsed
        return ((total - bytes) / bytesPerMs / 1000.0).toLong().coerceAtLeast(1L)
    }

    private companion object {
        const val WINDOW_MS = 3_000L
        const val MIN_SPAN_MS = 400L
    }
}

/** 45 -> "45s", 185 -> "3m 5s", 3720 -> "1h 2m". */
fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

/**
 * Download card body used by every APK download: big percent + state + speed, the wavy bar, then
 * "2.1 MB / 5.8 MB • ETA 12s" with round Pause/Resume and Cancel buttons.
 */
@Composable
fun TransferStatus(
    downloaded: Long,
    total: Long,
    etaSeconds: Long?,
    paused: Boolean,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit,
    accent: Color,
    track: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    /** Pause is hidden once the whole file is in (extracting / verifying). */
    canPause: Boolean = true,
    speedBytesPerSec: Long? = null,
) {
    val context = LocalContext.current
    val progress: Float? = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    val finishing = !paused && total > 0 && downloaded >= total
    val state = stringResource(
        when {
            paused -> R.string.gh_transfer_paused
            finishing -> R.string.gh_notif_download_preparing
            else -> R.string.gh_transfer_status_downloading
        },
    )
    val shown by animateFloatAsState(progress ?: 0f, tween(450, easing = LinearEasing), label = "download_progress")
    val sizes = when {
        total > 0 -> "${Formatter.formatShortFileSize(context, downloaded)} / ${Formatter.formatShortFileSize(context, total)}"
        downloaded > 0 -> Formatter.formatShortFileSize(context, downloaded)
        else -> ""
    }
    val etaText = if (!paused && !finishing && etaSeconds != null) stringResource(R.string.gh_transfer_eta, formatEta(etaSeconds)) else null
    val speedText = if (!paused && !finishing && speedBytesPerSec != null && speedBytesPerSec > 0) {
        stringResource(R.string.gh_transfer_speed, Formatter.formatShortFileSize(context, speedBytesPerSec))
    } else null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (progress != null) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
            )
            if (speedText != null) {
                Text(
                    speedText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        WaveProgressBar(
            progress = if (progress != null) shown else 0f,
            animating = !paused,
            indeterminate = progress == null,
            color = accent,
            trackColor = track,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(sizes.ifBlank { null }, etaText).joinToString(" \u2022 "),
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canPause) {
                RoundTransferButton(
                    onClick = onPauseResume,
                    icon = if (paused) XIcons.PlayArrow else XIcons.Pause,
                    description = stringResource(if (paused) R.string.gh_transfer_resume else R.string.gh_transfer_pause),
                    tint = accent,
                )
                Spacer(Modifier.width(12.dp))
            }
            RoundTransferButton(
                onClick = onCancel,
                icon = XIcons.Close,
                description = stringResource(R.string.action_cancel),
                tint = textColor,
            )
        }
    }
}

@Composable
private fun RoundTransferButton(onClick: () -> Unit, icon: ImageVector, description: String, tint: Color) {
    // A plain 36dp circle (not IconButton): IconButton pads itself up to 48dp and the two buttons overlapped.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** The wavy download bar of xmd: a travelling wave while downloading, a straight line when paused. */
@Composable
fun WaveProgressBar(
    progress: Float,
    animating: Boolean,
    indeterminate: Boolean,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "download_wave_transition")
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "wave_phase",
    )
    val indeterminateOffset by transition.animateFloat(
        initialValue = -0.35f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "indeterminate_offset",
    )
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val centerY = size.height / 2f
        val strokeWidth = 3.5.dp.toPx()
        val totalWidth = size.width
        drawLine(trackColor, Offset(0f, centerY), Offset(totalWidth, centerY), strokeWidth, StrokeCap.Round)
        if (indeterminate) {
            val barLength = totalWidth * 0.35f
            val startX = (indeterminateOffset * totalWidth).coerceIn(0f, totalWidth)
            val endX = (indeterminateOffset * totalWidth + barLength).coerceIn(0f, totalWidth)
            if (endX > startX) drawLine(color, Offset(startX, centerY), Offset(endX, centerY), strokeWidth, StrokeCap.Round)
        } else {
            val progressWidth = totalWidth * progress.coerceIn(0f, 1f)
            if (progressWidth > 0f) {
                if (animating && progress < 1f) {
                    val waveLength = 22.dp.toPx()
                    val amplitude = 2.2.dp.toPx()
                    val taperLength = 12.dp.toPx()
                    val path = Path()
                    path.moveTo(0f, centerY)
                    var x = 0f
                    while (x <= progressWidth) {
                        val angle = ((x / waveLength) + wavePhase) * 2f * Math.PI.toFloat()
                        val taper = ((progressWidth - x) / taperLength).coerceIn(0f, 1f) * (x / taperLength).coerceIn(0f, 1f)
                        path.lineTo(x, centerY + kotlin.math.sin(angle) * amplitude * taper)
                        x += 2f
                    }
                    path.lineTo(progressWidth, centerY)
                    drawPath(path, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawLine(color, Offset(0f, centerY), Offset(progressWidth, centerY), strokeWidth, StrokeCap.Round)
                }
            }
        }
    }
}

/** Text of the Install button for each [InstallPhase]. */
internal fun installPhaseLabel(phase: InstallPhase): Int = when (phase) {
    InstallPhase.Preparing -> R.string.gh_install_preparing
    InstallPhase.Installed -> R.string.gh_install_done
    else -> R.string.gh_installing
}
