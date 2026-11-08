package com.invictus.kodex.feature.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.invictus.kodex.R
import com.invictus.kodex.github.signing.GeneratedKeystore
import com.invictus.kodex.github.ui.ClipboardHelper
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.launch

private const val MAX_VISIBLE_REPOS = 40

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
    val field = RoundedCornerShape(16.dp)

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri -> uri?.let(vm::save) }

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

            // ---- keystore ----
            Section(R.string.tools_section_keystore) {
                Field(s.alias, { vm.edit { copy(alias = it) } }, R.string.tools_alias, !s.busy, field)
                Field(s.commonName, { vm.edit { copy(commonName = it) } }, R.string.tools_cn, !s.busy, field)
                Field(
                    s.password, { vm.edit { copy(password = it) } }, R.string.tools_password, !s.busy, field,
                    trailing = {
                        IconButton(onClick = {
                            ClipboardHelper.copy(context, "keystore password", s.password)
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.tools_password_copied)) }
                        }) { Icon(XIcons.ContentCopy, contentDescription = stringResource(R.string.tools_copy_password), tint = cs.primary) }
                        IconButton(onClick = vm::regeneratePassword, enabled = !s.busy) {
                            Icon(XIcons.Refresh, contentDescription = stringResource(R.string.tools_new_password), tint = cs.primary)
                        }
                    },
                )
                Text(stringResource(R.string.tools_key_size), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2048, 3072, 4096).forEach { bits ->
                        FilterChip(selected = s.keySize == bits, onClick = { vm.edit { copy(keySize = bits) } }, label = { Text("$bits") })
                    }
                }
                Text(stringResource(R.string.tools_validity), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 25, 30, 50).forEach { y ->
                        FilterChip(selected = s.years == y, onClick = { vm.edit { copy(years = y) } }, label = { Text(stringResource(R.string.tools_years, y)) })
                    }
                }
            }

            // ---- more options ----
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { vm.edit { copy(moreOpen = !moreOpen) } }.padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.tools_more_options), style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.weight(1f))
                Icon(if (s.moreOpen) XIcons.ExpandLess else XIcons.ExpandMore, contentDescription = null, tint = cs.primary)
            }
            if (s.moreOpen) {
                Section(R.string.tools_section_cert) {
                    Field(s.orgUnit, { vm.edit { copy(orgUnit = it) } }, R.string.tools_ou, !s.busy, field)
                    Field(s.org, { vm.edit { copy(org = it) } }, R.string.tools_org, !s.busy, field)
                    Field(s.locality, { vm.edit { copy(locality = it) } }, R.string.tools_locality, !s.busy, field)
                    Field(s.province, { vm.edit { copy(province = it) } }, R.string.tools_state, !s.busy, field)
                    Field(s.country, { vm.edit { copy(country = it.take(2).uppercase()) } }, R.string.tools_country, !s.busy, field)
                }
                Section(R.string.tools_section_names) {
                    Field(s.names.keystore, { vm.edit { copy(names = names.copy(keystore = it.uppercase())) } }, R.string.tools_name_keystore, !s.busy, field)
                    Field(s.names.storePassword, { vm.edit { copy(names = names.copy(storePassword = it.uppercase())) } }, R.string.tools_name_store_password, !s.busy, field)
                    Field(s.names.alias, { vm.edit { copy(names = names.copy(alias = it.uppercase())) } }, R.string.tools_name_alias, !s.busy, field)
                    Field(s.names.keyPassword, { vm.edit { copy(names = names.copy(keyPassword = it.uppercase())) } }, R.string.tools_name_key_password, !s.busy, field)
                }
                Section(R.string.tools_section_extras) {
                    Text(stringResource(R.string.tools_extras_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    s.extras.forEach { e ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Field(e.name, { vm.updateExtra(e.id, name = it.uppercase()) }, R.string.tools_extra_name, !s.busy, field)
                                Field(e.value, { vm.updateExtra(e.id, value = it) }, R.string.tools_extra_value, !s.busy, field)
                            }
                            IconButton(onClick = { vm.removeExtra(e.id) }, enabled = !s.busy) {
                                Icon(XIcons.Delete, contentDescription = stringResource(R.string.tools_remove), tint = cs.error)
                            }
                        }
                    }
                    TextButton(onClick = vm::addExtra, enabled = !s.busy) {
                        Icon(XIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tools_add_secret))
                    }
                }
            }

            // ---- apply to ----
            Section(R.string.tools_section_apply) {
                Text(stringResource(R.string.tools_apply_hint), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                OutlinedTextField(
                    value = s.query, onValueChange = { vm.edit { copy(query = it) } },
                    placeholder = { Text(stringResource(R.string.tools_search_repos)) },
                    leadingIcon = { Icon(XIcons.Search, contentDescription = null) },
                    singleLine = true, shape = field, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        context.resources.getQuantityString(R.plurals.tools_selected_count, s.selected.size, s.selected.size),
                        style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::selectAllVisible, enabled = !s.busy && s.visibleRepos.isNotEmpty()) { Text(stringResource(R.string.tools_select_all)) }
                    TextButton(onClick = vm::clearSelection, enabled = !s.busy && s.selected.isNotEmpty()) { Text(stringResource(R.string.tools_clear)) }
                }
                when {
                    s.reposLoading -> Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    s.reposError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.reposError!!.resolve(context), color = cs.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::loadRepos) { Text(stringResource(R.string.gh_retry)) }
                    }
                    s.visibleRepos.isEmpty() -> Text(stringResource(R.string.tools_no_repos), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        s.visibleRepos.take(MAX_VISIBLE_REPOS).forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !s.busy) { vm.toggleRepo(r.fullName) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = r.fullName in s.selected, onCheckedChange = { vm.toggleRepo(r.fullName) }, enabled = !s.busy)
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
                        if (s.visibleRepos.size > MAX_VISIBLE_REPOS) {
                            Text(
                                stringResource(R.string.tools_more_repos, s.visibleRepos.size - MAX_VISIBLE_REPOS),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Button(onClick = vm::generate, enabled = !s.busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                if (s.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                else Icon(XIcons.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        s.generating -> stringResource(R.string.tools_generating)
                        s.applyBusy -> stringResource(R.string.tools_applying)
                        s.selected.isNotEmpty() -> context.resources.getQuantityString(R.plurals.tools_generate_apply, s.selected.size, s.selected.size)
                        else -> stringResource(R.string.tools_generate)
                    },
                )
            }

            // ---- result ----
            s.result?.let { r ->
                ResultCard(
                    r = r,
                    canApply = s.selected.isNotEmpty() && !s.busy,
                    onApply = vm::applyToSelected,
                    onSave = { saver.launch("${r.alias}.p12") },
                    onCopyFingerprint = {
                        ClipboardHelper.copy(context, "SHA-256", r.sha256, sensitive = false)
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.tools_fingerprint_copied)) }
                    },
                )
            }
            if (s.applying.isNotEmpty()) {
                Section(R.string.tools_section_results) {
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
private fun Section(title: Int, content: @Composable () -> Unit) {
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
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    shape: RoundedCornerShape,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        label = { Text(stringResource(label)) },
        trailingIcon = trailing?.let { t -> { Row(verticalAlignment = Alignment.CenterVertically) { t() } } },
        singleLine = true, shape = shape, modifier = Modifier.fillMaxWidth(),
    )
}
