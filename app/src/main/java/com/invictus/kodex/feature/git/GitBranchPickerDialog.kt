package com.invictus.kodex.feature.git

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.invictus.kodex.R
import com.invictus.kodex.ui.icons.XIcons

/** What the picker body is showing right now. */
internal enum class BranchPickerPhase { LOADING, BUSY, EMPTY, LIST }

/**
 * Shared, compact "pick a branch" dialog used by Merge and Rebase. No fixed min height (the old
 * AlertDialog left a big empty gap above Cancel); loading is a pulsing skeleton instead of a
 * spinner floating in a blank card.
 */
@Composable
internal fun BranchPickerDialog(
    title: String,
    description: String?,
    phase: BranchPickerPhase,
    branches: List<String>,
    busyText: String,
    emptyText: String,
    dismissible: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Branch to mark with a check + highlight (null = no selection state, e.g. Merge / Rebase). */
    selected: String? = null,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            XIcons.AccountTree,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                when (phase) {
                    BranchPickerPhase.LOADING -> SkeletonRows()
                    BranchPickerPhase.BUSY -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(busyText, style = MaterialTheme.typography.bodyMedium)
                    }
                    BranchPickerPhase.EMPTY -> Text(
                        emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                    )
                    BranchPickerPhase.LIST -> Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        branches.forEach { branch -> BranchRow(branch, selected = branch == selected) { onPick(branch) } }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = dismissible) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun BranchRow(branch: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                XIcons.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = styledBranch(branch),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else null,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    XIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** "origin/main" -> dim "origin/" + normal "main", so the actual branch name pops. */
@Composable
private fun styledBranch(branch: String): AnnotatedString {
    val dim = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val slash = branch.indexOf('/')
    return buildAnnotatedString {
        if (slash in 1 until branch.lastIndex) {
            pushStyle(dim)
            append(branch.substring(0, slash + 1))
            pop()
            append(branch.substring(slash + 1))
        } else {
            append(branch)
        }
    }
}

@Composable
private fun SkeletonRows() {
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0.9f, 0.7f, 0.8f).forEach { w ->
            Box(
                Modifier
                    .fillMaxWidth(w)
                    .height(46.dp)
                    .alpha(pulse.value)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(16.dp)),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
