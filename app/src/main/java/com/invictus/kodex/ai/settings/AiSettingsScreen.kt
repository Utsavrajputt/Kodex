package com.invictus.kodex.ai.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.ai.keys.AiKeyStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiModel
import com.invictus.kodex.ai.provider.AiRequest
import com.invictus.kodex.ai.provider.ModelList
import com.invictus.kodex.ai.provider.ProviderRegistry
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AiSettingsViewModel(
    private val app: Application,
    private val prefs: AiPrefs = AiPrefs(app),
    private val keyStore: AiKeyStore = AiKeyStore(app),
) : ViewModel() {

    sealed interface TestState {
        data object Idle : TestState
        data object Testing : TestState
        data object Ok : TestState
        data class Failed(val message: String) : TestState
    }

    val providerId: StateFlow<String> = prefs.providerId.stateIn(viewModelScope, SharingStarted.Eagerly, "claude")
    val customModel: StateFlow<String> = prefs.customModel.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val consent: StateFlow<Boolean> = prefs.consentGranted.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val globs: StateFlow<String> = prefs.excludeGlobs.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _modelId = MutableStateFlow("")
    val modelId: StateFlow<String> = _modelId.asStateFlow()

    private val _models = MutableStateFlow<List<AiModel>>(emptyList())
    /** Dropdown contents: curated defaults, replaced by the live catalogue once it loads. */
    val models: StateFlow<List<AiModel>> = _models.asStateFlow()
    private var modelsJob: Job? = null

    private val _hasKey = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val hasKey: StateFlow<Map<String, Boolean>> = _hasKey.asStateFlow()

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            prefs.migrateRetiredModels()
            prefs.providerId.collect { p ->
                _modelId.value = prefs.modelId(p).first()
                loadModels(p)
            }
        }
    }

    /** Shows the curated defaults at once, then swaps in the provider's live model list if a key is saved. */
    private fun loadModels(providerId: String) {
        modelsJob?.cancel()
        val provider = ProviderRegistry.byId(providerId)
        _models.value = provider.defaultModels
        val key = keyStore.get(provider.id) ?: return
        modelsJob = viewModelScope.launch {
            val fetched = try {
                provider.listModels(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fetched != null) _models.value = ModelList.merge(provider.defaultModels, fetched)
        }
    }

    fun refresh() {
        _hasKey.value = ProviderRegistry.all.associate { it.id to (keyStore.get(it.id) != null) }
    }

    fun messageHandled() {
        _messages.value = null
    }

    fun selectProvider(id: String) {
        viewModelScope.launch {
            prefs.setProviderId(id)
            _modelId.value = prefs.modelId(id).first()
        }
    }

    fun selectModel(model: String) {
        _modelId.value = model
        viewModelScope.launch { prefs.setModelId(providerId.value, model) }
    }

    fun setCustomModel(v: String) {
        viewModelScope.launch { prefs.setCustomModel(v) }
    }

    fun setConsent(v: Boolean) {
        viewModelScope.launch { prefs.setConsentGranted(v) }
    }

    fun setGlobs(v: String) {
        viewModelScope.launch { prefs.setExcludeGlobs(v) }
    }

    fun saveKey(provider: String, key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        keyStore.put(provider, trimmed)
        refresh()
        _messages.value = "Key saved for ${ProviderRegistry.byId(provider).displayName}"
        if (provider == providerId.value) loadModels(provider)
    }

    fun clearKey(provider: String) {
        keyStore.remove(provider)
        refresh()
        if (provider == providerId.value) loadModels(provider)
    }

    fun testConnection() {
        val provider = ProviderRegistry.byId(providerId.value)
        val key = keyStore.get(provider.id)
        if (key == null) {
            _test.value = TestState.Failed("No key saved for ${provider.displayName}")
            return
        }
        val model = _modelId.value.ifEmpty { provider.defaultModels.first().id }
        _test.value = TestState.Testing
        viewModelScope.launch {
            _test.value = try {
                provider.complete(
                    key, model,
                    AiRequest(
                        messages = listOf(AiMessage("user", "Reply with exactly: OK")),
                        maxOutputTokens = 16, temperature = 0.0, model = model,
                    ),
                )
                TestState.Ok
            } catch (e: AiException) {
                TestState.Failed(e.error.message)
            } catch (e: Exception) {
                TestState.Failed(e.message ?: "failed")
            }
        }
    }

    companion object {
        fun factory(app: Application) = viewModelFactory {
            initializer { AiSettingsViewModel(app) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(vm: AiSettingsViewModel, onBack: () -> Unit) {
    val providerId by vm.providerId.collectAsState()
    val modelId by vm.modelId.collectAsState()
    val models by vm.models.collectAsState()
    val customModel by vm.customModel.collectAsState()
    val consent by vm.consent.collectAsState()
    val globs by vm.globs.collectAsState()
    val hasKey by vm.hasKey.collectAsState()
    val test by vm.test.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.messages.collectAsState()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageHandled() }
    }

    val provider = ProviderRegistry.byId(providerId)
    var keyDraft by remember(providerId) { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(XIcons.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("AI Assistant") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            var providerMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = providerMenu, onExpandedChange = { providerMenu = it }) {
                OutlinedTextField(
                    value = provider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                    ProviderRegistry.all.forEach { p ->
                        DropdownMenuItem(text = { Text(p.displayName) }, onClick = { vm.selectProvider(p.id); providerMenu = false })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Model", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            var modelMenu by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { modelMenu = it }) {
                OutlinedTextField(
                    value = modelId.ifEmpty { "Default (${provider.defaultModels.first().id})" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    models.ifEmpty { provider.defaultModels }.forEach { m ->
                        DropdownMenuItem(
                            text = { Text("${m.label} (${m.id})") },
                            onClick = { vm.selectModel(m.id); modelMenu = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SyncedTextField(
                value = customModel,
                onValueChange = { vm.setCustomModel(it) },
                label = { Text("Custom model id (optional, overrides)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
            Text("API key (${provider.displayName})", style = MaterialTheme.typography.labelLarge)
            Text(
                "Stored encrypted on-device (Keystore + AES/GCM). It only ever leaves the device to reach ${provider.displayName}.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                label = { Text(if (hasKey[providerId] == true) "Key saved — enter to replace" else "Paste API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = { vm.saveKey(providerId, keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) {
                    Text("Save key")
                }
                Spacer(Modifier.weight(1f))
                if (hasKey[providerId] == true) {
                    OutlinedButton(onClick = { vm.clearKey(providerId) }) { Text("Remove") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.testConnection() }, enabled = test != AiSettingsViewModel.TestState.Testing) {
                    Text("Test connection")
                }
                if (test == AiSettingsViewModel.TestState.Testing) {
                    Spacer(Modifier.padding(4.dp))
                    CircularProgressIndicator(Modifier.padding(4.dp))
                }
            }
            when (val t = test) {
                AiSettingsViewModel.TestState.Ok -> Text("Connected ✓", color = MaterialTheme.colorScheme.primary)
                is AiSettingsViewModel.TestState.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Allow AI in projects", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "When on, selected code and file contents are sent to ${provider.displayName} when you use AI features.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = consent, onCheckedChange = { vm.setConsent(it) })
            }

            Spacer(Modifier.height(16.dp))
            SyncedTextField(
                value = globs,
                onValueChange = { vm.setGlobs(it) },
                label = { Text("Never send files matching (comma-separated)") },
                supportingText = { Text("e.g. .env, *.pem, secrets/**") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Text field backed by an async store (DataStore). Typing edits local state immediately and the store
 * is written behind it; binding the field straight to the store's flow drops characters and moves the
 * cursor, because each keystroke renders the previous, stale value. Store updates are only adopted
 * while the field isn't focused (initial load, external changes).
 */
@Composable
private fun SyncedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        label = label,
        singleLine = singleLine,
        supportingText = supportingText,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
