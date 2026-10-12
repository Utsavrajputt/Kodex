package com.invictus.kodex.ai.settings

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.ai.agent.AiUsage
import com.invictus.kodex.ai.keys.AiKeyStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AiErrorPresenter
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiModel
import com.invictus.kodex.ai.provider.AiRequest
import com.invictus.kodex.ai.provider.ModelInfo
import com.invictus.kodex.ai.provider.ModelList
import com.invictus.kodex.ai.provider.ProviderRegistry
import com.invictus.kodex.ai.usage.UsageLedger
import com.invictus.kodex.ai.usage.UsageStore
import com.invictus.kodex.ai.usage.UsageView
import com.invictus.kodex.ai.ui.chat.AiTag
import com.invictus.kodex.ai.ui.chat.TierTag
import com.invictus.kodex.ui.icons.XIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        data class Ok(val millis: Long, val model: String) : TestState
        data class Failed(val title: String, val detail: String) : TestState
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

    private val _keyHint = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Masked tail of each saved key ("••••a1b2"), so the user can tell which key is stored without revealing it. */
    val keyHint: StateFlow<Map<String, String>> = _keyHint.asStateFlow()

    val usage: StateFlow<AiUsage.Snapshot> = AiUsage.state
    val ledger = UsageStore.ledger
    val dailyLimits = UsageStore.limits

    fun setDailyLimit(provider: String, model: String, limit: Int) = UsageStore.setDailyLimit(provider, model, limit)

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
        _keyHint.value = ProviderRegistry.all.mapNotNull { p ->
            keyStore.get(p.id)?.takeIf { it.isNotBlank() }?.let { p.id to "••••" + it.takeLast(4) }
        }.toMap()
    }

    fun resetUsage() {
        AiUsage.reset()
    }

    fun messageHandled() {
        _messages.value = null
    }

    fun selectProvider(id: String) {
        _test.value = TestState.Idle
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
            _test.value = TestState.Failed("No key saved", "Paste your ${provider.displayName} API key above, save it, then test again.")
            return
        }
        val model = _modelId.value.ifEmpty { provider.defaultModels.first().id }
        _test.value = TestState.Testing
        viewModelScope.launch {
            val started = System.nanoTime()
            _test.value = try {
                provider.complete(
                    key, model,
                    AiRequest(
                        messages = listOf(AiMessage("user", "Reply with exactly: OK")),
                        maxOutputTokens = 16, temperature = 0.0, model = model,
                    ),
                )
                TestState.Ok((System.nanoTime() - started) / 1_000_000, model)
            } catch (e: AiException) {
                val info = AiErrorPresenter.describe(e.error, provider.displayName)
                TestState.Failed(info.title, info.explanation)
            } catch (e: Exception) {
                TestState.Failed("Test failed", e.message ?: "Unexpected error")
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
    val keyHint by vm.keyHint.collectAsState()
    val test by vm.test.collectAsState()
    val usage by vm.usage.collectAsState()
    val ledger by vm.ledger.collectAsState()
    val dailyLimits by vm.dailyLimits.collectAsState()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowMs = System.currentTimeMillis() } }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.messages.collectAsState()
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageHandled() }
    }

    val provider = ProviderRegistry.byId(providerId)
    var keyDraft by remember(providerId) { mutableStateOf("") }
    var reveal by remember(providerId) { mutableStateOf(false) }
    var showAllModels by remember(providerId) { mutableStateOf(false) }
    val keySaved = hasKey[providerId] == true
    val modelList = models.ifEmpty { provider.defaultModels }
    val selectedId = modelId.ifEmpty { provider.defaultModels.first().id }
    val activeId = customModel.trim().ifEmpty { selectedId }
    val activeModel = modelList.firstOrNull { it.id == activeId } ?: provider.defaultModels.firstOrNull { it.id == activeId }
    val ready = keySaved && consent

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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- status hero ----
            Surface(shape = RoundedCornerShape(24.dp), color = Color.Transparent) {
                Column(
                    Modifier
                        .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                        .padding(18.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(XIcons.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                activeModel?.label ?: activeId,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                provider.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                        }
                        AiTag(
                            if (ready) "Ready" else "Setup needed",
                            container = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            content = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HeroCheck(keySaved, if (keySaved) "API key saved" else "Add an API key to begin")
                    HeroCheck(consent, if (consent) "AI allowed in projects" else "Turn on \"Allow AI in projects\" below")
                    activeModel?.let { HeroCheck(true, ModelInfo.contextLabel(it.maxContextChars) + " window") }
                    HeroUsage(
                        view = UsageLedger.view(ledger, dailyLimits, providerId, activeId, nowMs),
                        nowMs = nowMs,
                        contextTokens = usage.lastRequestTokens,
                        contextWindowTokens = activeModel?.let { it.maxContextChars / 4 } ?: 0,
                        resetsOnPacific = providerId == "gemini",
                    )
                }
            }

            // ---- provider ----
            SectionCard("Provider", XIcons.Cloud, "Who runs the model. Each provider uses its own API key.") {
                ProviderRegistry.all.forEach { p ->
                    SelectableRow(
                        selected = p.id == providerId,
                        onClick = { vm.selectProvider(p.id) },
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (hasKey[p.id] == true) "Key saved ${keyHint[p.id].orEmpty()}" else "No key yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AiTag(
                            "${p.defaultModels.size} models",
                            container = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            }

            // ---- model ----
            SectionCard(
                "Model",
                XIcons.Tune,
                "Bigger models reason better but are slower and hit limits sooner. Tags are a rough guide.",
            ) {
                val visible = if (showAllModels) modelList else modelList.take(6)
                visible.forEach { m ->
                    SelectableRow(selected = m.id == selectedId, onClick = { vm.selectModel(m.id) }) {
                        Column(Modifier.weight(1f)) {
                            Text(m.label, style = MaterialTheme.typography.titleSmall)
                            Text(m.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ModelInfo.tier(m.id)?.let { TierTag(it) }
                                if (m.maxContextChars > 0) AiTag(ModelInfo.contextLabel(m.maxContextChars))
                            }
                        }
                    }
                }
                if (modelList.size > 6) {
                    TextButton(onClick = { showAllModels = !showAllModels }) {
                        Text(if (showAllModels) "Show fewer" else "Show all ${modelList.size} models")
                    }
                }
                if (customModel.isNotBlank()) {
                    InfoNote(
                        "A custom model id is active (${customModel.trim()}). It overrides the selection above until you clear it.",
                        warn = true,
                    )
                }
                SyncedTextField(
                    value = customModel,
                    onValueChange = { vm.setCustomModel(it) },
                    label = { Text("Custom model id (optional)") },
                    supportingText = { Text("For a model that isn't listed yet. Leave empty to use the selection above.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- key & connection ----
            SectionCard("API key & connection", XIcons.Key, "Stored encrypted on this device (Keystore + AES/GCM). It only leaves the device to reach ${provider.displayName}.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (keySaved) XIcons.CheckCircle else XIcons.ErrorCircle,
                        contentDescription = null,
                        tint = if (keySaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (keySaved) "Key saved  ${keyHint[providerId].orEmpty()}" else "No key saved for ${provider.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    KEY_URLS[providerId]?.let { url ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
                            Text("Get a key")
                            Icon(XIcons.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(14.dp))
                        }
                    }
                }
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it },
                    label = { Text(if (keySaved) "Replace key" else "Paste API key") },
                    singleLine = true,
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(if (reveal) XIcons.VisibilityOff else XIcons.Visibility, contentDescription = if (reveal) "Hide key" else "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveKey(providerId, keyDraft); keyDraft = "" }, enabled = keyDraft.isNotBlank()) {
                        Text("Save key")
                    }
                    if (keySaved) {
                        OutlinedButton(onClick = { vm.clearKey(providerId) }) { Text("Remove") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { vm.testConnection() }, enabled = test !is AiSettingsViewModel.TestState.Testing) {
                        Text("Test connection")
                    }
                    if (test is AiSettingsViewModel.TestState.Testing) {
                        CircularProgressIndicator(Modifier.padding(start = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        Text("  Contacting ${provider.displayName}…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                when (val t = test) {
                    is AiSettingsViewModel.TestState.Ok -> StatusPanel(
                        ok = true,
                        title = "Connected · ${t.millis} ms",
                        detail = "${provider.displayName} accepted your key and answered using ${t.model}.",
                    )
                    is AiSettingsViewModel.TestState.Failed -> StatusPanel(ok = false, title = t.title, detail = t.detail)
                    else -> Unit
                }
            }

            // ---- usage ----
            SectionCard("Usage this session", XIcons.Bolt, "Counted since the app started. Token numbers are estimates, not your provider's bill.") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Requests", usage.requests.toString(), Modifier.weight(1f))
                    StatTile("Limit hits", usage.rateLimitHits.toString(), Modifier.weight(1f), warn = usage.rateLimitHits > 0)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Sent (est.)", "~" + ModelInfo.formatTokens(usage.inputTokens), Modifier.weight(1f))
                    StatTile("Received (est.)", "~" + ModelInfo.formatTokens(usage.outputTokens), Modifier.weight(1f))
                }
                val total = usage.inputTokens + usage.outputTokens
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { usage.inputTokens.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = MaterialTheme.colorScheme.tertiaryContainer,
                    )
                    Text(
                        "Sent vs received. Every agent step re-sends the conversation, so \"sent\" is usually much larger.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                usage.lastRateLimitAt?.let { at ->
                    val agoSec = ((System.currentTimeMillis() - at) / 1000).toInt().coerceAtLeast(0)
                    InfoNote(
                        "Last limit hit ${AiErrorPresenter.formatWait(agoSec)} ago — " +
                            if (usage.lastRateLimitDaily) "a daily quota (resets later)."
                            else "a short-term limit" + (usage.lastRateLimitWaitSec?.let { ", Kodex waited ${AiErrorPresenter.formatWait(it)}." } ?: "."),
                        warn = true,
                    )
                }
                SyncedTextField(
                    value = dailyLimits[UsageLedger.key(providerId, activeId)]?.toString().orEmpty(),
                    onValueChange = { vm.setDailyLimit(providerId, activeId, it.filter(Char::isDigit).take(7).toIntOrNull() ?: 0) },
                    label = { Text("Daily request limit (optional)") },
                    supportingText = { Text("Your plan's real cap for this model. Leave empty to use Kodex's rough default, if there is one.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { vm.resetUsage() }, enabled = usage.requests > 0 || usage.rateLimitHits > 0) { Text("Reset counters") }
            }

            // ---- limits explainer ----
            var limitsOpen by remember { mutableStateOf(false) }
            SectionCard("Understanding rate limits", XIcons.Info, null, onHeaderClick = { limitsOpen = !limitsOpen }, trailing = {
                Icon(if (limitsOpen) XIcons.KeyboardArrowUp else XIcons.KeyboardArrowDown, contentDescription = null)
            }) {
                if (limitsOpen) {
                    LimitPoint("Short-term limit", "Too many requests in a short window. Kodex waits the time the provider asks for (shown as a countdown in the chat) and retries up to 4 times, or you can tap Retry now.")
                    LimitPoint("Daily quota", "A per-day cap, common on free plans. Waiting seconds won't help — switch model or provider, or come back after the reset.")
                    LimitPoint("Why agents hit limits", "One message can trigger many requests (read files, search, edit). Lighter models usually allow more requests per minute.")
                    LimitPoint("What to do", "Use the model chip in the chat to switch on the spot, or add another provider's key here and switch to it.")
                } else {
                    Text("Tap to see what the countdown and quota messages mean.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ---- privacy ----
            SectionCard("Privacy", XIcons.Visibility, "You decide what the assistant may see.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow AI in projects", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When on, selected code and file contents are sent to ${provider.displayName} when you use AI features.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = consent, onCheckedChange = { vm.setConsent(it) })
                }
                SyncedTextField(
                    value = globs,
                    onValueChange = { vm.setGlobs(it) },
                    label = { Text("Never send files matching") },
                    supportingText = { Text("Comma-separated, e.g. .env, *.pem, secrets/**") },
                    modifier = Modifier.fillMaxWidth(),
                )
                InfoNote("Obvious secrets (API keys, tokens, private keys) are masked before anything is sent.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val KEY_URLS = mapOf(
    "claude" to "https://console.anthropic.com/settings/keys",
    "openai" to "https://platform.openai.com/api-keys",
    "gemini" to "https://aistudio.google.com/app/apikey",
)

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    subtitle: String?,
    onHeaderClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().then(if (onHeaderClick != null) Modifier.clickable { onHeaderClick() } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp)) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun SelectableRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(10.dp))
            content()
        }
    }
}

@Composable
private fun HeroCheck(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(
            if (ok) XIcons.CheckCircle else XIcons.ErrorCircle,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Today / this month / last-request-vs-window block for the status card. Token numbers are estimates. */
@Composable
private fun HeroUsage(view: UsageView, nowMs: Long, contextTokens: Int, contextWindowTokens: Int, resetsOnPacific: Boolean) {
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = onHero.copy(alpha = 0.2f))
    val limit = view.dailyLimit
    Text(
        "Today: ${view.day.requests}" + (if (limit != null) " / $limit" else "") + " requests · ~" +
            ModelInfo.formatTokens(view.day.tokens) + " tokens",
        style = MaterialTheme.typography.bodyMedium, color = onHero, fontWeight = FontWeight.Medium,
    )
    view.dailyFraction?.let { f ->
        val warn = f >= 0.8f
        LinearProgressIndicator(
            progress = { f.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (warn) MaterialTheme.colorScheme.error else onHero,
            trackColor = onHero.copy(alpha = 0.15f),
        )
    }
    view.resetAtMillis?.let { at ->
        Text(
            "Daily quota resets in ${UsageLedger.formatRemaining(at - nowMs)}" +
                (if (resetsOnPacific) " (midnight Pacific)" else "") +
                (if (view.limitIsCustom) "" else " · limit is a rough guess"),
            style = MaterialTheme.typography.labelSmall, color = onHero.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "This month: ${view.month.requests} requests · ~" + ModelInfo.formatTokens(view.month.tokens) + " tokens",
        style = MaterialTheme.typography.bodySmall, color = onHero, modifier = Modifier.padding(top = 8.dp),
    )
    if (contextWindowTokens > 0 && contextTokens > 0) {
        Text(
            "Last request: ~${ModelInfo.formatTokens(contextTokens.toLong())} of ${ModelInfo.formatTokens(contextWindowTokens.toLong())} window",
            style = MaterialTheme.typography.bodySmall, color = onHero.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp),
        )
    }
    Text(
        "The ~250K figure is the window per request, not a daily or monthly allowance. Tokens are estimates.",
        style = MaterialTheme.typography.labelSmall, color = onHero.copy(alpha = 0.6f), modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPanel(ok: Boolean, title: String, detail: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
            Icon(if (ok) XIcons.CheckCircle else XIcons.ErrorCircle, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = fg)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = fg)
            }
        }
    }
}

@Composable
private fun InfoNote(text: String, warn: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (warn) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Icon(XIcons.Info, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun LimitPoint(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
