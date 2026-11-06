package com.invictus.kodex.github.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.invictus.kodex.github.api.GitHubError
import com.invictus.kodex.github.api.IssueFilter
import com.invictus.kodex.github.api.PullFilter
import com.invictus.kodex.github.data.GitHubRepository
import com.invictus.kodex.ui.icons.XIcons

/** An issue or pull request offered after typing "#". */
data class RefItem(val number: Int, val title: String, val isPull: Boolean, val open: Boolean)

/** Newest issues and pull requests (one page each); a source that fails (e.g. issues switched off) is just left out. */
internal suspend fun GitHubRepository.recentRefs(): List<RefItem> {
    val out = ArrayList<RefItem>()
    try { issues(1, IssueFilter.All, null).items.forEach { out += RefItem(it.number, it.title, false, it.isOpen) } } catch (_: GitHubError) {}
    try { pulls(1, PullFilter.All, null).items.forEach { out += RefItem(it.number, it.title, true, it.isOpen) } } catch (_: GitHubError) {}
    return out.distinctBy { it.number }.sortedByDescending { it.number }
}

private val CLOSING = Regex("""(?i)\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+#(\d+)""")

/** Numbers named after a closing keyword ("fixes #12", "Closes: #7"): the issues GitHub closes when the pull request merges. */
internal fun closingIssueRefs(text: String): List<Int> =
    CLOSING.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.distinct().toList()

/** The "#query" being typed right before [cursor], as (index of '#', query), or null when the cursor is not in one. */
private fun hashToken(text: String, cursor: Int): Pair<Int, String>? {
    var i = cursor - 1
    while (i >= 0 && cursor - i <= 40) {
        val c = text[i]
        if (c == '#') {
            val before = if (i == 0) ' ' else text[i - 1]
            return if (before.isWhitespace() || before == '(' || before == '[') i to text.substring(i + 1, cursor) else null
        }
        if (c.isWhitespace()) return null
        i--
    }
    return null
}

private fun filterRefs(refs: List<RefItem>, query: String): List<RefItem> {
    val q = query.trim()
    val hits = when {
        q.isEmpty() -> refs
        q.all { it.isDigit() } -> refs.filter { it.number.toString().startsWith(q) } +
            refs.filter { !it.number.toString().startsWith(q) && it.title.contains(q, ignoreCase = true) }
        else -> refs.filter { it.title.contains(q, ignoreCase = true) }
    }
    return hits.take(6)
}

/**
 * Text field that offers issues and pull requests when "#" is typed (like github.com): tapping one writes "#123 ".
 * [onNeedRefs] fires when a "#" is first typed, so the list is only fetched when it is wanted.
 */
@Composable
internal fun HashRefField(
    value: String,
    onValueChange: (String) -> Unit,
    refs: List<RefItem>,
    onNeedRefs: () -> Unit,
    p: GhPalette,
    colors: TextFieldColors,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    /** Show the suggestions above the field (for fields at the bottom of the screen). */
    suggestionsAbove: Boolean = false,
) {
    var tfv by remember { mutableStateOf(TextFieldValue(value)) }
    // Text changed from outside (AI fill, reset): follow it, cursor at the end.
    LaunchedEffect(value) { if (tfv.text != value) tfv = TextFieldValue(value, TextRange(value.length)) }

    val token = if (tfv.selection.collapsed) hashToken(tfv.text, tfv.selection.start) else null
    LaunchedEffect(token != null) { if (token != null) onNeedRefs() }
    val matches = if (token != null) filterRefs(refs, token.second) else emptyList()

    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = tfv,
            onValueChange = { tfv = it; if (it.text != value) onValueChange(it.text) },
            label = label, placeholder = placeholder, trailingIcon = trailingIcon,
            singleLine = singleLine, minLines = minLines, maxLines = maxLines, enabled = enabled,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val list: @Composable () -> Unit = {
        if (token != null && matches.isNotEmpty()) {
            val shape = RoundedCornerShape(14.dp)
            Column(Modifier.fillMaxWidth().clip(shape).background(p.runCard).border(1.dp, p.cardBorder, shape)) {
                matches.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val start = token.first
                            val end = tfv.selection.start
                            val insert = "#${r.number} "
                            val text = tfv.text.substring(0, start) + insert + tfv.text.substring(end)
                            tfv = TextFieldValue(text, TextRange(start + insert.length))
                            onValueChange(text)
                        }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (r.isPull) XIcons.PullRequest else if (r.open) XIcons.IssueOpened else XIcons.IssueClosed,
                            contentDescription = null,
                            tint = if (r.open) p.success else p.onCardMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("#${r.number}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.onCard)
                        Spacer(Modifier.width(8.dp))
                        Text(r.title, style = MaterialTheme.typography.bodyMedium, color = p.onCardMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (suggestionsAbove) { list(); field() } else { field(); list() }
    }
}
