package com.invictus.kodex.feature.tools

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.invictus.kodex.R
import com.invictus.kodex.github.api.GhRepoItem
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.delay

private val DIALOG_SHAPE = RoundedCornerShape(28.dp)

/** Round tinted icon + title, the same header the merge-branch dialog uses. */
@Composable
private fun DialogHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String?, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(cs.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        trailing()
    }
}

/** A small filled field: label above, rounded tinted box, primary outline only while typing. */
@Composable
private fun CompactField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, mono: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface, fontFamily = if (mono) FontFamily.Monospace else null),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.fillMaxWidth().clip(shape).background(cs.surfaceContainerHigh)
                .border(1.dp, if (focused) cs.primary else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** "More options" of the keystore: certificate details and the names of the secrets, in a small dialog. */
@Composable
internal fun KeystoreOptionsDialog(
    s: KeystoreToolViewModel.UiState,
    onEdit: (KeystoreToolViewModel.UiState.() -> KeystoreToolViewModel.UiState) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = DIALOG_SHAPE, color = cs.surfaceContainer, tonalElevation = 0.dp) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
                DialogHeader(XIcons.Tune, stringResource(R.string.tools_more_options), stringResource(R.string.tools_options_hint))
                Spacer(Modifier.size(16.dp))
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.tools_section_cert), style = MaterialTheme.typography.labelLarge, color = cs.primary, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(stringResource(R.string.tools_ou), s.orgUnit, { v -> onEdit { copy(orgUnit = v) } }, Modifier.weight(1f))
                        CompactField(stringResource(R.string.tools_org), s.org, { v -> onEdit { copy(org = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(stringResource(R.string.tools_locality), s.locality, { v -> onEdit { copy(locality = v) } }, Modifier.weight(1f))
                        CompactField(stringResource(R.string.tools_state), s.province, { v -> onEdit { copy(province = v) } }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(stringResource(R.string.tools_country), s.country, { v -> onEdit { copy(country = v.take(2).uppercase()) } }, Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.tools_section_names), style = MaterialTheme.typography.labelLarge, color = cs.primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(stringResource(R.string.tools_name_keystore), s.names.keystore, { v -> onEdit { copy(names = names.copy(keystore = v.uppercase())) } }, Modifier.weight(1f), mono = true)
                        CompactField(stringResource(R.string.tools_name_store_password), s.names.storePassword, { v -> onEdit { copy(names = names.copy(storePassword = v.uppercase())) } }, Modifier.weight(1f), mono = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(stringResource(R.string.tools_name_alias), s.names.alias, { v -> onEdit { copy(names = names.copy(alias = v.uppercase())) } }, Modifier.weight(1f), mono = true)
                        CompactField(stringResource(R.string.tools_name_key_password), s.names.keyPassword, { v -> onEdit { copy(names = names.copy(keyPassword = v.uppercase())) } }, Modifier.weight(1f), mono = true)
                    }
                }
                Spacer(Modifier.size(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.tools_done)) }
                }
            }
        }
    }
}

/** The row on the keystore card that opens [KeystoreOptionsDialog]. */
@Composable
internal fun MoreOptionsRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(XIcons.Tune, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tools_more_options), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.tools_options_row_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Icon(XIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
    }
}

/**
 * "Apply to repositories": same look as the merge-branch dialog. Search is hidden behind the icon in the
 * header and slides open on tap; the repositories are rounded rows with a check when picked.
 */
@Composable
internal fun RepoPickerDialog(s: KeystoreToolViewModel.UiState, vm: KeystoreToolViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var searchOpen by remember { mutableStateOf(s.query.isNotEmpty()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) { delay(80); runCatching { focus.requestFocus() } } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = DIALOG_SHAPE, color = cs.surfaceContainer, tonalElevation = 0.dp) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
                DialogHeader(XIcons.GitHub, stringResource(R.string.tools_apply_row_title), null) {
                    IconButton(onClick = {
                        if (searchOpen) vm.edit { copy(query = "") }
                        searchOpen = !searchOpen
                    }) {
                        Icon(
                            if (searchOpen) XIcons.Close else XIcons.Search,
                            contentDescription = stringResource(R.string.tools_search_repos),
                            tint = if (searchOpen) cs.primary else cs.onSurfaceVariant,
                        )
                    }
                }
                AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(XIcons.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (s.query.isEmpty()) Text(stringResource(R.string.tools_search_repos), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            BasicTextField(
                                value = s.query, onValueChange = { v -> vm.edit { copy(query = v) } }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.resources.getQuantityString(R.plurals.tools_selected_count, s.selected.size, s.selected.size),
                        style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::selectAllVisible, enabled = s.visibleRepos.isNotEmpty()) { Text(stringResource(R.string.tools_select_all)) }
                    TextButton(onClick = vm::clearSelection, enabled = s.selected.isNotEmpty()) { Text(stringResource(R.string.tools_clear)) }
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    when {
                        s.reposLoading -> Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        s.reposError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(s.reposError!!.resolve(context), color = cs.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::loadRepos) { Text(stringResource(R.string.gh_retry)) }
                        }
                        s.visibleRepos.isEmpty() -> Text(
                            stringResource(R.string.tools_no_repos), color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp),
                        )
                        else -> LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(s.visibleRepos, key = { it.fullName }) { r -> RepoRow(r, r.fullName in s.selected) { vm.toggleRepo(r.fullName) } }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.tools_done)) }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(r: GhRepoItem, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) cs.primaryContainer else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(XIcons.GitHub, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    styledRepo(r), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else null,
                )
                val tags = listOfNotNull(
                    if (r.isPrivate) stringResource(R.string.tools_tag_private) else null,
                    if (r.isFork) stringResource(R.string.tools_tag_fork) else null,
                )
                if (tags.isNotEmpty()) Text(tags.joinToString(" \u2022 "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(XIcons.Check, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** "owner/" dim, "repo" normal, so the repository name pops. */
@Composable
private fun styledRepo(r: GhRepoItem): AnnotatedString {
    val dim = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    return buildAnnotatedString {
        pushStyle(dim); append(r.owner + "/"); pop()
        append(r.name)
    }
}
