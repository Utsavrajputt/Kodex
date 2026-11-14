package com.invictus.kodex.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.signing.GeneratedKeystore
import com.invictus.kodex.github.ui.ClipboardHelper
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

private val FIELD = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreToolScreen(
    onBack: () -> Unit,
    vm: KeystoreToolViewModel = viewModel(factory = KeystoreToolViewModel.Factory),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showPassword by remember { mutableStateOf(false) }
    var repoDialog by remember { mutableStateOf(false) }
    var fileTarget by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri -> uri?.let(vm::save) }
    val fileLoader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val name = fileTarget
        if (uri != null && name != null) vm.loadFromFile(name, uri)
    }

    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            if (e is KeystoreToolViewModel.Effect.Message) scope.launch { snackbar.showSnackbar(e.text.resolve(context)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_keystore_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !s.busy) { Icon(XIcons.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            // ---- 1. what to add ----
            Text(stringResource(R.string.tools_what_to_add), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecretChip(stringResource(R.string.tools_chip_keystore), s.keystoreOn, !s.busy, vm::toggleKeystore) { vm.toggleAlso("keystore") }
                SECRET_PRESETS.forEach { p ->
                    SecretChip(stringResource(p.title), p.id in s.presetsOn, !s.busy, { vm.togglePreset(p.id) }) { vm.toggleAlso(p.id) }
                }
                SecretChip(stringResource(R.string.tools_chip_custom), s.customOn, !s.busy, vm::toggleCustom) { vm.toggleAlso("custom") }
            }

            // ---- 2. the chosen ones ----
            if (s.keystoreOn) {
                Card(R.string.tools_section_keystore) {
                    Field(s.alias, { vm.edit { copy(alias = it) } }, R.string.tools_alias, !s.busy)
                    Field(s.commonName, { vm.edit { copy(commonName = it) } }, R.string.tools_cn, !s.busy)
                    OutlinedTextField(
                        value = s.password, onValueChange = { vm.edit { copy(password = it) } }, enabled = !s.busy,
                        label = { Text(stringResource(R.string.tools_password)) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        if (showPassword) XIcons.VisibilityOff else XIcons.Visibility,
                                        contentDescription = stringResource(if (showPassword) R.string.settings_github_hide_token else R.string.settings_github_show_token),
                                    )
                                }
                                IconButton(onClick = {
                                    ClipboardHelper.copy(context, "keystore password", s.password)
                                    scope.launch { snackbar.showSnackbar(context.getString(R.string.tools_password_copied)) }
                                }) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.tools_copy_password), tint = cs.primary) }
                                IconButton(onClick = vm::regeneratePassword, enabled = !s.busy) {
                                    Icon(XIcons.Refresh, contentDescription = stringResource(R.string.tools_new_password), tint = cs.primary)
                                }
                            }
                        },
                        singleLine = true, shape = FIELD, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.tools_key_size), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2048, 3072, 4096).forEach { bits ->
                            FilterChip(selected = s.keySize == bits, enabled = !s.busy, onClick = { vm.edit { copy(keySize = bits) } }, label = { Text("$bits") })
                        }
                    }
                    Text(stringResource(R.string.tools_validity), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 25, 30, 50).forEach { y ->
                            FilterChip(selected = s.years == y, enabled = !s.busy, onClick = { vm.edit { copy(years = y) } }, label = { Text(stringResource(R.string.tools_years, y)) })
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.edit { copy(moreOpen = !moreOpen) } }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.tools_more_options), style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.weight(1f))
                        Icon(if (s.moreOpen) XIcons.ExpandLess else XIcons.ExpandMore, contentDescription = null, tint = cs.primary)
                    }
                    if (s.moreOpen) {
                        Text(stringResource(R.string.tools_section_cert), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Field(s.orgUnit, { vm.edit { copy(orgUnit = it) } }, R.string.tools_ou, !s.busy)
                        Field(s.org, { vm.edit { copy(org = it) } }, R.string.tools_org, !s.busy)
                        Field(s.locality, { vm.edit { copy(locality = it) } }, R.string.tools_locality, !s.busy)
                        Field(s.province, { vm.edit { copy(province = it) } }, R.string.tools_state, !s.busy)
                        Field(s.country, { vm.edit { copy(country = it.take(2).uppercase()) } }, R.string.tools_country, !s.busy)
                        Text(stringResource(R.string.tools_section_names), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Field(s.names.keystore, { vm.edit { copy(names = names.copy(keystore = it.uppercase())) } }, R.string.tools_name_keystore, !s.busy)
                        Field(s.names.storePassword, { vm.edit { copy(names = names.copy(storePassword = it.uppercase())) } }, R.string.tools_name_store_password, !s.busy)
                        Field(s.names.alias, { vm.edit { copy(names = names.copy(alias = it.uppercase())) } }, R.string.tools_name_alias, !s.busy)
                        Field(s.names.keyPassword, { vm.edit { copy(names = names.copy(keyPassword = it.uppercase())) } }, R.string.tools_name_key_password, !s.busy)
                    }
                }
            }

            SECRET_PRESETS.filter { it.id in s.presetsOn }.forEach { p ->
                Card(p.title) {
                    p.fields.forEach { f ->
                        if (f.multiline) {
                            OutlinedTextField(
                                value = s.presetValues[f.name].orEmpty(), onValueChange = { vm.setPresetValue(f.name, it) }, enabled = !s.busy,
                                label = { Text(f.name) }, minLines = 3, maxLines = 6, shape = FIELD, modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            TextButton(onClick = { fileTarget = f.name; fileLoader.launch(arrayOf("application/json", "text/*", "*/*")) }, enabled = !s.busy) {
                                Icon(XIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.tools_load_file))
                            }
                        } else {
                            SecretValueField(f.name, s.presetValues[f.name].orEmpty(), !s.busy) { vm.setPresetValue(f.name, it) }
                        }
                    }
                }
            }

            if (s.customOn) {
                Card(R.string.tools_section_extras) {
                    Text(stringResource(R.string.tools_extras_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    s.extras.forEach { e ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field(e.name, { vm.updateExtra(e.id, name = it.uppercase()) }, R.string.tools_extra_name, !s.busy)
                                SecretValueField(stringResource(R.string.tools_extra_value), e.value, !s.busy) { vm.updateExtra(e.id, value = it) }
                            }
                            IconButton(onClick = { vm.removeExtra(e.id) }, enabled = !s.busy) {
                                Icon(XIcons.Delete, contentDescription = stringResource(R.string.tools_remove), tint = cs.error)
                            }
                        }
                    }
                    TextButton(onClick = vm::addExtra, enabled = !s.busy) {
                        Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tools_add_another))
                    }
                }
            }

            // ---- 3. where ----
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer)
                    .clickable(enabled = !s.busy) { repoDialog = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(XIcons.GitHub, contentDescription = null, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tools_apply_row_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (s.selected.isEmpty()) stringResource(R.string.tools_apply_row_none)
                        else context.resources.getQuantityString(R.plurals.tools_selected_count, s.selected.size, s.selected.size),
                        style = MaterialTheme.typography.bodyMedium, color = if (s.selected.isEmpty()) cs.onSurfaceVariant else cs.primary,
                    )
                }
                Icon(XIcons.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
            }

            // ---- 4. go ----
            Button(onClick = vm::generate, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                else Icon(XIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                val n = s.selected.size
                Text(
                    when {
                        s.generating -> stringResource(R.string.tools_generating)
                        s.applyBusy -> stringResource(R.string.tools_applying)
                        s.keystoreOn && n > 0 -> context.resources.getQuantityString(R.plurals.tools_generate_apply, n, n)
                        s.keystoreOn -> stringResource(R.string.tools_generate)
                        n > 0 -> context.resources.getQuantityString(R.plurals.tools_save_secrets, n, n)
                        else -> stringResource(R.string.tools_save_secrets_pick)
                    },
                )
            }

            // ---- 5. result ----
            s.result?.let { r ->
                ResultCard(
                    r = r, canApply = s.selected.isNotEmpty() && !s.busy,
                    onApply = vm::applyToSelected,
                    onSave = { saver.launch("${r.alias}.p12") },
                    onCopyFingerprint = {
                        ClipboardHelper.copy(context, "SHA-256", r.sha256, sensitive = false)
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.tools_fingerprint_copied)) }
                    },
                )
            }
            if (s.applying.isNotEmpty()) {
                Card(R.string.tools_section_results) {
                    s.applying.forEach { row ->
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            when (row.status) {
                                ApplyStatus.Done -> Icon(XIcons.CheckCircle, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                                ApplyStatus.Failed -> Icon(XIcons.ErrorCircle, contentDescription = null, tint = cs.error, modifier = Modifier.size(20.dp))
                                ApplyStatus.Working -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                ApplyStatus.Pending -> Spacer(Modifier.size(20.dp))
                            }
                            Column {
                                Text(row.repo, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                row.error?.let { Text(it.resolve(context), style = MaterialTheme.typography.bodySmall, color = cs.error) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (repoDialog) RepoPickerDialog(s, vm) { repoDialog = false }
}

/** "Apply to" dialog: search, tick the repositories, Select all / Clear, Done. */
@Composable
private fun RepoPickerDialog(s: KeystoreToolViewModel.UiState, vm: KeystoreToolViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.tools_apply_row_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = s.query, onValueChange = { vm.edit { copy(query = it) } },
                    placeholder = { Text(stringResource(R.string.tools_search_repos)) },
                    leadingIcon = { Icon(XIcons.Search, contentDescription = null) },
                    singleLine = true, shape = FIELD, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.resources.getQuantityString(R.plurals.tools_selected_count, s.selected.size, s.selected.size),
                        style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::selectAllVisible, enabled = s.visibleRepos.isNotEmpty()) { Text(stringResource(R.string.tools_select_all)) }
                    TextButton(onClick = vm::clearSelection, enabled = s.selected.isNotEmpty()) { Text(stringResource(R.string.tools_clear)) }
                }
                Column(Modifier.weight(1f)) {
                    when {
                        s.reposLoading -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        s.reposError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.reposError!!.resolve(context), color = cs.error, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm::loadRepos) { Text(stringResource(R.string.gh_retry)) }
                        }
                        s.visibleRepos.isEmpty() -> Text(stringResource(R.string.tools_no_repos), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(s.visibleRepos, key = { it.fullName }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.toggleRepo(r.fullName) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = r.fullName in s.selected, onCheckedChange = { vm.toggleRepo(r.fullName) })
                                    Column(Modifier.weight(1f)) {
                                        Text(r.fullName, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                        val tags = listOfNotNull(
                                            if (r.isPrivate) stringResource(R.string.tools_tag_private) else null,
                                            if (r.isFork) stringResource(R.string.tools_tag_fork) else null,
                                        )
                                        if (tags.isNotEmpty()) Text(tags.joinToString(" \u2022 "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tools_done)) }
            }
        }
    }
}

/**
 * Tap = choose only this one. Long-press = add it to (or take it off) what is already chosen.
 * FilterChip has no long-press, so a see-through layer on top carries both gestures.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SecretChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box {
        FilterChip(
            selected = selected, enabled = enabled, onClick = {}, label = { Text(label) },
            leadingIcon = if (selected) ({ Icon(XIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Checkbox,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                ),
        )
    }
}

@Composable
private fun ResultCard(r: GeneratedKeystore, canApply: Boolean, onApply: () -> Unit, onSave: () -> Unit, onCopyFingerprint: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.primaryContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(XIcons.CheckCircle, contentDescription = null, tint = cs.onPrimaryContainer)
            Text(stringResource(R.string.tools_result_title), style = MaterialTheme.typography.titleMedium, color = cs.onPrimaryContainer)
        }
        Text(r.subject, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
        Text(stringResource(R.string.tools_result_valid, r.validUntil), style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
        Text(
            r.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onPrimaryContainer,
            modifier = Modifier.clickable(onClick = onCopyFingerprint),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tools_save_file)) }
            Button(onClick = onApply, enabled = canApply, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tools_apply_again)) }
        }
    }
}

@Composable
private fun Card(title: Int, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(stringResource(label)) }, singleLine = true, shape = FIELD, modifier = Modifier.fillMaxWidth(),
    )
}

/** A secret value: hidden by default, eye to show. [label] is the secret's name. */
@Composable
private fun SecretValueField(label: String, value: String, enabled: Boolean, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(label) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) XIcons.VisibilityOff else XIcons.Visibility,
                    contentDescription = stringResource(if (show) R.string.settings_github_hide_token else R.string.settings_github_show_token),
                )
            }
        },
        singleLine = true, shape = FIELD, modifier = Modifier.fillMaxWidth(),
    )
}
