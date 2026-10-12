@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.invictus.kodex.ai.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.ai.agent.RateLimitInfo
import com.invictus.kodex.ai.provider.AiErrorInfo
import com.invictus.kodex.ai.provider.AiModel
import com.invictus.kodex.ai.provider.ErrorAction
import com.invictus.kodex.ai.provider.ModelInfo
import com.invictus.kodex.ai.provider.ModelTier
import com.invictus.kodex.ai.provider.ProviderRegistry
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Small rounded label ("Fast", "~200K context"…). Shared by the chat and the settings screen. */
@Composable
fun AiTag(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = RoundedCornerShape(6.dp), color = container, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun TierTag(tier: ModelTier) {
    val cs = MaterialTheme.colorScheme
    when (tier) {
        ModelTier.FAST -> AiTag(tier.label, container = cs.tertiaryContainer, content = cs.onTertiaryContainer)
        ModelTier.BALANCED -> AiTag(tier.label, container = cs.secondaryContainer, content = cs.onSecondaryContainer)
        ModelTier.POWERFUL -> AiTag(tier.label, container = cs.primaryContainer, content = cs.onPrimaryContainer)
    }
}

/**
 * Shown while a 429 is being waited out: what happened, a live countdown, and what the user can do now.
 * [RateLimitInfo.startedAt] anchors the clock, so the ring stays correct across recompositions.
 */
@Composable
fun RateLimitCard(
    info: RateLimitInfo,
    onRetryNow: () -> Unit,
    onSwitchModel: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember(info) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(info) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    val totalMs = (info.waitSec * 1000L).coerceAtLeast(1L)
    val leftMs = (info.startedAt + totalMs - now).coerceIn(0L, totalMs)
    val leftSec = ceil(leftMs / 1000.0).toInt()
    val elapsed = 1f - leftMs.toFloat() / totalMs

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { elapsed },
                        modifier = Modifier.size(52.dp),
                        strokeWidth = 4.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.15f),
                    )
                    Text(
                        "${leftSec}s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Slowing down — rate limit reached",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        "Retrying automatically · attempt ${info.attempt} of ${info.maxAttempts}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Text(
                "${info.provider} limits how quickly ${info.model} can be called. Nothing is lost — your message is sent again " +
                    "as soon as the timer ends. Agent runs make several requests per message, so big projects hit this sooner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = onRetryNow) { Text("Retry now") }
                OutlinedButton(onClick = onSwitchModel) { Text("Switch model") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

/** A failed request, explained: title, why it happened, and one button per sensible next step. */
@Composable
fun AiErrorCard(
    info: AiErrorInfo,
    onAction: (ErrorAction) -> Unit,
    onDismiss: () -> Unit,
    canOpenSettings: Boolean,
    modifier: Modifier = Modifier,
) {
    val actions = info.actions.filter { it != ErrorAction.OPEN_SETTINGS || canOpenSettings }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    XIcons.ErrorCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        info.explanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(XIcons.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { i, a ->
                        val label = errorActionLabel(a)
                        if (i == 0) Button(onClick = { onAction(a) }) { Text(label) }
                        else OutlinedButton(onClick = { onAction(a) }) { Text(label) }
                    }
                }
            }
        }
    }
}

/** Plain-string error (anything that has no structured explanation). */
@Composable
fun SimpleErrorCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            IconButton(onClick = onDismiss) { Icon(XIcons.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

/** Model selector in the input field: plain label + chevron (like Claude), tap to switch. */
@Composable
fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.ifBlank { "Choose model" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(XIcons.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Thin bar showing how much of the model's context window this chat's latest request used (estimate). */
@Composable
fun ContextMeter(usedTokens: Int, maxTokens: Int, modifier: Modifier = Modifier) {
    if (usedTokens <= 0 || maxTokens <= 0) return
    val fraction = (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
    val color = when {
        fraction > 0.85f -> MaterialTheme.colorScheme.error
        fraction > 0.6f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().heightIn(max = 3.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            "Context ~${ModelInfo.formatTokens(usedTokens.toLong())} of ${ModelInfo.formatTokens(maxTokens.toLong())} tokens" +
                if (fraction > 0.85f) " — nearly full, consider a new chat" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Quick provider/model switcher opened from the chat header or from a rate-limit/error card. */
@Composable
fun ModelSwitchSheet(
    status: AgentChatViewModel.AiStatus,
    onPick: (providerId: String, model: AiModel) -> Unit,
    onOpenSettings: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var providerId by remember { mutableStateOf(status.providerId) }
    val provider = ProviderRegistry.byId(providerId)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Switch model", style = MaterialTheme.typography.titleMedium)
            Text(
                "Takes effect on your next message. Providers without a saved key are greyed out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderRegistry.all.forEach { p ->
                    val keyed = p.id in status.keyedProviders
                    FilterChip(
                        selected = p.id == providerId,
                        onClick = { providerId = p.id },
                        enabled = keyed,
                        label = { Text(if (keyed) p.displayName else "${p.displayName} · no key") },
                    )
                }
            }
            if (status.customModelActive) {
                Text(
                    "A custom model id is set in Settings. Picking a model here clears it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.defaultModels.forEach { m ->
                    val selected = provider.id == status.providerId && m.id == status.modelId
                    Surface(
                        onClick = { onPick(provider.id, m); onDismiss() },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.titleSmall)
                                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ModelInfo.tier(m.id)?.let { TierTag(it) }
                                    AiTag(ModelInfo.contextLabel(m.maxContextChars))
                                }
                            }
                            if (selected) Icon(XIcons.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (onOpenSettings != null) {
                TextButton(onClick = { onDismiss(); onOpenSettings() }, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(XIcons.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  More in AI settings")
                }
            }
        }
    }
}

fun errorActionLabel(a: ErrorAction): String = when (a) {
    ErrorAction.RETRY -> "Try again"
    ErrorAction.SWITCH_MODEL -> "Switch model"
    ErrorAction.OPEN_SETTINGS -> "Open settings"
}
