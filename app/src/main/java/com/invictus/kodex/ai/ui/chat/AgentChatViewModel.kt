package com.invictus.kodex.ai.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.invictus.kodex.ai.notify.AiBackground
import com.invictus.kodex.ai.notify.AiNotifier
import com.invictus.kodex.ai.notify.AppVisibility
import com.invictus.kodex.ai.AiRuntime
import com.invictus.kodex.ai.Redactor
import com.invictus.kodex.ai.agent.AgentPrompt
import com.invictus.kodex.ai.agent.ProjectInstructions
import com.invictus.kodex.ai.agent.ProjectMemory
import com.invictus.kodex.ai.agent.AgentRunner
import com.invictus.kodex.ai.agent.AgentSink
import com.invictus.kodex.ai.agent.AgentTools
import com.invictus.kodex.ai.agent.ImageImporter
import com.invictus.kodex.ai.agent.RateLimitInfo
import com.invictus.kodex.ai.agent.ApprovedChange
import com.invictus.kodex.ai.agent.ChangeKind
import com.invictus.kodex.ai.agent.ChatMeta
import com.invictus.kodex.ai.agent.ChatStore
import com.invictus.kodex.ai.agent.DiskIo
import com.invictus.kodex.ai.agent.FileChange
import com.invictus.kodex.ai.agent.PendingChanges
import com.invictus.kodex.ai.agent.ProjectFiles
import com.invictus.kodex.ai.agent.StoredChat
import com.invictus.kodex.ai.agent.AppIconApplier
import com.invictus.kodex.ai.agent.GeneratedImage
import com.invictus.kodex.ai.agent.ImageToolbox
import com.invictus.kodex.ai.agent.StoredGenerated
import com.invictus.kodex.ai.agent.StoredMessage
import com.invictus.kodex.ai.agent.StoredStep
import com.invictus.kodex.ai.agent.WorkspaceIo
import com.invictus.kodex.ai.keys.AiKeyStore
import com.invictus.kodex.ai.prefs.AiPrefs
import com.invictus.kodex.ai.provider.AgentMessage
import com.invictus.kodex.ai.provider.AiErrorInfo
import com.invictus.kodex.ai.provider.AiErrorPresenter
import com.invictus.kodex.ai.provider.AiException
import com.invictus.kodex.ai.provider.AiMessage
import com.invictus.kodex.ai.provider.AiModel
import com.invictus.kodex.ai.provider.AiProvider
import com.invictus.kodex.ai.provider.AiRequest
import com.invictus.kodex.ai.provider.ChatImage
import com.invictus.kodex.ai.provider.ImageGen
import com.invictus.kodex.ai.provider.ModelInfo
import com.invictus.kodex.ai.provider.ProviderRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One project's AI agent chat. Shared by the full-screen workspace agent and the editor panel: streaming,
 * live tool steps, saved chats, staged multi-file changes with review/apply/undo.
 */
class AgentChatViewModel(private val app: Application, val projectPath: String) : ViewModel() {

    enum class StepStatus { RUNNING, DONE, FAILED }
    /** A tool step, or (with [thought]) a chunk of the model's reasoning summary shown in the same timeline. */
    data class UiStep(val id: String, val label: String, val status: StepStatus, val thought: Boolean = false)
    data class UiMessage(
        val id: Long,
        val role: String,
        val text: String,
        val steps: List<UiStep> = emptyList(),
        val attachments: List<String> = emptyList(),
        val streaming: Boolean = false,
        /** Where this user turn starts in the agent transcript; used by regenerate/edit to rewind. */
        val histPos: Int = 0,
        val images: List<ChatImage> = emptyList(),
        /** Pictures the agent made in this reply (shown with Save / Use-as-app-icon buttons). */
        val generated: List<GeneratedImage> = emptyList(),
    )
    /** What "Edit" on a user message hands back to the input box. */
    data class EditDraft(val text: String, val files: List<String>, val images: List<ChatImage>)
    data class EditorContext(val absPath: String, val fileText: String?, val selection: String?)
    data class UndoEntry(val path: String, val before: String?, val after: String?)
    data class UndoInfo(val entries: List<UndoEntry>)

    /** Provider / model / readiness shown in the chat header and the first-run setup card. */
    data class AiStatus(
        val loaded: Boolean = false,
        val providerId: String = "claude",
        val providerName: String = "",
        val modelId: String = "",
        val modelLabel: String = "",
        val contextTokens: Int = 0,
        val hasKey: Boolean = true,
        val consent: Boolean = true,
        val keyedProviders: Set<String> = emptySet(),
        val customModelActive: Boolean = false,
    ) {
        val ready get() = hasKey && consent
        val canSeeImages get() = ModelInfo.supportsImages(providerId, modelId)
    }

    private val prefs = AiPrefs(app)
    private val runtime = AiRuntime(app)
    private val keyStore = AiKeyStore(app)
    private val store = ChatStore(app, projectPath)
    private val root = File(projectPath)
    /** Memory + instructions live in the app's private storage, not in the project folder. */
    private val memDir = ProjectMemory.dirFor(app, projectPath)
    private var io: WorkspaceIo = DiskIo

    /** Set by the editor panel: evaluated on the main thread at send time (active file + selection). */
    var contextProvider: (() -> EditorContext?)? = null

    private val globs: StateFlow<String> = prefs.excludeGlobs.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val history = mutableListOf<AgentMessage>()
    private var nextId = 1L
    private var chatId = UUID.randomUUID().toString()
        set(value) {
            field = value
            SessionChats.current[projectPath] = value
        }
    /** Set when the user renamed the open chat; keeps the name instead of re-deriving it from the first message. */
    private var titleOverride: String? = null
    private var job: Job? = null
    private var needSeparator = false
    private var runner: AgentRunner? = null

    private val pending = PendingChanges { _changes.value = it }

    /** Auto-apply mode: files this turn wrote straight to disk, first "before" kept, for the Undo bar. */
    private val turnUndo = LinkedHashMap<String, UndoEntry>()

    private fun recordApplied(path: String, before: String?, after: String?) = synchronized(turnUndo) {
        val first = turnUndo[path]
        turnUndo[path] = UndoEntry(path, if (first != null) first.before else before, after)
    }

    /** Writes one staged change to disk (through the editor's open buffer if any). False = left staged for review. */
    private suspend fun writeThrough(c: FileChange): Boolean {
        val f = File(root, c.path)
        return try {
            val disk = if (f.exists()) io.read(f) else null
            when (c.kind) {
                ChangeKind.CREATE -> {
                    if (f.exists()) return false
                    io.write(f, c.newText.orEmpty())
                    recordApplied(c.path, null, c.newText)
                }
                ChangeKind.EDIT -> {
                    if (disk != c.baseText) return false
                    io.write(f, c.newText.orEmpty())
                    recordApplied(c.path, c.baseText, c.newText)
                }
                ChangeKind.DELETE -> {
                    if (disk != c.baseText) return false
                    io.delete(f)
                    recordApplied(c.path, c.baseText, null)
                }
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            false
        }
    }

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _failure = MutableStateFlow<AiErrorInfo?>(null)
    /** A structured, explained provider error (rate limit, bad key…); plain [error] strings cover everything else. */
    val failure: StateFlow<AiErrorInfo?> = _failure.asStateFlow()
    private val _rateLimit = MutableStateFlow<RateLimitInfo?>(null)
    /** Set while a 429 is being waited out; drives the countdown card. */
    val rateLimit: StateFlow<RateLimitInfo?> = _rateLimit.asStateFlow()
    private val _status = MutableStateFlow(AiStatus())
    val status: StateFlow<AiStatus> = _status.asStateFlow()
    private val _contextUsed = MutableStateFlow(0)
    /** Estimated tokens in this chat's latest request, for the context meter. */
    val contextUsed: StateFlow<Int> = _contextUsed.asStateFlow()
    private val _applyError = MutableStateFlow<String?>(null)
    val applyError: StateFlow<String?> = _applyError.asStateFlow()
    private val _reviewOpen = MutableStateFlow(false)
    val reviewOpen: StateFlow<Boolean> = _reviewOpen.asStateFlow()
    private val _changes = MutableStateFlow<List<FileChange>>(emptyList())
    val changes: StateFlow<List<FileChange>> = _changes.asStateFlow()
    private val _lastApplied = MutableStateFlow<UndoInfo?>(null)
    val lastApplied: StateFlow<UndoInfo?> = _lastApplied.asStateFlow()
    private val _chats = MutableStateFlow<List<ChatMeta>>(emptyList())
    val chats: StateFlow<List<ChatMeta>> = _chats.asStateFlow()
    private val _newChatEpoch = MutableStateFlow(0)
    /** Bumped whenever the chat is reset to a blank one, so the UI can clear its draft input and attachments. */
    val newChatEpoch: StateFlow<Int> = _newChatEpoch.asStateFlow()
    private val _fileIndex = MutableStateFlow<List<String>>(emptyList())
    /** Project-relative text files, for @mentions and the attach picker. */
    val fileIndex: StateFlow<List<String>> = _fileIndex.asStateFlow()

    init {
        viewModelScope.launch {
            // A fresh process starts with a blank chat (older ones are in History). Within one process the
            // chat of this project survives leaving and re-entering the screen.
            val chat = withContext(Dispatchers.IO) { SessionChats.current[projectPath]?.let { store.load(it) } }
            if (chat != null && _messages.value.isEmpty()) restore(chat) else chatId = chatId // remember the open chat
            refreshChats()
            refreshIndex()
        }
        refreshStatus()
    }

    /** Re-reads provider, model, key and consent (call on resume: they change in Settings). */
    fun refreshStatus() {
        viewModelScope.launch {
            val provider = ProviderRegistry.byId(prefs.providerId.first())
            val custom = prefs.customModel.first().trim()
            val model = prefs.currentModel(provider.id, provider.defaultModels)
            val known = provider.defaultModels.firstOrNull { it.id == model }
            _status.value = AiStatus(
                loaded = true,
                providerId = provider.id,
                providerName = provider.displayName,
                modelId = model,
                modelLabel = known?.label ?: model,
                contextTokens = (known?.maxContextChars ?: 100_000) / 4,
                hasKey = !keyStore.get(provider.id).isNullOrBlank(),
                consent = prefs.consentGranted.first(),
                keyedProviders = ProviderRegistry.all.filter { !keyStore.get(it.id).isNullOrBlank() }.map { it.id }.toSet(),
                customModelActive = custom.isNotEmpty(),
            )
        }
    }

    /** Quick switch from the chat header; a custom model id would override the pick, so it is cleared. */
    fun switchModel(providerId: String, model: AiModel) {
        viewModelScope.launch {
            prefs.setProviderId(providerId)
            prefs.setModelId(providerId, model.id)
            prefs.setCustomModel("")
            _failure.value = null
            refreshStatus()
        }
    }

    /** Ends the rate-limit countdown and retries right now. */
    fun retryNow() {
        runner?.skipWait()
    }

    fun bindIo(io: WorkspaceIo) {
        this.io = io
    }

    fun errorHandled() {
        _error.value = null
        _failure.value = null
    }

    // ---- sending ----

    /** Starts a turn; false when nothing was sent (busy, empty, or photos on a model that can't read them), so the caller keeps the draft. */
    fun send(text: String, attached: List<String>, images: List<ChatImage> = emptyList()): Boolean {
        if (_busy.value) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (images.isNotEmpty() && !_status.value.canSeeImages) {
            _error.value = "This model can't read images. Switch to a vision model or remove the photos."
            return false
        }
        startTurn(trimmed, attached, images)
        return true
    }

    /** Copies a picked photo into app storage (downscaled). Null (and an error card) when it can't be read. */
    suspend fun importImage(uri: Uri): ChatImage? {
        val img = withContext(Dispatchers.IO) { ImageImporter.import(app, uri, store.imageDir) }
        if (img == null) _error.value = "Couldn't read that image"
        return img
    }

    /** Deletes a draft photo that was never sent (or was taken back from a rewound message). */
    fun discardImage(img: ChatImage) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { File(img.path).delete() } }
    }

    fun stop() {
        job?.cancel()
    }

    fun regenerate() {
        if (_busy.value) return
        val list = _messages.value
        val idx = list.indexOfLast { it.role == "user" }
        if (idx < 0) return
        val u = list[idx]
        _messages.value = list.take(idx)
        truncateHistory(u.histPos)
        startTurn(u.text, u.attachments, u.images)
    }

    /** Rewinds the chat to just before user message [id] and hands back its text/attachments for editing. */
    fun editFrom(id: Long): EditDraft? {
        if (_busy.value) return null
        val list = _messages.value
        val idx = list.indexOfFirst { it.id == id && it.role == "user" }
        if (idx < 0) return null
        val u = list[idx]
        _messages.value = list.take(idx)
        truncateHistory(u.histPos)
        persistNow()
        return EditDraft(u.text, u.attachments, u.images)
    }

    private fun truncateHistory(pos: Int) {
        while (history.size > pos) history.removeAt(history.lastIndex)
    }

    private fun startTurn(text: String, attached: List<String>, images: List<ChatImage>) {
        _error.value = null
        _failure.value = null
        val startPos = history.size
        val cachedIndex = _fileIndex.value.toSet()
        val mentions = MENTION.findAll(text).map { it.groupValues[2] }.filter { cachedIndex.isEmpty() || it in cachedIndex }.toList()
        val attachedAll = (attached + mentions).distinct()
        val ctx = contextProvider?.invoke()
        _messages.update {
            it + UiMessage(nextId++, "user", text, attachments = attachedAll, histPos = startPos, images = images) +
                UiMessage(nextId++, "assistant", "", streaming = true, histPos = startPos)
        }
        job = viewModelScope.launch {
            _busy.value = true
            // Keeps the process alive (foreground service) so the answer finishes even when the app is minimized.
            AiBackground.begin(app, root.name)
            var cancelled = false
            var failed = false
            try {
                val files = ProjectFiles(root, globs.value, io)
                history += AgentMessage.User(buildUserText(files, attachedAll, ctx, text), images)
                val (provider, model, key) = runtime.resolve()
                val budget = (provider.defaultModels.firstOrNull { it.id == model }?.maxContextChars ?: 100_000)
                    .let { (it * 0.6).toInt().coerceAtMost(300_000) }
                val topLevel = withContext(Dispatchers.IO) {
                    root.listFiles().orEmpty()
                        .filter { it.name !in ProjectFiles.SKIP_DIRS && !files.isBlocked(it.name) }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .take(50).map { if (it.isDirectory) it.name + "/" else it.name }
                }
                // Pictures come from an image model: the chat provider's own if it has one, else any saved Gemini / OpenAI key.
                val imageProvider = (listOf(provider.id) + listOf("gemini", "openai"))
                    .firstOrNull { ImageGen.supports(it) && !(if (it == provider.id) key else keyStore.get(it)).isNullOrBlank() }
                val toolbox = ImageToolbox(
                    imageProviderId = imageProvider,
                    imageKey = imageProvider?.let { if (it == provider.id) key else keyStore.get(it) },
                    outDir = store.imageDir,
                    attachedImage = {
                        history.lastOrNull { it is AgentMessage.User && it.images.isNotEmpty() }
                            ?.let { (it as AgentMessage.User).images.lastOrNull()?.path?.let { p -> File(p) } }
                    },
                    lastGenerated = { _messages.value.flatMap { it.generated }.lastOrNull()?.let { File(it.path) } },
                    onImage = { g -> updateLast { it.copy(generated = it.generated + g) } },
                )
                val auto = prefs.autoApply.first()
                val memoryOn = prefs.memoryEnabled.first()
                val memoryStore = if (memoryOn) ProjectMemory.Store(memDir) { text -> condenseMemory(provider, key, model, text) } else null
                val writer: suspend (FileChange) -> Boolean = { c -> writeThrough(c) }
                synchronized(turnUndo) { turnUndo.clear() }
                val r = AgentRunner(
                    provider, key, model,
                    AgentTools(files, pending, toolbox, writer.takeIf { auto }, memoryStore),
                    budget,
                    sendImages = ModelInfo.supportsImages(provider.id, model),
                )
                runner = r
                val projectInstr = ProjectInstructions.load(files)
                val appInstr = ProjectMemory.loadInstructions(memDir)
                val instructions = when {
                    appInstr == null -> projectInstr
                    projectInstr == null -> ProjectInstructions.Found("Kodex project instructions", appInstr)
                    else -> ProjectInstructions.Found(projectInstr.fileName, appInstr + "\n\n" + projectInstr.text)
                }
                val globalInstructions = prefs.globalInstructions.first().trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { com.invictus.kodex.ai.Redactor.scrub(it).take(AiPrefs.GLOBAL_INSTRUCTIONS_MAX) }
                val memory = if (memoryOn) ProjectMemory.load(memDir) else null
                r.run(
                    AgentPrompt.system(
                        root.name, topLevel, instructions,
                        canPaintImages = toolbox.canRaster, autoApply = auto,
                        globalInstructions = globalInstructions, memoryEnabled = memoryOn, memory = memory,
                    ),
                    history, sink,
                )
                if (_messages.value.lastOrNull()?.let { it.text.isBlank() && it.steps.isEmpty() } == true) {
                    rollback(startPos, text, "", images)
                    _error.value = "The model returned an empty response"
                }
            } catch (ce: CancellationException) {
                cancelled = true
                rollback(startPos, text, _messages.value.lastOrNull()?.text.orEmpty(), images)
                throw ce
            } catch (e: AiException) {
                failed = true
                rollback(startPos, text, _messages.value.lastOrNull()?.text.orEmpty(), images)
                _failure.value = AiErrorPresenter.describe(e.error, providerName())
            } catch (e: Exception) {
                failed = true
                rollback(startPos, text, _messages.value.lastOrNull()?.text.orEmpty(), images)
                _error.value = e.message ?: "Request failed"
            } finally {
                runner = null
                _rateLimit.value = null
                // Auto-apply: whatever was written (even if the turn was stopped or failed) can be undone from the bar.
                val written = synchronized(turnUndo) { turnUndo.values.toList().also { turnUndo.clear() } }
                if (written.isNotEmpty()) {
                    _lastApplied.value = UndoInfo(written)
                    refreshIndex()
                }
                finishStreaming()
                _busy.value = false
                persistNow()
                refreshStatus()
                AiBackground.end(app)
                // Finished (or failed) while the app is minimized: tell the user. A Stop by the user stays silent.
                if (!cancelled && !AppVisibility.visible) {
                    val reply = _messages.value.lastOrNull { it.role == "assistant" }?.text.orEmpty()
                    AiNotifier(app).done(root.name, reply, ok = !failed && _error.value == null && _failure.value == null)
                }
            }
        }
    }

    private suspend fun providerName(): String = ProviderRegistry.byId(prefs.providerId.first()).displayName

    /** After a failed or stopped turn: drop the half-finished tool trail but keep the question (and any text streamed so far). */
    private fun rollback(startPos: Int, userText: String, partial: String, images: List<ChatImage>) {
        truncateHistory(startPos)
        history += AgentMessage.User(userText, images)
        if (partial.isNotBlank()) history += AgentMessage.Assistant(partial)
    }

    private fun finishStreaming() {
        _messages.update { l ->
            val last = l.lastOrNull()
            when {
                last == null || last.role != "assistant" || !last.streaming -> l
                last.text.isBlank() && last.steps.isEmpty() -> l.dropLast(1)
                else -> l.dropLast(1) + last.copy(
                    streaming = false,
                    steps = last.steps.map { if (it.status == StepStatus.RUNNING) it.copy(status = StepStatus.FAILED) else it },
                )
            }
        }
    }

    private suspend fun buildUserText(files: ProjectFiles, rels: List<String>, ctx: EditorContext?, text: String): String {
        val blocks = ArrayList<String>()
        var budget = 90_000
        for (rel in rels) {
            if (budget <= 0) break
            when (val r = files.read(rel)) {
                is ProjectFiles.Read.Ok -> {
                    val cut = Redactor.scrub(r.text).take(minOf(budget, 40_000))
                    budget -= cut.length
                    val tail = if (cut.length < r.text.length) "\n…[truncated; use read_file for the rest]" else ""
                    blocks += "<file path=\"$rel\">\n$cut$tail\n</file>"
                }
                is ProjectFiles.Read.Fail -> _error.value = r.message
            }
        }
        if (ctx != null) {
            val rel = files.relOf(File(ctx.absPath))
            if (!files.isBlocked(rel)) {
                if (rel !in rels && ctx.fileText != null) {
                    blocks += "<active_file path=\"$rel\">\n${Redactor.scrub(ctx.fileText).take(40_000)}\n</active_file>"
                }
                if (!ctx.selection.isNullOrBlank()) {
                    blocks += "<selection path=\"$rel\">\n${Redactor.scrub(ctx.selection).take(8_000)}\n</selection>"
                }
            }
        }
        return (blocks + text).joinToString("\n\n")
    }

    private fun updateLast(f: (UiMessage) -> UiMessage) =
        _messages.update { l -> if (l.isEmpty()) l else l.dropLast(1) + f(l.last()) }

    private val sink = object : AgentSink {
        override fun onRoundStart() {
            needSeparator = _messages.value.lastOrNull()?.text?.isNotBlank() == true
        }

        override fun onText(delta: String) {
            val d = if (needSeparator) "\n\n$delta".also { needSeparator = false } else delta
            updateLast { it.copy(text = it.text + d) }
        }

        override fun onThought(delta: String) = updateLast { m ->
            val last = m.steps.lastOrNull()
            if (last != null && last.thought) {
                m.copy(steps = m.steps.dropLast(1) + last.copy(label = last.label + delta))
            } else {
                m.copy(steps = m.steps + UiStep("t${m.steps.size}_${System.nanoTime()}", delta, StepStatus.DONE, thought = true))
            }
        }

        override fun onStepStart(id: String, label: String) =
            updateLast { it.copy(steps = it.steps + UiStep(id, label, StepStatus.RUNNING)) }

        override fun onRateLimit(info: RateLimitInfo?) {
            _rateLimit.value = info
        }

        override fun onUsage(inputTokens: Int, outputTokens: Int) {
            _contextUsed.value = inputTokens
        }

        override fun onStepEnd(id: String, ok: Boolean) = updateLast { m ->
            m.copy(steps = m.steps.map { s -> if (s.id == id) s.copy(status = if (ok) StepStatus.DONE else StepStatus.FAILED) else s })
        }
    }

    // ---- generated images: save / use as app icon ----

    private val _notice = MutableStateFlow<String?>(null)
    /** A short success message (saved, icon applied) shown as a dismissible card. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    /** Saves the picture to Pictures/Kodex (PNG) or, with [svg], the vector source to Download/Kodex. */
    fun saveImage(g: GeneratedImage, svg: Boolean) {
        viewModelScope.launch {
            val file = File(if (svg) g.svgPath ?: return@launch else g.path)
            val base = g.title.ifBlank { "kodex-image" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val ok = withContext(Dispatchers.IO) {
                AppIconApplier.saveToDevice(app, file, "$base-${System.currentTimeMillis() % 100000}." + if (svg) "svg" else "png", if (svg) "image/svg+xml" else "image/png")
            }
            if (ok) {
                _notice.value = if (svg) "Saved the SVG to Download/Kodex" else "Saved to Pictures/Kodex"
            } else {
                _error.value = "Couldn't save the image"
            }
        }
    }

    /** Installs the picture as this project's launcher icon (all densities + adaptive icon + Play Store icon). */
    fun applyAsAppIcon(g: GeneratedImage) {
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { AppIconApplier.apply(root, File(g.path)) }
                _notice.value = r.note
                refreshIndex()
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't apply the icon"
            }
        }
    }

    // ---- saved chats ----

    private fun restore(chat: StoredChat) {
        chatId = chat.id
        titleOverride = chat.title.takeIf { chat.titleLocked }
        history.clear()
        val ui = ArrayList<UiMessage>()
        chat.messages.forEach { m ->
            val pos = history.size
            val imgs = m.images.map { File(store.imageDir, it) }.filter { it.exists() }.map { ChatImage(it.absolutePath) }
            if (m.role == "user") history += AgentMessage.User(m.text, imgs)
            else if (m.text.isNotBlank()) history += AgentMessage.Assistant(m.text)
            val gens = m.generated.filter { File(store.imageDir, it.png).exists() }.map {
                GeneratedImage(
                    File(store.imageDir, it.png).absolutePath,
                    it.svg?.let { n -> File(store.imageDir, n) }?.takeIf { f -> f.exists() }?.absolutePath,
                    it.title, it.purpose,
                )
            }
            ui += UiMessage(
                nextId++, m.role, m.text,
                m.steps.map { UiStep("s${nextId++}", it.label, if (it.ok) StepStatus.DONE else StepStatus.FAILED, it.thought) },
                m.attachments, false, pos, imgs, gens,
            )
        }
        _messages.value = ui
    }

    private fun persistNow() {
        val msgs = _messages.value.filter { !it.streaming }
        val id = chatId
        if (msgs.none { it.role == "user" }) {
            viewModelScope.launch(Dispatchers.IO) {
                store.delete(id)
                _chats.value = store.list()
            }
            return
        }
        val title = titleOverride ?: msgs.first { it.role == "user" }.text.lineSequence().first().trim().take(48).ifEmpty { "Chat" }
        val chat = StoredChat(
            id, title, System.currentTimeMillis(),
            msgs.map { m ->
                StoredMessage(
                    m.role, m.text, m.attachments,
                    m.steps.map { StoredStep(it.label, it.status != StepStatus.FAILED, it.thought) },
                    m.images.map { File(it.path).name },
                    m.generated.map { StoredGenerated(File(it.path).name, it.svgPath?.let { p -> File(p).name }, it.title, it.purpose) },
                )
            },
            titleLocked = titleOverride != null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.save(chat) }
            _chats.value = store.list()
        }
    }

    fun refreshChats() {
        viewModelScope.launch { _chats.value = withContext(Dispatchers.IO) { store.list() } }
    }

    fun newChat() {
        job?.cancel()
        persistNow()
        chatId = UUID.randomUUID().toString()
        titleOverride = null
        history.clear()
        _messages.value = emptyList()
        _error.value = null
        _failure.value = null
        _contextUsed.value = 0
        _newChatEpoch.value++
    }

    fun openChat(id: String) {
        if (id == chatId) return
        job?.cancel()
        persistNow()
        _failure.value = null
        _contextUsed.value = 0
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            restore(chat)
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            if (id == chatId) {
                job?.cancel()
                chatId = UUID.randomUUID().toString()
                titleOverride = null
                history.clear()
                _messages.value = emptyList()
                _newChatEpoch.value++
            }
            refreshChats()
        }
    }

    /** Renames a saved chat (long-press in the history sheet). The new name sticks: later messages don't overwrite it. */
    fun renameChat(id: String, newTitle: String) {
        val title = newTitle.trim().take(60)
        if (title.isEmpty()) return
        if (id == chatId) titleOverride = title
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.rename(id, title) }
            refreshChats()
        }
    }

    // ---- memory & instructions (per project) ----

    /** Current text of the memory or the instructions ("" when nothing is saved yet). */
    suspend fun readDoc(doc: ProjectMemory.Doc): String = ProjectMemory.readDoc(memDir, doc)

    suspend fun writeDoc(doc: ProjectMemory.Doc, text: String): Boolean = ProjectMemory.writeDoc(memDir, doc, text)

    /** Same as [writeDoc] but survives the sheet closing (used when it is dismissed with unsaved edits). */
    fun writeDocInBackground(doc: ProjectMemory.Doc, text: String) {
        viewModelScope.launch { writeDoc(doc, text) }
    }

    /** Asks the current model to shorten an over-long memory file; null on any failure (the file is then left as is). */
    private suspend fun condenseMemory(provider: AiProvider, key: String, model: String, text: String): String? = try {
        provider.complete(
            key, model,
            AiRequest(
                messages = listOf(AiMessage("system", ProjectMemory.CONDENSE_SYSTEM), AiMessage("user", text)),
                maxOutputTokens = 4096, temperature = 0.1, model = model,
            ),
        ).text
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        null
    }

    // ---- file index ----

    fun refreshIndex() {
        viewModelScope.launch { _fileIndex.value = ProjectFiles(root, globs.value, io).index() }
    }

    // ---- staged changes: review / apply / undo ----

    fun openReview() {
        _applyError.value = null
        _reviewOpen.value = true
    }

    fun closeReview() {
        _reviewOpen.value = false
    }

    fun discardChanges() {
        pending.clear()
        _reviewOpen.value = false
        _applyError.value = null
    }

    fun dismissUndo() {
        _lastApplied.value = null
    }

    fun applyApproved(approved: List<ApprovedChange>) {
        if (approved.isEmpty()) {
            discardChanges()
            return
        }
        viewModelScope.launch {
            val stale = ArrayList<String>()
            for (a in approved) {
                val f = File(root, a.path)
                val ok = when (a.kind) {
                    ChangeKind.CREATE -> !f.exists()
                    ChangeKind.EDIT, ChangeKind.DELETE -> (if (f.exists()) io.read(f) else null) == a.baseText
                }
                if (!ok) stale += a.path
            }
            if (stale.isNotEmpty()) {
                _applyError.value = "Changed since the AI read them: ${stale.joinToString()}. Discard and ask again."
                return@launch
            }
            val done = ArrayList<UndoEntry>()
            try {
                for (a in approved) {
                    val f = File(root, a.path)
                    when (a.kind) {
                        ChangeKind.CREATE -> { io.write(f, a.finalText.orEmpty()); done += UndoEntry(a.path, null, a.finalText) }
                        ChangeKind.EDIT -> { io.write(f, a.finalText.orEmpty()); done += UndoEntry(a.path, a.baseText, a.finalText) }
                        ChangeKind.DELETE -> { io.delete(f); done += UndoEntry(a.path, a.baseText, null) }
                    }
                }
            } catch (e: Exception) {
                for (u in done.asReversed()) runCatching { restore(u) }
                _applyError.value = "Couldn't write files (${e.message}); nothing was changed"
                return@launch
            }
            pending.clear()
            _reviewOpen.value = false
            _applyError.value = null
            _lastApplied.value = UndoInfo(done)
            refreshIndex()
        }
    }

    private suspend fun restore(u: UndoEntry) {
        val f = File(root, u.path)
        val before = u.before
        if (before == null) io.delete(f) else io.write(f, before)
    }

    fun undoLast() {
        val info = _lastApplied.value ?: return
        viewModelScope.launch {
            val skipped = ArrayList<String>()
            for (e in info.entries.asReversed()) {
                val f = File(root, e.path)
                val current = if (f.exists()) io.read(f) else null
                if (current != e.after) { skipped += e.path; continue }
                restore(e)
            }
            _lastApplied.value = null
            if (skipped.isNotEmpty()) _error.value = "Not undone (changed since): ${skipped.joinToString()}"
            refreshIndex()
        }
    }

    /** The chat each project has open in this process (not persisted: a new process starts blank). */
    private object SessionChats {
        val current = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    companion object {
        /** Photos per message. */
        const val MAX_IMAGES = 4

        private val MENTION = Regex("""(^|\s)@(\S+)""")

        fun factory(app: Application, projectPath: String) = viewModelFactory {
            initializer { AgentChatViewModel(app, projectPath) }
        }

        private val sessions = java.util.concurrent.ConcurrentHashMap<String, AgentChatViewModel>()

        /**
         * One chat engine per project for the whole process. It is deliberately not tied to a screen's
         * ViewModelStore, so leaving the AI screen (or the editor panel) does not cancel an answer in progress.
         */
        fun obtain(app: Application, projectPath: String): AgentChatViewModel =
            sessions.getOrPut(projectPath) { AgentChatViewModel(app, projectPath) }
    }
}
